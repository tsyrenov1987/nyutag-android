// Права в группе: роли, кто кого может выгнать и назначить, «пишут только админы», закреплённые реплики.

package chat.nyutag.core

import chat.nyutag.core.rooms.CommunityLink
import chat.nyutag.core.rooms.PinnedEvents
import chat.nyutag.core.rooms.RoomPowers
import chat.nyutag.core.rooms.RoomPowers.Role
import chat.nyutag.core.rooms.Roster
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomPowersTest {
    private val owner = "@owner:nyutag.chat"
    private val admin = "@admin:nyutag.chat"
    private val mod = "@mod:nyutag.chat"
    private val user = "@user:nyutag.chat"

    private val powers = RoomPowers(JsonValue.of(
        "users" to JsonValue.of(admin to 100, mod to 50),
        "users_default" to 0, "events_default" to 0, "state_default" to 50,
        "kick" to 50, "ban" to 50, "redact" to 50, "invite" to 0,
        "events" to JsonValue.of("m.room.power_levels" to 100, "m.room.name" to 50),
    ), creators = setOf(owner))

    @Test fun roles() {
        assertEquals(Role.OWNER, powers.role(owner))
        assertEquals(Role.ADMIN, powers.role(admin))
        assertEquals(Role.MODERATOR, powers.role(mod))
        assertEquals(Role.MEMBER, powers.role(user))
        assertEquals(Role.MEMBER, RoomPowers(null).role(user))
    }

    @Test fun kickAndBanOnlyLower() {
        assertTrue(powers.canKick(owner, admin))
        assertTrue(powers.canKick(admin, mod))
        assertTrue(powers.canBan(mod, user))
        assertFalse(powers.canKick(mod, admin))
        assertFalse(powers.canKick(admin, owner))
        assertFalse(powers.canKick(user, user))
        assertFalse(powers.canBan(user, mod))
        assertTrue(powers.canRedactOthers(mod))
        assertFalse(powers.canRedactOthers(user))
    }

    @Test fun setLevelRules() {
        assertTrue(powers.canSetLevel(owner, admin, 0))
        assertTrue(powers.canSetLevel(admin, user, 100))
        assertTrue(powers.canSetLevel(admin, admin, 0))
        assertFalse(powers.canSetLevel(admin, owner, 0))
        assertFalse(powers.canSetLevel(mod, user, 50))
        assertFalse(powers.canSetLevel(admin, "@peer:nyutag.chat", 101))
    }

    @Test fun settingWritesUsers() {
        val promoted = RoomPowers(powers.setting(user, 100), setOf(owner))
        assertEquals(Role.ADMIN, promoted.role(user))
        assertEquals(50, promoted.content["kick"]?.int)
        val demoted = powers.setting(admin, 0)
        assertNull(demoted["users"]?.get(admin))
        assertEquals(50, demoted["users"]?.get(mod)?.int)
    }

    @Test fun onlyAdminsWrite() {
        assertFalse(powers.onlyAdminsWrite)
        val closed = RoomPowers(powers.writing(onlyAdmins = true))
        assertTrue(closed.onlyAdminsWrite)
        assertFalse(closed.canSend(user))
        assertTrue(closed.canSend(user, EventType.REACTION))
        assertEquals(50, closed.content["events"]?.get("m.room.name")?.int)
        val open = RoomPowers(closed.writing(onlyAdmins = false))
        assertFalse(open.onlyAdminsWrite)
        assertTrue(open.canSend(user))
        assertNull(open.content["events"]?.get(EventType.REACTION))
    }

    @Test fun stateByEventsThenDefault() {
        assertTrue(powers.canSetState(mod, EventType.NAME))
        assertFalse(powers.canSetState(mod, EventType.POWER_LEVELS))
        assertTrue(powers.canSetState(mod, EventType.TOPIC))
        assertFalse(powers.canSetState(user, EventType.TOPIC))
    }

    @Test fun pinsNewestFirst() {
        var content = PinnedEvents.pinning("\$a", true, null)
        content = PinnedEvents.pinning("\$b", true, content)
        content = PinnedEvents.pinning("\$a", true, content)
        assertEquals(listOf("\$a", "\$b"), PinnedEvents.ids(content))
        assertEquals(listOf("\$b"), PinnedEvents.ids(PinnedEvents.pinning("\$a", false, content)))
    }

    @Test fun roster() {
        fun member(user: String, membership: String, name: String? = null): Event {
            val content = linkedMapOf<String, Any?>("membership" to membership)
            if (name != null) content["displayname"] = name
            return Event(type = EventType.MEMBER, sender = user, stateKey = user, content = JsonValue.wrap(content))
        }
        val roster = Roster(listOf(member(user, "join", "Ян"), member(mod, "join", "Аюр"), member(admin, "knock"),
                                   member(owner, "ban"), member("@gone:nyutag.chat", "leave")))
        assertEquals(listOf("Аюр", "Ян"), roster.joined.map { it.name })
        assertEquals(listOf("admin"), roster.knocking.map { it.name })
        assertEquals(listOf(owner), roster.banned.map { it.userID })
        assertTrue(roster.invited.isEmpty())
    }

    @Test fun communityLink() {
        val id = "!9qE29BLZcWtl8B3QGXxgiJOmuGfG2d-ba_c-_WKfp0s:nyutag.chat"
        val web = CommunityLink.web(id)
        assertTrue(web.startsWith("https://nyutag.chat/c/#"))
        assertEquals(id, CommunityLink.roomID(web))
        assertEquals(id, CommunityLink.roomID("nyutag://community/$id"))
        val v12 = "!m6hl-9fSCLT2wJcyTNukpgT3DBDqbYs-KJ1GT7TaJr4"
        assertEquals(v12, CommunityLink.roomID(CommunityLink.web(v12)))
        assertEquals(v12, CommunityLink.roomID("nyutag://community/$v12"))
        // Так ссылку копирует iOS: дефисы тоже закодированы.
        assertEquals(v12, CommunityLink.roomID("https://nyutag.chat/c/#%21m6hl%2D9fSCLT2wJcyTNukpgT3DBDqbYs%2DKJ1GT7TaJr4"))
        assertNull(CommunityLink.roomID("nyutag://invite/ABC"))
        assertNull(CommunityLink.roomID("https://nyutag.chat/i/#ABC"))
    }
}
