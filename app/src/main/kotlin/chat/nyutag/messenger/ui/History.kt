// Нютаг. Старая история под прежним ключом: карточка над списком чатов и окно ввода ключа.
// Ключ вводится один раз; дальше хранилище заперто паролем аккаунта, и на новых устройствах хватает пароля.

package chat.nyutag.messenger.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.session.AppSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Карточка над списком: золотая полоска слева, ключ, по нажатию открывает окно ключа. */
@Composable
fun LockedHistoryCard(onOpen: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.card).clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).heightIn(min = 64.dp).background(palette.gold))
        Row(Modifier.weight(1f).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(palette.gold), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(24.dp)) { key(palette.onGold, palette.gold) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(words(P.historyLockedTitle), color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(words(P.historyLockedLead), color = palette.quiet, fontSize = 13.sp)
            }
            Text("›", color = palette.quiet, fontSize = 22.sp)
        }
    }
}

@Composable
fun HistoryKeyScreen(session: AppSession, onClose: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Пароль этого входа ещё в памяти: ключ перезапрёт хранилище, и дальше хватит пароля.
    val canRelock = remember { session.canRelockHistory }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<AppSession.KeyFailure?>(null) }
    var done by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val canSubmit = !busy && !done && key.count { !it.isWhitespace() } >= 8
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit() {
        if (!canSubmit) return
        busy = true
        failure = null
        scope.launch {
            try {
                session.openHistory(key)
                done = true
                busy = false
                delay(1200)
                onClose()
            } catch (e: AppSession.KeyException) {
                failure = e.failure
                busy = false
            }
            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "ключ ${if (done) "принят" else if (failure == AppSession.KeyFailure.WRONG_KEY) "отклонён" else "ошибка $failure"}")
        }
    }

    FormPage(words(P.historyLockedTitle), onClose, closeTitle = words(P.later)) {
        Text(words(if (canRelock) P.historyKeyExplain else P.historyKeyExplainNoPassword), color = palette.quiet, fontSize = 15.sp)
        FormCard {
            TextField(key, { key = it }, Modifier.fillMaxWidth().focusRequester(focus), minLines = 2, maxLines = 4, colors = fieldColors(),
                      placeholder = { Text(words(P.recoveryKey)) },
                      textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 17.sp),
                      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
            Text(words(P.paste), color = palette.sky, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.align(Alignment.End).clickable { paste(context)?.let { key = it } }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        failure?.let {
            Text(words(if (it == AppSession.KeyFailure.WRONG_KEY) P.wrongKey else P.noNetwork), color = palette.ink, fontSize = 15.sp,
                 modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.gold.copy(alpha = 0.18f)).padding(12.dp))
        }
        if (done) Text("✓ " + words(P.historyOpened), color = palette.read, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(18.dp))
                .background(palette.sky.copy(alpha = if (canSubmit) 1f else 0.45f)).clickable(enabled = canSubmit, onClick = ::submit),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            Text(words(if (busy) P.openingHistory else P.openHistory), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun paste(context: Context): String? =
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()

/** Ключ лёжа: кольцо головки и бородка, как key.horizontal на iOS. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.key(color: Color, hole: Color) {
    val w = size.width
    val h = size.height
    drawCircle(color, h * 0.26f, Offset(w * 0.28f, h * 0.5f))
    drawCircle(hole, h * 0.1f, Offset(w * 0.22f, h * 0.5f))
    drawRoundRect(color, Offset(w * 0.45f, h * 0.43f), Size(w * 0.50f, h * 0.14f), CornerRadius(h * 0.05f))
    drawRect(color, Offset(w * 0.72f, h * 0.55f), Size(w * 0.08f, h * 0.16f))
    drawRect(color, Offset(w * 0.85f, h * 0.55f), Size(w * 0.08f, h * 0.20f))
}
