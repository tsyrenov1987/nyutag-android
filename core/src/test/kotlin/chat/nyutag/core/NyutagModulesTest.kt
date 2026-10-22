// Нютаг. Модули Нютага: обращения разработчика, бурятские буквы, лунный календарь, сервис `/_nyutag/v1`.

package chat.nyutag.core

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.nyutag.Announcement
import chat.nyutag.core.nyutag.BuryatLetters
import chat.nyutag.core.nyutag.ContactMedium
import chat.nyutag.core.nyutag.InviteCode
import chat.nyutag.core.nyutag.NyutagService
import chat.nyutag.core.nyutag.Sagaalgan
import chat.nyutag.core.nyutag.ZurkhaiDay
import chat.nyutag.core.nyutag.ZurkhaiDays
import chat.nyutag.json.JsonValue
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NyutagModulesTest {
    private val zone = ZoneId.of("Asia/Irkutsk")

    private val sample = JsonValue.of(
        "id" to "2026-10-support",
        "from" to "2026-10-15",
        "until" to "2026-12-31",
        "repeat_days" to 14,
        "title" to JsonValue.of("ru" to "Поддержите Нютаг", "bxr" to "Нютагта туһалагты", "en" to "Support Nyutag"),
        "body" to JsonValue.of("ru" to "Нютаг делает маленькая студия.", "en" to "Nyutag is made by a small studio."),
        "actions" to listOf(
            JsonValue.of("type" to "url", "label" to JsonValue.of("ru" to "Поддержать", "en" to "Support"), "url" to "https://nyutag.chat/"),
            JsonValue.of("type" to "donate", "label" to JsonValue.of("ru" to "Чай разработчику")),
            JsonValue.of("type" to "url", "label" to JsonValue.of("ru" to "Плохая"), "url" to "http://nyutag.chat/"),
            JsonValue.of("type" to "video", "label" to JsonValue.of("ru" to "Неизвестная")),
        ),
        "dismiss" to JsonValue.of("ru" to "Позже"),
    )

    private fun at(day: String, hour: Int = 0): Instant =
        ZonedDateTime.of(LocalDate.parse(day).atTime(hour, 0), zone).toInstant()

    // MARK: Обращения

    @Test fun announcementRead() {
        val one = requireNotNull(Announcement.read(sample, zone))
        assertEquals("2026-10-support", one.id)
        assertEquals("Нютагта туһалагты", one.title.text("bxr"))
        assertEquals("Нютаг делает маленькая студия.", one.body.text("bxr"))
        assertEquals("Nyutag is made by a small studio.", one.body.text("en"))
        assertEquals(2, one.actions.size)
        assertEquals(Announcement.Action.Kind.Url(URI("https://nyutag.chat/")), one.actions[0].kind)
        assertEquals("Поддержать", one.actions[0].label.text("bxr"))
        assertEquals(Announcement.Action.Kind.Donate, one.actions[1].kind)
        assertNull(Announcement.read(JsonValue.of("id" to "x", "title" to JsonValue.of("ru" to "Без текста"))))
        assertNull(Announcement.read(JsonValue.of("title" to JsonValue.of("ru" to "Без id"), "body" to JsonValue.of("ru" to "…"))))
        val plain = requireNotNull(Announcement.read(JsonValue.of("id" to "p", "title" to "Строкой", "body" to "Тоже строкой")))
        assertEquals("Строкой", plain.title.text("en"))
    }

    @Test fun announcementTermsAndOrder() {
        val one = requireNotNull(Announcement.read(sample, zone))
        assertFalse(one.isCurrent(at("2026-10-14", 1)))
        assertTrue(one.isCurrent(at("2026-10-15")))
        assertTrue(one.isCurrent(at("2026-12-31", 23)))
        assertFalse(one.isCurrent(at("2027-01-01")))

        val old = JsonValue.of("id" to "old", "title" to "Старое", "body" to "…", "until" to "2026-10-01")
        val open = JsonValue.of("id" to "open", "title" to "Бессрочное", "body" to "…")
        val broken = JsonValue.of("id" to "", "title" to "x", "body" to "y")
        val list = Announcement.readAll(listOf(old, sample, open, broken), at("2026-11-01"), zone)
        assertEquals(listOf("open", "2026-10-support"), list.map { it.id })
    }

    // MARK: Бурятские буквы

    @Test fun buryatSubstitutesInsideCyrillic() {
        assertEquals("һайн байна", BuryatLetters.fix("hайн байна"))
        assertEquals("Һайн", BuryatLetters.fix("Hайн"))
        assertEquals("үнэр", BuryatLetters.fix("yнэр"))
        assertEquals("өглөө", BuryatLetters.fix("θглөө"))
        assertEquals("өдэр", BuryatLetters.fix("ɵдэр"))
        assertEquals("мэдэһэн", BuryatLetters.fix("мэдэhэн"))
        val text = "hайн yнэр θглөө"
        assertEquals(text.length, BuryatLetters.fix(text)?.length)
    }

    @Test fun buryatKeepsLatin() {
        assertNull(BuryatLetters.fix("hello, how are you"))
        assertNull(BuryatLetters.fix("https://nyutag.chat/h"))
        assertNull(BuryatLetters.fix("сайн байна"))
        assertNull(BuryatLetters.fix("hey привет"))
        assertNull(BuryatLetters.fix(""))
    }

    @Test fun buryatCapitalAfterSentence() {
        assertEquals('Һ', BuryatLetters.letter('һ', ""))
        assertEquals('Ө', BuryatLetters.letter('ө', "Сайн. "))
        assertEquals('ү', BuryatLetters.letter('ү', "сайн "))
        assertEquals('Һ', BuryatLetters.letter('һ', "ой!\n"))
    }

    // MARK: Лунный календарь

    @Test fun nextSagaalgan() {
        assertEquals(LocalDate.of(2027, 2, 7) to 109, Sagaalgan.next(LocalDate.of(2026, 10, 21)))
        assertEquals(0, Sagaalgan.next(LocalDate.of(2026, 2, 18))?.second)
        assertEquals(LocalDate.of(2026, 2, 18), Sagaalgan.next(LocalDate.of(2026, 1, 1))?.first)
        assertNull(Sagaalgan.next(LocalDate.of(2035, 3, 1)))
        assertEquals(2, Sagaalgan.upcoming(2034).size)
    }

    @Test fun zurkhaiTable() {
        val days = ZurkhaiDays.parse("""
            {"from":"2026-01-01",
             "ru":{"strings":["К счастью","Учиться.","Рубить лес."],"days":[[13,1,0,1,2],[14,0,0,2,1]]},
             "en":{"strings":["Luck","Study.","Logging."],"days":[[13,1,0,1,2],[14,0,0,2,1]]}}
        """)
        assertEquals(ZurkhaiDay(13, true, "К счастью", "Учиться.", "Рубить лес."), days.day(LocalDate.of(2026, 1, 1)))
        assertEquals("Logging.", days.day(LocalDate.of(2026, 1, 2), english = true)?.favorable)
        assertNull(days.day(LocalDate.of(2026, 1, 3)))
        assertNull(days.day(LocalDate.of(2025, 12, 31)))
        assertEquals(LocalDate.of(2026, 1, 2), days.last)
    }

    @Test fun bundledZurkhaiCovers2026And2027() {
        val days = ZurkhaiDays.bundled()
        assertEquals(LocalDate.of(2027, 12, 31), days.last)
        assertEquals(13, days.day(LocalDate.of(2026, 1, 1))?.lunarDay)
        assertNotNull(days.day(LocalDate.of(2027, 12, 31), english = true))
    }

    // MARK: Сервис /_nyutag/v1

    @Test fun inviteLinks() {
        assertEquals("ABCD-1234", InviteCode.fromLink("https://nyutag.chat/i/#abcd-1234"))
        assertEquals("ABCD1234", InviteCode.fromLink("nyutag://invite/ABCD1234"))
        assertNull(InviteCode.fromLink("https://example.com/i/#ABCD-1234"))
        assertNull(InviteCode.fromLink("https://nyutag.chat/i/#коротко"))
        assertEquals("https://nyutag.chat/i/#ABCD-1234", InviteCode("ABCD-1234", null).link)
    }

    @Test fun serviceRequests() = runBlocking {
        val stub = StubTransport()
        val service = NyutagService(MatrixClient(URI("http://127.0.0.1:1"), "tok", transport = stub))

        stub.response = JsonValue.of("require_invite" to false, "msisdn" to true)
        val config = service.configuration()
        assertFalse(config.requiresInvite)
        assertTrue(config.phoneAvailable)
        assertFalse(config.emailAvailable)
        assertEquals("/_nyutag/v1/config", stub.last?.url?.path)
        assertNull(stub.last?.header("Authorization"))

        stub.response = JsonValue.of("sid" to "s1")
        assertEquals(60, service.startVerification(ContactMedium.MSISDN, "+79990000000", "ABCD1234").resendAfterSeconds)
        val sent = JsonValue.parse(String(requireNotNull(stub.last?.body), Charsets.UTF_8))
        assertEquals("msisdn", sent["medium"]?.string)

        stub.response = JsonValue.of("code" to "ABCD-1234", "expires_at" to 1_800_000_000_000)
        val invite = service.createInvite()
        assertEquals(Instant.ofEpochMilli(1_800_000_000_000), invite.expiresAt)
        assertEquals("Bearer tok", stub.last?.header("Authorization"))

        stub.response = JsonValue.of("matches" to listOf(
            JsonValue.of("phone" to "+7999", "user_id" to "@a:nyutag.chat"),
            JsonValue.of("phone" to "+7999", "user_id" to "@b:nyutag.chat"),
            JsonValue.of("phone" to "+7000"),
        ))
        assertEquals(mapOf("+7999" to "@a:nyutag.chat"), service.lookup(listOf("+7999", "+7000")))

        stub.response = JsonValue.of("valid" to true)
        assertEquals(true to null, service.checkInvite("ABCD1234"))

        // Без нужного поля — ошибка разбора, а не пустая строка.
        stub.response = JsonValue.of()
        assertTrue((runCatching { service.register("C", "u", "p") }.exceptionOrNull() as? MatrixError)?.kind is MatrixError.Kind.Decoding)
    }
}
