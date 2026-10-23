// Нютаг. Встроенный набор стикеров с соёмбо: знак рисуется кодом (огонь, солнце, луна, наконечники, плашки,
// две рыбы и столбы по бокам), подписи — короткие бурятские приветствия. Рисунок тот же, что на iOS. Картинка
// загружается на сервер при первой отправке, адрес запоминается. Получатель видит обычный `m.sticker`.

package chat.nyutag.messenger.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kotlin.math.min

object Soyombo {
    /** Высота знака в своих единицах при ширине 100. */
    const val HEIGHT = 208f

    /** Знак в прямоугольнике `rect` (пропорции сохраняются, по центру). */
    fun draw(canvas: Canvas, rect: RectF, color: Int) {
        val scale = min(rect.width() / 100f, rect.height() / HEIGHT)
        canvas.save()
        canvas.translate(rect.centerX() - 50 * scale, rect.centerY() - HEIGHT / 2 * scale)
        canvas.scale(scale, scale)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

        // Огонь: три языка, средний выше.
        val fire = Path().apply {
            moveTo(20f, 40f)
            quadTo(17f, 24f, 29f, 13f)
            quadTo(34f, 22f, 39f, 30f)
            quadTo(38f, 12f, 50f, 0f)
            quadTo(62f, 12f, 61f, 30f)
            quadTo(66f, 22f, 71f, 13f)
            quadTo(83f, 24f, 80f, 40f)
            close()
        }
        canvas.drawPath(fire, fill)

        // Солнце.
        canvas.drawOval(RectF(39f, 43f, 61f, 65f), fill)

        // Луна: нижний серп.
        canvas.save()
        canvas.clipPath(Path().apply { addOval(RectF(32f, 52f, 68f, 88f), Path.Direction.CW) })
        val moon = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addOval(RectF(32f, 52f, 68f, 88f), Path.Direction.CW)
            addOval(RectF(32f, 46f, 68f, 82f), Path.Direction.CW)
        }
        canvas.drawPath(moon, fill)
        canvas.restore()

        // Наконечник, плашка, две рыбы, плашка, наконечник.
        triangle(canvas, 92f, fill)
        canvas.drawRect(18f, 113f, 82f, 121f, fill)
        fishes(canvas, 50f, 150f, 24f, fill)
        canvas.drawRect(18f, 179f, 82f, 187f, fill)
        triangle(canvas, 190f, fill)

        // Столбы по бокам.
        canvas.drawRect(0f, 92f, 11f, 208f, fill)
        canvas.drawRect(89f, 92f, 100f, 208f, fill)
        canvas.restore()
    }

    private fun triangle(canvas: Canvas, top: Float, fill: Paint) {
        canvas.drawPath(Path().apply {
            moveTo(18f, top)
            lineTo(82f, top)
            lineTo(50f, top + 18)
            close()
        }, fill)
    }

    /** Две рыбы в круге: залитая половина с изгибом, обводка и «глаза». */
    private fun fishes(canvas: Canvas, cx: Float, cy: Float, r: Float, fill: Paint) {
        val half = r / 2
        val fish = Path().apply {
            moveTo(cx, cy - r)
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 180f)
            arcTo(RectF(cx - half, cy, cx + half, cy + r), 90f, 180f)
            arcTo(RectF(cx - half, cy - r, cx + half, cy), 90f, -180f)
            close()
        }
        canvas.drawPath(fish, fill)
        val ring = Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawOval(RectF(cx - r, cy - r, cx + r, cy + r), ring)
        val eye = r / 6
        canvas.drawOval(RectF(cx - eye, cy - half - eye, cx + eye, cy - half + eye), fill)
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        canvas.drawOval(RectF(cx - eye, cy + half - eye, cx + eye, cy + half + eye), clear)
    }
}

/** Стикер встроенного набора: короткое имя и подпись (без подписи — один знак). */
data class SoyomboSticker(val shortcode: String, val caption: String?) {
    /** Подпись события: её видят в уведомлениях и в клиентах без картинок. */
    val body: String get() = caption ?: "Соёмбо"

    /** Картинка `side`×`side` на прозрачном фоне. */
    fun image(side: Int = SIDE): Bitmap {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = side.toFloat()
        if (caption == null) {
            Soyombo.draw(canvas, RectF(s * 0.06f, s * 0.04f, s * 0.94f, s * 0.96f), GOLD)
            return bitmap
        }
        Soyombo.draw(canvas, RectF(0f, s * 0.03f, s, s * 0.59f), GOLD)
        // Подпись на синей плашке: белые буквы, ширина под текст.
        val plate = RectF(s * 0.04f, s * 0.64f, s * 0.96f, s * 0.96f)
        canvas.drawRoundRect(plate, plate.height() / 2.4f, plate.height() / 2.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, 900, false)
            textAlign = Paint.Align.CENTER
        }
        var size = s * 0.13f
        do {
            text.textSize = size
            size -= 2
        } while (text.measureText(caption) > plate.width() * 0.9f && size > 20)
        val metrics = text.fontMetrics
        canvas.drawText(caption, plate.centerX(), plate.centerY() - (metrics.ascent + metrics.descent) / 2, text)
        return bitmap
    }

    fun png(): ByteArray = ByteArrayOutputStream().also { image().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    companion object {
        /** Версия рисунка: поменялся рисунок — меняем, и картинка загрузится заново. */
        const val VERSION = 1
        const val SIDE = 512

        val all = listOf(
            SoyomboSticker("soyombo", null),
            SoyomboSticker("sain-baina", "Сайн байна!"),
            SoyomboSticker("bayarlaa", "Баярлаа!"),
            SoyomboSticker("bayartai", "Баяртай!"),
            SoyomboSticker("sagaan-haraar", "Сагаан һараар!"),
        )

        private const val GOLD = 0xFFC9971C.toInt()
        private const val BLUE = 0xFF2347A6.toInt()
    }
}
