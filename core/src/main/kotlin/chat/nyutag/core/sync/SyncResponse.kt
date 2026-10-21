// Нютаг. Разбор ответа `/sync` (клиент-серверное API v3) в структуры движка. Разбираем из дерева JSON:
// формат местами разнороден, а нам нужны только известные поля.

package chat.nyutag.core.sync

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixError
import chat.nyutag.json.JsonValue

class SyncResponse(json: JsonValue) {
    val nextBatch: String
    val joined: Map<String, JoinedRoom>
    val invited: Map<String, InvitedRoom>
    val left: Map<String, LeftRoom>
    val knocked: Map<String, KnockedRoom>
    val accountData: List<Event>
    val presence: List<Event>
    val toDevice: List<Event>
    val deviceListsChanged: List<String>
    val deviceListsLeft: List<String>
    val oneTimeKeysCount: Map<String, Int>
    val unusedFallbackKeys: List<String>?

    class Timeline(json: JsonValue?, roomID: String) {
        val events: List<Event> = events(json?.get("events"), roomID)
        val limited: Boolean = json?.get("limited")?.bool ?: false
        val prevBatch: String? = json?.get("prev_batch")?.string
    }

    data class UnreadNotifications(val highlight: Int, val notification: Int)

    class JoinedRoom(roomID: String, json: JsonValue) {
        data class Summary(val heroes: List<String>?, val joinedCount: Int?, val invitedCount: Int?)

        val state: List<Event> = events(json.at("state", "events"), roomID)
        val timeline: Timeline = Timeline(json["timeline"], roomID)
        val ephemeral: List<Event> = events(json.at("ephemeral", "events"), roomID)
        val accountData: List<Event> = events(json.at("account_data", "events"), roomID)
        val unread = UnreadNotifications(json.at("unread_notifications", "highlight_count")?.int ?: 0,
                                         json.at("unread_notifications", "notification_count")?.int ?: 0)
        val summary = Summary(json.at("summary", "m.heroes")?.array?.mapNotNull { it.string },
                              json.at("summary", "m.joined_member_count")?.int,
                              json.at("summary", "m.invited_member_count")?.int)
    }

    class InvitedRoom(val inviteState: List<Event>)

    class LeftRoom(val state: List<Event>, val timeline: Timeline, val accountData: List<Event>)

    class KnockedRoom(val knockState: List<Event>)

    init {
        nextBatch = json["next_batch"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("sync: нет next_batch"))
        joined = (json.at("rooms", "join")?.obj ?: emptyMap()).mapValues { (roomID, room) -> JoinedRoom(roomID, room) }
        invited = (json.at("rooms", "invite")?.obj ?: emptyMap()).mapValues { (roomID, room) ->
            InvitedRoom(events(room.at("invite_state", "events"), roomID))
        }
        left = (json.at("rooms", "leave")?.obj ?: emptyMap()).mapValues { (roomID, room) ->
            LeftRoom(events(room.at("state", "events"), roomID), Timeline(room["timeline"], roomID),
                     events(room.at("account_data", "events"), roomID))
        }
        knocked = (json.at("rooms", "knock")?.obj ?: emptyMap()).mapValues { (roomID, room) ->
            KnockedRoom(events(room.at("knock_state", "events"), roomID))
        }
        accountData = events(json.at("account_data", "events"))
        presence = events(json.at("presence", "events"))
        toDevice = events(json.at("to_device", "events"))
        deviceListsChanged = json.at("device_lists", "changed")?.array?.mapNotNull { it.string } ?: emptyList()
        deviceListsLeft = json.at("device_lists", "left")?.array?.mapNotNull { it.string } ?: emptyList()
        oneTimeKeysCount = (json["device_one_time_keys_count"]?.obj ?: emptyMap())
            .mapNotNull { (key, value) -> value.int?.let { key to it } }.toMap()
        unusedFallbackKeys = (json["device_unused_fallback_key_types"] ?: json["org.matrix.msc2732.device_unused_fallback_key_types"])
            ?.array?.mapNotNull { it.string }
    }

    val isEmpty: Boolean get() = joined.isEmpty() && invited.isEmpty() && left.isEmpty() && knocked.isEmpty()
        && accountData.isEmpty() && presence.isEmpty() && toDevice.isEmpty() && deviceListsChanged.isEmpty()

    companion object {
        internal fun events(json: JsonValue?, roomID: String? = null): List<Event> =
            (json?.array ?: emptyList()).mapNotNull { item ->
                val event = Event.from(item) ?: return@mapNotNull null
                if (event.roomID == null) event.copy(roomID = roomID) else event
            }
    }
}

/** Фильтр /sync: ограничиваем хвост таймлайна и отключаем то, что не показываем. */
object SyncFilter {
    /** Первый sync: короткий таймлайн, чтобы стартовать быстро; историю докачивает `/messages`. */
    fun initial(timelineLimit: Int = 20): JsonValue = filter(timelineLimit)

    fun incremental(timelineLimit: Int = 50): JsonValue = filter(timelineLimit)

    private fun filter(timelineLimit: Int): JsonValue = JsonValue.of(
        "room" to JsonValue.of(
            "timeline" to JsonValue.of("limit" to timelineLimit, "lazy_load_members" to true),
            "state" to JsonValue.of("lazy_load_members" to true),
            "ephemeral" to JsonValue.of("types" to listOf("m.receipt", "m.typing")),
        ),
        "presence" to JsonValue.of("types" to emptyList<String>()),
    )
}

/** Один вызов `/sync`. `timeoutMillis` — long-poll на сервере; сетевой таймаут берётся с запасом. */
suspend fun MatrixClient.sync(since: String?, filter: JsonValue?, timeoutMillis: Long, setPresence: String = "online"): SyncResponse {
    val query = HashMap<String, String>()
    query["timeout"] = timeoutMillis.toString()
    query["set_presence"] = setPresence
    if (since != null) query["since"] = since
    if (filter != null) query["filter"] = filter.canonical()
    val json = request(MatrixClient.Method.GET, "sync", query = query, timeoutMillis = timeoutMillis + 10_000)
    return SyncResponse(json)
}
