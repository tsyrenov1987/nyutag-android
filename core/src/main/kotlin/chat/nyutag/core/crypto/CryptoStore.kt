// Нютаг. Хранение ключей шифрования в базе аккаунта: ключи устройства, сессии Olm, входящие сессии Megolm,
// использованные индексы сообщений (защита от повтора).

package chat.nyutag.core.crypto

import chat.nyutag.core.store.SqlValue
import chat.nyutag.core.store.Store
import chat.nyutag.crypto.Base64
import chat.nyutag.crypto.Ed25519KeyPair
import chat.nyutag.crypto.MegolmInboundSession
import chat.nyutag.crypto.MegolmOutboundSession
import chat.nyutag.crypto.MegolmRatchet
import chat.nyutag.crypto.OlmSession
import chat.nyutag.json.JsonValue

/** Входящая сессия Megolm вместе с тем, откуда она и к какой комнате относится. */
class StoredMegolmSession(
    val roomID: String,
    val senderKey: String,
    val session: MegolmInboundSession,
    val backedUp: Boolean,
    /** Ключ подписи устройства отправителя, как он его заявил (`keys.ed25519` в `m.room_key`). */
    val claimedEd25519: String?,
)

/** Устройство пользователя из `/keys/query` с проверенной подписью. */
data class DeviceInfo(
    val userID: String,
    val deviceID: String,
    val curve25519Key: String,
    val ed25519Key: String,
    val displayName: String?,
    /** Подписано ключом самоподписи владельца, который подписан его мастер-ключом. */
    val crossSigned: Boolean = false,
)

/** Ключи кросс-подписи пользователя из `/keys/query`. */
data class CrossSigningIdentity(
    val userID: String,
    val masterKey: String,
    val selfSigningKey: String?,
    /** Мастер-ключ подтверждён: свой совпал с ключом из SSSS, чужой подписан нашим ключом подписи пользователей. */
    val verified: Boolean,
)

/** Исходящая сессия Megolm комнаты и кому её ключ уже отдан. */
internal data class OutboundRecord(
    val ratchet: ByteArray,
    val counter: UInt,
    val signingKey: ByteArray,
    val createdAtEpochMillis: Long,
    val messageCount: Int = 0,
    /** "пользователь|устройство" → ключ Curve25519 устройства на момент отправки. */
    val sharedWith: Map<String, String> = emptyMap(),
    /** Кому уже сообщили, что ключа не будет (нет канала Olm): повторно не шлём. */
    val withheldFrom: List<String>? = null,
) {
    constructor(session: MegolmOutboundSession, messageCount: Int = 0, sharedWith: Map<String, String> = emptyMap(),
                withheldFrom: List<String>? = null) : this(
        session.ratchet.data.copyOf(), session.ratchet.counter, session.signingKey.rawRepresentation,
        session.createdAtEpochMillis, messageCount, sharedWith, withheldFrom,
    )

    fun session(): MegolmOutboundSession =
        MegolmOutboundSession(MegolmRatchet(ratchet.copyOf(), counter), Ed25519KeyPair(signingKey), createdAtEpochMillis)

    fun toJson(): JsonValue = JsonValue.of(
        "ratchet" to Base64.encodePadded(ratchet), "counter" to counter.toLong(),
        "signingKey" to Base64.encodePadded(signingKey), "createdAt" to createdAtEpochMillis,
        "messageCount" to messageCount, "sharedWith" to sharedWith, "withheldFrom" to withheldFrom,
    )

    companion object {
        fun fromJson(json: JsonValue): OutboundRecord = OutboundRecord(
            ratchet = Base64.decode(json["ratchet"]?.string ?: ""),
            counter = (json["counter"]?.long ?: 0L).toUInt(),
            signingKey = Base64.decode(json["signingKey"]?.string ?: ""),
            createdAtEpochMillis = json["createdAt"]?.long ?: 0L,
            messageCount = json["messageCount"]?.int ?: 0,
            sharedWith = (json["sharedWith"]?.obj ?: emptyMap()).mapNotNull { (k, v) -> v.string?.let { k to it } }.toMap(),
            withheldFrom = json["withheldFrom"]?.array?.mapNotNull { it.string },
        )
    }
}

/** Отказ в ключе сессии. */
data class WithheldRecord(val code: String, val reason: String?)

// MARK: Ключи устройства

