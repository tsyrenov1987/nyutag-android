// Нютаг. Лента чата: правки, реакции, удаления, ответы, служебные плашки, отметки прочтения, вложения.

package chat.nyutag.core

import chat.nyutag.core.media.Attachment
import chat.nyutag.core.media.MXC
import chat.nyutag.core.push.PushPreview
import chat.nyutag.core.rooms.ChatFeed
import chat.nyutag.core.rooms.FeedItem
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

class ChatFeedTest {
    private val me = "@me:nyutag.chat"
    private val arjuna = "@arjuna:nyutag.chat"
    private val room = "!r"

    private fun member(
        user: String, name: String? = null, membership: String = "join", by: String? = null,
        id: String? = null, ts: Long = 0, before: JsonValue? = null,
    ): Event {
        val content = LinkedHashMap<String, JsonValue>()
        content["membership"] = JsonValue.Str(membership)
        if (name != null) content["displayname"] = JsonValue.Str(name)
        return Event(
            eventID = id ?: "\$m-$user", type = EventType.MEMBER, sender = by ?: user, originServerTS = ts, stateKey = user,
            content = JsonValue.Obj(content), unsigned = before?.let { JsonValue.of("prev_content" to it) }, roomID = room,
        )
    }

    private fun text(id: String, from: String, ts: Long, body: String, extra: Map<String, JsonValue> = emptyMap()): Event {
        val content = LinkedHashMap<String, JsonValue>()
        content["msgtype"] = JsonValue.Str("m.text")
        content["body"] = JsonValue.Str(body)
        content.putAll(extra)
        return Event(eventID = id, type = EventType.MESSAGE, sender = from, originServerTS = ts, content = JsonValue.Obj(content), roomID = room)
    }

    private fun reaction(id: String, from: String, ts: Long, target: String, key: String): Event =
        Event(eventID = id, type = EventType.REACTION, sender = from, originServerTS = ts, content = Relations.reaction(target, key), roomID = room)

    private fun redaction(id: String, from: String, ts: Long, target: String): Event =
        Event(eventID = id, type = EventType.REDACTION, sender = from, originServerTS = ts, content = JsonValue.of("redacts" to target), roomID = room)

