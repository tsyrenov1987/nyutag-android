// Нютаг. Хранилище сеанса: комнаты, события, состояние, account data, маркер /sync. Одна база SQLite на аккаунт.
// Схема и запросы общие с iOS-клиентом, чтобы ядро на обеих платформах вело себя одинаково.

package chat.nyutag.core.store

import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue

data class RoomRecord(
    val id: String,
    var membership: Membership,
    var name: String? = null,
    var topic: String? = null,
    var avatarURL: String? = null,
    var canonicalAlias: String? = null,
    var isEncrypted: Boolean = false,
    var isDirect: Boolean = false,
    var isSpace: Boolean = false,
    var joinedCount: Int = 0,
    var invitedCount: Int = 0,
    var heroes: List<String> = emptyList(),
    var highlightCount: Int = 0,
    var notificationCount: Int = 0,
    var latestTS: Long = 0,
    var prevBatch: String? = null,
) {
    enum class Membership(val rawValue: String) {
        JOIN("join"), INVITE("invite"), LEAVE("leave"), KNOCK("knock"), BAN("ban");

        companion object {
            fun from(raw: String?): Membership = entries.firstOrNull { it.rawValue == raw } ?: LEAVE
        }
    }
}

data class SearchHit(val eventID: String, val roomID: String, val sender: String, val ts: Long, val text: String)

data class StateEntry(val stateKey: String, val sender: String?, val content: JsonValue)

/** Хранилище на SQLite. Один писатель: все вызовы под общим замком. */
class Store(val db: Sql) : AutoCloseable {
    internal val lock = Any()

    /** Сквозной порядок событий таймлайна: события из /sync идут в хронологии получения, события из `/messages` в прошлое. */
    private var nextOrdering: Long = 0

    init {
        migrate(db)
        nextOrdering = (db.scalar("SELECT COALESCE(MAX(ordering), 0) FROM events")?.long ?: 0L) + 1
    }

    override fun close() = db.close()

    // MARK: Поиск

    /** Запись индекса; `replace` — заменить текст (правка), иначе первую запись не трогаем. */
    fun indexSearch(eventID: String, roomID: String, sender: String, ts: Long, text: String, folded: String,
                    replace: Boolean = false) = synchronized(lock) {
        db.run(
            """
            INSERT INTO search_text(event_id, room_id, sender, ts, text, folded) VALUES(?, ?, ?, ?, ?, ?)
            ON CONFLICT(event_id) DO ${if (replace) "UPDATE SET text = excluded.text, folded = excluded.folded" else "NOTHING"}
            """.trimIndent(),
            SqlValue.of(eventID), SqlValue.of(roomID), SqlValue.of(sender), SqlValue.of(ts), SqlValue.of(text), SqlValue.of(folded),
        )
    }

    /** Реплики и шифровки, которых ещё нет в индексе: новые первыми, от отметки `before` (по времени) вниз. */
    fun unindexedEvents(before: Long?, limit: Int): List<Event> = synchronized(lock) {
        db.query(
            """
            SELECT * FROM events WHERE type IN (?, ?) AND ts < ? AND event_id NOT IN (SELECT event_id FROM search_text)
            ORDER BY ts DESC LIMIT ?
            """.trimIndent(),
            SqlValue.of(EventType.MESSAGE), SqlValue.of(EventType.ENCRYPTED), SqlValue.of(before ?: Long.MAX_VALUE), SqlValue.of(limit),
            row = ::event,
        )
    }

    /** Совпадения по свёрнутому тексту, новые первыми; удалённые реплики не показываем. */
    fun searchText(folded: String, roomID: String?, limit: Int): List<SearchHit> = synchronized(lock) {
        db.query(
            """
            SELECT event_id, room_id, sender, ts, text FROM search_text
            WHERE instr(folded, ?) > 0 AND (? IS NULL OR room_id = ?)
              AND event_id NOT IN (SELECT json_extract(content, '$.redacts') FROM events
                                   WHERE type = ? AND json_extract(content, '$.redacts') IS NOT NULL)
            ORDER BY ts DESC LIMIT ?
            """.trimIndent(),
            SqlValue.of(folded), SqlValue.of(roomID), SqlValue.of(roomID), SqlValue.of(EventType.REDACTION), SqlValue.of(limit),
        ) { row ->
            SearchHit(row.string(0) ?: "", row.string(1) ?: "", row.string(2) ?: "", row.long(3) ?: 0, row.string(4) ?: "")
        }
    }

