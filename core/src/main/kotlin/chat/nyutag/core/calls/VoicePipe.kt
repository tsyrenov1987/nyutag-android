// Нютаг. Конвейер голоса одного звонка. Три потока: захват (микрофон → Opus → шифр → релей), сеть (кадр → шифр →
// буфер дрожания) и воспроизведение (буфер → Opus → динамик). Воспроизведение тянет кадры само, в темпе звуковой
// карты, поэтому часы отправителя и получателя не расходятся: лишнее срезает буфер дрожания.
// В групповом звонке у каждого участника свой буфер и декодер, при воспроизведении голоса складываются.
// Кодек — снаружи: в приложении Opus из MediaCodec, в тестах на Маке — подделка.

package chat.nyutag.core.calls

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

object VoiceFormat {
    const val SAMPLE_RATE = 48_000
    /** Кадр 20 мс. */
    const val FRAME_SAMPLES = 960
    const val BIT_RATE = 32_000
}

/** Кодек одного направления. Не потокобезопасен: кодировщик живёт в потоке захвата, декодер — в потоке звука. */
interface VoiceEncoder {
    /** Ровно 960 отсчётов float32 −1…1 на входе. */
    fun encode(samples: FloatArray): ByteArray?
    fun close() {}
}

interface VoiceDecoder {
    /** Отсчёты одного кадра; битый кадр — пустой массив. */
    fun decode(packet: ByteArray): FloatArray
    fun close() {}
}

interface VoiceCodecs {
    fun encoder(): VoiceEncoder?
    fun decoder(): VoiceDecoder?
}

data class VoiceStats(
    val sent: Int = 0,
    val received: Int = 0,
    val played: Int = 0,
    val lost: Int = 0,
    /** Громкость последнего кадра, 0…1 (RMS). */
    val inputLevel: Float = 0f,
    val outputLevel: Float = 0f,
)

/** Источник и приёмник звука: микрофон с динамиком телефона или подделка в тестах. */
interface VoiceIO {
    fun start(pipe: VoicePipe)
    fun stop()
}

