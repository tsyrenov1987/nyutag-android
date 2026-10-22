// Нютаг. Пуши Android: регистрация устройства у шлюза на ядре и разбор того, что шлюз положил в пуш.
// Шлюз (Sygnal) живёт на loopback ядра, поэтому адрес пушера — 127.0.0.1: его открывает сервер, а не телефон.
// Формат `event_id_only`: в пуше только room_id и event_id, текст уведомления собирает PushReader на устройстве.

package chat.nyutag.core.push

import chat.nyutag.core.calls.CallEventType
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixClient.Method
import chat.nyutag.json.JsonValue

object AndroidPusher {
    const val GATEWAY = "http://127.0.0.1:5000/_matrix/push/v1/notify"
    /** Приложения шлюза: у отладочной сборки своё, чтобы её пуши не смешивались с релизными. */
    const val APP_ID = "chat.nyutag.messenger.android"
    const val DEBUG_APP_ID = "$APP_ID.debug"
    const val RING_RULE_ID = "nyutag.ring"

    fun appID(debug: Boolean): String = if (debug) DEBUG_APP_ID else APP_ID

    /** Куда ведёт пуш: комната и событие из данных шлюза; `null`, если это не пуш сообщения (например, счётчик). */
    fun target(data: Map<String, String>): Pair<String, String>? {
        val roomID = data["room_id"]?.takeIf { it.isNotEmpty() } ?: return null
        val eventID = data["event_id"]?.takeIf { it.isNotEmpty() } ?: return null
        return roomID to eventID
    }
}

/** Включить пуши на этом устройстве. Повторный вызов с тем же токеном обновляет запись, а не плодит новую. */
suspend fun MatrixClient.registerAndroidPush(token: String, debug: Boolean, deviceName: String, language: String) {
    request(Method.POST, "pushers/set", body = JsonValue.of(
        "kind" to "http",
        "app_id" to AndroidPusher.appID(debug),
        "pushkey" to token,
        "app_display_name" to "Нютаг",
        "device_display_name" to deviceName,
        "lang" to language,
        "append" to false,
        "data" to JsonValue.of("url" to AndroidPusher.GATEWAY, "format" to "event_id_only"),
    ))
}

/** Выключить пуши этого устройства (выход, запрет уведомлений). */
suspend fun MatrixClient.removeAndroidPush(token: String, debug: Boolean) {
    request(Method.POST, "pushers/set", body = JsonValue.of(
        "kind" to JsonValue.Null,
        "app_id" to AndroidPusher.appID(debug),
        "pushkey" to token,
    ))
}

/** Правило «будильник звонка — уведомлять»: без него сервер не отдаст `chat.nyutag.ring` шлюзу (стандартные
 *  правила личного чата смотрят только сообщения). Повторная запись ничего не ломает. */
suspend fun MatrixClient.ensureRingPushRule() {
    request(Method.PUT, "pushrules/global/override/${AndroidPusher.RING_RULE_ID}", body = JsonValue.of(
        "conditions" to listOf(JsonValue.of("kind" to "event_match", "key" to "type", "pattern" to CallEventType.RING)),
        "actions" to listOf("notify"),
    ))
}
