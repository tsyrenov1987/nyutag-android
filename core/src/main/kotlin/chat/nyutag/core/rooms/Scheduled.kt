// Нютаг. Отложенные сообщения, как в Telegram. Сервер Matrix отложенную отправку не умеет, поэтому очередь
// живёт на телефоне, а отправляет её приложение, когда срок наступил и оно открыто. Номер транзакции выводится
// из номера сообщения: если приложение упало между отправкой и снятием с очереди, повтор сервер не задвоит.

package chat.nyutag.core.rooms

import chat.nyutag.json.JsonValue
import java.util.UUID

data class ScheduledMessage(
    val id: String = UUID.randomUUID().toString().lowercase(),
    /** Чья очередь: на телефоне может побывать не одна учётка. */
    val account: String,
    val roomID: String,
    val body: String,
    /** Когда отправить, мс. */
    val sendAt: Long,
) {
    val json: JsonValue get() = JsonValue.of("id" to id, "account" to account, "room" to roomID, "body" to body, "at" to sendAt)
    val transactionID: String get() = "scheduled-$id"

    companion object {
        fun from(json: JsonValue): ScheduledMessage? {
            val id = json["id"]?.string ?: return null
            val account = json["account"]?.string ?: return null
            val roomID = json["room"]?.string ?: return null
            val body = json["body"]?.string ?: return null
            val sendAt = json["at"]?.long ?: return null
            return ScheduledMessage(id, account, roomID, body, sendAt)
        }
    }
}

class ScheduledQueue(messages: List<ScheduledMessage> = emptyList()) {
    /** Ближайшие первыми. */
    val messages: List<ScheduledMessage> = messages.distinctBy { it.id }.sortedWith(compareBy({ it.sendAt }, { it.id }))

    /** Сохранённый вид очереди (JSON-массив). */
    val text: String get() = JsonValue.Arr(messages.map { it.json }).compact()

    fun inRoom(roomID: String, account: String): List<ScheduledMessage> = messages.filter { it.account == account && it.roomID == roomID }

    fun due(account: String, now: Long = System.currentTimeMillis()): List<ScheduledMessage> =
        messages.filter { it.account == account && it.sendAt <= now }

    /** Когда наступит ближайший срок этой учётки (мс). */
    fun nextDue(account: String): Long? = messages.firstOrNull { it.account == account }?.sendAt

    fun adding(message: ScheduledMessage): ScheduledQueue = ScheduledQueue(messages.filter { it.id != message.id } + message)

    fun removing(id: String): ScheduledQueue = ScheduledQueue(messages.filter { it.id != id })

    /** «Отправить сейчас»: срок переносится на этот момент, отправит обычный обход. */
    fun sendingNow(id: String, now: Long = System.currentTimeMillis()): ScheduledQueue =
        ScheduledQueue(messages.map { if (it.id == id) it.copy(sendAt = minOf(it.sendAt, now)) else it })

    /** Учётка вышла — её очередь больше некому отправить. */
    fun removingAccount(account: String): ScheduledQueue = ScheduledQueue(messages.filter { it.account != account })

    override fun equals(other: Any?): Boolean = other is ScheduledQueue && other.messages == messages
    override fun hashCode(): Int = messages.hashCode()
    override fun toString(): String = "ScheduledQueue($messages)"

    companion object {
        /** Битые записи пропускаем: очередь не должна теряться целиком из-за одной. */
        fun parse(text: String?): ScheduledQueue {
            val items = text?.let { runCatching { JsonValue.parse(it) }.getOrNull() }?.array ?: emptyList()
            return ScheduledQueue(items.mapNotNull(ScheduledMessage::from))
        }
    }
}
