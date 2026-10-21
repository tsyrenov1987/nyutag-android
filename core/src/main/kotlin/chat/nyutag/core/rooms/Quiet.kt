// Нютаг. Чат «без звука»: правило уведомлений комнаты (`pushrules/global/room/{roomID}`) с пустыми действиями —
// сервер перестаёт слать пуши по этой комнате, а само правило приходит всем устройствам в `m.push_rules`
// данных учётки. Снять — удалить правило. Локальную копию правил правим сразу, чтобы колокольчик в списке
// поменялся до следующего /sync.

package chat.nyutag.core.rooms

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixClient.Method
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue

object MutedRooms {
    /** Комнаты без звука из содержимого `m.push_rules`: правила комнаты (`global.room`), которые включены
     *  и не содержат действия `notify`. */
    fun rooms(json: JsonValue?): Set<String> {
        val muted = HashSet<String>()
        for (rule in json?.at("global", "room")?.array ?: emptyList()) {
            val roomID = rule["rule_id"]?.string ?: continue
            if (rule["enabled"]?.bool == false) continue
            val actions = rule["actions"]?.array ?: emptyList()
            if (JsonValue.Str("notify") !in actions) muted.add(roomID)
        }
        return muted
    }

    fun load(store: Store): Set<String> = rooms(store.accountData(EventType.PUSH_RULES))

    /** Те же правила с добавленным (или снятым) правилом комнаты. */
    fun apply(json: JsonValue?, roomID: String, muted: Boolean): JsonValue {
        val global = LinkedHashMap(json?.get("global")?.obj ?: emptyMap())
        val rules = ArrayList((global["room"]?.array ?: emptyList()).filter { it["rule_id"]?.string != roomID })
        if (muted) {
            rules.add(JsonValue.of("rule_id" to roomID, "default" to false, "enabled" to true, "actions" to emptyList<String>()))
        }
        global["room"] = JsonValue.Arr(rules)
        val content = LinkedHashMap(json?.obj ?: emptyMap())
        content["global"] = JsonValue.Obj(global)
        return JsonValue.Obj(content)
    }
}

/** Комната без звука (или снова со звуком): правило на сервер и сразу в базу. */
suspend fun MatrixClient.setRoomMuted(roomID: String, muted: Boolean, store: Store) {
    if (muted) {
        request(Method.PUT, "pushrules/global/room/$roomID", body = JsonValue.of("actions" to emptyList<String>()))
    } else {
        try {
            request(Method.DELETE, "pushrules/global/room/$roomID")
        } catch (e: MatrixError) {
            // Правила и так нет.
            if (e.errcode != "M_NOT_FOUND") throw e
        }
    }
    val current = store.accountData(EventType.PUSH_RULES)
    store.setAccountData(EventType.PUSH_RULES, content = MutedRooms.apply(current, roomID, muted))
}
