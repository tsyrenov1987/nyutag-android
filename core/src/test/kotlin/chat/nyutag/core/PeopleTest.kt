// Нютаг. Люди: номера телефонов, сведение адресной книги с найденными, знакомые и собеседник в личном чате.

package chat.nyutag.core

import chat.nyutag.core.rooms.BookEntry
import chat.nyutag.core.rooms.People
import chat.nyutag.core.rooms.Person
import chat.nyutag.core.rooms.creators
import chat.nyutag.core.rooms.joinedMembers
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class PeopleTest {
    private val me = "@me:nyutag.chat"

    private fun member(room: String, user: String, membership: String = "join", name: String? = null): Event {
        val content = LinkedHashMap<String, JsonValue>()
        content["membership"] = JsonValue.Str(membership)
        if (name != null) content["displayname"] = JsonValue.Str(name)
        return Event.from(JsonValue.of(
            "type" to EventType.MEMBER, "state_key" to user, "room_id" to room, "sender" to user,
            "event_id" to "\$$room-$user", "content" to JsonValue.Obj(content),
        ))!!
    }

    @Test fun phoneKeys() {
        assertEquals("+79123456789", People.phoneKey("8 (912) 345-67-89"))
        assertEquals("+79123456789", People.phoneKey("9123456789"))
        assertEquals("+79123456789", People.phoneKey("+7 912 345 67 89"))
        assertEquals("+97699112233", People.phoneKey("+976 9911 2233"))
        assertNull(People.phoneKey("112"))
    }

    @Test fun uniquePhones() {
        val book = listOf(
            BookEntry("1", "Аня", listOf("8 912 345-67-89", "+79123456789")),
            BookEntry("2", "Баир", listOf("9123456789", "+7 900 000 00 01")),
        )
        assertEquals(listOf("8 912 345-67-89", "+7 900 000 00 01"), People.uniquePhones(book))
    }

    @Test fun merge() {
        val book = listOf(
            BookEntry("1", "Сэсэг", listOf("8 912 345-67-89")),
            BookEntry("2", "Арюна", listOf("+7 900 000 00 01")),
            BookEntry("3", "Баир", listOf("+7 900 000 00 02")),
        )
        val known = listOf(
            Person("@sesegma:nyutag.chat", "sesegma", directRoom = "!a"),
            Person("@tumen:nyutag.chat", "Түмэн", directRoom = "!b"),
        )
        val matches = mapOf("+79123456789" to "@sesegma:nyutag.chat", "+7 900 000 00 01" to "@aryuna:nyutag.chat")
        val merged = People.merge(book, matches, known)
        assertEquals(listOf("Арюна", "Сэсэг", "Түмэн"), merged.inNyutag.map { it.name })
        assertEquals("!a", merged.inNyutag.first { it.userID == "@sesegma:nyutag.chat" }.directRoom)
        assertNull(merged.inNyutag.first { it.userID == "@aryuna:nyutag.chat" }.directRoom)
        assertEquals(listOf("Баир"), merged.invite.map { it.name })
    }

    @Test fun matches() {
        assertTrue(People.matches("сэс", "Сэсэг"))
        assertTrue(People.matches("@sese", "Сэсэг", userID = "@sesegma:nyutag.chat"))
        assertTrue(People.matches("8 912 345", "Сэсэг", phones = listOf("+7 912 345-67-89")))
        assertTrue(People.matches("89123456789", "Сэсэг", phones = listOf("+7 912 345-67-89")))
        assertFalse(People.matches("12", "Сэсэг", phones = listOf("+7 912 345-67-89")))
        assertFalse(People.matches("баир", "Сэсэг"))
        assertTrue(People.matches("  ", "Сэсэг"))
    }

    @Test fun knownFromDirectChats() {
        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord("!a", RoomRecord.Membership.JOIN, isDirect = true))
        store.setAccountData(EventType.DIRECT, content = JsonValue.parse("""{"@sesegma:nyutag.chat":["!a"],"@gone:nyutag.chat":["!x"]}"""))
        store.setState(member("!a", "@sesegma:nyutag.chat", name = "Сэсэгма"))
        assertEquals(listOf(Person("@sesegma:nyutag.chat", "Сэсэгма", directRoom = "!a")), People.known(store, me))
    }

    @Test fun partner() {
        val store = Store(JdbcSql.inMemory())
        store.setAccountData(EventType.DIRECT, content = JsonValue.parse("""{"@sesegma:nyutag.chat":["!a"]}"""))
        val bair = "@bair:nyutag.chat"
        val dulma = "@dulma:nyutag.chat"
        for (event in listOf(
            member("!b", me), member("!b", bair),
            member("!c", me), member("!c", bair), member("!c", dulma),
            member("!d", me), member("!d", dulma), member("!d", bair, membership = "leave"),
        )) store.setState(event)
        assertEquals("@sesegma:nyutag.chat", People.partner("!a", store, me))
        assertEquals(bair, People.partner("!b", store, me))
        assertNull(People.partner("!c", store, me))
        assertEquals(dulma, People.partner("!d", store, me))
    }

    @Test fun communityMembersByName() = runBlocking {
        val transport = StubTransport()
        transport.response = JsonValue.parse("""{"joined":{"@me:nyutag.chat":{"display_name":"Я"},
            "@sayan:nyutag.chat":{"display_name":"Саян","avatar_url":"mxc://x/s"},"@bair:nyutag.chat":{}}}""")
        val people = stubClient(transport).joinedMembers("!c:nyutag.chat")
        assertEquals(3, people.size)
        assertEquals(listOf("Саян", "Я", "bair"), people.map { it.name })
        assertEquals("mxc://x/s", people[0].avatarURL)
        assertEquals("/_matrix/client/v3/rooms/!c:nyutag.chat/joined_members", transport.last?.path)
    }

    @Test fun roomCreatorsByVersion() = runBlocking {
        val transport = StubTransport()
        transport.response = JsonValue.parse("""{"type":"m.room.create","sender":"@dulma:nyutag.chat",
            "content":{"room_version":"12","additional_creators":["@bair:nyutag.chat"]}}""")
        val client = stubClient(transport)
        assertEquals(setOf("@dulma:nyutag.chat", "@bair:nyutag.chat"), client.creators("!c:nyutag.chat"))
        assertEquals("format=event", transport.last?.url?.query)
        transport.response = JsonValue.parse("""{"type":"m.room.create","sender":"@dulma:nyutag.chat","content":{"room_version":"11"}}""")
        assertEquals(emptySet<String>(), client.creators("!c:nyutag.chat"))
    }
}
