// Нютаг. Общие помощники тестов E2EE: устройство с машиной на поддельном сервере, чтение комнаты, входящие to-device.

package chat.nyutag.core

import chat.nyutag.core.auth.Session
import chat.nyutag.core.crypto.CryptoMachine
import chat.nyutag.core.store.Store
import chat.nyutag.json.JsonValue

const val TEST_ROOM = "!room:nyutag.chat"
const val ALICE = "@alice:nyutag.chat"
const val BOB = "@bob:nyutag.chat"

class CryptoDevice(val machine: CryptoMachine, val session: Session)

suspend fun FakeHomeserver.cryptoDevice(user: String, id: String): CryptoDevice {
    val session = session(user, id)
    val machine = CryptoMachine.create(client(session), Store(JdbcSql.inMemory()), session)
    machine.publishKeys(null)
    return CryptoDevice(machine, session)
}

/** Принять to-device устройства. */
suspend fun FakeHomeserver.inbox(device: CryptoDevice) {
    device.machine.receiveToDevice(takeToDevice(device.session.userID, device.session.deviceID))
}

/** Принять to-device и расшифровать все события комнаты; null там, где ключа нет. */
suspend fun FakeHomeserver.read(device: CryptoDevice, room: String = TEST_ROOM): List<String?> {
    inbox(device)
    return events(room).map { event ->
        runCatching { device.machine.decrypt(event, room) }.getOrNull()?.event?.content?.get("body")?.string
    }
}

fun text(body: String) = JsonValue.of("msgtype" to "m.text", "body" to body)
