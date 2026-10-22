// Нютаг. Ход звонка: несколько диспетчеров в одной комнате, релей и звук подменены, кадры настоящие (кодек + шифр).

package chat.nyutag.core

import chat.nyutag.core.calls.CallCenter
import chat.nyutag.core.calls.CallEndReason
import chat.nyutag.core.calls.CallEnvironment
import chat.nyutag.core.calls.CallID
import chat.nyutag.core.calls.CallMessage
import chat.nyutag.core.calls.CallPhase
import chat.nyutag.core.calls.CallSecret
import chat.nyutag.core.calls.CallSession
import chat.nyutag.core.calls.CallSignal
import chat.nyutag.core.calls.CallTransport
import chat.nyutag.core.calls.RelayInbound
import chat.nyutag.core.calls.VoiceCodecs
import chat.nyutag.core.calls.VoiceDecoder
import chat.nyutag.core.calls.VoiceEncoder
import chat.nyutag.core.calls.VoiceFormat
import chat.nyutag.core.calls.VoiceIO
import chat.nyutag.core.calls.VoicePipe
import chat.nyutag.core.calls.VoiceStats
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

class CallFlowTest {
    companion object {
        const val ROOM = "!call:nyutag.chat"
        const val ALICE = "@alice:nyutag.chat"
        const val BOB = "@bob:nyutag.chat"
        const val CAROL = "@carol:nyutag.chat"
    }

    @Test fun callIsAnsweredAndVoiceFlows() = runBlocking {
        val line = Line()
        val alice = line.center(ALICE, "A1")
        val bob = line.center(BOB, "B1")
        val call = requireNotNull(alice.dial(ROOM, BOB))
        val incoming = first(bob.incoming)
        assertTrue(incoming.callID == call.callID && incoming.peer == ALICE && !incoming.isCaller)
        assertEquals(CallPhase.Ringing, incoming.phase)
        incoming.accept()
        waitLive(call)
        waitLive(incoming)
        // Звук ходит в обе стороны и доходит до динамика не тишиной.
        until { (incoming.stats?.outputLevel ?: 0f) > 0.05f && (call.stats?.outputLevel ?: 0f) > 0.05f }
        // Отдельный ключ на звонок: релей видит только шифр, свой слот у звонка.
        assertEquals(1, line.relay.slotsSeen)
        assertFalse(line.relay.plaintextLeaked)
        call.hangUp()
        assertEquals(CallPhase.Ended(CallEndReason.HANG_UP), call.phase)
        until { incoming.phase == CallPhase.Ended(CallEndReason.HANG_UP) }
        until { line.relay.openLinks == 0 }
    }

    @Test fun declinedAndUnanswered() = runBlocking {
        val line = Line()
        val alice = line.center(ALICE, "A1")
        val bob = line.center(BOB, "B1")
        val call = alice.dial(ROOM, BOB)!!
        first(bob.incoming).hangUp()
        until { call.phase == CallPhase.Ended(CallEndReason.DECLINED) }

        line.ringTimeout = 300
        val quick = line.center(ALICE, "A2")
        val unanswered = quick.dial(ROOM, BOB)!!
        until { unanswered.phase == CallPhase.Ended(CallEndReason.NO_ANSWER) }
        assertTrue(line.posted.any { it == CallSignal.End(unanswered.callID, CallEndReason.NO_ANSWER) })
    }

    @Test fun secondCallGetsBusy() = runBlocking {
        val line = Line()
        val alice = line.center(ALICE, "A1")
        val bob = line.center(BOB, "B1")
        // Кэрол звонит Бобу из другой комнаты: первого будильника она не видела.
        val other = "!other:nyutag.chat"
        val carol = line.center(CAROL, "C1", rooms = setOf(other))
        line.center(BOB, "B1", rooms = setOf(other), sharing = bob)
        val firstCall = alice.dial(ROOM, BOB)!!
        first(bob.incoming).accept()
        waitLive(firstCall)
        val second = carol.dial(other, BOB)!!
        until { second.phase == CallPhase.Ended(CallEndReason.BUSY) }
        // У Боба первый разговор не тронут, и второй экран не поднимался.
        assertFalse(firstCall.phase.isEnded)
        assertNull(bob.incoming.tryReceive().getOrNull())
        firstCall.hangUp()
    }

