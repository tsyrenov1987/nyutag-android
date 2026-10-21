// Двери: разбор строк каталога, слияние, память последней двери, порядок и гонка проб.

package chat.nyutag.core

import chat.nyutag.core.net.Door
import chat.nyutag.core.net.DoorCatalogue
import chat.nyutag.core.net.DoorMemory
import chat.nyutag.core.net.DoorRace
import chat.nyutag.core.net.MemoryKeyValueStore
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoorCatalogueTest {
    @Test fun parseLine() {
        val a = requireNotNull(Door.parse("https://d4.assetnorth.org:6167#advin-d4"))
        assertEquals("d4.assetnorth.org", a.url.host)
        assertEquals(6167, a.url.port)
        assertEquals("advin-d4", a.name)
        val b = requireNotNull(Door.parse("  https://k4.cdn-tvzvezda-ru.ru  "))
        assertEquals(-1, b.url.port)
        assertEquals("k4.cdn-tvzvezda-ru.ru", b.name)
        assertEquals("", Door.parse("https://k4.cdn-tvzvezda-ru.ru/#k4")?.url?.rawPath)
    }

    @Test fun rejects() {
        assertNull(Door.parse("kilvater://abc@1.2.3.4:443?sni=x#old"))
        assertNull(Door.parse("http://d4.assetnorth.org:6167#x"))
        assertNull(Door.parse("https://d4.assetnorth.org/connect#x"))
        assertNull(Door.parse("https://d4.assetnorth.org?x=1#x"))
        assertNull(Door.parse(""))
        assertNull(Door.of(URI("https:///nohost"), "x"))
    }

    @Test fun parseText() {
        val text = """
            # каталог
            door-key abc123
            https://k4.cdn-tvzvezda-ru.ru#nsk-k4
            kilvater://old@1.2.3.4:443?sni=x#old
            https://d4.assetnorth.org:6167#advin-d4

        """.trimIndent()
        val catalogue = requireNotNull(DoorCatalogue.parse(text))
        assertEquals("abc123", catalogue.key)
        assertEquals(listOf("nsk-k4", "advin-d4"), catalogue.doors.map { it.name })
        assertNull(DoorCatalogue.parse("https://k4.cdn-tvzvezda-ru.ru#x"), "без ключа каталога нет")
        assertNull(DoorCatalogue.parse("door-key abc"), "без дверей каталога нет")
    }

    @Test fun merge() {
        val a = Door.parse("https://a.test#a")!!; val b = Door.parse("https://b.test#b")!!; val c = Door.parse("https://c.test#c")!!
        val bAgain = Door.parse("https://b.test#b2")!!
        assertEquals(listOf(a, b, c), DoorCatalogue.merge(listOf(a, b), listOf(bAgain, c, a)))
        assertEquals(listOf(a), DoorCatalogue.merge(emptyList(), listOf(a, a)))
    }

    @Test fun memory() {
        val store = MemoryKeyValueStore()
        DoorMemory.forget(store)
        assertNull(DoorMemory.recall(store))
        assertTrue(DoorMemory.recallDynamic(store).isEmpty())
        val door = Door.parse("https://d4.assetnorth.org:6167#advin-d4")!!
        DoorMemory.remember(door, "k", store)
        val back = requireNotNull(DoorMemory.recall(store))
        assertEquals(door, back.door)
        assertEquals("k", back.key)
        val fresh = listOf(Door.parse("https://d15.assetnorth.org:6167#new")!!)
        DoorMemory.rememberDynamic(fresh, store)
        assertEquals(fresh, DoorMemory.recallDynamic(store))
        DoorMemory.forget(store)
        assertNull(DoorMemory.recall(store))
        assertTrue(DoorMemory.recallDynamic(store).isEmpty())
    }
}

class DoorRaceTest {
    private val a = Door.parse("https://a.test#a")!!
    private val b = Door.parse("https://b.test#b")!!
    private val c = Door.parse("https://c.test#c")!!

    @Test fun order() {
        assertEquals(listOf(c, a, b), DoorRace.order(listOf(a, b, c, b), c))
        assertEquals(listOf(a, b), DoorRace.order(listOf(a, b), null))
        val stranger = Door.parse("https://z.test#z")!!
        assertEquals(listOf(a, b), DoorRace.order(listOf(a, b), stranger))
    }

    @Test fun firstWins() = runBlocking {
        val probed = Collections.synchronizedList(ArrayList<Door>())
        val winner = DoorRace.run(listOf(a, b, c), null, 0) { door ->
            probed += door
            if (door == b) return@run true
            try { delay(5_000) } catch (_: Exception) {}
            currentCoroutineContext().isActive
        }
        assertEquals(b, winner)
        assertEquals(3, probed.size, "без предпочтения все стартуют сразу")
    }

    @Test fun headStartKeepsPreferred() = runBlocking {
        val probed = Collections.synchronizedList(ArrayList<Door>())
        val winner = DoorRace.run(listOf(a, b, c), c, 500) { door ->
            probed += door
            door == c
        }
        assertEquals(c, winner)
        assertEquals(1, probed.size)
    }

    @Test fun headStartExpires() = runBlocking {
        val started = System.nanoTime()
        val winner = DoorRace.run(listOf(a, b), a, 200) { door ->
            if (door == a) { delay(5_000); return@run false }
            true
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(b, winner)
        assertTrue(elapsedMillis >= 200)
        assertTrue(elapsedMillis < 4_000, "проигравшую пробу сняли, её сна не ждали")
    }

    @Test fun nobody() = runBlocking {
        assertNull(DoorRace.run(listOf(a, b), null, 0) { false })
        assertNull(DoorRace.run(emptyList(), a, 0) { true })
        assertFalse(false)
    }
}
