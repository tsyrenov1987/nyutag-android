// Нютаг. Список чатов из базы: заголовки, последняя реплика, галочки, порядок.

package chat.nyutag.core

import chat.nyutag.core.push.PushPreview
import chat.nyutag.core.rooms.ChatDigest
import chat.nyutag.core.rooms.ChatLine
import chat.nyutag.core.rooms.Relations
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatDigestTest {
    private val me = "@me:nyutag.chat"
    private val arjuna = "@arjuna:nyutag.chat"

    private fun member(user: String, roomID: String, name: String? = null, membership: String = "join", avatar: String? = null): Event {
        val content = LinkedHashMap<String, JsonValue>()
        content["membership"] = JsonValue.Str(membership)
        if (name != null) content["displayname"] = JsonValue.Str(name)
        if (avatar != null) content["avatar_url"] = JsonValue.Str(avatar)
        return Event(eventID = "\$m-$user-$roomID", type = EventType.MEMBER, sender = user, stateKey = user, content = JsonValue.Obj(content), roomID = roomID)
    }

    private fun text(id: String, roomID: String, sender: String, ts: Long, body: String): Event =
        Event(eventID = id, type = EventType.MESSAGE, sender = sender, originServerTS = ts, content = JsonValue.of("msgtype" to "m.text", "body" to body), roomID = roomID)

    private fun pair(store: Store, roomID: String = "!dm") {
        store.upsert(RoomRecord(roomID, RoomRecord.Membership.JOIN))
        store.setState(member(me, roomID))
        store.setState(member(arjuna, roomID))
    }

    @Test fun directTitleAndPreview() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord("!dm", RoomRecord.Membership.JOIN, isDirect = true, heroes = listOf(arjuna), notificationCount = 2))
        store.setState(member(me, "!dm", "Я"))
        store.setState(member(arjuna, "!dm", "Арюна"))
        store.append(listOf(
            text("\$1", "!dm", arjuna, 100, "Сайн байна!"),
            Event(eventID = "\$2", type = EventType.REACTION, sender = me, originServerTS = 110,
                  content = JsonValue.of("m.relates_to" to JsonValue.of("rel_type" to "m.annotation", "event_id" to "\$1", "key" to "👍")), roomID = "!dm"),
        ))
        val line = ChatDigest.lines(store, me).first()
        assertEquals("Арюна", line.title)
        assertEquals(PushPreview.Kind.Text("Сайн байна!"), line.last)
        assertEquals("Арюна", line.lastSenderName)
        assertFalse(line.lastIsMine)
        assertEquals(ChatLine.Delivery.NONE, line.delivery)
        assertEquals(100L, line.lastTS)
        assertEquals(2, line.unread)
    }

    @Test fun avatarFromPartner() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord("!dm", RoomRecord.Membership.JOIN, isDirect = true))
        store.upsert(RoomRecord("!named", RoomRecord.Membership.JOIN, avatarURL = "mxc://nyutag.chat/room"))
        store.upsert(RoomRecord("!group", RoomRecord.Membership.JOIN))
        store.upsert(RoomRecord("!pair", RoomRecord.Membership.JOIN, name = "Группа на двоих"))
        for (event in listOf(
            member(me, "!dm"), member(arjuna, "!dm", "Арюна", avatar = "mxc://nyutag.chat/arjuna"),
            member(me, "!named"), member(arjuna, "!named"),
            member(me, "!group"), member(arjuna, "!group"), member("@third:nyutag.chat", "!group"),
            member(me, "!pair"), member(arjuna, "!pair", "Арюна", avatar = "mxc://nyutag.chat/arjuna"),
        )) store.setState(event)
        val byID = ChatDigest.lines(store, me).associate { it.id to it.avatarURL }
        assertEquals("mxc://nyutag.chat/arjuna", byID["!dm"])
        assertEquals("mxc://nyutag.chat/room", byID["!named"])
        assertTrue(byID.containsKey("!group"))
        assertNull(byID["!group"])
        assertTrue(byID.containsKey("!pair"))
        assertNull(byID["!pair"])
    }

    @Test fun previewEditsAndDeletions() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        pair(store)
        store.append(listOf(
            text("\$1", "!dm", arjuna, 100, "сайн"),
            text("\$2", "!dm", me, 110, "черновик"),
            Event(eventID = "\$3", type = EventType.MESSAGE, sender = me, originServerTS = 120, content = Relations.edit("\$2", "набело"), roomID = "!dm"),
        ))
        assertEquals(PushPreview.Kind.Text("набело"), ChatDigest.lines(store, me).first().last)
        store.append(listOf(Event(eventID = "\$4", type = EventType.REDACTION, sender = me, originServerTS = 130, content = JsonValue.of("redacts" to "\$2"), roomID = "!dm")))
        val line = ChatDigest.lines(store, me).first()
        assertEquals(PushPreview.Kind.Text("сайн"), line.last)
        assertFalse(line.lastIsMine)
    }

    @Test fun previewBehindCallSignals() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        pair(store)
        val rings = (0 until 40).map { i ->
            Event(eventID = "\$r$i", type = "chat.nyutag.ring", sender = arjuna, originServerTS = 200L + i, content = JsonValue.of("slot" to "s$i"), roomID = "!dm")
        }
        store.append(listOf(text("\$1", "!dm", arjuna, 100, "перезвоню")) + rings)
        assertEquals(PushPreview.Kind.Text("перезвоню"), ChatDigest.lines(store, me).first().last)
    }

    @Test fun deliveryTicks() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        pair(store)
        store.append(listOf(text("\$1", "!dm", arjuna, 100, "привет"), text("\$2", "!dm", me, 200, "здарова")))
        assertEquals(ChatLine.Delivery.SENT, ChatDigest.lines(store, me).first().delivery)
        // Отметка на более раннем событии ещё не значит прочтения.
        store.setReceipt("!dm", arjuna, "\$1", 150)
        assertEquals(ChatLine.Delivery.SENT, ChatDigest.lines(store, me).first().delivery)
        // Своя отметка не считается.
        store.setReceipt("!dm", me, "\$2", 210)
        assertEquals(ChatLine.Delivery.SENT, ChatDigest.lines(store, me).first().delivery)
        store.setReceipt("!dm", arjuna, "\$2", 220)
        val line = ChatDigest.lines(store, me).first()
        assertEquals(ChatLine.Delivery.READ, line.delivery)
        assertTrue(line.lastIsMine)
    }

    @Test fun encryptedAndEdits() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord("!e", RoomRecord.Membership.JOIN))
        val sealed = Event(eventID = "\$s", type = EventType.ENCRYPTED, sender = arjuna, originServerTS = 300, content = JsonValue.of("algorithm" to "m.megolm.v1.aes-sha2"), roomID = "!e")
        val edit = Event(eventID = "\$x", type = EventType.MESSAGE, sender = arjuna, originServerTS = 310,
                         content = JsonValue.of("msgtype" to "m.text", "body" to "* правка", "m.relates_to" to JsonValue.of("rel_type" to "m.replace", "event_id" to "\$s")), roomID = "!e")
        store.append(listOf(sealed, edit))
        assertEquals(PushPreview.Kind.Locked, ChatDigest.lines(store, me).first().last)

        val lines = ChatDigest.lines(store, me, decrypt = { event, roomID ->
            Event(eventID = event.eventID, type = EventType.MESSAGE, sender = event.sender, originServerTS = event.originServerTS,
                  content = JsonValue.of("msgtype" to "m.image", "body" to "фото.jpg"), roomID = roomID)
        })
        assertEquals(PushPreview.Kind.Image, lines.first().last)
        assertEquals(300L, lines.first().lastTS)
    }

    @Test fun orderingAndTitles() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord("!old", RoomRecord.Membership.JOIN, name = "Старый"))
        store.upsert(RoomRecord("!fresh", RoomRecord.Membership.JOIN, canonicalAlias = "#hori:nyutag.chat"))
        store.upsert(RoomRecord("!inv", RoomRecord.Membership.INVITE))
        store.upsert(RoomRecord("!left", RoomRecord.Membership.LEAVE))
        store.upsert(RoomRecord("!grp", RoomRecord.Membership.JOIN))
        store.append(listOf(text("\$o", "!old", arjuna, 10, "a"), text("\$f", "!fresh", arjuna, 20, "b")))
        for (event in listOf(
            member(me, "!grp"), member("@bair:nyutag.chat", "!grp", "Баир"), member("@dasha:nyutag.chat", "!grp"),
            member("@gone:nyutag.chat", "!grp", membership = "leave"),
        )) store.setState(event)
        val lines = ChatDigest.lines(store, me)
        assertEquals(listOf("!inv", "!fresh", "!old", "!grp"), lines.map { it.id })
        assertEquals("#hori:nyutag.chat", lines[1].title)
        assertEquals("Старый", lines[2].title)
        assertEquals("Баир, dasha", lines[3].title)
        assertNull(lines[3].last)
        assertEquals("", lines[0].title)
    }

    @Test fun localpart() {
        assertEquals("arjuna", ChatDigest.localpart("@arjuna:nyutag.chat"))
        assertEquals("bare", ChatDigest.localpart("bare"))
    }

    @Test fun staleInvite() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord("!g", RoomRecord.Membership.JOIN))
        store.append(listOf(member(me, "!g", membership = "invite")))
        assertNull(ChatDigest.lines(store, me).first().last)
    }
}