internal fun Store.cryptoBlob(key: String): ByteArray? = synchronized(lock) {
    db.scalar("SELECT value FROM crypto_meta WHERE key = ?", SqlValue.Text(key))?.bytes
}

internal fun Store.setCryptoBlob(key: String, value: ByteArray?) = synchronized(lock) {
    if (value != null) {
        db.run("INSERT INTO crypto_meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
               SqlValue.Text(key), SqlValue.Blob(value))
    } else {
        db.run("DELETE FROM crypto_meta WHERE key = ?", SqlValue.Text(key))
    }
}

// MARK: Olm

internal fun Store.olmSessions(senderKey: String): List<OlmSession> = synchronized(lock) {
    db.query("SELECT data FROM olm_sessions WHERE sender_key = ? ORDER BY last_used DESC", SqlValue.Text(senderKey)) { row ->
        OlmSession.fromJson(String(row.bytes(0) ?: ByteArray(0), Charsets.UTF_8))
    }
}

internal fun Store.saveOlmSession(session: OlmSession) = synchronized(lock) {
    db.run("""
        INSERT INTO olm_sessions(session_id, sender_key, data, last_used) VALUES(?, ?, ?, ?)
        ON CONFLICT(session_id) DO UPDATE SET data = excluded.data, last_used = excluded.last_used
        """.trimIndent(), SqlValue.Text(session.sessionID), SqlValue.Text(session.theirIdentityKey),
           SqlValue.Blob(session.toJsonString().toByteArray()), SqlValue.Real(session.lastUsedEpochMillis / 1000.0))
}

// MARK: Megolm

/** Сохранить сессию; если такая уже есть, оставить ту, что начинается с меньшего индекса
 *  (при равенстве — проверенную подписью отправителя). */
internal fun Store.saveMegolmSession(
    session: MegolmInboundSession, roomID: String, senderKey: String,
    backedUp: Boolean = false, claimedEd25519: String? = null,
): Boolean = synchronized(lock) {
    val existing = megolmSession(senderKey, session.sessionID)
    if (existing != null) {
        val better = session.firstKnownIndex < existing.session.firstKnownIndex ||
            (session.firstKnownIndex == existing.session.firstKnownIndex && session.isVerified && !existing.session.isVerified)
        if (!better) return@synchronized false
    }
    db.run("""
        INSERT INTO megolm_inbound(sender_key, session_id, room_id, exported, first_index, verified, backed_up,
            claimed_ed25519) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(sender_key, session_id) DO UPDATE SET room_id = excluded.room_id, exported = excluded.exported,
            first_index = excluded.first_index, verified = excluded.verified, backed_up = excluded.backed_up,
            claimed_ed25519 = COALESCE(excluded.claimed_ed25519, claimed_ed25519)
        """.trimIndent(), SqlValue.Text(senderKey), SqlValue.Text(session.sessionID), SqlValue.Text(roomID),
           SqlValue.Text(session.export()), SqlValue.Integer(session.firstKnownIndex.toLong()), SqlValue.of(session.isVerified),
           SqlValue.of(backedUp), SqlValue.of(claimedEd25519))
    true
}

internal fun Store.megolmSession(senderKey: String, sessionID: String): StoredMegolmSession? = synchronized(lock) {
    db.query("""
        SELECT room_id, exported, verified, backed_up, claimed_ed25519 FROM megolm_inbound
        WHERE sender_key = ? AND session_id = ?
        """.trimIndent(), SqlValue.Text(senderKey), SqlValue.Text(sessionID)) { row -> megolm(row, senderKey) }.firstOrNull()
}

/** Сессия по одному session_id: в новых событиях `sender_key` может отсутствовать. */
internal fun Store.megolmSession(sessionID: String): StoredMegolmSession? = synchronized(lock) {
    db.query("""
        SELECT room_id, exported, verified, backed_up, claimed_ed25519, sender_key FROM megolm_inbound WHERE session_id = ?
        """.trimIndent(), SqlValue.Text(sessionID)) { row -> megolm(row, row.string(5) ?: "") }.firstOrNull()
}

internal fun Store.megolmSessionCount(): Int = synchronized(lock) {
    db.scalar("SELECT COUNT(*) FROM megolm_inbound")?.int ?: 0
}

private fun megolm(row: chat.nyutag.core.store.SqlRow, senderKey: String): StoredMegolmSession {
    val restored = MegolmInboundSession.fromExportedKey(row.string(1) ?: "")
    val session = MegolmInboundSession(restored.initialRatchet, restored.signingKey, row.int(2) == 1)
    return StoredMegolmSession(row.string(0) ?: "", senderKey, session, row.int(3) == 1, row.string(4))
}

