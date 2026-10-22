// Нютаг. Звонки: события, ключи, кадры, буфер, сокет релея.

package chat.nyutag.core

import chat.nyutag.core.calls.CallCipher
import chat.nyutag.core.calls.CallEventType
import chat.nyutag.core.calls.CallID
import chat.nyutag.core.calls.CallKeyPair
import chat.nyutag.core.calls.CallMessage
import chat.nyutag.core.calls.CallSignal
import chat.nyutag.core.calls.CallEndReason
import chat.nyutag.core.calls.FrameKind
import chat.nyutag.core.calls.JitterBuffer
import chat.nyutag.core.calls.RelayInbound
import chat.nyutag.core.calls.RelayLink
import chat.nyutag.core.net.HttpRequest
import chat.nyutag.crypto.Base64
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URI
import kotlin.concurrent.thread

class CallTest {
    @Test fun signalsRoundTrip() {
        val id = CallID.make()
        val key = ByteArray(32) { 7 }
        for (signal in listOf(
            CallSignal.Ring(id, false, 45_000), CallSignal.Offer(id, key, true), CallSignal.Answer(id, key, "DEV"),
            CallSignal.End(id, CallEndReason.DECLINED), CallSignal.Ring(id, false, 45_000, group = true),
            CallSignal.Join(id, CallID.make(), key, 77, "DEV"), CallSignal.Leave(id, "DEV"),
        )) {
            // Через текст: так событие и ходит по сети.
            val wire = JsonValue.parse(signal.content.compact())
            assertEquals(signal, CallSignal.parse(signal.eventType, wire))
        }
        assertFalse(CallSignal.Ring(id, false, 45_000).wantsRoomEncryption)
        assertTrue(CallSignal.Offer(id, key, false).wantsRoomEncryption)
        // Короткий ключ, чужой тип, плохой id — не наше.
        assertNull(CallSignal.parse(CallEventType.OFFER, JsonValue.of("call_id" to id, "key" to "AAAA")))
        assertNull(CallSignal.parse("m.call.invite", JsonValue.of("call_id" to id)))
        assertNull(CallSignal.parse(CallEventType.END, JsonValue.of("call_id" to "a b")))
        // Join без метки или с плохим слотом не открывает приём.
        val b64 = Base64.encodePadded(key)
        assertNull(CallSignal.parse(CallEventType.JOIN, JsonValue.of("call_id" to id, "slot" to "x y", "tag" to 5, "key" to b64, "device_id" to "D")))
        assertNull(CallSignal.parse(CallEventType.JOIN, JsonValue.of("call_id" to id, "slot" to id, "tag" to 0, "key" to b64, "device_id" to "D")))
        assertTrue(CallSignal.Join(id, id, key, 1, "D").wantsRoomEncryption)
        // Будильник без call_id не поднимает наш экран.
        assertNull(CallSignal.parse(CallEventType.RING, JsonValue.of("lifetime" to 90000, "m.call.intent" to "audio")))
    }

    @Test fun staleRingDoesNotRing() {
        val ring = CallSignal.Ring(CallID.make(), false, 45_000)
        val now = System.currentTimeMillis()
        assertTrue(CallMessage(ring, "!r", "@a", timestamp = now - 5_000).isRinging(now))
        assertFalse(CallMessage(ring, "!r", "@a", timestamp = now - 60_000).isRinging(now))
    }

