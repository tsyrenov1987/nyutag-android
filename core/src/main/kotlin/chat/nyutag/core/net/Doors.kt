// Нютаг. Двери к ядру: обычные HTTPS-сайты на наших доменах, за которыми стоит ядро nyutag.chat.
// Клиент идёт к ядру по TLS насквозь (сертификат двери выдан публичным центром, доверие системное),
// узлы по дороге только передают байты. Дверь открывается заголовком с ключом из каталога; без него
// сайт отвечает 404, как любой чужой адрес. Здесь чистая логика: разбор каталога, гонка проб и память
// о последней удачной двери. Сами пробы живут в приложении.

package chat.nyutag.core.net

import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Дверь: схема, хост и порт, путь запроса подставляет клиент. */
class Door private constructor(val url: URI, val name: String) {
    /** Строка каталога `https://host[:port]#имя`: так дверь хранится в памяти и в каталоге. */
    val line: String get() = "$url#$name"

    override fun equals(other: Any?): Boolean = other is Door && other.url == url && other.name == name
    override fun hashCode(): Int = url.hashCode() * 31 + name.hashCode()
    override fun toString(): String = line

    companion object {
        /** Только `https` с хостом и без пути: иначе дверь не наша или адрес сломан. */
        fun of(url: URI, name: String): Door? {
            if (url.scheme != "https") return null
            val host = url.host ?: return null
            if (host.isEmpty()) return null
            val path = url.rawPath ?: ""
            if (path.isNotEmpty() && path != "/") return null
            if (url.rawQuery != null) return null
            return Door(url, name)
        }

        /** Строка каталога `https://host[:port]#имя`; без имени имя — хост. */
        fun parse(line: String): Door? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("https://")) return null
            val hash = trimmed.indexOf('#')
            val address = (if (hash >= 0) trimmed.substring(0, hash) else trimmed).trim()
            val name = if (hash >= 0) trimmed.substring(hash + 1) else ""
            // Хвостовой «/» — тот же адрес: храним без него, чтобы двери сравнивались по адресу.
            val bare = if (address.endsWith("/")) address.dropLast(1) else address
            val url = try { URI(bare) } catch (_: URISyntaxException) { return null }
            val host = url.host ?: return null
            return of(url, if (name.isNotEmpty()) name else host)
        }
    }
}

/** Каталог из ресурсов приложения: ключ и двери. Строки `kilvater://…` старого формата пропускаются. */
data class DoorCatalogue(val key: String, val doors: List<Door>) {
    companion object {
        const val HEADER_NAME = "X-Nyutag-Door"
        internal const val KEY_PREFIX = "door-key "

        /** Текст: строка `door-key <ключ>`, затем по двери на строку; пустые строки и `#…` — заметки.
         *  Без ключа или без дверей каталога нет. */
        fun parse(text: String): DoorCatalogue? {
            var key: String? = null
            val doors = ArrayList<Door>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith(KEY_PREFIX)) {
                    key = line.removePrefix(KEY_PREFIX).trim()
                } else {
                    Door.parse(line)?.let(doors::add)
                }
            }
            if (key.isNullOrEmpty() || doors.isEmpty()) return null
            return DoorCatalogue(key, doors)
        }

        /** Двери из строк каталога сервера (`doors` в `/_nyutag/v1/catalogue`), непонятные строки пропускаются. */
        fun doors(lines: List<String>): List<Door> = lines.mapNotNull(Door::parse)

        /** Объединение без повторов, порядок сохраняется: сначала `first`, затем новые из `second`. */
        fun merge(first: List<Door>, second: List<Door>): List<Door> {
            val seen = HashSet<URI>()
            return (first + second).filter { seen.add(it.url) }
        }
    }
}

/** Простое хранилище строк: в приложении — SharedPreferences, в тестах — память. `null` стирает ключ. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MemoryKeyValueStore : KeyValueStore {
    private val values = HashMap<String, String>()
    @Synchronized override fun get(key: String): String? = values[key]
    @Synchronized override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** Память о дверях: последняя удачная дверь с ключом и двери, присланные сервером. */
object DoorMemory {
    internal const val LAST_KEY = "nyutag.door.last"
    internal const val KEY_KEY = "nyutag.door.key"
    internal const val DYNAMIC_KEY = "nyutag.door.dynamic"

    data class Remembered(val door: Door, val key: String)

    /** Дверь хранится строкой каталога (адрес и имя): по ней DoorHub узнаёт свою дверь в каталоге. */
    fun remember(door: Door, key: String, store: KeyValueStore) {
        store.put(LAST_KEY, door.line)
        store.put(KEY_KEY, key)
    }

    fun recall(store: KeyValueStore): Remembered? {
        val door = store.get(LAST_KEY)?.let(Door::parse) ?: return null
        val key = store.get(KEY_KEY)?.takeIf { it.isNotEmpty() } ?: return null
        return Remembered(door, key)
    }

    fun rememberDynamic(doors: List<Door>, store: KeyValueStore) {
        store.put(DYNAMIC_KEY, doors.joinToString("\n") { it.line })
    }

    fun recallDynamic(store: KeyValueStore): List<Door> =
        DoorCatalogue.doors(store.get(DYNAMIC_KEY)?.lines() ?: emptyList())

    fun forget(store: KeyValueStore) {
        for (key in listOf(LAST_KEY, KEY_KEY, DYNAMIC_KEY)) store.put(key, null)
    }
}

/** Гонка дверей: все пробуются разом, побеждает первая, за которой ответило ядро. */
object DoorRace {
    /** Порядок проб: последняя удачная дверь первой, повторы убраны. */
    fun order(doors: List<Door>, preferred: Door?): List<Door> {
        val ordered = DoorCatalogue.merge(emptyList(), doors).toMutableList()
        if (preferred != null) {
            val index = ordered.indexOf(preferred)
            if (index >= 0) {
                ordered.removeAt(index)
                ordered.add(0, preferred)
            }
        }
        return ordered
    }

    /** Предпочтительная дверь стартует первой, остальные — через `headStartMillis`, если она ещё не ответила:
     *  знакомая живая дверь остаётся за человеком, а двери не дёргаются без нужды. Без предпочтения
     *  все стартуют сразу. `probe` отвечает, пустило ли ядро; проигравшие пробы снимаются. */
    suspend fun run(doors: List<Door>, preferred: Door?, headStartMillis: Long,
                    probe: suspend (Door) -> Boolean): Door? {
        val ordered = order(doors, preferred)
        if (ordered.isEmpty()) return null
        val delayed = preferred != null && ordered.first() == preferred && headStartMillis > 0
        return coroutineScope {
            val results = Channel<Door?>(ordered.size)
            val jobs: List<Job> = ordered.mapIndexed { index, door ->
                launch {
                    if (delayed && index > 0) delay(headStartMillis)
                    val answered = try {
                        probe(door)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        false
                    }
                    results.send(if (answered) door else null)
                }
            }
            var winner: Door? = null
            for (i in ordered.indices) {
                winner = results.receive() ?: continue
                break
            }
            jobs.forEach { it.cancel() }
            winner
        }
    }
}
