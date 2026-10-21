// Нютаг. Автоудаление сообщений в чате, как в Telegram. Правило — состояние комнаты `chat.nyutag.autodelete`
// `{"ttl": секунды, "since": мс}`, формат общий с iOS. Содержимое шифрованных чатов сервер не видит, поэтому
// удаляют клиенты: истёкшее прячут все, а автор ещё и удаляет свои реплики на сервере.
// Реплики, отправленные до включения, не трогаем; служебные плашки (вход, переименование) остаются.

package chat.nyutag.core.rooms

import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue

class AutoDelete(ttl: Int = 0, since: Long = 0) {
    /** Срок жизни, с. */
    val ttl: Int = maxOf(0, ttl)
    /** С какого момента действует, мс. */
    val since: Long = maxOf(0, since)

    val json: JsonValue get() = JsonValue.of("ttl" to ttl, "since" to since)
    val isOn: Boolean get() = ttl > 0

    /** Чужой нестандартный срок показываем ближайшим вариантом сверху. */
    val choice: Int get() = if (!isOn) 0 else choices.firstOrNull { it >= ttl } ?: MONTH

    /** Время сервера бывает округлено до секунды вниз — к `since` секунда запаса. */
    fun isExpired(ts: Long, now: Long = System.currentTimeMillis()): Boolean =
        isOn && ts + 999 >= since && ts + ttl * 1000L <= now

    /** Когда истечёт ближайшая из показанных реплик (мс): к этому времени ленту надо пересобрать. */
    fun nextExpiry(items: List<FeedItem>): Long? {
        if (!isOn) return null
        val first = items.filter { !it.isNotice && it.ts + 999 >= since }.minOfOrNull { it.ts } ?: return null
        return first + ttl * 1000L
    }

    /** Лента без истёкших реплик. */
    fun visible(items: List<FeedItem>, now: Long = System.currentTimeMillis()): List<FeedItem> =
        if (!isOn) items else items.filter { it.isNotice || !isExpired(it.ts, now) }

    /** Свои истёкшие реплики, ещё не удалённые на сервере. */
    fun ownToDelete(items: List<FeedItem>, now: Long = System.currentTimeMillis()): List<String> =
        if (!isOn) emptyList() else items.filter { it.isMine && it.canDelete && isExpired(it.ts, now) }.map { it.id }

    override fun equals(other: Any?): Boolean = other is AutoDelete && other.ttl == ttl && other.since == since
    override fun hashCode(): Int = 31 * ttl + since.hashCode()
    override fun toString(): String = "AutoDelete(ttl=$ttl, since=$since)"

    companion object {
        const val DAY = 86_400
        const val WEEK = 7 * DAY
        const val MONTH = 31 * DAY
        /** Выкл, день, неделя, месяц. */
        val choices: List<Int> = listOf(0, DAY, WEEK, MONTH)

        fun from(json: JsonValue?): AutoDelete = AutoDelete(json?.get("ttl")?.int ?: 0, json?.get("since")?.long ?: 0)

        /** Новое правило с этого момента; 0 — выключить. */
        fun starting(ttl: Int, now: Long = System.currentTimeMillis()): AutoDelete = AutoDelete(ttl, if (ttl > 0) now else 0)

        fun load(roomID: String, store: Store): AutoDelete = from(store.state(roomID, EventType.AUTODELETE, ""))
    }
}
