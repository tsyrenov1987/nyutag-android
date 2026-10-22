// Нютаг. Видео звонков: H.264 из MediaCodec телефона, как у iOS-клиента (VideoToolbox): портрет 480×640, 24 кадра в
// секунду, без B-кадров, ключевой не реже раза в 2 с. MediaCodec отдаёт и берёт единицы NAL со стартовыми кодами
// (Annex B), а на проводе они в виде AVCC (длина 4 байта BE): переводим туда и обратно.
// Кодировщик берёт кадр камеры в I420 и кладёт в свою входную картинку; декодер рисует прямо на поверхность экрана
// звонка (RemoteSurface). Поверхности ещё нет — кадры декодируются вхолостую, чтобы цепочка не рвалась.

package chat.nyutag.messenger.calls

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import chat.nyutag.core.calls.VideoCodecs
import chat.nyutag.core.calls.VideoDecoder
import chat.nyutag.core.calls.VideoEncoder
import chat.nyutag.core.calls.VideoFormat
import chat.nyutag.core.calls.VideoFrame
import chat.nyutag.core.calls.VideoPicture
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/** Поверхность для картинки собеседника: её задаёт экран звонка, пока он на экране. */
object RemoteSurface {
    @Volatile var current: Surface? = null
}

object H264Codecs : VideoCodecs {
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    override fun encoder(output: (VideoFrame) -> Unit): VideoEncoder? = runCatching { H264Encoder(output) }
        .onFailure { Log.w("calls", "кодировщик H.264 не поднялся: $it") }.getOrNull()

    override fun decoder(shown: (Int, Int) -> Unit): VideoDecoder? = H264Decoder(shown)

