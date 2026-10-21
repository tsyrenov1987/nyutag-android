// Нютаг. Реплики, которые ссылаются на другие: правка своей реплики и реакция. Плюс кто сейчас набирает текст
// (`m.typing` в эфемерных событиях синхронизации).

package chat.nyutag.core.rooms

import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue

object Relations {
    /** Правка своей текстовой реплики. Старые клиенты покажут `body` со звёздочкой, новые подставят `m.new_content`. */
    fun edit(eventID: String, text: String): JsonValue = JsonValue.of(
        "msgtype" to "m.text", "body" to "* $text",
        "m.new_content" to JsonValue.of("msgtype" to "m.text", "body" to text),
        "m.relates_to" to JsonValue.of("rel_type" to "m.replace", "event_id" to eventID),
    )

    /** Реакция-аннотация на реплику. */
    fun reaction(eventID: String, key: String): JsonValue = JsonValue.of(
        "m.relates_to" to JsonValue.of("rel_type" to "m.annotation", "event_id" to eventID, "key" to key),
    )

    /** Кто набирает текст в комнате по последнему `m.typing`, кроме нас. `null` — в этом ответе новостей нет. */
    fun typing(ephemeral: List<Event>, me: String): List<String>? {
        val event = ephemeral.lastOrNull { it.type == EventType.TYPING } ?: return null
        return (event.content["user_ids"]?.array ?: emptyList()).mapNotNull { it.string }.filter { it != me }
    }
}
