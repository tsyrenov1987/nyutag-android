// Помощники тестов сети: путь, параметры и тело запроса в читаемом виде.

package chat.nyutag.core

import chat.nyutag.core.net.HttpRequest
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.json.JsonValue
import java.net.URI
import java.net.URLDecoder

/** Путь запроса без процентов: `/_matrix/client/v3/profile/@me:nyutag.chat`. */
val HttpRequest.path: String get() = URLDecoder.decode(url.rawPath, "UTF-8")

/** Параметры запроса по порядку, уже раскодированные: `server_name=x`. */
val HttpRequest.queryItems: List<String> get() =
    url.rawQuery?.split("&")?.filter { it.isNotEmpty() }?.map { URLDecoder.decode(it, "UTF-8") } ?: emptyList()

/** Параметры запроса словарём (первый побеждает). */
val HttpRequest.queryMap: Map<String, String> get() {
    val map = LinkedHashMap<String, String>()
    for (item in queryItems) {
        val eq = item.indexOf('=')
        val key = if (eq < 0) item else item.substring(0, eq)
        val value = if (eq < 0) "" else item.substring(eq + 1)
        map.putIfAbsent(key, value)
    }
    return map
}

/** Тело запроса как JSON; пустое тело — пустой объект. */
val HttpRequest.json: JsonValue get() {
    val bytes = body ?: return JsonValue.Obj(emptyMap())
    if (bytes.isEmpty()) return JsonValue.Obj(emptyMap())
    return JsonValue.parse(String(bytes, Charsets.UTF_8))
}

/** Клиент к заглушке с токеном `tok`. */
fun stubClient(transport: StubTransport, token: String? = "tok"): MatrixClient =
    MatrixClient(URI("https://nyutag.test"), accessToken = token, transport = transport)
