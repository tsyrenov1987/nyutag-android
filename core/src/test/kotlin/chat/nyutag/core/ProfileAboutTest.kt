// Нютаг. «О себе»: восточный год, правки только изменившихся полей, чтение из профиля.

package chat.nyutag.core

import chat.nyutag.core.rooms.EastYear
import chat.nyutag.core.rooms.NyutagProfileKey
import chat.nyutag.core.rooms.Profile
import chat.nyutag.core.rooms.ProfileAbout
import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileAboutTest {
    @Test fun eastYearCycle() {
        assertEquals("wood-rat", EastYear.ofYear(1984).key)
        assertEquals("fire-rabbit", EastYear.ofYear(1987).key)
        assertEquals("fire-horse", EastYear.ofYear(2026).key)
        assertEquals("wood-dragon", EastYear.ofYear(2024).key)
        assertEquals("water-pig", EastYear.ofYear(1923).key)
        assertEquals(EastYear.ofYear(1987), EastYear.ofYear(2047))
        assertEquals(EastYear.ofYear(2026), EastYear.fromKey("fire-horse"))
        assertNull(EastYear.fromKey("fire"))
        assertNull(EastYear.fromKey("lava-horse"))
    }

    @Test fun editsOnlyChanged() {
        val old = ProfileAbout(about = "Привет", toonto = "Хори", ug = "", eastYear = "fire-horse")
        val fresh = old.copy(about = "  Привет  ", toonto = "", ug = " Хуасай ", eastYear = "мусор")
        val edits = fresh.edits(old)
        assertEquals(3, edits.size)
        assertTrue(edits.containsKey(NyutagProfileKey.TOONTO))
        assertNull(edits[NyutagProfileKey.TOONTO])
        assertEquals("Хуасай", edits[NyutagProfileKey.UG])
        assertTrue(edits.containsKey(NyutagProfileKey.EAST_YEAR))
        assertNull(edits[NyutagProfileKey.EAST_YEAR])
        assertFalse(edits.containsKey(NyutagProfileKey.ABOUT))
        assertEquals(ProfileAbout.MAX_ABOUT, ProfileAbout(about = "а".repeat(600)).cleaned.about.length)
    }

    @Test fun readsProfile() {
        val profile = Profile(fields = mapOf(
            "chat.nyutag.about" to JsonValue.Str("Сайн"), "chat.nyutag.east_year" to JsonValue.Str("wood-rat"), "chat.nyutag.ug" to JsonValue.Str(""),
        ))
        assertEquals(ProfileAbout(about = "Сайн", eastYear = "wood-rat"), ProfileAbout(profile))
    }
}
