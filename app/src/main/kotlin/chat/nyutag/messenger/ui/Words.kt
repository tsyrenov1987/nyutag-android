// Нютаг. Язык интерфейса: выбирается на первом экране и хранится в настройках. Фразы — в [P].

package chat.nyutag.messenger.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import chat.nyutag.core.net.KeyValueStore
import chat.nyutag.core.rooms.EastYear
import java.util.Locale

enum class Lang(val ownName: String) {
    bxr("Буряад хэлэн"), ru("Русский"), en("English");

    /** Бурятского в форматах дат платформы нет: числа и дни недели — по-русски. */
    val locale: Locale get() = if (this == en) Locale.ENGLISH else Locale.forLanguageTag("ru")
}

data class Phrase(val bxr: String, val ru: String, val en: String)

class Words(private val prefs: KeyValueStore) {
    /** `null` — язык ещё не выбран: показываем экран выбора. */
    var lang: Lang? by mutableStateOf(prefs.get(KEY)?.let { runCatching { Lang.valueOf(it) }.getOrNull() })
        private set

    fun choose(lang: Lang) {
        this.lang = lang
        prefs.put(KEY, lang.name)
    }

    operator fun invoke(phrase: Phrase): String = when (lang ?: Lang.ru) {
        Lang.bxr -> phrase.bxr
        Lang.ru -> phrase.ru
        Lang.en -> phrase.en
    }

    /** Фраза с подстановками `{1}`, `{2}`: имена в служебных плашках. */
    operator fun invoke(phrase: Phrase, vararg values: String): String {
        var text = this(phrase)
        values.forEachIndexed { index, value -> text = text.replace("{${index + 1}}", value) }
        return text
    }

    val locale: Locale get() = (lang ?: Lang.ru).locale

    /** Кнопки «ө ү һ» над полем ввода и замена h, y, θ внутри кириллического слова; по умолчанию включено. */
    var buryatLetters: Boolean by mutableStateOf(prefs.get(LETTERS_KEY) != "false")
        private set

    fun chooseLetters(on: Boolean) {
        buryatLetters = on
        prefs.put(LETTERS_KEY, on.toString())
    }

    /** «Огненная Лошадь» / «Улаан морин» / «Fire Horse»: по-русски стихия согласуется с родом животного. */
    fun eastYear(year: EastYear): String {
        val feminine = year.animal in listOf(0, 5, 6, 7, 8, 10, 11)
        val element = if (feminine) elementsFeminine[year.element] else elements[year.element]
        return "${this(element)} ${this(animals[year.animal])}"
    }

    companion object {
        const val KEY = "nyutag.lang"
        const val LETTERS_KEY = "nyutag.buryatLetters"

        private val elements = listOf(
            Phrase(bxr = "Хүхэ", ru = "Деревянный", en = "Wood"), Phrase(bxr = "Улаан", ru = "Огненный", en = "Fire"),
            Phrase(bxr = "Шара", ru = "Земляной", en = "Earth"), Phrase(bxr = "Сагаан", ru = "Металлический", en = "Metal"),
            Phrase(bxr = "Хара", ru = "Водяной", en = "Water"),
        )
        private val elementsFeminine = listOf(
            Phrase(bxr = "Хүхэ", ru = "Деревянная", en = "Wood"), Phrase(bxr = "Улаан", ru = "Огненная", en = "Fire"),
            Phrase(bxr = "Шара", ru = "Земляная", en = "Earth"), Phrase(bxr = "Сагаан", ru = "Металлическая", en = "Metal"),
            Phrase(bxr = "Хара", ru = "Водяная", en = "Water"),
        )
        private val animals = listOf(
            Phrase(bxr = "хулгана", ru = "Крыса", en = "Rat"), Phrase(bxr = "үхэр", ru = "Бык", en = "Ox"),
            Phrase(bxr = "бар", ru = "Тигр", en = "Tiger"), Phrase(bxr = "туулай", ru = "Кролик", en = "Rabbit"),
            Phrase(bxr = "луу", ru = "Дракон", en = "Dragon"), Phrase(bxr = "могой", ru = "Змея", en = "Snake"),
            Phrase(bxr = "морин", ru = "Лошадь", en = "Horse"), Phrase(bxr = "хонин", ru = "Овца", en = "Sheep"),
            Phrase(bxr = "бишэн", ru = "Обезьяна", en = "Monkey"), Phrase(bxr = "тахяа", ru = "Петух", en = "Rooster"),
            Phrase(bxr = "нохой", ru = "Собака", en = "Dog"), Phrase(bxr = "гахай", ru = "Свинья", en = "Pig"),
        )
    }
}

val LocalWords = staticCompositionLocalOf<Words> { error("Words не заданы") }
