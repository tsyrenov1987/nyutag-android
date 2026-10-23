// Нютаг. Единственное окно: язык → вход (или регистрация, сброс пароля) → вкладки (чаты, контакты) → переписка. Навигация — простое состояние и «назад».

package chat.nyutag.messenger

import chat.nyutag.messenger.widget.ZurkhaiWidget
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import chat.nyutag.core.calls.CallPhase
import chat.nyutag.messenger.session.AppSession
import chat.nyutag.messenger.ui.CallScreen
import chat.nyutag.messenger.ui.CallStrip
import chat.nyutag.messenger.ui.ChatScreen
import chat.nyutag.messenger.ui.HomeScreen
import chat.nyutag.messenger.ui.LanguageScreen
import chat.nyutag.messenger.ui.LocalAvatars
import chat.nyutag.messenger.ui.LocalWallpapers
import chat.nyutag.messenger.ui.Wallpapers
import chat.nyutag.messenger.ui.LocalPalette
import chat.nyutag.messenger.ui.LocalWords
import chat.nyutag.messenger.ui.NyutagTheme
import chat.nyutag.messenger.ui.PasswordResetScreen
import chat.nyutag.messenger.ui.SignInScreen
import chat.nyutag.messenger.ui.SignUpScreen
import chat.nyutag.messenger.ui.Words

class MainActivity : ComponentActivity() {
    private val app get() = application as NyutagApp
    /** Чат из нажатого уведомления: откроется, как только вход поднимется. */
    private val pushedRoom = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val calendarAsked = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Чат на экране; сессии он нужен, только пока окно видно. */
    private var shownRoom: String? = null
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val words = Words(app.prefs)
        val session = app.session
        session.launch()
        val wallpapers = Wallpapers(applicationContext)
        pushedRoom.value = intent?.getStringExtra(OPEN_ROOM)
        calendarAsked.value = intent?.getBooleanExtra(OPEN_CALENDAR, false) == true
        setContent {
            NyutagTheme {
                CompositionLocalProvider(LocalWords provides words, LocalAvatars provides session,
                                         LocalWallpapers provides wallpapers) {
                    val phase by session.phase.collectAsState()
                    var openRoom by rememberSaveable { mutableStateOf<String?>(null) }
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    var community by rememberSaveable { mutableStateOf<String?>(null) }
                    // Без входа: форма входа, регистрация или сброс пароля.
                    var entry by rememberSaveable { mutableStateOf(Entry.SIGN_IN) }
                    LaunchedEffect(phase) {
                        if (phase == AppSession.Phase.SIGNED_IN) entry = Entry.SIGN_IN
                        if (phase == AppSession.Phase.SIGNED_OUT) { tab = 0; community = null }
                    }
                    val pushed by pushedRoom.collectAsState()
                    LaunchedEffect(pushed, phase) {
                        val room = pushed ?: return@LaunchedEffect
                        if (phase != AppSession.Phase.SIGNED_IN) return@LaunchedEffect
                        openRoom = room
                        pushedRoom.value = null
                    }
                    // Виджет «Зурхай»: нажатие открывает вкладку «Календарь».
                    val calendar by calendarAsked.collectAsState()
                    LaunchedEffect(calendar, phase) {
                        if (!calendar || phase != AppSession.Phase.SIGNED_IN) return@LaunchedEffect
                        openRoom = null
                        community = null
                        tab = 3
                        calendarAsked.value = false
                    }
                    // Виджет пишет на языке приложения: сменили язык — перерисовать, заодно и новый день при запуске.
                    LaunchedEffect(words.lang, words.buryatLetters) { ZurkhaiWidget.refresh(applicationContext) }
                    LaunchedEffect(openRoom) {
                        shownRoom = openRoom
                        if (visible) session.visibleRoom = openRoom
                        openRoom?.let { app.notices.clear(it) }
                    }
                    // Android 13+: уведомления только с разрешения; спрашиваем один раз после входа.
                    val askNotices = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                    LaunchedEffect(phase) {
                        if (phase != AppSession.Phase.SIGNED_IN || Build.VERSION.SDK_INT < 33) return@LaunchedEffect
                        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
                        if (app.prefs.get("nyutag.noticesAsked") != null) return@LaunchedEffect
                        app.prefs.put("nyutag.noticesAsked", "1")
                        askNotices.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    val call by session.calls.current.collectAsState()
                    val folded by session.calls.folded.collectAsState()
                    // Звонок открывается и поверх запертого экрана; входящий ещё и будит экран.
                    val callPhase by session.calls.phase.collectAsState()
                    LaunchedEffect(call != null, callPhase == CallPhase.Ringing) {
                        setShowWhenLocked(call != null)
                        setTurnScreenOn(callPhase == CallPhase.Ringing)
                    }
                    val inCall = call != null && phase == AppSession.Phase.SIGNED_IN
                    Column(Modifier.fillMaxSize()) {
                    // Свёрнутый звонок: полоска сверху забирает отступ строки состояния у экранов под ней.
                    if (inCall && folded) CallStrip(session.calls)
                    Box(Modifier.weight(1f).then(if (inCall && folded) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
                    when {
                        words.lang == null -> LanguageScreen(words)
                        phase == AppSession.Phase.LAUNCHING ->
                            Box(Modifier.fillMaxSize().background(LocalPalette.current.milk), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        phase == AppSession.Phase.SIGNED_OUT -> when (entry) {
                            Entry.SIGN_IN -> SignInScreen(session, onSignUp = { entry = Entry.SIGN_UP }, onReset = { entry = Entry.RESET })
                            Entry.SIGN_UP -> SignUpScreen(session, app.doors) { entry = Entry.SIGN_IN }
                            Entry.RESET -> PasswordResetScreen(session, app.doors) { entry = Entry.SIGN_IN }
                        }
                        openRoom != null -> ChatScreen(session, openRoom!!) { openRoom = null }
                        else -> HomeScreen(session, tab, { tab = it }, community, { community = it }) { openRoom = it }
                    }
                    }
                    }
                    if (inCall && !folded) CallScreen(session.calls)
                }
            }
        }
    }

    private enum class Entry { SIGN_IN, SIGN_UP, RESET }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(OPEN_ROOM)?.let { pushedRoom.value = it }
        if (intent.getBooleanExtra(OPEN_CALENDAR, false)) calendarAsked.value = true
    }

    override fun onStart() {
        super.onStart()
        visible = true
        app.session.visibleRoom = shownRoom
        app.doors.appBecameActive()
        app.session.appBecameActive()
    }

    override fun onStop() {
        visible = false
        app.session.visibleRoom = null
        super.onStop()
    }

    companion object {
        const val OPEN_ROOM = "chat.nyutag.messenger.OPEN_ROOM"
        const val OPEN_CALENDAR = "chat.nyutag.messenger.OPEN_CALENDAR"
    }
}
