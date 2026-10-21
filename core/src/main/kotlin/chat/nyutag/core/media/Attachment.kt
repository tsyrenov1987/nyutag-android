// Нютаг. Вложение реплики (m.image, m.file, m.video, m.audio): откуда качать, как называется, какого размера.
// В зашифрованной комнате адрес и ключ лежат в `file`, в открытой — адрес в `url`. Миниатюра — по тем же правилам
// в `info.thumbnail_file` или `info.thumbnail_url`.

package chat.nyutag.core.media

import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue

data class Attachment(
    /** m.image, m.file, m.video, m.audio или m.sticker. */
    val msgtype: String = "m.file",
    val source: Source,
    val thumbnail: Source? = null,
    /** Имя файла: `filename`, а если его нет — `body`. */
    val name: String,
    val mimetype: String? = null,
    val size: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Исчезающее фото: метка `chat.nyutag.view_once` в содержимом, формат общий с iOS. */
    val viewOnce: Boolean = false,
    /** Длительность видео или звука, мс. */
    val duration: Int? = null,
    /** Голосовое (`org.matrix.msc3245.voice`) и его волна: до сотни точек 0…1024 (`org.matrix.msc1767.audio`). */
    val isVoice: Boolean = false,
    val waveform: List<Int> = emptyList(),
) {
    sealed class Source {
        data class Plain(val address: MXC) : Source()
        data class Sealed(val file: EncryptedFile) : Source()

        /** Адрес на сервере в любом случае. */
        val mxc: MXC? get() = when (this) {
            is Plain -> address
            is Sealed -> file.mxc
        }
    }

    /** Миниатюра к отправке: своя картинка (JPEG) по тем же правилам шифрования, что и сам файл. */
    data class Preview(
        val source: Source,
        val mimetype: String = "image/jpeg",
        val size: Int,
        val width: Int? = null,
        val height: Int? = null,
    )

    /** Картинка, которую показываем в пузыре. GIF и SVG — как файлы: первый кадр и чужой рендер обманули бы. */
    val isImage: Boolean get() = msgtype == "m.image" && (mimetype ?: "") !in setOf("image/gif", "image/svg+xml")

    /** Видео в пузыре: миниатюра со значком воспроизведения, по нажатию — проигрыватель. */
    val isVideo: Boolean get() = msgtype == "m.video"

    val isSticker: Boolean get() = msgtype == EventType.STICKER

    companion object {
        const val VIEW_ONCE_KEY = "chat.nyutag.view_once"
        internal const val VOICE_KEY = "org.matrix.msc3245.voice"
        internal const val AUDIO_KEY = "org.matrix.msc1767.audio"

        internal val kinds: Set<String> = setOf("m.image", "m.file", "m.video", "m.audio")

        /** Вложение из содержимого реплики; у текста и у битого содержимого — `null`. */
        fun read(content: JsonValue): Attachment? {
            val msgtype = content["msgtype"]?.string ?: return null
            if (msgtype !in kinds) return null
            val source = locate(content["file"], content["url"]) ?: return null
            val info = content["info"]
            val audio = content[AUDIO_KEY]
            return Attachment(
                msgtype = msgtype, source = source,
                thumbnail = locate(info?.get("thumbnail_file"), info?.get("thumbnail_url")),
                name = content["filename"]?.string ?: content["body"]?.string ?: "",
                mimetype = info?.get("mimetype")?.string, size = info?.get("size")?.int,
                width = info?.get("w")?.int, height = info?.get("h")?.int,
                viewOnce = content[VIEW_ONCE_KEY]?.bool == true,
                duration = info?.get("duration")?.int ?: audio?.get("duration")?.int,
                isVoice = msgtype == "m.audio" && content[VOICE_KEY] != null,
                waveform = (audio?.get("waveform")?.array ?: emptyList()).mapNotNull { it.int?.coerceIn(0, 1024) },
            )
        }

        /** Содержимое реплики с вложением. `body` — имя файла: старые клиенты покажут хотя бы его.
         *  У видео — миниатюра (`thumbnail_file` или `thumbnail_url` c `thumbnail_info`) и длительность в мс. */
        fun content(
            source: Source, name: String, mimetype: String, size: Int, width: Int? = null, height: Int? = null,
            viewOnce: Boolean = false, preview: Preview? = null, duration: Int? = null, voice: List<Int>? = null,
        ): JsonValue {
            val info = LinkedHashMap<String, JsonValue>()
            info["mimetype"] = JsonValue.Str(mimetype)
            info["size"] = JsonValue.Int(size.toLong())
            if (width != null && height != null) {
                info["w"] = JsonValue.Int(width.toLong())
                info["h"] = JsonValue.Int(height.toLong())
            }
            if (duration != null) info["duration"] = JsonValue.Int(duration.toLong())
            if (preview != null) {
                val thumbInfo = LinkedHashMap<String, JsonValue>()
                thumbInfo["mimetype"] = JsonValue.Str(preview.mimetype)
                thumbInfo["size"] = JsonValue.Int(preview.size.toLong())
                if (preview.width != null && preview.height != null) {
                    thumbInfo["w"] = JsonValue.Int(preview.width.toLong())
                    thumbInfo["h"] = JsonValue.Int(preview.height.toLong())
                }
                info["thumbnail_info"] = JsonValue.Obj(thumbInfo)
                when (val thumb = preview.source) {
                    is Source.Plain -> info["thumbnail_url"] = JsonValue.Str(thumb.address.toString())
                    is Source.Sealed -> info["thumbnail_file"] = thumb.file.json
                }
            }
            val content = LinkedHashMap<String, JsonValue>()
            content["msgtype"] = JsonValue.Str(msgtypeFor(mimetype))
            content["body"] = JsonValue.Str(name)
            content["filename"] = JsonValue.Str(name)
            content["info"] = JsonValue.Obj(info)
            if (viewOnce) content[VIEW_ONCE_KEY] = JsonValue.Bool(true)
            if (voice != null) {
                // Голосовое по MSC3245: метка и волна; остальные клиенты покажут обычный звук.
                content[VOICE_KEY] = JsonValue.Obj(emptyMap())
                val audio = LinkedHashMap<String, JsonValue>()
                audio["waveform"] = JsonValue.Arr(voice.map { JsonValue.Int(it.toLong()) })
                if (duration != null) audio["duration"] = JsonValue.Int(duration.toLong())
                content[AUDIO_KEY] = JsonValue.Obj(audio)
            }
            when (source) {
                is Source.Plain -> content["url"] = JsonValue.Str(source.address.toString())
                is Source.Sealed -> content["file"] = source.file.json
            }
            return JsonValue.Obj(content)
        }

        /** Вид реплики по типу файла. */
        fun msgtypeFor(mimetype: String): String = when {
            mimetype.startsWith("image/") -> "m.image"
            mimetype.startsWith("video/") -> "m.video"
            mimetype.startsWith("audio/") -> "m.audio"
            else -> "m.file"
        }

        /** Стикер (`m.sticker`): картинка без пузыря и без ключа, подпись в `body`. */
        fun sticker(content: JsonValue): Attachment? {
            val mxc = content["url"]?.string?.let(MXC::parse) ?: return null
            val info = content["info"]
            return Attachment(
                msgtype = EventType.STICKER, source = Source.Plain(mxc),
                thumbnail = locate(info?.get("thumbnail_file"), info?.get("thumbnail_url")),
                name = content["body"]?.string ?: "", mimetype = info?.get("mimetype")?.string, size = info?.get("size")?.int,
                width = info?.get("w")?.int, height = info?.get("h")?.int,
            )
        }

        internal fun locate(file: JsonValue?, url: JsonValue?): Source? {
            val sealed = EncryptedFile.from(file)
            if (sealed != null && sealed.mxc != null) return Source.Sealed(sealed)
            return url?.string?.let(MXC::parse)?.let { Source.Plain(it) }
        }
    }
}
