// Нютаг. Поиск по сообщениям: индекс, правки, удаления, зашифрованные.

package chat.nyutag.core

import chat.nyutag.core.rooms.Search
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchTest {
    private val me = "@me:nyutag.test"
    private val friend = "@friend:nyutag.test"

    private fun text(id: String, body: String, room: String, ts: Long, sender: String = me, content: JsonValue? = null): Event =
        Event(eventID = id, type = EventType.MESSAGE, sender = sender, originServerTS = ts,
              content = content ?: JsonValue.of("msgtype" to "m.text", "body" to body), roomID = room)

    @Test fun indexAndFind() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord("!a", RoomRecord.Membership.JOIN))
        store.upsert(RoomRecord("!b", RoomRecord.Membership.JOIN))
        store.append(listOf(
            text("\$1", "Привет, ёлка!", "!a", 100),
            text("\$2", "привет из второго чата", "!b", 200, sender = friend),
            text("\$3", "удали меня: привет", "!a", 300),
            Event(eventID = "\$4", type = EventType.REDACTION, sender = me, originServerTS = 400, content = JsonValue.of("redacts" to "\$3"), roomID = "!a"),
            text("\$5", "старый текст", "!a", 500),
            text("\$6", "", "!a", 600, content = JsonValue.of(
                "msgtype" to "m.text", "body" to "* новый привет",
                "m.new_content" to JsonValue.of("msgtype" to "m.text", "body" to "новый привет"),
                "m.relates_to" to JsonValue.of("rel_type" to "m.replace", "event_id" to "\$5"),
            )),
            text("\$7", "", "!a", 700, content = JsonValue.of("msgtype" to "m.image", "body" to "privet.png", "url" to "mxc://x/1")),
        ))
        assertEquals(6, Search.index(store, decrypt = { _, _ -> null }))
        assertEquals(listOf("\$5", "\$2", "\$1"), Search.find("ПРИВЕТ", store = store).map { it.eventID })
        assertEquals(listOf("\$7"), Search.find("privet", store = store).map { it.eventID })
        assertTrue(Search.find("новый", store = store).any { it.text.contains("новый привет") })
        assertEquals(listOf("\$1"), Search.find("елка", store = store).map { it.eventID })
        assertEquals(listOf("\$2"), Search.find("привет", "!b", store).map { it.eventID })
        assertTrue(Search.find("старый", store = store).isEmpty())
        assertTrue(Search.find("п", store = store).isEmpty())
        assertEquals(0, Search.index(store, decrypt = { _, _ -> null }))
    }

    @Test fun encryptedNeedsDecrypt() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord("!e", RoomRecord.Membership.JOIN))
        store.append(listOf(Event(eventID = "\$c", type = EventType.ENCRYPTED, sender = friend, originServerTS = 100,
                                  content = JsonValue.of("algorithm" to "m.megolm.v1.aes-sha2", "ciphertext" to "x"), roomID = "!e")))
        assertEquals(0, Search.index(store, decrypt = { _, _ -> null }))
        assertTrue(Search.find("тайна", store = store).isEmpty())
        val decrypted = Search.index(store, decrypt = { event, room ->
            text(event.eventID!!, "большая тайна", room, event.originServerTS!!, sender = event.sender!!)
        })
        assertEquals(1, decrypted)
        val hits = Search.find("тайна", store = store)
        assertEquals(1, hits.size)
        assertEquals(friend, hits.first().sender)
        assertEquals("!e", hits.first().roomID)
    }
}
