// Нютаг. Текст пуша: событие с сервера, расшифровка ключами из хранилища; пушер Android и правило звонка.

package chat.nyutag.core

import chat.nyutag.core.crypto.CryptoMachine
import chat.nyutag.core.crypto.cryptoBlob
import chat.nyutag.core.crypto.sendEncrypted
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.push.AndroidPusher
import chat.nyutag.core.push.PushPreview
import chat.nyutag.core.push.PushReader
import chat.nyutag.core.push.ensureRingPushRule
import chat.nyutag.core.push.registerAndroidPush
import chat.nyutag.core.push.removeAndroidPush
import chat.nyutag.core.rooms.send
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PushReaderTest {
    @Test fun encryptedMessageIsDecrypted() = runBlocking {
        val server = FakeHomeserver()
        val alice = server.cryptoDevice(ALICE, "A1")
        // У Боба одно хранилище на движок и читатель пушей: движок принял ключ, читатель читает.
        val bobSession = server.session(BOB, "B1")
        val bobStore = Store(JdbcSql.inMemory())
        val bob = CryptoMachine.create(server.client(bobSession), bobStore, bobSession)
        bob.publishKeys(null)
        server.setMembers(listOf(ALICE, BOB), TEST_ROOM)
        bobStore.setState(Event(type = EventType.MEMBER, sender = ALICE, stateKey = ALICE, roomID = TEST_ROOM,
                                content = JsonValue.of("membership" to "join", "displayname" to "Алиса")))

        alice.machine.sendEncrypted(TEST_ROOM, EventType.MESSAGE, text("> <@bob:nyutag.chat> где ты\n\nдома"))
        val eventID = requireNotNull(server.events(TEST_ROOM).last().eventID)
        val reader = PushReader(server.client(bobSession), bobStore, bobSession)

        // Ключ ещё не дошёл до устройства — нейтральный пуш.
        assertEquals(PushPreview.Kind.Locked, reader.preview(TEST_ROOM, eventID).kind)

        bob.receiveToDevice(server.takeToDevice(BOB, "B1"))
        val preview = reader.preview(TEST_ROOM, eventID)
        assertEquals(PushPreview.Kind.Text("дома"), preview.kind)
        assertEquals(ALICE, preview.sender)
        assertEquals("Алиса", preview.senderName)
        // Движок позже расшифрует то же событие: защита от повтора не должна его отвергнуть.
        val again = bob.decrypt(server.events(TEST_ROOM).last(), TEST_ROOM)
        assertTrue(again.event.content["body"]?.string?.endsWith("дома") == true)
    }

    @Test fun noAccountMeansNoDecryption() = runBlocking {
        val server = FakeHomeserver()
        val session = server.session(BOB, "B1")
        val store = Store(JdbcSql.inMemory())
        val client = server.client(session)
        client.send(TEST_ROOM, EventType.ENCRYPTED,
                    JsonValue.of("algorithm" to "m.megolm.v1.aes-sha2", "session_id" to "s", "ciphertext" to "c"))
        val eventID = requireNotNull(server.events(TEST_ROOM).last().eventID)
        assertEquals(PushPreview.Kind.Locked, PushReader(client, store, session).preview(TEST_ROOM, eventID).kind)
        // Читатель пушей не заводит свой Olm-аккаунт.
        assertNull(store.cryptoBlob("account"))
    }

    @Test fun storedEventNeedsNoServer() = runBlocking {
        val server = FakeHomeserver()
        val session = server.session(BOB, "B1")
        val store = Store(JdbcSql.inMemory())
        val client = server.client(session)
        client.send(TEST_ROOM, EventType.MESSAGE, text("из базы"))
        val event = server.events(TEST_ROOM).last().copy(roomID = TEST_ROOM)
        store.append(listOf(event))
        val eventID = requireNotNull(event.eventID)

        // Пути к ядру нет: другой сервер, где события нет и токен чужой.
        val offline = PushReader(FakeHomeserver().client(accessToken = null), store, session)
        assertEquals(PushPreview.Kind.Text("из базы"), offline.preview(TEST_ROOM, eventID).kind)
        assertTrue(runCatching { offline.preview(TEST_ROOM, "\$нет") }.exceptionOrNull() is MatrixError)
    }

    @Test fun pusherRegistersOnceAndRemoves() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(BOB, "B1"))
        client.registerAndroidPush("fcm-token", debug = false, deviceName = "Redmi", language = "ru")
        client.registerAndroidPush("fcm-token", debug = false, deviceName = "Redmi", language = "ru")
        assertEquals(1, server.pushers(BOB).size)
        val pusher = server.pushers(BOB).first()
        assertEquals("chat.nyutag.messenger.android", pusher["app_id"]?.string)
        assertEquals("fcm-token", pusher["pushkey"]?.string)
        assertEquals("http://127.0.0.1:5000/_matrix/push/v1/notify", pusher.at("data", "url")?.string)
        assertEquals("event_id_only", pusher.at("data", "format")?.string)
        client.registerAndroidPush("fcm-token", debug = true, deviceName = "Redmi", language = "ru")
        assertEquals(setOf("chat.nyutag.messenger.android", "chat.nyutag.messenger.android.debug"),
                     server.pushers(BOB).map { it["app_id"]?.string }.toSet())
        client.removeAndroidPush("fcm-token", debug = false)
        client.removeAndroidPush("fcm-token", debug = true)
        assertTrue(server.pushers(BOB).isEmpty())
    }

    @Test fun ringRule() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(BOB, "B1"))
        client.ensureRingPushRule()
        val rule = requireNotNull(server.pushRule(BOB, "override", "nyutag.ring"))
        assertEquals("chat.nyutag.ring", rule["conditions"]?.array?.first()?.get("pattern")?.string)
        assertEquals("notify", rule["actions"]?.array?.first()?.string)
    }

    @Test fun targetFromGatewayData() {
        assertEquals(TEST_ROOM to "\$e", AndroidPusher.target(mapOf("room_id" to TEST_ROOM, "event_id" to "\$e", "unread" to "2")))
        // Пуш-счётчик без события (прочитано на другом устройстве) — уведомление не показывать.
        assertNull(AndroidPusher.target(mapOf("room_id" to TEST_ROOM, "unread" to "0")))
        assertNull(AndroidPusher.target(mapOf("room_id" to "", "event_id" to "\$e")))
    }
}
