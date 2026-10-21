// Нютаг. Профиль (имя, аватар, произвольные поля MSC4133) и личные чаты (account data `m.direct`).

package chat.nyutag.core.rooms

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixClient.Method
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue

/** Профиль пользователя: `displayname`, `avatar_url` и произвольные поля (`chat.nyutag.*` и др.). */
data class Profile(
    val displayName: String? = null,
    val avatarURL: String? = null,
    /** Все поля ответа, включая displayname и avatar_url. */
    val fields: Map<String, JsonValue> = emptyMap(),
) {
    /** Строковое значение произвольного поля; пустая строка считается отсутствием. */
    fun string(key: String): String? = fields[key]?.string?.takeIf { it.isNotEmpty() }

    /** Значение поля Нютага; пусто или не строка — `null`. */
    operator fun get(key: NyutagProfileKey): String? = string(key.rawValue)

    companion object {
        fun from(json: JsonValue): Profile {
            val fields = json.obj ?: emptyMap()
            return Profile(fields["displayname"]?.string, fields["avatar_url"]?.string, fields)
        }
    }
}

/** Ключи произвольных полей профиля Нютага (имена на проводе общие с другими клиентами). */
enum class NyutagProfileKey(val rawValue: String) {
    ABOUT("chat.nyutag.about"),
    TOONTO("chat.nyutag.toonto"),
    UG("chat.nyutag.ug"),
    /** Год по восточному календарю в виде `<стихия>-<животное>`. */
    EAST_YEAR("chat.nyutag.east_year"),
}

/** Какой API произвольных полей профиля предлагает сервер. */
enum class ProfileFieldsAPI {
    /** `/_matrix/client/v3/profile/{userId}/{key}` (Matrix 1.16, `m.profile_fields`). */
    STABLE,
    /** `/_matrix/client/unstable/uk.tcpip.msc4133/profile/{userId}/{key}`. */
    UNSTABLE,
    /** Сервер явно отключил произвольные поля. */
    DISABLED;

    companion object {
        const val STABLE_CAPABILITY = "m.profile_fields"
        const val UNSTABLE_CAPABILITY = "uk.tcpip.msc4133.profile_fields"
        internal const val UNSTABLE_VERSION = "unstable/uk.tcpip.msc4133"

        /** Выбор по ответу `/capabilities`. Если ни одна из возможностей не упомянута, берём стабильный путь: так
         *  работают форки Нютага на Tuwunel. */
        fun from(capabilities: JsonValue): ProfileFieldsAPI {
            val caps = capabilities["capabilities"]
            val stable = caps?.get(STABLE_CAPABILITY)
            val unstable = caps?.get(UNSTABLE_CAPABILITY)
            return when {
                stable != null && stable["enabled"]?.bool != false -> STABLE
                unstable != null && unstable["enabled"]?.bool != false -> UNSTABLE
                stable != null || unstable != null -> DISABLED
                else -> STABLE
            }
        }
    }
}

// MARK: Профиль

/** Весь профиль. Неизвестный пользователь (404) даёт пустой профиль, как в форках. */
suspend fun MatrixClient.profile(userID: String): Profile = try {
    Profile.from(request(Method.GET, "profile/$userID"))
} catch (e: MatrixError) {
    if (e.status == 404) Profile() else throw e
}

suspend fun MatrixClient.setDisplayName(userID: String, name: String) {
    request(Method.PUT, "profile/$userID/displayname", body = JsonValue.of("displayname" to name))
}

suspend fun MatrixClient.setAvatarURL(userID: String, url: String) {
    request(Method.PUT, "profile/$userID/avatar_url", body = JsonValue.of("avatar_url" to url))
}

// MARK: Произвольные поля (MSC4133)

suspend fun MatrixClient.profileFieldsAPI(): ProfileFieldsAPI = ProfileFieldsAPI.from(request(Method.GET, "capabilities"))

