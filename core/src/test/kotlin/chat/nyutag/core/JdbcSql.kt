// SQLite через JDBC для тестов на Маке: тот же интерфейс, что у платформенного SQLite в приложении.

package chat.nyutag.core

import chat.nyutag.core.store.Sql
import chat.nyutag.core.store.SqlException
import chat.nyutag.core.store.SqlRow
import chat.nyutag.core.store.SqlValue
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

class JdbcSql private constructor(private val connection: Connection) : Sql {
    private val statements = HashMap<String, PreparedStatement>()
    override var changes: Int = 0
        private set

    init {
        connection.autoCommit = true
        exec("PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL; PRAGMA foreign_keys = ON; PRAGMA temp_store = MEMORY; PRAGMA busy_timeout = 5000;")
    }

    override fun exec(sql: String) {
        try {
            connection.createStatement().use { statement ->
                for (piece in sql.split(";")) {
                    val trimmed = piece.trim()
                    if (trimmed.isEmpty()) continue
                    statement.execute(trimmed)
                }
            }
        } catch (e: SQLException) {
            throw SqlException(e.message ?: "exec", sql, e)
        }
    }

    override fun run(sql: String, vararg values: SqlValue) {
        try {
            val statement = prepare(sql, values)
            changes = statement.executeUpdate()
        } catch (e: SQLException) {
            throw SqlException(e.message ?: "run", sql, e)
        }
    }

    override fun <T> query(sql: String, vararg values: SqlValue, row: (SqlRow) -> T): List<T> {
        try {
            val statement = prepare(sql, values)
            statement.executeQuery().use { result ->
                val out = ArrayList<T>()
                val wrapped = Row(result)
                while (result.next()) out += row(wrapped)
                return out
            }
        } catch (e: SQLException) {
            throw SqlException(e.message ?: "query", sql, e)
        }
    }

    override fun <T> transaction(body: () -> T): T {
        connection.autoCommit = false
        try {
            val result = body()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() {
        statements.values.forEach { it.close() }
        statements.clear()
        connection.close()
    }

    private fun prepare(sql: String, values: Array<out SqlValue>): PreparedStatement {
        val statement = statements.getOrPut(sql) { connection.prepareStatement(sql) }
        statement.clearParameters()
        values.forEachIndexed { index, value ->
            val i = index + 1
            when (value) {
                is SqlValue.Null -> statement.setNull(i, java.sql.Types.NULL)
                is SqlValue.Integer -> statement.setLong(i, value.value)
                is SqlValue.Real -> statement.setDouble(i, value.value)
                is SqlValue.Text -> statement.setString(i, value.value)
                is SqlValue.Blob -> statement.setBytes(i, value.value)
            }
        }
        return statement
    }

    private class Row(private val result: ResultSet) : SqlRow {
        override fun value(column: Int): SqlValue = when (val raw = result.getObject(column + 1)) {
            null -> SqlValue.Null
            is Int -> SqlValue.Integer(raw.toLong())
            is Long -> SqlValue.Integer(raw)
            is Short -> SqlValue.Integer(raw.toLong())
            is Float -> SqlValue.Real(raw.toDouble())
            is Double -> SqlValue.Real(raw)
            is String -> SqlValue.Text(raw)
            is ByteArray -> SqlValue.Blob(raw)
            else -> SqlValue.Text(raw.toString())
        }
    }

    companion object {
        fun open(path: String): JdbcSql = JdbcSql(DriverManager.getConnection("jdbc:sqlite:$path"))
        fun inMemory(): JdbcSql = JdbcSql(DriverManager.getConnection("jdbc:sqlite::memory:"))
    }
}
