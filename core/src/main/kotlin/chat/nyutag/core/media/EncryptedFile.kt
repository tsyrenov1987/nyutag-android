// Нютаг. Описание зашифрованного вложения (`file` в содержимом реплики): адрес, ключ AES-CTR, iv и хеш шифртекста.
// Формат по спецификации Matrix (EncryptedFile v2), общий со всеми клиентами. Файл шифруется AES-256-CTR на устройстве,
// на сервер уходит только шифртекст как application/octet-stream, а ключ, iv и SHA-256 шифртекста едут внутри
// (зашифрованного Megolm) события.

package chat.nyutag.core.media

import chat.nyutag.crypto.Aes
import chat.nyutag.crypto.Base64
import chat.nyutag.crypto.CryptoException
import chat.nyutag.crypto.Hash
import chat.nyutag.crypto.Random
import chat.nyutag.crypto.constantTimeEquals
import chat.nyutag.json.JsonValue

data class EncryptedFileKey(
    val kty: String = "oct",
    val keyOps: List<String> = listOf("encrypt", "decrypt"),
    val alg: String = "A256CTR",
    /** Ключ в base64url без `=`. */
    val k: String,
    val ext: Boolean = true,
) {
    val json: JsonValue get() = JsonValue.of("kty" to kty, "key_ops" to keyOps, "alg" to alg, "k" to k, "ext" to ext)

    companion object {
        fun from(json: JsonValue?): EncryptedFileKey? {
            val kty = json?.get("kty")?.string ?: return null
            val ops = json["key_ops"]?.array?.mapNotNull { it.string } ?: return null
            val alg = json["alg"]?.string ?: return null
            val k = json["k"]?.string ?: return null
            val ext = json["ext"]?.bool ?: return null
            return EncryptedFileKey(kty, ops, alg, k, ext)
        }
    }
}

data class EncryptedFile(
    val url: String,
    val key: EncryptedFileKey,
    /** Unpadded base64, 16 байт: 8 случайных + 8 нулевых (нулевая половина — счётчик блоков CTR). */
    val iv: String,
    /** `{"sha256": <unpadded base64 от шифртекста>}`. */
    val hashes: Map<String, String>,
    val v: String = "v2",
) {
    val mxc: MXC? get() = MXC.parse(url)

    val json: JsonValue get() = JsonValue.of("url" to url, "key" to key.json, "iv" to iv, "hashes" to hashes, "v" to v)

    /** Расшифровка. Сначала сверяется SHA-256 шифртекста (за постоянное время), `v == "v2"` и `alg == "A256CTR"`,
     *  только потом CTR: испорченный файл не расшифровывается вовсе. */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        if (v != "v2") throw CryptoException.BadMessage("EncryptedFile: версия $v, нужна v2")
        if (key.alg != "A256CTR" || key.kty != "oct") throw CryptoException.BadMessage("EncryptedFile: алгоритм ${key.alg}")
        val expected = hashes["sha256"] ?: throw CryptoException.BadMessage("EncryptedFile: нет hashes.sha256")
        if (!Hash.sha256(ciphertext).constantTimeEquals(Base64.decode(expected))) throw CryptoException.BadMAC
        return Aes.ctr(decodeBase64URL(key.k), Base64.decode(iv), ciphertext)
    }

    /** Содержимое m.image / m.file и т. п. для зашифрованной комнаты: `file` вместо `url`, при наличии
     *  `info.thumbnail_file` вместо `thumbnail_url`. `info` — остальные поля (mimetype, size, w, h…). */
    fun content(msgtype: String, body: String, info: Map<String, JsonValue> = emptyMap(),
                thumbnail: EncryptedFile? = null): JsonValue {
        val fields = LinkedHashMap(info)
        fields.remove("thumbnail_url")
        if (thumbnail != null) fields["thumbnail_file"] = thumbnail.json
        return JsonValue.of("msgtype" to msgtype, "body" to body, "file" to json, "info" to JsonValue.Obj(fields))
    }

    /** Шифртекст и описание файла; `url` проставляется после загрузки. */
    class Sealed(val ciphertext: ByteArray, val file: EncryptedFile)

    companion object {
        /** Шифрование: случайный 32-байтовый ключ, случайные 8 байт iv + 8 нулевых. */
        fun encrypt(plaintext: ByteArray, url: String = ""): Sealed =
            encrypt(plaintext, Random.bytes(32), Random.bytes(8) + ByteArray(8), url)

        /** Шифрование с заданными ключом и iv (для тестов и воспроизводимых векторов). */
        internal fun encrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray, url: String): Sealed {
            val ciphertext = Aes.ctr(key, iv, plaintext)
            val file = EncryptedFile(url, EncryptedFileKey(k = base64URL(key)), Base64.encodeUnpadded(iv),
                                     mapOf("sha256" to Base64.encodeUnpadded(Hash.sha256(ciphertext))))
            return Sealed(ciphertext, file)
        }

        /** base64url без набивки (RFC 4648 §5), как требует JWK. */
        internal fun base64URL(data: ByteArray): String = Base64.encodeUnpadded(data).replace('+', '-').replace('/', '_')

        internal fun decodeBase64URL(string: String): ByteArray = Base64.decode(string.replace('-', '+').replace('_', '/'))

        /** Строго по формату: любого обязательного поля нет — `null`. */
        fun from(json: JsonValue?): EncryptedFile? {
            val url = json?.get("url")?.string ?: return null
            val key = EncryptedFileKey.from(json["key"]) ?: return null
            val iv = json["iv"]?.string ?: return null
            val hashesJson = json["hashes"]?.obj ?: return null
            val hashes = LinkedHashMap<String, String>()
            for ((name, value) in hashesJson) hashes[name] = value.string ?: return null
            val v = json["v"]?.string ?: return null
            return EncryptedFile(url, key, iv, hashes, v)
        }
    }
}
