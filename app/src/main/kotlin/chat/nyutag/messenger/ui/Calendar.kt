// Нютаг. Вкладка «Календарь»: сколько дней до Сагаалгана, какой год по восточному календарю, что говорит
// зурхай про сегодняшний день, и даты Сагаалгана на годы вперёд. Данные — из нашего приложения «Зурхай».
// Значки рисуем сами: набора иконок в зависимостях нет.

package chat.nyutag.messenger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.nyutag.Sagaalgan
import chat.nyutag.core.nyutag.ZurkhaiDay
import chat.nyutag.core.nyutag.ZurkhaiDays
import chat.nyutag.core.rooms.EastYear
import chat.nyutag.messenger.BuildConfig
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

/** Таблица зурхая из движка; испорченная или пропавшая — карточки дня просто нет. */
private val zurkhai: ZurkhaiDays? by lazy { runCatching { ZurkhaiDays.bundled() }.getOrNull() }

@Composable
fun CalendarScreen() {
    val words = LocalWords.current
    val palette = LocalPalette.current
    var today by remember { mutableStateOf(LocalDate.now()) }
    // Вкладку могут держать открытой через полночь.
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now()
            if (BuildConfig.DEBUG) {
                val next = Sagaalgan.next(today)
                android.util.Log.d("ui-check", "календарь: Сагаалган ${next?.let { "${it.first}, через ${it.second}" } ?: "нет"}, " +
                    "зурхай ${zurkhai?.day(today)?.let { "${it.lunarDay}-е сутки" } ?: "нет"}")
            }
            delay(Duration.between(LocalDateTime.now(), today.plusDays(1).atStartOfDay()).toMillis() + 1000)
        }
    }
    val english = words.lang == Lang.en
    Column(Modifier.fillMaxSize()) {
        Text(words(P.calendar), color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
             modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
               verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Sagaalgan.next(today)?.let { (day, left) -> SagaalganCard(day, left) }
            zurkhai?.day(today, english)?.let { ZurkhaiCard(it) }
            YearsCard(today)
            Text(words(P.zurkhaiSource), color = palette.quiet, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

@Composable
private fun SagaalganCard(day: LocalDate, daysLeft: Int) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
               .background(Brush.verticalGradient(listOf(palette.gold.copy(alpha = 0.85f).compositeOver(Color.White), palette.gold)))
               .padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(words(P.sagaalgan), color = palette.onGold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(format(day, words), color = palette.onGold.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(words(P.eastYearOf, words.eastYear(EastYear.ofYear(day.year))), color = palette.onGold.copy(alpha = 0.85f), fontSize = 15.sp)
        Text(if (daysLeft == 0) words(P.sagaalganToday) else words(P.sagaalganLeft, "$daysLeft"),
             color = palette.onGold, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ZurkhaiCard(day: ZurkhaiDay) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.card).padding(14.dp),
           verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(words(P.zurkhaiToday), color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(words(P.lunarDay, "${day.lunarDay}"), color = palette.sky, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Advice(::scissors, if (day.haircutGood) palette.read else palette.quiet, words(P.haircut), day.haircut)
        Advice(::sun, palette.gold, words(P.favorable), day.favorable)
        Advice(::cloud, palette.quiet, words(P.unfavorable), day.unfavorable)
    }
}

@Composable
private fun Advice(icon: DrawScope.(Color) -> Unit, tint: Color, title: String, text: String) {
    val palette = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.padding(top = 4.dp).size(22.dp)) { icon(tint) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = palette.quiet, fontSize = 12.sp)
            Text(text, color = palette.ink, fontSize = 16.sp)
        }
    }
}

@Composable
private fun YearsCard(today: LocalDate) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val days = Sagaalgan.upcoming(today.year).filter { it >= today }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.card).padding(14.dp)) {
        Text(words(P.sagaalganYears), color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(bottom = 8.dp))
        days.forEachIndexed { index, day ->
            if (index > 0) HorizontalDivider(color = palette.plate)
            Row(Modifier.padding(vertical = 7.dp)) {
                Text(format(day, words), color = palette.ink, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text(words.eastYear(EastYear.ofYear(day.year)), color = palette.quiet, fontSize = 15.sp)
            }
        }
    }
}

private fun format(day: LocalDate, words: Words): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(words.locale).format(day)

private fun Color.compositeOver(background: Color): Color = Color(
    red = red * alpha + background.red * (1 - alpha), green = green * alpha + background.green * (1 - alpha),
    blue = blue * alpha + background.blue * (1 - alpha))

/** Ножницы: два кольца и скрещённые лезвия. */
private fun scissors(scope: DrawScope, color: Color) = with(scope) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.8.dp.toPx())
    drawCircle(color, w * 0.15f, Offset(w * 0.22f, h * 0.78f), style = stroke)
    drawCircle(color, w * 0.15f, Offset(w * 0.78f, h * 0.78f), style = stroke)
    drawLine(color, Offset(w * 0.32f, h * 0.66f), Offset(w * 0.80f, h * 0.06f), 1.8.dp.toPx())
    drawLine(color, Offset(w * 0.68f, h * 0.66f), Offset(w * 0.20f, h * 0.06f), 1.8.dp.toPx())
}

/** Солнце: круг и восемь лучей. */
private fun sun(scope: DrawScope, color: Color) = with(scope) {
    val c = center
    val r = size.minDimension
    drawCircle(color, r * 0.22f, c)
    for (i in 0 until 8) {
        val a = Math.toRadians(45.0 * i)
        val dx = Math.cos(a).toFloat()
        val dy = Math.sin(a).toFloat()
        drawLine(color, Offset(c.x + dx * r * 0.33f, c.y + dy * r * 0.33f), Offset(c.x + dx * r * 0.47f, c.y + dy * r * 0.47f), 1.8.dp.toPx())
    }
}

/** Облако: три круга на общем основании. */
private fun cloud(scope: DrawScope, color: Color) = with(scope) {
    val w = size.width
    val h = size.height
    drawCircle(color, w * 0.18f, Offset(w * 0.30f, h * 0.60f))
    drawCircle(color, w * 0.24f, Offset(w * 0.52f, h * 0.48f))
    drawCircle(color, w * 0.17f, Offset(w * 0.73f, h * 0.62f))
    drawRect(color, Offset(w * 0.30f, h * 0.60f), androidx.compose.ui.geometry.Size(w * 0.43f, h * 0.19f))
}
