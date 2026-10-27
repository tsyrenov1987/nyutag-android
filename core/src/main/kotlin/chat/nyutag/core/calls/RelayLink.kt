// Нютаг. Связь с релеем звонков: WebSocket к `/_nyutag/relay/v1?slot=…` через тот же путь, что и запросы к ядру
// (дверь, ключ в заголовке). Бинарные сообщения — кадры собеседника как есть, текстовые — служебные (`{"peers":N}`).
// Сокет свой (RFC 6455, только то, что нужно релею): TLS со своим SNI и проверкой имени, Upgrade, маскированные
// кадры от нас, ping раз в 15 с, чтобы молчащий эфир не рвался по таймауту релея. Читает и пишет по своему потоку:
// голос не ждёт сети, а кадр, не успевший уйти, всё равно устарел бы.

package chat.nyutag.core.calls

import chat.nyutag.core.net.HttpRequest
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.crypto.Base64
import chat.nyutag.crypto.Random
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runInterruptible
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

sealed class RelayInbound {
    class Packet(val data: ByteArray) : RelayInbound()
    /** Сколько участников сейчас в слоте, считая нас. */
    data class Peers(val count: Int) : RelayInbound()
}

/** Канал кадров к собеседнику: релей или подделка в тестах. */
interface CallTransport {
    /** Закрывается, когда связь оборвалась или закрыта. */
    val inbound: ReceiveChannel<RelayInbound>
    fun send(packet: ByteArray)
    fun close()
    /** Сколько байт ещё не ушло в сеть: по нему видео уступает голосу. */
    val backlog: Int get() = 0
}