    /** Единицы NAL из потока со стартовыми кодами 00 00 01 / 00 00 00 01. */
    internal fun annexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += i to i + 3
                i += 3
            } else {
                i += 1
            }
        }
        return starts.mapIndexedNotNull { n, (_, body) ->
            var end = if (n + 1 < starts.size) starts[n + 1].first else data.size
            // Четырёхбайтовый код: нуль перед 00 00 01 принадлежит ему, а не концу единицы.
            while (end > body && n + 1 < starts.size && data[end - 1].toInt() == 0) end -= 1
            if (end > body) data.copyOfRange(body, end) else null
        }
    }

    private class H264Encoder(private val output: (VideoFrame) -> Unit) : VideoEncoder {
        private val codec = MediaCodec.createEncoderByType(MIME)
        @Volatile private var running = true
        private var sets: List<ByteArray> = emptyList()
        private val drainer: Thread

        init {
            val capabilities = codec.codecInfo.getCapabilitiesForType(MIME)
            fun format(profile: Boolean) = MediaFormat.createVideoFormat(MIME, VideoFormat.WIDTH, VideoFormat.HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, VideoFormat.START_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VideoFormat.FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VideoFormat.KEY_INTERVAL)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                if (capabilities.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
                if (profile) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }
            // Не всякий кодировщик берёт явный профиль: тогда без него (у железа по умолчанию тоже без B-кадров).
            runCatching { codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
                .recoverCatching { codec.reset(); codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
                .onFailure { codec.release() }
                .getOrThrow()
            codec.start()
            drainer = thread(name = "h264-encoder") { drain() }
        }

        /** Только в потоке камеры. */
        private var lastTime = -1L
        private var shift = 0L

        override fun encode(picture: VideoPicture) {
            if (!running || picture.width != VideoFormat.WIDTH || picture.height != VideoFormat.HEIGHT) return
            try {
                val index = codec.dequeueInputBuffer(0)
                // Кодировщик не успевает: кадр пропускаем, следующий придёт через 40 мс.
                if (index < 0) return
                // Новая камера (после выключения или смены) считает время с нуля, а кодировщик кадры из прошлого
                // молча выбрасывает: время только растёт.
                val time = maxOf(picture.timeMicros + shift, lastTime + 1)
                shift = time - picture.timeMicros
                lastTime = time
                val image = codec.getInputImage(index) ?: return codec.queueInputBuffer(index, 0, 0, time, 0)
                val planes = image.planes
                copyPlane(picture.y, picture.width, picture.height, planes[0])
                copyPlane(picture.u, picture.width / 2, picture.height / 2, planes[1])
                copyPlane(picture.v, picture.width / 2, picture.height / 2, planes[2])
                codec.queueInputBuffer(index, 0, picture.width * picture.height * 3 / 2, time, 0)
            } catch (e: IllegalStateException) {
                Log.w("calls", "кодировщик H.264: $e")
            }
        }

        private fun copyPlane(source: ByteArray, width: Int, height: Int, plane: android.media.Image.Plane) {
            val buffer = plane.buffer
            val row = plane.rowStride
            val step = plane.pixelStride
            if (step == 1) {
                for (y in 0 until height) {
                    buffer.position(y * row)
                    buffer.put(source, y * width, width)
                }
            } else {
                for (y in 0 until height) {
                    val base = y * row
                    val from = y * width
                    for (x in 0 until width) buffer.put(base + x * step, source[from + x])
                }
            }
        }

        private fun drain() {
            val info = MediaCodec.BufferInfo()
            while (running) {
                val index = try {
                    codec.dequeueOutputBuffer(info, 20_000)
                } catch (e: IllegalStateException) {
                    break
                }
                if (index < 0) continue
                val buffer = codec.getOutputBuffer(index)
                val data = ByteArray(info.size)
                buffer?.position(info.offset)
                buffer?.get(data)
                codec.releaseOutputBuffer(index, false)
                val units = annexB(data)
                val fresh = units.filter { nalType(it) == 7 || nalType(it) == 8 }
                if (fresh.size >= 2) {
                    sets = listOf(fresh.first { nalType(it) == 7 }, fresh.first { nalType(it) == 8 })
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) continue
                val body = units.filter { nalType(it) !in setOf(7, 8, 9) }
                if (body.isEmpty()) continue
                val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (keyframe && sets.size < 2) continue
                val out = ByteArrayOutputStream(data.size + body.size * 4)
                for (unit in body) {
                    out.write(byteArrayOf((unit.size shr 24).toByte(), (unit.size shr 16).toByte(), (unit.size shr 8).toByte(), unit.size.toByte()))
                    out.write(unit)
                }
                output(VideoFrame(keyframe, if (keyframe) sets else emptyList(), out.toByteArray()))
            }
        }

        override fun requestKeyframe() {
            runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }

        override fun setBitRate(value: Int) {
            runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, value) }) }
        }

        override fun close() {
            running = false
            drainer.join(1_000)
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun nalType(unit: ByteArray) = if (unit.isEmpty()) -1 else unit[0].toInt() and 0x1F

    /** Декодер поднимается на первом ключевом кадре и пересобирается, когда меняются SPS/PPS или поверхность. */
    private class H264Decoder(private val shown: (Int, Int) -> Unit) : VideoDecoder {
        private var codec: MediaCodec? = null
        private var drainer: Thread? = null
        @Volatile private var running = false
        @Volatile private var failed = false
        private var sets: List<ByteArray> = emptyList()
        private var surface: Surface? = null
        private var time = 0L
        @Volatile private var width = VideoFormat.WIDTH
        @Volatile private var height = VideoFormat.HEIGHT

        override fun decode(frame: VideoFrame): Boolean {
            val wanted = RemoteSurface.current
            if (codec != null && wanted !== surface) {
                if (wanted != null && surface != null && runCatching { codec?.setOutputSurface(wanted) }.isSuccess) {
                    surface = wanted
                } else {
                    // На поверхность и с неё MediaCodec не переключается: нужен новый декодер и ключевой кадр.
                    release()
                }
            }
            if (failed) release()
            if (frame.keyframe && (codec == null || !sameSets(frame.parameterSets))) {
                if (!rebuild(frame.parameterSets, wanted)) return false
            }
            val codec = codec ?: return false
            val data = annexOf(frame.units) ?: return false
            return try {
                val index = codec.dequeueInputBuffer(20_000)
                if (index < 0) return false
                val buffer = codec.getInputBuffer(index) ?: return false
                if (buffer.capacity() < data.size) {
                    codec.queueInputBuffer(index, 0, 0, time, 0)
                    return false
                }
                buffer.clear()
                buffer.put(data)
                time += 1_000_000L / VideoFormat.FRAME_RATE
                codec.queueInputBuffer(index, 0, data.size, time, 0)
                true
            } catch (e: IllegalStateException) {
                failed = true
                false
            }
        }

        private fun sameSets(other: List<ByteArray>) = other.size == sets.size && other.zip(sets).all { (a, b) -> a.contentEquals(b) }

        private fun rebuild(parameterSets: List<ByteArray>, target: Surface?): Boolean {
            release()
            val sps = parameterSets.firstOrNull { nalType(it) == 7 } ?: return false
            val pps = parameterSets.firstOrNull { nalType(it) == 8 } ?: return false
            val start = byteArrayOf(0, 0, 0, 1)
            val made = try {
                MediaCodec.createDecoderByType(MIME).apply {
                    val format = MediaFormat.createVideoFormat(MIME, VideoFormat.WIDTH, VideoFormat.HEIGHT).apply {
                        setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(start + sps))
                        setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(start + pps))
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                        if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    try {
                        configure(format, target, null, 0)
                    } catch (e: Exception) {
                        // Не всякий декодер знает «низкую задержку».
                        reset()
                        format.removeKey(MediaFormat.KEY_LOW_LATENCY)
                        configure(format, target, null, 0)
                    }
                    start()
                }
            } catch (e: Exception) {
                Log.w("calls", "декодер H.264 не поднялся: $e")
                return false
            }
            codec = made
            sets = parameterSets
            surface = target
            failed = false
            running = true
            drainer = thread(name = "h264-decoder") { drain(made, target != null) }
            return true
        }

        private fun drain(codec: MediaCodec, render: Boolean) {
            val info = MediaCodec.BufferInfo()
            while (running) {
                val index = try {
                    codec.dequeueOutputBuffer(info, 20_000)
                } catch (e: IllegalStateException) {
                    failed = true
                    break
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = codec.outputFormat
                    val w = if (format.containsKey("crop-right")) format.getInteger("crop-right") - format.getInteger("crop-left") + 1
                            else format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
                            else format.getInteger(MediaFormat.KEY_HEIGHT)
                    width = w
                    height = h
                }
                if (index < 0) continue
                runCatching { codec.releaseOutputBuffer(index, render) }
                shown(width, height)
            }
        }

        /** AVCC → Annex B: длина каждой единицы меняется на стартовый код той же длины. */
        private fun annexOf(units: ByteArray): ByteArray? {
            val out = units.copyOf()
            var at = 0
            while (at < out.size) {
                if (at + 4 > out.size) return null
                val length = (out[at].toInt() and 0xFF shl 24) or (out[at + 1].toInt() and 0xFF shl 16) or
                    (out[at + 2].toInt() and 0xFF shl 8) or (out[at + 3].toInt() and 0xFF)
                if (length <= 0 || at + 4 + length > out.size) return null
                out[at] = 0; out[at + 1] = 0; out[at + 2] = 0; out[at + 3] = 1
                at += 4 + length
            }
            return out
        }

        private fun release() {
            val old = codec ?: return
            running = false
            drainer?.join(1_000)
            drainer = null
            runCatching { old.stop() }
            old.release()
            codec = null
            sets = emptyList()
            surface = null
        }

        override fun close() = release()
    }
}
