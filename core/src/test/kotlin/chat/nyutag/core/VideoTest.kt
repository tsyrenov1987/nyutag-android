// Нютаг. Видео звонка: формат кадра, куски, потеря куска и просьба о ключевом, забитый канал, выключенная камера.
// Кодек — подделка: H.264 на Маке без MediaCodec не проверить, его проверяет телефон.

package chat.nyutag.core

import chat.nyutag.core.calls.CallKeyPair
import chat.nyutag.core.calls.CallSecret
import chat.nyutag.core.calls.VideoCodecs
import chat.nyutag.core.calls.VideoDecoder
import chat.nyutag.core.calls.VideoEncoder
import chat.nyutag.core.calls.VideoFormat
import chat.nyutag.core.calls.VideoFrame
import chat.nyutag.core.calls.VideoPicture
import chat.nyutag.core.calls.VideoPipe
import chat.nyutag.core.calls.VideoSink
import chat.nyutag.core.calls.VideoSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class VideoTest {
    @Test fun frameWireRoundTrip() {
        val key = VideoFrame(true, listOf(byteArrayOf(0x67, 1, 2), byteArrayOf(0x68, 3)), byteArrayOf(0, 0, 0, 1, 0x65))
        assertEquals(key, VideoFrame.of(key.wire))
        val delta = VideoFrame(false, emptyList(), byteArrayOf(0, 0, 0, 1, 0x41))
        assertEquals(delta, VideoFrame.of(delta.wire))
        assertNull(VideoFrame.of(byteArrayOf(1, 2, 0, 1)))
        assertNull(VideoFrame.of(ByteArray(0)))
    }

    /** Две стороны через «релей», который теряет один кусок: получатель просит ключевой кадр и снова видит. */
    @Test fun lostChunkRecoversWithKeyframe() {
        val (callerSecret, calleeSecret) = pair("video-loss-test")
        val drop = AtomicInteger(-1)
        val sent = AtomicInteger(0)
        val seen = AtomicInteger(0)
        var receiver: VideoPipe? = null
        var sender: VideoPipe? = null
        sender = VideoPipe(callerSecret, FakeCodecs, send = { packet ->
            val n = sent.incrementAndGet()
            if (n != drop.get()) receiver?.receive(packet)
        })
        receiver = VideoPipe(calleeSecret, FakeCodecs, send = { packet -> sender.receive(packet) })
        receiver.sink = VideoSink(remote = { _, _ -> seen.incrementAndGet() })
        sender.cameraOn = true
        val camera = PatternCamera()
        camera.start(sender)
        try {
            until { seen.get() >= 10 }
            // Теряем следующий кусок.
            drop.set(sent.get() + 1)
            until { receiver.stats.brokenFrames >= 1 }
            val before = seen.get()
            until { seen.get() >= before + 10 }
        } finally {
            camera.stop()
            println("video test: отправитель ${sender.stats}, получатель ${receiver.stats}, показано ${seen.get()}")
        }
        assertTrue(receiver.stats.keyRequestsSent >= 1)
        assertTrue(sender.stats.keyRequestsReceived >= 1)
        assertTrue(sender.stats.sentKeyframes >= 2)
        assertEquals(VideoFormat.WIDTH, receiver.stats.remoteWidth)
        sender.close()
        receiver.close()
    }

    /** Забитый канал: кадры камеры пропускаются и битрейт падает, голосу остаётся место. */
    @Test fun crowdedChannelSkipsFrames() {
        val (secret, _) = pair("video-crowd-test")
        val pipe = VideoPipe(secret, FakeCodecs, send = {}, backlog = { 1 shl 20 })
        pipe.cameraOn = true
        val camera = PatternCamera()
        camera.start(pipe)
        try {
            until(6_000) { pipe.stats.bitRate < VideoFormat.START_BIT_RATE && pipe.stats.skipped > 10 }
        } finally {
            camera.stop()
        }
        assertEquals(0, pipe.stats.sentFrames)
        pipe.close()
    }

    @Test fun cameraOffIsAnnounced() {
        val (callerSecret, calleeSecret) = pair("video-off-test")
        val off = AtomicBoolean(false)
        val receiver = VideoPipe(calleeSecret, FakeCodecs, send = {})
        val sender = VideoPipe(callerSecret, FakeCodecs, send = { receiver.receive(it) })
        receiver.sink = VideoSink(remoteCameraOff = { off.set(true) })
        sender.cameraOn = true
        sender.cameraOn = false
        assertTrue(off.get())
    }

    private fun pair(callID: String): Pair<CallSecret, CallSecret> {
        val a = CallKeyPair()
        val b = CallKeyPair()
        return a.secret(b.publicKey, callID, isCaller = true) to b.secret(a.publicKey, callID, isCaller = false)
    }

    private fun until(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("не дождались за $timeoutMillis мс")
    }

    /** Кодек-подделка: кадр 10 КБ (два куска), ключевой — первый и по просьбе; декодер без ключевого не берёт. */
    private object FakeCodecs : VideoCodecs {
        override fun encoder(output: (VideoFrame) -> Unit) = object : VideoEncoder {
            @Volatile private var forceKey = true
            override fun encode(picture: VideoPicture) {
                val key = forceKey
                forceKey = false
                val units = ByteArray(10_000) { (picture.timeMicros + it).toByte() }
                output(VideoFrame(key, if (key) listOf(byteArrayOf(0x67, 1), byteArrayOf(0x68, 2)) else emptyList(), units))
            }
            override fun requestKeyframe() { forceKey = true }
            override fun setBitRate(value: Int) {}
            override fun close() {}
        }

        override fun decoder(shown: (Int, Int) -> Unit) = object : VideoDecoder {
            private var started = false
            override fun decode(frame: VideoFrame): Boolean {
                if (frame.keyframe) started = true
                if (!started) return false
                shown(VideoFormat.WIDTH, VideoFormat.HEIGHT)
                return true
            }
            override fun close() {}
        }
    }

    /** Вместо камеры — пустые кадры 480×640, 24 в секунду. */
    private class PatternCamera : VideoSource {
        @Volatile private var running = false
        private var worker: Thread? = null

        override fun start(pipe: VideoPipe) {
            running = true
            worker = thread(name = "pattern-camera") {
                val y = ByteArray(VideoFormat.WIDTH * VideoFormat.HEIGHT)
                val uv = ByteArray(y.size / 4) { 128.toByte() }
                var i = 0L
                while (running) {
                    pipe.capture(VideoPicture(VideoFormat.WIDTH, VideoFormat.HEIGHT, y, uv, uv, i * 1_000_000 / VideoFormat.FRAME_RATE))
                    i += 1
                    Thread.sleep(1_000L / VideoFormat.FRAME_RATE)
                }
            }
        }

        override fun stop() {
            running = false
            worker?.join(1_000)
        }
    }
}