class VoicePipe private constructor(
    /** Метка своих кадров в групповом звонке: релей раздаёт кадры всем, по метке получатель находит ключ.
     *  В звонке один на один меток нет. */
    private val tag: Long?,
    sealer: CallSecret,
    private val codecs: VoiceCodecs,
    private val encoder: VoiceEncoder,
    initial: Map<Long, Track>,
    private val send: (ByteArray) -> Unit,
) {
    /** Голос одного собеседника: свой шифр, буфер дрожания и декодер. Декодер и `playing` — только поток звука. */
    private class Track(secret: CallSecret, val decoder: VoiceDecoder) {
        val opener = CallCipher(secret)
        val jitter = JitterBuffer()
        val received = AtomicInteger(0)
        var playing = FloatArray(0)
        var playIndex = 0
    }

    private val lock = Any()
    private val sealer = CallCipher(sealer)
    private var sent = 0
    private var inputLevel = 0f
    private var outputLevel = 0f
    @Volatile var muted = false
    private val tracks = HashMap(initial)
    /** Только поток захвата. */
    private val pending = FloatArray(VoiceFormat.FRAME_SAMPLES)
    private var pendingCount = 0
    /** Только поток воспроизведения. */
    private var mix = FloatArray(0)
    private val silence = FloatArray(VoiceFormat.FRAME_SAMPLES)
    @Volatile private var closed = false

    /** Участник группового звонка: его кадры с меткой `tag` открываются `secret`. */
    fun add(tag: Long, secret: CallSecret) {
        if (this.tag == null) return
        val decoder = codecs.decoder() ?: return
        synchronized(lock) { tracks.put(tag, Track(secret, decoder)) }?.decoder?.close()
    }

    fun remove(tag: Long) {
        synchronized(lock) { tracks.remove(tag) }
    }

    val voices: Int get() = synchronized(lock) { tracks.size }

    val stats: VoiceStats get() = synchronized(lock) {
        var stats = VoiceStats(sent = sent, inputLevel = inputLevel, outputLevel = outputLevel)
        for (track in tracks.values) {
            stats = stats.copy(received = stats.received + track.received.get(), played = stats.played + track.jitter.played,
                               lost = stats.lost + track.jitter.lost)
        }
        stats
    }

    /** Захват: отсчёты 48 кГц моно любой длины. Без звука (микрофон выключен) шлём тишину: у собеседника не
     *  пустеет буфер, и после включения не надо копить заново. */
    fun capture(samples: FloatArray, count: Int = samples.size) {
        var index = 0
        while (index < count) {
            val take = minOf(count - index, VoiceFormat.FRAME_SAMPLES - pendingCount)
            samples.copyInto(pending, pendingCount, index, index + take)
            pendingCount += take
            index += take
            if (pendingCount < VoiceFormat.FRAME_SAMPLES) break
            pendingCount = 0
            if (closed) continue
            val muted = muted
            val encoded = encoder.encode(if (muted) silence else pending) ?: continue
            val level = if (muted) 0f else rms(pending, pending.size)
            val packet = synchronized(lock) {
                inputLevel = level
                sent += 1
                sealer.seal(FrameKind.AUDIO, encoded)
            } ?: continue
            send(if (tag != null) label(tag) + packet else packet)
        }
    }

    /** Кадр из релея. Чужие и подделанные отбрасываются; в групповом звонке впереди метка отправителя. */
    fun receive(packet: ByteArray) {
        var body = packet
        var key = 0L
        if (tag != null) {
            if (packet.size <= 4) return
            key = unlabel(packet)
            body = packet.copyOfRange(4, packet.size)
        }
        val track = synchronized(lock) { tracks[key] } ?: return
        val frame = track.opener.open(body) ?: return
        if (frame.kind != FrameKind.AUDIO) return
        track.received.incrementAndGet()
        synchronized(lock) { track.jitter.insert(frame.sequence, frame.payload) }
    }

    /** Воспроизведение: заполнить буфер звуковой карты (`count` отсчётов). Голоса участников складываются. */
    fun render(out: FloatArray, count: Int = out.size) {
        out.fill(0f, 0, count)
        val playing = synchronized(lock) { tracks.values.toList() }
        if (mix.size < count) mix = FloatArray(count)
        var level = 0f
        for (track in playing) {
            level = maxOf(level, fill(track, count))
            for (i in 0 until count) out[i] += mix[i]
        }
        if (playing.size > 1) {
            for (i in 0 until count) out[i] = out[i].coerceIn(-1f, 1f)
        }
        synchronized(lock) { outputLevel = level }
    }

    /** Следующие `count` отсчётов голоса в `mix`. Возвращает громкость последнего декодированного кадра. */
    private fun fill(track: Track, count: Int): Float {
        var filled = 0
        var level = if (track.playing.isEmpty()) 0f else rms(track.playing, track.playing.size)
        while (filled < count) {
            if (track.playIndex >= track.playing.size) {
                val pulled = synchronized(lock) { track.jitter.pull() }
                track.playing = if (pulled is JitterBuffer.Pull.Frame) {
                    track.decoder.decode(pulled.payload).takeIf { it.isNotEmpty() } ?: silence
                } else {
                    silence
                }
                track.playIndex = 0
                level = rms(track.playing, track.playing.size)
            }
            val n = minOf(count - filled, track.playing.size - track.playIndex)
            track.playing.copyInto(mix, filled, track.playIndex, track.playIndex + n)
            filled += n
            track.playIndex += n
        }
        return level
    }

    /** Кодеки отдать: зовёт звонок, когда звук уже остановлен. */
    fun close() {
        closed = true
        val all = synchronized(lock) { tracks.values.toList().also { tracks.clear() } }
        for (track in all) track.decoder.close()
        encoder.close()
    }

    companion object {
        fun direct(secret: CallSecret, codecs: VoiceCodecs, send: (ByteArray) -> Unit): VoicePipe? {
            val decoder = codecs.decoder() ?: return null
            val encoder = codecs.encoder() ?: return null.also { decoder.close() }
            return VoicePipe(null, secret, codecs, encoder, mapOf(0L to Track(secret, decoder)), send)
        }

        /** Групповой звонок: свои кадры шифруем своим ключом и метим `tag`, голоса участников добавляет `add`. */
        fun group(secret: CallSecret, tag: Long, codecs: VoiceCodecs, send: (ByteArray) -> Unit): VoicePipe? {
            val encoder = codecs.encoder() ?: return null
            return VoicePipe(tag, secret, codecs, encoder, emptyMap(), send)
        }

        fun label(tag: Long): ByteArray = byteArrayOf((tag shr 24).toByte(), (tag shr 16).toByte(), (tag shr 8).toByte(), tag.toByte())

        fun unlabel(packet: ByteArray): Long =
            ((packet[0].toLong() and 0xFF) shl 24) or ((packet[1].toLong() and 0xFF) shl 16) or
                ((packet[2].toLong() and 0xFF) shl 8) or (packet[3].toLong() and 0xFF)

        fun rms(samples: FloatArray, count: Int): Float {
            if (count == 0) return 0f
            var sum = 0f
            for (i in 0 until count) sum += samples[i] * samples[i]
            return sqrt(sum / count)
        }
    }
}
