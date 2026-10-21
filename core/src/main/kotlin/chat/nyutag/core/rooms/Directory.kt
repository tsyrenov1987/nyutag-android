// Нютаг. Каталог комнат, пространства (spaces) и knock. Сообщества Нютага — это комнаты типа `m.space`.
// Форматы по спецификации Matrix CS API v1.12: /publicRooms, /rooms/{id}/hierarchy, /knock, /directory.

package chat.nyutag.core.rooms

import chat.nyutag.core.media.MXC
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixClient.Method
import chat.nyutag.core.net.MatrixError
import chat.nyutag.json.JsonValue

/** Запись каталога (`PublicRoomsChunk`). Для обязательных по спеке полей без значения берём безопасный ноль. */
data class PublicRoom(
    val roomID: String,
    val name: String? = null,
    val topic: String? = null,
    val canonicalAlias: String? = null,
    val avatarURL: MXC? = null,
    val numJoinedMembers: Int = 0,
    val worldReadable: Boolean = false,
    val guestCanJoin: Boolean = false,
    val joinRule: String? = null,
    /** `m.space` для сообщества, `null` для обычной комнаты. */
    val roomType: String? = null,
) {
    val isSpace: Boolean get() = roomType == "m.space"

    companion object {
        /** `null`, если нет `room_id`. */
        fun from(json: JsonValue): PublicRoom? {
            val roomID = json["room_id"]?.string ?: return null
            return PublicRoom(
                roomID = roomID, name = json["name"]?.string, topic = json["topic"]?.string,
                canonicalAlias = json["canonical_alias"]?.string, avatarURL = json["avatar_url"]?.string?.let(MXC::parse),
                numJoinedMembers = json["num_joined_members"]?.int ?: 0, worldReadable = json["world_readable"]?.bool ?: false,
                guestCanJoin = json["guest_can_join"]?.bool ?: false, joinRule = json["join_rule"]?.string,
                roomType = json["room_type"]?.string,
            )
        }
    }
}

/** Фильтр каталога (`Filter` в теле POST /publicRooms). */
data class PublicRoomsFilter(
    val genericSearchTerm: String? = null,
    /** Типы комнат; `null` внутри списка — комнаты без типа. `null` целиком — без фильтра по типу. */
    val roomTypes: List<String?>? = null,
) {
    val json: JsonValue get() {
        val obj = LinkedHashMap<String, JsonValue>()
        if (genericSearchTerm != null) obj["generic_search_term"] = JsonValue.Str(genericSearchTerm)
        if (roomTypes != null) obj["room_types"] = JsonValue.Arr(roomTypes.map { if (it == null) JsonValue.Null else JsonValue.Str(it) })
        return JsonValue.Obj(obj)
    }

    companion object {
        /** Только сообщества Нютага (`m.space`). */
        fun communities(searching: String? = null): PublicRoomsFilter = PublicRoomsFilter(searching, listOf("m.space"))
    }
}

/** Страница каталога. */
data class PublicRoomsPage(
    val rooms: List<PublicRoom>,
    val nextBatch: String?,
    val prevBatch: String?,
    val totalRoomCountEstimate: Int?,
) {
    companion object {
        fun from(json: JsonValue): PublicRoomsPage {
            val chunk = json["chunk"]?.array ?: throw MatrixError(MatrixError.Kind.Decoding("/publicRooms: нет chunk"))
            return PublicRoomsPage(chunk.mapNotNull(PublicRoom::from), json["next_batch"]?.string, json["prev_batch"]?.string,
                                   json["total_room_count_estimate"]?.int)
        }
    }
}

/** Связь «пространство → дочерняя комната» (`m.space.child`), в иерархии приходит как `children_state`. */
data class SpaceChild(
    /** `state_key` — id дочерней комнаты. */
    val roomID: String,
    val via: List<String>,
    val suggested: Boolean,
    /** Ключ ручной сортировки (строка из символов 0x20–0x7E, до 50 символов). */
    val order: String?,
    val sender: String?,
    val originServerTS: Long?,
) {
    companion object {
        fun from(json: JsonValue): SpaceChild? {
            if (json["type"]?.string != "m.space.child") return null
            val roomID = json["state_key"]?.string ?: return null
            val content = json["content"]
            return SpaceChild(
                roomID, (content?.get("via")?.array ?: emptyList()).mapNotNull { it.string },
                content?.get("suggested")?.bool ?: false, content?.get("order")?.string,
                json["sender"]?.string, json["origin_server_ts"]?.long,
            )
        }
    }
}

/** Комната в ответе /hierarchy: запись каталога плюс связи с детьми. */
data class SpaceHierarchyRoom(val room: PublicRoom, val childrenState: List<SpaceChild>)

data class SpaceHierarchyPage(
    /** Первая — сама запрошенная комната. */
    val rooms: List<SpaceHierarchyRoom>,
    val nextBatch: String?,
) {
    companion object {
        fun from(json: JsonValue): SpaceHierarchyPage {
            val rooms = json["rooms"]?.array ?: throw MatrixError(MatrixError.Kind.Decoding("/hierarchy: нет rooms"))
            return SpaceHierarchyPage(rooms.mapNotNull { item ->
                PublicRoom.from(item)?.let { room ->
                    SpaceHierarchyRoom(room, (item["children_state"]?.array ?: emptyList()).mapNotNull(SpaceChild::from))
                }
            }, json["next_batch"]?.string)
        }
    }
}

