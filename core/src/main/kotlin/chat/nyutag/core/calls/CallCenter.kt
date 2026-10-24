// Нютаг. Диспетчер звонков аккаунта: сигналы из ленты → нужный звонок, входящий будильник → экран, второй
// звонок во время разговора → «занято».

package chat.nyutag.core.calls

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class CallCenter(private val env: CallEnvironment) {
    private val context = Dispatchers.Default.limitedParallelism(1)
    private val sessions = LinkedHashMap<String, CallSession>()
    /** offer, пришедший раньше будильника (шифрованный расшифровался позже открытого или наоборот). */
    private val earlyOffers = LinkedHashMap<String, CallMessage>()
    /** join группового звонка, пришедшие раньше будильника. */
    private val earlyJoins = LinkedHashMap<String, MutableList<CallMessage>>()
    private val incomingChannel = Channel<CallSession>(4, BufferOverflow.DROP_OLDEST)
    /** Входящие звонки для экрана. */
    val incoming: ReceiveChannel<CallSession> get() = incomingChannel

    /** Позвонить. `null` — уже идёт другой звонок. */
    suspend fun dial(roomID: String, peer: String, video: Boolean = false): CallSession? = withContext(context) {
        if (activeHere() != null) return@withContext null
        val session = CallSession(CallID.make(), roomID, peer, isCaller = true, video = video, group = false, env = env)
        sessions[session.callID] = session
        session.dial()
        session
    }

    /** Групповой звонок в группе (ключи кадров идут шифром комнаты, даже открытой). `null` — уже идёт другой звонок. */
    suspend fun dialGroup(roomID: String): CallSession? = withContext(context) {
        if (activeHere() != null) return@withContext null
        val session = CallSession(CallID.make(), roomID, roomID, isCaller = true, video = false, group = true, env = env)
        sessions[session.callID] = session
        session.dial()
        session
    }

    /** Текущий незаконченный звонок. */
    suspend fun active(): CallSession? = withContext(context) { activeHere() }

    private fun activeHere(): CallSession? {
        val iterator = sessions.values.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            if (session.phase.isEnded) iterator.remove() else return session
        }
        return null
    }

    suspend fun absorb(message: CallMessage, now: Long = System.currentTimeMillis()) = withContext(context) {
        val id = message.signal.callID
        sessions[id]?.let { return@withContext it.handle(message) }
        when (val signal = message.signal) {
            is CallSignal.Ring -> {
                if (message.sender == env.me || !message.isRinging(now)) return@withContext
                // Занятому групповой не звонит: остальные участники разговаривают и без него.
                if (activeHere() != null) {
                    if (!signal.group) runCatching { env.post(CallSignal.End(id, CallEndReason.BUSY), message.roomID) }
                    return@withContext
                }
                val session = CallSession(id, message.roomID, message.sender, isCaller = false, video = signal.video,
                                          group = signal.group, env = env)
                sessions[id] = session
                // Время будильника — по часам сервера. Часы телефона могут расходиться на десятки секунд, поэтому
                // звеним не меньше MINIMUM_RING: раньше звонок гасит отбой звонящего.
                val until = maxOf((message.timestamp ?: 0) + signal.lifetimeMillis,
                                  now + minOf(MINIMUM_RING, signal.lifetimeMillis))
                session.ring(until)
                earlyOffers.remove(id)?.let { session.handle(it) }
                earlyJoins.remove(id)?.forEach { session.handle(it) }
                incomingChannel.trySend(session)
            }
            is CallSignal.Offer -> {
                if (message.sender == env.me) return@withContext
                if (earlyOffers.size >= 16) earlyOffers.keys.firstOrNull()?.let { earlyOffers.remove(it) }
                earlyOffers[id] = message
            }
            is CallSignal.Join -> {
                if (id !in earlyJoins && earlyJoins.size >= 16) earlyJoins.keys.firstOrNull()?.let { earlyJoins.remove(it) }
                val list = earlyJoins.getOrPut(id) { ArrayList() }
                if (list.size < 32) list += message
            }
            is CallSignal.Answer, is CallSignal.End, is CallSignal.Leave, is CallSignal.Ringing -> {}
        }
    }

    companion object {
        /** Сколько звенеть входящему как минимум, мс, сколько бы будильник ни шёл по чужим часам. */
        const val MINIMUM_RING = 30_000L
    }
}
