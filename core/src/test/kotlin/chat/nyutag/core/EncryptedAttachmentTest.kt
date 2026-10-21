// Нютаг. Зашифрованные вложения (EncryptedFile v2): шифрование туда и обратно, отказ испорченным, формат JSON.

package chat.nyutag.core

import chat.nyutag.core.media.EncryptedFile
import chat.nyutag.crypto.Base64
import chat.nyutag.crypto.CryptoException
import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EncryptedAttachmentTest {
    private val sample = ByteArray(1000) { (it % 251).toByte() }

    private fun sealed() = EncryptedFile.encrypt(sample, url = "mxc://x/y")

    private fun failure(block: () -> Unit): Throwable? = runCatching(block).exceptionOrNull()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun roundTrip() {
        val s = sealed()
        assertFalse(s.ciphertext.contentEquals(sample))
        assertEquals(sample.size, s.ciphertext.size)
        assertArrayEquals(sample, s.file.decrypt(s.ciphertext))
        val empty = EncryptedFile.encrypt(ByteArray(0))
        assertArrayEquals(ByteArray(0), empty.file.decrypt(empty.ciphertext))
    }

    @Test fun freshKeyAndIVEachTime() {
        val a = sealed().file
        val b = sealed().file
        assertNotEquals(a.key.k, b.key.k)
        assertNotEquals(a.iv, b.iv)
    }

    @Test fun rejectsTamperedHash() {
        val s = sealed()
        val zero = s.file.copy(hashes = mapOf("sha256" to Base64.encodeUnpadded(ByteArray(32))))
        assertEquals(CryptoException.BadMAC, failure { zero.decrypt(s.ciphertext) })
        assertTrue(failure { s.file.copy(hashes = emptyMap()).decrypt(s.ciphertext) } is CryptoException)
    }

    @Test fun rejectsTamperedByte() {
        val s = sealed()
        val cipher = s.ciphertext.copyOf()
        cipher[500] = (cipher[500].toInt() xor 1).toByte()
        assertEquals(CryptoException.BadMAC, failure { s.file.decrypt(cipher) })
    }

    @Test fun rejectsWrongVersionAndAlg() {
        val s = sealed()
        assertTrue(failure { s.file.copy(v = "v1").decrypt(s.ciphertext) } is CryptoException)
        assertTrue(failure { s.file.copy(key = s.file.key.copy(alg = "A128CTR")).decrypt(s.ciphertext) } is CryptoException)
        assertTrue(failure { s.file.copy(key = s.file.key.copy(kty = "RSA")).decrypt(s.ciphertext) } is CryptoException)
    }

    @Test fun jsonFormat() {
        val json = sealed().file.json
        assertEquals(setOf("url", "key", "iv", "hashes", "v"), json.obj?.keys)
        assertEquals("v2", json["v"]?.string)
        assertEquals("mxc://x/y", json["url"]?.string)
        val key = requireNotNull(json["key"])
        assertEquals(setOf("kty", "key_ops", "alg", "k", "ext"), key.obj?.keys)
        assertEquals("oct", key["kty"]?.string)
        assertEquals("A256CTR", key["alg"]?.string)
        assertEquals(true, key["ext"]?.bool)
        assertEquals(listOf("encrypt", "decrypt"), key["key_ops"]?.array?.mapNotNull { it.string })
        // k: base64url без набивки, 32 байта = 43 символа, без «+», «/», «=».
        val k = requireNotNull(key["k"]?.string)
        assertEquals(43, k.length)
        assertFalse(k.any { it in "+/=" })
        assertEquals(32, EncryptedFile.decodeBase64URL(k).size)
        // iv: 16 байт, без набивки, последние 8 нулевые.
        val iv = requireNotNull(json["iv"]?.string)
        assertFalse(iv.contains("="))
        val ivBytes = Base64.decode(iv)
        assertEquals(16, ivBytes.size)
        assertArrayEquals(ByteArray(8), ivBytes.copyOfRange(8, 16))
        val hash = requireNotNull(json.at("hashes", "sha256")?.string)
        assertFalse(hash.contains("="))
        assertEquals(32, Base64.decode(hash).size)
    }

    @Test fun base64URLUsesUrlAlphabet() {
        val data = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xfe.toByte(), 0xfa.toByte(), 0xff.toByte())
        val s = EncryptedFile.base64URL(data)
        assertFalse(s.contains("+") || s.contains("/"))
        assertTrue(s.contains("-") || s.contains("_"))
        assertArrayEquals(data, EncryptedFile.decodeBase64URL(s))
    }

    /** Вектор NIST SP 800-38A, F.5.5 (CTR-AES256.Encrypt, первый блок): iv идёт в CTR как начальный 16-байтный счётчик. */
    @Test fun nistCTRVector() {
        val key = hex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
        val iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val plain = hex("6bc1bee22e409f96e93d7e117393172a")
        val s = EncryptedFile.encrypt(plain, key, iv, "mxc://x/y")
        assertArrayEquals(hex("601ec313775789a5b7a7f504bbf3d228"), s.ciphertext)
        assertArrayEquals(plain, s.file.decrypt(s.ciphertext))
    }

    @Test fun contentUsesFileInsteadOfURL() {
        val file = sealed().file
        val thumb = EncryptedFile.encrypt(byteArrayOf(1, 2, 3), url = "mxc://x/t").file
        val content = file.content("m.image", "cat.png",
                                   mapOf("mimetype" to JsonValue.Str("image/png"), "thumbnail_url" to JsonValue.Str("mxc://leak/1")),
                                   thumb)
        assertEquals("m.image", content["msgtype"]?.string)
        assertNull(content["url"])
        assertEquals("mxc://x/y", content.at("file", "url")?.string)
        assertEquals("image/png", content.at("info", "mimetype")?.string)
        assertNull(content.at("info", "thumbnail_url"))
        assertEquals("mxc://x/t", content.at("info", "thumbnail_file", "url")?.string)
        assertEquals(file, EncryptedFile.from(content["file"]))
    }
}
