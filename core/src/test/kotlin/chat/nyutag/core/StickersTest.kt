// Нютаг. Стикеры: наборы MSC2545 читаются и правятся, событие m.sticker ходит туда и обратно,
// лента показывает его как вложение-стикер.

package chat.nyutag.core

import chat.nyutag.core.media.Attachment
import chat.nyutag.core.media.MXC
import chat.nyutag.core.push.PushPreview
import chat.nyutag.core.rooms.ChatFeed
import chat.nyutag.core.rooms.FeedItem
import chat.nyutag.core.rooms.Sticker
import chat.nyutag.core.rooms.StickerPack
import chat.nyutag.core.rooms.Stickers
import chat.nyutag.core.rooms.addSticker
import chat.nyutag.core.rooms.removeSticker
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StickersTest {
    private val me = "@me:nyutag.test"

    @Test fun readPack() {
        val content = JsonValue.parse(
            """{"pack":{"display_name":"Соёмбо","usage":["sticker"]},
                "images":{
                  "hello":{"url":"mxc://nyutag.test/a","body":"Сайн байна","info":{"mimetype":"image/png","w":512,"h":512}},
                  "smile":{"url":"mxc://nyutag.test/b","usage":["emoticon"]},
                  "broken":{"url":"not-an-mxc"}}}""",
        )
        val pack = StickerPack.read(content, "Мои")
        assertEquals("Соёмбо", pack.name)
        assertEquals(listOf("hello"), pack.stickers.map { it.shortcode })
        assertEquals("Сайн байна", pack.stickers.first().body)
        assertEquals(512, pack.stickers.first().width)
        assertEquals(StickerPack("Мои", emptyList()), StickerPack.read(null, "Мои"))
    }

    @Test fun addRemove() {
        val a = Sticker("кот", MXC.parse("mxc://nyutag.test/1")!!, mimetype = "image/png", width = 300, height = 200)
        var content = StickerPack.adding(a, null, "Мои")
        assertEquals("Мои", content.at("pack", "display_name")?.string)
        assertEquals(JsonValue.list("sticker"), content.at("images", "кот", "usage"))
        assertEquals("кот-2", StickerPack.freeShortcode("кот", content))
        val b = Sticker("кот-2", MXC.parse("mxc://nyutag.test/2")!!)
        content = StickerPack.adding(b, content, "Другое")
        assertEquals("Мои", content.at("pack", "display_name")?.string)
        assertEquals(listOf("кот", "кот-2"), StickerPack.read(content, "").stickers.map { it.shortcode })
        content = StickerPack.removing("кот", content)
        assertEquals(listOf("кот-2"), StickerPack.read(content, "").stickers.map { it.shortcode })
    }

    @Test fun stickerEvent() = runBlocking {
        val mxc = MXC.parse("mxc://nyutag.test/s")!!
        val sticker = Sticker("hello", mxc, body = "Сайн байна", mimetype = "image/png", width = 512, height = 512)
        val content = sticker.content
        assertEquals("mxc://nyutag.test/s", content["url"]?.string)
        assertEquals("Сайн байна", content["body"]?.string)
        assertEquals(512, content.at("info", "w")?.int)
        assertEquals(mxc, Sticker.read(content)?.mxc)
        assertNull(Sticker.read(JsonValue.of("body" to "x")))

        val store = Store(JdbcSql.inMemory())
        store.upsert(RoomRecord("!r", RoomRecord.Membership.JOIN))
        store.append(listOf(Event(eventID = "\$s", type = EventType.STICKER, sender = me, originServerTS = 100, content = content, roomID = "!r")))
        val item = ChatFeed.items("!r", store, me).first()
        assertEquals(FeedItem.Body.Attachment(PushPreview.Kind.Sticker, null), item.body)
        val attachment = item.attachment!!
        assertTrue(attachment.isSticker)
        assertEquals(Attachment.Source.Plain(mxc), attachment.source)
        assertEquals("Сайн байна", attachment.name)
        assertEquals(512, attachment.width)
    }

    @Test fun ownPackAndRoomPacks() = runBlocking {
        val server = FakeHomeserver()
        val client = server.client(server.session(me, "D1"))
        val store = Store(JdbcSql.inMemory())
        val mxc = MXC.parse("mxc://nyutag.test/1")!!
        val first = client.addSticker(Sticker("кот", mxc), me, "Мои", store)
        val second = client.addSticker(Sticker("кот", mxc), me, "Мои", store)
        assertEquals("кот", first.shortcode)
        assertEquals("кот-2", second.shortcode)
        val onServer = StickerPack.read(server.accountDataContent(me, StickerPack.USER_TYPE), "")
        assertEquals(listOf("кот", "кот-2"), onServer.stickers.map { it.shortcode })
        assertEquals(2, Stickers.mine(store, "Мои").stickers.size)
        client.removeSticker("кот", me, store)
        assertEquals(listOf("кот-2"), Stickers.mine(store, "Мои").stickers.map { it.shortcode })

        store.upsert(RoomRecord("!r", RoomRecord.Membership.JOIN))
        store.setState(Event(eventID = "\$p", type = StickerPack.ROOM_TYPE, sender = me, stateKey = "buryad",
                             content = JsonValue.parse("""{"pack":{"display_name":"Буряад"},"images":{"a":{"url":"mxc://nyutag.test/a"}}}"""), roomID = "!r"))
        store.setState(Event(eventID = "\$e", type = StickerPack.ROOM_TYPE, sender = me, stateKey = "empty",
                             content = JsonValue.parse("""{"images":{}}"""), roomID = "!r"))
        val packs = Stickers.inRoom("!r", store)
        assertEquals(listOf("Буряад"), packs.map { it.name })
        assertEquals(listOf("a"), packs.first().stickers.map { it.shortcode })
    }
}
