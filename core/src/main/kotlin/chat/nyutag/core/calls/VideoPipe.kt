// Нютаг. Конвейер видео одного звонка, как у iOS-клиента. Отправка: камера → H.264 → куски по 6 КБ → шифр → релей.
// Приём: шифр → сборка кадра из кусков → декодер → экран. Видео идёт тем же каналом, что и голос, и не должно его
// душить: пока в канале застряло много байт, новые кадры камеры не кодируются, а битрейт снижается.
// Потерянный кусок (релей сбрасывает кадры медленному получателю) ломает цепочку кадров до следующего ключевого:
// получатель просит его служебным кадром, не дожидаясь планового.
//
// Кадр H.264 на проводе: [флаги 1 байт: бит 0 — ключевой]; у ключевого дальше [число наборов параметров 1 байт] и
// каждый набор как [длина 2 байта BE][байты] (SPS, PPS); затем единицы NAL в виде AVCC (длина 4 байта BE + байты).
// Кусок: [номер кадра 2 байта BE][номер куска 1 байт][кусков в кадре 1 байт][байты кадра].
// Служебный: [код 1 байт]: 1 — пришли ключевой кадр, 2 — камера выключена.
// Кодек и камера — снаружи: в приложении MediaCodec и Camera2, в тестах на Маке — подделки.

package chat.nyutag.core.calls

object VideoFormat {
    /** Портрет. */
    const val WIDTH = 480
    const val HEIGHT = 640
    const val FRAME_RATE = 24
    const val START_BIT_RATE = 600_000
    const val MIN_BIT_RATE = 150_000
    const val MAX_BIT_RATE = 1_000_000
    /** Ключевой кадр не реже, с. */
    const val KEY_INTERVAL = 2
}

class VideoFrame(
    val keyframe: Boolean,
    /** SPS и PPS; только у ключевого. */
    val parameterSets: List<ByteArray>,
    /** Единицы NAL в виде AVCC. */
    val units: ByteArray,
) {
    val wire: ByteArray
        get() {
            val out = java.io.ByteArrayOutputStream(units.size + 64)
            out.write(if (keyframe) 1 else 0)
            if (keyframe) {
                out.write(parameterSets.size)
                for (set in parameterSets) {
                    out.write(set.size shr 8)
                    out.write(set.size and 0xFF)
                    out.write(set)
                }
            }
            out.write(units)
            return out.toByteArray()
        }

    override fun equals(other: Any?) = other is VideoFrame && other.keyframe == keyframe && other.units.contentEquals(units) &&
        other.parameterSets.size == parameterSets.size && other.parameterSets.zip(parameterSets).all { (a, b) -> a.contentEquals(b) }

    override fun hashCode() = units.contentHashCode()

    companion object {
        fun of(wire: ByteArray): VideoFrame? {
            if (wire.isEmpty()) return null
            val keyframe = wire[0].toInt() and 1 == 1
            var at = 1
            val sets = ArrayList<ByteArray>()
            if (keyframe) {
                if (at >= wire.size) return null
                val count = wire[at].toInt() and 0xFF
                at += 1
                repeat(count) {
                    if (at + 2 > wire.size) return null
                    val length = (wire[at].toInt() and 0xFF) shl 8 or (wire[at + 1].toInt() and 0xFF)
                    at += 2
                    if (length <= 0 || at + length > wire.size) return null
                    sets += wire.copyOfRange(at, at + length)
                    at += length
                }
                if (sets.size < 2) return null
            }
            if (at >= wire.size) return null
            return VideoFrame(keyframe, sets, wire.copyOfRange(at, wire.size))
        }
    }
}

/** Кадр камеры в I420: яркость целиком, цвет вдвое меньше по обеим осям. Время — мкс. */
class VideoPicture(val width: Int, val height: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray, val timeMicros: Long)

/** Кодировщик одного звонка: кадры подаются из потока камеры, готовые уходят в `output` из потока кодека. */
interface VideoEncoder {
    fun encode(picture: VideoPicture)
    /** Собеседник потерял кадр: следующий — ключевой. */
    fun requestKeyframe()
    fun setBitRate(value: Int)
    fun close()
}

interface VideoDecoder {
    /** `false` — кадр не лёг (нет ключевого до него или поток испорчен): нужен ключевой. */
    fun decode(frame: VideoFrame): Boolean
    fun close()
}

interface VideoCodecs {
    fun encoder(output: (VideoFrame) -> Unit): VideoEncoder?
    /** `shown(ширина, высота)` — кадр собеседника лёг на экран. */
    fun decoder(shown: (Int, Int) -> Unit): VideoDecoder?
}

/** Камера телефона или подделка в тестах. Кадры отдаёт в `pipe.capture`; своё превью показывает сама. */
interface VideoSource {
    fun start(pipe: VideoPipe)
    fun stop()
    /** Фронтальная ↔ основная. */
    fun switchCamera() {}
}

