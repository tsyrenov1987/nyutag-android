// Нютаг. Лента одного чата из локальной базы: реплики и служебные плашки, уже собранные для показа.
// Правки подставлены в исходную реплику, удалённые помечены, реакции посчитаны, ответ несёт короткую цитату.
// Расшифровку, как и в списке чатов, подставляет приложение.

package chat.nyutag.core.rooms

import chat.nyutag.core.media.Attachment as MediaAttachment
import chat.nyutag.core.push.PushPreview
import chat.nyutag.core.push.PushReader
import chat.nyutag.core.store.StateEntry
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue

data class FeedItem(
    val id: String,
    val sender: String,
    val senderName: String,
    val isMine: Boolean,
    val ts: Long,
    val body: Body,
    val edited: Boolean,
    val reply: Quote?,
    val reactions: List<Reaction>,
    /** Файл или картинка реплики, если она с вложением. */
    val attachment: MediaAttachment?,
    /** Только у своих реплик: прочитал ли кто-то из собеседников её или более позднюю. */
    val isRead: Boolean,
    /** Переслано из другого чата. */
    val forwarded: Boolean = false,
) {
    /** Что случилось в комнате, кроме реплик. Имена уже человеческие. */
    sealed class Notice {
        data class Created(val by: String) : Notice()
        data class Joined(val name: String) : Notice()
        data class Left(val name: String) : Notice()
        data class Invited(val name: String, val by: String) : Notice()
        data class Removed(val name: String, val by: String) : Notice()
        data class Banned(val name: String, val by: String) : Notice()
        data class RenamedSelf(val from: String, val to: String) : Notice()
        data class RoomNamed(val name: String, val by: String) : Notice()
        data class RoomPhoto(val by: String) : Notice()
        data class RoomTopic(val topic: String, val by: String) : Notice()
        data class EncryptionOn(val by: String) : Notice()
    }

    sealed class Body {
        data class Text(val text: String) : Body()
        /** Вложение, стикер, опрос, звонок: вид и подпись, если есть. */
        data class Attachment(val kind: PushPreview.Kind, val caption: String?) : Body()
        /** Зашифровано, ключа пока нет. */
        object Locked : Body()
        object Deleted : Body()
        data class Notice(val notice: FeedItem.Notice) : Body()
    }

    data class Reaction(
        val key: String,
        val count: Int,
        val mine: Boolean,
        /** Наша реакция с этим ключом: её удаляем, когда человек снимает реакцию. */
        val myEventID: String? = null,
    )

    data class Quote(val eventID: String, val senderName: String, val preview: PushPreview.Kind)

    val isNotice: Boolean get() = body is Body.Notice

    /** Свой текст можно поправить. */
    val canEdit: Boolean get() = isMine && body is Body.Text

    /** Своё можно удалить, пока оно не удалено. */
    val canDelete: Boolean get() = isMine && !isNotice && body != Body.Deleted

    /** Пожаловаться можно на чужую реплику, которую видно. */
    val canReport: Boolean get() = !isMine && !isNotice && body != Body.Deleted && body != Body.Locked
}

object ChatFeed {
    private class Tally(var count: Int = 0, var mine: String? = null)

