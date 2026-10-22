// Нютаг. Сервис `/_nyutag/v1` на ядре: приглашения, регистрация, вход по телефону или почте, сброс пароля,
// поиск друзей по номерам, обращения разработчика, состав дверей к ядру.
// В бою он за тем же адресом, что и Tuwunel (nginx разводит пути); на тестовом стенде отдельный порт.

package chat.nyutag.core.nyutag

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixClient.Method
import chat.nyutag.core.net.MatrixError
import chat.nyutag.json.JsonValue
import java.net.URI
import java.time.Instant

data class SignUpConfiguration(
    val requiresInvite: Boolean,
    val requiresContact: Boolean,
    val emailAvailable: Boolean,
    val phoneAvailable: Boolean,
    val requiresEmail: Boolean,
    val requiresPhone: Boolean,
    val phoneUnverified: Boolean,
)

data class Verification(val sid: String, val resendAfterSeconds: Int)

data class InviteCode(val code: String, val expiresAt: Instant?) {
    /** Страница приглашения на сайте; код после `#` браузер на сервер сайта не отправляет. */
    val link: String get() = "https://nyutag.chat/i/#$code"

    companion object {
        private val shape = Regex("[0-9A-Z]{4}-?[0-9A-Z]{4}")

        /** Код из ссылки `https://nyutag.chat/i/#КОД` или `nyutag://invite/КОД`; чужая ссылка — `null`. */
        fun fromLink(link: String): String? {
            val url = runCatching { URI(link) }.getOrNull() ?: return null
            val scheme = url.scheme?.lowercase()
            val host = url.host?.lowercase()
            val raw = when {
                scheme == "nyutag" && host == "invite" -> url.path?.split('/')?.firstOrNull { it.isNotEmpty() }
                scheme == "https" && host == "nyutag.chat" && url.path.orEmpty().startsWith("/i") -> url.fragment
                else -> null
            } ?: return null
            return raw.uppercase().takeIf(shape::matches)
        }
    }
}

data class IssuedInvite(val invite: InviteCode, val usedBy: String?)

enum class ContactMedium(val wire: String) { EMAIL("email"), MSISDN("msisdn") }

/** `client` с токеном нужен для запросов от имени участника (приглашения, поиск по номерам, свой номер). */
class NyutagService(private val client: MatrixClient) {
    constructor(baseURL: URI, accessToken: String? = null) : this(MatrixClient(baseURL, accessToken))

    private suspend fun call(method: Method, name: String, body: JsonValue? = null, authenticated: Boolean = false): JsonValue =
        client.raw(method, "/_nyutag/v1/$name", body = body, authenticated = authenticated)

