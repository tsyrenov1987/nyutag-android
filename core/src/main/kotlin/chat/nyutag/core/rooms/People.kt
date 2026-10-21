// Нютаг. Люди для вкладки «Контакты»: с кем уже есть личный чат, кого сервер нашёл по номерам из телефонной
// книги и кто нашёлся поиском по имени. Номера сравниваем в том же виде, что и сервер (+7… для российских).

package chat.nyutag.core.rooms

import chat.nyutag.core.auth.Nickname
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixClient.Method
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue

/** Человек в Нютаге. */
data class Person(
    val userID: String,
    /** Имя из телефонной книги, иначе из профиля, иначе ник. */
    val name: String,
    val avatarURL: String? = null,
    /** Номер из книжки, по которому человек нашёлся. */
    val phone: String? = null,
    /** Уже есть личный чат, куда мы вошли или куда нас позвали. */
    val directRoom: String? = null,
)

/** Запись телефонной книги: имя и номера, как их записал человек. */
data class BookEntry(val id: String, val name: String, val phones: List<String>)

object People {
    private val activeMemberships = setOf("join", "invite")
    /** Имена по-людски, как в iOS: сначала кириллица (бурятские и русские имена), потом остальное, без учёта регистра. */
    internal val names: Comparator<String> = compareBy<String> { if (it.firstOrNull()?.let(::isCyrillic) == true) 0 else 1 }
        .then { a, b -> collator.compare(a, b) }
    private val collator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale("ru")).also { it.strength = java.text.Collator.SECONDARY }
    private val byName: Comparator<Person> = compareBy(names) { it.name }

    private fun isCyrillic(ch: Char): Boolean = Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC

    /** Номер в виде, в каком его хранит сервер: `+` и 10–15 цифр; российские 8… и 9… приводим к +7. */
    fun phoneKey(raw: String): String? {
        val trimmed = raw.trim()
        var digits = trimmed.filter { it.code < 128 && it.isDigit() }
        if (trimmed.startsWith("+")) {
            // Код страны уже есть.
        } else if (digits.length == 11 && (digits[0] == '7' || digits[0] == '8')) {
            digits = "7" + digits.drop(1)
        } else if (digits.length == 10 && digits[0] == '9') {
            digits = "7$digits"
        }
        return if (digits.length in 10..15) "+$digits" else null
    }

    /** Все разные номера книжки, по одному на нормальный вид: столько уйдёт на сверку. */
    fun uniquePhones(book: List<BookEntry>): List<String> {
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        for (entry in book) for (phone in entry.phones) {
            val key = phoneKey(phone) ?: continue
            if (seen.add(key)) result.add(phone)
        }
        return result
    }

    /** Собеседники из `m.direct`: комната, где мы ещё состоим, и имя из её участников. */
    fun known(store: Store, me: String): List<Person> {
        val direct = store.accountData(EventType.DIRECT)?.obj ?: emptyMap()
        val people = ArrayList<Person>()
        for ((userID, rooms) in direct) {
            if (userID == me) continue
            var foundRoom: String? = null
            var foundName: String? = null
            var foundAvatar: String? = null
            for (roomID in (rooms.array ?: emptyList()).mapNotNull { it.string }) {
                val record = store.room(roomID) ?: continue
                if (record.membership != RoomRecord.Membership.JOIN && record.membership != RoomRecord.Membership.INVITE) continue
                val member = store.state(roomID, EventType.MEMBER, userID)
                foundRoom = roomID
                foundName = member?.get("displayname")?.string
                foundAvatar = member?.get("avatar_url")?.string
                if (record.membership == RoomRecord.Membership.JOIN) break
            }
            if (foundRoom == null) continue
            val name = foundName?.takeIf { it.isNotEmpty() } ?: ChatDigest.localpart(userID)
            people.add(Person(userID, name, foundAvatar, directRoom = foundRoom))
        }
        return people
    }

    /** Собеседник чата один на один: по `m.direct`, а если чат там не отмечен — единственный другой
     *  участник комнаты на двоих (участников сервер шлёт лениво, поэтому сначала смотрим сводку комнаты). */
    fun partner(roomID: String, store: Store, me: String): String? {
        val direct = store.accountData(EventType.DIRECT)?.obj ?: emptyMap()
        val marked = direct.entries.firstOrNull { (user, rooms) -> user != me && (rooms.array ?: emptyList()).contains(JsonValue.Str(roomID)) }
        if (marked != null) return marked.key
        val room = store.room(roomID)
        if (room != null && room.joinedCount + room.invitedCount == 2 && room.heroes.size == 1) {
            val hero = room.heroes.firstOrNull { it != me }
            if (hero != null) return hero
        }
        val members = store.stateAll(roomID, EventType.MEMBER)
            .filter { it.content["membership"]?.string in activeMemberships }
            .map { it.stateKey }
        if (members.size != 2 || me !in members) return null
        return members.firstOrNull { it != me }
    }

    data class Merged(val inNyutag: List<Person>, val invite: List<BookEntry>)

    /** Кто в Нютаге: собеседники плюс найденные по номерам. Имя из книжки главнее имени из профиля.
     *  Остальные записи книжки — те, кого можно позвать. */
    fun merge(book: List<BookEntry>, matches: Map<String, String>, known: List<Person>): Merged {
        val byID = LinkedHashMap<String, Person>()
        for (person in known) byID[person.userID] = person
        val byKey = HashMap<String, String>()
        for ((phone, user) in matches) phoneKey(phone)?.let { byKey.putIfAbsent(it, user) }
        val invite = ArrayList<BookEntry>()
        for (entry in book) {
            var hit: Pair<String, String>? = null
            for (phone in entry.phones) {
                val user = phoneKey(phone)?.let { byKey[it] } ?: continue
                hit = phone to user
                break
            }
            if (hit == null) {
                invite.add(entry)
                continue
            }
            val (phone, userID) = hit
            var person = byID[userID] ?: Person(userID, entry.name)
            if (entry.name.isNotEmpty()) person = person.copy(name = entry.name)
            person = person.copy(phone = person.phone ?: phone)
            byID[userID] = person
        }
        return Merged(byID.values.sortedWith(byName), invite.sortedWith(compareBy(names) { it.name }))
    }

    /** Подходит ли человек под строку поиска: по имени, нику или цифрам номера. */
    fun matches(query: String, name: String, userID: String? = null, phones: List<String> = emptyList()): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        if (name.contains(q, ignoreCase = true)) return true
        if (userID != null && userID.contains(if (q.startsWith("@")) q.drop(1) else q, ignoreCase = true)) return true
        val digits = q.filter { it.isDigit() }
        if (digits.length < 3 || digits.length != q.count { it !in " +-()" }) return false
        // 8 912… и +7 912… — один номер: российский код в начале запроса не обязателен.
        val tail = if (digits[0] in "78") digits.drop(1) else digits
        return phones.any { phone ->
            listOf(phone.filter { it.isDigit() }, phoneKey(phone)?.filter { it.isDigit() } ?: "")
                .any { it.contains(digits) || it.contains(tail) }
        }
    }

    /** Все, с кем есть общие чаты (кроме сообществ и себя): собеседники из `m.direct` плюс участники
     *  вошедших комнат. Из них зовут в новую группу. Имя из личного чата главнее. */
    fun acquaintances(store: Store, me: String): List<Person> {
        val byID = HashMap<String, Person>()
        for (room in store.rooms(RoomRecord.Membership.JOIN)) {
            if (room.isSpace) continue
            for (person in members(room.id, store, me)) byID.putIfAbsent(person.userID, person)
        }
        for (person in known(store, me)) byID[person.userID] = person
        return byID.values.sortedWith(byName)
    }

    /** Участники комнаты (вошедшие и приглашённые), кроме себя: имя из записи участника, иначе ник. */
    fun members(roomID: String, store: Store, me: String): List<Person> = store.stateAll(roomID, EventType.MEMBER)
        .filter { it.stateKey != me && (it.content["membership"]?.string ?: "") in activeMemberships }
        .map { member ->
            val name = member.content["displayname"]?.string?.takeIf { it.isNotEmpty() }
            Person(member.stateKey, name ?: ChatDigest.localpart(member.stateKey), member.content["avatar_url"]?.string)
        }
        .sortedWith(byName)
}

