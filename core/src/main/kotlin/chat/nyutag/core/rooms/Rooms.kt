// Нютаг. Действия с комнатами: история (/messages), отправка и удаление событий, состояние, вход и выход,
// отметки прочтения и набор текста.

package chat.nyutag.core.rooms

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixClient.Method
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.core.sync.SyncEngine
import chat.nyutag.json.JsonValue
import java.util.UUID

/** Страница истории из `/messages`. */
class MessagesPage(
    /** В порядке запроса: для [Direction.BACKWARD] от новых к старым. */
    val events: List<Event>,
    val state: List<Event>,
    val start: String,
    /** Маркер следующей страницы; `null` — история кончилась. */
    val end: String?,
) {
    companion object {
        fun from(json: JsonValue, roomID: String): MessagesPage {
            val start = json["start"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("/messages: нет start"))
            fun parse(key: String): List<Event> = (json[key]?.array ?: emptyList()).mapNotNull(Event::from)
                .map { if (it.roomID == null) it.copy(roomID = roomID) else it }
            return MessagesPage(parse("chunk"), parse("state"), start, json["end"]?.string)
        }
    }
}

enum class Direction(val rawValue: String) { BACKWARD("b"), FORWARD("f") }

/** Источник идентификаторов транзакций: уникальны в пределах устройства, повтор того же id сервер не дублирует. */
object TransactionID {
    fun make(): String = "ny" + System.currentTimeMillis() + "." + UUID.randomUUID().toString().take(8).lowercase()
}

private val emptyObject = JsonValue.Obj(emptyMap())

// MARK: История

suspend fun MatrixClient.messages(
    roomID: String, from: String?, direction: Direction = Direction.BACKWARD, limit: Int = 50, filter: JsonValue? = null,
): MessagesPage {
    val query = HashMap<String, String>()
    query["dir"] = direction.rawValue
    query["limit"] = limit.toString()
    if (from != null) query["from"] = from
    if (filter != null) query["filter"] = filter.canonical()
    return MessagesPage.from(request(Method.GET, "rooms/$roomID/messages", query = query), roomID)
}

suspend fun MatrixClient.event(roomID: String, eventID: String): Event {
    val json = request(Method.GET, "rooms/$roomID/event/$eventID")
    return Event.from(json) ?: throw MatrixError(MatrixError.Kind.Decoding("событие $eventID"))
}

/** Окрестность события (переход к ответу или закреплённому сообщению). */
suspend fun MatrixClient.context(roomID: String, eventID: String, limit: Int = 20): JsonValue =
    request(Method.GET, "rooms/$roomID/context/$eventID", query = mapOf("limit" to limit.toString()))

// MARK: Отправка

/** Отправка события таймлайна. Возвращает `event_id`. */
suspend fun MatrixClient.send(
    roomID: String, type: String, content: JsonValue, transactionID: String = TransactionID.make(),
): String {
    val json = request(Method.PUT, "rooms/$roomID/send/$type/$transactionID", body = content)
    return json["event_id"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("send: нет event_id"))
}

suspend fun MatrixClient.sendText(roomID: String, body: String): String =
    send(roomID, EventType.MESSAGE, JsonValue.of("msgtype" to "m.text", "body" to body))

suspend fun MatrixClient.redact(
    roomID: String, eventID: String, reason: String? = null, transactionID: String = TransactionID.make(),
): String {
    val body = LinkedHashMap<String, JsonValue>()
    if (reason != null) body["reason"] = JsonValue.Str(reason)
    val json = request(Method.PUT, "rooms/$roomID/redact/$eventID/$transactionID", body = JsonValue.Obj(body))
    return json["event_id"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("redact: нет event_id"))
}

suspend fun MatrixClient.setState(roomID: String, type: String, stateKey: String = "", content: JsonValue): String {
    val json = request(Method.PUT, "rooms/$roomID/state/$type/$stateKey", body = content)
    return json["event_id"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("state: нет event_id"))
}

suspend fun MatrixClient.state(roomID: String, type: String, stateKey: String = ""): JsonValue =
    request(Method.GET, "rooms/$roomID/state/$type/$stateKey")

/** Создатели комнаты: с версии 12 у них неограниченные права, а в `power_levels` их нет — это автор
 *  `m.room.create` и `additional_creators`. В комнатах до 12-й права создателя записаны в `power_levels`: пусто. */
