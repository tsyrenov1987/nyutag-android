// Нютаг. Лунный календарь: даты Сагаалгана и день по зурхаю. Даты Сагаалгана — таблица по системе Пхугпа
// из нашего же приложения «Зурхай»; дни зурхая — его же данные, сжатые в ресурсе `zurkhai-days.json`.
// Дни — `LocalDate`: без часового пояса, разница в днях не зависит от зоны и високосных лет.

package chat.nyutag.core.nyutag

import chat.nyutag.json.JsonValue
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object Sagaalgan {
    /** Первый день Белого месяца по годам. */
    private val dates: Map<Int, Pair<Int, Int>> = mapOf(
        2024 to (2 to 10), 2025 to (2 to 28), 2026 to (2 to 18), 2027 to (2 to 7), 2028 to (2 to 26), 2029 to (2 to 14),
        2030 to (3 to 5), 2031 to (2 to 22), 2032 to (2 to 12), 2033 to (3 to 1), 2034 to (2 to 19), 2035 to (2 to 8),
    )

    val years: IntRange = 2024..2035

    fun day(year: Int): LocalDate? = dates[year]?.let { (month, day) -> LocalDate.of(year, month, day) }

    /** Ближайший Сагаалган с сегодняшнего дня (сегодня — 0 дней) и сколько до него дней; `null` за пределами таблицы. */
    fun next(today: LocalDate): Pair<LocalDate, Int>? {
        for (year in today.year..today.year + 1) {
            val day = day(year) ?: continue
            if (day >= today) return day to ChronoUnit.DAYS.between(today, day).toInt()
        }
        return null
    }

    /** Все даты от года `from` до конца таблицы. */
    fun upcoming(from: Int): List<LocalDate> = years.filter { it >= from }.mapNotNull(::day)
}

/** День по зурхаю: лунные сутки, стрижка и советы. */
data class ZurkhaiDay(
    val lunarDay: Int,
    val haircutGood: Boolean,
    val haircut: String,
    val favorable: String,
    val unfavorable: String,
)

/** Сжатые дни: строки один раз в таблице, у дня — номера строк. Дни идут подряд от `start`. */
class ZurkhaiDays private constructor(
    val start: LocalDate,
    private val ru: Table,
    private val en: Table,
) {
    private class Table(val strings: List<String>, val days: List<List<Int>>)

    /** Последний день, на который есть данные. */
    val last: LocalDate get() = start.plusDays(ru.days.size - 1L)

    /** По-английски — английские тексты, на остальных языках — русские (бурятских в «Зурхае» нет). */
    fun day(day: LocalDate, english: Boolean = false): ZurkhaiDay? {
        val table = if (english) en else ru
        val row = table.days.getOrNull(ChronoUnit.DAYS.between(start, day).toInt()) ?: return null
        if (row.size != 5 || row.subList(2, 5).any { it !in table.strings.indices }) return null
        return ZurkhaiDay(lunarDay = row[0], haircutGood = row[1] == 1, haircut = table.strings[row[2]],
                          favorable = table.strings[row[3]], unfavorable = table.strings[row[4]])
    }

    companion object {
        /** Разбор файла; испорченный файл — исключение. */
        fun parse(text: String): ZurkhaiDays {
            val json = JsonValue.parse(text)
            val start = LocalDate.parse(json["from"]?.string ?: throw IllegalArgumentException("zurkhai: нет from"))
            return ZurkhaiDays(start, table(json["ru"], "ru"), table(json["en"], "en"))
        }

        /** Таблица, что лежит в движке. */
        fun bundled(): ZurkhaiDays {
            val stream = ZurkhaiDays::class.java.getResourceAsStream("zurkhai-days.json")
                ?: throw IllegalStateException("zurkhai-days.json нет в ресурсах")
            return stream.use { parse(String(it.readBytes(), Charsets.UTF_8)) }
        }

        private fun table(json: JsonValue?, name: String): Table {
            val strings = json?.get("strings")?.array?.map { it.string ?: throw IllegalArgumentException("zurkhai: $name.strings") }
            val days = json?.get("days")?.array?.map { row ->
                row.array?.map { it.int ?: throw IllegalArgumentException("zurkhai: $name.days") }
                    ?: throw IllegalArgumentException("zurkhai: $name.days")
            }
            if (strings == null || days == null) throw IllegalArgumentException("zurkhai: нет таблицы $name")
            return Table(strings, days)
        }
    }
}
