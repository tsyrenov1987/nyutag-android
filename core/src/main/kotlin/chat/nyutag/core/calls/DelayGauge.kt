// Нютаг. Задержка пути до собеседника и обратно по зондам видео, как у iOS-клиента. Своя очередь отправки (backlog)
// пробку дальше по дороге не видит: кадры оседают в буферах TCP у дверей и ядра, а у собеседника звук стоит в них за
// видео. Поэтому отправитель видео раз в полсекунды шлёт метку времени, собеседник сразу её возвращает, и рост
// задержки эха сверх обычной для этого звонка значит: видео надо урезать. Эха не было ни разу (у собеседника старая
// версия) — судить не о чем, видео живёт по своей очереди, как раньше.

package chat.nyutag.core.calls

class DelayGauge {
    enum class Verdict {
        /** Эха ещё не было. */
        UNKNOWN,
        /** Задержка как обычно: можно прибавлять. */
        CLEAR,
        /** Подросла: держим как есть. */
        FAIR,
        /** Пробка: урезаем. */
        JAMMED,
    }

    /** Метки зондов без эха (мс от начала звонка, младшие 32 бита), по порядку отправки. */
    private val pending = ArrayDeque<Long>()
    private var lastProbe: Long? = null
    /** (когда пришло эхо, задержка), по порядку. */
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Метка для нового зонда, если пора его слать. `now` — мс от начала звонка. */
    fun probe(now: Long): Long? {
        lastProbe?.let { if (now - it < PROBE_EVERY) return null }
        lastProbe = now
        val stamp = now and MASK
        pending.addLast(stamp)
        if (pending.size > 32) pending.removeFirst()
        return stamp
    }

    /** Пришло эхо. Зонды до него, оставшиеся без эха, потерялись: их больше не ждём. */
    fun echo(stamp: Long, now: Long) {
        val index = pending.indexOf(stamp)
        if (index < 0) return
        repeat(index + 1) { pending.removeFirst() }
        samples.addLast(now to ((now - stamp) and MASK))
        samples.removeAll { now - it.first > BASE_WINDOW }
    }

    /** Обычная задержка звонка. */
    val base: Long? get() = samples.minOfOrNull { it.second }

    /**
     * Задержка сейчас: наименьшая из эх за последнюю секунду (одиночный всплеск — не пробка), но не меньше возраста
     * самого старого зонда без эха (эхо перестало приходить — это тоже пробка).
     */
    fun delay(now: Long): Long? {
        if (samples.isEmpty()) return null
        val recent = samples.filter { now - it.first <= 1_000 }.minOfOrNull { it.second } ?: 0L
        val waiting = pending.firstOrNull()?.let { (now - it) and MASK } ?: 0L
        return maxOf(recent, waiting)
    }

    fun verdict(now: Long): Verdict {
        val base = base ?: return Verdict.UNKNOWN
        val delay = delay(now) ?: return Verdict.UNKNOWN
        if (delay > base + JAM_MARGIN) return Verdict.JAMMED
        return if (delay <= base + CLEAR_MARGIN) Verdict.CLEAR else Verdict.FAIR
    }

    companion object {
        const val PROBE_EVERY = 500L
        /** Сверх обычной задержки — пробка. */
        const val JAM_MARGIN = 500L
        /** Не дальше от обычной — путь свободен. */
        const val CLEAR_MARGIN = 200L
        /** Обычная задержка — наименьшая за это время: путь мог смениться при переподключении. */
        const val BASE_WINDOW = 60_000L
        private const val MASK = 0xFFFF_FFFFL
    }
}
