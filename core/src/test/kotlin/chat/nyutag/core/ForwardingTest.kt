// Нютаг. Пересылка: что уходит в новый чат и что не пересылается.

package chat.nyutag.core

import chat.nyutag.core.media.Attachment
import chat.nyutag.core.rooms.Forwarding
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ForwardingTest {
    private fun event(content: JsonValue, redacted: Boolean = false): Event = event(EventType.MESSAGE, content, redacted)

    private fun event(type: String, content: JsonValue, redacted: Boolean = false): Event = Event(
        eventID = "\$e", type = type, sender = "@a:x", originServerTS = 1, content = content,
        unsigned = if (redacted) JsonValue.of("redacted_because" to JsonValue.Obj(emptyMap())) else null, roomID = "!r:x",
    )

    @Test fun text() {
        val source = event(JsonValue.of(
            "msgtype" to "m.text", "body" to "> <@b:x> привет\n> как дела\n\nвсё хорошо",
            "format" to "org.matrix.custom.html", "formatted_body" to "<mx-reply>…</mx-reply>всё хорошо",
            "m.relates_to" to JsonValue.of("m.in_reply_to" to JsonValue.of("event_id" to "\$q")),
        ))
        val out = requireNotNull(Forwarding.content(source))
        assertEquals(EventType.MESSAGE, out.type)
        assertEquals(JsonValue.of("msgtype" to "m.text", "body" to "всё хорошо", Forwarding.FORWARDED_KEY to true), out.content)
    }

    @Test fun edited() {
        val source = event(JsonValue.of("msgtype" to "m.text", "body" to "опечатка"))
        val out = requireNotNull(Forwarding.content(source, JsonValue.of("msgtype" to "m.text", "body" to "без опечатки")))
        assertEquals("без опечатки", out.content["body"]?.string)
    }

    @Test fun media() {
        val file = JsonValue.of("url" to "mxc://x/1", "key" to JsonValue.of("k" to "секрет"), "iv" to "iv", "hashes" to JsonValue.of("sha256" to "h"), "v" to "v2")
        val photo = requireNotNull(Forwarding.content(event(JsonValue.of("msgtype" to "m.image", "body" to "фото.jpg", "file" to file))))
        assertEquals(file, photo.content["file"])
        val sticker = requireNotNull(Forwarding.content(event(EventType.STICKER, JsonValue.of("body" to "Сайн байна!", "url" to "mxc://x/2", "info" to JsonValue.Obj(emptyMap())))))
        assertEquals(EventType.STICKER, sticker.type)
        assertEquals("mxc://x/2", sticker.content["url"]?.string)
    }

    @Test fun refused() {
        assertNull(Forwarding.content(event(JsonValue.of("msgtype" to "m.image", "body" to "x", "url" to "mxc://x/1", Attachment.VIEW_ONCE_KEY to true))))
        assertNull(Forwarding.content(event(JsonValue.of("msgtype" to "m.text", "body" to "x"), redacted = true)))
        assertNull(Forwarding.content(event("m.poll.start", JsonValue.of("body" to "?"))))
        assertNull(Forwarding.content(event(JsonValue.of("msgtype" to "m.key.verification.request", "body" to "x"))))
    }

    @Test fun fallback() {
        assertEquals("b", Forwarding.stripReplyFallback("> a\nb"))
        assertEquals("a\n> b", Forwarding.stripReplyFallback("a\n> b"))
        assertEquals("> только цитата", Forwarding.stripReplyFallback("> только цитата"))
    }
}
