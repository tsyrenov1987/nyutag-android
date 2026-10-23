// Нютаг. Вложение к отправке: снимок пережат до 2560 px в JPEG (заодно уходят геометка и прочие EXIF),
// ролик переложен в новый MP4 без метаданных и с кадром-миниатюрой, файл — как есть. Формат реплики общий с iOS.

package chat.nyutag.messenger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max

/** Готовый к отправке файл: картинка уже пережата, у неё известны размеры. */
class OutgoingFile(
    val data: ByteArray,
    val name: String,
    val mimetype: String,
    val width: Int? = null,
    val height: Int? = null,
    /** Исчезающее фото: один просмотр у получателя. */
    val viewOnce: Boolean = false,
    /** У видео: кадр-миниатюра (JPEG). */
    val thumbnail: Thumbnail? = null,
    /** Длительность видео или голосового, мс. */
    val duration: Int? = null,
    /** Голосовое: волна 0…1024 (`m.audio` с пометкой голосового). */
    val voice: List<Int>? = null,
) {
    class Thumbnail(val data: ByteArray, val width: Int, val height: Int)

    /** Что пошло не так при подготовке. */
    class TooBig : Exception()

    companion object {
        /** Сервер принимает до 25 МБ за запрос. */
        const val LIMIT = 24 * 1024 * 1024

        /** Снимок из галереи: длинная сторона не больше 2560 px, JPEG, поворот по EXIF уже применён. */
        fun photo(context: Context, uri: Uri, name: String, viewOnce: Boolean = false): OutgoingFile? {
            val raw = read(context, uri) ?: return null
            val jpeg = Pictures.shrunkJPEG(raw, 2560) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            return OutgoingFile(jpeg, "$name.jpg", "image/jpeg", bounds.outWidth, bounds.outHeight, viewOnce = viewOnce)
        }

        /** Файл из «Файлов». Тип — от системы или по расширению; неизвестный уходит как поток байт. */
        fun file(context: Context, uri: Uri): OutgoingFile? {
            val name = displayName(context, uri) ?: "file"
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
            if (size > LIMIT) throw TooBig()
            val data = read(context, uri) ?: return null
            if (data.size > LIMIT) throw TooBig()
            val type = context.contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            var width: Int? = null
            var height: Int? = null
            if (type.startsWith("image/")) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth > 0) { width = bounds.outWidth; height = bounds.outHeight }
            }
            return OutgoingFile(data, name, type, width, height)
        }

        /** Ролик: дорожки перекладываются в новый MP4 без пережатия — метаданные (геометка, модель телефона)
         *  туда не попадают. Кадр-миниатюра до 800 px, длительность и размеры с учётом поворота. */
        fun video(context: Context, uri: Uri, name: String): OutgoingFile? {
            val source = File(context.cacheDir, "video-${UUID.randomUUID()}")
            val out = File(context.cacheDir, "video-${UUID.randomUUID()}.mp4")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { input.copyTo(it) } } ?: return null
                if (source.length() > LIMIT * 3L) throw TooBig()
                if (!remux(source, out)) return null
                if (out.length() > LIMIT) throw TooBig()
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(out.path)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    if (rotation % 180 != 0) width = height.also { height = width }
                    val at = minOf(500L, (duration ?: 0) / 2L) * 1000
                    val frame = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    val thumbnail = frame?.let { scaled(it, 800) }?.let { picture ->
                        val bytes = ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                        Thumbnail(bytes, picture.width, picture.height)
                    }
                    return OutgoingFile(out.readBytes(), "$name.mp4", "video/mp4", width, height,
                                        thumbnail = thumbnail, duration = duration)
                } finally {
                    retriever.release()
                }
            } finally {
                source.delete()
                out.delete()
            }
        }

        /** Голосовое: AAC в m4a, как у iOS. */
        fun voice(data: ByteArray, duration: Int, waveform: List<Int>): OutgoingFile =
            OutgoingFile(data, "voice-${System.currentTimeMillis() / 1000}.m4a", "audio/mp4", duration = duration, voice = waveform)

        private fun remux(source: File, out: File): Boolean {
            val extractor = MediaExtractor()
            var muxer: MediaMuxer? = null
            try {
                extractor.setDataSource(source.path)
                muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val tracks = HashMap<Int, Int>()
                var bufferSize = 1 shl 20
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                    if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                        muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                    }
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        bufferSize = max(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                    extractor.selectTrack(index)
                    tracks[index] = muxer.addTrack(format)
                }
                if (tracks.isEmpty()) return false
                // Поворот у части телефонов лежит только в метаданных контейнера.
                val retriever = MediaMetadataRetriever()
                runCatching {
                    retriever.setDataSource(source.path)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                }.getOrNull()?.takeIf { it != 0 }?.let { muxer.setOrientationHint(it) }
                retriever.release()
                muxer.start()
                val buffer = ByteBuffer.allocate(bufferSize)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val track = extractor.sampleTrackIndex
                    if (track < 0) break
                    info.size = extractor.readSampleData(buffer, 0)
                    if (info.size < 0) break
                    info.offset = 0
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    tracks[track]?.let { muxer.writeSampleData(it, buffer, info) }
                    extractor.advance()
                }
                muxer.stop()
                return true
            } catch (e: Exception) {
                return false
            } finally {
                runCatching { muxer?.release() }
                extractor.release()
            }
        }

        private fun scaled(image: Bitmap, longest: Int): Bitmap {
            val side = max(image.width, image.height)
            if (side <= longest) return image
            val scale = longest.toFloat() / side
            return Bitmap.createScaledBitmap(image, (image.width * scale).toInt(), (image.height * scale).toInt(), true)
        }

        private fun read(context: Context, uri: Uri): ByteArray? =
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

        fun displayName(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.replace('/', '_')
    }
}
