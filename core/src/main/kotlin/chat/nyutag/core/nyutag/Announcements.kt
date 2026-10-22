// Нютаг. Обращения разработчика: сервер отдаёт `announce.json` (`GET /_nyutag/v1/announce`), приложение показывает их
// каналом «Новости Нютага» в списке чатов. Формат общий с iOS: `{id, from, until, repeat_days, title{ru,bxr,en},
// body{…}, actions[{type: url|donate, label{…}, url}], dismiss{…}}`. Язык выбирает интерфейс; если перевода нет,
// берётся русский, потом любой.

package chat.nyutag.core.nyutag

import chat.nyutag.json.JsonValue
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Announcement(
    val id: String,
    /** Показывать с этого момента (начало суток по зоне устройства); `null` — сразу. */
    val from: Instant? = null,
    /** Показывать до этого момента, не включая (начало суток после последнего дня); `null` — бессрочно. */
    val until: Instant? = null,
    val title: Translated,
    val body: Translated,
    val actions: List<Action> = emptyList(),
) {
    data class Action(val kind: Kind, val label: Translated) {
        sealed class Kind {
            data class Url(val url: URI) : Kind()
            object Donate : Kind() { override fun toString() = "Donate" }
        }
    }

    /** Текст на языках интерфейса: `ru`, `bxr`, `en`. */
    data class Translated(val texts: Map<String, String>) {
        fun text(lang: String): String = texts[lang] ?: texts["ru"] ?: texts.toSortedMap().values.firstOrNull().orEmpty()

        companion object {
            internal fun read(json: JsonValue?): Translated? {
                json?.string?.let { return Translated(mapOf("ru" to it)) }
                val texts = json?.obj?.mapNotNull { (lang, value) -> value.string?.takeIf { it.isNotEmpty() }?.let { lang to it } }
                return texts?.takeIf { it.isNotEmpty() }?.let { Translated(it.toMap()) }
            }
        }
    }

    fun isCurrent(now: Instant): Boolean {
        if (from != null && now < from) return false
        if (until != null && now >= until) return false
        return true
    }

    companion object {
        /** Объявление из записи сервера; без `id`, заголовка или текста — `null`. */
        fun read(json: JsonValue, zone: ZoneId = ZoneId.systemDefault()): Announcement? {
            val id = json["id"]?.string?.takeIf { it.isNotEmpty() } ?: return null
            val title = Translated.read(json["title"]) ?: return null
            val body = Translated.read(json["body"]) ?: return null
            val actions = json["actions"]?.array.orEmpty().mapNotNull { action ->
                val label = Translated.read(action["label"]) ?: return@mapNotNull null
                when (action["type"]?.string) {
                    "donate" -> Action(Action.Kind.Donate, label)
                    "url" -> {
                        val url = action["url"]?.string?.let { runCatching { URI(it) }.getOrNull() }
                        if (url?.scheme == "https") Action(Action.Kind.Url(url), label) else null
                    }
                    else -> null
                }
            }
            return Announcement(id, from = day(json["from"])?.atStartOfDay(zone)?.toInstant(),
                                until = day(json["until"])?.plusDays(1)?.atStartOfDay(zone)?.toInstant(),
                                title = title, body = body, actions = actions)
        }

        /** Все годные объявления списка сервера, свежие сверху (порядок файла — старые первыми). */
        fun readAll(json: List<JsonValue>, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<Announcement> =
            json.mapNotNull { read(it, zone) }.filter { it.isCurrent(now) }.reversed()

        /** День `ГГГГ-ММ-ДД`. */
        internal fun day(json: JsonValue?): LocalDate? {
            val parts = json?.string?.split('-')?.mapNotNull(String::toIntOrNull) ?: return null
            if (parts.size != 3) return null
            return runCatching { LocalDate.of(parts[0], parts[1], parts[2]) }.getOrNull()
        }
    }
}