suspend fun MatrixClient.creators(roomID: String): Set<String> {
    val event = request(Method.GET, "rooms/$roomID/state/m.room.create/", query = mapOf("format" to "event"))
    val version = event["content"]?.get("room_version")?.string ?: "1"
    if ((version.toIntOrNull() ?: Int.MAX_VALUE) < 12) return emptySet()
    val extra = event["content"]?.get("additional_creators")?.array.orEmpty().mapNotNull { it.string }
    return (listOfNotNull(event["sender"]?.string) + extra).toSet()
}

// MARK: Членство

data class CreateRoom(
    val name: String? = null,
    val topic: String? = null,
    val invite: List<String> = emptyList(),
    val isDirect: Boolean = false,
    val preset: String = "private_chat",
    val encrypted: Boolean = true,
    val roomType: String? = null,
) {
    val json: JsonValue get() {
        val body = LinkedHashMap<String, JsonValue>()
        body["preset"] = JsonValue.Str(preset)
        body["is_direct"] = JsonValue.Bool(isDirect)
        if (name != null) body["name"] = JsonValue.Str(name)
        if (topic != null) body["topic"] = JsonValue.Str(topic)
        if (invite.isNotEmpty()) body["invite"] = JsonValue.Arr(invite.map { JsonValue.Str(it) })
        if (roomType != null) body["creation_content"] = JsonValue.of("type" to roomType)
        if (encrypted) {
            body["initial_state"] = JsonValue.list(JsonValue.of(
                "type" to EventType.ENCRYPTION, "state_key" to "",
                "content" to JsonValue.of("algorithm" to "m.megolm.v1.aes-sha2"),
            ))
        }
        return JsonValue.Obj(body)
    }
}

suspend fun MatrixClient.createRoom(options: CreateRoom): String {
    val json = request(Method.POST, "createRoom", body = options.json)
    return json["room_id"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("createRoom: нет room_id"))
}

/** Вход по id или алиасу. `via` — серверы, через которые искать комнату. */
suspend fun MatrixClient.join(roomIDOrAlias: String, via: List<String> = emptyList()): String {
    val query = if (via.isEmpty()) emptyMap() else mapOf("server_name" to via.joinToString(","))
    val json = request(Method.POST, "join/$roomIDOrAlias", query = query, body = emptyObject)
    return json["room_id"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("join: нет room_id"))
}

suspend fun MatrixClient.leave(roomID: String, reason: String? = null) {
    val body = if (reason != null) JsonValue.of("reason" to reason) else emptyObject
    request(Method.POST, "rooms/$roomID/leave", body = body)
}

suspend fun MatrixClient.forget(roomID: String) {
    request(Method.POST, "rooms/$roomID/forget", body = emptyObject)
}

/** Комнаты, где мы сейчас `join`, по мнению сервера. */
suspend fun MatrixClient.joinedRooms(): Set<String> =
    (request(Method.GET, "joined_rooms")["joined_rooms"]?.array ?: emptyList()).mapNotNull { it.string }.toSet()

suspend fun MatrixClient.invite(roomID: String, userID: String, reason: String? = null) {
    val body = linkedMapOf<String, JsonValue>("user_id" to JsonValue.Str(userID))
    if (reason != null) body["reason"] = JsonValue.Str(reason)
    request(Method.POST, "rooms/$roomID/invite", body = JsonValue.Obj(body))
}

suspend fun MatrixClient.kick(roomID: String, userID: String, reason: String? = null) {
    val body = linkedMapOf<String, JsonValue>("user_id" to JsonValue.Str(userID))
    if (reason != null) body["reason"] = JsonValue.Str(reason)
    request(Method.POST, "rooms/$roomID/kick", body = JsonValue.Obj(body))
}

/** Полный список участников (для отправки ключей Megolm нужны все устройства joined и invited). */
suspend fun MatrixClient.members(roomID: String, notMembership: String? = "leave"): List<Event> {
    val query = if (notMembership != null) mapOf("not_membership" to notMembership) else emptyMap()
    val json = request(Method.GET, "rooms/$roomID/members", query = query)
    return (json["chunk"]?.array ?: emptyList()).mapNotNull(Event::from)
}

// MARK: Прочтение и набор

