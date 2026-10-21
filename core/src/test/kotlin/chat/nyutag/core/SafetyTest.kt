// Нютаг. Защита от людей: блокировка, жалоба, удаление аккаунта.

package chat.nyutag.core

import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.push.PushPreview
import chat.nyutag.core.rooms.ChatDigest
import chat.nyutag.core.rooms.ChatFeed
import chat.nyutag.core.rooms.IgnoredUsers
import chat.nyutag.core.rooms.deactivate
import chat.nyutag.core.rooms.report
import chat.nyutag.core.rooms.setIgnoredUsers
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafetyTest {
    private val me = "@me:nyutag.chat"
    private val bully = "@bully:nyutag.chat"

    @Test fun ignoredFormat() {
        val json = JsonValue.parse("""{"ignored_users":{"@bully:nyutag.chat":{},"@spam:x":{"why":1},"junk":{}}}""")
        assertEquals(setOf(bully, "@spam:x"), IgnoredUsers.users(json))
        assertTrue(IgnoredUsers.users(null).isEmpty())
        assertTrue(IgnoredUsers.users(JsonValue.parse("""{"ignored_users":[]}""")).isEmpty())
        val back = IgnoredUsers.json(setOf(bully))
        assertEquals(JsonValue.Obj(emptyMap()), back["ignored_users"]?.get(bully))
        assertEquals(setOf(bully), IgnoredUsers.users(back))
    }

    @Test fun blockHidesMessages() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(me, "D1"))
        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord("!dm", RoomRecord.Membership.JOIN))
        for (user in listOf(me, bully)) {
            store.setState(Event(
                eventID = "\$m-$user", type = EventType.MEMBER, sender = user, stateKey = user,
                content = JsonValue.of("membership" to "join", "displayname" to ChatDigest.localpart(user)), roomID = "!dm",
            ))
        }
        store.append(listOf(
            Event(eventID = "\$1", type = EventType.MESSAGE, sender = me, originServerTS = 100,
                  content = JsonValue.of("msgtype" to "m.text", "body" to "привет"), roomID = "!dm"),
            Event(eventID = "\$2", type = EventType.MESSAGE, sender = bully, originServerTS = 200,
                  content = JsonValue.of("msgtype" to "m.text", "body" to "гадость"), roomID = "!dm"),
            Event(eventID = "\$3", type = EventType.REACTION, sender = bully, originServerTS = 210,
                  content = JsonValue.of("m.relates_to" to JsonValue.of("rel_type" to "m.annotation", "event_id" to "\$1", "key" to "💩")), roomID = "!dm"),
        ))
        assertEquals(listOf("\$1", "\$2"), ChatFeed.items("!dm", store, me).map { it.id })
        assertEquals(PushPreview.Kind.Text("гадость"), ChatDigest.lines(store, me).first().last)

        client.setIgnoredUsers(setOf(bully), me, store)
        assertEquals(setOf(bully), IgnoredUsers.users(server.accountDataContent(me, EventType.IGNORED_USERS)))
        val ignored = IgnoredUsers.load(store)
        assertEquals(setOf(bully), ignored)
        val items = ChatFeed.items("!dm", store, me, ignored = ignored)
        assertEquals(listOf("\$1"), items.map { it.id })
        assertTrue(items.first().reactions.isEmpty())
        val line = ChatDigest.lines(store, me, ignored).first()
        assertEquals(PushPreview.Kind.Text("привет"), line.last)
        assertTrue(line.lastIsMine)

        client.setIgnoredUsers(emptySet(), me, store)
        assertTrue(IgnoredUsers.load(store).isEmpty())
    }

    @Test fun reportGoesToServer() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(me, "D1"))
        client.report("!dm", "\$2", "оскорбления")
        val report = server.reports.first()
        assertEquals(me, report.user)
        assertEquals("!dm", report.roomID)
        assertEquals("\$2", report.eventID)
        assertEquals("оскорбления", report.reason)
        assertEquals(-100, report.score)
    }

    @Test fun deactivateWithPassword() = runBlocking {
        val server = FakeHomeserver()
        server.setPassword("секрет", me)
        val session = server.session(me, "D1")
        val client = server.client(session)
        assertThrows(MatrixError::class.java) { runBlocking { client.deactivate(me, "не тот") } }
        assertTrue(server.deactivated.isEmpty())
        assertTrue(server.isTokenValid(session.accessToken))

        client.deactivate(me, "секрет")
        assertEquals(setOf(me), server.deactivated)
        assertTrue(server.erased)
        assertFalse(server.isTokenValid(session.accessToken))
    }
}