    private fun prepared(): Store {
        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord(room, RoomRecord.Membership.JOIN))
        store.setState(member(me, "Я"))
        store.setState(member(arjuna, "Арюна"))
        return store
    }

    @Test fun edits() = runBlocking {
        val store = prepared()
        store.append(listOf(
            text("\$1", arjuna, 100, "сайн"),
            text("\$2", arjuna, 110, "* сайн байна", mapOf(
                "m.new_content" to JsonValue.of("msgtype" to "m.text", "body" to "сайн байна"),
                "m.relates_to" to JsonValue.of("rel_type" to "m.replace", "event_id" to "\$1"),
            )),
            text("\$3", me, 120, "моё"),
            // Чужая «правка» моей реплики не принимается.
            text("\$4", arjuna, 130, "* подмена", mapOf(
                "m.new_content" to JsonValue.of("msgtype" to "m.text", "body" to "подмена"),
                "m.relates_to" to JsonValue.of("rel_type" to "m.replace", "event_id" to "\$3"),
            )),
        ))
        val items = ChatFeed.items(room, store, me)
        assertEquals(listOf("\$1", "\$3"), items.map { it.id })
        assertEquals(FeedItem.Body.Text("сайн байна"), items[0].body)
        assertTrue(items[0].edited)
        assertEquals("Арюна", items[0].senderName)
        assertEquals(FeedItem.Body.Text("моё"), items[1].body)
        assertFalse(items[1].edited)
        assertTrue(items[1].isMine)
    }

    @Test fun reactionsAndRedactions() = runBlocking {
        val store = prepared()
        store.append(listOf(
            text("\$1", arjuna, 100, "хурдан"),
            text("\$2", arjuna, 101, "удалю"),
            reaction("\$r1", me, 200, "\$1", "👍"),
            reaction("\$r2", arjuna, 200, "\$1", "👍"),
            reaction("\$r3", arjuna, 200, "\$1", "🔥"),
            reaction("\$r4", me, 200, "\$2", "👍"),
            redaction("\$x", arjuna, 300, "\$2"),
        ))
        val items = ChatFeed.items(room, store, me)
        val first = items.first { it.id == "\$1" }
        assertEquals(listOf(FeedItem.Reaction("👍", 2, true, "\$r1"), FeedItem.Reaction("🔥", 1, false)), first.reactions)
        val second = items.first { it.id == "\$2" }
        assertEquals(FeedItem.Body.Deleted, second.body)
        assertTrue(second.reactions.isEmpty())
        assertTrue(items.none { it.id == "\$x" || it.id.startsWith("\$r") })
    }

    @Test fun ownActions() = runBlocking {
        val store = prepared()
        store.append(listOf(
            text("\$1", me, 100, "сайн"),
            text("\$2", arjuna, 101, "байна"),
            Event(eventID = "\$r1", type = EventType.REACTION, sender = me, originServerTS = 102, content = Relations.reaction("\$2", "❤️"), roomID = room),
            Event(eventID = "\$e1", type = EventType.MESSAGE, sender = me, originServerTS = 103, content = Relations.edit("\$1", "сайн байна"), roomID = room),
            redaction("\$x", me, 104, "\$r1"),
        ))
        val items = ChatFeed.items(room, store, me)
        assertEquals(listOf("\$1", "\$2"), items.map { it.id })
        assertEquals(FeedItem.Body.Text("сайн байна"), items[0].body)
        assertTrue(items[0].edited && items[0].canEdit && items[0].canDelete)
        assertFalse(items[1].canEdit)
        assertFalse(items[1].canDelete)
        assertTrue(items[1].reactions.isEmpty())
    }

    @Test fun typing() {
        val ephemeral = listOf(Event(type = EventType.TYPING, content = JsonValue.of("user_ids" to listOf(me, arjuna)), roomID = room))
        assertEquals(listOf(arjuna), Relations.typing(ephemeral, me))
        assertNull(Relations.typing(emptyList(), me))
    }

    @Test fun repliesAndEncryption() = runBlocking {
        val store = prepared()
        store.append(listOf(
            Event(eventID = "\$s", type = EventType.ENCRYPTED, sender = arjuna, originServerTS = 100, content = JsonValue.of("algorithm" to "m.megolm.v1.aes-sha2"), roomID = room),
            text("\$a", me, 110, "> <@arjuna:nyutag.chat> ...\n\nответ", mapOf(
                "m.relates_to" to JsonValue.of("m.in_reply_to" to JsonValue.of("event_id" to "\$s")),
            )),
        ))
        val locked = ChatFeed.items(room, store, me)
        assertEquals(FeedItem.Body.Locked, locked.first { it.id == "\$s" }.body)
        assertEquals(PushPreview.Kind.Locked, locked.first { it.id == "\$a" }.reply?.preview)

        val opened = ChatFeed.items(room, store, me, decrypt = { event, roomID ->
            if (event.eventID == "\$s") {
                Event(eventID = "\$s", type = EventType.MESSAGE, sender = event.sender, originServerTS = event.originServerTS,
                      content = JsonValue.of("msgtype" to "m.text", "body" to "тайна"), roomID = roomID)
            } else null
        })
        assertEquals(FeedItem.Body.Text("тайна"), opened.first { it.id == "\$s" }.body)
        val answer = opened.first { it.id == "\$a" }
        assertEquals(FeedItem.Body.Text("ответ"), answer.body)
        assertEquals(FeedItem.Quote("\$s", "Арюна", PushPreview.Kind.Text("тайна")), answer.reply)
    }

    @Test fun notices() = runBlocking {
        val store = prepared()
        val algorithm = JsonValue.of("algorithm" to "m.megolm.v1.aes-sha2")
        store.append(listOf(
            Event(eventID = "\$c", type = EventType.CREATE, sender = me, originServerTS = 1, stateKey = "", content = JsonValue.Obj(emptyMap()), roomID = room),
            member(me, "Я", id = "\$own", ts = 1),
            Event(eventID = "\$e", type = EventType.ENCRYPTION, sender = me, originServerTS = 2, stateKey = "", content = algorithm, roomID = room),
            member(arjuna, "Арюна", "invite", by = me, id = "\$i", ts = 3),
            member(arjuna, "Арюна", id = "\$j", ts = 4, before = JsonValue.of("membership" to "invite")),
            member(arjuna, "Арюна Д.", id = "\$n", ts = 5, before = JsonValue.of("membership" to "join", "displayname" to "Арюна")),
            member(arjuna, "Арюна Д.", id = "\$same", ts = 6, before = JsonValue.of("membership" to "join", "displayname" to "Арюна Д.")),
            member(arjuna, membership = "leave", by = me, id = "\$k", ts = 7, before = JsonValue.of("membership" to "join", "displayname" to "Арюна Д.")),
        ))
        val notices = ChatFeed.items(room, store, me).mapNotNull { (it.body as? FeedItem.Body.Notice)?.notice }
        assertEquals(listOf(
            FeedItem.Notice.Created("Я"),
            FeedItem.Notice.EncryptionOn("Я"),
            FeedItem.Notice.Invited("Арюна", "Я"),
            FeedItem.Notice.Joined("Арюна"),
            FeedItem.Notice.RenamedSelf("Арюна", "Арюна Д."),
            FeedItem.Notice.Removed("Арюна Д.", "Я"),
        ), notices)
    }

    @Test fun readMarks() = runBlocking {
        val store = prepared()
        store.append(listOf(text("\$1", me, 100, "а"), text("\$2", arjuna, 200, "б"), text("\$3", me, 300, "в")))
        store.setReceipt(room, arjuna, "\$2", 250)
        val items = ChatFeed.items(room, store, me).associateBy { it.id }
        assertTrue(items["\$1"]!!.isRead)
        assertFalse(items["\$2"]!!.isRead)
        assertFalse(items["\$3"]!!.isRead)
    }

    @Test fun attachments() = runBlocking {
        val store = prepared()
        store.append(listOf(
            Event(eventID = "\$p", type = EventType.MESSAGE, sender = arjuna, originServerTS = 1,
                  content = JsonValue.of("msgtype" to "m.image", "body" to "IMG_1.jpg", "url" to "mxc://x/y"), roomID = room),
            Event(eventID = "\$q", type = EventType.MESSAGE, sender = arjuna, originServerTS = 2,
                  content = JsonValue.of("msgtype" to "m.image", "body" to "Байкал", "filename" to "IMG_2.jpg", "url" to "mxc://x/z"), roomID = room),
        ))
        val items = ChatFeed.items(room, store, me).associateBy { it.id }
        assertEquals(FeedItem.Body.Attachment(PushPreview.Kind.Image, null), items["\$p"]!!.body)
        assertEquals(FeedItem.Body.Attachment(PushPreview.Kind.Image, "Байкал"), items["\$q"]!!.body)
        assertEquals("IMG_2.jpg", items["\$q"]!!.attachment?.name)
        assertEquals(Attachment.Source.Plain(MXC.parse("mxc://x/z")!!), items["\$q"]!!.attachment?.source)
    }
}
