// Нютаг. Главный экран: вкладки внизу (выбранная живёт в окне, чтобы вернуться на неё из чата), как на iOS (Чаты | Контакты | Сообщества | Календарь).
// Открытое сообщество тоже живёт в окне: из чата его группы возвращаемся в карточку.
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
fun HomeScreen(session: AppSession, tab: Int, onTab: (Int) -> Unit, community: String?, onCommunity: (String?) -> Unit,
               onOpen: (String) -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    var inviting by rememberSaveable { mutableStateOf(false) }
    var founding by rememberSaveable { mutableStateOf(false) }
    var grouping by rememberSaveable { mutableStateOf(false) }
    var meOpen by rememberSaveable { mutableStateOf(false) }
    if (inviting) {
        InviteScreen(session) { inviting = false }
        return
    }
    if (meOpen) {
        MeScreen(session, onClose = { meOpen = false }, onOpen = onOpen, onInvite = { inviting = true })
        return
    }
    if (grouping) {
        GroupCreateScreen(session, onClose = { grouping = false }) { grouping = false; onOpen(it) }
        return
    }
    if (founding) {
        NewCommunityScreen(session, onClose = { founding = false }) { founding = false; onCommunity(it) }
        return
    }
    if (tab == 2 && community != null) {
        CommunityScreen(session, community, onClose = { onCommunity(null) }, onOpen = onOpen)
        return
    }
    Column(Modifier.fillMaxSize().background(palette.milk).safeDrawingPadding()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ChatListScreen(session, onOpen, onMe = { meOpen = true }, onNewGroup = { grouping = true })
                1 -> ContactsScreen(session, onOpen, onInvite = { inviting = true })
                2 -> CommunitiesScreen(session, onCommunity = { onCommunity(it) }, onNew = { founding = true })
                else -> CalendarScreen()
            }
        }
        HorizontalDivider(color = palette.plate)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            TabItem(words(P.chats), tab == 0, Modifier.weight(1f), ::bubbles) { onTab(0) }
            TabItem(words(P.contacts), tab == 1, Modifier.weight(1f), ::people) { onTab(1) }
            TabItem(words(P.communities), tab == 2, Modifier.weight(1f), ::hexagons) { onTab(2) }
            TabItem(words(P.calendar), tab == 3, Modifier.weight(1f), ::moonStars) { onTab(3) }
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

/** Соты: три шестиугольника, как значок сообществ на iOS. */
private fun hexagons(scope: DrawScope, color: Color) = with(scope) {
    val r = size.height * 0.24f
    val stroke = Stroke(width = 1.6.dp.toPx())
    val dx = r * 1.732f / 2f
    val centers = listOf(Offset(size.width / 2f - dx, size.height * 0.68f), Offset(size.width / 2f + dx, size.height * 0.68f),
                         Offset(size.width / 2f, size.height * 0.68f - r * 1.5f))
    for (c in centers) {
        val path = androidx.compose.ui.graphics.Path()
        for (i in 0 until 6) {
            val a = Math.toRadians(60.0 * i - 90.0)
            val p = Offset(c.x + r * Math.cos(a).toFloat(), c.y + r * Math.sin(a).toFloat())
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawPath(path, color, style = stroke)
    }
}

/** Полумесяц и две звезды, как moon.stars на iOS. */
private fun moonStars(scope: DrawScope, color: Color) = with(scope) {
    val h = size.height
    val c = Offset(size.width * 0.40f, h * 0.55f)
    val r = h * 0.38f
    val moon = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    val bite = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * 0.55f, c.y - r * 0.40f), r * 0.85f)) }
    drawPath(androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, moon, bite), color)
    drawCircle(color, h * 0.08f, Offset(size.width * 0.80f, h * 0.18f))
    drawCircle(color, h * 0.05f, Offset(size.width * 0.92f, h * 0.48f))
}
