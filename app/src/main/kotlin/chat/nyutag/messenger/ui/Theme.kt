// Нютаг. Свой облик, как на iOS: синий неба и золото соёмбо со значка, молочный фон, карточки вместо
// линеек-разделителей, аватары-«подушки», счётчики золотые. Красного в интерфейсе нет вовсе.

package chat.nyutag.messenger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
class Palette(
    val sky: Color, val gold: Color, val onGold: Color, val milk: Color, val card: Color, val ink: Color,
    val quiet: Color, val read: Color, val readOnBlue: Color,
    /** Подложка служебных плашек и разделителей дней: темнее фона, чтобы не сливаться с ним. */
    val plate: Color,
    /** Свои пузыри: синий неба с белым текстом; чужие — белая карточка. */
    val mine: Color,
    /** Пять цветов для аватаров без картинки; цвет выбирается по идентификатору и не меняется. */
    val avatarTints: List<Color>,
)

private fun rgb(hex: Long) = Color(0xFF000000 or hex)

private val light = Palette(
    sky = rgb(0x2347A6), gold = rgb(0xC9971C), onGold = rgb(0x2A1F05), milk = rgb(0xF4F0E6), card = rgb(0xFFFFFF),
    ink = rgb(0x18202E), quiet = rgb(0x6B7280), read = rgb(0x1F8A5B), readOnBlue = rgb(0x8FF0C0), plate = rgb(0xE4DDCB),
    mine = rgb(0x2347A6),
    avatarTints = listOf(rgb(0x2347A6), rgb(0x1F7A57), rgb(0xB5761A), rgb(0x7A3E9D), rgb(0x2E7D8C)),
)

private val dark = Palette(
    sky = rgb(0x6F95F2), gold = rgb(0xE2B84A), onGold = rgb(0x1C1404), milk = rgb(0x0D121C), card = rgb(0x172031),
    ink = rgb(0xECEFF5), quiet = rgb(0x9AA3B5), read = rgb(0x4FD39A), readOnBlue = rgb(0x8FF0C0), plate = rgb(0x222C40),
    mine = rgb(0x2F55B8),
    avatarTints = listOf(rgb(0x3F66CC), rgb(0x2E9B70), rgb(0xC98C2E), rgb(0x9459B8), rgb(0x3C9AAB)),
)

/** Синий значка (фон знака Нютага). */
val LogoBlue = Color(0xFF1C6FB3)

val LocalPalette = staticCompositionLocalOf { light }

@Composable
fun NyutagTheme(content: @Composable () -> Unit) {
    val night = isSystemInDarkTheme()
    val palette = if (night) dark else light
    val scheme = if (night) {
        darkColorScheme(primary = palette.sky, secondary = palette.gold, background = palette.milk,
                        surface = palette.card, onBackground = palette.ink, onSurface = palette.ink)
    } else {
        lightColorScheme(primary = palette.sky, secondary = palette.gold, background = palette.milk,
                         surface = palette.card, onBackground = palette.ink, onSurface = palette.ink)
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
