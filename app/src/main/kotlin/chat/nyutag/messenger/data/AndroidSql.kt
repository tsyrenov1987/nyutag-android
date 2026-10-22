// Нютаг. SQLite платформы под интерфейс движка `Sql`. Параметры привязываются к запросу через фабрику курсора:
// у `rawQuery` они только строками, а в базе есть BLOB-ключи и числа.

package chat.nyutag.messenger.data

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteProgram
import chat.nyutag.core.store.Sql
import chat.nyutag.core.store.SqlException
import chat.nyutag.core.store.SqlRow
import chat.nyutag.core.store.SqlValue

class AndroidSql private constructor(private val db: SQLiteDatabase) : Sql {
    override var changes: Int = 0
        private set

    override fun exec(sql: String) {
        for (piece in sql.split(";")) {
            val statement = piece.trim()
            if (statement.isEmpty()) continue
            try {
                // PRAGMA с ответом (`journal_mode`, `user_version` без «=») execSQL не принимает.
                if (statement.startsWith("PRAGMA", ignoreCase = true)) {
                    db.rawQuery(statement, null).use { it.moveToFirst() }
                } else {
                    db.execSQL(statement)
                }
            } catch (e: SQLiteException) {
                throw SqlException(e.message ?: "exec", statement, e)
            }
        }
    }

    override fun run(sql: String, vararg values: SqlValue) {
        try {
            db.compileStatement(sql).use { statement ->
                bind(statement, values)
                changes = statement.executeUpdateDelete()
            }
        } catch (e: SQLiteException) {
            throw SqlException(e.message ?: "run", sql, e)
        }
    }

    override fun <T> query(sql: String, vararg values: SqlValue, row: (SqlRow) -> T): List<T> {
        try {
            val cursor = db.rawQueryWithFactory({ _, driver, table, query ->
                bind(query, values)
                SQLiteCursor(driver, table, query)
            }, sql, null, "")
            return cursor.use {
                val out = ArrayList<T>()
                val wrapped = Row(it)
                while (it.moveToNext()) out += row(wrapped)
                out
            }
        } catch (e: SQLiteException) {
            throw SqlException(e.message ?: "query", sql, e)
        }
    }

    override fun <T> transaction(body: () -> T): T {
        db.beginTransaction()
        try {
            val result = body()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = db.close()

    private fun bind(program: SQLiteProgram, values: Array<out SqlValue>) {
        values.forEachIndexed { index, value ->
            val i = index + 1
            when (value) {
                is SqlValue.Null -> program.bindNull(i)
                is SqlValue.Integer -> program.bindLong(i, value.value)
                is SqlValue.Real -> program.bindDouble(i, value.value)
                is SqlValue.Text -> program.bindString(i, value.value)
                is SqlValue.Blob -> program.bindBlob(i, value.value)
            }
        }
    }

    private class Row(private val cursor: Cursor) : SqlRow {
        override fun value(column: Int): SqlValue = when (cursor.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> SqlValue.Null
            Cursor.FIELD_TYPE_INTEGER -> SqlValue.Integer(cursor.getLong(column))
            Cursor.FIELD_TYPE_FLOAT -> SqlValue.Real(cursor.getDouble(column))
            Cursor.FIELD_TYPE_BLOB -> SqlValue.Blob(cursor.getBlob(column))
            else -> SqlValue.Text(cursor.getString(column))
        }
    }

    companion object {
        fun open(path: String): AndroidSql {
            val db = SQLiteDatabase.openDatabase(path, null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
            db.setForeignKeyConstraintsEnabled(true)
            return AndroidSql(db).also { it.exec("PRAGMA synchronous = NORMAL; PRAGMA temp_store = MEMORY") }
        }
    }
}
