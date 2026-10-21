// Нютаг. «Избранное» — чат с собой для заметок, ссылок и файлов. Одна комната на учётку, её номер лежит в данных
// учётки `chat.nyutag.saved_messages` `{"room_id": "!…"}` (формат общий с iOS), поэтому с любого устройства
// открывается одна и та же. Комната обычная шифрованная, без приглашённых.

package chat.nyutag.core.rooms

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue

object SavedMessages {
    /** Имена, которые ставит сам Нютаг на своих языках: такое имя можно перевести на язык телефона.
     *  Имя, данное человеком, не трогаем. */
    val defaultNames: Set<String> = setOf("Избранное", "Хадагалһан", "Saved messages")

    fun roomID(json: JsonValue?): String? = json?.get("room_id")?.string?.takeIf { it.startsWith("!") }

    fun json(roomID: String): JsonValue = JsonValue.of("room_id" to roomID)

    /** Нужно ли переименовать на язык телефона. */
    fun needsRename(current: String, localized: String): Boolean = current != localized && current in defaultNames

    fun load(store: Store): String? = roomID(store.accountData(EventType.SAVED_MESSAGES))
}

/** Новая комната «Избранного»: создаём и сразу записываем в данные учётки. */
suspend fun MatrixClient.createSavedMessages(name: String, topic: String, userID: String, store: Store): String {
    val roomID = createRoom(CreateRoom(name = name, topic = topic))
    val content = SavedMessages.json(roomID)
    setAccountData(userID, EventType.SAVED_MESSAGES, content = content)
    store.setAccountData(EventType.SAVED_MESSAGES, content = content)
    return roomID
}