    @Test fun answeredOnAnotherDeviceStopsRinging() = runBlocking {
        val line = Line()
        val alice = line.center(ALICE, "A1")
        val phone = line.center(BOB, "B1")
        val pad = line.center(BOB, "B2")
        val call = alice.dial(ROOM, BOB)!!
        val onPhone = first(phone.incoming)
        val onPad = first(pad.incoming)
        onPhone.accept()
        until { onPad.phase == CallPhase.Ended(CallEndReason.ELSEWHERE) }
        waitLive(call)
        call.hangUp()
    }

    @Test fun oldRingFromFirstSyncIsIgnored() = runBlocking {
        val line = Line()
        val bob = line.center(BOB, "B1")
        val ring = CallSignal.Ring(CallID.make(), false, 45_000)
        bob.absorb(CallMessage(ring, ROOM, ALICE, timestamp = System.currentTimeMillis() - 120_000))
        assertNull(bob.active())
    }

    @Test fun shortRelayDropIsSurvived() = runBlocking {
        val line = Line()
        val alice = line.center(ALICE, "A1")
        val bob = line.center(BOB, "B1")
        val call = alice.dial(ROOM, BOB)!!
        val incoming = first(bob.incoming)
        incoming.accept()
        waitLive(call)
        val before = incoming.stats?.received ?: 0
        line.relay.dropAll()
        until { (incoming.stats?.received ?: 0) > before + 20 }
        assertFalse(call.phase.isEnded)
        call.hangUp()
    }

    @Test fun silentPeerEndsCallAsLost() = runBlocking {
        val line = Line()
        line.silenceLimit = 300
        line.lossGrace = 600
        val alice = line.center(ALICE, "A1")
        val bob = line.center(BOB, "B1")
        val call = alice.dial(ROOM, BOB)!!
        val incoming = first(bob.incoming)
        incoming.accept()
        waitLive(call)
        // Каналы повисли: релей держит обе стороны, но кадры больше не идут.
        line.relay.stall()
        until { call.phase == CallPhase.Ended(CallEndReason.LOST) }
    }

    // MARK: Групповые

    @Test fun groupCallJoinsEveryoneAndMixesVoices() = runBlocking {
        val line = Line()
        val alice = line.center(ALICE, "A1")
        val bob = line.center(BOB, "B1")
        val carol = line.center(CAROL, "C1")
        val call = alice.dialGroup(ROOM)!!
        assertTrue(call.group && call.isCaller)
        val atBob = first(bob.incoming)
        val atCarol = first(carol.incoming)
        assertTrue(atBob.group && atBob.peer == ALICE && atCarol.group)
        assertEquals(CallPhase.Ringing, atBob.phase)
        atBob.accept()
        waitLive(call)
        waitLive(atBob)
        atCarol.accept()
        waitLive(atCarol)
        // Каждый видит двух других и слышит обоих: принимает вдвое больше кадров, чем шлёт.
        for (session in listOf(call, atBob, atCarol)) until { session.others().size == 2 }
        until {
            val stats = atCarol.stats
            (stats?.outputLevel ?: 0f) > 0.05f && (stats?.received ?: 0) > 2 * 20
        }
        val before = atCarol.stats ?: VoiceStats()
        delay(600)
        val after = atCarol.stats ?: VoiceStats()
        val ratio = (after.received - before.received).toDouble() / maxOf(after.sent - before.sent, 1)
        assertTrue(ratio > 1.5, "ratio $ratio")
        assertEquals(1, line.relay.slotsSeen)
        assertFalse(line.relay.plaintextLeaked)
        // Кэрол ушла: остальные разговаривают дальше.
        atCarol.hangUp()
        until { call.others().size == 1 && atBob.others().size == 1 }
        assertFalse(call.phase.isEnded)
        assertTrue(line.posted.any { it == CallSignal.Leave(call.callID, "C1") })
        // Ушёл и Боб: Алиса одна — звонок кончается.
        atBob.hangUp()
        until { call.phase == CallPhase.Ended(CallEndReason.HANG_UP) }
        until { line.relay.openLinks == 0 }
    }

