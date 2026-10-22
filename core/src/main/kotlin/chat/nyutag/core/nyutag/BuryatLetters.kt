// Нютаг. Бурятские буквы ө, ү, һ при наборе. На русской раскладке их нет, и в переписке их заменяют латиницей:
// «hайн», «yнэр», «θглөө». Такую замену возвращаем к бурятской букве, но только внутри кириллического слова:
// английский текст, ссылки и ники остаются как есть.

package chat.nyutag.core.nyutag

object BuryatLetters {
    /** Буквы для строки над полем ввода, в порядке алфавита. */
    val row: List<Char> = listOf('ө', 'ү', 'һ')

    private val substitutes: Map<Char, Char> = mapOf(
        'h' to 'һ', 'H' to 'Һ',
        'y' to 'ү', 'Y' to 'Ү',
        'θ' to 'ө', 'Θ' to 'Ө', 'ɵ' to 'ө', 'Ɵ' to 'Ө',
    )

    private fun isCyrillic(char: Char): Boolean = char in 'Ѐ'..'ӿ' && char.isLetter()

    /** Текст с заменёнными заменителями; длина не меняется, поэтому курсор остаётся на месте.
     *  `null` — менять нечего. */
    fun fix(text: String): String? {
        val out = text.toCharArray()
        var changed = false
        for (index in text.indices) {
            val letter = substitutes[text[index]] ?: continue
            // Соседей смотрим по исходному тексту: «hh» в латинице так и останется латиницей.
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + 1)
            if (before?.let(::isCyrillic) != true && after?.let(::isCyrillic) != true) continue
            out[index] = letter
            changed = true
        }
        return if (changed) String(out) else null
    }

    /** Буква из строки над полем: заглавная в начале текста и после конца предложения. */
    fun letter(letter: Char, after: CharSequence): Char {
        val last = after.trimEnd(' ', '\n').lastOrNull()
        return if (last == null || last in ".!?") letter.uppercaseChar() else letter
    }
}
