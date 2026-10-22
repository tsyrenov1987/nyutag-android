// Нютаг. Один звонок: от будильника до отбоя.
// Звонящий шлёт ring (открыто, ради пуша) и offer со своим ключом; принявший отвечает answer со своим.
// Из пары ключей обе стороны выводят слот релея и ключи кадров, подключаются к релею и, когда в слоте оба,
// включают звук. Время разговора идёт с этого момента. Обрыв связи до 20 с переживаем переподключением.
// Групповой (голос): начавший шлёт ring с `group` и join со случайным слотом, своим ключом и меткой, и сразу ждёт
// в слоте. Принявший шлёт join с тем же слотом. Каждый слушает всех, чьи join видел. Отклонивший молчит.
// Ушедший шлёт leave; никто так и не пришёл — начавший шлёт end, и у остальных будильник гаснет.
// Всё состояние звонка живёт на одном последовательном диспетчере, как у актора iOS-клиента.

package chat.nyutag.core.calls

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

sealed class CallPhase {
    /** Мы звоним, собеседник ещё не ответил. */
    object Calling : CallPhase() { override fun toString() = "Calling" }
    /** Мы звоним, у собеседника звенит (пришло его «у меня звонит»): идут гудки. */
    object Alerting : CallPhase() { override fun toString() = "Alerting" }
    /** Звонят нам. */
    object Ringing : CallPhase() { override fun toString() = "Ringing" }
    /** Ответ есть, сводим связь через релей. */
    object Connecting : CallPhase() { override fun toString() = "Connecting" }
    /** `since` — мс с 1970. */
    data class Live(val since: Long) : CallPhase()
    data class Ended(val reason: CallEndReason) : CallPhase()

    val isEnded: Boolean get() = this is Ended
    val isLive: Boolean get() = this is Live
}

/** Всё, чем звонок пользуется снаружи: подменяется в тестах. Время — в мс. */
class CallEnvironment(
    val me: String,
    val deviceID: String,
    /** Отправить сигнал в комнату (шифром, если комната шифрованная и сигнал не ring). */
    val post: suspend (CallSignal, String) -> Unit,
    val openTransport: suspend (String) -> CallTransport?,
    val makeVoice: () -> VoiceIO,
    val codecs: VoiceCodecs,
    /** Без кодека видео (тесты голоса) звонок идёт только голосом. */
    val videoCodecs: VideoCodecs? = null,
    val makeCamera: () -> VideoSource? = { null },
    val ringTimeout: Long = 45_000,
    val connectTimeout: Long = 20_000,
    val lossGrace: Long = 20_000,
    /** Столько без единого кадра — связь считаем оборванной, даже если релей держит обе стороны (повисший канал
     *  собеседника): переподключаемся и ждём lossGrace. Собеседник шлёт кадры всегда, и с выключенным микрофоном. */
    val silenceLimit: Long = 5_000,
    val retryDelay: Long = 1_000,
    /** Сообщение устройствам человека (to-device): «у меня звонит». Второй аргумент — кому. */
    val notify: suspend (CallSignal, String) -> Unit = { _, _ -> },
)

/** Участник группового звонка. */
class CallMember(val user: String, val deviceID: String, internal val key: ByteArray, internal val tag: Long)

