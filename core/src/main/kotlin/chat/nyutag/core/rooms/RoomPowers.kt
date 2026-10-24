// Нютаг. Права в группе и сообществе по `m.room.power_levels`: кто владелец, админ, модератор, что кому можно
// и как выглядит новое содержимое уровней после назначения или «пишут только админы». Создатель комнаты
// 12-й версии (и `additional_creators`) выше всех, хотя в `users` его нет. Формат общий с iOS-клиентом.

package chat.nyutag.core.rooms

import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class RoomPowers(powerLevels: JsonValue? = null, val creators: Set<String> = emptySet()) {
    enum class Role { MEMBER, MODERATOR, ADMIN, OWNER }

    /** Содержимое `m.room.power_levels`; пустое, если события нет. */
    val content: JsonValue = powerLevels ?: JsonValue.Obj(emptyMap())

    private fun number(key: String, fallback: Long): Long = content[key]?.long ?: fallback

    fun level(userID: String): Long {
        if (userID in creators) return Long.MAX_VALUE
        return content["users"]?.get(userID)?.long ?: number("users_default", 0)
    }

    fun role(userID: String): Role {
        val level = level(userID)
        return when {
            level == Long.MAX_VALUE -> Role.OWNER
            level >= ADMIN -> Role.ADMIN
            level >= MODERATOR -> Role.MODERATOR
            else -> Role.MEMBER
        }
    }

    /** Уровень, нужный для события; `state` — событие состояния (по умолчанию `state_default`, иначе `events_default`). */
    fun needed(type: String, state: Boolean): Long =
        content["events"]?.get(type)?.long ?: if (state) number("state_default", 50) else number("events_default", 0)

    fun canSend(userID: String, type: String = EventType.MESSAGE): Boolean = level(userID) >= needed(type, state = false)

    fun canSetState(userID: String, type: String): Boolean = level(userID) >= needed(type, state = true)

    fun canInvite(userID: String): Boolean = level(userID) >= number("invite", 0)

    /** Удалить чужую реплику. */
    fun canRedactOthers(userID: String): Boolean = level(userID) >= number("redact", 50)

    /** Выгнать можно только того, кто ниже тебя. */
    fun canKick(userID: String, target: String): Boolean =
        userID != target && level(userID) >= number("kick", 50) && level(userID) > level(target)

    fun canBan(userID: String, target: String): Boolean =
        userID != target && level(userID) >= number("ban", 50) && level(userID) > level(target)

    /** Поставить `target` уровень `to`: сам должен быть выше цели (или менять себя вниз) и не выдавать больше своего. */
    fun canSetLevel(userID: String, target: String, to: Long): Boolean {
        if (target in creators || !canSetState(userID, EventType.POWER_LEVELS)) return false
        val mine = level(userID)
        if (to > mine) return false
        return userID == target || mine > level(target)
    }

    /** Пишут только те, у кого уровень не ниже модератора. */
    val onlyAdminsWrite: Boolean get() = needed(EventType.MESSAGE, state = false) >= MODERATOR

    /** Содержимое уровней с новым уровнем человека; уровень по умолчанию убирает его из `users`. */
    fun setting(userID: String, level: Long): JsonValue {
        val obj = LinkedHashMap(content.obj.orEmpty())
        val users = LinkedHashMap(obj["users"]?.obj.orEmpty())
        if (level == number("users_default", 0)) users.remove(userID) else users[userID] = JsonValue.Int(level)
        obj["users"] = JsonValue.Obj(users)
        return JsonValue.Obj(obj)
    }

    /** «Пишут только админы»: обычный уровень реплик поднимается до модератора, реакции остаются всем. */
    fun writing(onlyAdmins: Boolean): JsonValue {
        val obj = LinkedHashMap(content.obj.orEmpty())
        val events = LinkedHashMap(obj["events"]?.obj.orEmpty())
        if (onlyAdmins) {
            obj["events_default"] = JsonValue.Int(MODERATOR)
            events[EventType.REACTION] = JsonValue.Int(0)
        } else {
            obj["events_default"] = JsonValue.Int(0)
            events.remove(EventType.REACTION)
        }
        obj["events"] = JsonValue.Obj(events)
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ADMIN = 100L
        const val MODERATOR = 50L

        /** Уровни и создатели из своей базы (состояние комнаты приходит с синхронизацией). */
        fun local(roomID: String, store: Store): RoomPowers {
            val levels = store.state(roomID, EventType.POWER_LEVELS)
            var creators = emptySet<String>()
            val create = store.stateAll(roomID, EventType.CREATE).firstOrNull()
            if (create != null) {
                val version = create.content["room_version"]?.string ?: "1"
                if ((version.toIntOrNull() ?: Int.MAX_VALUE) >= 12) {
                    creators = (listOfNotNull(create.sender) +
                        create.content["additional_creators"]?.array.orEmpty().mapNotNull { it.string }).toSet()
                }
            }
            return RoomPowers(levels, creators)
        }
    }
}

/** Ссылка на сообщество: `https://nyutag.chat/c/#<id>` (страница с кнопкой) и `nyutag://community/<id>` (из неё в приложение). */
object CommunityLink {
    fun web(roomID: String): String = "https://nyutag.chat/c/#" + URLEncoder.encode(roomID, "UTF-8").replace("+", "%20")

    fun roomID(link: String): String? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val raw = when {
            uri.scheme == "nyutag" && uri.host == "community" -> uri.rawPath?.trim('/')?.substringBefore('/')
            uri.scheme == "https" && uri.host == "nyutag.chat" && (uri.path ?: "").startsWith("/c") -> uri.rawFragment
            else -> null
        } ?: return null
        val id = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
        // У комнат 12-й версии id без «:сервер».
        return id.takeIf { it.startsWith("!") && it.length > 1 && '/' !in it }
    }
}

/** Закреплённые реплики комнаты (`m.room.pinned_events`). */
object PinnedEvents {
    const val TYPE = "m.room.pinned_events"

    fun ids(content: JsonValue?): List<String> = content?.get("pinned")?.array.orEmpty().mapNotNull { it.string }

    /** Новое закреплённое — первым; повтор не задваивается. */
    fun pinning(eventID: String, pinned: Boolean, content: JsonValue?): JsonValue {
        val ids = ids(content).filter { it != eventID }.toMutableList()
        if (pinned) ids.add(0, eventID)
        return JsonValue.of("pinned" to JsonValue.Arr(ids.map { JsonValue.Str(it) }))
    }
}

/** Участники комнаты по событиям `m.room.member`: кто внутри, кто стучится (заявка), кто заблокирован. */
class Roster(members: List<Event>) {
    val joined: List<Person>
    val invited: List<Person>
    val knocking: List<Person>
    val banned: List<Person>

    init {
        val groups = HashMap<String, MutableList<Person>>()
        for (event in members) {
            if (event.type != EventType.MEMBER) continue
            val userID = event.stateKey?.ifEmpty { null } ?: continue
            val name = event.content["displayname"]?.string?.ifEmpty { null } ?: ChatDigest.localpart(userID)
            val person = Person(userID = userID, name = name, avatarURL = event.content["avatar_url"]?.string)
            groups.getOrPut(event.content["membership"]?.string ?: "") { ArrayList() }.add(person)
        }
        fun sorted(key: String) = groups[key].orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        joined = sorted("join")
        invited = sorted("invite")
        knocking = sorted("knock")
        banned = sorted("ban")
    }
}
