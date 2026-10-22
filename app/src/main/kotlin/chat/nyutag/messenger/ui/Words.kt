// Нютаг. Язык интерфейса: выбирается на первом экране и хранится в настройках. Фразы — в [P].

package chat.nyutag.messenger.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import chat.nyutag.core.net.KeyValueStore
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

    companion object {
        const val KEY = "nyutag.lang"
    }
}

val LocalWords = staticCompositionLocalOf<Words> { error("Words не заданы") }
