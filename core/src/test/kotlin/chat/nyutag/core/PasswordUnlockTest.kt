// Нютаг. Хранилище секретов под паролем аккаунта: вход паролем на новом устройстве поднимает историю без recovery-кода.

package chat.nyutag.core

import chat.nyutag.core.crypto.E2EEException
import chat.nyutag.core.crypto.PasswordUnlockOutcome
import chat.nyutag.core.crypto.bootstrapCrossSigning
import chat.nyutag.core.crypto.devices
import chat.nyutag.core.crypto.enableRecovery
import chat.nyutag.core.crypto.importCrossSigning
import chat.nyutag.core.crypto.relockWithPassword
import chat.nyutag.core.crypto.sendEncrypted
import chat.nyutag.core.crypto.unlockWithPassword
import chat.nyutag.core.sync.EventType
import chat.nyutag.crypto.Random
import chat.nyutag.crypto.RecoveryKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PasswordUnlockTest {
    /** В приложении 500 000; в тестах меньше, чтобы не ждать. */
    private val iterations = 1000

    @Test fun firstSignInCreatesThenNewDeviceOpensByPassword() = runBlocking {
        val server = FakeHomeserver()
        server.requirePassword = "пароль-1"
        val alice = server.cryptoDevice(ALICE, "A1")
        val bob = server.cryptoDevice(BOB, "B1")
        server.setMembers(listOf(ALICE, BOB), TEST_ROOM)
        bob.machine.sendEncrypted(TEST_ROOM, EventType.MESSAGE, text("сайн байна"))
        assertEquals(listOf("сайн байна"), server.read(alice))

        assertEquals(PasswordUnlockOutcome.Created, alice.machine.unlockWithPassword("пароль-1", iterations))
        val keyID = requireNotNull(server.accountDataContent(ALICE, "m.secret_storage.default_key")?.get("key")?.string)
        val description = requireNotNull(server.accountDataContent(ALICE, "m.secret_storage.key.$keyID"))
        assertEquals("m.pbkdf2", description.at("passphrase", "algorithm")?.string)
        assertEquals(iterations, description.at("passphrase", "iterations")?.int)

        val fresh = server.cryptoDevice(ALICE, "A2")
        assertEquals(listOf<String?>(null), server.read(fresh))
        assertEquals(PasswordUnlockOutcome.NeedsRecoveryKey, fresh.machine.unlockWithPassword("чужой", iterations))
        assertEquals(PasswordUnlockOutcome.Opened(1), fresh.machine.unlockWithPassword("пароль-1", iterations))
        assertEquals(listOf("сайн байна"), server.read(fresh))
        assertEquals(true, alice.machine.devices(listOf(ALICE)).firstOrNull { it.deviceID == "A2" }?.crossSigned)
    }

    @Test fun identityWithoutStorageNeedsOtherDevice() = runBlocking {
        val server = FakeHomeserver()
        val old = server.cryptoDevice(ALICE, "A1")
        old.machine.bootstrapCrossSigning(null)
        val master = server.crossSigningKey(ALICE, "master")

        val fresh = server.cryptoDevice(ALICE, "A2")
        assertEquals(PasswordUnlockOutcome.NeedsOtherDevice, fresh.machine.unlockWithPassword("pw", iterations))
        assertEquals(master, server.crossSigningKey(ALICE, "master"))
        assertNull(server.accountDataContent(ALICE, "m.secret_storage.default_key"))
        // Устройство, у которого ключи есть, заводит хранилище под паролем, не меняя личность.
        assertEquals(PasswordUnlockOutcome.Created, old.machine.unlockWithPassword("pw", iterations))
        assertEquals(master, server.crossSigningKey(ALICE, "master"))
        assertEquals(PasswordUnlockOutcome.Opened(0), fresh.machine.unlockWithPassword("pw", iterations))
    }

    @Test fun oldRandomCodeIsRelockedWithPassword() = runBlocking {
        val server = FakeHomeserver()
        val alice = server.cryptoDevice(ALICE, "A1")
        val bob = server.cryptoDevice(BOB, "B1")
        server.setMembers(listOf(ALICE, BOB), TEST_ROOM)
        bob.machine.sendEncrypted(TEST_ROOM, EventType.MESSAGE, text("старое"))
        server.read(alice)
        val code = alice.machine.enableRecovery(null)

        val fresh = server.cryptoDevice(ALICE, "A2")
        assertEquals(PasswordUnlockOutcome.NeedsRecoveryKey, fresh.machine.unlockWithPassword("pw", iterations))
        assertEquals(1, fresh.machine.relockWithPassword(code, "pw", iterations))
        assertEquals(listOf("старое"), server.read(fresh))

        val third = server.cryptoDevice(ALICE, "A3")
        assertEquals(PasswordUnlockOutcome.Opened(1), third.machine.unlockWithPassword("pw", iterations))
        assertEquals(E2EEException.WrongRecoveryKey, runCatching { third.machine.restoreBackup(code) }.exceptionOrNull())
    }

    @Test fun wrongKeyChangesNothingAndKeyAloneOpensHistory() = runBlocking {
        val server = FakeHomeserver()
        val alice = server.cryptoDevice(ALICE, "A1")
        val bob = server.cryptoDevice(BOB, "B1")
        server.setMembers(listOf(ALICE, BOB), TEST_ROOM)
        bob.machine.sendEncrypted(TEST_ROOM, EventType.MESSAGE, text("старое"))
        server.read(alice)
        val code = alice.machine.enableRecovery(null)
        val other = RecoveryKey.encode(Random.bytes(32))

        val fresh = server.cryptoDevice(ALICE, "A2")
        assertEquals(E2EEException.WrongRecoveryKey,
                     runCatching { fresh.machine.relockWithPassword(other, "pw", iterations) }.exceptionOrNull())
        assertEquals(E2EEException.WrongRecoveryKey,
                     runCatching { fresh.machine.relockWithPassword("не ключ", "pw", iterations) }.exceptionOrNull())
        assertEquals(PasswordUnlockOutcome.NeedsRecoveryKey, fresh.machine.unlockWithPassword("pw", iterations))

        // После перезапуска пароля в памяти нет: приложение только открывает хранилище ключом, без перезапирания.
        fresh.machine.secretStorageKey(code)
        fresh.machine.importCrossSigning(code)
        assertEquals(1, fresh.machine.restoreBackup(code).imported)
        assertEquals(listOf("старое"), server.read(fresh))
        assertEquals(PasswordUnlockOutcome.NeedsRecoveryKey, fresh.machine.unlockWithPassword("pw", iterations))
    }
}