    @Test fun groupDeclineIsSilentAndUnansweredStopsRinging() = runBlocking {
        val line = Line()
        line.ringTimeout = 1_000
        val alice = line.center(ALICE, "A1")
        val bob = line.center(BOB, "B1")
        val carol = line.center(CAROL, "C1")
        val call = alice.dialGroup(ROOM)!!
        val atBob = first(bob.incoming)
        val atCarol = first(carol.incoming)
        atBob.hangUp()
        assertEquals(CallPhase.Ended(CallEndReason.DECLINED), atBob.phase)
        // Отказ одного не гасит звонок у остальных.
        assertEquals(CallPhase.Calling, call.phase)
        assertEquals(CallPhase.Ringing, atCarol.phase)
        assertFalse(line.posted.any { it is CallSignal.End })
        until { call.phase == CallPhase.Ended(CallEndReason.NO_ANSWER) }
        until { atCarol.phase == CallPhase.Ended(CallEndReason.NO_ANSWER) }
    }

    @Test fun groupRingDoesNotDisturbBusy() = runBlocking {
        val line = Line()
        val other = "!other:nyutag.chat"
        val alice = line.center(ALICE, "A1")
        val bob = line.center(BOB, "B1")
        val carol = line.center(CAROL, "C1", rooms = setOf(other))
        line.center(BOB, "B1", rooms = setOf(other), sharing = bob)
        val firstCall = alice.dial(ROOM, BOB)!!
        first(bob.incoming).accept()
        waitLive(firstCall)
        val group = carol.dialGroup(other)!!
        delay(200)
        assertEquals(CallPhase.Calling, group.phase)
        assertFalse(line.posted.any { it is CallSignal.End && it.callID == group.callID })
        firstCall.hangUp()
        group.hangUp()
        assertTrue(line.posted.any { it == CallSignal.End(group.callID, CallEndReason.HANG_UP) })
    }

    @Test fun groupJoinedOnAnotherDeviceStopsRinging() = runBlocking {
        val line = Line()
        val alice = line.center(ALICE, "A1")
        val phone = line.center(BOB, "B1")
        val pad = line.center(BOB, "B2")
        val call = alice.dialGroup(ROOM)!!
        val onPhone = first(phone.incoming)
        val onPad = first(pad.incoming)
        onPhone.accept()
        until { onPad.phase == CallPhase.Ended(CallEndReason.ELSEWHERE) }
        waitLive(call)
        call.hangUp()
        until { onPhone.phase == CallPhase.Ended(CallEndReason.HANG_UP) }
    }

    /** join расшифровался раньше, чем пришёл открытый будильник. */
    @Test fun groupJoinBeforeRingIsKept() = runBlocking {
        val line = Line()
        val bob = line.center(BOB, "B1")
        val id = CallID.make()
        val slot = CallID.make()
        val now = System.currentTimeMillis()
        bob.absorb(CallMessage(CallSignal.Join(id, slot, CallSecret.makeGroupKey(), 9, "A1"), ROOM, ALICE, timestamp = now))
        bob.absorb(CallMessage(CallSignal.Ring(id, false, 45_000, group = true), ROOM, ALICE, timestamp = now))
        val session = requireNotNull(bob.active())
        assertEquals(listOf(ALICE), session.others().map { it.user })
        session.hangUp()
    }

    // MARK: Помощники

    private suspend fun first(channel: ReceiveChannel<CallSession>): CallSession = withTimeout(5_000) { channel.receive() }

    private suspend fun waitLive(session: CallSession) = until { session.phase.isLive }

    private suspend fun until(timeoutMillis: Long = 5_000, condition: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            delay(20)
        }
        fail("не дождались")
    }
}

/** Комната и релей в памяти. Сигналы доходят до всех диспетчеров, включая отправителя (эхо). */
class Line {
    val relay = FakeRelay()
    private val centers = ArrayList<Pair<CallCenter, Set<String>>>()
    private val signals = ArrayList<CallSignal>()
    var ringTimeout = 45_000L
    var silenceLimit = 5_000L
    var lossGrace = 20_000L

    val posted: List<CallSignal> get() = synchronized(this) { signals.toList() }

    /** `sharing` — тот же диспетчер, но ещё и участник других комнат. */
    fun center(user: String, device: String, rooms: Set<String> = setOf(CallFlowTest.ROOM), sharing: CallCenter? = null): CallCenter {
        if (sharing != null) {
            synchronized(this) { centers += sharing to rooms }
            return sharing
        }
        val env = CallEnvironment(
            me = user, deviceID = device,
            post = { signal, room -> deliver(signal, room, user) },
            openTransport = { slot -> relay.join(slot) },
            makeVoice = { ToneVoice() },
            codecs = PcmCodecs,
            ringTimeout = ringTimeout, lossGrace = lossGrace, silenceLimit = silenceLimit, retryDelay = 50,
        )
        val center = CallCenter(env)
        synchronized(this) { centers += center to rooms }
        return center
    }

