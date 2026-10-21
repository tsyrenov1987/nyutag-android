// Нютаг. Защита от людей: блокировка собеседника, жалоба на сообщение, удаление аккаунта.
// Блокировка — стандартный `m.ignored_user_list` в данных учётки: сервер перестаёт присылать события
// блокированного, а мы на всякий случай прячем его реплики и у себя. Жалоба — `/rooms/{id}/report/{event}`,
// её читает администратор сервера. Удаление аккаунта — `/account/deactivate` с подтверждением паролем
// (обязательно для приложений с регистрацией).

package chat.nyutag.core.rooms

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixClient.Method
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue

object IgnoredUsers {
    /** `{"ignored_users": {"@a:b": {}, …}}` → набор Matrix ID. */
    fun users(json: JsonValue?): Set<String> {
        val list = json?.get("ignored_users")?.obj ?: return emptySet()
        return list.keys.filter { it.startsWith("@") }.toSet()
    }

    fun json(users: Set<String>): JsonValue {
        val list = LinkedHashMap<String, JsonValue>()
        for (user in users.sorted()) list[user] = JsonValue.Obj(emptyMap())
        return JsonValue.of("ignored_users" to JsonValue.Obj(list))
    }

    fun load(store: Store): Set<String> = users(store.accountData(EventType.IGNORED_USERS))
}

/** Новый список блокированных: на сервер и сразу в базу, чтобы лента спряталась до следующего /sync. */
suspend fun MatrixClient.setIgnoredUsers(users: Set<String>, userID: String, store: Store) {
    val content = IgnoredUsers.json(users)
    setAccountData(userID, EventType.IGNORED_USERS, content = content)
    store.setAccountData(EventType.IGNORED_USERS, content = content)
}

/** Жалоба администратору сервера на сообщение. `score` по спецификации от -100 (худшее) до 0. */
suspend fun MatrixClient.report(roomID: String, eventID: String, reason: String, score: Int = -100) {
    request(Method.POST, "rooms/$roomID/report/$eventID", body = JsonValue.of("reason" to reason, "score" to score))
}

/** Удалить аккаунт на сервере навсегда. Сервер просит подтвердить паролем (UIA): первый запрос без
 *  `auth` возвращает сессию подтверждения, второй идёт с паролем. `erase` просит стереть и содержимое
 *  (имя, аватар, реплики для новых участников), как того ждут от удаления аккаунта. */
suspend fun MatrixClient.deactivate(userID: String, password: String) {
    try {
        request(Method.POST, "account/deactivate", body = JsonValue.of("erase" to true))
    } catch (e: MatrixError) {
        val session = e.uiaSession ?: throw e
        request(Method.POST, "account/deactivate", body = JsonValue.of("erase" to true, "auth" to passwordAuth(userID, password, session)))
    }
    setAccessToken(null)
}
