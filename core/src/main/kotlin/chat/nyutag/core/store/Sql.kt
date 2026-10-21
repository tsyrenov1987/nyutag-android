// Нютаг. Тонкий слой над SQLite: выполнить, привязать, читать. В приложении за ним стоит
// android.database.sqlite, в тестах на Маке — JDBC-драйвер. Соединение одно на хранилище;
// потоки защищает само хранилище.

package chat.nyutag.core.store

import chat.nyutag.json.JsonValue

class SqlException(message: String, val sql: String? = null, cause: Throwable? = null) :
    Exception(if (sql != null) "$message [$sql]" else message, cause)

sealed class SqlValue {
    object Null : SqlValue()
    data class Integer(val value: Long) : SqlValue()
    data class Real(val value: Double) : SqlValue()
    data class Text(val value: String) : SqlValue()
    class Blob(val value: ByteArray) : SqlValue() {
        override fun equals(other: Any?): Boolean = other is Blob && other.value.contentEquals(value)
        override fun hashCode(): Int = value.contentHashCode()
    }

    val long: Long? get() = (this as? Integer)?.value
    val int: Int? get() = long?.toInt()
    val string: String? get() = (this as? Text)?.value
    val bytes: ByteArray? get() = (this as? Blob)?.value
    val double: Double? get() = when (this) {
        is Real -> value
        is Integer -> value.toDouble()
        else -> null
    }
    val json: JsonValue? get() = string?.let { try { JsonValue.parse(it) } catch (_: Exception) { null } }

    companion object {
        fun of(string: String?): SqlValue = if (string == null) Null else Text(string)
        fun of(int: Int?): SqlValue = if (int == null) Null else Integer(int.toLong())
        fun of(long: Long?): SqlValue = if (long == null) Null else Integer(long)
        fun of(bool: Boolean): SqlValue = Integer(if (bool) 1 else 0)
        fun of(bytes: ByteArray?): SqlValue = if (bytes == null) Null else Blob(bytes)
        fun json(json: JsonValue?): SqlValue = if (json == null) Null else Text(json.compact())
    }
}

/** Строка результата; живёт только внутри замыкания `row`. */
interface SqlRow {
    fun value(column: Int): SqlValue
    fun string(column: Int): String? = value(column).string
    fun int(column: Int): Int? = value(column).int
    fun long(column: Int): Long? = value(column).long
    fun bytes(column: Int): ByteArray? = value(column).bytes
    fun bool(column: Int): Boolean = (value(column).long ?: 0L) != 0L
}

interface Sql : AutoCloseable {
    /** Выполнить SQL без параметров (DDL, PRAGMA); допускается несколько команд через `;`. */
    fun exec(sql: String)

    /** Выполнить запрос с параметрами, без чтения результата. */
    fun run(sql: String, vararg values: SqlValue)

    /** Выполнить запрос и собрать строки. */
    fun <T> query(sql: String, vararg values: SqlValue, row: (SqlRow) -> T): List<T>

    fun scalar(sql: String, vararg values: SqlValue): SqlValue? = query(sql, *values) { it.value(0) }.firstOrNull()

    fun <T> transaction(body: () -> T): T

    /** Сколько строк изменил последний `run`. */
    val changes: Int
}