/** Сессии, которых ещё нет в бэкапе на сервере. */
internal fun Store.megolmSessionsNotBackedUp(limit: Int): List<StoredMegolmSession> = synchronized(lock) {
    db.query("""
        SELECT room_id, exported, verified, backed_up, claimed_ed25519, sender_key FROM megolm_inbound
        WHERE backed_up = 0 LIMIT ?
        """.trimIndent(), SqlValue.Integer(limit.toLong())) { row -> megolm(row, row.string(5) ?: "") }
}

internal fun Store.markMegolmBackedUp(sessions: List<StoredMegolmSession>) = synchronized(lock) {
    for (stored in sessions) {
        db.run("UPDATE megolm_inbound SET backed_up = 1 WHERE sender_key = ? AND session_id = ?",
               SqlValue.Text(stored.senderKey), SqlValue.Text(stored.session.sessionID))
    }
}

/** Новая версия бэкапа на сервере: всё нужно выгрузить заново. */
internal fun Store.resetMegolmBackedUp() = synchronized(lock) {
    db.run("UPDATE megolm_inbound SET backed_up = 0")
}

/** Запомнить индекс; `false`, если этот индекс сессии уже был у другого события (повтор). */
internal fun Store.markMegolmIndex(sessionID: String, index: UInt, eventID: String): Boolean = synchronized(lock) {
    val seen = db.scalar("SELECT event_id FROM megolm_seen WHERE session_id = ? AND message_index = ?",
                         SqlValue.Text(sessionID), SqlValue.Integer(index.toLong()))?.string
    if (seen != null) return@synchronized seen == eventID
    db.run("INSERT INTO megolm_seen(session_id, message_index, event_id) VALUES(?, ?, ?)",
           SqlValue.Text(sessionID), SqlValue.Integer(index.toLong()), SqlValue.Text(eventID))
    true
}

// MARK: Устройства

/** Пользователи, чьи списки устройств надо (пере)запросить: новые и отмеченные в `device_lists.changed`. */
internal fun Store.usersNeedingDeviceQuery(users: List<String>): List<String> = synchronized(lock) {
    users.filter { user ->
        (db.scalar("SELECT dirty FROM tracked_users WHERE user_id = ?", SqlValue.Text(user))?.int ?: 1) == 1
    }
}

internal fun Store.markDeviceListsChanged(users: List<String>) = synchronized(lock) {
    for (user in users) db.run("UPDATE tracked_users SET dirty = 1 WHERE user_id = ?", SqlValue.Text(user))
}

/** Свежий список устройств пользователя: заменяет прежний целиком. */
internal fun Store.replaceDevices(devices: List<DeviceInfo>, userID: String) = synchronized(lock) {
    db.run("DELETE FROM devices WHERE user_id = ?", SqlValue.Text(userID))
    for (device in devices) {
        val extra = JsonValue.of("display_name" to device.displayName)
        db.run("INSERT INTO devices(user_id, device_id, curve_key, ed_key, keys, cross_signed) VALUES(?, ?, ?, ?, ?, ?)",
               SqlValue.Text(userID), SqlValue.Text(device.deviceID), SqlValue.Text(device.curve25519Key),
               SqlValue.Text(device.ed25519Key), SqlValue.json(extra), SqlValue.of(device.crossSigned))
    }
    db.run("INSERT INTO tracked_users(user_id, dirty) VALUES(?, 0) ON CONFLICT(user_id) DO UPDATE SET dirty = 0",
           SqlValue.Text(userID))
}

internal fun Store.devices(userID: String): List<DeviceInfo> = synchronized(lock) {
    db.query("SELECT device_id, curve_key, ed_key, keys, cross_signed FROM devices WHERE user_id = ? ORDER BY device_id",
             SqlValue.Text(userID)) { row ->
        DeviceInfo(userID, row.string(0) ?: "", row.string(1) ?: "", row.string(2) ?: "",
                   row.value(3).json?.get("display_name")?.string, row.int(4) == 1)
    }
}

/** Пересчитать доверие ко всем: после смены своих ключей кросс-подписи. */
internal fun Store.markAllDeviceListsChanged() = synchronized(lock) {
    db.run("UPDATE tracked_users SET dirty = 1")
}

// MARK: Кросс-подпись

