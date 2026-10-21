// Нютаг. Вид пуша по событию: картинка, правка, приглашение, сигналы звонка, цитата ответа.

package chat.nyutag.core

import chat.nyutag.core.calls.CallEventType
import chat.nyutag.core.push.PushPreview
import chat.nyutag.core.push.PushReader
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PushPreviewTest {
    private val alice = "@alice:nyutag.chat"
    private val bob = "@bob:nyutag.chat"

    private fun kind(type: String, content: JsonValue, stateKey: String? = null): PushPreview.Kind =
        PushReader.kind(Event(type = type, sender = alice, stateKey = stateKey, content = content), bob)

    @Test fun kinds() {
        assertEquals(PushPreview.Kind.Image, kind(EventType.MESSAGE, JsonValue.of("msgtype" to "m.image", "body" to "a.jpg")))
        assertEquals(PushPreview.Kind.Audio, kind(EventType.MESSAGE, JsonValue.of("msgtype" to "m.audio", "body" to "v.ogg")))
        assertEquals(PushPreview.Kind.Text("новое"), kind(EventType.MESSAGE, JsonValue.of(
            "msgtype" to "m.text", "body" to "* старое", "m.new_content" to JsonValue.of("msgtype" to "m.text", "body" to "новое"),
        )))
        assertEquals(PushPreview.Kind.Invite, kind(EventType.MEMBER, JsonValue.of("membership" to "invite"), stateKey = bob))
        assertEquals(PushPreview.Kind.Other, kind(EventType.MEMBER, JsonValue.of("membership" to "invite"), stateKey = "@c:nyutag.chat"))
        assertEquals(PushPreview.Kind.Call, kind(EventType.CALL, JsonValue.Obj(emptyMap())))
        assertEquals(PushPreview.Kind.Signal, kind(CallEventType.RING, JsonValue.of("call_id" to "c")))
        assertEquals(PushPreview.Kind.Signal, kind(CallEventType.ANSWER, JsonValue.of("call_id" to "c")))
        assertEquals(PushPreview.Kind.Signal, kind(CallEventType.END, JsonValue.of("call_id" to "c")))
        assertEquals(PushPreview.Kind.Sticker, kind("m.sticker", JsonValue.of("body" to "кот")))
        assertEquals(PushPreview.Kind.Other, kind(EventType.MESSAGE, JsonValue.of("msgtype" to "m.text")))
        assertEquals("> цитата без ответа", PushReader.withoutReplyQuote("> цитата без ответа"))
        assertEquals("просто текст", PushReader.withoutReplyQuote("просто текст"))
        assertEquals("дома", PushReader.withoutReplyQuote("> <@bob:nyutag.chat> где ты\n\nдома"))
    }
}
