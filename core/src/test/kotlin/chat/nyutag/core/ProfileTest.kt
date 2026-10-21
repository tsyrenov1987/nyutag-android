// Нютаг. Профиль: разбор, пути запросов, поля MSC4133 и личные чаты m.direct.

package chat.nyutag.core

import chat.nyutag.core.net.HttpRequest
import chat.nyutag.core.net.HttpResponse
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.net.Transport
import chat.nyutag.core.rooms.NyutagProfileKey
import chat.nyutag.core.rooms.Profile
import chat.nyutag.core.rooms.ProfileFieldsAPI
import chat.nyutag.core.rooms.addDirectRoom
import chat.nyutag.core.rooms.applyProfileEdits
import chat.nyutag.core.rooms.createDirectRoom
import chat.nyutag.core.rooms.deleteProfileField
import chat.nyutag.core.rooms.directRoom
import chat.nyutag.core.rooms.directRooms
import chat.nyutag.core.rooms.profile
import chat.nyutag.core.rooms.profileFieldsAPI
import chat.nyutag.core.rooms.setAccountData
import chat.nyutag.core.rooms.setAvatarURL
import chat.nyutag.core.rooms.setDisplayName
import chat.nyutag.core.rooms.setProfileField
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileTest {
    private val me = "@me:nyutag.chat"
    private val other = "@sayan:nyutag.chat"

    @Test fun fullProfileParsing() = runBlocking {
        val transport = StubTransport()
        transport.response = JsonValue.parse("""{"displayname":"Саян","avatar_url":"mxc://x/a","chat.nyutag.about":"привет","chat.nyutag.ug":""}""")
        val profile = stubClient(transport).profile(other)
        assertEquals("Саян", profile.displayName)
        assertEquals("mxc://x/a", profile.avatarURL)
        assertEquals("привет", profile[NyutagProfileKey.ABOUT])
        assertNull(profile[NyutagProfileKey.UG])
        assertNull(profile[NyutagProfileKey.TOONTO])
        assertEquals("GET", transport.last?.method)
        assertEquals("/_matrix/client/v3/profile/@sayan:nyutag.chat", transport.last?.path)
    }

    @Test fun missingProfileIsEmpty() = runBlocking {
        val transport = StubTransport()
        transport.status = 404
        transport.response = JsonValue.of("errcode" to "M_NOT_FOUND", "error" to "нет")
        assertEquals(Profile(), stubClient(transport).profile(other))
    }

    @Test fun displayNameAndAvatarPaths() = runBlocking {
        val transport = StubTransport()
        val client = stubClient(transport)
        client.setDisplayName(me, "Нютаг")
        assertEquals("PUT", transport.last?.method)
        assertEquals("/_matrix/client/v3/profile/@me:nyutag.chat/displayname", transport.last?.path)
        assertEquals(JsonValue.of("displayname" to "Нютаг"), transport.last?.json)
        client.setAvatarURL(me, "mxc://x/b")
        assertEquals("/_matrix/client/v3/profile/@me:nyutag.chat/avatar_url", transport.last?.path)
        assertEquals(JsonValue.of("avatar_url" to "mxc://x/b"), transport.last?.json)
    }

    @Test fun capabilitiesChooseAPI() {
        fun api(text: String) = ProfileFieldsAPI.from(JsonValue.parse(text))
        assertEquals(ProfileFieldsAPI.STABLE, api("""{"capabilities":{"m.profile_fields":{"enabled":true}}}"""))
        assertEquals(ProfileFieldsAPI.UNSTABLE, api("""{"capabilities":{"uk.tcpip.msc4133.profile_fields":{"enabled":true}}}"""))
        assertEquals(ProfileFieldsAPI.DISABLED, api("""{"capabilities":{"m.profile_fields":{"enabled":false}}}"""))
        assertEquals(ProfileFieldsAPI.STABLE, api("""{"capabilities":{"m.change_password":{"enabled":true}}}"""))
        assertEquals(ProfileFieldsAPI.STABLE, api("""{"capabilities":{"m.profile_fields":{"enabled":true},"uk.tcpip.msc4133.profile_fields":{"enabled":true}}}"""))
    }

    @Test fun capabilitiesRequestIsRead() = runBlocking {
        val transport = StubTransport()
        transport.response = JsonValue.parse("""{"capabilities":{"uk.tcpip.msc4133.profile_fields":{"enabled":true}}}""")
        assertEquals(ProfileFieldsAPI.UNSTABLE, stubClient(transport).profileFieldsAPI())
        assertEquals("/_matrix/client/v3/capabilities", transport.last?.path)
    }

    @Test fun stableFieldPathAndBody() = runBlocking {
        val transport = StubTransport()
        stubClient(transport).setProfileField(me, NyutagProfileKey.TOONTO.rawValue, JsonValue.Str("Хубсугул"))
        assertEquals("PUT", transport.last?.method)
        assertEquals("/_matrix/client/v3/profile/@me:nyutag.chat/chat.nyutag.toonto", transport.last?.path)
        assertEquals(JsonValue.of("chat.nyutag.toonto" to "Хубсугул"), transport.last?.json)
    }

    @Test fun unstableFieldPath() = runBlocking {
        val transport = StubTransport()
        stubClient(transport).setProfileField(me, "chat.nyutag.about", JsonValue.Str("x"), ProfileFieldsAPI.UNSTABLE)
        assertEquals("/_matrix/client/unstable/uk.tcpip.msc4133/profile/@me:nyutag.chat/chat.nyutag.about", transport.last?.path)
    }

    @Test fun disabledFieldsAreRefusedWithoutRequest() {
        val transport = StubTransport()
        assertThrows(MatrixError::class.java) {
            runBlocking { stubClient(transport).setProfileField(me, "chat.nyutag.about", JsonValue.Str("x"), ProfileFieldsAPI.DISABLED) }
        }
        assertEquals(0, transport.hits)
    }

    @Test fun deleteFieldTolerates404() = runBlocking {
        val transport = StubTransport()
        transport.status = 404
        transport.response = JsonValue.of("errcode" to "M_NOT_FOUND", "error" to "нет")
        stubClient(transport).deleteProfileField(me, "chat.nyutag.ug")
        assertEquals("DELETE", transport.last?.method)
        assertEquals("/_matrix/client/v3/profile/@me:nyutag.chat/chat.nyutag.ug", transport.last?.path)
    }

    @Test fun editsWriteAndDeleteInKeyOrder() = runBlocking {
        val transport = StubTransport()
        stubClient(transport).applyProfileEdits(
            me, mapOf(NyutagProfileKey.ABOUT to "стало", NyutagProfileKey.UG to null, NyutagProfileKey.TOONTO to ""), ProfileFieldsAPI.STABLE,
        )
        assertEquals(listOf("PUT", "DELETE", "DELETE"), transport.requests.map { it.method })
        assertEquals(listOf("chat.nyutag.about", "chat.nyutag.toonto", "chat.nyutag.ug"), transport.requests.map { it.path.substringAfterLast('/') })
        assertEquals(JsonValue.of("chat.nyutag.about" to "стало"), transport.requests[0].json)
    }

    // MARK: m.direct

    private class Counting(private val inner: Transport) : Transport {
        var puts = 0
        override suspend fun send(request: HttpRequest): HttpResponse {
            if (request.method == "PUT") puts += 1
            return inner.send(request)
        }
    }

    private fun direct(vararg pairs: Pair<String, List<String>>): JsonValue = JsonValue.Obj(pairs.associate { (user, rooms) -> user to JsonValue.Arr(rooms.map { JsonValue.Str(it) }) })

    @Test fun missingDirectIsEmptyDictionary() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(me, "D1"))
        assertTrue(client.directRooms(me).isEmpty())
    }

    @Test fun directParsingAndLookup() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(me, "D1"))
        client.setAccountData(me, EventType.DIRECT, content = JsonValue.parse("""{"@a:x":["!1:x","!2:x"],"@b:x":["!3:x"],"@bad:x":"не массив"}"""))
        val rooms = client.directRooms(me)
        assertEquals(listOf("!1:x", "!2:x"), rooms["@a:x"])
        assertEquals(emptyList<String>(), rooms["@bad:x"])
        assertEquals("!3:x", client.directRoom(me, "@b:x"))
        assertNull(client.directRoom(me, "@nobody:x"))
    }

    @Test fun addDirectKeepsOthersEntries() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(me, "D1"))
        client.setAccountData(me, EventType.DIRECT, content = direct("@a:x" to listOf("!1:x"), "@b:x" to listOf("!3:x"), other to listOf("!old:x")))
        client.addDirectRoom(me, other, "!new:x")
        assertEquals(direct("@a:x" to listOf("!1:x"), "@b:x" to listOf("!3:x"), other to listOf("!old:x", "!new:x")), server.accountDataContent(me, EventType.DIRECT))
    }

    @Test fun addDirectToMissingAccountData() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(me, "D1"))
        client.addDirectRoom(me, other, "!new:x")
        assertEquals(direct(other to listOf("!new:x")), server.accountDataContent(me, EventType.DIRECT))
    }

    @Test fun addDirectIsIdempotent() = runBlocking {
        val server = FakeHomeserver()
        val session = server.session(me, "D1")
        val counting = Counting(server)
        val client = MatrixClient(server.homeserver, session.accessToken, transport = counting)
        client.setAccountData(me, EventType.DIRECT, content = direct(other to listOf("!new:x")))
        val before = counting.puts
        client.addDirectRoom(me, other, "!new:x")
        assertEquals(before, counting.puts)
    }

    @Test fun createDirectRoomEncrypted() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(me, "D1"))
        client.setAccountData(me, EventType.DIRECT, content = direct("@a:x" to listOf("!1:x")))
        val roomID = client.createDirectRoom(me, other)
        assertEquals("!new:nyutag.chat", roomID)
        val body = server.createdRooms.last()
        assertEquals(true, body["is_direct"]?.bool)
        assertEquals("trusted_private_chat", body["preset"]?.string)
        assertEquals(listOf(JsonValue.Str(other)), body["invite"]?.array)
        assertEquals("m.room.encryption", body["initial_state"]?.array?.first()?.get("type")?.string)
        assertEquals(direct("@a:x" to listOf("!1:x"), other to listOf("!new:nyutag.chat")), server.accountDataContent(me, EventType.DIRECT))
    }

    @Test fun createDirectRoomUnencrypted() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(me, "D1"))
        client.createDirectRoom(me, other, encrypted = false)
        val body = server.createdRooms.last()
        assertNull(body["initial_state"])
        assertEquals(true, body["is_direct"]?.bool)
    }
}
