// Нютаг. Буфер против дрожания сети: кадры приходят неровно и не по порядку, а играть их надо ровно каждые 20 мс.
// Перед началом копим `target` кадров. Дыра — отдаём «потерян» и идём дальше (кодек сгладит). Буфер опустел —
// копим заново: так задержка сама подрастает на плохой сети. Набралось слишком много — выбрасываем старое,
// чтобы собеседник не слышал себя с опозданием в секунду. Не потокобезопасен: снаружи под замком.

package chat.nyutag.core.calls

class JitterBuffer(val target: Int = 3, val limit: Int = 12) {
    sealed class Pull {
        class Frame(val payload: ByteArray) : Pull()
        /** Кадр не пришёл вовремя: играть замену. */
        object Missing : Pull()
        /** Играть нечего, ждём накопления: тишина. */
        object Waiting : Pull()
    }

    private val frames = HashMap<Long, ByteArray>()
    private var next: Long? = null
    /** Всё, что не новее этого номера, уже сыграно или пропущено: опоздавшие кадры не нужны. */
    private var floor: Long? = null
    var played = 0
        private set
    var lost = 0
        private set
    var dropped = 0
        private set

    val depth: Int get() = frames.size

    fun insert(sequence: Long, payload: ByteArray) {
        next?.let { if (sequence < it) { dropped += 1; return } }
        floor?.let { if (sequence <= it) { dropped += 1; return } }
        frames[sequence] = payload
        if (frames.size > limit) {
            // Догоняем: оставляем последние `target` кадров.
            val keep = frames.keys.max() - (target - 1)
            val old = frames.keys.filter { it < keep }
            for (key in old) frames.remove(key)
            dropped += old.size
            next = keep
            floor = keep - 1
        }
    }

    fun pull(): Pull {
        val current = next
        if (current == null) {
            if (frames.size < target) return Pull.Waiting
            next = frames.keys.min()
            return pull()
        }
        frames.remove(current)?.let {
            next = current + 1
            floor = current
            played += 1
            return Pull.Frame(it)
        }
        if (frames.isEmpty()) {
            next = null
            return Pull.Waiting
        }
        floor = current
        next = current + 1
        lost += 1
        return Pull.Missing
    }
}