    private fun required(json: JsonValue, key: String, name: String): String =
        json[key]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("$name: нет $key"))

    private fun instant(json: JsonValue?): Instant? = json?.long?.let(Instant::ofEpochMilli)

    // MARK: Без входа

    suspend fun configuration(): SignUpConfiguration {
        val json = call(Method.GET, "config")
        return SignUpConfiguration(requiresInvite = json["require_invite"]?.bool ?: true,
                                   requiresContact = json["require_contact"]?.bool ?: false,
                                   emailAvailable = json["email"]?.bool ?: false,
                                   phoneAvailable = json["msisdn"]?.bool ?: false,
                                   requiresEmail = json["require_email"]?.bool ?: false,
                                   requiresPhone = json["require_phone"]?.bool ?: false,
                                   phoneUnverified = json["phone_unverified"]?.bool ?: false)
    }

    /** Обращения разработчика: сырой список, язык и кнопки разбирает [Announcement]. */
    suspend fun announcements(): List<JsonValue> = call(Method.GET, "announce")["announcements"]?.array.orEmpty()

    /** Годные на сегодня объявления, свежие сверху. */
    suspend fun currentAnnouncements(now: Instant = Instant.now()): List<Announcement> =
        Announcement.readAll(announcements(), now)

    /** Состав дверей к ядру `{"version", "paths", "retired", "doors"}`. */
    suspend fun catalogue(): JsonValue = call(Method.GET, "catalogue")

    /** Годен ли код приглашения и Matrix ID пригласившего, если сервер его сообщает. */
    suspend fun checkInvite(code: String): Pair<Boolean, String?> {
        val json = call(Method.POST, "invites/check", JsonValue.of("code" to code))
        return (json["valid"]?.bool == true) to json["inviter"]?.string
    }

    suspend fun startVerification(medium: ContactMedium, address: String, inviteCode: String): Verification {
        val json = call(Method.POST, "verify/start",
                        JsonValue.of("medium" to medium.wire, "address" to address, "code" to inviteCode))
        return Verification(required(json, "sid", "verify/start"), json["resend_after_s"]?.int ?: 60)
    }

    suspend fun checkVerification(sid: String, token: String): Boolean =
        call(Method.POST, "verify/check", JsonValue.of("sid" to sid, "token" to token))["verified"]?.bool == true

    /** Номер годен и не занят; возвращает его в виде `+7…`. */
    suspend fun checkPhone(phone: String, inviteCode: String): String =
        required(call(Method.POST, "phone/check", JsonValue.of("phone" to phone, "code" to inviteCode)), "phone", "phone/check")

    /** Регистрация по приглашению; возвращает Matrix ID, войти потом обычным паролем. */
    suspend fun register(code: String, username: String, password: String, sid: String? = null, phone: String? = null): String {
        val body = linkedMapOf<String, JsonValue>("code" to JsonValue.Str(code), "username" to JsonValue.Str(username),
                                                  "password" to JsonValue.Str(password))
        sid?.let { body["sid"] = JsonValue.Str(it) }
        phone?.let { body["phone"] = JsonValue.Str(it) }
        return required(call(Method.POST, "register", JsonValue.Obj(body)), "user_id", "register")
    }

    /** Вход по телефону или почте: Matrix ID аккаунта, если пароль верный. */
    suspend fun resolve(identifier: String, password: String): String =
        required(call(Method.POST, "resolve", JsonValue.of("identifier" to identifier, "password" to password)),
                 "user_id", "resolve")

    suspend fun startPasswordReset(address: String): Verification {
        val json = call(Method.POST, "password/start", JsonValue.of("address" to address))
        return Verification(required(json, "sid", "password/start"), json["resend_after_s"]?.int ?: 60)
    }

    suspend fun resetPassword(sid: String, token: String, password: String): String =
        required(call(Method.POST, "password/reset", JsonValue.of("sid" to sid, "token" to token, "password" to password)),
                 "user_id", "password/reset")

    // MARK: От имени участника

    /** Одноразовый код приглашения (живёт 7 дней). */
    suspend fun createInvite(): InviteCode {
        val json = call(Method.POST, "invites", authenticated = true)
        return InviteCode(required(json, "code", "invites"), instant(json["expires_at"]))
    }

    /** Свои выпущенные коды (последние 50, без отозванных); `usedBy` у использованных. */
    suspend fun invites(): List<IssuedInvite> =
        call(Method.GET, "invites", authenticated = true)["invites"]?.array.orEmpty().mapNotNull { item ->
            val code = item["code"]?.string ?: return@mapNotNull null
            IssuedInvite(InviteCode(code, instant(item["expires_at"])), item["used_by"]?.string)
        }

    /** Отзыв неиспользованного кода. */
    suspend fun revokeInvite(code: String) {
        call(Method.DELETE, "invites/$code", authenticated = true)
    }

    /** Номер (как передан) → Matrix ID. Ненайденных в ответе нет. */
    suspend fun lookup(phones: List<String>): Map<String, String> {
        val json = call(Method.POST, "lookup", JsonValue.of("phones" to phones), authenticated = true)
        val result = LinkedHashMap<String, String>()
        for (match in json["matches"]?.array.orEmpty()) {
            val phone = match["phone"]?.string ?: continue
            val userID = match["user_id"]?.string ?: continue
            result.putIfAbsent(phone, userID)
        }
        return result
    }

    /** Есть ли номер участника среди переданных номеров (сервер отвечает да или нет и номера не хранит). */
    suspend fun isInContacts(userID: String, phones: List<String>): Boolean =
        call(Method.POST, "contact-check", JsonValue.of("user_id" to userID, "phones" to phones),
             authenticated = true)["in_contacts"]?.bool == true

    suspend fun hasPhone(): Boolean = call(Method.GET, "me", authenticated = true)["phone"]?.bool == true

    suspend fun setPhone(phone: String) {
        call(Method.POST, "me/phone", JsonValue.of("phone" to phone), authenticated = true)
    }
}
