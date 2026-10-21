// Нютаг. Что показать в уведомлении и чем является реплика для списка чатов и ленты. Подписи («Фото», «Звонок»)
// выбирает приложение на языке пользователя. Чтение события с сервера и расшифровка — в слое пушей.

package chat.nyutag.core.push

import chat.nyutag.core.calls.CallEventType
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType

data class PushPreview(
    val roomID: String,
    val eventID: String,
    val sender: String?,
    val senderName: String?,
    val roomName: String?,
    val isDirect: Boolean,
    val kind: Kind,
) {
    sealed class Kind {
        data class Text(val text: String) : Kind()
        object Image : Kind()
        object Video : Kind()
        object Audio : Kind()
        object File : Kind()
        object Location : Kind()
        object Sticker : Kind()
        object Poll : Kind()
        object Call : Kind()
        /** Служебный сигнал звонка (ring/offer/answer/end): уведомление не показывать. */
        object Signal : Kind()
        /** Нас пригласили в комнату. */
        object Invite : Kind()
        /** Зашифровано, а ключа сессии на устройстве ещё нет: показать нейтральное «Новое сообщение». */
        object Locked : Kind()
        object Other : Kind()
    }
}

object PushReader {
    fun kind(event: Event, me: String): PushPreview.Kind {
        when (event.type) {
            EventType.MEMBER -> return if (event.stateKey == me && event.content["membership"]?.string == "invite") PushPreview.Kind.Invite else PushPreview.Kind.Other
            EventType.STICKER -> return PushPreview.Kind.Sticker
            "m.poll.start", "org.matrix.msc3381.poll.start" -> return PushPreview.Kind.Poll
            "m.call.invite", EventType.CALL -> return PushPreview.Kind.Call
            CallEventType.RING, CallEventType.OFFER, CallEventType.ANSWER, CallEventType.END -> return PushPreview.Kind.Signal
            EventType.MESSAGE -> {}
            else -> return PushPreview.Kind.Other
        }
        // Правка: показываем новый текст, а не «* старый».
        val content = event.content["m.new_content"] ?: event.content
        return when (content["msgtype"]?.string) {
            "m.image" -> PushPreview.Kind.Image
            "m.video" -> PushPreview.Kind.Video
            "m.audio" -> PushPreview.Kind.Audio
            "m.file" -> PushPreview.Kind.File
            "m.location" -> PushPreview.Kind.Location
            else -> {
                val body = content["body"]?.string ?: return PushPreview.Kind.Other
                PushPreview.Kind.Text(withoutReplyQuote(body))
            }
        }
    }

    /** Старые клиенты кладут в ответ цитату `> <@кто> текст` и пустую строку перед самим ответом. */
    fun withoutReplyQuote(body: String): String {
        val lines = body.split("\n")
        if (lines.firstOrNull()?.startsWith("> ") != true) return body
        val rest = lines.dropWhile { it.startsWith(">") }.dropWhile { it.isEmpty() }
        return if (rest.isEmpty()) body else rest.joinToString("\n")
    }
}
