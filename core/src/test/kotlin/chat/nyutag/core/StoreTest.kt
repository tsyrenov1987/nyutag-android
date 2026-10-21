package chat.nyutag.core

import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.store.SqlValue
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StoreTest {
    @Test fun sqliteBasics() {
        val db = JdbcSql.inMemory()
        db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT, blob BLOB, real REAL)")
        db.run("INSERT INTO t(name, blob, real) VALUES(?, ?, ?)", SqlValue.Text("юрта"), SqlValue.Blob(byteArrayOf(1, 2, 3)), SqlValue.Real(1.5))
        db.run("INSERT INTO t(name) VALUES(?)", SqlValue.Null)
        val rows = db.query("SELECT id, name, blob, real FROM t ORDER BY id") { row ->
            listOf(row.int(0), row.string(1), row.bytes(2), row.value(3).double)
        }
        assertEquals(2, rows.size)
        assertEquals("юрта", rows[0][1])
        assertArrayEquals(byteArrayOf(1, 2, 3), rows[0][2] as ByteArray)
        assertEquals(1.5, rows[0][3])
        assertNull(rows[1][1])
        assertEquals(2, db.scalar("SELECT COUNT(*) FROM t")?.int)
    }

    @Test fun transactionRollsBack() {
        val db = JdbcSql.inMemory()
        db.exec("CREATE TABLE t(x INTEGER)")
        class Boom : Exception()
        assertThrows(Boom::class.java) {
            db.transaction {
                db.run("INSERT INTO t VALUES(1)")
                throw Boom()
            }
        }
        assertEquals(0, db.scalar("SELECT COUNT(*) FROM t")?.int)
    }

    @Test fun storeRoomsEventsState() {
        val store = Store(JdbcSql.inMemory())
        val room = RoomRecord("!a:nyutag.chat", RoomRecord.Membership.JOIN)
        room.name = "Улан-Удэ"
        room.isEncrypted = true
        store.upsert(room)
        assertEquals("Улан-Удэ", store.room("!a:nyutag.chat")?.name)
        assertEquals(1, store.rooms(RoomRecord.Membership.JOIN).size)

        val events = (1..3).map { i ->
            Event(eventID = "\$e$i", type = EventType.MESSAGE, sender = "@u:nyutag.chat", originServerTS = 1000L + i,
                  content = JsonValue.of("msgtype" to "m.text", "body" to "сообщение $i"), roomID = "!a:nyutag.chat")
        }
        store.append(events)
        store.append(listOf(events[1])) // дубликат
        assertEquals(3, store.eventCount("!a:nyutag.chat"))
        val loaded = store.events("!a:nyutag.chat", limit = 2)
        assertEquals(listOf("\$e2", "\$e3"), loaded.map { it.eventID })
        assertEquals("сообщение 2", loaded[0].content["body"]?.string)

        val name = Event(eventID = "\$s1", type = EventType.NAME, sender = "@u:nyutag.chat", stateKey = "",
                         content = JsonValue.of("name" to "Нютаг"), roomID = "!a:nyutag.chat")
        store.setState(name)
        assertEquals("Нютаг", store.state("!a:nyutag.chat", EventType.NAME)?.get("name")?.string)

        store.setAccountData(EventType.CHAT_LAYOUT, content = JsonValue.of("pins" to listOf("!a:nyutag.chat")))
        assertEquals("!a:nyutag.chat", store.accountData(EventType.CHAT_LAYOUT)?.get("pins")?.get(0)?.string)

        store.setMeta("sync_token", "s1")
        assertEquals("s1", store.syncToken)
        store.wipe()
        assertNull(store.syncToken)
        assertTrue(store.rooms().isEmpty())
    }

    @Test fun prependAndSearch() {
        val store = Store(JdbcSql.inMemory())
        val roomID = "!a:nyutag.chat"
        store.append(listOf(Event(eventID = "\$new", type = EventType.MESSAGE, sender = "@u:nyutag.chat", originServerTS = 2000,
                                  content = JsonValue.of("body" to "новое"), roomID = roomID)))
        val added = store.prepend(listOf(
            Event(eventID = "\$old2", type = EventType.MESSAGE, sender = "@u:nyutag.chat", originServerTS = 1500, content = JsonValue.of("body" to "старое 2")),
            Event(eventID = "\$old1", type = EventType.MESSAGE, sender = "@u:nyutag.chat", originServerTS = 1000, content = JsonValue.of("body" to "старое 1")),
        ), roomID)
        assertEquals(2, added)
        assertEquals(listOf("\$old1", "\$old2", "\$new"), store.events(roomID).map { it.eventID })

        assertEquals(3, store.unindexedEvents(null, 10).size)
        store.indexSearch("\$new", roomID, "@u:nyutag.chat", 2000, "Сайн байна", "сайн байна")
        assertEquals(2, store.unindexedEvents(null, 10).size)
        assertEquals("Сайн байна", store.searchText("байна", null, 10).single().text)
        assertTrue(store.searchText("байна", "!other:nyutag.chat", 10).isEmpty())
        store.deleteRoom(roomID)
        assertEquals(0, store.eventCount(roomID))
    }
}
