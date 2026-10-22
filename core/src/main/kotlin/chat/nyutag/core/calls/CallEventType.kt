// Нютаг. Типы событий звонков: формат на проводе общий с iOS-клиентом.

package chat.nyutag.core.calls

object CallEventType {
    const val RING = "chat.nyutag.ring"
    const val OFFER = "chat.nyutag.call.offer"
    const val ANSWER = "chat.nyutag.call.answer"
    const val END = "chat.nyutag.call.end"
    const val JOIN = "chat.nyutag.call.join"
    const val LEAVE = "chat.nyutag.call.leave"
    /** Сообщение устройству (to-device), не событие комнаты: в `all` его нет, из ленты не берём. */
    const val RINGING = "chat.nyutag.call.ringing"

    val all: Set<String> = setOf(RING, OFFER, ANSWER, END, JOIN, LEAVE)
}
