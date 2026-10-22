// Нютаг. Стойка звонков для экрана: какой звонок идёт, его фаза, участники группового, микрофон и громкая связь.
// Входящий звенит мелодией телефона с вибрацией, исходящий — гудками. Идущий звонок держит службу переднего плана:
// без неё система глушит микрофон, когда экран гаснет или приложение уходит в фон.

package chat.nyutag.messenger.calls

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import chat.nyutag.core.calls.CallCenter
import chat.nyutag.core.calls.CallMessage
import chat.nyutag.core.calls.CallPhase
import chat.nyutag.core.calls.CallSession
import chat.nyutag.messenger.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

class CallDesk(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val currentFlow = MutableStateFlow<CallSession?>(null)
    /** Звонок на экране; после конца итог висит полторы секунды, потом `null`. */
    val current: StateFlow<CallSession?> get() = currentFlow
    private val phaseFlow = MutableStateFlow<CallPhase?>(null)
    val phase: StateFlow<CallPhase?> get() = phaseFlow
    private val peerNameFlow = MutableStateFlow("")
    val peerName: StateFlow<String> get() = peerNameFlow
    private val membersFlow = MutableStateFlow<List<String>>(emptyList())
    /** Кто в групповом звонке, кроме нас. */
    val members: StateFlow<List<String>> get() = membersFlow
    private val mutedFlow = MutableStateFlow(false)
    val muted: StateFlow<Boolean> get() = mutedFlow
    private val speakerFlow = MutableStateFlow(false)
    val speaker: StateFlow<Boolean> get() = speakerFlow
    private val foldedFlow = MutableStateFlow(false)
    /** Звонок свёрнут в полоску над приложением: можно переписываться, не кладя трубку. */
    val folded: StateFlow<Boolean> get() = foldedFlow

    private var center: CallCenter? = null
    private var listener: Job? = null
    private var follower: Job? = null
    private var memberWatch: Job? = null
    /** Будильники, пришедшие раньше, чем поднялся вход. */
    private val waiting = ArrayList<CallMessage>()
    /** Звук текущего звонка: через него переключается громкая связь. */
    @Volatile private var voice: DeviceVoice? = null
    private val ringer = Ringer(context)

    /** Имя собеседника по комнате; участника группового — по комнате и пользователю. */
    var name: (String, String) -> String = { _, peer -> peer }
    var memberName: suspend (String, String) -> String = { _, user -> user }

    fun makeVoice(): DeviceVoice = DeviceVoice(context).also {
        it.setSpeaker(speakerFlow.value)
        voice = it
    }

    fun attach(center: CallCenter) {
        this.center = center
        val rings = waiting.toList()
        waiting.clear()
        listener?.cancel()
        listener = scope.launch {
            for (ring in rings) center.absorb(ring)
            for (session in center.incoming) show(session)
        }
    }

    suspend fun detach() {
        listener?.cancel()
        currentFlow.value?.hangUp()
        center = null
    }

    suspend fun absorb(message: CallMessage) {
        val center = center
        if (center == null) {
            if (waiting.size < 16) waiting += message
            return
        }
        center.absorb(message)
    }

    /** Позвонить один на один. Микрофон разрешает экран заранее. */
    suspend fun dial(roomID: String, peer: String) {
        val center = center ?: return
        if (currentFlow.value != null) return
        center.dial(roomID, peer)?.let { show(it) }
    }

    suspend fun dialGroup(roomID: String) {
        val center = center ?: return
        if (currentFlow.value != null) return
        center.dialGroup(roomID)?.let { show(it) }
    }

    suspend fun accept() {
        val session = currentFlow.value ?: return
        CallService.start(context, peerNameFlow.value)
        session.accept()
    }

    suspend fun hangUp() {
        currentFlow.value?.hangUp()
    }

    fun setMuted(muted: Boolean) {
        mutedFlow.value = muted
        currentFlow.value?.setMuted(muted)
    }

    fun setSpeaker(on: Boolean) {
        speakerFlow.value = on
        voice?.setSpeaker(on)
    }

    fun setFolded(folded: Boolean) {
        foldedFlow.value = folded && phaseFlow.value.let { it == CallPhase.Calling || it == CallPhase.Connecting || it is CallPhase.Live }
    }

    private fun show(session: CallSession) {
        currentFlow.value = session
        peerNameFlow.value = name(session.roomID, session.peer)
        mutedFlow.value = false
        speakerFlow.value = false
        foldedFlow.value = false
        membersFlow.value = emptyList()
        voice = null
        if (session.isCaller) CallService.start(context, peerNameFlow.value)
        memberWatch?.cancel()
        if (session.group) memberWatch = watchMembers(session)
        follower?.cancel()
        follower = scope.launch {
            session.phases.takeWhile { phase ->
                phaseFlow.value = phase
                ringer.follow(phase)
                log("звонок ${session.callID.take(6)}: $phase")
                !phase.isEnded
            }.collect {}
            phaseFlow.value = session.phase
            ringer.follow(session.phase)
            CallService.stop(context)
            session.stats?.let { log("звонок итог: ушло ${it.sent}, пришло ${it.received}, сыграно ${it.played}, потеряно ${it.lost}") }
            // Итог висит на экране недолго, потом экран уходит.
            delay(1_500)
            if (currentFlow.value !== session) return@launch
            currentFlow.value = null
            phaseFlow.value = null
            foldedFlow.value = false
            membersFlow.value = emptyList()
            memberWatch?.cancel()
            voice = null
        }
        if (BuildConfig.DEBUG) scope.launch {
            while (!session.phase.isEnded) {
                delay(2_000)
                val stats = session.stats ?: continue
                log("звонок: ушло ${stats.sent}, пришло ${stats.received}, сыграно ${stats.played}, потеряно ${stats.lost}, " +
                    "микрофон ${"%.3f".format(stats.inputLevel)}, динамик ${"%.3f".format(stats.outputLevel)}")
            }
        }
    }

    /** Участники группового звонка: кто пришёл и кто ушёл, раз в секунду. */
    private fun watchMembers(session: CallSession): Job = scope.launch {
        var known = emptyList<String>()
        while (!session.phase.isEnded) {
            val users = session.others().map { it.user }.distinct().sorted()
            if (users != known) {
                known = users
                val names = users.map { memberName(session.roomID, it) }.sorted()
                if (currentFlow.value !== session) return@launch
                membersFlow.value = names
                log("в групповом звонке ${names.size} кроме нас")
            }
            delay(1_000)
        }
    }

    fun close() {
        ringer.follow(null)
        scope.cancel()
    }

    private fun log(line: String) {
        if (BuildConfig.DEBUG) Log.d("ui-check", line)
    }

    /** Мелодия входящего с вибрацией и гудки исходящего. */
    private class Ringer(private val context: Context) {
        private var ringtone: Ringtone? = null
        private var tones: ToneGenerator? = null
        private var vibrating = false
        private val vibrator = context.getSystemService(Vibrator::class.java)

        fun follow(phase: CallPhase?) {
            when (phase) {
                CallPhase.Ringing -> ring()
                CallPhase.Calling -> ringback()
                else -> quiet()
            }
        }

        private fun ring() {
            if (ringtone != null) return
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                    isLooping = true
                    runCatching { play() }
                }
            }
            if (audio.ringerMode != AudioManager.RINGER_MODE_SILENT && vibrator?.hasVibrator() == true) {
                vibrating = true
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
            }
        }

        private fun ringback() {
            if (tones != null) return
            tones = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()?.apply {
                startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }

        private fun quiet() {
            ringtone?.stop()
            ringtone = null
            tones?.let { it.stopTone(); it.release() }
            tones = null
            if (vibrating) vibrator?.cancel()
            vibrating = false
        }
    }
}

/** Разговор с выключенным экраном: служба с типом «микрофон», пока идёт звонок. */
class CallService : android.app.Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, "Звонки", android.app.NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName), android.app.PendingIntent.FLAG_IMMUTABLE)
        val notification = android.app.Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title.ifEmpty { "Нютаг" })
            .setContentText("Нютаг")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        runCatching {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }.onFailure {
            Log.w("calls", "служба звонка не стала службой переднего плана: $it")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "calls"

        private var title = ""

        /** Только с разрешённым микрофоном: служба этого типа без него не встаёт, а начатая служба обязана встать. */
        fun start(context: Context, title: String) {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return
            this.title = title
            runCatching { context.startForegroundService(Intent(context, CallService::class.java)) }
                .onFailure { Log.w("calls", "служба звонка не запустилась: $it") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
