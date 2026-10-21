// Нютаг. Пересылка: из уже расшифрованной реплики собираем новую — без ответа, правок и цитаты-подстановки
// старых клиентов, с пометкой «переслано». Вложение уходит той же ссылкой и тем же ключом, заново не грузится.
// Одноразовое фото, опросы и звонки не пересылаются.

package chat.nyutag.core.rooms

import chat.nyutag.core.media.Attachment
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue

object Forwarding {
    const val FORWARDED_KEY = "chat.nyutag.forwarded"

    internal val msgtypes: Set<String> = setOf("m.text", "m.notice", "m.emote", "m.image", "m.file", "m.video", "m.audio", "m.location")

    /** Тип и содержимое новой реплики. */
    data class Forwarded(val type: String, val content: JsonValue)

    /** `replaced` — `m.new_content` последней правки, если она была. `null` — пересылать нельзя. */
    fun content(event: Event, replaced: JsonValue? = null): Forwarded? {
        if (event.isRedacted) return null
        if (event.type != EventType.MESSAGE && event.type != EventType.STICKER) return null
        val content = LinkedHashMap((replaced ?: event.content).obj ?: return null)
        if (event.type == EventType.MESSAGE) {
            val msgtype = content["msgtype"]?.string ?: return null
            if (msgtype !in msgtypes) return null
        }
        if (content[Attachment.VIEW_ONCE_KEY]?.bool == true) return null
        val wasReply = event.content.at("m.relates_to", "m.in_reply_to") != null
        content.remove("m.relates_to")
        content.remove("m.new_content")
        if (wasReply && replaced == null) {
            content["body"]?.string?.let { content["body"] = JsonValue.Str(stripReplyFallback(it)) }
            content.remove("format")
            content.remove("formatted_body")
        }
        content[FORWARDED_KEY] = JsonValue.Bool(true)
        return Forwarded(event.type, JsonValue.Obj(content))
    }

    /** Старые клиенты кладут цитату в начало текста: строки «> …» и пустая строка после них. */
    internal fun stripReplyFallback(body: String): String {
        val lines = body.split("\n")
        if (lines.firstOrNull()?.startsWith("> ") != true) return body
        var rest = lines.dropWhile { it.startsWith(">") }
        if (rest.firstOrNull()?.isEmpty() == true) rest = rest.drop(1)
        return if (rest.isEmpty()) body else rest.joinToString("\n")
    }
}
