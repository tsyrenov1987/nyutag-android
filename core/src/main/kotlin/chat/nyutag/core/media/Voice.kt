// Нютаг. Волна голосового: уровни громкости записи (дБ, 0 — максимум) → до сотни столбиков 0…1024.

package chat.nyutag.core.media

import kotlin.math.roundToInt

object Voice {
    const val BARS = 100
    /** Тише этого — тишина, громче нуля не бывает. */
    private const val FLOOR = -50f

    /** Уровни в дБ раз в несколько десятков мс. Больше `bars` — усредняем соседние. */
    fun waveform(decibels: List<Float>, bars: Int = BARS): List<Int> {
        if (decibels.isEmpty() || bars <= 0) return emptyList()
        val levels = decibels.map { db -> if (!db.isFinite()) 0f else (db.coerceIn(FLOOR, 0f) - FLOOR) / -FLOOR }
        val count = minOf(bars, levels.size)
        return (0 until count).map { bar ->
            val from = bar * levels.size / count
            val to = maxOf((bar + 1) * levels.size / count, from + 1)
            val mean = levels.subList(from, to).sum() / (to - from)
            (mean * 1024).roundToInt()
        }
    }

    /** Волна под заданное число столбиков на экране: 0…1, пустая — ровная низкая. */
    fun display(waveform: List<Int>, bars: Int): List<Double> {
        if (bars <= 0) return emptyList()
        if (waveform.isEmpty()) return List(bars) { 0.15 }
        return (0 until bars).map { bar ->
            val index = minOf(bar * waveform.size / bars, waveform.size - 1)
            maxOf(waveform[index] / 1024.0, 0.08)
        }
    }

    /** «0:07», «1:23». */
    fun clock(milliseconds: Int): String {
        val seconds = maxOf(milliseconds, 0) / 1000
        return "${seconds / 60}:" + "%02d".format(seconds % 60)
    }
}
