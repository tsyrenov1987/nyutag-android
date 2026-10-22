// Нютаг. Общие детали экранов: знак, аватар-«подушка», главная кнопка, заметка об ошибке, галочки.

package chat.nyutag.messenger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.core.rooms.ChatLine
import chat.nyutag.messenger.R

/** Знак Нютага, как на иконке: юрта-реплика с соёмбо на синем значка. */
@Composable
fun NyutagLogo(size: Dp = 96.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.225f))
            .background(Brush.verticalGradient(listOf(LogoBlue.copy(alpha = 0.85f), LogoBlue))),
        contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.logo_mark), contentDescription = "Нютаг",
              modifier = Modifier.fillMaxSize().padding(size * 0.2f))
    }
}

/** Аватар без картинки: первая буква на цвете из пятёрки; цвет постоянный по идентификатору. */
@Composable
fun Pillow(seed: String, title: String, size: Dp = 52.dp) {
    val tints = LocalPalette.current.avatarTints
    val tint = tints[(stableHash(seed) % tints.size.toUInt()).toInt()]
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.34f))
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.85f), tint))),
        contentAlignment = Alignment.Center) {
        Text(initial(title), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold)
    }
}

fun initial(title: String): String =
    title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "·"

/** FNV-1a, как на iOS: цвет аватара одинаков на обеих платформах. */
fun stableHash(text: String): UInt =
    text.toByteArray(Charsets.UTF_8).fold(2_166_136_261u) { hash, byte -> (hash xor byte.toUByte().toUInt()) * 16_777_619u }

/** Главная кнопка формы: синяя, с крутилкой, бледная, пока нажимать рано. */
@Composable
fun BigButton(title: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(18.dp))
            .background(palette.sky.copy(alpha = if (enabled) 1f else 0.45f))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            Box(Modifier.width(10.dp))
        }
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FailureNote(text: String) {
    val palette = LocalPalette.current
    Text(text, color = palette.ink, fontSize = 15.sp,
         modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
             .background(palette.gold.copy(alpha = 0.18f)).padding(12.dp))
}

/** Галочки своей реплики: одна — дошла до сервера, две зелёные — прочитали. */
@Composable
fun Ticks(delivery: ChatLine.Delivery, color: Color = LocalPalette.current.quiet, readColor: Color = LocalPalette.current.read) {
    if (delivery == ChatLine.Delivery.NONE) return
    val read = delivery == ChatLine.Delivery.READ
    val tint = if (read) readColor else color
    Canvas(Modifier.size(width = if (read) 18.dp else 13.dp, height = 10.dp)) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun tick(dx: Float) {
            val path = Path().apply {
                moveTo(dx + 1.dp.toPx(), size.height * 0.55f)
                lineTo(dx + 4.5.dp.toPx(), size.height - 1.dp.toPx())
                lineTo(dx + 12.dp.toPx(), 1.dp.toPx())
            }
            drawPath(path, tint, style = stroke)
        }
        tick(0f)
        if (read) tick(5.dp.toPx())
    }
}

/** Замок шифрованного чата. */
@Composable
fun LockMark(color: Color, size: Dp = 10.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawArc(color, 180f, 180f, useCenter = false, topLeft = Offset(w * 0.22f, h * 0.04f),
                size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.6f), style = Stroke(1.4.dp.toPx()))
        drawRoundRect(color, topLeft = Offset(w * 0.1f, h * 0.42f), size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.56f),
                      cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
    }
}
