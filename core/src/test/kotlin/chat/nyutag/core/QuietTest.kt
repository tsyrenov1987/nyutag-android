// Нютаг. Тишина: правила пушей комнаты, участники и знакомые.

package chat.nyutag.core

import chat.nyutag.core.rooms.MutedRooms
import chat.nyutag.core.rooms.People
import chat.nyutag.core.rooms.setRoomMuted
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuietTest {
    private val me = "@me:nyutag.chat"

    private fun member(room: String, user: String, membership: String, name: String? = null, avatar: String? = null): Event {
        val content = LinkedHashMap<String, JsonValue>()
        content["membership"] = JsonValue.Str(membership)
        if (name != null) content["displayname"] = JsonValue.Str(name)
        if (avatar != null) content["avatar_url"] = JsonValue.Str(avatar)
        return Event(eventID = "\$$room-$user", type = EventType.MEMBER, sender = user, stateKey = user, content = JsonValue.Obj(content), roomID = room)
    }

    @Test fun readsMutedRooms() {
        val json = JsonValue.parse(
            """{"global":{"override":[],"room":[
                {"rule_id":"!quiet:x","enabled":true,"actions":[]},
                {"rule_id":"!dont:x","enabled":true,"actions":["dont_notify"]},
                {"rule_id":"!loud:x","enabled":true,"actions":["notify",{"set_tweak":"sound","value":"default"}]},
                {"rule_id":"!off:x","enabled":false,"actions":[]}]}}""",
        )
        assertEquals(setOf("!quiet:x", "!dont:x"), MutedRooms.rooms(json))
        assertTrue(MutedRooms.rooms(null).isEmpty())
    }

    @Test fun applyKeepsOtherRules() {
        val json = JsonValue.parse("""{"global":{"override":[{"rule_id":"nyutag.ring","actions":["notify"]}],"room":[]}}""")
        val on = MutedRooms.apply(json, "!a:x", true)
        assertEquals(setOf("!a:x"), MutedRooms.rooms(on))
        val twice = MutedRooms.apply(on, "!a:x", true)
        assertEquals(1, twice["global"]?.get("room")?.array?.size)
        assertEquals(1, twice["global"]?.get("override")?.array?.size)
        assertTrue(MutedRooms.rooms(MutedRooms.apply(twice, "!a:x", false)).isEmpty())
        assertEquals(setOf("!b:x"), MutedRooms.rooms(MutedRooms.apply(null, "!b:x", true)))
    }

    @Test fun muteGoesToServer() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(me, "D1"))
        val store = Store(JdbcSql.inMemory())
        client.setRoomMuted("!g:nyutag.chat", true, store)
        assertTrue(server.pushRule(me, "room", "!g:nyutag.chat")!!["actions"]!!.array!!.isEmpty())
        assertEquals(setOf("!g:nyutag.chat"), MutedRooms.load(store))
        client.setRoomMuted("!g:nyutag.chat", false, store)
        assertNull(server.pushRule(me, "room", "!g:nyutag.chat"))
        assertTrue(MutedRooms.load(store).isEmpty())
        client.setRoomMuted("!g:nyutag.chat", false, store)
    }

    @Test fun membersOfRoom() {
        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord("!g", RoomRecord.Membership.JOIN))
        for (event in listOf(
            member("!g", me, "join", "Я"),
            member("!g", "@bair:nyutag.chat", "join", "Баир", "mxc://nyutag.chat/b"),
            member("!g", "@dari:nyutag.chat", "invite", ""),
            member("!g", "@gone:nyutag.chat", "leave", "Ушёл"),
            member("!g", "@aldar:nyutag.chat", "join", "Алдар"),
        )) store.setState(event)
        val members = People.members("!g", store, me)
        assertEquals(listOf("@aldar:nyutag.chat", "@bair:nyutag.chat", "@dari:nyutag.chat"), members.map { it.userID })
        assertEquals(listOf("Алдар", "Баир", "dari"), members.map { it.name })
        assertEquals("mxc://nyutag.chat/b", members[1].avatarURL)
    }

    @Test fun acquaintances() {
        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord("!space", RoomRecord.Membership.JOIN, isSpace = true))
        store.upsert(RoomRecord("!g", RoomRecord.Membership.JOIN))
        store.upsert(RoomRecord("!dm", RoomRecord.Membership.JOIN))
        store.upsert(RoomRecord("!old", RoomRecord.Membership.LEAVE))
        for (event in listOf(
            member("!space", "@stranger:nyutag.chat", "join", "Чужой"),
            member("!g", "@bair:nyutag.chat", "join", "Баир"),
            member("!g", me, "join"),
            member("!dm", "@bair:nyutag.chat", "join", "Баир Д."),
            member("!dm", me, "join"),
            member("!old", "@gone:nyutag.chat", "join"),
        )) store.setState(event)
        store.setAccountData(EventType.DIRECT, content = JsonValue.parse("""{"@bair:nyutag.chat":["!dm"]}"""))
        val people = People.acquaintances(store, me)
        assertEquals(listOf("@bair:nyutag.chat"), people.map { it.userID })
        assertEquals("Баир Д.", people.first().name)
        assertEquals("!dm", people.first().directRoom)
    }
}
