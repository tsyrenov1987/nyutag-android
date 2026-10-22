// Нютаг. Единственное окно: язык → вход → чаты → переписка. Навигация — простое состояние и «назад».

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import chat.nyutag.messenger.session.AppSession
import chat.nyutag.messenger.ui.ChatListScreen
import chat.nyutag.messenger.ui.ChatScreen
import chat.nyutag.messenger.ui.LanguageScreen
import chat.nyutag.messenger.ui.LocalPalette
import chat.nyutag.messenger.ui.LocalWords
import chat.nyutag.messenger.ui.NyutagTheme
import chat.nyutag.messenger.ui.SignInScreen
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
                    when {
                        words.lang == null -> LanguageScreen(words)
                        phase == AppSession.Phase.LAUNCHING ->
                            Box(Modifier.fillMaxSize().background(LocalPalette.current.milk), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        phase == AppSession.Phase.SIGNED_OUT -> SignInScreen(session)
                        openRoom != null -> ChatScreen(session, openRoom!!) { openRoom = null }
                        else -> ChatListScreen(session) { openRoom = it }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.doors.appBecameActive()
    }
}
