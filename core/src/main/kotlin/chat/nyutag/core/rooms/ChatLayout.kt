// Нютаг. Закреплённые чаты (до пяти) и папки. Лежат в данных учётки `chat.nyutag.chat_layout`, так же, как
// у iOS-клиента: {"pins":["!room",…], "folders":[{"id","name","rooms":[…]}]}. Пустые поля не пишем,
// повторы и папки без имени при чтении отбрасываем: чужие или битые данные не должны ломать список.

package chat.nyutag.core.rooms

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import java.util.UUID

data class ChatFolder(val id: String, val name: String, val rooms: List<String> = emptyList())

class ChatLayout private constructor(
    /** Сверху вниз: первый — самый верхний. */
    val pins: List<String>,
    val folders: List<ChatFolder>,
    @Suppress("UNUSED_PARAMETER") normalized: Boolean,
) {
    companion object {
        const val MAX_PINS = 5

        operator fun invoke(pins: List<String> = emptyList(), folders: List<ChatFolder> = emptyList()): ChatLayout {
            val cleanPins = pins.distinct().take(MAX_PINS)
            val ids = HashSet<String>()
            val cleanFolders = folders.mapNotNull { folder ->
                val id = folder.id.trim(' ', '\t')
                val name = folder.name.trim()
                if (id.isEmpty() || name.isEmpty() || !ids.add(id)) null else ChatFolder(id, name, folder.rooms.distinct())
            }
            return ChatLayout(cleanPins, cleanFolders, true)
        }

        fun from(json: JsonValue?): ChatLayout {
            val pins = json?.get("pins")?.array?.mapNotNull { it.string } ?: emptyList()
            val folders = (json?.get("folders")?.array ?: emptyList()).map { item ->
                ChatFolder(item["id"]?.string ?: "", item["name"]?.string ?: "", item["rooms"]?.array?.mapNotNull { it.string } ?: emptyList())
            }
            return invoke(pins, folders)
        }

        /** Что лежит в базе после последнего /sync (или нашей последней записи). */
        fun load(store: Store): ChatLayout = from(store.accountData(EventType.CHAT_LAYOUT))
    }

    val json: JsonValue get() {
        val obj = LinkedHashMap<String, JsonValue>()
        if (pins.isNotEmpty()) obj["pins"] = JsonValue.Arr(pins.map { JsonValue.Str(it) })
        if (folders.isNotEmpty()) {
            obj["folders"] = JsonValue.Arr(folders.map { folder ->
                val item = LinkedHashMap<String, JsonValue>()
                item["id"] = JsonValue.Str(folder.id)
                item["name"] = JsonValue.Str(folder.name)
                if (folder.rooms.isNotEmpty()) item["rooms"] = JsonValue.Arr(folder.rooms.map { JsonValue.Str(it) })
                JsonValue.Obj(item)
            })
        }
        return JsonValue.Obj(obj)
    }

    fun isPinned(roomID: String): Boolean = roomID in pins
    val canPinMore: Boolean get() = pins.size < MAX_PINS

    /** Новый закреп встаёт наверх. Сверх пяти ничего не меняется. */
    fun pinning(roomID: String, pinned: Boolean): ChatLayout = when {
        !pinned -> ChatLayout(pins.filter { it != roomID }, folders, true)
        !isPinned(roomID) && canPinMore -> ChatLayout(listOf(roomID) + pins, folders, true)
        else -> this
    }

    fun addingFolder(id: String = UUID.randomUUID().toString().lowercase(), name: String, with: String? = null): ChatLayout {
        val clean = name.trim()
        if (clean.isEmpty() || folders.any { it.id == id }) return this
        return ChatLayout(pins, folders + ChatFolder(id, clean, if (with != null) listOf(with) else emptyList()), true)
    }

    fun renamingFolder(id: String, to: String): ChatLayout {
        val clean = to.trim()
        if (clean.isEmpty()) return this
        return ChatLayout(pins, folders.map { if (it.id == id) it.copy(name = clean) else it }, true)
    }

    fun removingFolder(id: String): ChatLayout = ChatLayout(pins, folders.filter { it.id != id }, true)

    fun placing(roomID: String, folderID: String, inside: Boolean): ChatLayout = ChatLayout(pins, folders.map { folder ->
        when {
            folder.id != folderID -> folder
            inside && roomID !in folder.rooms -> folder.copy(rooms = folder.rooms + roomID)
            !inside -> folder.copy(rooms = folder.rooms.filter { it != roomID })
            else -> folder
        }
    }, true)

    /** Список для показа: в папке — только её чаты; закреплённые сверху в своём порядке, остальные как были. */
    fun arrange(lines: List<ChatLine>, folderID: String? = null): List<ChatLine> {
        var shown = lines
        if (folderID != null) {
            val rooms = folders.firstOrNull { it.id == folderID }?.rooms?.toSet() ?: emptySet()
            shown = shown.filter { it.id in rooms }
        }
        val byID = HashMap<String, ChatLine>()
        for (line in shown) byID.putIfAbsent(line.id, line)
        val top = pins.mapNotNull { byID[it] }
        val topIDs = top.map { it.id }.toSet()
        return top + shown.filter { it.id !in topIDs }
    }

    override fun equals(other: Any?): Boolean = other is ChatLayout && other.pins == pins && other.folders == folders
    override fun hashCode(): Int = 31 * pins.hashCode() + folders.hashCode()
    override fun toString(): String = "ChatLayout(pins=$pins, folders=$folders)"
}

/** Пишем на сервер и сразу в базу: список меняется, не дожидаясь /sync. */
suspend fun MatrixClient.save(layout: ChatLayout, userID: String, store: Store) {
    setAccountData(userID, EventType.CHAT_LAYOUT, content = layout.json)
    store.setAccountData(EventType.CHAT_LAYOUT, content = layout.json)
}