    @Test fun bothSidesDeriveMatchingKeys() {
        val caller = CallKeyPair()
        val callee = CallKeyPair()
        val id = CallID.make()
        val a = caller.secret(callee.publicKey, id, isCaller = true)
        val b = callee.secret(caller.publicKey, id, isCaller = false)
        assertTrue(a.slot == b.slot && a.slot.length == 32)
        val sendA = CallCipher(a)
        val sendB = CallCipher(b)
        val packet = requireNotNull(sendA.seal(FrameKind.AUDIO, "алло".toByteArray()))
        val opened = requireNotNull(CallCipher(b).open(packet))
        assertTrue(opened.kind == FrameKind.AUDIO && opened.sequence == 0L)
        assertArrayEquals("алло".toByteArray(), opened.payload)
        val second = sendA.seal(FrameKind.AUDIO, byteArrayOf(1))!!
        assertEquals(1L, CallCipher(b).open(second)?.sequence)
        // Своё не открывается своим ключом приёма, подмена байта ломает метку, другой звонок — другой слот.
        assertNull(CallCipher(a).open(packet))
        assertNull(CallCipher(b).open(packet.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }))
        assertNull(CallCipher(b).open(packet.copyOf().also { it[4] = (it[4].toInt() xor 1).toByte() }))
        val reply = sendB.seal(FrameKind.AUDIO, "слышу".toByteArray())!!
        assertArrayEquals("слышу".toByteArray(), CallCipher(a).open(reply)?.payload)
        assertNotEquals(a.slot, caller.secret(callee.publicKey, CallID.make(), isCaller = true).slot)
    }

    /** Кадр iOS-клиента: тот же ключ и номер дают тот же шифртекст (формат на проводе общий). */
    @Test fun videoNonceDiffersFromAudio() {
        val caller = CallKeyPair()
        val callee = CallKeyPair()
        val id = CallID.make()
        val a = caller.secret(callee.publicKey, id, isCaller = true)
        val audio = CallCipher(a).seal(FrameKind.AUDIO, ByteArray(8))!!
        val video = CallCipher(a).seal(FrameKind.VIDEO, ByteArray(8))!!
        assertFalse(audio.copyOfRange(5, audio.size).contentEquals(video.copyOfRange(5, video.size)))
        assertEquals(FrameKind.VIDEO, CallCipher(callee.secret(caller.publicKey, id, false)).open(video)?.kind)
    }

    @Test fun jitterReordersAndFillsGaps() {
        val buffer = JitterBuffer(target = 3, limit = 8)
        fun pulled(): Any? = when (val p = buffer.pull()) {
            is JitterBuffer.Pull.Frame -> p.payload[0].toInt()
            JitterBuffer.Pull.Missing -> "missing"
            JitterBuffer.Pull.Waiting -> "waiting"
        }
        assertEquals("waiting", pulled())
        buffer.insert(1, byteArrayOf(1))
        buffer.insert(0, byteArrayOf(0))
        assertEquals("waiting", pulled())
        buffer.insert(3, byteArrayOf(3))
        assertEquals(0, pulled())
        assertEquals(1, pulled())
        assertEquals("missing", pulled())
        // Опоздавший второй уже не нужен.
        buffer.insert(2, byteArrayOf(2))
        assertEquals(3, pulled())
        assertEquals("waiting", pulled())
        assertTrue(buffer.lost == 1 && buffer.dropped == 1)
        // Затор: держим не больше limit, догоняем до свежих.
        for (seq in 10L until 30L) buffer.insert(seq, byteArrayOf(seq.toByte()))
        assertTrue(buffer.depth <= 8)
        // Старые кадры затора выброшены: играем с хвоста, а не с десятого.
        assertTrue((pulled() as Int) >= 20)
    }

    @Test fun acceptKeyMatchesRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", RelayLink.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    /** Свой сокет против маленького сервера: рукопожатие с заголовками, кадры туда и обратно, `{"peers":N}`, ping. */
    @Test fun relayLinkTalksWebSocket() = runBlocking {
        val server = ServerSocket(0)
        var seenHeaders = ""
        val got = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        thread(isDaemon = true) {
            val socket = server.accept()
            val input = DataInputStream(socket.getInputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            seenHeaders = head.toString()
            val key = head.lines().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
            val out = socket.getOutputStream()
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${RelayLink.acceptKey(key)}\r\n\r\n").toByteArray())
            frame(out, 1, "{\"peers\":2}".toByteArray())
            frame(out, 2, ByteArray(300) { it.toByte() })
            out.flush()
            // Принимаем один бинарный кадр от клиента (маскированный) и отвечаем ping'ом.
            while (true) {
                val b0 = input.read()
                val b1 = input.read()
                if (b0 < 0) break
                assertTrue(b1 and 0x80 != 0, "кадры клиента маскируются")
                var len = b1 and 0x7F
                if (len == 126) len = (input.read() shl 8) or input.read()
                val mask = ByteArray(4).also { input.readFully(it) }
                val body = ByteArray(len).also { input.readFully(it) }
                for (i in body.indices) body[i] = (body[i].toInt() xor mask[i and 3].toInt()).toByte()
                when (b0 and 0x0F) {
                    2 -> { got.put(body); frame(out, 9, byteArrayOf(5)); out.flush() }
                    10 -> got.put(byteArrayOf(-1) + body)
                    8 -> { socket.close(); break }
                }
            }
        }
        val request = HttpRequest("GET", URI("http://127.0.0.1:${server.localPort}${RelayLink.PATH}?slot=abc"),
                                  mapOf("Authorization" to "Bearer t", "X-Nyutag-Door" to "k"), null, 5_000)
        val link = requireNotNull(RelayLink.open(request))
        withTimeout(5_000) {
            assertEquals(RelayInbound.Peers(2), link.inbound.receive())
            val packet = link.inbound.receive() as RelayInbound.Packet
            assertEquals(300, packet.data.size)
            assertEquals(299.toByte(), packet.data[299])
        }
        assertTrue(seenHeaders.startsWith("GET /_nyutag/relay/v1?slot=abc HTTP/1.1"))
        assertTrue(seenHeaders.contains("Authorization: Bearer t") && seenHeaders.contains("X-Nyutag-Door: k"))
        link.send(ByteArray(200) { 9 })
        val echoed = got.poll(5, java.util.concurrent.TimeUnit.SECONDS)
        assertArrayEquals(ByteArray(200) { 9 }, echoed)
        // На ping сервера клиент отвечает pong с тем же телом.
        assertArrayEquals(byteArrayOf(-1, 5), got.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        link.close()
        withTimeout(5_000) { assertTrue(link.inbound.receiveCatching().isClosed) }
        server.close()
    }

    private fun frame(out: OutputStream, opcode: Int, payload: ByteArray) {
        out.write(0x80 or opcode)
        if (payload.size < 126) out.write(payload.size) else { out.write(126); out.write(payload.size shr 8); out.write(payload.size and 0xFF) }
        out.write(payload)
    }
}
