// Нютаг. «О себе» в профиле: пара слов, тоонто нютаг, уг гарбал и год по восточному календарю. Поля профиля
// MSC4133 ([NyutagProfileKey]), значения — строки; год на проводе `<стихия>-<животное>`, например `fire-horse`.

package chat.nyutag.core.rooms

/** Год 60-летнего цикла. Стихия меняется раз в два года, животное — каждый год; 1984 — деревянная крыса. */
data class EastYear(
    /** Номера в [elements] и [animals]. */
    val element: Int,
    val animal: Int,
) {
    val key: String get() = "${elements[element]}-${animals[animal]}"

    companion object {
        val elements = listOf("wood", "fire", "earth", "metal", "water")
        val animals = listOf("rat", "ox", "tiger", "rabbit", "dragon", "snake", "horse", "sheep", "monkey", "rooster", "dog", "pig")

        fun ofYear(year: Int): EastYear {
            val shift = ((year - 1984) % 60 + 60) % 60
            return EastYear(shift % 10 / 2, shift % 12)
        }

        fun fromKey(key: String): EastYear? {
            val parts = key.split("-", limit = 2)
            if (parts.size != 2) return null
            val element = elements.indexOf(parts[0]).takeIf { it >= 0 } ?: return null
            val animal = animals.indexOf(parts[1]).takeIf { it >= 0 } ?: return null
            return EastYear(element, animal)
        }
    }
}

data class ProfileAbout(
    val about: String = "",
    val toonto: String = "",
    val ug: String = "",
    /** Ключ [EastYear], пусто — не указан. */
    val eastYear: String = "",
) {
    constructor(profile: Profile) : this(
        profile[NyutagProfileKey.ABOUT] ?: "", profile[NyutagProfileKey.TOONTO] ?: "",
        profile[NyutagProfileKey.UG] ?: "", profile[NyutagProfileKey.EAST_YEAR] ?: "",
    )

    val isEmpty: Boolean get() = this == ProfileAbout()

    /** Без пробелов по краям, в пределах длины; незнакомый год отбрасываем. */
    val cleaned: ProfileAbout get() {
        fun trim(text: String, limit: Int): String = text.trim().take(limit)
        return ProfileAbout(trim(about, MAX_ABOUT), trim(toonto, MAX_SHORT), trim(ug, MAX_SHORT), EastYear.fromKey(eastYear)?.key ?: "")
    }

    /** Что отправить на сервер: только изменившиеся поля, пустое — удалить (`null`). */
    fun edits(old: ProfileAbout): Map<NyutagProfileKey, String?> {
        val fresh = cleaned
        val edits = LinkedHashMap<NyutagProfileKey, String?>()
        val pairs = listOf(
            Triple(NyutagProfileKey.ABOUT, fresh.about, old.about), Triple(NyutagProfileKey.TOONTO, fresh.toonto, old.toonto),
            Triple(NyutagProfileKey.UG, fresh.ug, old.ug), Triple(NyutagProfileKey.EAST_YEAR, fresh.eastYear, old.eastYear),
        )
        for ((key, new, before) in pairs) if (new != before) edits[key] = new.ifEmpty { null }
        return edits
    }

    companion object {
        const val MAX_ABOUT = 500
        const val MAX_SHORT = 100
    }
}
