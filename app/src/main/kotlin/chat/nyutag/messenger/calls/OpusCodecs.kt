// Нютаг. Opus звонков из MediaCodec телефона (кодировщик есть с Android 10): 48 кГц, моно, кадр 20 мс, 32 кбит/с,
// как у iOS-клиента. Кодек работает синхронно в потоке звонка; выход кодека может отстать на кадр — тогда кадр
// уходит со следующим вызовом.

package chat.nyutag.messenger.calls

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import chat.nyutag.core.calls.VoiceCodecs
import chat.nyutag.core.calls.VoiceDecoder
import chat.nyutag.core.calls.VoiceEncoder
import chat.nyutag.core.calls.VoiceFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OpusCodecs : VoiceCodecs {
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS

    override fun encoder(): VoiceEncoder? = runCatching { OpusEncoder() }
        .onFailure { Log.w("calls", "кодировщик Opus не поднялся: $it") }.getOrNull()

    override fun decoder(): VoiceDecoder? = runCatching { OpusDecoder() }
        .onFailure { Log.w("calls", "декодер Opus не поднялся: $it") }.getOrNull()

    /** Отсчёты −1…1 → 16 бит, порядок байт кодека. */
    private fun pcm(samples: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) buffer.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        buffer.flip()
        return buffer
    }

    private class OpusEncoder : VoiceEncoder {
        private val codec = MediaCodec.createEncoderByType(MIME).apply {
            val format = MediaFormat.createAudioFormat(MIME, VoiceFormat.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, VoiceFormat.BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, VoiceFormat.FRAME_SAMPLES * 2 * 4)
            }
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        private val info = MediaCodec.BufferInfo()
        private val ready = ArrayDeque<ByteArray>()
        private var timeUs = 0L

        override fun encode(samples: FloatArray): ByteArray? {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val input = codec.getInputBuffer(index) ?: return null
                input.clear()
                input.put(pcm(samples))
                codec.queueInputBuffer(index, 0, samples.size * 2, timeUs, 0)
                timeUs += samples.size * 1_000_000L / VoiceFormat.SAMPLE_RATE
            }
            drain(if (ready.isEmpty()) 5_000 else 0)
            // Затор кодировщика копит задержку: старые кадры не нужны.
            while (ready.size > 3) ready.removeFirst()
            return ready.removeFirstOrNull()
        }

        private fun drain(waitUs: Long) {
            var wait = waitUs
            while (true) {
                val index = codec.dequeueOutputBuffer(info, wait)
                wait = 0
                if (index < 0) {
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    continue
                }
                val output = codec.getOutputBuffer(index)
                // Первый выход — заголовок OpusHead, не голос.
                if (output != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    output.position(info.offset)
                    ready.addLast(ByteArray(info.size).also { output.get(it) })
                }
                codec.releaseOutputBuffer(index, false)
            }
        }

        override fun close() {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private class OpusDecoder : VoiceDecoder {
        private val codec = MediaCodec.createDecoderByType(MIME).apply {
            val format = MediaFormat.createAudioFormat(MIME, VoiceFormat.SAMPLE_RATE, 1).apply {
                setByteBuffer("csd-0", head())
                // Задержка кодека и предпрокрутка в наносекундах. Собеседник шлёт живой поток, срезать начало не надо.
                setByteBuffer("csd-1", long(0))
                setByteBuffer("csd-2", long(80_000_000))
            }
            configure(format, null, null, 0)
            start()
        }
        private val info = MediaCodec.BufferInfo()
        private var timeUs = 0L

        override fun decode(packet: ByteArray): FloatArray {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) return FloatArray(0)
            val input = codec.getInputBuffer(index) ?: return FloatArray(0)
            input.clear()
            input.put(packet)
            codec.queueInputBuffer(index, 0, packet.size, timeUs, 0)
            timeUs += 20_000
            var samples = FloatArray(0)
            var wait = 10_000L
            while (true) {
                val out = codec.dequeueOutputBuffer(info, wait)
                if (out < 0) {
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    continue
                }
                val buffer = codec.getOutputBuffer(out)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val start = samples.size
                    samples = samples.copyOf(start + shorts.remaining())
                    for (i in start until samples.size) samples[i] = shorts.get() / 32768f
                    // Кадр получен — дальше не ждём, хвост заберём со следующим.
                    wait = 0
                }
                codec.releaseOutputBuffer(out, false)
            }
            return samples
        }

        override fun close() {
            runCatching { codec.stop() }
            codec.release()
        }

        /** Заголовок OpusHead (RFC 7845): моно, 48 кГц, без предпропуска. */
        private fun head(): ByteBuffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())
            put(1)
            put(1)
            putShort(0)
            putInt(VoiceFormat.SAMPLE_RATE)
            putShort(0)
            put(0)
            flip()
        }

        private fun long(value: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }
    }
}
