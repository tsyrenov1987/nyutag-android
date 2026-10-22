// Нютаг. Единственное окно: язык → вход (или регистрация, сброс пароля) → вкладки (чаты, контакты) → переписка. Навигация — простое состояние и «назад».

package chat.nyutag.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import chat.nyutag.messenger.session.AppSession
import chat.nyutag.messenger.ui.ChatScreen
import chat.nyutag.messenger.ui.HomeScreen
import chat.nyutag.messenger.ui.LanguageScreen
import chat.nyutag.messenger.ui.LocalPalette
import chat.nyutag.messenger.ui.LocalWords
import chat.nyutag.messenger.ui.NyutagTheme
import chat.nyutag.messenger.ui.PasswordResetScreen
import chat.nyutag.messenger.ui.SignInScreen
import chat.nyutag.messenger.ui.SignUpScreen
import chat.nyutag.messenger.ui.Words

class MainActivity : ComponentActivity() {
    private val app get() = application as NyutagApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val words = Words(app.prefs)
        val session = app.session
        session.launch()
        setContent {
            NyutagTheme {
                CompositionLocalProvider(LocalWords provides words) {
                    val phase by session.phase.collectAsState()
                    var openRoom by rememberSaveable { mutableStateOf<String?>(null) }
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    // Без входа: форма входа, регистрация или сброс пароля.
                    var entry by rememberSaveable { mutableStateOf(Entry.SIGN_IN) }
                    LaunchedEffect(phase) {
                        if (phase == AppSession.Phase.SIGNED_IN) entry = Entry.SIGN_IN
                        if (phase == AppSession.Phase.SIGNED_OUT) tab = 0
                    }
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
                        else -> HomeScreen(session, tab, { tab = it }) { openRoom = it }
                    }
                }
            }
        }
    }

    private enum class Entry { SIGN_IN, SIGN_UP, RESET }

    override fun onStart() {
        super.onStart()
        app.doors.appBecameActive()
    }
}
