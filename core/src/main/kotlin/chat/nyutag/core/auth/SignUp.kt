// Нютаг. Адрес нового участника: из имени, как его набрал человек, получаем латинский ник для Matrix ID.
// Кириллицу, включая бурятские ө, ү, һ, пишем латиницей; занятость проверяет сервер.

package chat.nyutag.core.auth

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixError
import kotlin.random.Random

object Nickname {
    /** Разрешённые в нике знаки; сервис регистрации принимает 2–32 таких знака. */
    internal val allowed: Set<Char> = "abcdefghijklmnopqrstuvwxyz0123456789._-".toSet()
    const val LONGEST = 24

    private val latin: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya", 'ө' to "o", 'ү' to "u", 'һ' to "h",
    )

    /** Ник из имени: «Арюна Доржиева» → `aryuna.dorzhieva`. Пусто, если из имени ничего не вышло. */
    fun from(name: String): String {
        val out = StringBuilder()
        for (char in name.lowercase()) {
            val spelled = latin[char]
            when {
                spelled != null -> out.append(spelled)
                char in allowed -> out.append(char)
                char.isWhitespace() || char == ',' -> out.append('.')
            }
        }
        return tidy(out.toString())
    }

    /** То, что человек набрал в поле ника сам: те же правила, без смены регистра в середине. */
    fun clean(typed: String): String = tidy(typed.lowercase().filter { it in allowed })

    fun isValid(nick: String): Boolean = nick.length in 2..32 && nick.all { it in allowed }

    /** Запасные варианты, если ник занят: с двумя-тремя цифрами в конце. */
    fun alternatives(nick: String, count: Int = 4): List<String> {
        val seen = hashSetOf(nick)
        val result = ArrayList<String>()
        while (result.size < count) {
            val candidate = nick.take(LONGEST - 4) + Random.nextInt(10, 1000)
            if (seen.add(candidate)) result += candidate
        }
        return result
    }

    private fun tidy(raw: String): String {
        val out = StringBuilder()
        for (char in raw) {
            // Подряд идущие разделители схлопываем в один.
            if (char in SEPARATORS && out.isNotEmpty() && out.last() in SEPARATORS) continue
            out.append(char)
        }
        val trimmed = out.toString().trim { it in SEPARATORS }
        return trimmed.take(LONGEST).trim { it in SEPARATORS }
    }

    private const val SEPARATORS = ".-_"
}

/** Свободен ли ник для регистрации. Занятый сервер отдаёт как `M_USER_IN_USE`, недопустимый — `M_INVALID_USERNAME`. */
suspend fun MatrixClient.isUsernameAvailable(nick: String): Boolean {
    return try {
        val json = request(MatrixClient.Method.GET, "register/available", query = mapOf("username" to nick), authenticated = false)
        json["available"]?.bool == true
    } catch (e: MatrixError) {
        if (e.errcode == "M_USER_IN_USE" || e.errcode == "M_INVALID_USERNAME") false else throw e
    }
}