internal fun Store.saveCrossSigning(identity: CrossSigningIdentity?, userID: String) = synchronized(lock) {
    if (identity == null) {
        db.run("DELETE FROM cross_signing WHERE user_id = ?", SqlValue.Text(userID))
        return@synchronized
    }
    db.run("""
        INSERT INTO cross_signing(user_id, master, self_signing, verified) VALUES(?, ?, ?, ?)
        ON CONFLICT(user_id) DO UPDATE SET master = excluded.master, self_signing = excluded.self_signing,
            verified = excluded.verified
        """.trimIndent(), SqlValue.Text(userID), SqlValue.Text(identity.masterKey), SqlValue.of(identity.selfSigningKey),
           SqlValue.of(identity.verified))
}

internal fun Store.crossSigning(userID: String): CrossSigningIdentity? = synchronized(lock) {
    db.query("SELECT master, self_signing, verified FROM cross_signing WHERE user_id = ?", SqlValue.Text(userID)) { row ->
        CrossSigningIdentity(userID, row.string(0) ?: "", row.string(1), row.int(2) == 1)
    }.firstOrNull()
}

// MARK: Запросы ключей и отказы

/** Запомнить свой запрос ключа; `false`, если такой уже отправлен. */
internal fun Store.addKeyRequest(sessionID: String, senderKey: String, roomID: String, requestID: String): Boolean = synchronized(lock) {
    if (db.scalar("SELECT 1 FROM key_requests WHERE session_id = ? AND sender_key = ?",
                  SqlValue.Text(sessionID), SqlValue.Text(senderKey)) != null) return@synchronized false
    db.run("INSERT INTO key_requests(session_id, sender_key, room_id, request_id) VALUES(?, ?, ?, ?)",
           SqlValue.Text(sessionID), SqlValue.Text(senderKey), SqlValue.Text(roomID), SqlValue.Text(requestID))
    true
}

internal fun Store.keyRequestID(sessionID: String, senderKey: String): String? = synchronized(lock) {
    db.scalar("SELECT request_id FROM key_requests WHERE session_id = ? AND sender_key = ?",
              SqlValue.Text(sessionID), SqlValue.Text(senderKey))?.string
}

internal fun Store.removeKeyRequest(sessionID: String, senderKey: String) = synchronized(lock) {
    db.run("DELETE FROM key_requests WHERE session_id = ? AND sender_key = ?", SqlValue.Text(sessionID), SqlValue.Text(senderKey))
}

internal fun Store.saveWithheld(sessionID: String, senderKey: String, code: String, reason: String?) = synchronized(lock) {
    db.run("""
        INSERT INTO withheld(session_id, sender_key, code, reason) VALUES(?, ?, ?, ?)
        ON CONFLICT(session_id, sender_key) DO UPDATE SET code = excluded.code, reason = excluded.reason
        """.trimIndent(), SqlValue.Text(sessionID), SqlValue.Text(senderKey), SqlValue.Text(code), SqlValue.of(reason))
}

/** Отказ в ключе сессии; `senderKey` null — искать по одному session_id. */
internal fun Store.withheld(sessionID: String, senderKey: String?): WithheldRecord? = synchronized(lock) {
    val rows = db.query("SELECT code, reason, sender_key FROM withheld WHERE session_id = ?", SqlValue.Text(sessionID)) { row ->
        Triple(row.string(0) ?: "", row.string(1), row.string(2) ?: "")
    }
    rows.firstOrNull { senderKey == null || it.third == senderKey }?.let { WithheldRecord(it.first, it.second) }
}

// MARK: Исходящие сессии

internal fun Store.outbound(roomID: String): OutboundRecord? = synchronized(lock) {
    val data = db.scalar("SELECT data FROM megolm_outbound WHERE room_id = ?", SqlValue.Text(roomID))?.bytes
        ?: return@synchronized null
    OutboundRecord.fromJson(JsonValue.parse(String(data, Charsets.UTF_8)))
}

internal fun Store.saveOutbound(record: OutboundRecord?, roomID: String) = synchronized(lock) {
    if (record != null) {
        db.run("INSERT INTO megolm_outbound(room_id, data) VALUES(?, ?) ON CONFLICT(room_id) DO UPDATE SET data = excluded.data",
               SqlValue.Text(roomID), SqlValue.Blob(record.toJson().compact().toByteArray()))
    } else {
        db.run("DELETE FROM megolm_outbound WHERE room_id = ?", SqlValue.Text(roomID))
    }
}
