// Нютаг. Что показать в уведомлении и чем является реплика для списка чатов и ленты. Подписи («Фото», «Звонок»)
// выбирает приложение на языке пользователя. Пуш несёт только room_id и event_id: событие берём из хранилища или
// с сервера и расшифровываем ключами из того же хранилища. Читатель пуша только читает: не синкается, не заводит
// Olm-аккаунт и не рассылает ключи — это дело основного движка.

package chat.nyutag.core.push

import chat.nyutag.core.auth.Session
import chat.nyutag.core.calls.CallEventType
import chat.nyutag.core.crypto.CryptoMachine
import chat.nyutag.core.crypto.cryptoBlob
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.rooms.event
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PushPreview(
    val roomID: String,
    val eventID: String,
    val sender: String?,
    val senderName: String?,
    val roomName: String?,
    val isDirect: Boolean,
    val kind: Kind,
) {
    sealed class Kind {
        data class Text(val text: String) : Kind()
        object Image : Kind()
        object Video : Kind()
        object Audio : Kind()
        object File : Kind()
        object Location : Kind()
        object Sticker : Kind()
        object Poll : Kind()
        object Call : Kind()
        /** Служебный сигнал звонка (ring/offer/answer/end): уведомление не показывать. */
        object Signal : Kind()
        /** Нас пригласили в комнату. */
        object Invite : Kind()
        /** Зашифровано, а ключа сессии на устройстве ещё нет: показать нейтральное «Новое сообщение». */
        object Locked : Kind()
        object Other : Kind()
    }
}

class PushReader(private val client: MatrixClient, private val store: Store, private val session: Session) {
    private val lock = Mutex()
    private var machine: CryptoMachine? = null

    /** Текст уведомления; ошибка сети — только если события нет ни в хранилище, ни на сервере. */
    suspend fun preview(roomID: String, eventID: String): PushPreview {
        var event = store.event(eventID) ?: client.event(roomID, eventID)
        var locked = false
        if (event.type == EventType.ENCRYPTED) {
            val opened = decrypt(event, roomID)
            if (opened != null) event = opened else locked = true
        }
        val record = store.room(roomID)
        val senderName = event.sender?.let { store.state(roomID, EventType.MEMBER, it)?.get("displayname")?.string }
        return PushPreview(
            roomID = roomID, eventID = eventID, sender = event.sender, senderName = senderName,
            roomName = record?.name, isDirect = record?.isDirect ?: false,
            kind = if (locked) PushPreview.Kind.Locked else kind(event, session.userID),
        )
    }

    /** Расшифровка без побочных эффектов на аккаунт: машину открываем, только если движок уже завёл аккаунт. */
    private suspend fun decrypt(event: Event, roomID: String): Event? = lock.withLock {
        if (machine == null) {
            if (store.cryptoBlob("account") == null) return@withLock null
            machine = runCatching { CryptoMachine.create(client, store, session) }.getOrNull()
        }
        runCatching { machine?.decrypt(event, roomID)?.event }.getOrNull()
    }

    companion object {
        fun kind(event: Event, me: String): PushPreview.Kind {
            when (event.type) {
                EventType.MEMBER -> return if (event.stateKey == me && event.content["membership"]?.string == "invite") PushPreview.Kind.Invite else PushPreview.Kind.Other
                EventType.STICKER -> return PushPreview.Kind.Sticker
                "m.poll.start", "org.matrix.msc3381.poll.start" -> return PushPreview.Kind.Poll
                "m.call.invite", EventType.CALL -> return PushPreview.Kind.Call
                CallEventType.RING, CallEventType.OFFER, CallEventType.ANSWER, CallEventType.END -> return PushPreview.Kind.Signal
                EventType.MESSAGE -> {}
                else -> return PushPreview.Kind.Other
            }
            // Правка: показываем новый текст, а не «* старый».
            val content = event.content["m.new_content"] ?: event.content
            return when (content["msgtype"]?.string) {
                "m.image" -> PushPreview.Kind.Image
                "m.video" -> PushPreview.Kind.Video
                "m.audio" -> PushPreview.Kind.Audio
                "m.file" -> PushPreview.Kind.File
                "m.location" -> PushPreview.Kind.Location
                else -> {
                    val body = content["body"]?.string ?: return PushPreview.Kind.Other
                    PushPreview.Kind.Text(withoutReplyQuote(body))
                }
            }
        }

        /** Старые клиенты кладут в ответ цитату `> <@кто> текст` и пустую строку перед самим ответом. */
        fun withoutReplyQuote(body: String): String {
            val lines = body.split("\n")
            if (lines.firstOrNull()?.startsWith("> ") != true) return body
            val rest = lines.dropWhile { it.startsWith(">") }.dropWhile { it.isEmpty() }
            return if (rest.isEmpty()) body else rest.joinToString("\n")
        }
    }
}
