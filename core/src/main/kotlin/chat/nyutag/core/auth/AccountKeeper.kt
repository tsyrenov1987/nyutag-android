// Нютаг. Жизненный цикл входа: войти и запомнить, поднять сохранённое при запуске, выйти и стереть следы.
// Где лежит сессия (Keystore-шифрование в приложении, память в тестах) и как открывается SQLite (платформенный
// или JDBC) решает вызывающий через [SessionVault] и `openSql`.

package chat.nyutag.core.auth

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.store.Sql
import chat.nyutag.core.store.Store
import chat.nyutag.crypto.Hash
import java.io.File
import java.net.URI

/** Вошедший аккаунт: всё, что нужно синку, шифрованию и пушам. */
class SignedInAccount(val session: Session, val client: MatrixClient, val store: Store)

/** Хранилище сессии (токен, устройство). */
interface SessionVault {
    fun save(session: Session)
    /** Сохранённая сессия или `null`, если входа не было. Испорченная запись — ошибка, а не тихий выход. */
    fun load(): Session?
    fun erase()
}

class MemorySessionVault : SessionVault {
    @Volatile private var session: Session? = null
    override fun save(session: Session) { this.session = session }
    override fun load(): Session? = session
    override fun erase() { session = null }
}

/** Раскладка файлов аккаунтов: своя папка на каждый Matrix ID. */
class Shelf(val root: File, private val openSql: (path: String) -> Sql) {
    /** Папка аккаунта: имя — хеш Matrix ID, чтобы в путях не светился логин. */
    fun folder(userID: String): File {
        val digest = Hash.sha256(userID.toByteArray(Charsets.UTF_8)).take(10)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return File(File(root, "accounts"), digest)
    }

    fun storeFile(userID: String): File = File(folder(userID), "nyutag.sqlite")

    /** База аккаунта; папка создаётся. */
    fun openStore(userID: String): Store {
        folder(userID).mkdirs()
        return Store(openSql(storeFile(userID).path))
    }

    /** Выход: стираем базу аккаунта вместе с журналами WAL. */
    fun wipe(userID: String) {
        val folder = folder(userID)
        if (folder.exists()) folder.deleteRecursively()
    }
}

class AccountKeeper(
    val vault: SessionVault,
    val shelf: Shelf,
    /** Клиент под адрес сервера и токен: дверь к ядру (или прямой путь) задаёт вызывающий. */
    private val makeClient: (homeserver: URI, accessToken: String?) -> MatrixClient,
) {
    /** Вход по паролю. Сессия сохраняется до открытия базы: если база не откроется, повторный запуск поднимет
     *  уже выданное устройство, а не заведёт новое. Вход — всегда новое устройство: база, оставшаяся от прежнего
     *  (сессию стёрли или Keystore потерял ключ), стирается, иначе старые ключи Olm встали бы под новым ID. */
    suspend fun signIn(homeserver: URI, user: String, password: String, deviceName: String): SignedInAccount {
        val client = makeClient(homeserver, null)
        val session = client.login(user, password, deviceDisplayName = deviceName)
        shelf.wipe(session.userID)
        vault.save(session)
        return SignedInAccount(session, client, shelf.openStore(session.userID))
    }

    /** Сохранённый вход или `null`. Токен не проверяем по сети: запуск без связи должен показать переписку из базы. */
    fun restore(): SignedInAccount? {
        val session = vault.load() ?: return null
        return SignedInAccount(session, makeClient(session.homeserver, session.accessToken), shelf.openStore(session.userID))
    }

    /** Выход. Токен отзываем на сервере по возможности; локально стираем всегда, даже без связи. */
    suspend fun signOut(account: SignedInAccount) {
        try { account.client.logout() } catch (_: Exception) {}
        account.store.close()
        vault.erase()
        shelf.wipe(account.session.userID)
    }

    /** Сервер ответил, что токен больше не действует (вышли с другого устройства): стираем без запроса. */
    fun forget(userID: String) {
        vault.erase()
        shelf.wipe(userID)
    }
}
