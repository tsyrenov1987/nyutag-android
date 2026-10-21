// Нютаг. Файлы: адрес mxc, содержимое вложений туда и обратно, миниатюры, метка «один просмотр».

package chat.nyutag.core

import chat.nyutag.core.media.Attachment
import chat.nyutag.core.media.EncryptedFile
import chat.nyutag.core.media.EncryptedFileKey
import chat.nyutag.core.media.MXC
import chat.nyutag.core.media.MediaCache
import chat.nyutag.core.media.ThumbnailMethod
import chat.nyutag.core.media.downloadDecrypted
import chat.nyutag.core.media.downloadMedia
import chat.nyutag.core.media.mediaUploadLimit
import chat.nyutag.core.media.uploadEncrypted
import chat.nyutag.core.media.uploadMedia
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.crypto.CryptoException
import chat.nyutag.json.JsonValue
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaTest {
    /** Описание зашифрованного файла без самого шифрования: формат проверяем отдельно от крипто. */
    private fun sealed(url: String, tag: String): EncryptedFile = EncryptedFile(
        url, EncryptedFileKey(k = "k-$tag"), iv = "iv-$tag", hashes = mapOf("sha256" to "h-$tag"),
    )

    @Test fun parsesMXC() {
        val mxc = MXC.parse("mxc://nyutag.chat/AbC123")
        assertEquals("nyutag.chat", mxc?.server)
        assertEquals("AbC123", mxc?.mediaID)
        assertEquals("mxc://nyutag.chat/AbC123", mxc?.toString())
        assertNull(MXC.parse("https://nyutag.chat/AbC"))
        assertNull(MXC.parse("mxc://nyutag.chat/"))
        assertNull(MXC.parse("mxc://nyutag.chat/a/b"))
    }

    @Test fun encryptedFileFormat() {
        val file = sealed("mxc://nyutag.chat/F1", "a")
        assertEquals(file, EncryptedFile.from(file.json))
        assertEquals(MXC("nyutag.chat", "F1"), file.mxc)
        assertEquals("A256CTR", file.json.at("key", "alg")?.string)
        assertEquals("v2", file.json["v"]?.string)
        assertNull(EncryptedFile.from(JsonValue.of("url" to "mxc://x/y")))
        assertNull(EncryptedFile.from(null))
    }

    @Test fun attachmentRoundTrip() {
        val file = sealed("mxc://nyutag.chat/F1", "f")
        val content = Attachment.content(Attachment.Source.Sealed(file), "photo.jpg", "image/jpeg", 12, width = 640, height = 480)
        assertEquals("m.image", content["msgtype"]?.string)
        assertNull(content["url"])
        val read = Attachment.read(content)!!
        assertEquals(Attachment.Source.Sealed(file), read.source)
        assertEquals("photo.jpg", read.name)
        assertEquals(640, read.width); assertEquals(480, read.height); assertEquals(12, read.size)
        assertTrue(read.isImage)

        val plain = Attachment.content(Attachment.Source.Plain(MXC.parse("mxc://nyutag.chat/D2")!!), "отчёт.pdf", "application/pdf", 900)
        assertEquals("m.file", plain["msgtype"]?.string)
        assertEquals(Attachment.Source.Plain(MXC.parse("mxc://nyutag.chat/D2")!!), Attachment.read(plain)?.source)
        assertEquals(false, Attachment.read(plain)?.isImage)
    }

    @Test fun videoWithThumbnail() {
        val clip = sealed("mxc://nyutag.chat/V9", "v")
        val thumb = sealed("mxc://nyutag.chat/T9", "t")
        val preview = Attachment.Preview(Attachment.Source.Sealed(thumb), size = 4, width = 320, height = 180)
        val content = Attachment.content(Attachment.Source.Sealed(clip), "clip.mp4", "video/mp4", 5, width = 1280, height = 720, preview = preview, duration = 2000)
        assertEquals("m.video", content["msgtype"]?.string)
        assertEquals(2000, content.at("info", "duration")?.int)
        assertEquals(320, content.at("info", "thumbnail_info", "w")?.int)
        assertEquals("image/jpeg", content.at("info", "thumbnail_info", "mimetype")?.string)
        val read = Attachment.read(content)!!
        assertTrue(read.isVideo); assertFalse(read.isImage)
        assertEquals(Attachment.Source.Sealed(thumb), read.thumbnail)
        assertEquals(2000, read.duration)
        assertEquals(1280, read.width); assertEquals(720, read.height)

        // Открытый чат: миниатюра по адресу.
        val plain = Attachment.content(
            Attachment.Source.Plain(MXC.parse("mxc://nyutag.chat/V8")!!), "clip.mp4", "video/mp4", 5,
            preview = Attachment.Preview(Attachment.Source.Plain(MXC.parse("mxc://nyutag.chat/T8")!!), size = 4),
        )
        assertEquals("mxc://nyutag.chat/T8", plain.at("info", "thumbnail_url")?.string)
        assertEquals(Attachment.Source.Plain(MXC.parse("mxc://nyutag.chat/T8")!!), Attachment.read(plain)?.thumbnail)
        assertNull(Attachment.read(plain)?.duration)
    }

    @Test fun viewOnceMark() {
        val mxc = MXC.parse("mxc://nyutag.chat/V1")!!
        val once = Attachment.content(Attachment.Source.Plain(mxc), "photo.jpg", "image/jpeg", 5, viewOnce = true)
        assertEquals(JsonValue.Bool(true), once["chat.nyutag.view_once"])
        assertEquals(true, Attachment.read(once)?.viewOnce)
        // Как пишет старый Android: метка рядом с обычными полями.
        val android = JsonValue.parse("""{"msgtype":"m.image","body":"a.jpg","url":"mxc://nyutag.chat/V1","chat.nyutag.view_once":true}""")
        assertEquals(true, Attachment.read(android)?.viewOnce)
        val plain = Attachment.content(Attachment.Source.Plain(mxc), "photo.jpg", "image/jpeg", 5)
        assertNull(plain["chat.nyutag.view_once"])
        assertEquals(false, Attachment.read(plain)?.viewOnce)
    }

    @Test fun attachmentFromForeignClient() {
        // Чужой клиент: без filename, с миниатюрой по открытому адресу; GIF показываем файлом.
        val content = JsonValue.parse(
            """{"msgtype":"m.image","body":"cat.gif","url":"mxc://s/G","info":{"mimetype":"image/gif","thumbnail_url":"mxc://s/T","w":10,"h":20}}""",
        )
        val read = Attachment.read(content)!!
        assertEquals("cat.gif", read.name)
        assertEquals(Attachment.Source.Plain(MXC.parse("mxc://s/T")!!), read.thumbnail)
        assertFalse(read.isImage)
        assertNull(Attachment.read(JsonValue.parse("""{"msgtype":"m.image","body":"x"}""")))
        assertNull(Attachment.read(JsonValue.parse("""{"msgtype":"m.text","body":"x","url":"mxc://s/G"}""")))
    }

    private fun tempDir(): File = Files.createTempDirectory("nyutag-media").toFile()

    @Test fun cacheKeysDifferBySize() {
        val mxc = MXC.parse("mxc://x/y")!!
        val full = MediaCache.key(mxc, null)
        assertEquals(64, full.length)
        val small = MediaCache.key(mxc, MediaCache.Size(96, 96))
        assertTrue(full != small)
        assertTrue(small != MediaCache.key(mxc, MediaCache.Size(320, 320)))
        assertTrue(small != MediaCache.key(mxc, MediaCache.Size(96, 96), ThumbnailMethod.CROP))
        assertEquals(full, MediaCache.key(mxc, null, ThumbnailMethod.CROP))
    }

    @Test fun cacheEvictsOldestOverLimit() = runBlocking {
        val dir = tempDir()
        try {
            // Сервер по адресу :9 не отвечает: всё, что читается, берётся из кэша.
            val cache = MediaCache(MatrixClient(URI("http://127.0.0.1:9")), dir, byteLimit = 250)
            cache.store(ByteArray(100) { 1 }, MXC.parse("mxc://x/a")!!)
            File(dir, MediaCache.key(MXC.parse("mxc://x/a")!!, null)).setLastModified(System.currentTimeMillis() - 60_000)
            cache.store(ByteArray(100) { 2 }, MXC.parse("mxc://x/b")!!)
            assertEquals(200, cache.totalBytes())
            cache.store(ByteArray(100) { 3 }, MXC.parse("mxc://x/c")!!)
            assertTrue(cache.totalBytes() <= 250)
            assertFalse(File(dir, MediaCache.key(MXC.parse("mxc://x/a")!!, null)).exists())
            assertEquals(3.toByte(), cache.data(MXC.parse("mxc://x/c")!!).first())
            cache.clear()
            assertEquals(0, cache.totalBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun uploadAndDownload() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(ALICE, "A1"))
        val mxc = client.uploadMedia("снимок".toByteArray(), "image/jpeg", "photo.jpg")
        assertEquals("nyutag.chat", mxc.server)
        assertEquals("image/jpeg", server.mediaTypes[mxc.mediaID])
        assertEquals("снимок", String(client.downloadMedia(mxc)))
        assertEquals(50_000_000, client.mediaUploadLimit())
    }

    @Test fun encryptedUploadLeavesOnlyCiphertext() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(ALICE, "A1"))
        val secret = "тайный снимок".toByteArray()
        val file = client.uploadEncrypted(secret)
        val id = requireNotNull(file.mxc).mediaID
        assertEquals("application/octet-stream", server.mediaTypes[id])
        assertFalse(server.media[id]!!.contentEquals(secret))
        assertArrayEquals(secret, client.downloadDecrypted(file))
        // Сервер подменил файл: расшифровки нет.
        server.media[id] = server.media[id]!!.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(CryptoException.BadMAC, runCatching { client.downloadDecrypted(file) }.exceptionOrNull())
    }

    @Test fun cacheKeepsCiphertextAndDownloadsOnce() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(ALICE, "A1"))
        val dir = tempDir()
        try {
            val cache = MediaCache(client, dir)
            val secret = "в кэше только шифртекст".toByteArray()
            val file = client.uploadEncrypted(secret)
            val results = List(5) { async { cache.decrypted(file) } }.awaitAll()
            results.forEach { assertArrayEquals(secret, it) }
            assertEquals(1, server.mediaRequests.size)
            val onDisk = File(dir, MediaCache.key(file.mxc!!, null)).readBytes()
            assertArrayEquals(server.media[file.mxc!!.mediaID], onDisk)
            assertFalse(onDisk.contentEquals(secret))

            // Миниатюра — свой ключ кэша и свой запрос с размерами.
            cache.data(file.mxc!!, MediaCache.Size(96, 96), ThumbnailMethod.CROP)
            assertEquals(2, server.mediaRequests.size)
            assertTrue(server.mediaRequests.last().contains("thumbnail") && server.mediaRequests.last().contains("method=crop"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
