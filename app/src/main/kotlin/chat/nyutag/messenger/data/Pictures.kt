// Нютаг. Картинки без сторонних библиотек: раскодировать с уменьшением (большое фото не должно съесть память),
// повернуть по EXIF, вырезать квадрат для аватара, ужать фото для обоев.

package chat.nyutag.messenger.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

object Pictures {
    /** Раскодировать так, чтобы длинная сторона была не больше `maxPixels` (шаг уменьшения — степень двойки). */
    fun decode(data: ByteArray, maxPixels: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Снимок → квадрат `side` по центру → JPEG. `null` — не картинка. */
    fun squareJPEG(data: ByteArray, side: Int = 512): ByteArray? {
        val image = upright(data, side * 2) ?: return null
        val edge = min(image.width, image.height)
        val square = Bitmap.createBitmap(image, (image.width - edge) / 2, (image.height - edge) / 2, edge, edge)
        val scaled = if (edge > side) Bitmap.createScaledBitmap(square, side, side, true) else square
        return jpeg(scaled)
    }

    /** Фото для обоев: длинная сторона не больше `longest`, иначе зря заняло бы место и память. */
    fun shrunkJPEG(data: ByteArray, longest: Int = 2000): ByteArray? {
        val image = upright(data, longest) ?: return null
        val side = max(image.width, image.height)
        val scaled = if (side > longest) {
            val scale = longest.toFloat() / side
            Bitmap.createScaledBitmap(image, (image.width * scale).toInt(), (image.height * scale).toInt(), true)
        } else {
            image
        }
        return jpeg(scaled)
    }

    /** Своя картинка в стикеры: длинная сторона до 512, PNG с прозрачностью. Ширина и высота — для набора. */
    fun stickerPNG(data: ByteArray, longest: Int = 512): Triple<ByteArray, Int, Int>? {
        val image = upright(data, longest) ?: return null
        val side = max(image.width, image.height)
        val scaled = if (side > longest) {
            val scale = longest.toFloat() / side
            Bitmap.createScaledBitmap(image, (image.width * scale).toInt(), (image.height * scale).toInt(), true)
        } else {
            image
        }
        val png = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return Triple(png, scaled.width, scaled.height)
    }

    /** В JPEG прозрачность пропадает и становится чёрной: прозрачные места заливаем белым. */
    private fun jpeg(image: Bitmap): ByteArray {
        val flat = if (image.hasAlpha()) {
            Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also {
                val canvas = android.graphics.Canvas(it)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(image, 0f, 0f, null)
            }
        } else {
            image
        }
        return ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    /** Камера пишет снимок как есть и кладёт поворот в EXIF: без поворота фото легло бы набок. */
    private fun upright(data: ByteArray, maxPixels: Int): Bitmap? {
        val image = decode(data, maxPixels) ?: return null
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return image
        }
        return Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
    }
}
