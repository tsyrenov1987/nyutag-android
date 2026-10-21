// Нютаг. Вход и выход: `m.login.password`, `whoami`, `logout`.

package chat.nyutag.core.auth

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixError
import chat.nyutag.json.JsonValue
import java.net.URI

data class Session(
    val userID: String,
    val deviceID: String,
    val accessToken: String,
    val homeserver: URI,
) {
    /** `@user:nyutag.chat` → `user`. */
    val localpart: String get() = userID.drop(1).takeWhile { it != ':' }

    val serverName: String get() = userID.dropWhile { it != ':' }.drop(1)

    fun toJson(): JsonValue = JsonValue.of(
        "userID" to userID, "deviceID" to deviceID, "accessToken" to accessToken, "homeserver" to homeserver.toString(),
    )

    companion object {
        fun from(json: JsonValue): Session? {
            val userID = json["userID"]?.string ?: return null
            val deviceID = json["deviceID"]?.string ?: return null
            val accessToken = json["accessToken"]?.string ?: return null
            val homeserver = json["homeserver"]?.string?.let { try { URI(it) } catch (_: Exception) { null } } ?: return null
            return Session(userID, deviceID, accessToken, homeserver)
        }
    }
}

data class LoginResponse(val userID: String, val accessToken: String, val deviceID: String) {
    companion object {
        fun from(json: JsonValue): LoginResponse = LoginResponse(
            json["user_id"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("login: нет user_id")),
            json["access_token"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("login: нет access_token")),
            json["device_id"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("login: нет device_id")),
        )
    }
}

data class WhoAmI(val userID: String, val deviceID: String?)

/** Вход по паролю. `user` принимает и localpart, и полный `@user:server`. */
suspend fun MatrixClient.login(user: String, password: String, deviceID: String? = null, deviceDisplayName: String): Session {
    val body = LinkedHashMap<String, JsonValue>()
    body["type"] = JsonValue.Str("m.login.password")
    body["identifier"] = JsonValue.of("type" to "m.id.user", "user" to user)
    body["password"] = JsonValue.Str(password)
    body["initial_device_display_name"] = JsonValue.Str(deviceDisplayName)
    if (deviceID != null) body["device_id"] = JsonValue.Str(deviceID)
    val response = LoginResponse.from(request(MatrixClient.Method.POST, "login", body = JsonValue.Obj(body), authenticated = false))
    setAccessToken(response.accessToken)
    return Session(response.userID, response.deviceID, response.accessToken, homeserver)
}

suspend fun MatrixClient.whoAmI(): WhoAmI {
    val json = request(MatrixClient.Method.GET, "account/whoami")
    return WhoAmI(json["user_id"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("whoami: нет user_id")),
                  json["device_id"]?.string)
}

/** Выход: токен на сервере отзывается, локальный токен сбрасывается даже при сетевой ошибке. */
suspend fun MatrixClient.logout() {
    try {
        request(MatrixClient.Method.POST, "logout")
    } finally {
        setAccessToken(null)
    }
}
