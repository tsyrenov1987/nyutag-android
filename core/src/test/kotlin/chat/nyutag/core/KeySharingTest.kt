// Нютаг. Кросс-подпись, запрос ключей у своих устройств и отказы в ключе на поддельном сервере.

package chat.nyutag.core

import chat.nyutag.core.crypto.CrossSigningKind
import chat.nyutag.core.crypto.CryptoMachine
import chat.nyutag.core.crypto.E2EEException
import chat.nyutag.core.crypto.adoptCrossSigning
import chat.nyutag.core.crypto.answerRoomKeyRequest
import chat.nyutag.core.crypto.bootstrapCrossSigning
import chat.nyutag.core.crypto.crossSigningIdentity
import chat.nyutag.core.crypto.devices
import chat.nyutag.core.crypto.hasCrossSigningKeys
import chat.nyutag.core.crypto.hasKeyRequest
import chat.nyutag.core.crypto.isVerified
import chat.nyutag.core.crypto.ownCrossSigningKey
import chat.nyutag.core.crypto.requestRoomKey
import chat.nyutag.core.crypto.sendEncrypted
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeySharingTest {
    /** Второе устройство получает те же ключи кросс-подписи (как после ввода recovery-кода). */
    private suspend fun adopt(from: CryptoDevice, to: CryptoDevice) {
        val master = requireNotNull(from.machine.ownCrossSigningKey(CrossSigningKind.MASTER))
        val selfSigning = requireNotNull(from.machine.ownCrossSigningKey(CrossSigningKind.SELF_SIGNING))
        to.machine.adoptCrossSigning(master, selfSigning, from.machine.ownCrossSigningKey(CrossSigningKind.USER_SIGNING))
    }

    @Test fun bootstrapAsksPasswordAndSignsDevice() = runBlocking {
        val server = FakeHomeserver()
        server.requirePassword = "пароль"
        val alice = server.cryptoDevice(ALICE, "A1")
        val bob = server.cryptoDevice(BOB, "B1")

        assertTrue(runCatching { alice.machine.bootstrapCrossSigning(null) }.exceptionOrNull() is E2EEException)
        assertFalse(alice.machine.hasCrossSigningKeys)
        alice.machine.bootstrapCrossSigning("пароль")
        assertTrue(alice.machine.hasCrossSigningKeys)

        // Сама Алиса видит своё устройство подтверждённым.
        val own = alice.machine.devices(listOf(ALICE)).first()
        assertTrue(own.crossSigned)
        assertTrue(alice.machine.isVerified(own))
        // Боб видит подпись, но мастер-ключ Алисы им не подтверждён.
        val seen = bob.machine.devices(listOf(ALICE)).first()
        assertTrue(seen.crossSigned)
        assertFalse(bob.machine.isVerified(seen))
        assertEquals(false, bob.machine.crossSigningIdentity(ALICE)?.verified)
    }

    @Test fun forgedSelfSigningKeyDoesNotVerify() = runBlocking {
        val server = FakeHomeserver()
        val alice = server.cryptoDevice(ALICE, "A1")
        val other = server.cryptoDevice(ALICE, "A2")
        alice.machine.bootstrapCrossSigning(null)
        // Сервер подсовывает чужой мастер-ключ: A2 с настоящими ключами больше не верит своей личности.
        val fake = server.cryptoDevice(ALICE, "A3")
        fake.machine.bootstrapCrossSigning(null)
        adopt(alice, other)
        val devices = other.machine.devices(listOf(ALICE))
        assertTrue(devices.all { !it.crossSigned || it.deviceID == "A3" })
        for (device in devices) assertFalse(other.machine.isVerified(device))
    }

    @Test fun keyRequestAnsweredOnlyForVerifiedOwnDevice() = runBlocking {
        val server = FakeHomeserver()
        val alice1 = server.cryptoDevice(ALICE, "A1")
        val bob = server.cryptoDevice(BOB, "B1")
        alice1.machine.bootstrapCrossSigning(null)
        server.setMembers(listOf(ALICE, BOB), TEST_ROOM)
        bob.machine.sendEncrypted(TEST_ROOM, EventType.MESSAGE, text("до A2"))
        server.inbox(alice1)

        // Новые устройства Алисы: A2 подтверждено (ключи кросс-подписи подняты), A3 — нет.
        val alice2 = server.cryptoDevice(ALICE, "A2")
        val alice3 = server.cryptoDevice(ALICE, "A3")
        adopt(alice1, alice2)
        val event = server.events(TEST_ROOM).first()
        assertEquals(E2EEException.MissingSession(requireNotNull(event.content["session_id"]?.string)),
                     runCatching { alice2.machine.decrypt(event, TEST_ROOM) }.exceptionOrNull())
        assertTrue(alice2.machine.requestRoomKey(event, TEST_ROOM))
        assertFalse(alice2.machine.requestRoomKey(event, TEST_ROOM))
        assertTrue(alice3.machine.requestRoomKey(event, TEST_ROOM))

        // A1 видит оба запроса, отвечает только A2.
        server.inbox(alice1)
        val toA3 = server.takeToDevice(ALICE, "A3")
        assertFalse(toA3.any { it.type == EventType.ENCRYPTED })
        alice3.machine.receiveToDevice(toA3)
        assertTrue(runCatching { alice3.machine.decrypt(event, TEST_ROOM) }.exceptionOrNull() is E2EEException)
        server.inbox(alice2)
        val opened = alice2.machine.decrypt(event, TEST_ROOM)
        assertEquals("до A2", opened.event.content["body"]?.string)
        assertFalse(opened.isVerifiedSession)
        // Запрос закрыт: другим устройствам ушла отмена.
        assertFalse(alice2.machine.hasKeyRequest(opened.sessionID, ""))
        val cancels = server.takeToDevice(ALICE, "A1").filter { it.content["action"]?.string == "request_cancellation" }
        assertEquals(1, cancels.size)
    }

    @Test fun forwardedKeyWithoutRequestIsIgnored() = runBlocking {
        val server = FakeHomeserver()
        val alice1 = server.cryptoDevice(ALICE, "A1")
        val bob = server.cryptoDevice(BOB, "B1")
        alice1.machine.bootstrapCrossSigning(null)
        server.setMembers(listOf(ALICE, BOB), TEST_ROOM)
        bob.machine.sendEncrypted(TEST_ROOM, EventType.MESSAGE, text("x"))
        server.inbox(alice1)
        val alice2 = server.cryptoDevice(ALICE, "A2")
        adopt(alice1, alice2)
        val event = server.events(TEST_ROOM).first()

        // A1 сам шлёт ключ, о котором A2 не просил: A2 его не берёт.
        val request = Event(type = EventType.ROOM_KEY_REQUEST, sender = ALICE, content = JsonValue.of(
            "action" to "request", "requesting_device_id" to "A2", "request_id" to "r1",
            "body" to JsonValue.of(
                "algorithm" to CryptoMachine.MEGOLM_ALGORITHM, "room_id" to TEST_ROOM,
                "session_id" to event.content["session_id"], "sender_key" to event.content["sender_key"],
            ),
        ))
        assertTrue(alice1.machine.answerRoomKeyRequest(request))
        server.inbox(alice2)
        assertTrue(runCatching { alice2.machine.decrypt(event, TEST_ROOM) }.exceptionOrNull() is E2EEException)
    }

    @Test fun noOlmChannelGivesWithheldOnce() = runBlocking {
        val server = FakeHomeserver()
        val alice = server.cryptoDevice(ALICE, "A1")
        val bob = server.cryptoDevice(BOB, "B1")
        server.dropOneTimeKeys(BOB, "B1")
        server.setMembers(listOf(ALICE, BOB), TEST_ROOM)

        alice.machine.sendEncrypted(TEST_ROOM, EventType.MESSAGE, text("1"))
        alice.machine.sendEncrypted(TEST_ROOM, EventType.MESSAGE, text("2"))
        val received = server.takeToDevice(BOB, "B1")
        assertEquals(listOf(CryptoMachine.WITHHELD_TYPE), received.map { it.type })
        bob.machine.receiveToDevice(received)
        val event = server.events(TEST_ROOM).first()
        assertEquals(E2EEException.Withheld("m.no_olm", "Нет одноразовых ключей для устройства"),
                     runCatching { bob.machine.decrypt(event, TEST_ROOM) }.exceptionOrNull())
    }
}
