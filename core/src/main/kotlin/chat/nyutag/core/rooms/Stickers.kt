// Нютаг. Стикеры: наборы картинок по MSC2545 (как в Element и iOS-клиенте). Свой набор лежит в данных учётки
// `im.ponies.user_emotes`, наборы комнаты — в её состоянии `im.ponies.room_emotes` (ключ состояния — имя набора).
// Стикер в ленте — событие `m.sticker` с `body`, `url` и `info`: открытая картинка, ключа у неё нет, и в
// зашифрованной комнате тоже (так во всех клиентах: стикер — не секрет, а картинка из общего набора).

package chat.nyutag.core.rooms

import chat.nyutag.core.media.MXC
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.store.Store
import chat.nyutag.json.JsonValue

data class Sticker(
    /** Короткое имя в наборе: по нему же стикер ищут и удаляют. */
    val shortcode: String,
    val mxc: MXC,
    /** Подпись: `body` в наборе, иначе короткое имя. */
    val body: String = shortcode,
    val mimetype: String? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    /** Содержимое события `m.sticker`. */
    val content: JsonValue get() = JsonValue.of("body" to body, "url" to mxc.toString(), "info" to JsonValue.Obj(info()))

    internal fun info(): LinkedHashMap<String, JsonValue> {
        val info = LinkedHashMap<String, JsonValue>()
        if (mimetype != null) info["mimetype"] = JsonValue.Str(mimetype)
        if (width != null && height != null) {
            info["w"] = JsonValue.Int(width.toLong())
            info["h"] = JsonValue.Int(height.toLong())
        }
        return info
    }

    companion object {
        /** Стикер из события `m.sticker`; у битого содержимого — `null`. */
        fun read(content: JsonValue): Sticker? {
            val mxc = content["url"]?.string?.let(MXC::parse) ?: return null
            val info = content["info"]
            val body = content["body"]?.string ?: ""
            return Sticker(body, mxc, body, info?.get("mimetype")?.string, info?.get("w")?.int, info?.get("h")?.int)
        }
    }
}

/** Набор стикеров: свой или комнаты. */
data class StickerPack(val name: String, val stickers: List<Sticker>) {
    companion object {
        const val USER_TYPE = "im.ponies.user_emotes"
        const val ROOM_TYPE = "im.ponies.room_emotes"

        /** Набор из содержимого `im.ponies.*_emotes`. Картинки только для эмодзи (`usage: ["emoticon"]`) не берём:
         *  это не стикеры, а замена смайлам в тексте. Порядок — по короткому имени. */
        fun read(content: JsonValue?, fallbackName: String): StickerPack {
            val packUsage = usage(content?.get("pack"))
            val stickers = ArrayList<Sticker>()
            for ((shortcode, image) in content?.get("images")?.obj ?: emptyMap()) {
                val mxc = image["url"]?.string?.let(MXC::parse) ?: continue
                val own = usage(image) ?: packUsage
                if (own != null && "sticker" !in own) continue
                val info = image["info"]
                stickers.add(Sticker(shortcode, mxc, image["body"]?.string ?: shortcode, info?.get("mimetype")?.string,
                                     info?.get("w")?.int, info?.get("h")?.int))
            }
            val name = content?.at("pack", "display_name")?.string?.takeIf { it.isNotEmpty() } ?: fallbackName
            return StickerPack(name, stickers.sortedBy { it.shortcode })
        }

        private fun usage(json: JsonValue?): Set<String>? = json?.get("usage")?.array?.mapNotNull { it.string }?.toSet()

        /** То же содержимое с добавленным стикером (замена по короткому имени). Набор помечаем как стикерный. */
        fun adding(sticker: Sticker, to: JsonValue?, packName: String): JsonValue {
            val root = LinkedHashMap(to?.obj ?: emptyMap())
            val images = LinkedHashMap(root["images"]?.obj ?: emptyMap())
            images[sticker.shortcode] = JsonValue.of(
                "url" to sticker.mxc.toString(), "body" to sticker.body, "info" to JsonValue.Obj(sticker.info()), "usage" to listOf("sticker"),
            )
            root["images"] = JsonValue.Obj(images)
            val pack = LinkedHashMap(root["pack"]?.obj ?: emptyMap())
            if (pack["display_name"] == null) pack["display_name"] = JsonValue.Str(packName)
            root["pack"] = JsonValue.Obj(pack)
            return JsonValue.Obj(root)
        }

        /** То же содержимое без стикера. */
        fun removing(shortcode: String, from: JsonValue?): JsonValue {
            val root = LinkedHashMap(from?.obj ?: emptyMap())
            val images = LinkedHashMap(root["images"]?.obj ?: emptyMap())
            images.remove(shortcode)
            root["images"] = JsonValue.Obj(images)
            return JsonValue.Obj(root)
        }

        /** Свободное короткое имя: `name`, иначе `name-2`, `name-3`… */
        fun freeShortcode(wanted: String, content: JsonValue?): String {
            val taken = content?.get("images")?.obj?.keys ?: emptySet()
            if (wanted !in taken) return wanted
            var n = 2
            while ("$wanted-$n" in taken) n += 1
            return "$wanted-$n"
        }
    }
}

object Stickers {
    /** Свой набор из базы. */
    fun mine(store: Store, packName: String): StickerPack = StickerPack.read(store.accountData(StickerPack.USER_TYPE), packName)

    /** Наборы комнаты (из её состояния), без пустых. */
    fun inRoom(roomID: String, store: Store): List<StickerPack> = store.stateAll(roomID, StickerPack.ROOM_TYPE)
        .map { StickerPack.read(it.content, it.stateKey) }
        .filter { it.stickers.isNotEmpty() }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

/** Добавить стикер в свой набор: на сервер и сразу в базу. Возвращает стикер с занятым именем. */
suspend fun MatrixClient.addSticker(sticker: Sticker, userID: String, packName: String, store: Store): Sticker {
    val current = store.accountData(StickerPack.USER_TYPE)
    val named = sticker.copy(shortcode = StickerPack.freeShortcode(sticker.shortcode, current))
    val content = StickerPack.adding(named, current, packName)
    setAccountData(userID, StickerPack.USER_TYPE, content = content)
    store.setAccountData(StickerPack.USER_TYPE, content = content)
    return named
}

suspend fun MatrixClient.removeSticker(shortcode: String, userID: String, store: Store) {
    val content = StickerPack.removing(shortcode, store.accountData(StickerPack.USER_TYPE))
    setAccountData(userID, StickerPack.USER_TYPE, content = content)
    store.setAccountData(StickerPack.USER_TYPE, content = content)
}