    fun eventCount(): Int = synchronized(lock) { db.scalar("SELECT COUNT(*) FROM events")?.int ?: 0 }

    // MARK: Meta

    fun meta(key: String): String? = synchronized(lock) {
        db.scalar("SELECT value FROM meta WHERE key = ?", SqlValue.of(key))?.string
    }

    fun setMeta(key: String, value: String?) = synchronized(lock) {
        if (value != null) {
            db.run("INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                   SqlValue.of(key), SqlValue.of(value))
        } else {
            db.run("DELETE FROM meta WHERE key = ?", SqlValue.of(key))
        }
    }

    val syncToken: String? get() = meta("sync_token")

    // MARK: Комнаты

    fun room(roomID: String): RoomRecord? = synchronized(lock) {
        db.query("SELECT * FROM rooms WHERE room_id = ?", SqlValue.of(roomID), row = ::roomRecord).firstOrNull()
    }

    fun rooms(membership: RoomRecord.Membership? = null): List<RoomRecord> = synchronized(lock) {
        if (membership != null) {
            db.query("SELECT * FROM rooms WHERE membership = ? ORDER BY latest_ts DESC", SqlValue.of(membership.rawValue), row = ::roomRecord)
        } else {
            db.query("SELECT * FROM rooms ORDER BY latest_ts DESC", row = ::roomRecord)
        }
    }

    fun upsert(room: RoomRecord) = synchronized(lock) {
        db.run(
            """
            INSERT INTO rooms(room_id, membership, name, topic, avatar_url, canonical_alias, is_encrypted, is_direct, is_space,
                              joined_count, invited_count, heroes, highlight_count, notification_count, latest_ts, prev_batch)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(room_id) DO UPDATE SET
                membership = excluded.membership, name = excluded.name, topic = excluded.topic, avatar_url = excluded.avatar_url,
                canonical_alias = excluded.canonical_alias, is_encrypted = excluded.is_encrypted, is_direct = excluded.is_direct,
                is_space = excluded.is_space, joined_count = excluded.joined_count, invited_count = excluded.invited_count,
                heroes = excluded.heroes, highlight_count = excluded.highlight_count, notification_count = excluded.notification_count,
                latest_ts = excluded.latest_ts, prev_batch = excluded.prev_batch
            """.trimIndent(),
            SqlValue.of(room.id), SqlValue.of(room.membership.rawValue), SqlValue.of(room.name), SqlValue.of(room.topic),
            SqlValue.of(room.avatarURL), SqlValue.of(room.canonicalAlias), SqlValue.of(room.isEncrypted), SqlValue.of(room.isDirect),
            SqlValue.of(room.isSpace), SqlValue.of(room.joinedCount), SqlValue.of(room.invitedCount),
            SqlValue.json(JsonValue.Arr(room.heroes.map { JsonValue.Str(it) })),
            SqlValue.of(room.highlightCount), SqlValue.of(room.notificationCount), SqlValue.of(room.latestTS), SqlValue.of(room.prevBatch),
        )
    }

    private fun roomRecord(row: SqlRow): RoomRecord = RoomRecord(
        id = row.string(0) ?: "",
        membership = RoomRecord.Membership.from(row.string(1)),
        name = row.string(2),
        topic = row.string(3),
        avatarURL = row.string(4),
        canonicalAlias = row.string(5),
        isEncrypted = row.bool(6),
        isDirect = row.bool(7),
        isSpace = row.bool(8),
        joinedCount = row.int(9) ?: 0,
        invitedCount = row.int(10) ?: 0,
        heroes = row.value(11).json?.array?.mapNotNull { it.string } ?: emptyList(),
        highlightCount = row.int(12) ?: 0,
        notificationCount = row.int(13) ?: 0,
        latestTS = row.long(14) ?: 0,
        prevBatch = row.string(15),
    )

    // MARK: События

    /** Добавляет события таймлайна в порядке получения. Дубликаты (тот же event_id) пропускаются. */
    fun append(events: List<Event>) = synchronized(lock) {
        for (event in events) {
            val eventID = event.eventID ?: continue
            val roomID = event.roomID ?: continue
            insertEvent(eventID, roomID, event, nextOrdering)
            if (db.changes > 0) nextOrdering += 1
        }
    }

    /** Добавляет историю из `/messages` (dir=b): события приходят от новых к старым и встают перед самым ранним
     *  сохранённым событием комнаты. Дубликаты пропускаются. Возвращает число добавленных. */
    fun prepend(events: List<Event>, roomID: String): Int = synchronized(lock) {
        var ordering = (db.scalar("SELECT MIN(ordering) FROM events WHERE room_id = ?", SqlValue.of(roomID))?.long ?: nextOrdering) - 1
        var added = 0
        for (event in events) {
            val eventID = event.eventID ?: continue
            insertEvent(eventID, event.roomID ?: roomID, event, ordering)
            if (db.changes > 0) {
                ordering -= 1
                added += 1
            }
        }
        added
    }

    private fun insertEvent(eventID: String, roomID: String, event: Event, ordering: Long) {
        db.run(
            """
            INSERT OR IGNORE INTO events(event_id, room_id, type, sender, ts, state_key, content, unsigned, ordering)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            SqlValue.of(eventID), SqlValue.of(roomID), SqlValue.of(event.type), SqlValue.of(event.sender),
            SqlValue.of(event.originServerTS ?: 0L), SqlValue.of(event.stateKey), SqlValue.json(event.content),
            SqlValue.json(event.unsigned), SqlValue.of(ordering),
        )
    }

    /** Последние события комнаты, старые первыми. */
    fun events(roomID: String, limit: Int = 50, before: Long? = null): List<Event> = synchronized(lock) {
        val rows = if (before != null) {
            db.query("SELECT * FROM events WHERE room_id = ? AND ordering < ? ORDER BY ordering DESC LIMIT ?",
                     SqlValue.of(roomID), SqlValue.of(before), SqlValue.of(limit), row = ::event)
        } else {
            db.query("SELECT * FROM events WHERE room_id = ? ORDER BY ordering DESC LIMIT ?",
                     SqlValue.of(roomID), SqlValue.of(limit), row = ::event)
        }
        rows.reversed()
    }

    fun event(eventID: String): Event? = synchronized(lock) {
        db.query("SELECT * FROM events WHERE event_id = ?", SqlValue.of(eventID), row = ::event).firstOrNull()
    }

    fun replace(event: Event) = synchronized(lock) {
        val eventID = event.eventID ?: return
        db.run("UPDATE events SET type = ?, content = ?, unsigned = ? WHERE event_id = ?",
               SqlValue.of(event.type), SqlValue.json(event.content), SqlValue.json(event.unsigned), SqlValue.of(eventID))
    }

    fun eventCount(roomID: String): Int = synchronized(lock) {
        db.scalar("SELECT COUNT(*) FROM events WHERE room_id = ?", SqlValue.of(roomID))?.int ?: 0
    }

    private fun event(row: SqlRow): Event = Event(
        eventID = row.string(0), type = row.string(2) ?: "", sender = row.string(3), originServerTS = row.long(4),
        stateKey = row.string(5), content = row.value(6).json ?: JsonValue.Obj(emptyMap()), unsigned = row.value(7).json,
        roomID = row.string(1),
    )

    // MARK: Состояние

    fun setState(event: Event) = synchronized(lock) {
        val roomID = event.roomID ?: return
        val stateKey = event.stateKey ?: return
        db.run(
            """
            INSERT INTO state(room_id, type, state_key, event_id, sender, content) VALUES(?, ?, ?, ?, ?, ?)
            ON CONFLICT(room_id, type, state_key) DO UPDATE SET event_id = excluded.event_id, sender = excluded.sender, content = excluded.content
            """.trimIndent(),
            SqlValue.of(roomID), SqlValue.of(event.type), SqlValue.of(stateKey), SqlValue.of(event.eventID), SqlValue.of(event.sender),
            SqlValue.json(event.content),
        )
    }

    fun state(roomID: String, type: String, stateKey: String = ""): JsonValue? = synchronized(lock) {
        db.scalar("SELECT content FROM state WHERE room_id = ? AND type = ? AND state_key = ?",
                  SqlValue.of(roomID), SqlValue.of(type), SqlValue.of(stateKey))?.json
    }

    /** Все события состояния одного типа (например, участники). */
    fun stateAll(roomID: String, type: String): List<StateEntry> = synchronized(lock) {
        db.query("SELECT state_key, sender, content FROM state WHERE room_id = ? AND type = ?", SqlValue.of(roomID), SqlValue.of(type)) { row ->
            StateEntry(row.string(0) ?: "", row.string(1), row.value(2).json ?: JsonValue.Obj(emptyMap()))
        }
    }

    /** Забытая комната: сервер больше не пришлёт о ней ничего, поэтому стираем всё локально. */
    fun deleteRoom(roomID: String) = synchronized(lock) {
        for (table in listOf("rooms", "events", "state", "receipts")) {
            db.run("DELETE FROM $table WHERE room_id = ?", SqlValue.of(roomID))
        }
        db.run("DELETE FROM account_data WHERE room_id = ?", SqlValue.of(roomID))
    }

    // MARK: Account data

    fun setAccountData(type: String, roomID: String = "", content: JsonValue) = synchronized(lock) {
        db.run("INSERT INTO account_data(room_id, type, content) VALUES(?, ?, ?) ON CONFLICT(room_id, type) DO UPDATE SET content = excluded.content",
               SqlValue.of(roomID), SqlValue.of(type), SqlValue.json(content))
    }

    fun accountData(type: String, roomID: String = ""): JsonValue? = synchronized(lock) {
        db.scalar("SELECT content FROM account_data WHERE room_id = ? AND type = ?", SqlValue.of(roomID), SqlValue.of(type))?.json
    }

    // MARK: Квитанции прочтения

    fun setReceipt(roomID: String, userID: String, eventID: String, ts: Long) = synchronized(lock) {
        db.run("INSERT INTO receipts(room_id, user_id, event_id, ts) VALUES(?, ?, ?, ?) ON CONFLICT(room_id, user_id) DO UPDATE SET event_id = excluded.event_id, ts = excluded.ts",
               SqlValue.of(roomID), SqlValue.of(userID), SqlValue.of(eventID), SqlValue.of(ts))
    }

    fun receipt(roomID: String, userID: String): String? = synchronized(lock) {
        db.scalar("SELECT event_id FROM receipts WHERE room_id = ? AND user_id = ?", SqlValue.of(roomID), SqlValue.of(userID))?.string
    }

    // MARK: Транзакции

    fun <T> transaction(body: () -> T): T = synchronized(lock) { db.transaction(body) }

    /** Стереть всё (выход из аккаунта). */
    fun wipe() = synchronized(lock) {
        db.exec("DELETE FROM meta; DELETE FROM rooms; DELETE FROM events; DELETE FROM state; DELETE FROM account_data; DELETE FROM receipts; DELETE FROM crypto_meta; DELETE FROM olm_sessions; DELETE FROM megolm_inbound; DELETE FROM megolm_seen;")
        nextOrdering = 1
    }

    companion object {
        const val SCHEMA_VERSION = 5

        private fun migrate(db: Sql) {
            val version = db.scalar("PRAGMA user_version")?.int ?: 0
            if (version < 1) {
                db.exec(
                    """
                    CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
                    CREATE TABLE IF NOT EXISTS rooms (
                        room_id TEXT PRIMARY KEY,
                        membership TEXT NOT NULL,
                        name TEXT, topic TEXT, avatar_url TEXT, canonical_alias TEXT,
                        is_encrypted INTEGER NOT NULL DEFAULT 0,
                        is_direct INTEGER NOT NULL DEFAULT 0,
                        is_space INTEGER NOT NULL DEFAULT 0,
                        joined_count INTEGER NOT NULL DEFAULT 0,
                        invited_count INTEGER NOT NULL DEFAULT 0,
                        heroes TEXT NOT NULL DEFAULT '[]',
                        highlight_count INTEGER NOT NULL DEFAULT 0,
                        notification_count INTEGER NOT NULL DEFAULT 0,
                        latest_ts INTEGER NOT NULL DEFAULT 0,
                        prev_batch TEXT
                    );
                    CREATE TABLE IF NOT EXISTS events (
                        event_id TEXT PRIMARY KEY,
                        room_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        sender TEXT,
                        ts INTEGER NOT NULL DEFAULT 0,
                        state_key TEXT,
                        content TEXT NOT NULL,
                        unsigned TEXT,
                        ordering INTEGER NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS events_room_ordering ON events(room_id, ordering);
                    CREATE TABLE IF NOT EXISTS state (
                        room_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        state_key TEXT NOT NULL,
                        event_id TEXT,
                        sender TEXT,
                        content TEXT NOT NULL,
                        PRIMARY KEY (room_id, type, state_key)
                    );
                    CREATE TABLE IF NOT EXISTS account_data (
                        room_id TEXT NOT NULL DEFAULT '',
                        type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        PRIMARY KEY (room_id, type)
                    );
                    CREATE TABLE IF NOT EXISTS receipts (
                        room_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        ts INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (room_id, user_id)
                    );
                    PRAGMA user_version = 1;
                    """.trimIndent(),
                )
            }
            if (version < 2) {
                // Ключи шифрования. Лежат в той же базе; на телефоне база в приватном каталоге приложения.
                db.exec(
                    """
                    CREATE TABLE IF NOT EXISTS crypto_meta (key TEXT PRIMARY KEY, value BLOB NOT NULL);
                    CREATE TABLE IF NOT EXISTS olm_sessions (
                        session_id TEXT PRIMARY KEY,
                        sender_key TEXT NOT NULL,
                        data BLOB NOT NULL,
                        last_used REAL NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS olm_sessions_sender ON olm_sessions(sender_key);
                    CREATE TABLE IF NOT EXISTS megolm_inbound (
                        sender_key TEXT NOT NULL,
                        session_id TEXT NOT NULL,
                        room_id TEXT NOT NULL,
                        exported TEXT NOT NULL,
                        first_index INTEGER NOT NULL,
                        verified INTEGER NOT NULL DEFAULT 0,
                        backed_up INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (sender_key, session_id)
                    );
                    CREATE TABLE IF NOT EXISTS megolm_seen (
                        session_id TEXT NOT NULL,
                        message_index INTEGER NOT NULL,
                        event_id TEXT NOT NULL,
                        PRIMARY KEY (session_id, message_index)
                    );
                    PRAGMA user_version = 2;
                    """.trimIndent(),
                )
            }
            if (version < 3) {
                // Отправка: устройства собеседников, исходящие сессии комнат, заявленный ключ отправителя для бэкапа.
                db.exec(
                    """
                    CREATE TABLE IF NOT EXISTS devices (
                        user_id TEXT NOT NULL,
                        device_id TEXT NOT NULL,
                        curve_key TEXT NOT NULL,
                        ed_key TEXT NOT NULL,
                        keys TEXT NOT NULL,
                        PRIMARY KEY (user_id, device_id)
                    );
                    CREATE TABLE IF NOT EXISTS tracked_users (user_id TEXT PRIMARY KEY, dirty INTEGER NOT NULL DEFAULT 1);
                    CREATE TABLE IF NOT EXISTS megolm_outbound (room_id TEXT PRIMARY KEY, data BLOB NOT NULL);
                    ALTER TABLE megolm_inbound ADD COLUMN claimed_ed25519 TEXT;
                    PRAGMA user_version = 3;
                    """.trimIndent(),
                )
            }
            if (version < 4) {
                // Кросс-подпись: ключи пользователей и подпись устройств; запросы ключей; отказы в ключах.
                db.exec(
                    """
                    CREATE TABLE IF NOT EXISTS cross_signing (
                        user_id TEXT PRIMARY KEY,
                        master TEXT NOT NULL,
                        self_signing TEXT,
                        verified INTEGER NOT NULL DEFAULT 0
                    );
                    ALTER TABLE devices ADD COLUMN cross_signed INTEGER NOT NULL DEFAULT 0;
                    CREATE TABLE IF NOT EXISTS key_requests (
                        session_id TEXT NOT NULL,
                        sender_key TEXT NOT NULL,
                        room_id TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        PRIMARY KEY (session_id, sender_key)
                    );
                    CREATE TABLE IF NOT EXISTS withheld (
                        session_id TEXT NOT NULL,
                        sender_key TEXT NOT NULL,
                        code TEXT NOT NULL,
                        reason TEXT,
                        PRIMARY KEY (session_id, sender_key)
                    );
                    PRAGMA user_version = 4;
                    """.trimIndent(),
                )
            }
            if (version < 5) {
                // Поиск по сообщениям: открытый (расшифрованный) текст реплик. Пустой текст — событие разобрано,
                // искать в нём нечего. Правка пишется под событием исходной реплики.
                db.exec(
                    """
                    CREATE TABLE IF NOT EXISTS search_text (
                        event_id TEXT PRIMARY KEY,
                        room_id TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        ts INTEGER NOT NULL DEFAULT 0,
                        text TEXT NOT NULL,
                        folded TEXT NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS search_text_room_ts ON search_text(room_id, ts);
                    PRAGMA user_version = 5;
                    """.trimIndent(),
                )
            }
        }
    }
}
