// Нютаг. Сетевая основа: один HTTP-запрос и ответ. В приложении запросы идут через HttpsURLConnection
// из состава Android, в тестах на Маке — через заглушку или поддельный сервер.

package chat.nyutag.core.net

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine

class HttpRequest(
    val method: String,
    val url: URI,
    val headers: Map<String, String>,
    val body: ByteArray?,
    /** Сколько ждать ответа (чтение), мс. */
    val timeoutMillis: Long,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

class HttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/** Ошибка сети до ответа сервера. `kind` различает беду двери и беду всей сети. */
class TransportException(val kind: Kind, message: String?, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind {
        TIMED_OUT, CANNOT_CONNECT, UNKNOWN_HOST, TLS_FAILED, CONNECTION_LOST,
        /** Интернета нет вообще: другая дверь не поможет. */
        NO_INTERNET,
        /** Передача данных запрещена (режим полёта, лимит трафика). */
        DATA_NOT_ALLOWED,
        OTHER,
    }
}

interface Transport {
    /** Бросает [TransportException] при сетевой беде, [kotlinx.coroutines.CancellationException] при снятии. */
    suspend fun send(request: HttpRequest): HttpResponse
}

/** HttpsURLConnection из состава платформы. Снятие корутины рвёт соединение, чтобы поток не ждал таймаута. */
open class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = 30_000,
    /** Есть ли сеть вообще (ConnectivityManager в приложении); без ответа «нет» все беды считаются бедой двери. */
    private val isOnline: () -> Boolean = { true },
) : Transport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        val connection = request.url.toURL().openConnection() as HttpURLConnection
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            Dispatchers.IO.asExecutor().execute {
                try {
                    val response = perform(connection, request)
                    continuation.resume(response)
                } catch (e: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(wrap(e))
                }
            }
        }
    }

    private fun perform(connection: HttpURLConnection, request: HttpRequest): HttpResponse {
        connection.requestMethod = request.method
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = request.timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        for ((name, value) in request.headers) connection.setRequestProperty(name, value)
        val body = request.body
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
        connection.disconnect()
        return HttpResponse(status, headers, bytes)
    }

    private fun wrap(error: Throwable): Throwable {
        if (error is TransportException) return error
        if (error !is IOException) return error
        val kind = if (!isOnline()) TransportException.Kind.NO_INTERNET else classify(error)
        return TransportException(kind, error.message ?: error.javaClass.simpleName, error)
    }

    /** Разбор ошибки платформы; приложение может уточнить по своим признакам. */
    protected open fun classify(error: IOException): TransportException.Kind = when (error) {
        is SocketTimeoutException -> TransportException.Kind.TIMED_OUT
        is InterruptedIOException -> TransportException.Kind.TIMED_OUT
        is ConnectException, is NoRouteToHostException -> TransportException.Kind.CANNOT_CONNECT
        is UnknownHostException -> TransportException.Kind.UNKNOWN_HOST
        is SSLException -> TransportException.Kind.TLS_FAILED
        is SocketException -> TransportException.Kind.CONNECTION_LOST
        else -> TransportException.Kind.OTHER
    }
}