data class ResolvedAlias(val roomID: String, val servers: List<String>)

// MARK: Каталог

/** POST /v3/publicRooms. `server` — чей каталог смотреть (по умолчанию домашний). */
suspend fun MatrixClient.publicRooms(
    server: String? = null, limit: Int? = null, since: String? = null, filter: PublicRoomsFilter? = null, includeAllNetworks: Boolean = false,
): PublicRoomsPage {
    val body = LinkedHashMap<String, JsonValue>()
    if (limit != null) body["limit"] = JsonValue.Int(limit.toLong())
    if (since != null) body["since"] = JsonValue.Str(since)
    if (filter != null) body["filter"] = filter.json
    if (includeAllNetworks) body["include_all_networks"] = JsonValue.Bool(true)
    val query = if (server != null) mapOf("server" to server) else emptyMap()
    return PublicRoomsPage.from(request(Method.POST, "publicRooms", query = query, body = JsonValue.Obj(body)))
}

/** Каталог сообществ Нютага: только `m.space`. */
suspend fun MatrixClient.publicCommunities(server: String? = null, search: String? = null, limit: Int? = null, since: String? = null): PublicRoomsPage =
    publicRooms(server, limit, since, PublicRoomsFilter.communities(search))

/** Видимость комнаты в каталоге: `public` или `private`. Читать можно без токена. */
suspend fun MatrixClient.roomVisibility(roomID: String): String {
    val json = request(Method.GET, "directory/list/room/$roomID", authenticated = false)
    return json["visibility"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("directory/list: нет visibility"))
}

suspend fun MatrixClient.setRoomVisibility(roomID: String, public: Boolean) {
    request(Method.PUT, "directory/list/room/$roomID", body = JsonValue.of("visibility" to if (public) "public" else "private"))
}

/** GET /v3/directory/room/{alias}: id комнаты и серверы, через которые в неё входить. */
suspend fun MatrixClient.resolveAlias(alias: String): ResolvedAlias {
    val json = request(Method.GET, "directory/room/$alias", authenticated = false)
    val roomID = json["room_id"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("directory/room: нет room_id"))
    return ResolvedAlias(roomID, (json["servers"]?.array ?: emptyList()).mapNotNull { it.string })
}

// MARK: Пространства

/** GET /v1/rooms/{id}/hierarchy. */
suspend fun MatrixClient.roomHierarchy(
    roomID: String, from: String? = null, limit: Int? = null, maxDepth: Int? = null, suggestedOnly: Boolean = false,
): SpaceHierarchyPage {
    val query = HashMap<String, String>()
    if (from != null) query["from"] = from
    if (limit != null) query["limit"] = limit.toString()
    if (maxDepth != null) query["max_depth"] = maxDepth.toString()
    if (suggestedOnly) query["suggested_only"] = "true"
    return SpaceHierarchyPage.from(request(Method.GET, "rooms/$roomID/hierarchy", version = "v1", query = query))
}

/** Добавить комнату в пространство: `m.space.child` в пространстве, `via` обязателен. */
suspend fun MatrixClient.addSpaceChild(spaceID: String, childID: String, via: List<String>, suggested: Boolean = false, order: String? = null): String {
    val content = LinkedHashMap<String, JsonValue>()
    content["via"] = JsonValue.Arr(via.map { JsonValue.Str(it) })
    if (suggested) content["suggested"] = JsonValue.Bool(true)
    if (order != null) content["order"] = JsonValue.Str(order)
    return setState(spaceID, "m.space.child", childID, JsonValue.Obj(content))
}

/** Убрать комнату из пространства: по спеке связь снимается пустым содержимым. */
suspend fun MatrixClient.removeSpaceChild(spaceID: String, childID: String): String =
    setState(spaceID, "m.space.child", childID, JsonValue.Obj(emptyMap()))

/** Обратная ссылка `m.space.parent` в дочерней комнате. */
suspend fun MatrixClient.setSpaceParent(roomID: String, spaceID: String, via: List<String>, canonical: Boolean = false): String {
    val content = LinkedHashMap<String, JsonValue>()
    content["via"] = JsonValue.Arr(via.map { JsonValue.Str(it) })
    if (canonical) content["canonical"] = JsonValue.Bool(true)
    return setState(roomID, "m.space.parent", spaceID, JsonValue.Obj(content))
}

suspend fun MatrixClient.removeSpaceParent(roomID: String, spaceID: String): String =
    setState(roomID, "m.space.parent", spaceID, JsonValue.Obj(emptyMap()))

// MARK: Knock

/** POST /v3/knock/{roomIdOrAlias}: постучаться в комнату с правилом входа `knock`. Возвращает id комнаты.
 *  Каждый сервер из `via` уходит и как `via`, и как исторический `server_name`. */
suspend fun MatrixClient.knock(roomIDOrAlias: String, reason: String? = null, via: List<String> = emptyList()): String {
    val repeated = if (via.isEmpty()) emptyMap() else linkedMapOf("server_name" to via, "via" to via)
    val body = if (reason != null) JsonValue.of("reason" to reason) else JsonValue.Obj(emptyMap())
    val json = request(Method.POST, "knock/$roomIDOrAlias", repeatedQuery = repeated, body = body)
    return json["room_id"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("knock: нет room_id"))
}
