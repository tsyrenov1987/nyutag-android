// Заглушка сети: отвечает заданным статусом и запоминает последний запрос.

package chat.nyutag.core

import chat.nyutag.core.net.HttpRequest
import chat.nyutag.core.net.HttpResponse
import chat.nyutag.core.net.Transport
import chat.nyutag.core.net.TransportException
import chat.nyutag.json.JsonValue

class StubTransport : Transport {
    @Volatile var last: HttpRequest? = null
    @Volatile var status: Int = 200
    @Volatile var hits: Int = 0
    /** Тело ответа; `null` — пустой объект. */
    @Volatile var response: JsonValue? = null
    /** Все запросы по порядку. */
    val requests = ArrayList<HttpRequest>()
    /** Если задано — вместо ответа бросаем эту сетевую ошибку. */
    @Volatile var failure: TransportException.Kind? = null

    override suspend fun send(request: HttpRequest): HttpResponse {
        last = request
        synchronized(requests) { requests.add(request) }
        hits += 1
        failure?.let { throw TransportException(it, it.name) }
        val body = response?.compact() ?: "{}"
        return HttpResponse(status, mapOf("Content-Type" to listOf("application/json")), body.toByteArray(Charsets.UTF_8))
    }
}
