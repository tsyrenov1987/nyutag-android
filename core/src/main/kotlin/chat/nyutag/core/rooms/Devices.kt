// Нютаг. Где открыт аккаунт: список входов (`/devices`), переименование и выход на чужом устройстве.
// Удаление входа сервер подтверждает паролем (UIA), как и удаление аккаунта; вместе со входом пропадают
// его ключи шифрования, поэтому то устройство больше не получит новые сообщения.

package chat.nyutag.core.rooms

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixClient.Method
import chat.nyutag.core.net.MatrixError
import chat.nyutag.json.JsonValue

data class AccountDevice(
    val deviceID: String,
    val displayName: String? = null,
    val lastSeenTS: Long? = null,
    val lastSeenIP: String? = null,
) {
    companion object {
        fun from(json: JsonValue): AccountDevice? {
            val id = json["device_id"]?.string ?: return null
            return AccountDevice(id, json["display_name"]?.string, json["last_seen_ts"]?.long, json["last_seen_ip"]?.string)
        }

        /** Своё устройство первым, остальные — от недавних к давним. */
        fun sorted(devices: List<AccountDevice>, current: String): List<AccountDevice> = devices.sortedWith { a, b ->
            val aCurrent = a.deviceID == current
            val bCurrent = b.deviceID == current
            when {
                aCurrent != bCurrent -> if (aCurrent) -1 else 1
                a.lastSeenTS != b.lastSeenTS -> (b.lastSeenTS ?: 0).compareTo(a.lastSeenTS ?: 0)
                else -> a.deviceID.compareTo(b.deviceID)
            }
        }
    }
}

/** Подтверждение паролем для UIA (`auth` в теле повторного запроса). */
internal fun passwordAuth(userID: String, password: String, session: String): JsonValue = JsonValue.of(
    "type" to "m.login.password", "identifier" to JsonValue.of("type" to "m.id.user", "user" to userID),
    "password" to password, "session" to session,
)

suspend fun MatrixClient.devices(): List<AccountDevice> {
    val json = request(Method.GET, "devices")
    val list = json["devices"]?.array ?: throw MatrixError(MatrixError.Kind.Decoding("/devices: нет devices"))
    return list.mapNotNull(AccountDevice::from)
}

suspend fun MatrixClient.renameDevice(deviceID: String, name: String) {
    request(Method.PUT, "devices/$deviceID", body = JsonValue.of("display_name" to name))
}

/** Выйти на другом устройстве. Первый запрос без `auth` возвращает сессию подтверждения, второй — с паролем. */
suspend fun MatrixClient.deleteDevice(deviceID: String, userID: String, password: String) {
    val path = "devices/$deviceID"
    try {
        request(Method.DELETE, path, body = JsonValue.Obj(emptyMap()))
    } catch (e: MatrixError) {
        val session = e.uiaSession ?: throw e
        request(Method.DELETE, path, body = JsonValue.of("auth" to passwordAuth(userID, password, session)))
    }
}
