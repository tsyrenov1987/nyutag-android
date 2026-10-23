// Нютаг. Строки списка чатов из локальной базы: заголовок, последняя реплика, счётчики.
// Работает без сети: список на запуске строится до первого /sync. Расшифровку подставляет приложение
// (машину держит оно); если ключа нет, реплика остаётся `Locked`, а подпись выбирает интерфейс.

package chat.nyutag.core.rooms

import chat.nyutag.core.push.PushPreview
import chat.nyutag.core.push.PushReader
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.store.StateEntry
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType

data class ChatLine(
    val id: String,
    /** Пусто, если у комнаты нет ни имени, ни собеседников (интерфейс пишет «Пустой чат»). */
    val title: String,
    val avatarURL: String?,
    val membership: RoomRecord.Membership,
    val isDirect: Boolean,
    val isEncrypted: Boolean,
    val isSpace: Boolean,
    /** Последняя показываемая реплика; `null` — в базе пока нет ни одной. */
    val last: PushPreview.Kind?,
    val lastSenderName: String?,
    val lastIsMine: Boolean,
    val delivery: Delivery,
    val lastTS: Long,
    val unread: Int,
    val mentions: Int,
) {
    /** Судьба своей последней реплики (галочки в списке): дошла до сервера или её уже прочитали. */
    enum class Delivery { NONE, SENT, READ }
}

object ChatDigest {
    /** Сколько последних событий просматривать в поисках реплики (реакции и служебные события пропускаются).
     *  Один звонок оставляет пачку сигналов (ring + шифрованные offer/answer/end), поэтому окно с запасом:
     *  цикл всё равно останавливается на первой реплике. */
    internal const val LOOKBACK = 300

    private val activeMemberships = setOf("join", "invite")

    /** Все комнаты, кроме покинутых: приглашения сверху, дальше по времени последнего события.
     *  `ignored` — блокированные: их реплики не становятся последней строкой чата. */
    suspend fun lines(
        store: Store, me: String, ignored: Set<String> = emptySet(),
        decrypt: suspend (Event, String) -> Event? = { _, _ -> null },
    ): List<ChatLine> {
        val lines = ArrayList<ChatLine>()
        for (record in store.rooms()) {
            if (record.membership == RoomRecord.Membership.LEAVE) continue
            lines.add(line(record, store, me, ignored, decrypt))
        }
        return lines.sortedWith { a, b ->
            val aInvite = a.membership == RoomRecord.Membership.INVITE
            val bInvite = b.membership == RoomRecord.Membership.INVITE
            when {
                aInvite != bInvite -> if (aInvite) -1 else 1
                a.lastTS != b.lastTS -> b.lastTS.compareTo(a.lastTS)
                else -> a.id.compareTo(b.id)
            }
        }
    }

