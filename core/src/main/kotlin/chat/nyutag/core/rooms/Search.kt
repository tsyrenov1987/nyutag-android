// Нютаг. Поиск по сообщениям. Шифрованные реплики в базе лежат закрытыми, поэтому поиск идёт по своему индексу
// (`search_text`): фоновый проход расшифровывает ещё не разобранные события и складывает открытый текст.
// Реплика без ключа пропускается и будет разобрана в следующий проход, когда ключ придёт.

package chat.nyutag.core.rooms

import chat.nyutag.core.store.SearchHit
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType

object Search {
    /** Свёртка для сравнения без учёта регистра; «ё» и «е» считаем одной буквой. */
    fun fold(text: String): String = text.lowercase().replace("ё", "е")

    /** Что из реплики попадает в индекс: текст и событие, под которым его хранить (у правки — исходная реплика). */
    internal fun entry(event: Event): Pair<String, String>? {
        if (event.type != EventType.MESSAGE || event.isRedacted) return null
        val id = event.eventID ?: return null
        val relation = event.content["m.relates_to"]
        if (relation?.get("rel_type")?.string == "m.replace") {
            val target = relation["event_id"]?.string
            if (target != null) return target to (event.content.at("m.new_content", "body")?.string ?: "")
        }
        return id to (event.content["body"]?.string ?: "")
    }

    /** Фоновый проход: разобрать неразобранные события, новые первыми. Возвращает, сколько реплик легло в индекс.
     *  `maxEvents` ограничивает один проход, чтобы не держать телефон занятым; остаток доберёт следующий. */
    suspend fun index(store: Store, decrypt: suspend (Event, String) -> Event?, batch: Int = 200, maxEvents: Int = 2000): Int {
        var cursor: Long? = null
        var seen = 0
        var added = 0
        while (seen < maxEvents) {
            val events = store.unindexedEvents(cursor, batch)
            if (events.isEmpty()) break
            seen += events.size
            cursor = events.mapNotNull { it.originServerTS }.minOrNull() ?: 0
            for (event in events) {
                val id = event.eventID ?: continue
                val roomID = event.roomID ?: continue
                val clear = if (event.type == EventType.ENCRYPTED) {
                    if (event.isRedacted) event else decrypt(event, roomID)
                } else {
                    event
                }
                // Ключа пока нет: в индекс не пишем, попробуем в другой раз.
                if (clear == null) continue
                val ts = event.originServerTS ?: 0
                val sender = event.sender ?: ""
                val found = entry(clear)
                if (found != null && found.second.isNotEmpty()) {
                    val (target, text) = found
                    store.indexSearch(target, roomID, sender, ts, text, fold(text), replace = target != id)
                    added += 1
                    if (target == id) continue
                }
                // Разобрано, искать нечего (служебное, вложение без подписи, сама правка).
                store.indexSearch(id, roomID, sender, ts, "", "")
            }
        }
        return added
    }

    /** Совпадения по запросу от двух знаков, новые первыми; `roomID` — искать только в одном чате. */
    fun find(query: String, roomID: String? = null, store: Store, limit: Int = 60): List<SearchHit> {
        val folded = fold(query.trim())
        if (folded.length < 2) return emptyList()
        return store.searchText(folded, roomID, limit)
    }
}
