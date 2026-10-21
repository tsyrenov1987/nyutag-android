// Нютаг. Включение восстановления с нуля: кросс-подпись, бэкап, SSSS и recovery-код на поддельном сервере.

package chat.nyutag.core

import chat.nyutag.core.crypto.E2EEException
import chat.nyutag.core.crypto.bootstrapCrossSigning
import chat.nyutag.core.crypto.crossSigningPublicKey
import chat.nyutag.core.crypto.cryptoBlob
import chat.nyutag.core.crypto.devices
import chat.nyutag.core.crypto.enableRecovery
import chat.nyutag.core.crypto.importCrossSigning
import chat.nyutag.core.crypto.sendEncrypted
import chat.nyutag.core.crypto.setCryptoBlob
import chat.nyutag.core.crypto.sharedSecretNames
import chat.nyutag.core.crypto.uploadRoomKeysToBackup
import chat.nyutag.core.sync.EventType
import chat.nyutag.crypto.Base64
import chat.nyutag.crypto.BackupKey
import chat.nyutag.crypto.Ed25519KeyPair
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecoveryTest {
    /** Подпись `auth_data` ключом `ed25519:<keyID>` сходится с открытым ключом. */
    private fun signatureValid(obj: JsonValue, user: String, keyID: String, publicKey: String): Boolean {
        val signature = obj.at("signatures", user, "ed25519:$keyID")?.string ?: return false
        val fields = LinkedHashMap(obj.obj ?: return false).apply { remove("signatures") }
        return Ed25519KeyPair.verify(Base64.decode(signature), JsonValue.Obj(fields).canonical().toByteArray(), Base64.decode(publicKey))
    }

    @Test fun recoveryFromScratchOpensHistoryOnNewDevice() = runBlocking {
        val server = FakeHomeserver()
        server.requirePassword = "pw"
        val alice = server.cryptoDevice(ALICE, "A1")
        val bob = server.cryptoDevice(BOB, "B1")
        server.setMembers(listOf(ALICE, BOB), TEST_ROOM)
        bob.machine.sendEncrypted(TEST_ROOM, EventType.MESSAGE, text("до бэкапа"))
        assertEquals(listOf("до бэкапа"), server.read(alice))

        // Без пароля сервер не даст завести кросс-подпись.
        assertTrue(runCatching { alice.machine.enableRecovery(null) }.exceptionOrNull() is E2EEException)
        val code = alice.machine.enableRecovery("pw")
        assertTrue(code.startsWith("Es"))
        val master = requireNotNull(server.crossSigningKey(ALICE, "master")?.let { crossSigningPublicKey(it, ALICE, "master") })
        assertEquals(1, server.backup[TEST_ROOM]?.size)
        val authData = requireNotNull(server.backupAuthData)
        assertTrue(signatureValid(authData, ALICE, "A1", alice.machine.ed25519Key))
        assertTrue(signatureValid(authData, ALICE, master, master))
        val keyID = requireNotNull(server.accountDataContent(ALICE, "m.secret_storage.default_key")?.get("key")?.string)
        for (name in sharedSecretNames) {
            assertNotNull(server.accountDataContent(ALICE, name)?.at("encrypted", keyID), name)
        }

        // Новое устройство по коду: ключи кросс-подписи, подпись себя, история комнаты.
        val fresh = server.cryptoDevice(ALICE, "A2")
        assertEquals(listOf<String?>(null), server.read(fresh))
        fresh.machine.importCrossSigning(code)
        val restored = fresh.machine.restoreBackup(code)
        assertEquals(1, restored.imported)
        assertEquals(listOf("до бэкапа"), server.read(fresh))
        assertEquals(true, alice.machine.devices(listOf(ALICE)).firstOrNull { it.deviceID == "A2" }?.crossSigned)
        // Ключ бэкапа у нового устройства тот же: его собственная сессия выгружается в ту же версию.
        fresh.machine.sendEncrypted(TEST_ROOM, EventType.MESSAGE, text("после"))
        assertEquals(1, fresh.machine.uploadRoomKeysToBackup())
        assertEquals(2, server.backup[TEST_ROOM]?.size)
    }

    @Test fun newCodeReplacesOldAndKeepsBackup() = runBlocking {
        val server = FakeHomeserver()
        val alice = server.cryptoDevice(ALICE, "A1")
        val bob = server.cryptoDevice(BOB, "B1")
        server.setMembers(listOf(ALICE, BOB), TEST_ROOM)
        bob.machine.sendEncrypted(TEST_ROOM, EventType.MESSAGE, text("x"))
        server.read(alice)
        val first = alice.machine.enableRecovery(null)
        val master = server.crossSigningKey(ALICE, "master")
        val version = server.backupVersion

        val second = alice.machine.enableRecovery(null)
        assertNotEquals(first, second)
        assertEquals(version, server.backupVersion)
        assertEquals(master, server.crossSigningKey(ALICE, "master"))
        assertEquals(1, server.backup[TEST_ROOM]?.size)

        val fresh = server.cryptoDevice(ALICE, "A2")
        assertEquals(E2EEException.WrongRecoveryKey, runCatching { fresh.machine.restoreBackup(first) }.exceptionOrNull())
        assertEquals(1, fresh.machine.restoreBackup(second).imported)
    }

    @Test fun existingIdentityNeedsKeysOrExplicitReset() = runBlocking {
        val server = FakeHomeserver()
        val old = server.cryptoDevice(ALICE, "A1")
        old.machine.bootstrapCrossSigning(null)
        val master = server.crossSigningKey(ALICE, "master")

        // У нового устройства закрытых ключей нет: молча менять личность нельзя.
        val fresh = server.cryptoDevice(ALICE, "A2")
        assertTrue(runCatching { fresh.machine.enableRecovery(null) }.exceptionOrNull() is E2EEException)
        assertEquals(master, server.crossSigningKey(ALICE, "master"))
        assertNull(server.backupAuthData)
        assertNull(server.accountDataContent(ALICE, "m.secret_storage.default_key"))

        val code = fresh.machine.enableRecovery(null, resetIdentity = true)
        assertNotEquals(master, server.crossSigningKey(ALICE, "master"))
        val other = server.cryptoDevice(ALICE, "A3")
        other.machine.importCrossSigning(code)
        assertEquals(true, other.machine.devices(listOf(ALICE)).firstOrNull { it.deviceID == "A2" }?.crossSigned)
    }

    @Test fun staleLocalBackupKeyGetsNewVersion() = runBlocking {
        val server = FakeHomeserver()
        val alice = server.cryptoDevice(ALICE, "A1")
        alice.machine.enableRecovery(null)
        assertEquals("2", server.backupVersion)
        // Ключ бэкапа на устройстве к версии на сервере не подходит (например, бэкап завели с другого устройства).
        alice.machine.store.setCryptoBlob("backup_key", BackupKey().keyPair.rawRepresentation)
        val code = alice.machine.enableRecovery(null)
        assertEquals("3", server.backupVersion)
        val fresh = server.cryptoDevice(ALICE, "A2")
        fresh.machine.restoreBackup(code)
        val local = requireNotNull(fresh.machine.store.cryptoBlob("backup_key"))
        assertEquals(BackupKey(privateKey = local).publicKeyBase64, server.backupAuthData?.get("public_key")?.string)
    }
}
