// Нютаг. Комнаты: подгрузка истории, разбор /messages, номера транзакций, тело createRoom.

package chat.nyutag.core

import chat.nyutag.core.rooms.CreateRoom
import chat.nyutag.core.rooms.MessagesPage
import chat.nyutag.core.rooms.TransactionID
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RoomsTest {
    private fun message(id: Int, room: String = "!r:nyutag.chat"): Event = Event(
        eventID = "\$m$id", type = EventType.MESSAGE, sender = "@u:nyutag.chat", originServerTS = id.toLong(),
        content = JsonValue.of("msgtype" to "m.text", "body" to "№$id"), roomID = room,
    )

    @Test fun prependPutsHistoryBeforeTimeline() {
        val store = Store(JdbcSql.inMemory())
        store.append(listOf(message(10), message(11)))
        // /messages dir=b отдаёт от новых к старым, с повтором уже известного события.
        val added = store.prepend(listOf(message(10), message(9), message(8)), "!r:nyutag.chat")
        assertEquals(2, added)
        store.append(listOf(message(12)))
        val ids = store.events("!r:nyutag.chat").mapNotNull { it.eventID }
        assertEquals(listOf("\$m8", "\$m9", "\$m10", "\$m11", "\$m12"), ids)
    }

    @Test fun messagesPageParsing() {
        val json = JsonValue.parse(
            """{"start":"t2","end":"t1","chunk":[{"type":"m.room.message","event_id":"${'$'}a","sender":"@u:x","content":{"body":"x"}}],
               "state":[{"type":"m.room.member","state_key":"@u:x","event_id":"${'$'}m","content":{"membership":"join"}}]}""",
        )
        val page = MessagesPage.from(json, "!r:x")
        assertEquals("!r:x", page.events.first().roomID)
        assertEquals(1, page.state.size)
        assertEquals("t1", page.end)
        val last = MessagesPage.from(JsonValue.of("start" to "t2", "chunk" to emptyList<Any>()), "!r:x")
        assertNull(last.end)
    }

    @Test fun transactionIDsAreUnique() {
        val ids = (0 until 1000).map { TransactionID.make() }.toSet()
        assertEquals(1000, ids.size)
    }

    @Test fun createRoomBodyEnablesEncryption() {
        val body = CreateRoom(name = "Тест", invite = listOf("@a:x")).json
        assertEquals("m.megolm.v1.aes-sha2", body["initial_state"]?.array?.first()?.get("content")?.get("algorithm")?.string)
        assertEquals("private_chat", body["preset"]?.string)
        assertEquals(1, body["invite"]?.array?.size)
    }
}