    /** Последние `limit` событий комнаты, собранные в ленту: старые первыми.
     *  `ignored` — блокированные: их реплики, реакции и правки в ленту не попадают. */
    suspend fun items(
        roomID: String, store: Store, me: String, limit: Int = 80, ignored: Set<String> = emptySet(),
        decrypt: suspend (Event, String) -> Event? = { _, _ -> null },
    ): List<FeedItem> {
        val members = store.stateAll(roomID, EventType.MEMBER)
        val names = HashMap<String, String>()
        for (member in members) {
            val name = member.content["displayname"]?.string
            if (!name.isNullOrEmpty()) names[member.stateKey] = name
        }
        fun name(userID: String): String = names[userID] ?: ChatDigest.localpart(userID)

        val opened = ArrayList<Event>()
        for (event in store.events(roomID, limit)) {
            val sender = event.sender
            if (sender != null && sender in ignored && event.type != EventType.MEMBER) continue
            if (event.type == EventType.ENCRYPTED && !event.isRedacted) {
                opened.add(decrypt(event, roomID) ?: event)
            } else {
                opened.add(event)
            }
        }

        // Сначала служебное о других событиях: правки, реакции, удаления.
        val edits = HashMap<String, JsonValue>()
        val reactions = HashMap<String, LinkedHashMap<String, Tally>>()
        val removed = HashSet<String>()
        val authors = HashMap<String, String>()
        for (event in opened) {
            val id = event.eventID
            val sender = event.sender
            if (id != null && sender != null) authors[id] = sender
        }
        // Удаления — первыми: снятая реакция может стоять в ленте раньше своего удаления только по времени.
        for (event in opened) {
            if (event.type != EventType.REDACTION || event.isRedacted) continue
            val target = event.content["redacts"]?.string ?: event.unsigned?.get("redacts")?.string
            if (target != null) removed.add(target)
        }
        for (event in opened) {
            if (event.isRedacted || event.eventID?.let { it in removed } == true) continue
            val relation = event.content["m.relates_to"]
            when (event.type) {
                EventType.REDACTION -> {}
                EventType.REACTION -> {
                    if (relation?.get("rel_type")?.string != "m.annotation") continue
                    val target = relation["event_id"]?.string ?: continue
                    val key = relation["key"]?.string ?: continue
                    val tally = reactions.getOrPut(target) { LinkedHashMap() }.getOrPut(key) { Tally() }
                    tally.count += 1
                    if (tally.mine == null && event.sender == me) tally.mine = event.eventID
                }
                else -> {
                    // Правку принимаем только от автора исходной реплики.
                    if (relation?.get("rel_type")?.string != "m.replace") continue
                    val target = relation["event_id"]?.string ?: continue
                    val fresh = event.content["m.new_content"] ?: continue
                    val author = authors[target]
                    if (author == null || author == event.sender) edits[target] = fresh
                }
            }
        }

        // Вход создателя сразу после создания — та же новость, что «чат создан».
        val creator = opened.firstOrNull { it.type == EventType.CREATE }?.sender

        val readUpTo = othersReadTS(roomID, members, me, store)
        val byID = HashMap<String, Event>()
        for (event in opened) event.eventID?.let { byID[it] = event }

        val items = ArrayList<FeedItem>()
        for (event in opened) {
            var shownContent = event.content
            val id = event.eventID ?: continue
            val sender = event.sender ?: continue
            if (ChatDigest.isEdit(event)) continue
            val body: FeedItem.Body
            if (event.isRedacted || id in removed) {
                if (event.type != EventType.MESSAGE && event.type != EventType.ENCRYPTED && event.type != EventType.STICKER) continue
                body = FeedItem.Body.Deleted
            } else if (event.type == EventType.ENCRYPTED) {
                body = FeedItem.Body.Locked
            } else {
                val notice = notice(event) { name(it) }
                if (notice != null) {
                    if (notice is FeedItem.Notice.Joined && event.stateKey == creator &&
                        event.unsigned?.at("prev_content", "membership") == null) continue
                    body = FeedItem.Body.Notice(notice)
                } else {
                    body = bubble(event, edits[id], me) ?: continue
                    edits[id]?.let { shownContent = it }
                }
            }
            var reply: FeedItem.Quote? = null
            if (body !is FeedItem.Body.Notice) {
                val target = event.content.at("m.relates_to", "m.in_reply_to", "event_id")?.string
                if (target != null) {
                    var quoted = byID[target]
                    if (quoted == null) {
                        val stored = store.event(target)
                        if (stored != null) quoted = if (stored.type == EventType.ENCRYPTED) (decrypt(stored, roomID) ?: stored) else stored
                    }
                    val author = quoted?.sender
                    if (quoted != null && author != null) {
                        val kind = if (quoted.type == EventType.ENCRYPTED) PushPreview.Kind.Locked else PushReader.kind(quoted, me)
                        reply = FeedItem.Quote(target, name(author), kind)
                    }
                }
            }
            val counted = reactions[id]?.map { (key, tally) -> FeedItem.Reaction(key, tally.count, tally.mine != null, tally.mine) } ?: emptyList()
            val ts = event.originServerTS ?: 0
            val attachment = when {
                body is FeedItem.Body.Attachment && body.kind == PushPreview.Kind.Sticker -> MediaAttachment.sticker(shownContent)
                body is FeedItem.Body.Attachment -> MediaAttachment.read(shownContent)
                else -> null
            }
            val deleted = body == FeedItem.Body.Deleted
            items.add(FeedItem(
                id = id, sender = sender, senderName = name(sender), isMine = sender == me, ts = ts, body = body,
                edited = edits[id] != null && !deleted, reply = reply, reactions = if (deleted) emptyList() else counted,
                attachment = attachment, isRead = sender == me && readUpTo != null && ts <= readUpTo,
                forwarded = !deleted && event.content[Forwarding.FORWARDED_KEY]?.bool == true,
            ))
        }
        return items
    }