private fun fieldRoute(api: ProfileFieldsAPI): String = when (api) {
    ProfileFieldsAPI.STABLE -> MatrixClient.API_VERSION
    ProfileFieldsAPI.UNSTABLE -> ProfileFieldsAPI.UNSTABLE_VERSION
    ProfileFieldsAPI.DISABLED -> throw MatrixError(MatrixError.Kind.Server(400, "M_FORBIDDEN", "поля профиля отключены"))
}

/** Записать одно поле: `PUT …/profile/{userId}/{key}` с телом `{key: value}`. */
suspend fun MatrixClient.setProfileField(userID: String, key: String, value: JsonValue, api: ProfileFieldsAPI = ProfileFieldsAPI.STABLE) {
    request(Method.PUT, "profile/$userID/$key", version = fieldRoute(api), body = JsonValue.Obj(mapOf(key to value)))
}

/** Удалить поле. Отсутствующее поле (404) — тот же результат, не ошибка. */
suspend fun MatrixClient.deleteProfileField(userID: String, key: String, api: ProfileFieldsAPI = ProfileFieldsAPI.STABLE) {
    try {
        request(Method.DELETE, "profile/$userID/$key", version = fieldRoute(api))
    } catch (e: MatrixError) {
        if (e.status != 404) throw e
    }
}

/** Применить правки полей Нютага по одной: значение — записать, `null` или пустая строка — удалить поле.
 *  Порядок по имени ключа, чтобы обрыв посреди серии вёл себя одинаково. */
suspend fun MatrixClient.applyProfileEdits(userID: String, edits: Map<NyutagProfileKey, String?>, api: ProfileFieldsAPI) {
    for (key in edits.keys.sortedBy { it.rawValue }) {
        val value = edits[key]
        if (!value.isNullOrEmpty()) {
            setProfileField(userID, key.rawValue, JsonValue.Str(value), api)
        } else {
            deleteProfileField(userID, key.rawValue, api)
        }
    }
}

// MARK: Личные чаты (m.direct)

/** `m.direct`: собеседник → комнаты. Нет записи (404) — пустой словарь. */
suspend fun MatrixClient.directRooms(userID: String): Map<String, List<String>> {
    val json = try {
        accountData(userID, EventType.DIRECT)
    } catch (e: MatrixError) {
        if (e.status == 404) return emptyMap() else throw e
    }
    val result = LinkedHashMap<String, List<String>>()
    for ((user, rooms) in json.obj ?: emptyMap()) result[user] = (rooms.array ?: emptyList()).mapNotNull { it.string }
    return result
}

/** Существующий личный чат с `other` (первый из `m.direct`), если есть. */
suspend fun MatrixClient.directRoom(userID: String, other: String): String? = directRooms(userID)[other]?.firstOrNull()

/** Добавить комнату в `m.direct`: читаем текущее, меняем только запись `other`, остальное пишем как было.
 *  Не атомарно: запись с другого устройства между чтением и записью может потеряться, у Matrix для
 *  account data нет условной записи. */
suspend fun MatrixClient.addDirectRoom(userID: String, other: String, roomID: String) {
    val content = try {
        LinkedHashMap(accountData(userID, EventType.DIRECT).obj ?: emptyMap())
    } catch (e: MatrixError) {
        if (e.status == 404) LinkedHashMap() else throw e
    }
    val rooms = (content[other]?.array ?: emptyList()).mapNotNull { it.string }
    if (roomID in rooms) return
    content[other] = JsonValue.Arr((rooms + roomID).map { JsonValue.Str(it) })
    setAccountData(userID, EventType.DIRECT, content = JsonValue.Obj(content))
}

/** Создать личный чат: `is_direct`, приглашение, `trusted_private_chat`, шифрование по `encrypted`;
 *  затем запись в `m.direct`. */
suspend fun MatrixClient.createDirectRoom(userID: String, other: String, encrypted: Boolean = true): String {
    val roomID = createRoom(CreateRoom(invite = listOf(other), isDirect = true, preset = "trusted_private_chat", encrypted = encrypted))
    addDirectRoom(userID, other, roomID)
    return roomID
}
