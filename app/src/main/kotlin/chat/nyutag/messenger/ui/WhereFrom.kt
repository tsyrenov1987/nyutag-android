// Нютаг. «Откуда вы родом?»: один раз после входа, пока человек ни в одном сообществе. Ведёт в свой нютаг, где
// земляки и общий чат: без него новичок остаётся с пустым списком чатов.

package chat.nyutag.messenger.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.rooms.PublicRoom
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.session.AppSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object WhereFrom {
    private const val DONE = "whereFromDone"

    fun due(context: Context): Boolean = !prefs(context).getBoolean(DONE, false)

    fun done(context: Context) = prefs(context).edit().putBoolean(DONE, true).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("nyutag.ui", Context.MODE_PRIVATE)

    /** Открытые, но не земляческие: тестирование, Buriyad Gürün, iOS-чат. В «Откуда» их не показываем. */
    val notNyutag = setOf(
        "!VEj4gB4U9HZG_NGmOtFSjovtzTxwrnUpIQO_DDn90eA",
        "!uMU-VoU8rMTIGdD2rDV0kwkc3fYfrSvWRSuhnjDnve4",
        "!tQswEmiMYcV3nEMOPdlIVSc1UaZdyrCoW59WbUFIClk",
    )
}

/** Каталог нютагов, крупные первыми. Открытый — вступаем сразу и открываем карточку, закрытый — карточка с заявкой. */
@Composable
fun WhereFromScreen(session: AppSession, onCommunity: (String) -> Unit, onNew: () -> Unit, onClose: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<List<PublicRoom>?>(session.catalog.takeIf { it.isNotEmpty() }) }
    var joining by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        for (pause in listOf(0L, 2L, 4L, 8L, 15L)) {
            if (pause > 0) delay(pause * 1000)
            session.findCommunities("")?.let { catalog = it; return@LaunchedEffect }
        }
        catalog = emptyList()
    }

    FormPage(words(P.whereFromTitle), onClose, closeTitle = words(P.whereFromLater)) {
        Text(words(P.whereFromHint), color = palette.quiet, fontSize = 15.sp, textAlign = TextAlign.Center,
             modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        val list = catalog
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        for (room in list.orEmpty().filter { it.roomID !in WhereFrom.notNyutag }) {
            val community = Community.of(room)
            CommunityRow(community) {
                if (joining != null) return@CommunityRow
                joining = room.roomID
                scope.launch {
                    if (!community.isClosed) session.joinCommunity(room.roomID, room.joinRule)
                    if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "откуда: выбран нютаг")
                    joining = null
                    onCommunity(room.roomID)
                }
            }
        }
        Text("＋ " + words(P.whereFromNew), color = palette.sky, fontSize = 16.sp, fontWeight = FontWeight.Medium,
             modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onNew).padding(16.dp))
    }
}
