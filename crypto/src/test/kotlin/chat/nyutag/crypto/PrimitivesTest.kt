package chat.nyutag.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrimitivesTest {
    @Test
    fun base64Roundtrip() {
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x01)
        assertEquals("3q2+7wE", Base64.encodeUnpadded(data))
        assertEquals("3q2+7wE=", Base64.encodePadded(data))
        assertArrayEquals(data, Base64.decode("3q2+7wE"))
        assertArrayEquals(data, Base64.decode("3q2+7wE="))
        assertThrows(CryptoException.BadBase64::class.java) { Base64.decode("A") }
    }

    @Test
    fun hmacRfc4231() {
        val mac = Hash.hmacSha256("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.toHex())
    }

    @Test
    fun sha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hash.sha256("abc".toByteArray()).toHex())
    }

    @Test
    fun hkdfRfc5869() {
        val ikm = ByteArray(22) { 0x0B }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xF0 + it).toByte() }
        val okm = Hash.hkdfSha256(ikm, salt, info, 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
    }

    @Test
    fun pbkdf2Sha512() {
        val key = Hash.pbkdf2Sha512("password".toByteArray(), "salt".toByteArray(), 1, 64)
        assertTrue(key.toHex().startsWith("867f70cf1ade02cff3752599a3a53dc4"))
    }

    @Test
    fun x25519Rfc7748() {
        val alicePrivate = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexToBytes()
        val bobPrivate = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".hexToBytes()
        val bobPublic = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".hexToBytes()
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742".hexToBytes()
        val alice = Curve25519KeyPair(alicePrivate)
        val bob = Curve25519KeyPair(bobPrivate)
        assertArrayEquals(bobPublic, bob.publicKey)
        assertArrayEquals(shared, alice.sharedSecret(bob.publicKey))
        assertArrayEquals(shared, bob.sharedSecret(alice.publicKey))
    }

    @Test
    fun x25519RandomKeys() {
        val a = Curve25519KeyPair()
        val b = Curve25519KeyPair()
        assertEquals(32, a.rawRepresentation.size)
        assertEquals(43, a.publicKeyBase64.length)
        assertArrayEquals(a.sharedSecret(b.publicKey), b.sharedSecret(a.publicKey))
        val restored = Curve25519KeyPair(a.rawRepresentation)
        assertArrayEquals(a.publicKey, restored.publicKey)
        assertThrows(CryptoException.BadKeyLength::class.java) { Curve25519KeyPair(ByteArray(31)) }
        assertThrows(CryptoException.BadKeyLength::class.java) { a.sharedSecret(ByteArray(32)) }
    }

    @Test
    fun ed25519Rfc8032() {
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes()
        val key = Ed25519KeyPair(seed)
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", key.publicKey.toHex())
        val signature = key.sign(ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            signature.toHex(),
        )
        assertTrue(Ed25519KeyPair.verify(signature, ByteArray(0), key.publicKey))

        // Тест 2 из RFC 8032: проверка чужой подписи без своей подписи рядом.
        val public2 = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c".hexToBytes()
        val signature2 = ("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                          "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00").hexToBytes()
        assertTrue(Ed25519KeyPair.verify(signature2, byteArrayOf(0x72), public2))
        assertFalse(Ed25519KeyPair.verify(signature2, byteArrayOf(0x73), public2))
        assertEquals(public2.toHex(), Ed25519KeyPair("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb".hexToBytes()).publicKey.toHex())
    }

    @Test
    fun ed25519SignVerify() {
        val key = Ed25519KeyPair()
        val message = "сообщение".toByteArray()
        val signature = key.sign(message)
        assertEquals(64, signature.size)
        assertTrue(Ed25519KeyPair.verify(signature, message, key.publicKey))
        assertFalse(Ed25519KeyPair.verify(signature, "другое".toByteArray(), key.publicKey))
        val broken = signature.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertFalse(Ed25519KeyPair.verify(broken, message, key.publicKey))
        assertFalse(Ed25519KeyPair.verify(signature, message, Ed25519KeyPair().publicKey))
    }

    @Test
    fun aesCbcLengths() {
        val key = Random.bytes(32)
        val iv = Random.bytes(16)
        for (length in listOf(0, 1, 15, 16, 17, 100)) {
            val plaintext = Random.bytes(length)
            val ciphertext = Aes.cbcEncrypt(key, iv, plaintext)
            assertEquals((length / 16 + 1) * 16, ciphertext.size)
            assertArrayEquals(plaintext, Aes.cbcDecrypt(key, iv, ciphertext))
        }
        assertThrows(CryptoException.BadKeyLength::class.java) { Aes.cbcEncrypt(ByteArray(16), iv, ByteArray(1)) }
    }

    @Test
    fun aesCtrNistF55() {
        val key = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4".hexToBytes()
        val iv = "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff".hexToBytes()
        val plaintext = "6bc1bee22e409f96e93d7e117393172a".hexToBytes()
        val ciphertext = Aes.ctr(key, iv, plaintext)
        assertEquals("601ec313775789a5b7a7f504bbf3d228", ciphertext.toHex())
        assertArrayEquals(plaintext, Aes.ctr(key, iv, ciphertext))
    }

    @Test
    fun constantTimeEquals() {
        assertTrue(byteArrayOf(1, 2, 3).constantTimeEquals(byteArrayOf(1, 2, 3)))
        assertFalse(byteArrayOf(1, 2, 3).constantTimeEquals(byteArrayOf(1, 2, 4)))
        assertFalse(byteArrayOf(1, 2, 3).constantTimeEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun bigEndian() {
        assertArrayEquals(byteArrayOf(0, 0, 1, 2), 258u.toBigEndianBytes())
        assertEquals(258u, byteArrayOf(0, 0, 1, 2).bigEndianUInt())
        assertEquals(0xFFFFFFFFu, byteArrayOf(9, -1, -1, -1, -1).bigEndianUInt(1))
        assertEquals(0x80000000u, byteArrayOf(-128, 0, 0, 0).bigEndianUInt())
    }
}
