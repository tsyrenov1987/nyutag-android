// Нютаг. Карточка над списком чатов для установок, где старая версия заперла историю отдельным ключом.
// Ключ больше не спрашиваем: новый вход паролем заводит хранилище под паролем, и дальше история сохраняется.

package chat.nyutag.messenger.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Карточка над списком: золотая полоска слева, ключ, по нажатию предлагает выйти и войти снова. */
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