/** Куда конвейер сообщает о картинке собеседника. Вызывается из потоков кодека и звонка. */
class VideoSink(
    val remote: (width: Int, height: Int) -> Unit = { _, _ -> },
    val remoteCameraOff: () -> Unit = {},
)

data class VideoStats(
    val sentFrames: Int = 0,
    val sentKeyframes: Int = 0,
    /** Кадры камеры, не отправленные из-за забитого канала. */
    val skipped: Int = 0,
    val bitRate: Int = VideoFormat.START_BIT_RATE,
    val receivedFrames: Int = 0,
    val shownFrames: Int = 0,
    /** Кадры, которые пришли не целиком или не легли в декодер. */
    val brokenFrames: Int = 0,
    val keyRequestsSent: Int = 0,
    val keyRequestsReceived: Int = 0,
    val remoteWidth: Int = 0,
    val remoteHeight: Int = 0,
)

class VideoPipe(
    secret: CallSecret,
    private val codecs: VideoCodecs,
    private val send: (ByteArray) -> Unit,
    private val backlog: () -> Int = { 0 },
) {
    private val outgoing = Any()
    private val videoSealer = CallCipher(secret)
    private val controlSealer = CallCipher(secret)
    private var frameNumber = 0
    private var cameraOnValue = false
    private var windowStart = System.nanoTime()
    private var windowSkipped = 0

    private val incoming = Any()
    private var lastSequence: Long? = null
    private var assembling: Int? = null
    private var chunks: Array<ByteArray?> = emptyArray()
    private var needKey = true
    private var lastKeyRequest = 0L

    private val opener = CallCipher(secret)
    @Volatile private var counters = VideoStats()
    private val countersLock = Any()
    @Volatile var sink: VideoSink? = null
    @Volatile private var closed = false
    private var encoder: VideoEncoder? = null
    private var decoder: VideoDecoder? = null

    val stats: VideoStats get() = counters

    private inline fun count(change: (VideoStats) -> VideoStats) {
        synchronized(countersLock) { counters = change(counters) }
    }

    /** Своя камера включена: кадры уходят собеседнику. Выключили — он узнаёт об этом сразу, а не по тишине. */
    var cameraOn: Boolean
        get() = synchronized(outgoing) { cameraOnValue }
        set(value) {
            val changed = synchronized(outgoing) {
                val was = cameraOnValue
                cameraOnValue = value
                was != value
            }
            if (!changed) return
            if (value) {
                synchronized(outgoing) { encoder }?.requestKeyframe()
            } else {
                // Служебный кадр может потеряться у релея: дважды.
                sendControl(CONTROL_CAMERA_OFF)
                sendControl(CONTROL_CAMERA_OFF)
            }
        }

    fun close() {
        closed = true
        sink = null
        // Закрываем вне замков: поток кодировщика может ждать замок в ship, а close его дожидается.
        synchronized(outgoing) { encoder.also { encoder = null } }?.close()
        synchronized(incoming) { decoder.also { decoder = null } }?.close()
    }

    // MARK: Отправка

    /** Кадр камеры. Поток камеры один. */
    fun capture(picture: VideoPicture) {
        if (closed) return
        val now = System.nanoTime()
        val crowded = backlog() > BACKLOG_LIMIT
        var retune: Int? = null
        val encoder = synchronized(outgoing) {
            if (!cameraOnValue || closed) return
            if (crowded) windowSkipped += 1
            if (now - windowStart >= 1_000_000_000L) {
                val current = counters.bitRate
                val next = when {
                    windowSkipped > 2 -> maxOf(VideoFormat.MIN_BIT_RATE, current * 7 / 10)
                    windowSkipped == 0 -> minOf(VideoFormat.MAX_BIT_RATE, current + 50_000)
                    else -> current
                }
                if (next != current) retune = next
                windowStart = now
                windowSkipped = 0
            }
            encoder ?: codecs.encoder { ship(it) }?.also { made ->
                made.setBitRate(counters.bitRate)
                encoder = made
            }
        } ?: return
        retune?.let { bitRate ->
            encoder.setBitRate(bitRate)
            count { it.copy(bitRate = bitRate) }
        }
        if (crowded) return count { it.copy(skipped = it.skipped + 1) }
        encoder.encode(picture)
    }

    private fun ship(frame: VideoFrame) {
        val wire = frame.wire
        val total = (wire.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        if (total <= 0 || total > 255) return
        synchronized(outgoing) {
            if (!cameraOnValue || closed) return
            val number = frameNumber
            frameNumber = (frameNumber + 1) and 0xFFFF
            for (index in 0 until total) {
                val start = index * CHUNK_SIZE
                val end = minOf(start + CHUNK_SIZE, wire.size)
                val chunk = ByteArray(4 + end - start)
                chunk[0] = (number shr 8).toByte()
                chunk[1] = number.toByte()
                chunk[2] = index.toByte()
                chunk[3] = total.toByte()
                System.arraycopy(wire, start, chunk, 4, end - start)
                // Отправка только ставит в очередь: под замком, чтобы куски уходили по порядку номеров.
                videoSealer.seal(FrameKind.VIDEO, chunk)?.let(send)
            }
        }
        count { it.copy(sentFrames = it.sentFrames + 1, sentKeyframes = it.sentKeyframes + if (frame.keyframe) 1 else 0) }
    }

    private fun sendControl(code: Int) {
        val packet = synchronized(outgoing) { controlSealer.seal(FrameKind.CONTROL, byteArrayOf(code.toByte())) }
        packet?.let(send)
    }

    // MARK: Приём

    /** Кадр видео или служебный из релея. Вызывается из одного потока (звонка). */
    fun receive(packet: ByteArray) {
        if (closed) return
        val opened = opener.open(packet) ?: return
        when (opened.kind) {
            FrameKind.CONTROL -> when (opened.payload.firstOrNull()?.toInt()) {
                CONTROL_KEYFRAME -> {
                    count { it.copy(keyRequestsReceived = it.keyRequestsReceived + 1) }
                    synchronized(outgoing) { encoder }?.requestKeyframe()
                }
                CONTROL_CAMERA_OFF -> sink?.remoteCameraOff?.invoke()
                else -> {}
            }
            FrameKind.VIDEO -> take(opened.sequence, opened.payload)
            FrameKind.AUDIO -> {}
        }
    }

    private sealed class Next {
        object Wait : Next()
        object Broken : Next()
        class Ready(val wire: ByteArray) : Next()
    }

    private fun take(sequence: Long, chunk: ByteArray) {
        if (chunk.size < 4) return
        val index = chunk[2].toInt() and 0xFF
        val total = chunk[3].toInt() and 0xFF
        if (total == 0 || index >= total) return
        val number = (chunk[0].toInt() and 0xFF) shl 8 or (chunk[1].toInt() and 0xFF)
        val next = synchronized(incoming) {
            var broken = false
            val last = lastSequence
            if (last != null && sequence != ((last + 1) and 0xFFFF_FFFFL)) {
                // Пропал кусок: собираемый кадр не соберётся, и цепочка до него тоже порвана.
                broken = true
                needKey = true
            }
            lastSequence = sequence
            if (assembling != number) {
                if (assembling != null && chunks.any { it == null }) {
                    broken = true
                    needKey = true
                }
                assembling = number
                chunks = arrayOfNulls(total)
            }
            if (chunks.size != total) return@synchronized Next.Broken
            chunks[index] = chunk.copyOfRange(4, chunk.size)
            if (chunks.any { it == null }) return@synchronized if (broken) Next.Broken else Next.Wait
            val out = java.io.ByteArrayOutputStream(chunks.sumOf { it!!.size })
            for (part in chunks) out.write(part!!)
            assembling = null
            chunks = emptyArray()
            Next.Ready(out.toByteArray())
        }
        when (next) {
            Next.Wait -> {}
            Next.Broken -> {
                count { it.copy(brokenFrames = it.brokenFrames + 1) }
                askKeyframe()
            }
            is Next.Ready -> {
                val frame = VideoFrame.of(next.wire) ?: return askKeyframe()
                count { it.copy(receivedFrames = it.receivedFrames + 1) }
                if (synchronized(incoming) { needKey } && !frame.keyframe) {
                    // Цепочка до этого кадра порвана: без ключевого он не ляжет.
                    count { it.copy(brokenFrames = it.brokenFrames + 1) }
                    return askKeyframe()
                }
                val decoder = synchronized(incoming) {
                    if (closed) return
                    decoder ?: codecs.decoder { width, height -> show(width, height) }?.also { decoder = it }
                } ?: return
                if (decoder.decode(frame)) {
                    if (frame.keyframe) synchronized(incoming) { needKey = false }
                } else {
                    synchronized(incoming) { needKey = true }
                    count { it.copy(brokenFrames = it.brokenFrames + 1) }
                    askKeyframe()
                }
            }
        }
    }

    /** Попросить у собеседника ключевой кадр: не чаще двух раз в секунду, ключевой тяжёлый, а просьба могла уже уйти. */
    fun askKeyframe() {
        val now = System.nanoTime()
        val due = synchronized(incoming) {
            if (lastKeyRequest != 0L && now - lastKeyRequest < 500_000_000L) return@synchronized false
            lastKeyRequest = now
            true
        }
        if (!due) return
        count { it.copy(keyRequestsSent = it.keyRequestsSent + 1) }
        sendControl(CONTROL_KEYFRAME)
    }

    private fun show(width: Int, height: Int) {
        count { it.copy(shownFrames = it.shownFrames + 1, remoteWidth = width, remoteHeight = height) }
        sink?.remote?.invoke(width, height)
    }

    companion object {
        const val CHUNK_SIZE = 6_000
        /** Столько байт может ждать отправки, прежде чем новые кадры камеры начнут пропускаться. */
        const val BACKLOG_LIMIT = 96 shl 10
        private const val CONTROL_KEYFRAME = 1
        private const val CONTROL_CAMERA_OFF = 2
    }
}