/** POST /v3/user_directory/search: кого сервер покажет по имени или нику. */
suspend fun MatrixClient.searchUsers(term: String, limit: Int = 20): List<Person> {
    val json = request(Method.POST, "user_directory/search", body = JsonValue.of("search_term" to term, "limit" to limit))
    return (json["results"]?.array ?: emptyList()).mapNotNull { item ->
        val userID = item["user_id"]?.string ?: return@mapNotNull null
        val name = item["display_name"]?.string?.takeIf { it.isNotEmpty() } ?: ChatDigest.localpart(userID)
        Person(userID, name, item["avatar_url"]?.string)
    }
}

/** Человек по точному нику: каталог сервера показывает только тех, с кем есть общие комнаты,
 *  а ник новый знакомый мог продиктовать. Нет такого (404) — `null`. */
suspend fun MatrixClient.person(nick: String, server: String): Person? {
    val clean = Nickname.clean(nick)
    if (!Nickname.isValid(clean)) return null
    val userID = "@$clean:$server"
    return try {
        val json = request(Method.GET, "profile/$userID")
        Person(userID, json["displayname"]?.string?.takeIf { it.isNotEmpty() } ?: clean, json["avatar_url"]?.string)
    } catch (e: MatrixError) {
        if (e.status == 404) null else throw e
    }
}
