// Собеседник для проверки звонков на телефоне: второй аккаунт на Маке с тем же движком звонков. Голос не
// декодирует, а возвращает телефону его же кадры Opus (эхо): телефон слышит себя, счётчики видны с обеих сторон.
// Видео так же: кадры H.264 телефона уходят обратно, и телефон видит свою камеру на месте собеседника.
// Запускается только явно: NYUTAG_PEER_USER/NYUTAG_PEER_PASSWORD (не тот аккаунт, что на телефоне) и стенд двери.
//   NYUTAG_PEER_MODE=rooms — личные комнаты с NYUTAG_PEER_WITH; answer — ждать звонка и принять; dial — позвонить.
//   NYUTAG_PEER_VIDEO=1 — в режиме dial позвонить с видео.

package chat.nyutag.core

import chat.nyutag.core.auth.login
import chat.nyutag.core.auth.logout
import chat.nyutag.core.calls.CallCenter
import chat.nyutag.core.calls.CallEnvironment
import chat.nyutag.core.calls.CallMessage
import chat.nyutag.core.calls.VideoCodecs
import chat.nyutag.core.calls.VideoDecoder
import chat.nyutag.core.calls.VideoEncoder
import chat.nyutag.core.calls.VideoFormat
import chat.nyutag.core.calls.VideoFrame
import chat.nyutag.core.calls.VideoPicture
import chat.nyutag.core.calls.VideoPipe
import chat.nyutag.core.calls.VideoSource
import chat.nyutag.core.calls.VoiceCodecs
import chat.nyutag.core.calls.VoiceDecoder
import chat.nyutag.core.calls.VoiceEncoder
import chat.nyutag.core.calls.VoiceFormat
import chat.nyutag.core.calls.VoiceIO
import chat.nyutag.core.calls.VoicePipe
import chat.nyutag.core.calls.RelayLink
import chat.nyutag.core.crypto.CryptoMachine
import chat.nyutag.core.crypto.sendEncrypted
import chat.nyutag.core.crypto.sendToDevice
import chat.nyutag.core.net.Door
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.rooms.send
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.EventType
import chat.nyutag.core.sync.SyncEngine
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.concurrent.thread

class LiveCallPeerTest {
    private val env = System.getenv()
    private val door = env["NYUTAG_TEST_DOOR"]?.let { Door.of(URI(it), "test") }
    private val doorKey = env["NYUTAG_TEST_DOOR_KEY"]
    private val user = env["NYUTAG_PEER_USER"]
    private val password = env["NYUTAG_PEER_PASSWORD"]
    private val with = env["NYUTAG_PEER_WITH"] ?: "@test18:nyutag.chat"
    private val mode = env["NYUTAG_PEER_MODE"] ?: "rooms"
    private val seconds = env["NYUTAG_PEER_SECONDS"]?.toLongOrNull() ?: 90

