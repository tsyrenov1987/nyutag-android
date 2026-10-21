// Нютаг. Медиа: адрес `mxc://сервер/идентификатор`, загрузка на сервер, скачивание и миниатюры, кэш файлов на диске.

package chat.nyutag.core.media

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixError
import chat.nyutag.crypto.Hash
import chat.nyutag.json.JsonValue
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MXC(val server: String, val mediaID: String) {
    override fun toString(): String = "mxc://$server/$mediaID"

    companion object {
        const val PREFIX = "mxc://"

        /** `null`, если строка не адрес: нет префикса, нет сервера или идентификатора, лишний `/`. */
        fun parse(string: String): MXC? {
            if (!string.startsWith(PREFIX)) return null
            val rest = string.substring(PREFIX.length)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val mediaID = rest.substring(slash + 1)
            if (mediaID.isEmpty() || mediaID.contains('/')) return null
            return MXC(rest.substring(0, slash), mediaID)
        }
    }
}

enum class ThumbnailMethod(val rawValue: String) { CROP("crop"), SCALE("scale") }

// MARK: - Сервер: загрузка, скачивание и миниатюры через авторизованный `/_matrix/client/v1/media`

/** Загрузка файла. Возвращает `content_uri`. */
suspend fun MatrixClient.uploadMedia(data: ByteArray, contentType: String, filename: String? = null): MXC {
    val query = if (filename != null) mapOf("filename" to filename) else emptyMap()
    val response = upload("/_matrix/media/v3/upload", query, data, contentType)
    val uri = try {
        JsonValue.parse(String(response, Charsets.UTF_8))["content_uri"]?.string
    } catch (_: Exception) {
        null
    }
    return uri?.let { MXC.parse(it) } ?: throw MatrixError(MatrixError.Kind.Decoding("upload: нет content_uri"))
}

suspend fun MatrixClient.downloadMedia(mxc: MXC): ByteArray =
    data(MatrixClient.Method.GET, "/_matrix/client/v1/media/download/${mxc.server}/${mxc.mediaID}")

suspend fun MatrixClient.thumbnail(mxc: MXC, width: Int, height: Int, method: ThumbnailMethod = ThumbnailMethod.SCALE): ByteArray =
    data(MatrixClient.Method.GET, "/_matrix/client/v1/media/thumbnail/${mxc.server}/${mxc.mediaID}",
         query = mapOf("width" to width.toString(), "height" to height.toString(), "method" to method.rawValue))

/** Лимит размера загрузки (`m.upload.size`), если сервер его сообщает. */
suspend fun MatrixClient.mediaUploadLimit(): Int? =
    raw(MatrixClient.Method.GET, "/_matrix/client/v1/media/config")["m.upload.size"]?.int

/** Шифрует и грузит файл (на сервере он лежит как application/octet-stream; настоящие имя и тип — в `body`
 *  и `info.mimetype` события, на сервер они не уходят: утекли бы открытыми). Возвращает описание для поля `file`. */
suspend fun MatrixClient.uploadEncrypted(data: ByteArray): EncryptedFile {
    val sealed = EncryptedFile.encrypt(data)
    val mxc = uploadMedia(sealed.ciphertext, "application/octet-stream")
    return sealed.file.copy(url = mxc.toString())
}

/** Скачивает и расшифровывает вложение. */
suspend fun MatrixClient.downloadDecrypted(file: EncryptedFile): ByteArray {
    val mxc = file.mxc ?: throw MatrixError(MatrixError.Kind.Decoding("EncryptedFile: плохой url"))
    return file.decrypt(downloadMedia(mxc))
}

// MARK: - Кэш на диске

/** Кэш медиа в каталоге (в приложении: cacheDir, чтобы сервис пушей видел аватары).
 *  Ключ — SHA-256 от адреса и размера миниатюры; сверх лимита по объёму вытесняются файлы, к которым дольше не обращались. */
class MediaCache(private val client: MatrixClient, val directory: File, val byteLimit: Long = 300L * 1024 * 1024) {
    data class Size(val width: Int, val height: Int)

    private val mutex = Mutex()
    private val inFlight = HashMap<String, CompletableDeferred<ByteArray>>()

    init {
        directory.mkdirs()
    }

    /** Файл целиком или миниатюра (`crop` — вырезать квадрат, как для аватара). Повторные запросы того же ключа ждут первый. */
    suspend fun data(mxc: MXC, size: Size? = null, method: ThumbnailMethod = ThumbnailMethod.SCALE): ByteArray {
        val key = key(mxc, size, method)
        val file = File(directory, key)
        val (waiter, mine) = mutex.withLock {
            withContext(Dispatchers.IO) { readTouched(file) }?.let { return it }
            inFlight[key]?.let { it to false } ?: (CompletableDeferred<ByteArray>().also { inFlight[key] = it } to true)
        }
        if (!mine) return waiter.await()
        try {
            val bytes = if (size != null) client.thumbnail(mxc, size.width, size.height, method) else client.downloadMedia(mxc)
            write(file, bytes)
            waiter.complete(bytes)
            return bytes
        } catch (e: Throwable) {
            waiter.completeExceptionally(e)
            throw e
        } finally {
            mutex.withLock { inFlight.remove(key) }
        }
    }

    /** Расшифрованное вложение. В кэше на диске лежит ШИФРТЕКСТ (тот же ключ, что у [data]): иначе открытый текст
     *  зашифрованной комнаты осел бы в кэше. Цена — повторная расшифровка AES-CTR при открытии; целостность проверяется каждый раз. */
    suspend fun decrypted(file: EncryptedFile): ByteArray {
        val mxc = file.mxc ?: throw MatrixError(MatrixError.Kind.Decoding("EncryptedFile: плохой url"))
        return file.decrypt(data(mxc))
    }

    /** Положить свой только что загруженный файл, чтобы не качать его обратно. */
    suspend fun store(data: ByteArray, mxc: MXC) = write(File(directory, key(mxc, null)), data)

    suspend fun totalBytes(): Long = mutex.withLock { files().sumOf { it.length() } }

    suspend fun clear() = mutex.withLock { withContext(Dispatchers.IO) { files().forEach { it.delete() } } }

    private fun readTouched(file: File): ByteArray? {
        if (!file.isFile) return null
        return try {
            file.readBytes().also { file.setLastModified(System.currentTimeMillis()) }
        } catch (_: java.io.IOException) {
            null
        }
    }

    /** Запись через временный файл и переименование: оборванная запись не оставит половину файла под ключом. */
    private suspend fun write(file: File, bytes: ByteArray) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val temporary = File(directory, "${file.name}.part")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
            trim()
        }
    }

    private fun files(): List<File> = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: emptyList()

    private fun trim() {
        val all = files().map { Triple(it, it.length(), it.lastModified()) }
        var total = all.sumOf { it.second }
        if (total <= byteLimit) return
        for ((file, length, _) in all.sortedBy { it.third }) {
            if (total <= byteLimit) break
            if (file.delete()) total -= length
        }
    }

    companion object {
        internal fun key(mxc: MXC, size: Size?, method: ThumbnailMethod = ThumbnailMethod.SCALE): String {
            var source = mxc.toString()
            if (size != null) source += "#${size.width}x${size.height}"
            if (size != null && method == ThumbnailMethod.CROP) source += "/crop"
            return Hash.sha256(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
