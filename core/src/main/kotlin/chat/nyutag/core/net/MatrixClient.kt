// Нютаг. HTTP-слой клиент-серверного API Matrix поверх [Transport].
// К ядру клиент ходит через дверь ([Door]): обычный HTTPS-адрес, который подставляется вместо `homeserver`,
// плюс заголовок с ключом. Без ключа (тесты на Маке по SSH-пробросу) клиент ходит на `homeserver` напрямую.

package chat.nyutag.core.net

import chat.nyutag.json.JsonValue
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MatrixError(
    val kind: Kind,
    /** Пауза перед повтором для `M_LIMIT_EXCEEDED`, мс. */
    val retryAfterMillis: Long? = null,
    /** Сессия подтверждения (401 с `session` и `flows`): запрос надо повторить с `auth`. */
    val uiaSession: String? = null,
) : Exception(kind.toString()) {
    sealed class Kind {
        /** Сервер ответил ошибкой Matrix (`errcode` + `error`). */
        data class Server(val status: Int, val errcode: String, val message: String) : Kind()
        /** Сервер ответил не JSON-ошибкой (например, 502 от прокси). */
        data class Http(val status: Int) : Kind()
        data class Network(val reason: String) : Kind()
        data class Decoding(val reason: String) : Kind()
        object NoAccessToken : Kind() { override fun toString() = "NoAccessToken" }
    }

    val errcode: String? get() = (kind as? Kind.Server)?.errcode

    val status: Int? get() = when (kind) {
        is Kind.Server -> kind.status
        is Kind.Http -> kind.status
        else -> null
    }

    val isUnknownToken: Boolean get() = errcode == "M_UNKNOWN_TOKEN"
}

