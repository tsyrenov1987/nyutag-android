// Нютаг. Машина E2EE: подпись ключей устройства, ключ комнаты по Olm, расшифровка Megolm, защита от повтора.

package chat.nyutag.core

import chat.nyutag.core.auth.Session
import chat.nyutag.core.crypto.CryptoMachine
import chat.nyutag.core.crypto.E2EEException
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.crypto.MegolmOutboundSession
import chat.nyutag.crypto.OlmAccount
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class CryptoMachineTest {
    private val bob = Session("@bob:nyutag.chat", "BOBDEVICE", "x", URI("http://127.0.0.1:1"))

    private suspend fun machine(store: Store = Store(JdbcSql.inMemory())): CryptoMachine =
        CryptoMachine.create(MatrixClient(bob.homeserver), store, bob)

    @Test fun deviceKeysAreSignedCanonically() = runBlocking {
        val machine = machine()
        val keys = machine.deviceKeys()
        assertEquals(machine.curve25519Key, keys.at("keys", "curve25519:BOBDEVICE")?.string)
        assertTrue(CryptoMachine.verifySignature(keys, "@bob:nyutag.chat", "BOBDEVICE", machine.ed25519Key))
        // Порядок ключей и `unsigned` на подпись не влияют, правка содержимого — влияет.
        val fields = LinkedHashMap(requireNotNull(keys.obj))
        fields["unsigned"] = JsonValue.of("device_display_name" to "Мак")
        assertTrue(CryptoMachine.verifySignature(JsonValue.Obj(fields), "@bob:nyutag.chat", "BOBDEVICE", machine.ed25519Key))
        fields["device_id"] = JsonValue.Str("OTHER")
        assertFalse(CryptoMachine.verifySignature(JsonValue.Obj(fields), "@bob:nyutag.chat", "BOBDEVICE", machine.ed25519Key))
    }

    /** Чужое устройство (только модуль crypto) присылает ключ комнаты через Olm, потом сообщение Megolm. */
    @Test fun roomKeyOverOlmThenMegolmMessage() = runBlocking {
        val machine = machine()
        val otk = machine.oneTimeKeyForTest()
        val alice = OlmAccount()
        val outbound = MegolmOutboundSession()
        val roomID = "!room:nyutag.chat"
        val payload = JsonValue.of(
            "type" to "m.room_key",
            "content" to JsonValue.of(
                "algorithm" to "m.megolm.v1.aes-sha2", "room_id" to roomID,
                "session_id" to outbound.sessionID, "session_key" to outbound.sessionKey(),
            ),
            "sender" to "@alice:nyutag.chat", "sender_device" to "ALICEDEV",
            "recipient" to "@bob:nyutag.chat", "recipient_keys" to JsonValue.of("ed25519" to machine.ed25519Key),
            "keys" to JsonValue.of("ed25519" to alice.ed25519Key),
        )
        val olm = alice.outboundSession(machine.curve25519Key, otk)
        val sealed = olm.encrypt(payload.compact().toByteArray())
        val toDevice = Event(type = "m.room.encrypted", sender = "@alice:nyutag.chat", content = JsonValue.of(
            "algorithm" to "m.olm.v1.curve25519-aes-sha2", "sender_key" to alice.curve25519Key,
            "ciphertext" to JsonValue.of(machine.curve25519Key to JsonValue.of("type" to 0, "body" to sealed.body)),
        ))
        val received = machine.receiveToDevice(listOf(toDevice))
        assertEquals(listOf("m.room_key"), received.map { it.type })
        assertEquals(alice.ed25519Key, received.first().unsigned?.get("sender_ed25519")?.string)
        assertEquals(1, machine.megolmSessionCount())

        fun roomEvent(eventID: String, body: String, room: String = roomID): Event {
            val plaintext = JsonValue.of("type" to "m.room.message", "room_id" to room,
                                         "content" to JsonValue.of("msgtype" to "m.text", "body" to body))
            return Event(eventID = eventID, type = "m.room.encrypted", sender = "@alice:nyutag.chat", originServerTS = 1,
                         content = JsonValue.of(
                             "algorithm" to "m.megolm.v1.aes-sha2", "sender_key" to alice.curve25519Key,
                             "session_id" to outbound.sessionID, "device_id" to "ALICEDEV",
                             "ciphertext" to outbound.encrypt(plaintext.compact().toByteArray()),
                         ), roomID = room)
        }
        val first = roomEvent("$1", "сайн байна")
        val decrypted = machine.decrypt(first, roomID)
        assertEquals("m.room.message", decrypted.event.type)
        assertEquals("сайн байна", decrypted.event.content["body"]?.string)
        assertTrue(decrypted.isVerifiedSession)
        assertEquals(0u, decrypted.messageIndex)
        // То же событие повторно — можно, тот же индекс под другим event_id — повтор.
        machine.decrypt(first, roomID)
        val forged = Event(eventID = "\$forged", type = first.type, sender = first.sender, content = first.content)
        assertEquals(E2EEException.Replay, runCatching { machine.decrypt(forged, roomID) }.exceptionOrNull())
        // Ключ комнаты не годится для другой комнаты.
        val elsewhere = roomEvent("$2", "x", "!other:nyutag.chat")
        assertEquals(E2EEException.MissingSession(outbound.sessionID),
                     runCatching { machine.decrypt(elsewhere, "!other:nyutag.chat") }.exceptionOrNull())
        // Без sender_key (новые клиенты его не шлют) сессия ищется по session_id.
        val modern = roomEvent("$3", "без sender_key")
        val content = LinkedHashMap(requireNotNull(modern.content.obj)).apply { remove("sender_key") }
        val stripped = Event(eventID = "$3", type = modern.type, sender = modern.sender, content = JsonValue.Obj(content))
        assertEquals("без sender_key", machine.decrypt(stripped, roomID).event.content["body"]?.string)
    }

    @Test fun toDeviceForSomeoneElseIsRejected() = runBlocking {
        val machine = machine()
        val otk = machine.oneTimeKeyForTest()
        val alice = OlmAccount()
        val payload = JsonValue.of(
            "type" to "m.room_key", "content" to JsonValue.of(), "sender" to "@alice:nyutag.chat",
            "recipient" to "@mallory:nyutag.chat", "recipient_keys" to JsonValue.of("ed25519" to machine.ed25519Key),
            "keys" to JsonValue.of("ed25519" to alice.ed25519Key),
        )
        val sealed = alice.outboundSession(machine.curve25519Key, otk).encrypt(payload.compact().toByteArray())
        val event = Event(type = "m.room.encrypted", sender = "@alice:nyutag.chat", content = JsonValue.of(
            "algorithm" to "m.olm.v1.curve25519-aes-sha2", "sender_key" to alice.curve25519Key,
            "ciphertext" to JsonValue.of(machine.curve25519Key to JsonValue.of("type" to 0, "body" to sealed.body)),
        ))
        assertTrue(machine.receiveToDevice(listOf(event)).isEmpty())
    }

    @Test fun accountSurvivesRestart() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        val first = machine(store)
        val second = machine(store)
        assertEquals(first.curve25519Key, second.curve25519Key)
        assertEquals(first.ed25519Key, second.ed25519Key)
    }
}

/** Одноразовый ключ, как если бы его выдал сервер по `/keys/claim`. */
internal fun CryptoMachine.oneTimeKeyForTest(): String {
    account.generateOneTimeKeys(1)
    val key = account.unpublishedOneTimeKeys.values.first()
    account.markKeysPublished()
    return key
}
