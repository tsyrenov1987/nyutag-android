// Нютаг. Каталог комнат, иерархия сообществ, стук в дверь, псевдонимы.

package chat.nyutag.core

import chat.nyutag.core.media.MXC
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.rooms.PublicRoomsFilter
import chat.nyutag.core.rooms.addSpaceChild
import chat.nyutag.core.rooms.knock
import chat.nyutag.core.rooms.publicCommunities
import chat.nyutag.core.rooms.publicRooms
import chat.nyutag.core.rooms.removeSpaceChild
import chat.nyutag.core.rooms.removeSpaceParent
import chat.nyutag.core.rooms.resolveAlias
import chat.nyutag.core.rooms.roomHierarchy
import chat.nyutag.core.rooms.roomVisibility
import chat.nyutag.core.rooms.setRoomVisibility
import chat.nyutag.core.rooms.setSpaceParent
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirectoryTest {
    private val publicRoomsJSON = """
    {"chunk":[
      {"room_id":"!sp:nyutag.chat","name":"Буряад хэлэн","topic":"Сообщество","canonical_alias":"#buryad:nyutag.chat",
       "avatar_url":"mxc://nyutag.chat/abc123","num_joined_members":42,"world_readable":true,"guest_can_join":false,
       "join_rule":"public","room_type":"m.space"},
      {"room_id":"!plain:nyutag.chat","num_joined_members":3,"world_readable":false,"guest_can_join":true},
      {"name":"без room_id"}
    ],"next_batch":"n1","prev_batch":"p1","total_room_count_estimate":115}
    """

    private fun stub(response: JsonValue = JsonValue.Obj(emptyMap()), status: Int = 200): StubTransport =
        StubTransport().also { it.response = response; it.status = status }

    private val empty = JsonValue.of("chunk" to emptyList<Any>())

    @Test fun publicRoomsParsing() = runBlocking {
        val transport = stub(JsonValue.parse(publicRoomsJSON))
        val page = stubClient(transport).publicRooms(limit = 20)
        assertEquals(2, page.rooms.size)
        val space = page.rooms.first()
        assertEquals("!sp:nyutag.chat", space.roomID)
        assertEquals("Буряад хэлэн", space.name)
        assertEquals("Сообщество", space.topic)
        assertEquals("#buryad:nyutag.chat", space.canonicalAlias)
        assertEquals(MXC.parse("mxc://nyutag.chat/abc123"), space.avatarURL)
        assertEquals(42, space.numJoinedMembers)
        assertTrue(space.worldReadable)
        assertFalse(space.guestCanJoin)
        assertEquals("public", space.joinRule)
        assertTrue(space.isSpace)
        val plain = page.rooms[1]
        assertNull(plain.name); assertNull(plain.avatarURL); assertNull(plain.roomType)
        assertTrue(plain.guestCanJoin); assertFalse(plain.isSpace)
        assertEquals("n1", page.nextBatch); assertEquals("p1", page.prevBatch); assertEquals(115, page.totalRoomCountEstimate)
    }

    @Test fun publicRoomsRequestShape() = runBlocking {
        val transport = stub(empty)
        val filter = PublicRoomsFilter(genericSearchTerm = "бурятия", roomTypes = listOf("m.space", null))
        stubClient(transport).publicRooms(server = "other.example", limit = 10, since = "tok1", filter = filter, includeAllNetworks = true)
        val request = transport.last!!
        assertEquals("POST", request.method)
        assertEquals("/_matrix/client/v3/publicRooms", request.path)
        assertEquals(mapOf("server" to "other.example"), request.queryMap)
        val body = request.json
        assertEquals(10, body["limit"]?.int)
        assertEquals("tok1", body["since"]?.string)
        assertEquals(true, body["include_all_networks"]?.bool)
        assertEquals("бурятия", body.at("filter", "generic_search_term")?.string)
        assertEquals(JsonValue.Arr(listOf(JsonValue.Str("m.space"), JsonValue.Null)), body.at("filter", "room_types"))
        assertEquals("Bearer tok", request.header("Authorization"))
    }

    @Test fun publicRoomsMinimalBodyOmitsOptionals() = runBlocking {
        val transport = stub(empty)
        stubClient(transport).publicRooms()
        val request = transport.last!!
        assertTrue(request.queryItems.isEmpty())
        assertEquals(JsonValue.Obj(emptyMap()), request.json)
    }

    @Test fun communitiesFilterIsSpaceOnly() = runBlocking {
        val transport = stub(empty)
        stubClient(transport).publicCommunities(search = "нютаг")
        val body = transport.last!!.json
        assertEquals(JsonValue.Arr(listOf(JsonValue.Str("m.space"))), body.at("filter", "room_types"))
        assertEquals("нютаг", body.at("filter", "generic_search_term")?.string)
    }

    @Test fun publicRoomsWithoutChunkIsDecodingError() {
        val transport = stub(JsonValue.of("next_batch" to "x"))
        assertThrows(MatrixError::class.java) { runBlocking { stubClient(transport).publicRooms() } }
    }

    @Test fun hierarchyParsingAndRequest() = runBlocking {
        val json = JsonValue.parse(
            """{"rooms":[
              {"room_id":"!sp:x","name":"Space","num_joined_members":5,"world_readable":false,"guest_can_join":false,
               "room_type":"m.space","join_rule":"public",
               "children_state":[
                 {"type":"m.space.child","state_key":"!a:x","sender":"@o:x","origin_server_ts":1700,
                  "content":{"via":["x","y"],"suggested":true,"order":"01"}},
                 {"type":"m.space.child","state_key":"!b:x","sender":"@o:x","origin_server_ts":1701,"content":{"via":["x"]}},
                 {"type":"m.room.member","state_key":"@u:x","content":{}}
               ]},
              {"room_id":"!a:x","name":"A","num_joined_members":2,"world_readable":false,"guest_can_join":false,"children_state":[]}
            ],"next_batch":"hb"}""",
        )
        val transport = stub(json)
        val page = stubClient(transport).roomHierarchy("!sp:x", from = "h0", limit = 5, maxDepth = 2, suggestedOnly = true)
        val request = transport.last!!
        assertEquals("GET", request.method)
        assertEquals("/_matrix/client/v1/rooms/!sp:x/hierarchy", request.path)
        assertFalse(request.url.rawPath.contains('!'), "служебные символы в пути должны быть закодированы")
        assertEquals(mapOf("from" to "h0", "limit" to "5", "max_depth" to "2", "suggested_only" to "true"), request.queryMap)
        assertEquals("hb", page.nextBatch)
        assertEquals(2, page.rooms.size)
        val root = page.rooms[0]
        assertTrue(root.room.isSpace)
        assertEquals(2, root.childrenState.size) // чужой тип события отброшен
        assertEquals("!a:x", root.childrenState[0].roomID)
        assertEquals(listOf("x", "y"), root.childrenState[0].via)
        assertTrue(root.childrenState[0].suggested)
        assertEquals("01", root.childrenState[0].order)
        assertEquals("@o:x", root.childrenState[0].sender)
        assertEquals(1700L, root.childrenState[0].originServerTS)
        assertFalse(root.childrenState[1].suggested); assertNull(root.childrenState[1].order)
        assertTrue(page.rooms[1].childrenState.isEmpty())
    }

    @Test fun hierarchyDefaultsHaveNoQuery() = runBlocking {
        val transport = stub(JsonValue.of("rooms" to emptyList<Any>()))
        stubClient(transport).roomHierarchy("!sp:x")
        assertTrue(transport.last!!.queryItems.isEmpty())
    }

    @Test fun knockRequestAndResponse() = runBlocking {
        val transport = stub(JsonValue.of("room_id" to "!k:x"))
        val id = stubClient(transport).knock("#club:x", reason = "Пустите", via = listOf("x", "y"))
        assertEquals("!k:x", id)
        val request = transport.last!!
        assertEquals("POST", request.method)
        assertEquals("/_matrix/client/v3/knock/#club:x", request.path)
        assertFalse(request.url.rawPath.contains('#'))
        assertEquals(listOf("server_name=x", "server_name=y", "via=x", "via=y"), request.queryItems)
        assertEquals(JsonValue.of("reason" to "Пустите"), request.json)
    }

    @Test fun knockWithoutViaAndReason() = runBlocking {
        val transport = stub(JsonValue.of("room_id" to "!k:x"))
        stubClient(transport).knock("!k:x")
        val request = transport.last!!
        assertTrue(request.queryItems.isEmpty())
        assertEquals(JsonValue.Obj(emptyMap()), request.json)
    }

    @Test fun serverErrorIsPropagated() {
        val transport = stub(JsonValue.of("errcode" to "M_FORBIDDEN", "error" to "no"), status = 403)
        val error = assertThrows(MatrixError::class.java) { runBlocking { stubClient(transport).knock("!k:x") } }
        assertEquals("M_FORBIDDEN", error.errcode)
        assertEquals(403, error.status)
    }

    @Test fun resolveAliasIsUnauthenticated() = runBlocking {
        val transport = stub(JsonValue.of("room_id" to "!r:x", "servers" to listOf("x", "y")))
        val result = stubClient(transport).resolveAlias("#club:x")
        assertEquals("!r:x", result.roomID)
        assertEquals(listOf("x", "y"), result.servers)
        val request = transport.last!!
        assertEquals("GET", request.method)
        assertEquals("/_matrix/client/v3/directory/room/#club:x", request.path)
        assertNull(request.header("Authorization"))
    }

    @Test fun roomVisibility() = runBlocking {
        val get = stub(JsonValue.of("visibility" to "public"))
        assertEquals("public", stubClient(get).roomVisibility("!r:x"))
        assertEquals("GET", get.last?.method)
        assertEquals("/_matrix/client/v3/directory/list/room/!r:x", get.last?.path)

        val put = stub()
        stubClient(put).setRoomVisibility("!r:x", public = false)
        val request = put.last!!
        assertEquals("PUT", request.method)
        assertEquals("/_matrix/client/v3/directory/list/room/!r:x", request.path)
        assertEquals(JsonValue.of("visibility" to "private"), request.json)
        assertEquals("Bearer tok", request.header("Authorization"))
    }

    @Test fun spaceChildHelpers() = runBlocking {
        val transport = stub(JsonValue.of("event_id" to "\$e"))
        val client = stubClient(transport)
        client.addSpaceChild("!sp:x", "!c:x", via = listOf("x"), suggested = true, order = "a")
        var request = transport.last!!
        assertEquals("PUT", request.method)
        assertEquals("/_matrix/client/v3/rooms/!sp:x/state/m.space.child/!c:x", request.path)
        assertEquals(JsonValue.of("via" to listOf("x"), "suggested" to true, "order" to "a"), request.json)

        client.addSpaceChild("!sp:x", "!c:x", via = listOf("x"))
        assertEquals(JsonValue.of("via" to listOf("x")), transport.last?.json)

        client.removeSpaceChild("!sp:x", "!c:x")
        request = transport.last!!
        assertEquals("/_matrix/client/v3/rooms/!sp:x/state/m.space.child/!c:x", request.path)
        assertEquals(JsonValue.Obj(emptyMap()), request.json)
    }

    @Test fun spaceParentHelpers() = runBlocking {
        val transport = stub(JsonValue.of("event_id" to "\$e"))
        val client = stubClient(transport)
        client.setSpaceParent("!c:x", "!sp:x", via = listOf("x"), canonical = true)
        var request = transport.last!!
        assertEquals("/_matrix/client/v3/rooms/!c:x/state/m.space.parent/!sp:x", request.path)
        assertEquals(JsonValue.of("via" to listOf("x"), "canonical" to true), request.json)

        client.removeSpaceParent("!c:x", "!sp:x")
        request = transport.last!!
        assertEquals("/_matrix/client/v3/rooms/!c:x/state/m.space.parent/!sp:x", request.path)
        assertEquals(JsonValue.Obj(emptyMap()), request.json)
    }
}