    internal fun bubble(event: Event, replaced: JsonValue?, me: String): FeedItem.Body? {
        val shown = if (replaced != null) event.copy(content = replaced, stateKey = null, unsigned = null) else event
        return when (val kind = PushReader.kind(shown, me)) {
            is PushPreview.Kind.Text -> FeedItem.Body.Text(kind.text)
            PushPreview.Kind.Other, PushPreview.Kind.Invite, PushPreview.Kind.Locked, PushPreview.Kind.Signal -> null
            else -> {
                val caption = shown.content["body"]?.string
                // Имя файла в body — не подпись; подпись есть, только если рядом лежит отдельное filename.
                val hasCaption = shown.content["filename"]?.string?.let { it != caption } ?: false
                FeedItem.Body.Attachment(kind, if (hasCaption) caption else null)
            }
        }
    }

    internal fun notice(event: Event, name: (String) -> String): FeedItem.Notice? {
        val sender = event.sender ?: return null
        val content = event.content
        return when (event.type) {
            EventType.CREATE -> FeedItem.Notice.Created(name(sender))
            EventType.NAME -> {
                val title = content["name"]?.string
                if (title.isNullOrEmpty()) null else FeedItem.Notice.RoomNamed(title, name(sender))
            }
            EventType.AVATAR -> FeedItem.Notice.RoomPhoto(name(sender))
            EventType.TOPIC -> {
                val topic = content["topic"]?.string
                if (topic.isNullOrEmpty()) null else FeedItem.Notice.RoomTopic(topic, name(sender))
            }
            EventType.ENCRYPTION -> FeedItem.Notice.EncryptionOn(name(sender))
            EventType.MEMBER -> {
                val target = event.stateKey ?: return null
                val before = event.unsigned?.get("prev_content")
                val was = before?.get("membership")?.string
                val shownName = content["displayname"]?.string ?: name(target)
                when (content["membership"]?.string) {
                    "join" -> {
                        if (was == "join") {
                            val old = before?.get("displayname")?.string ?: ChatDigest.localpart(target)
                            val new = content["displayname"]?.string
                            if (new == null || new == old) null else FeedItem.Notice.RenamedSelf(old, new)
                        } else {
                            FeedItem.Notice.Joined(shownName)
                        }
                    }
                    "invite" -> FeedItem.Notice.Invited(shownName, name(sender))
                    "leave" -> {
                        val oldName = before?.get("displayname")?.string ?: name(target)
                        if (sender != target) FeedItem.Notice.Removed(oldName, name(sender)) else FeedItem.Notice.Left(oldName)
                    }
                    "ban" -> FeedItem.Notice.Banned(before?.get("displayname")?.string ?: name(target), name(sender))
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Самая поздняя отметка прочтения собеседников (время прочитанного события). */
    internal fun othersReadTS(roomID: String, members: List<StateEntry>, me: String, store: Store): Long? {
        var latest: Long? = null
        for (member in members) {
            if (member.stateKey == me || member.content["membership"]?.string != "join") continue
            val receipted = store.receipt(roomID, member.stateKey) ?: continue
            val seen = store.event(receipted) ?: continue
            latest = maxOf(latest ?: 0, seen.originServerTS ?: 0)
        }
        return latest
    }
}
