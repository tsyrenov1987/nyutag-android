// Нютаг. Из меню сообщения: пересылка в другой чат и жалоба администратору, как на iOS.
// Пересылка — список своих чатов, «Избранное» первым, нажатие пересылает и закрывает. Жалоба — причина и две кнопки:
// просто отправить или отправить и заблокировать автора.

package chat.nyutag.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.rooms.FeedItem
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.session.AppSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Пересылать можно текст и вложения, кроме одноразовых. */
val FeedItem.canForward: Boolean
    get() = when (body) {
        is FeedItem.Body.Text -> true
        is FeedItem.Body.Attachment -> attachment?.let { !it.viewOnce } ?: false
        else -> false
    }

@Composable
fun ForwardScreen(session: AppSession, item: FeedItem, roomID: String, onClose: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val lines by session.lines.collectAsState()
    var saved by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { saved = withContext(Dispatchers.IO) { session.savedRoomID } }
    val joined = lines.filter { it.membership == RoomRecord.Membership.JOIN && !it.isSpace }
    val found = if (query.isBlank()) joined else joined.filter { it.title.contains(query.trim(), ignoreCase = true) }
    val targets = found.filter { it.id == saved } + found.filter { it.id != saved }
    LaunchedEffect(Unit) { if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "выбор чата для пересылки, чатов ${joined.size}") }

    FormPage(words(P.forward), onClose) {
        TextField(query, { query = it }, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.card),
                  singleLine = true, colors = fieldColors(), placeholder = { Text(words(P.searchChats)) },
                  keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        for (line in targets) {
            Box(Modifier.alpha(if (sending == null || sending == line.id) 1f else 0.5f)) {
                ChatRow(line, null, words) {
                    if (sending != null) return@ChatRow
                    sending = line.id
                    scope.launch {
                        val eventID = session.forward(item, roomID, line.id)
                        if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "переслано в ${if (line.id == saved) "избранное" else "чат"}: ${eventID != null}")
                        sending = null
                        if (eventID != null) onClose() else failed = true
                    }
                }
            }
        }
    }
    if (failed) {
        AlertDialog(onDismissRequest = { failed = false }, title = { Text(words(P.forwardFailed)) },
                    confirmButton = { TextButton({ failed = false }) { Text(words(P.done), color = palette.sky) } })
    }
}

@Composable
fun ReportScreen(session: AppSession, item: FeedItem, roomID: String, onClose: () -> Unit, onSent: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    fun send(block: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val text = reason.trim().ifEmpty { words(P.reportAction) }
            val ok = session.report(roomID, item.id, text) && (!block || session.setBlocked(item.sender, true))
            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "жалоба${if (block) " с блокировкой" else ""} ${if (ok) "ушла" else "не прошла"}")
            busy = false
            if (ok) onSent() else failed = true
        }
    }

    FormPage(words(P.reportTitle), onClose) {
        Text(words(P.reportLead), color = palette.ink, fontSize = 16.sp)
        FormCard {
            TextField(reason, { reason = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, colors = fieldColors(),
                      placeholder = { Text(words(P.reportReason)) },
                      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        }
        BigButton(words(P.send), busy, !busy) { send(false) }
        Text(words(P.reportAndBlock), color = Danger.copy(alpha = if (busy) 0.45f else 1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(12.dp))
                 .clickable(enabled = !busy) { send(true) }.padding(horizontal = 16.dp, vertical = 10.dp))
    }
    if (failed) {
        AlertDialog(onDismissRequest = { failed = false }, title = { Text(words(P.cantDo)) },
                    confirmButton = { TextButton({ failed = false }) { Text(words(P.done), color = palette.sky) } })
    }
}