    @Test fun peer() = runBlocking {
        assumeTrue(door != null && doorKey != null && user != null && password != null, "нет NYUTAG_PEER_* и стенда двери")
        // ⛔ test17 — аккаунт владельца: им не входим и ему не звоним.
        assumeTrue("test17" !in requireNotNull(user) && "test17" !in with, "test17 не для тестов")
        val client = MatrixClient(URI("https://core.nyutag.chat"), userAgent = "Nyutag-Android-test", doorKey = doorKey,
                                  door = door, doorWaitMillis = 0)
        val session = client.login(user, requireNotNull(password), deviceDisplayName = "Нютаг Android собеседник звонка (Мак)")
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val store = Store(JdbcSql.inMemory())
            val engine = SyncEngine(client, store, session, scope)
            engine.syncOnce()
            val machine = CryptoMachine.create(client, store, session)
            engine.addObserver(machine)
            machine.publishKeys(null)
            // Личная комната: ровно мы и собеседник.
            val direct = store.rooms().filter { room ->
                val members = store.stateAll(room.id, EventType.MEMBER)
                    .filter { (it.content["membership"]?.string ?: "") in setOf("join", "invite") }.map { it.stateKey }.toSet()
                members == setOf(session.userID, with)
            }
            println("peer: личных комнат с $with: ${direct.size}" +
                    direct.joinToString("") { "\n  ${it.id} шифр=${it.isEncrypted}" })
            if (mode == "rooms") return@runBlocking
            if (mode == "say") {
                // Проверка пушей: текст в комнату по названию — только в тестовую «Проверку групповых звонков».
                val name = env["NYUTAG_PEER_ROOM"] ?: "Проверка групповых звонков"
                assumeTrue(name == "Проверка групповых звонков", "писать можно только в тестовую комнату")
                val target = store.rooms(RoomRecord.Membership.JOIN).firstOrNull { it.name == name } ?: error("нет комнаты «$name»")
                val content = JsonValue.of("msgtype" to "m.text", "body" to (env["NYUTAG_PEER_TEXT"] ?: "Проверка пуша"))
                val id = if (target.isEncrypted) machine.sendEncrypted(target.id, EventType.MESSAGE, content)
                         else client.send(target.id, EventType.MESSAGE, content)
                println("peer: сообщение ушло $id в ${target.id} шифр=${target.isEncrypted}")
                return@runBlocking
            }
            val room = direct.firstOrNull() ?: error("нет личной комнаты с $with")

            val video = EchoVideo()
            val callEnv = CallEnvironment(
                me = session.userID, deviceID = session.deviceID,
                post = { signal, roomID ->
                    if (signal.wantsRoomEncryption && store.room(roomID)?.isEncrypted == true) {
                        machine.sendEncrypted(roomID, signal.eventType, signal.content)
                    } else {
                        client.send(roomID, signal.eventType, signal.content)
                    }
                    println("peer: ушёл ${signal.eventType}")
                },
                openTransport = { slot -> RelayLink.open(client, slot) },
                notify = { signal, user ->
                    client.sendToDevice(signal.eventType, mapOf(user to mapOf("*" to signal.content)))
                    println("peer: ушёл ${signal.eventType} устройствам")
                },
                makeVoice = { EchoVoice() },
                codecs = PassCodecs,
                videoCodecs = video,
                makeCamera = { video },
            )
            val center = CallCenter(callEnv)
            val fresh = { System.currentTimeMillis() - 120_000 }
            scope.launch {
                engine.updates.collect { update ->
                    for (event in update.response.toDevice) {
                        val message = CallMessage.ofToDevice(event) ?: continue
                        println("peer: пришёл ${event.type} с устройства")
                        center.absorb(message)
                    }
                    for ((roomID, joined) in update.response.joined) {
                        for (event in joined.timeline.events) {
                            if ((event.originServerTS ?: 0) < fresh()) continue
                            val clear = if (event.type == EventType.ENCRYPTED) {
                                runCatching { machine.decrypt(event, roomID).event }
                                    .onFailure { println("peer: не раскрыто ${event.eventID}: $it") }.getOrNull() ?: continue
                            } else event
                            val message = CallMessage.of(clear, roomID) ?: continue
                            println("peer: пришёл ${clear.type}")
                            center.absorb(message)
                        }
                    }
                }
            }
            engine.start()
            val call = if (mode == "dial") {
                requireNotNull(center.dial(room.id, with, video = env["NYUTAG_PEER_VIDEO"] != null))
            } else {
                println("peer: жду звонка ${seconds} с")
                withTimeoutOrNull(seconds * 1_000) { center.incoming.receive() }?.also {
                    // NYUTAG_PEER_ANSWER_AFTER — сколько секунд звенеть до ответа (у звонящего идут гудки).
                    delay((env["NYUTAG_PEER_ANSWER_AFTER"]?.toLongOrNull() ?: 2) * 1_000)
                    it.accept()
                } ?: return@runBlocking println("peer: звонка не было")
            }
            val until = System.currentTimeMillis() + seconds * 1_000
            var last: Any? = null
            while (System.currentTimeMillis() < until && !call.phase.isEnded) {
                if (call.phase != last) { last = call.phase; println("peer: фаза ${call.phase}") }
                call.stats?.let { println("peer: ушло ${it.sent}, пришло ${it.received}, сыграно ${it.played}, потеряно ${it.lost}") }
                call.videoStats?.let { println("peer: видео $it") }
                delay(2_000)
            }
            println("peer: итог ${call.phase}")
            if (!call.phase.isEnded) call.hangUp()
            delay(1_000)
            println("peer: после отбоя ${call.phase}")
            engine.stop()
        } finally {
            scope.cancel()
            client.logout()
        }
    }

    /** Видео-эхо: «декодер» отдаёт пришедший кадр «кодировщику», просьбу о ключевом кадре — телефону. Кодировщик
     *  заводится пустым кадром «камеры», когда пришёл первый кадр: к тому времени камера уже включена. */
    private class EchoVideo : VideoCodecs, VideoSource {
        @Volatile private var output: ((VideoFrame) -> Unit)? = null
        @Volatile private var pipe: VideoPipe? = null
        private val blank = ByteArray(VideoFormat.WIDTH * VideoFormat.HEIGHT).let {
            VideoPicture(VideoFormat.WIDTH, VideoFormat.HEIGHT, it, it, it, 0)
        }

        override fun encoder(output: (VideoFrame) -> Unit) = object : VideoEncoder {
            init { this@EchoVideo.output = output }
            override fun encode(picture: VideoPicture) {}
            override fun requestKeyframe() { pipe?.askKeyframe() }
            override fun setBitRate(value: Int) {}
            override fun close() { this@EchoVideo.output = null }
        }

        override fun decoder(shown: (Int, Int) -> Unit) = object : VideoDecoder {
            override fun decode(frame: VideoFrame): Boolean {
                if (output == null) pipe?.capture(blank)
                output?.invoke(frame)
                shown(VideoFormat.WIDTH, VideoFormat.HEIGHT)
                return true
            }
            override fun close() {}
        }

        override fun start(pipe: VideoPipe) {
            this.pipe = pipe
        }

        override fun stop() { pipe = null }
    }

    /** Кадр Opus как есть: длина в первом отсчёте, байты в следующих. Пустой кадр — тишина Opus (CELT 20 мс). */
    private object PassCodecs : VoiceCodecs {
        private val silence = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())
        override fun encoder() = object : VoiceEncoder {
            override fun encode(samples: FloatArray): ByteArray {
                val size = samples[0].toInt()
                if (size <= 0 || size >= samples.size) return silence
                return ByteArray(size) { samples[it + 1].toInt().toByte() }
            }
        }
        override fun decoder() = object : VoiceDecoder {
            override fun decode(packet: ByteArray): FloatArray {
                val out = FloatArray(VoiceFormat.FRAME_SAMPLES)
                if (packet.size >= out.size) return out
                out[0] = packet.size.toFloat()
                for (i in packet.indices) out[i + 1] = packet[i].toFloat()
                return out
            }
        }
    }

    /** Эхо: каждые 20 мс берём кадр собеседника и отправляем его обратно. */
    private class EchoVoice : VoiceIO {
        @Volatile private var running = false
        private var worker: Thread? = null

        override fun start(pipe: VoicePipe) {
            running = true
            worker = thread(name = "echo") {
                val frame = FloatArray(VoiceFormat.FRAME_SAMPLES)
                var next = System.nanoTime()
                while (running) {
                    pipe.render(frame, frame.size)
                    pipe.capture(frame, frame.size)
                    next += 20_000_000
                    val wait = (next - System.nanoTime()) / 1_000_000
                    if (wait > 0) Thread.sleep(wait)
                }
            }
        }

        override fun stop() {
            running = false
            worker?.join(1_000)
        }
    }
}
