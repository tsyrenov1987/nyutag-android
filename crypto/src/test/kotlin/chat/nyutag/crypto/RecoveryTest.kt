package chat.nyutag.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecoveryTest {
    @Test
    fun recoveryKeyRoundtrip() {
        val key = Random.bytes(32)
        val code = RecoveryKey.encode(key)
        assertTrue(code.startsWith("Es"), code)
        assertTrue(code.split(" ").all { it.length <= 4 })
        assertArrayEquals(key, RecoveryKey.decode(code))
        assertArrayEquals(key, RecoveryKey.decode(code.replace(" ", "")))

        val compact = code.replace(" ", "")
        val typo = compact.substring(0, 10) + (if (compact[10] == 'a') 'b' else 'a') + compact.substring(11)
        assertThrows(CryptoException.BadMessage::class.java) { RecoveryKey.decode(typo) }
        assertThrows(CryptoException.BadMessage::class.java) { RecoveryKey.decode("Es0OIl") }
    }

    @Test
    fun base58() {
        assertEquals("2NEpo7TZRRrLZSi2U", RecoveryKey.base58Encode("Hello World!".toByteArray()))
        assertArrayEquals("Hello World!".toByteArray(), RecoveryKey.base58Decode("2NEpo7TZRRrLZSi2U"))
        assertEquals("112", RecoveryKey.base58Encode(byteArrayOf(0, 0, 1)))
        assertArrayEquals(byteArrayOf(0, 0, 1), RecoveryKey.base58Decode("112"))
    }

    @Test
    fun secretStorage() {
        val key = Random.bytes(32)
        val secret = Base64.encodePadded(Random.bytes(32)).toByteArray()
        val encrypted = SecretStorage.encrypt(secret, "m.megolm_backup.v1", key)
        assertArrayEquals(secret, SecretStorage.decrypt(encrypted, "m.megolm_backup.v1", key))
        assertThrows(CryptoException.BadMAC::class.java) { SecretStorage.decrypt(encrypted, "m.cross_signing.master", key) }
        assertThrows(CryptoException.BadMAC::class.java) { SecretStorage.decrypt(encrypted, "m.megolm_backup.v1", Random.bytes(32)) }

        val check = SecretStorage.encrypt(ByteArray(32), "", key)
        assertTrue(SecretStorage.check(key, check.iv, check.mac))
        assertFalse(SecretStorage.check(Random.bytes(32), check.iv, check.mac))
        assertFalse(SecretStorage.check(key, "***", check.mac))
    }

    @Test
    fun passphrase() {
        val key = RecoveryKey.fromPassphrase("password", "salt", 1, 512)
        assertTrue(key.toHex().startsWith("867f70cf1ade02cff3752599a3a53dc4"))
        assertEquals(32, RecoveryKey.fromPassphrase("пароль", "соль", 10).size)
    }

    @Test
    fun backupRoundtrip() {
        val backup = BackupKey()
        val plaintext = "{\"algorithm\":\"m.megolm.v1.aes-sha2\",\"session_key\":\"AQ\"}".toByteArray()
        val record = BackupKey.encrypt(plaintext, backup.publicKeyBase64)
        assertArrayEquals(plaintext, backup.decrypt(record))
        assertThrows(CryptoException.BadMAC::class.java) { BackupKey().decrypt(record) }
        val restored = BackupKey(backup.keyPair.rawRepresentation)
        assertEquals(backup.publicKeyBase64, restored.publicKeyBase64)
        assertArrayEquals(plaintext, restored.decrypt(record))
    }
}