    internal suspend fun line(
        record: RoomRecord, store: Store, me: String, ignored: Set<String> = emptySet(),
        decrypt: suspend (Event, String) -> Event?,
    ): ChatLine {
        val members = store.stateAll(record.id, EventType.MEMBER)
        val names = HashMap<String, String>()
        for (member in members) {
            val name = member.content["displayname"]?.string
            if (!name.isNullOrEmpty()) names[member.stateKey] = name
        }
        fun name(userID: String): String = names[userID] ?: localpart(userID)
        fun isActive(member: StateEntry): Boolean =
            member.stateKey != me && (member.content["membership"]?.string ?: "") in activeMemberships

        var last: PushPreview.Kind? = null
        var lastEvent: Event? = null
        // Идём от новых к старым: удаления и правки встречаются раньше реплик, к которым относятся.
        val removed = HashSet<String>()
        val edits = HashMap<String, Event>()
        // Непрочитанное считаем сами: сервер не отличает шифрованные сигналы звонка от реплик и считает их тоже.
        // Видимые чужие реплики после своей отметки прочтения или своей реплики.
        val readID = store.receipt(record.id, me)
        val readTS = readID?.let { store.event(it) }?.originServerTS
        var counted = 0
        var reachedRead = record.notificationCount == 0
        for (event in store.events(record.id, LOOKBACK).asReversed()) {
            // Дошли до создания комнаты — вся история перед глазами, счёт точный.
            if (event.type == EventType.CREATE) reachedRead = true
            if (!reachedRead && (event.eventID == readID || (readTS != null && (event.originServerTS ?: 0) <= readTS))) reachedRead = true
            if (lastEvent != null && reachedRead) break
            if (event.type == EventType.REDACTION) {
                val target = event.content["redacts"]?.string ?: event.unsigned?.get("redacts")?.string
                if (target != null) removed.add(target)
                continue
            }
            if (event.isRedacted || event.eventID?.let { it in removed } == true) continue
            val sender = event.sender
            if (sender != null && sender in ignored) continue
            var shown = event
            var kind: PushPreview.Kind
            if (event.type == EventType.ENCRYPTED) {
                val opened = decrypt(event, record.id)
                if (opened != null) {
                    shown = opened
                    kind = PushReader.kind(opened, me)
                } else {
                    kind = PushPreview.Kind.Locked
                }
            } else {
                kind = PushReader.kind(event, me)
            }
            if (isEdit(shown)) {
                val target = shown.content.at("m.relates_to", "event_id")?.string
                if (target != null && edits[target] == null) edits[target] = shown
                continue
            }
            // Удалённое не показываем, правку — только от автора реплики.
            val id = shown.eventID
            val edit = if (id != null) edits[id] else null
            val fresh = edit?.content?.get("m.new_content")
            if (edit != null && edit.sender == shown.sender && fresh != null) {
                shown = shown.copy(content = fresh, stateKey = null, unsigned = null)
                kind = PushReader.kind(shown, me)
            }
            if (kind == PushPreview.Kind.Other || kind == PushPreview.Kind.Signal) continue
            // Своё старое приглашение в чате, куда уже вошли, — не реплика.
            if (kind == PushPreview.Kind.Invite && record.membership != RoomRecord.Membership.INVITE) continue
            if (lastEvent == null) {
                last = kind
                lastEvent = shown
            }
            if (shown.sender == me) reachedRead = true
            if (!reachedRead) counted++
        }
        val unread = if (reachedRead) minOf(counted, record.notificationCount) else record.notificationCount

        val title: String
        val roomName = record.name
        val alias = record.canonicalAlias
        if (!roomName.isNullOrEmpty()) {
            title = roomName
        } else if (!alias.isNullOrEmpty()) {
            title = alias
        } else {
            var others = record.heroes.filter { it != me }
            if (others.isEmpty()) others = members.filter(::isActive).map { it.stateKey }.sorted()
            title = others.take(3).map { name(it) }.joinToString(", ")
        }

        // Картинка: у комнаты своя, иначе у личного чата — аватар собеседника из его записи участника.
        // Комната с именем — группа, даже если в ней пока двое: ей чужое фото не даём.
        var avatarURL = record.avatarURL
        if (avatarURL == null && (record.isDirect || record.name == null)) {
            val others = members.filter(::isActive)
            if (others.size == 1) avatarURL = others[0].content["avatar_url"]?.string
        }

        val sender = lastEvent?.sender
        var delivery = ChatLine.Delivery.NONE
        if (lastEvent != null && sender == me) {
            delivery = if (isRead(lastEvent, record.id, members, me, store)) ChatLine.Delivery.READ else ChatLine.Delivery.SENT
        }
        return ChatLine(
            id = record.id, title = title, avatarURL = avatarURL, membership = record.membership,
            isDirect = record.isDirect, isEncrypted = record.isEncrypted, isSpace = record.isSpace,
            last = last, lastSenderName = sender?.let { name(it) }, lastIsMine = sender == me, delivery = delivery,
            lastTS = lastEvent?.originServerTS ?: record.latestTS,
            unread = unread, mentions = record.highlightCount,
        )
    }

    /** Прочитано, если кто-то из участников отметил прочтение этой реплики или более поздней. */
    internal fun isRead(event: Event, roomID: String, members: List<StateEntry>, me: String, store: Store): Boolean {
        val sentAt = event.originServerTS ?: 0
        for (member in members) {
            if (member.stateKey == me || member.content["membership"]?.string != "join") continue
            val receipted = store.receipt(roomID, member.stateKey) ?: continue
            if (receipted == event.eventID) return true
            val seen = store.event(receipted)
            if (seen != null && (seen.originServerTS ?: 0) >= sentAt) return true
        }
        return false
    }

    /** Правка уже показанной реплики не двигает список: последней остаётся сама реплика. */
    internal fun isEdit(event: Event): Boolean = event.content.at("m.relates_to", "rel_type")?.string == "m.replace"

    /** `@arjuna:nyutag.chat` → `arjuna`. */
    fun localpart(userID: String): String {
        val trimmed = userID.removePrefix("@")
        return trimmed.substringBefore(":")
    }
}