class RelayLink private constructor(private val socket: Socket) : CallTransport {
    private val input: InputStream = BufferedInputStream(socket.getInputStream(), 16 shl 10)
    private val output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 16 shl 10)
    private val channel = Channel<RelayInbound>(256, BufferOverflow.DROP_OLDEST)
    override val inbound: ReceiveChannel<RelayInbound> get() = channel
    private val queue = LinkedBlockingQueue<Outgoing>()
    private val unsent = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    private class Outgoing(val opcode: Int, val payload: ByteArray)

    override val backlog: Int get() = unsent.get()

    override fun send(packet: ByteArray) {
        // Сеть стоит: новые кадры выбрасываем, а не копим. Видео встаёт раньше (VideoPipe.BACKLOG_LIMIT), так что
        // выбрасывается голос, который к доставке всё равно устарел бы.
        if (closed.get() || unsent.get() >= SEND_LIMIT) return
        unsent.addAndGet(packet.size)
        queue.offer(Outgoing(OP_BINARY, packet))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channel.close()
        // Писатель отправит «закрываю» и закроет сокет; если он уже умер — закрываем сами.
        queue.offer(Outgoing(OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte())))
        Thread { linger(); runCatching { socket.close() } }.apply { isDaemon = true }.start()
    }

    /** Дать писателю секунду отправить «закрываю». */
    private fun linger() {
        runCatching { Thread.sleep(1_000) }
    }

    private fun start() {
        Thread(::readLoop, "relay-read").apply { isDaemon = true }.start()
        Thread(::writeLoop, "relay-write").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        val message = java.io.ByteArrayOutputStream()
        var messageOp = 0
        try {
            while (true) {
                val b0 = read()
                val b1 = read()
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                val masked = b1 and 0x80 != 0
                var length = (b1 and 0x7F).toLong()
                if (length == 126L) length = ((read() shl 8) or read()).toLong()
                else if (length == 127L) { length = 0; repeat(8) { length = (length shl 8) or read().toLong() } }
                if (length < 0 || length > MAX_MESSAGE) throw IOException("кадр больше предела")
                val mask = if (masked) readExactly(4) else null
                val payload = readExactly(length.toInt())
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                when (opcode) {
                    OP_PING -> queue.offer(Outgoing(OP_PONG, payload))
                    OP_PONG -> {}
                    OP_CLOSE -> break
                    OP_TEXT, OP_BINARY, OP_CONTINUATION -> {
                        if (opcode != OP_CONTINUATION) { message.reset(); messageOp = opcode }
                        if (message.size() + payload.size > MAX_MESSAGE) throw IOException("сообщение больше предела")
                        message.write(payload)
                        if (fin) deliver(messageOp, message.toByteArray())
                    }
                    else -> throw IOException("неизвестный кадр $opcode")
                }
            }
        } catch (_: Exception) {
        } finally {
            channel.close()
            if (closed.compareAndSet(false, true)) runCatching { socket.close() }
            queue.offer(Outgoing(OP_CLOSE, ByteArray(0)))
        }
    }

    private fun deliver(opcode: Int, data: ByteArray) {
        if (opcode == OP_BINARY) {
            channel.trySend(RelayInbound.Packet(data))
            return
        }
        val json = runCatching { JsonValue.parse(String(data, Charsets.UTF_8)) }.getOrNull() ?: return
        json["peers"]?.int?.let { channel.trySend(RelayInbound.Peers(it)) }
    }

    private fun writeLoop() {
        try {
            while (true) {
                val next = queue.poll(PING_SECONDS, TimeUnit.SECONDS)
                if (next == null) {
                    if (closed.get()) break
                    writeFrame(OP_PING, ByteArray(0))
                    output.flush()
                    continue
                }
                if (next.opcode == OP_BINARY) unsent.addAndGet(-next.payload.size)
                writeFrame(next.opcode, next.payload)
                // Пачку, накопившуюся за время записи, отправляем одним сбросом.
                if (queue.isEmpty() || next.opcode == OP_CLOSE) output.flush()
                if (next.opcode == OP_CLOSE) break
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
            queue.clear()
            unsent.set(0)
        }
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        val size = payload.size
        when {
            size < 126 -> output.write(0x80 or size)
            size < 65_536 -> { output.write(0x80 or 126); output.write(size shr 8); output.write(size and 0xFF) }
            else -> { output.write(0x80 or 127); for (shift in 56 downTo 0 step 8) output.write(((size.toLong() shr shift) and 0xFF).toInt()) }
        }
        val mask = Random.bytes(4)
        output.write(mask)
        val masked = ByteArray(size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() }
        output.write(masked)
    }

    private fun read(): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readExactly(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(bytes, offset, count - offset)
            if (n < 0) throw EOFException()
            offset += n
        }
        return bytes
    }

    companion object {
        const val PATH = "/_nyutag/relay/v1"
        const val MAX_MESSAGE = 256 shl 10
        /** Больше этого в очереди на отправку — сеть стоит. */
        const val SEND_LIMIT = 512 shl 10
        private const val PING_SECONDS = 15L
        /** Без единого байта столько — связь мертва: на наш ping релей отвечает pong каждые 15 с. */
        private const val READ_TIMEOUT_MILLIS = 45_000
        private const val OP_CONTINUATION = 0
        private const val OP_TEXT = 1
        private const val OP_BINARY = 2
        private const val OP_CLOSE = 8
        private const val OP_PING = 9
        private const val OP_PONG = 10
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Проверка имени сервера в сертификате поверх той, что делает сам TLS: в Android её отдаёт система. */
        @Volatile var hostnameCheck: ((String, SSLSession) -> Boolean)? = null

        suspend fun open(client: MatrixClient, slot: String): RelayLink? {
            val request = runCatching { client.webSocket(PATH, mapOf("slot" to slot)) }.getOrNull() ?: return null
            return open(request)
        }

        suspend fun open(request: HttpRequest): RelayLink? = runInterruptible(Dispatchers.IO) {
            runCatching { connect(request) }.getOrNull()
        }

        private fun connect(request: HttpRequest): RelayLink {
            val url = request.url
            val host = url.host ?: throw IOException("нет хоста")
            val secure = url.scheme == "https" || url.scheme == "wss"
            val port = if (url.port > 0) url.port else if (secure) 443 else 80
            val timeout = request.timeoutMillis.toInt()
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(host, port), timeout)
                plain.tcpNoDelay = true
                plain.soTimeout = timeout
                val socket = if (secure) tls(plain, host, port) else plain
                handshake(socket, request, host, port, secure)
                socket.soTimeout = READ_TIMEOUT_MILLIS
                return RelayLink(socket).also { it.start() }
            } catch (e: Exception) {
                runCatching { plain.close() }
                throw e
            }
        }

        private fun tls(plain: Socket, host: String, port: Int): SSLSocket {
            val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
            val params = socket.sslParameters
            if (!isAddress(host)) params.serverNames = listOf(SNIHostName(host))
            params.endpointIdentificationAlgorithm = "HTTPS"
            params.applicationProtocols = arrayOf("http/1.1")
            socket.sslParameters = params
            socket.startHandshake()
            val check = hostnameCheck
            if (check != null && !check(host, socket.session)) throw IOException("сертификат не того сервера")
            return socket
        }

        private fun isAddress(host: String): Boolean = host.contains(':') || host.all { it.isDigit() || it == '.' }

        private fun handshake(socket: Socket, request: HttpRequest, host: String, port: Int, secure: Boolean) {
            val key = Base64.encodePadded(Random.bytes(16))
            val target = (request.url.rawPath ?: "/").ifEmpty { "/" } + (request.url.rawQuery?.let { "?$it" } ?: "")
            val authority = if ((secure && port == 443) || (!secure && port == 80)) host else "$host:$port"
            val text = StringBuilder("GET $target HTTP/1.1\r\n")
                .append("Host: $authority\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: $key\r\n")
                .append("Sec-WebSocket-Version: 13\r\n")
            for ((name, value) in request.headers) {
                if (name.contains('\r') || name.contains('\n') || value.contains('\r') || value.contains('\n')) continue
                text.append(name).append(": ").append(value).append("\r\n")
            }
            text.append("\r\n")
            val out = socket.getOutputStream()
            out.write(text.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            // Заголовки ответа читаем по байту: после них сразу могут идти кадры, лишнего брать нельзя.
            val input = socket.getInputStream()
            val head = java.io.ByteArrayOutputStream()
            var tail = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException()
                head.write(b)
                tail = (tail shl 8) or b
                if (tail == 0x0D0A0D0A) break
                if (head.size() > 16 shl 10) throw IOException("слишком длинный ответ")
            }
            val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull()
            if (status != 101) throw IOException("релей ответил $status")
            val accept = lines.drop(1).firstNotNullOfOrNull { line ->
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("Sec-WebSocket-Accept", true)) line.substring(colon + 1).trim() else null
            }
            if (accept != acceptKey(key)) throw IOException("релей не подтвердил сокет")
        }

        internal fun acceptKey(key: String): String =
            Base64.encodePadded(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.UTF_8)))
    }
}