@OptIn(ExperimentalCoroutinesApi::class)
class CallSession internal constructor(
    val callID: String,
    val roomID: String,
    /** Собеседник; в групповом — начавший звонок (у начавшего — комната). */
    val peer: String,
    val isCaller: Boolean,
    video: Boolean,
    val group: Boolean,
    private val env: CallEnvironment,
) {
    val video: Boolean = if (group) false else video
    private val context = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + context)
    private val phaseFlow = MutableStateFlow<CallPhase>(if (isCaller) CallPhase.Calling else CallPhase.Ringing)
    /** Фаза для экрана звонка. Последняя — `Ended`. */
    val phases: StateFlow<CallPhase> get() = phaseFlow
    val phase: CallPhase get() = phaseFlow.value

    private val keys = CallKeyPair()
    private var theirKey: ByteArray? = null
    private var acceptRequested = false
    @Volatile private var pipe: VoicePipe? = null
    private var voice: VoiceIO? = null
    @Volatile private var videoPipe: VideoPipe? = null
    private var camera: VideoSource? = null
    @Volatile private var cameraWanted = this.video
    @Volatile private var pendingSink: VideoSink? = null
    /** Текущий канал: в него пишет поток захвата звука, меняет его звонок при переподключении. */
    private val outbox = AtomicReference<CallTransport?>(null)
    private var slot: String? = null
    private var reader: Job? = null
    private var deadline: Job? = null
    private var lossTimer: Job? = null
    private var watch: Job? = null
    private var lastHeard = System.nanoTime()
    // Групповой звонок.
    private val groupKey = CallSecret.makeGroupKey()
    private val groupTag = kotlin.random.Random.nextLong(1, 0x1_0000_0000L)
    private val members = LinkedHashMap<String, CallMember>()
    private var joined = false

    private suspend fun <T> confined(block: suspend () -> T): T = withContext(context) { block() }

    // MARK: Звонящий

    internal suspend fun dial() = confined {
        if (group) return@confined dialGroup()
        try {
            env.post(CallSignal.Ring(callID, video, env.ringTimeout), roomID)
            env.post(CallSignal.Offer(callID, keys.publicKey, video), roomID)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@confined finish(CallEndReason.LOST, notify = false)
        }
        arm(env.ringTimeout, CallEndReason.NO_ANSWER, notify = true)
    }

    private suspend fun dialGroup() {
        slot = CallID.make()
        try {
            env.post(CallSignal.Ring(callID, false, env.ringTimeout, group = true), roomID)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return finish(CallEndReason.LOST, notify = false)
        }
        arm(env.ringTimeout, CallEndReason.NO_ANSWER, notify = true)
        joinGroup()
    }

    /** Участники группового звонка, кроме нас: кто прислал join и не ушёл. */
    suspend fun others(): List<CallMember> = confined { members.values.sortedBy { it.user } }

    // MARK: Нам звонят

    /** Будильник звенит до `untilMillis`; не ответили — пропущенный, без сигнала в комнату.
     *  Звонящему сразу уходит «у меня звонит»: у него вместо «Звоним…» пойдут гудки. */
    internal suspend fun ring(untilMillis: Long) = confined {
        arm(maxOf(untilMillis - System.currentTimeMillis(), 1_000), CallEndReason.NO_ANSWER, notify = false)
        if (group) return@confined
        val signal = CallSignal.Ringing(callID, roomID, env.deviceID)
        scope.launch { runCatching { env.notify(signal, peer) } }
    }

    suspend fun accept() = confined {
        if (phase != CallPhase.Ringing) return@confined
        acceptRequested = true
        set(CallPhase.Connecting)
        arm(env.connectTimeout, CallEndReason.LOST, notify = true)
        if (group) {
            if (slot != null) joinGroup()
        } else if (theirKey != null) {
            answer()
        }
    }

    private suspend fun joinGroup() {
        val slot = slot ?: return
        if (joined || phase.isEnded) return
        val secret = CallSecret.group(slot, groupKey) ?: return
        joined = true
        try {
            env.post(CallSignal.Join(callID, slot, groupKey, groupTag, env.deviceID), roomID)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return finish(CallEndReason.LOST, notify = false)
        }
        val pipe = VoicePipe.group(secret, groupTag, env.codecs) { packet -> outbox.get()?.send(packet) }
            ?: return finish(CallEndReason.LOST, notify = true)
        for (member in members.values) {
            CallSecret.group(slot, member.key)?.let { pipe.add(member.tag, it) }
        }
        this.pipe = pipe
        openLink()
    }

    private suspend fun answer() {
        val theirs = theirKey
        val secret = theirs?.let { runCatching { keys.secret(it, callID, isCaller = false) }.getOrNull() }
            ?: return finish(CallEndReason.LOST, notify = true)
        try {
            env.post(CallSignal.Answer(callID, keys.publicKey, env.deviceID), roomID)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return finish(CallEndReason.LOST, notify = false)
        }
        connect(secret)
    }

    // MARK: Сигналы из комнаты

    internal suspend fun handle(message: CallMessage) = confined {
        if (message.signal.callID != callID || phase.isEnded) return@confined
        val mine = message.sender == env.me
        if (group) return@confined handleGroup(message, mine)
        when (val signal = message.signal) {
            is CallSignal.Ringing -> {
                if (isCaller && phase == CallPhase.Calling && message.sender == peer) set(CallPhase.Alerting)
            }
            is CallSignal.Offer -> {
                if (isCaller || mine || message.sender != peer || theirKey != null) return@confined
                theirKey = signal.key
                if (acceptRequested) answer()
            }
            is CallSignal.Answer -> {
                if (mine) {
                    // Своё эхо или ответ с другого нашего устройства.
                    if (!isCaller && signal.deviceID != env.deviceID && phase == CallPhase.Ringing) {
                        finish(CallEndReason.ELSEWHERE, notify = false)
                    }
                    return@confined
                }
                if (!isCaller || (phase != CallPhase.Calling && phase != CallPhase.Alerting) || message.sender != peer) return@confined
                val secret = runCatching { keys.secret(signal.key, callID, isCaller = true) }.getOrNull() ?: return@confined
                set(CallPhase.Connecting)
                arm(env.connectTimeout, CallEndReason.LOST, notify = true)
                connect(secret)
            }
            is CallSignal.End -> {
                if (mine) {
                    // Отклонили на другом нашем устройстве; своё эхо сюда не доходит — звонок уже закончен.
                    if (!isCaller && phase == CallPhase.Ringing) finish(CallEndReason.ELSEWHERE, notify = false)
                    return@confined
                }
                if (message.sender != peer) return@confined
                finish(signal.reason, notify = false)
            }
            is CallSignal.Ring, is CallSignal.Join, is CallSignal.Leave -> {}
        }
    }

    private suspend fun handleGroup(message: CallMessage, mine: Boolean) {
        when (val signal = message.signal) {
            is CallSignal.Join -> {
                if (mine && signal.deviceID == env.deviceID) return
                if (mine && phase == CallPhase.Ringing) return finish(CallEndReason.ELSEWHERE, notify = false)
                if (slot == null) slot = signal.slot
                if (signal.slot != slot) return
                val id = message.sender + "|" + signal.deviceID
                members[id]?.let { pipe?.remove(it.tag) }
                members[id] = CallMember(message.sender, signal.deviceID, signal.key, signal.tag)
                CallSecret.group(signal.slot, signal.key)?.let { pipe?.add(signal.tag, it) }
                if (acceptRequested && !joined) joinGroup()
            }
            is CallSignal.Leave -> {
                val old = members.remove(message.sender + "|" + signal.deviceID) ?: return
                pipe?.remove(old.tag)
                if (members.isEmpty() && phase.isLive) finish(CallEndReason.HANG_UP, notify = true)
            }
            is CallSignal.End -> {
                // Начавший сдался, пока никто не пришёл.
                if (mine || message.sender != peer || phase.isLive) return
                finish(signal.reason, notify = false)
            }
            is CallSignal.Ring, is CallSignal.Offer, is CallSignal.Answer, is CallSignal.Ringing -> {}
        }
    }

    // MARK: Разговор

    suspend fun hangUp() = confined {
        // Групповой отклоняем молча: остальные участники ждут других.
        val ringing = phase == CallPhase.Ringing
        finish(if (ringing) CallEndReason.DECLINED else CallEndReason.HANG_UP, notify = !(group && ringing))
    }

    fun setMuted(muted: Boolean) {
        pipe?.muted = muted
    }

    val stats: VoiceStats? get() = pipe?.stats

    val videoStats: VideoStats? get() = videoPipe?.stats

    /** Куда сообщать о картинке собеседника. Можно задать до начала разговора. */
    fun showVideo(sink: VideoSink?) {
        pendingSink = sink
        videoPipe?.sink = sink
    }

    val cameraOn: Boolean get() = cameraWanted

    suspend fun setCamera(on: Boolean) = confined {
        cameraWanted = on
        if (phase.isLive) applyCamera()
    }

    suspend fun switchCamera() = confined { camera?.switchCamera() }

    private fun applyCamera() {
        val videoPipe = videoPipe ?: return
        val current = camera
        if (cameraWanted && current == null) {
            val made = env.makeCamera()
            if (made == null) {
                cameraWanted = false
            } else {
                try {
                    made.start(videoPipe)
                    camera = made
                } catch (e: Exception) {
                    cameraWanted = false
                }
            }
        } else if (!cameraWanted && current != null) {
            current.stop()
            camera = null
        }
        videoPipe.cameraOn = cameraWanted && camera != null
    }

    private fun connect(secret: CallSecret) {
        val pipe = VoicePipe.direct(secret, env.codecs) { packet -> outbox.get()?.send(packet) }
        if (pipe == null) {
            scope.launch { finish(CallEndReason.LOST, notify = true) }
            return
        }
        this.pipe = pipe
        env.videoCodecs?.let { codecs ->
            videoPipe = VideoPipe(secret, codecs, send = { packet -> outbox.get()?.send(packet) },
                                  backlog = { outbox.get()?.backlog ?: 0 }).also { it.sink = pendingSink }
        }
        slot = secret.slot
        openLink()
    }

    private fun openLink() {
        val slot = slot ?: return
        if (phase.isEnded) return
        reader?.cancel()
        reader = scope.launch {
            val transport = env.openTransport(slot)
            if (transport == null) {
                delay(env.retryDelay)
                linkDropped()
                return@launch
            }
            if (phase.isEnded) return@launch transport.close()
            outbox.set(transport)
            try {
                for (item in transport.inbound) {
                    when (item) {
                        is RelayInbound.Packet -> deliver(item.data)
                        is RelayInbound.Peers -> peers(item.count)
                    }
                }
            } finally {
                transport.close()
                outbox.compareAndSet(transport, null)
            }
            delay(env.retryDelay)
            linkDropped()
        }
    }

    private fun deliver(packet: ByteArray) {
        if (group || (packet.isNotEmpty() && packet[0] == FrameKind.AUDIO.code)) pipe?.receive(packet) else videoPipe?.receive(packet)
        lastHeard = System.nanoTime()
        // Связь вернулась, когда снова пошёл звук, а не когда релей насчитал двоих.
        lossTimer?.let {
            it.cancel()
            lossTimer = null
        }
    }

    private suspend fun peers(count: Int) {
        if (phase.isEnded) return
        if (count >= 2) {
            if (!(phase == CallPhase.Connecting || (group && phase == CallPhase.Calling))) return
            val pipe = pipe ?: return
            deadline?.cancel()
            val voice = env.makeVoice()
            try {
                voice.start(pipe)
            } catch (e: Exception) {
                return finish(CallEndReason.LOST, notify = true)
            }
            this.voice = voice
            set(CallPhase.Live(System.currentTimeMillis()))
            lastHeard = System.nanoTime()
            watchSilence()
            applyCamera()
        } else if (phase.isLive) {
            startLossTimer()
        }
    }

    private fun linkDropped() {
        if (phase.isEnded) return
        outbox.set(null)
        if (phase.isLive) startLossTimer()
        openLink()
    }

    private fun watchSilence() {
        val limit = env.silenceLimit
        watch?.cancel()
        watch = scope.launch {
            while (isActive) {
                delay(limit / 2)
                checkSilence(limit)
            }
        }
    }

    private fun checkSilence(limit: Long) {
        if (!phase.isLive || lossTimer != null || (System.nanoTime() - lastHeard) / 1_000_000 <= limit) return
        startLossTimer()
        outbox.getAndSet(null)?.close()
        openLink()
    }

    private fun startLossTimer() {
        if (lossTimer != null) return
        val grace = env.lossGrace
        lossTimer = scope.launch {
            delay(grace)
            finish(CallEndReason.LOST, notify = true)
        }
    }

    // MARK: Внутреннее

    private fun arm(delayMillis: Long, reason: CallEndReason, notify: Boolean) {
        deadline?.cancel()
        deadline = scope.launch {
            delay(delayMillis)
            finish(reason, notify)
        }
    }

    private fun set(phase: CallPhase) {
        phaseFlow.value = phase
    }

    internal suspend fun finish(reason: CallEndReason, notify: Boolean) {
        if (phase.isEnded) return
        val before = phase
        set(CallPhase.Ended(reason))
        voice?.stop()
        voice = null
        camera?.stop()
        camera = null
        videoPipe?.close()
        pipe?.close()
        outbox.getAndSet(null)?.close()
        // Конец часто объявляет сам таймер или чтец: гасим их после отправки, иначе отмена оборвёт и её.
        try {
            if (notify && !group) {
                env.post(CallSignal.End(callID, reason), roomID)
            } else if (notify) {
                if (joined) env.post(CallSignal.Leave(callID, env.deviceID), roomID)
                // Начавший ушёл, пока никто не пришёл: у остальных гаснет будильник.
                if (before == CallPhase.Calling) env.post(CallSignal.End(callID, reason), roomID)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
        scope.cancel()
    }
}