/** Публичная и приватная отметка прочтения плюс маркер полностью прочитанного одним запросом. */
suspend fun MatrixClient.setReadMarkers(roomID: String, fullyRead: String? = null, read: String? = null, readPrivate: String? = null) {
    val body = LinkedHashMap<String, JsonValue>()
    if (fullyRead != null) body["m.fully_read"] = JsonValue.Str(fullyRead)
    if (read != null) body["m.read"] = JsonValue.Str(read)
    if (readPrivate != null) body["m.read.private"] = JsonValue.Str(readPrivate)
    request(Method.POST, "rooms/$roomID/read_markers", body = JsonValue.Obj(body))
}

suspend fun MatrixClient.setTyping(roomID: String, userID: String, typing: Boolean, timeoutMillis: Long = 20_000) {
    val body = linkedMapOf<String, JsonValue>("typing" to JsonValue.Bool(typing))
    if (typing) body["timeout"] = JsonValue.Int(timeoutMillis)
    request(Method.PUT, "rooms/$roomID/typing/$userID", body = JsonValue.Obj(body))
}

// MARK: Данные учётки

private fun accountDataPath(userID: String, type: String, roomID: String?): String =
    if (roomID != null) "user/$userID/rooms/$roomID/account_data/$type" else "user/$userID/account_data/$type"

suspend fun MatrixClient.setAccountData(userID: String, type: String, roomID: String? = null, content: JsonValue) {
    request(Method.PUT, accountDataPath(userID, type, roomID), body = content)
}

suspend fun MatrixClient.accountData(userID: String, type: String, roomID: String? = null): JsonValue =
    request(Method.GET, accountDataPath(userID, type, roomID))

// MARK: Движок

/** Выйти из комнаты; с `forget` ещё и забыть её на сервере и стереть локально. */
suspend fun SyncEngine.leave(roomID: String, forget: Boolean = false) {
    client.leave(roomID)
    if (!forget) return
    client.forget(roomID)
    try { store.deleteRoom(roomID) } catch (e: Exception) { throw MatrixError(MatrixError.Kind.Decoding("хранилище: $e")) }
}

/** Комнату, которую на другом устройстве покинули и забыли, `/sync` больше не присылает вовсе, и здесь она
 *  навсегда осталась бы `join`. Сверяемся со списком сервера; возвращает комнаты, помеченные покинутыми. */
suspend fun SyncEngine.dropForgotten(): List<String> {
    // Снимок до запроса: комната, в которую вошли, пока он шёл, в снимок не попадёт и не пострадает.
    val local = try { store.rooms(RoomRecord.Membership.JOIN) } catch (e: Exception) { throw MatrixError(MatrixError.Kind.Decoding("хранилище: $e")) }
    val joined = client.joinedRooms()
    try {
        val gone = local.filter { it.id !in joined }
        for (record in gone) {
            record.membership = RoomRecord.Membership.LEAVE
            store.upsert(record)
        }
        return gone.map { it.id }
    } catch (e: Exception) {
        throw MatrixError(MatrixError.Kind.Decoding("хранилище: $e"))
    }
}

data class Backfill(val added: Int, val hasMore: Boolean)

/** Докачивает страницу истории назад от сохранённого `prev_batch` комнаты и кладёт её в хранилище.
 *  `added == 0` при `hasMore == false` значит, что начало комнаты достигнуто. */
suspend fun SyncEngine.backfill(roomID: String, limit: Int = 50): Backfill {
    val record = try { store.room(roomID) } catch (e: Exception) { throw MatrixError(MatrixError.Kind.Decoding("хранилище: $e")) }
    val from = record?.prevBatch ?: return Backfill(0, false)
    val page = client.messages(roomID, from, Direction.BACKWARD, limit)
    try {
        val added = store.prepend(page.events, roomID)
        // Участники из ленивой подгрузки: только если о них ещё ничего не знаем, свежий sync важнее старой страницы.
        for (event in page.state) {
            if (store.state(roomID, event.type, event.stateKey ?: "") == null) store.setState(event)
        }
        record.prevBatch = page.end
        store.upsert(record)
        return Backfill(added, page.end != null && page.events.isNotEmpty())
    } catch (e: Exception) {
        throw MatrixError(MatrixError.Kind.Decoding("хранилище: $e"))
    }
}
