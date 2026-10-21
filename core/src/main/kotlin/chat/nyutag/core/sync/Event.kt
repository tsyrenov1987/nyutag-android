// Нютаг. Событие Matrix в том виде, в каком его отдаёт /sync: тип, отправитель, содержимое как дерево JSON.

package chat.nyutag.core.sync

import chat.nyutag.json.JsonValue

data class Event(
    val eventID: String? = null,
    val type: String,
    val sender: String? = null,
    val originServerTS: Long? = null,
    val stateKey: String? = null,
    val content: JsonValue = JsonValue.Obj(emptyMap()),
    val unsigned: JsonValue? = null,
    /** Заполняется движком при разборе /sync; в самом событии комнаты поля нет. */
    val roomID: String? = null,
) {
    val isState: Boolean get() = stateKey != null

    val json: JsonValue get() {
        val obj = LinkedHashMap<String, JsonValue>()
        obj["type"] = JsonValue.Str(type)
        obj["content"] = content
        eventID?.let { obj["event_id"] = JsonValue.Str(it) }
        sender?.let { obj["sender"] = JsonValue.Str(it) }
        originServerTS?.let { obj["origin_server_ts"] = JsonValue.Int(it) }
        stateKey?.let { obj["state_key"] = JsonValue.Str(it) }
        unsigned?.let { obj["unsigned"] = it }
        roomID?.let { obj["room_id"] = JsonValue.Str(it) }
        return JsonValue.Obj(obj)
    }

    /** Идентификатор транзакции нашего собственного эха (сервер возвращает его в `unsigned.transaction_id`). */
    val transactionID: String? get() = unsigned?.get("transaction_id")?.string

    /** Удалено ли событие (сервер оставляет `unsigned.redacted_because`). */
    val isRedacted: Boolean get() = unsigned?.get("redacted_because") != null

    companion object {
        fun from(json: JsonValue): Event? {
            val type = json["type"]?.string ?: return null
            return Event(
                eventID = json["event_id"]?.string,
                type = type,
                sender = json["sender"]?.string,
                originServerTS = json["origin_server_ts"]?.long,
                stateKey = json["state_key"]?.string,
                content = json["content"] ?: JsonValue.Obj(emptyMap()),
                unsigned = json["unsigned"],
                roomID = json["room_id"]?.string,
            )
        }
    }
}

object EventType {
    const val MESSAGE = "m.room.message"
    const val ENCRYPTED = "m.room.encrypted"
    const val MEMBER = "m.room.member"
    const val NAME = "m.room.name"
    const val TOPIC = "m.room.topic"
    const val AVATAR = "m.room.avatar"
    const val CREATE = "m.room.create"
    const val CANONICAL_ALIAS = "m.room.canonical_alias"
    const val ENCRYPTION = "m.room.encryption"
    const val REDACTION = "m.room.redaction"
    const val REACTION = "m.reaction"
    const val STICKER = "m.sticker"
    const val POWER_LEVELS = "m.room.power_levels"
    const val JOIN_RULES = "m.room.join_rules"
    const val TOMBSTONE = "m.room.tombstone"
    const val ROOM_KEY = "m.room_key"
    const val FORWARDED_ROOM_KEY = "m.forwarded_room_key"
    const val ROOM_KEY_REQUEST = "m.room_key_request"
    const val RECEIPT = "m.receipt"
    const val TYPING = "m.typing"
    const val PRESENCE = "m.presence"
    const val DIRECT = "m.direct"
    const val PUSH_RULES = "m.push_rules"
    const val FULLY_READ = "m.fully_read"
    const val IGNORED_USERS = "m.ignored_user_list"

    // Свои типы Нютага; форматы общие с iOS-клиентом.
    const val CHAT_LAYOUT = "chat.nyutag.chat_layout"
    const val SAVED_MESSAGES = "chat.nyutag.saved_messages"
    const val AUTODELETE = "chat.nyutag.autodelete"
    const val CALL = "chat.nyutag.call"
}