/** Один сеанс запросов к домашнему серверу. Токен доступа меняется при входе и выходе. */
class MatrixClient(
    /** Имя ядра: хранится в сессии и в токенах, а запросы при дверях идут на адрес двери. */
    val homeserver: URI,
    accessToken: String? = null,
    val transport: Transport = UrlConnectionTransport(),
    val userAgent: String = "Nyutag",
    /** Ключ двери; `null` — клиент без дверей, ходит на `homeserver` (тесты на Маке). */
    private val doorKey: String? = null,
    door: Door? = null,
    /** При дверях без двери запрос ждёт её не дольше этого (приложение только запустилось, гонка ещё идёт),
     *  потом падает ошибкой сети; на `homeserver` напрямую он не уходит — снаружи ядро закрыто. */
    private val doorWaitMillis: Long = 20_000,
) {
    enum class Method { GET, POST, PUT, DELETE }

    @Volatile var accessToken: String? = accessToken
        private set

    private val doorFlow = MutableStateFlow(door)
    /** Текущая дверь. */
    val door: Door? get() = doorFlow.value
    val doors: StateFlow<Door?> get() = doorFlow

    /** Жалоба на дверь: запрос к ней не дошёл или узел ответил 502–504. Получает тот, кто выбирает двери. */
    @Volatile private var doorTrouble: ((Door) -> Unit)? = null

    /** Повторы при 429: не больше этого числа, и не дольше суммарно `maxRetryWaitMillis`. */
    var maxRateLimitRetries = 4
    var maxRetryWaitMillis = 30_000L
    /** Сетевой таймаут обычного запроса, мс. */
    var defaultTimeoutMillis = 60_000L

    /** Гонка выбрала другую дверь (или ни одной: `null`). Запросы в полёте дописываются на старой, новые идут
     *  на новую; ждущие двери берут её сразу. */
    fun setDoor(newDoor: Door?) {
        doorFlow.value = newDoor
    }

    fun setDoorTrouble(handler: ((Door) -> Unit)?) {
        doorTrouble = handler
    }

    /** Клиент переключается на каждую дверь из потока, пока жива `scope`. */
    fun follow(doors: Flow<Door?>, scope: CoroutineScope): Job = scope.launch {
        doors.collect { setDoor(it) }
    }

    fun setAccessToken(token: String?) {
        accessToken = token
    }

    // MARK: Запросы

    /** Запрос к `/_matrix/client/<version>/<path>`; ответ — дерево JSON (пустое тело — пустой объект). */
    suspend fun request(
        method: Method,
        path: String,
        version: String = API_VERSION,
        query: Map<String, String> = emptyMap(),
        repeatedQuery: Map<String, List<String>> = emptyMap(),
        body: JsonValue? = null,
        authenticated: Boolean = true,
        timeoutMillis: Long? = null,
    ): JsonValue = raw(method, "/_matrix/client/$version/$path", query, repeatedQuery, body, authenticated, timeoutMillis)

    /** Запрос по произвольному пути сервера (`/_nyutag/v1/...`, `/_matrix/media/...`). */
    suspend fun raw(
        method: Method,
        absolutePath: String,
        query: Map<String, String> = emptyMap(),
        repeatedQuery: Map<String, List<String>> = emptyMap(),
        body: JsonValue? = null,
        authenticated: Boolean = true,
        timeoutMillis: Long? = null,
    ): JsonValue {
        val bytes = data(method, absolutePath, query, repeatedQuery, body, authenticated, timeoutMillis)
        if (bytes.isEmpty()) return JsonValue.Obj(emptyMap())
        return try {
            JsonValue.parse(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw MatrixError(MatrixError.Kind.Decoding("$absolutePath: ${e.message}"))
        }
    }

    /** Сырые байты ответа (медиа, скачивание). */
    suspend fun data(
        method: Method,
        absolutePath: String,
        query: Map<String, String> = emptyMap(),
        repeatedQuery: Map<String, List<String>> = emptyMap(),
        body: JsonValue? = null,
        authenticated: Boolean = true,
        timeoutMillis: Long? = null,
    ): ByteArray {
        val headers = HashMap<String, String>()
        var bytes: ByteArray? = null
        if (body != null) {
            bytes = body.compact().toByteArray(Charsets.UTF_8)
            headers["Content-Type"] = "application/json"
        }
        val request = makeRequest(method, absolutePath, query, repeatedQuery, authenticated, timeoutMillis, headers, bytes)
        return perform(request)
    }

    /** Загрузка байтов (медиа) с заданным Content-Type. */
    suspend fun upload(absolutePath: String, query: Map<String, String> = emptyMap(), body: ByteArray,
                       contentType: String): ByteArray {
        val request = makeRequest(Method.POST, absolutePath, query, emptyMap(), true, 300_000,
                                  hashMapOf("Content-Type" to contentType), body)
        return perform(request)
    }

    /** Запрос на WebSocket (релей звонков): тот же путь через дверь и те же заголовки, схема `https` остаётся —
     *  сокет сам делает Upgrade поверх TLS. */
    suspend fun webSocket(absolutePath: String, query: Map<String, String> = emptyMap()): HttpRequest {
        val request = makeRequest(Method.GET, absolutePath, query, emptyMap(), true, null, HashMap(), null)
        return HttpRequest(request.method, request.url, request.headers - "Accept", null, 15_000)
    }

    // MARK: Внутреннее

    /** Основа адреса: дверь при дверях, иначе `homeserver`. Двери нет — ждём её, пока идёт гонка. */
    private suspend fun base(): URI {
        if (doorKey == null) return homeserver
        doorFlow.value?.let { return it.url }
        val door = if (doorWaitMillis <= 0) null else withTimeoutOrNull(doorWaitMillis) { doorFlow.filterNotNull().first() }
        return door?.url ?: throw MatrixError(MatrixError.Kind.Network(NO_PATH))
    }

    /** `repeatedQuery`: ключ, повторённый для каждого значения (`?via=a&via=b`), идёт после обычных параметров. */
    private suspend fun makeRequest(
        method: Method, absolutePath: String, query: Map<String, String>, repeatedQuery: Map<String, List<String>>,
        authenticated: Boolean, timeoutMillis: Long?, headers: HashMap<String, String>, body: ByteArray?,
    ): HttpRequest {
        val base = base()
        val authority = base.rawAuthority ?: throw MatrixError(MatrixError.Kind.Network("плохой адрес сервера"))
        val items = ArrayList<Pair<String, String>>()
        for (key in query.keys.sorted()) items += key to query.getValue(key)
        for (key in repeatedQuery.keys.sorted()) for (value in repeatedQuery.getValue(key)) items += key to value
        val address = StringBuilder("${base.scheme}://$authority").append(encodePath(absolutePath))
        if (items.isNotEmpty()) {
            address.append('?')
            items.forEachIndexed { index, (key, value) ->
                if (index > 0) address.append('&')
                address.append(encodeQuery(key)).append('=').append(encodeQuery(value))
            }
        }
        val url = try { URI(address.toString()) } catch (e: Exception) {
            throw MatrixError(MatrixError.Kind.Network("плохой путь $absolutePath"))
        }
        headers["User-Agent"] = userAgent
        headers["Accept"] = "application/json"
        if (doorKey != null) headers[DoorCatalogue.HEADER_NAME] = doorKey
        if (authenticated) {
            val token = accessToken ?: throw MatrixError(MatrixError.Kind.NoAccessToken)
            headers["Authorization"] = "Bearer $token"
        }
        val timeout = if (timeoutMillis != null) timeoutMillis + 5_000 else defaultTimeoutMillis
        return HttpRequest(method.name, url, headers, body, timeout)
    }

    private suspend fun perform(request: HttpRequest): ByteArray {
        var attempt = 0
        var waited = 0L
        while (true) {
            val response = try {
                transport.send(request)
            } catch (e: TransportException) {
                if (isDoorTrouble(e.kind)) complain(request)
                throw MatrixError(MatrixError.Kind.Network(e.message ?: e.kind.name))
            }
            if (response.status in 200..299) return response.body
            if (response.status in 502..504) complain(request)

            val error = parseError(response.status, response.body, response.header("Retry-After"))
            if (response.status == 429 && attempt < maxRateLimitRetries) {
                val pause = error.retryAfterMillis ?: 2_000
                if (waited + pause <= maxRetryWaitMillis) {
                    attempt += 1
                    waited += pause
                    delay(pause)
                    continue
                }
            }
            throw error
        }
    }

    /** Жалуемся только на ту дверь, к которой шёл запрос: на сменившуюся за время полёта жаловаться поздно. */
    private fun complain(request: HttpRequest) {
        val door = doorFlow.value ?: return
        if (!request.url.host.equals(door.url.host, ignoreCase = true)) return
        doorTrouble?.invoke(door)
    }

    companion object {
        const val API_VERSION = "v3"
        const val NO_PATH = "нет пути к ядру"

        /** Беда двери, а не всей сети: без интернета вообще другая дверь не поможет. */
        fun isDoorTrouble(kind: TransportException.Kind): Boolean = when (kind) {
            TransportException.Kind.NO_INTERNET, TransportException.Kind.DATA_NOT_ALLOWED -> false
            else -> true
        }

        private val unreserved = BooleanArray(128).also { table ->
            for (c in 'a'..'z') table[c.code] = true
            for (c in 'A'..'Z') table[c.code] = true
            for (c in '0'..'9') table[c.code] = true
            for (c in "-._~") table[c.code] = true
        }

        /** Сегменты пути (идентификаторы комнат и событий содержат `!`, `:`, `$`) кодируем по RFC 3986. */
        fun encodePath(path: String): String =
            path.split("/").joinToString("/") { segment -> percentEncode(segment) }

        private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun percentEncode(text: String): String {
            val out = StringBuilder()
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val code = byte.toInt() and 0xFF
                if (code < 128 && unreserved[code]) out.append(code.toChar()) else out.append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
            }
            return out.toString()
        }

        private const val HEX = "0123456789ABCDEF"

        fun parseError(status: Int, body: ByteArray, retryAfterHeader: String?): MatrixError {
            var retryAfter: Long? = retryAfterHeader?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            val json = try { JsonValue.parse(String(body, Charsets.UTF_8)) } catch (_: Exception) { null }
            val uiaSession = if (status == 401 && json?.get("flows") != null) json["session"]?.string else null
            val errcode = json?.get("errcode")?.string
            if (json != null && errcode != null) {
                json["retry_after_ms"]?.long?.let { retryAfter = it }
                return MatrixError(MatrixError.Kind.Server(status, errcode, json["error"]?.string ?: ""), retryAfter, uiaSession)
            }
            return MatrixError(MatrixError.Kind.Http(status), retryAfter, uiaSession)
        }
    }
}
