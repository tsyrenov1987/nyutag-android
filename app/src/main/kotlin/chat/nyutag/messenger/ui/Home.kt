// Нютаг. Главный экран: вкладки внизу (выбранная живёт в окне, чтобы вернуться на неё из чата), как на iOS (Чаты | Контакты; сообщества и календарь — следующими срезами).
// Значки рисуем сами: набора иконок в зависимостях нет.

package chat.nyutag.messenger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.messenger.session.AppSession

@Composable
fun HomeScreen(session: AppSession, tab: Int, onTab: (Int) -> Unit, onOpen: (String) -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    var inviting by rememberSaveable { mutableStateOf(false) }
    if (inviting) {
        InviteScreen(session) { inviting = false }
        return
    }
    Column(Modifier.fillMaxSize().background(palette.milk).safeDrawingPadding()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ChatListScreen(session, onOpen, onInvite = { inviting = true })
                else -> ContactsScreen(session, onOpen, onInvite = { inviting = true })
            }
        }
        HorizontalDivider(color = palette.plate)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            TabItem(words(P.chats), tab == 0, Modifier.weight(1f), ::bubbles) { onTab(0) }
            TabItem(words(P.contacts), tab == 1, Modifier.weight(1f), ::people) { onTab(1) }
        }
    }
}

@Composable
private fun TabItem(title: String, selected: Boolean, modifier: Modifier, icon: DrawScope.(Color) -> Unit, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val color = if (selected) palette.sky else palette.quiet
    Column(modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
           horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Canvas(Modifier.size(26.dp, 22.dp)) { icon(color) }
        Text(title, color = color, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Два облачка реплик. */
private fun bubbles(scope: DrawScope, color: Color) = with(scope) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.8.dp.toPx())
    drawRoundRect(color, Offset(w * 0.30f, h * 0.30f), Size(w * 0.66f, h * 0.52f), CornerRadius(h * 0.22f), style = stroke)
    drawRoundRect(color, Offset(w * 0.04f, h * 0.08f), Size(w * 0.62f, h * 0.50f), CornerRadius(h * 0.22f))
}

/** Двое людей: голова и плечи. */
private fun people(scope: DrawScope, color: Color) = with(scope) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.8.dp.toPx())
    drawCircle(color, h * 0.17f, Offset(w * 0.68f, h * 0.28f), style = stroke)
    drawArc(color, 180f, 180f, false, Offset(w * 0.46f, h * 0.56f), Size(w * 0.46f, h * 0.70f), style = stroke)
    drawCircle(color, h * 0.20f, Offset(w * 0.36f, h * 0.30f))
    drawArc(color, 180f, 180f, true, Offset(w * 0.08f, h * 0.58f), Size(w * 0.56f, h * 0.80f))
}
