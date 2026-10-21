// Вход, хранение сессии, подъём при запуске и выход; ник при регистрации.

package chat.nyutag.core

import chat.nyutag.core.auth.AccountKeeper
import chat.nyutag.core.auth.MemorySessionVault
import chat.nyutag.core.auth.Nickname
import chat.nyutag.core.auth.Session
import chat.nyutag.core.auth.Shelf
import chat.nyutag.core.auth.isUsernameAvailable
import chat.nyutag.core.auth.login
import chat.nyutag.core.auth.logout
import chat.nyutag.core.auth.whoAmI
import chat.nyutag.core.net.MatrixError
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthTest {
    private val user = "@dorzho:nyutag.chat"

    @Test fun sessionFields() {
        val session = Session(user, "D1", "t1", URI("https://core.nyutag.chat"))
        assertEquals("dorzho", session.localpart)
        assertEquals("nyutag.chat", session.serverName)
        assertEquals(session, Session.from(session.toJson()))
    }

    @Test fun loginWhoAmILogout() = runBlocking {
        val server = FakeHomeserver()
        server.setPassword("секрет", user)
        val client = server.client(null)
        val wrong = assertThrows(MatrixError::class.java) { runBlocking { client.login("dorzho", "не тот", deviceDisplayName = "Xiaomi") } }
        assertEquals("M_FORBIDDEN", wrong.errcode)
        val noToken = assertThrows(MatrixError::class.java) { runBlocking { client.whoAmI() } }
        assertEquals(MatrixError.Kind.NoAccessToken, noToken.kind)

        val session = client.login("dorzho", "секрет", deviceDisplayName = "Xiaomi")
        assertEquals(user, session.userID)
        assertEquals(session.accessToken, client.accessToken)
        assertEquals(user, client.whoAmI().userID)
        client.logout()
        assertNull(client.accessToken)
        assertFalse(server.isTokenValid(session.accessToken))
    }

    @Test fun shelfFolderHidesUserID() {
        val shelf = Shelf(Files.createTempDirectory("nyutag-shelf").toFile()) { JdbcSql.open(it) }
        val folder = shelf.folder(user)
        assertFalse(folder.path.contains("dorzho"))
        assertTrue(folder != shelf.folder("@other:nyutag.chat"))
        assertEquals(folder, shelf.folder(user))
    }

    @Test fun signInRestoreSignOut() = runBlocking {
        val server = FakeHomeserver()
        server.setPassword("секрет", user)
        val vault = MemorySessionVault()
        val root: File = Files.createTempDirectory("nyutag-shelf").toFile()
        val shelf = Shelf(root) { JdbcSql.open(it) }
        try {
            val keeper = AccountKeeper(vault, shelf) { _, token -> server.client(token) }
            assertNull(keeper.restore())
            assertThrows(MatrixError::class.java) {
                runBlocking { keeper.signIn(server.homeserver, "dorzho", "не тот", "Xiaomi") }
            }
            assertNull(vault.load())

            val account = keeper.signIn(server.homeserver, "dorzho", "секрет", "Xiaomi")
            assertEquals(user, account.session.userID)
            account.store.setMeta("probe", "1")
            account.store.close()
            assertTrue(shelf.storeFile(user).exists())

            // Перезапуск: та же сессия и та же база, без похода в сеть.
            val restored = requireNotNull(keeper.restore())
            assertEquals(account.session, restored.session)
            assertEquals("1", restored.store.meta("probe"))
            assertEquals(user, restored.client.whoAmI().userID)

            keeper.signOut(restored)
            assertFalse(server.isTokenValid(account.session.accessToken))
            assertNull(vault.load())
            assertFalse(shelf.folder(user).exists())
            assertNull(keeper.restore())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun usernameAvailability() = runBlocking {
        val server = FakeHomeserver()
        server.setPassword("секрет", user)
        val client = server.client(null)
        assertFalse(client.isUsernameAvailable("dorzho"), "занятый ник")
        assertTrue(client.isUsernameAvailable("aryuna"), "свободный ник")
    }
}

class SignUpTest {
    @Test fun transliteration() {
        assertEquals("aryuna.dorzhieva", Nickname.from("Арюна Доржиева"))
        assertEquals("bayarma.heren-dulma", Nickname.from("Баярма Һэрэн-Дулма"))
        assertEquals("tumen.olzy", Nickname.from("Түмэн Өлзы"))
        assertEquals("chingis", Nickname.from("Чингис"))
        assertEquals("ivan.petrov", Nickname.from("Ivan Petrov"))
    }

    @Test fun tidy() {
        assertEquals("anya", Nickname.from("  Аня  ✨  "))
        assertEquals("", Nickname.from("Ъ"))
        assertEquals("mergen.b", Nickname.from("Мэргэн..__Б"))
        assertEquals("saryuna_99", Nickname.clean("@Saryuna_99!"))
        assertTrue(Nickname.from("Сэсэг ".repeat(10)).length <= Nickname.LONGEST)
    }

    @Test fun validity() {
        assertTrue(Nickname.isValid("ab"))
        assertFalse(Nickname.isValid("a"))
        assertFalse(Nickname.isValid("Ab"))
        assertFalse(Nickname.isValid("арюна"))
        val more = Nickname.alternatives("aryuna")
        assertEquals(4, more.size)
        assertEquals(4, more.toSet().size)
        assertTrue(more.all { it.startsWith("aryuna") && Nickname.isValid(it) && it != "aryuna" })
    }
}