    private suspend fun deliver(signal: CallSignal, room: String, sender: String) {
        val everyone = synchronized(this) {
            signals += signal
            centers.filter { room in it.second }.map { it.first }
        }
        val message = CallMessage(signal, room, sender, "$" + CallID.make(), System.currentTimeMillis())
        for (center in everyone) center.absorb(message)
    }
}

class FakeRelay {
    private val slots = HashMap<String, MutableList<FakeLink>>()
    private val seen = HashSet<String>()
    @Volatile var plaintextLeaked = false
        private set
    @Volatile private var stalled = false

    val slotsSeen: Int get() = synchronized(this) { seen.size }
    val openLinks: Int get() = synchronized(this) { slots.values.sumOf { it.size } }

    fun join(slot: String): FakeLink {
        val link = FakeLink(this, slot)
        val members = synchronized(this) {
            seen += slot
            slots.getOrPut(slot) { ArrayList() } += link
            slots.getValue(slot).toList()
        }
        for (member in members) member.push(RelayInbound.Peers(members.size))
        return link
    }

    fun forward(packet: ByteArray, from: FakeLink) {
        // Шифр всегда добавляет 21 байт заголовка и метки.
        if (packet.size < 21) plaintextLeaked = true
        val others = synchronized(this) { if (stalled) emptyList() else slots[from.slot]?.filter { it !== from } ?: emptyList() }
        for (other in others) other.push(RelayInbound.Packet(packet))
    }

    fun leave(link: FakeLink) {
        val rest = synchronized(this) {
            slots[link.slot]?.remove(link)
            slots[link.slot]?.toList() ?: emptyList()
        }
        for (member in rest) member.push(RelayInbound.Peers(rest.size))
    }

    /** Связи живы, но кадры не идут: так выглядит повисший канал собеседника. */
    fun stall() { stalled = true }

    /** Релей перезапустился: все связи рвутся. */
    fun dropAll() {
        val all = synchronized(this) { slots.values.flatten() }
        for (link in all) link.close()
    }
}

class FakeLink(private val relay: FakeRelay, val slot: String) : CallTransport {
    private val channel = Channel<RelayInbound>(256, BufferOverflow.DROP_OLDEST)
    override val inbound: ReceiveChannel<RelayInbound> get() = channel
    private val closed = AtomicBoolean(false)

    fun push(item: RelayInbound) { channel.trySend(item) }
    override fun send(packet: ByteArray) { if (!closed.get()) relay.forward(packet, this) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        relay.leave(this)
        channel.close()
    }
}

/** Вместо Opus — 16-битный PCM: голос доходит до динамика тем же, чем ушёл. */
object PcmCodecs : VoiceCodecs {
    override fun encoder(): VoiceEncoder = object : VoiceEncoder {
        override fun encode(samples: FloatArray): ByteArray {
            val out = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                out[2 * i] = v.toByte()
                out[2 * i + 1] = (v shr 8).toByte()
            }
            return out
        }
    }

    override fun decoder(): VoiceDecoder = object : VoiceDecoder {
        override fun decode(packet: ByteArray): FloatArray = FloatArray(packet.size / 2) { i ->
            ((packet[2 * i].toInt() and 0xFF) or (packet[2 * i + 1].toInt() shl 8)).toShort() / 32767f
        }
    }
}

/** Вместо микрофона — тон 440 Гц, вместо динамика — счётчик: тянет кадры в темпе 20 мс, как звуковая карта. */
class ToneVoice : VoiceIO {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun start(pipe: VoicePipe) {
        running = true
        worker = Thread {
            var phase = 0.0
            val tone = FloatArray(VoiceFormat.FRAME_SAMPLES)
            val out = FloatArray(VoiceFormat.FRAME_SAMPLES)
            while (running) {
                for (i in tone.indices) {
                    tone[i] = (0.3 * sin(phase)).toFloat()
                    phase += 2 * Math.PI * 440 / VoiceFormat.SAMPLE_RATE
                }
                pipe.capture(tone)
                pipe.render(out)
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        worker?.let { if (it !== Thread.currentThread()) it.join(1_000) }
    }
}
