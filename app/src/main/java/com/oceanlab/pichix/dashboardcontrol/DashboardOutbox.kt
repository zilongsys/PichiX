package com.oceanlab.pichix.dashboardcontrol

// ============================================================================
// DashboardOutbox — bandeja de salida en disco (SQLite propio de la app).
//
// Todo log/evento que va a la PC pasa por aquí. Si la PC no está (horas o días),
// se acumula; al reconectar se envía en orden. Solo se borra cuando la PC confirma
// que lo guardó (up_ack), así que un corte a mitad no pierde nada.
//
// - `id` AUTOINCREMENT: nunca se reutiliza, así la PC detecta repetidos por número.
// - `outbox_id`: identifica esta bandeja. Si se borran los datos de la app nace una
//   bandeja nueva y la PC reinicia su contador para este teléfono.
// - Límites: 7 días y MAX_ROWS filas. Si se pasa, se descartan primero los logs más
//   viejos (los eventos se conservan más) y se deja un aviso en la propia bandeja.
//
// Solo lo usa el hilo "dash-writer" (insertar/podar) y el hilo "dash-link"
// (leer/borrar). SQLiteDatabase es seguro entre hilos con una única conexión.
// ============================================================================

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

internal class DashboardOutbox(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "dashboardcontrol_outbox.db"
        private const val DB_VERSION = 1
        const val KIND_LOG = 0
        const val KIND_EVENT = 1
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_ROWS = 250_000L
    }

    class Row(val id: Long, val kind: Int, val body: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE q(id INTEGER PRIMARY KEY AUTOINCREMENT, k INTEGER NOT NULL, ts INTEGER NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE INDEX q_ts ON q(ts)")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
        db.insert("meta", null, ContentValues().apply {
            put("k", "outbox_id")
            put("v", UUID.randomUUID().toString().replace("-", ""))
        })
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    val outboxId: String by lazy {
        DatabaseUtils.stringForQuery(readableDatabase, "SELECT v FROM meta WHERE k='outbox_id'", null)
    }

    /** Inserta un lote en una sola transacción. (kind, ts, json) */
    fun insert(rows: List<Triple<Int, Long, String>>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT INTO q(k, ts, body) VALUES (?,?,?)")
            for ((k, ts, body) in rows) {
                st.bindLong(1, k.toLong())
                st.bindLong(2, ts)
                st.bindString(3, body)
                st.executeInsert()
                st.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Siguiente lote después de [afterId], en orden. Corta también por tamaño para no armar mensajes enormes. */
    fun after(afterId: Long, maxRows: Int, maxChars: Int): List<Row> {
        val out = ArrayList<Row>(minOf(maxRows, 512))
        var chars = 0
        readableDatabase.rawQuery(
            "SELECT id, k, body FROM q WHERE id > ? ORDER BY id LIMIT ?",
            arrayOf(afterId.toString(), maxRows.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val body = c.getString(2)
                if (out.isNotEmpty() && chars + body.length > maxChars) break
                chars += body.length
                out.add(Row(c.getLong(0), c.getInt(1), body))
            }
        }
        return out
    }

    fun countAfter(afterId: Long): Long =
        DatabaseUtils.longForQuery(readableDatabase, "SELECT COUNT(*) FROM q WHERE id > ?", arrayOf(afterId.toString()))

    fun oldestTsAfter(afterId: Long): Long =
        DatabaseUtils.longForQuery(readableDatabase, "SELECT IFNULL(MIN(ts), 0) FROM q WHERE id > ?", arrayOf(afterId.toString()))

    fun deleteUpTo(id: Long) {
        writableDatabase.delete("q", "id <= ?", arrayOf(id.toString()))
    }

    fun clear() {
        writableDatabase.delete("q", null, null)
    }

    /**
     * Aplica los límites. Devuelve cuántas filas se descartaron sin haberse enviado
     * (para dejar constancia en el log de la PC).
     */
    fun prune(now: Long): Int {
        val db = writableDatabase
        var dropped = db.delete("q", "ts < ?", arrayOf((now - MAX_AGE_MS).toString()))
        val total = DatabaseUtils.queryNumEntries(db, "q")
        if (total > MAX_ROWS) {
            val extra: Any = total - MAX_ROWS + MAX_ROWS / 20 // libera un 5 % de margen
            db.execSQL("DELETE FROM q WHERE id IN (SELECT id FROM q WHERE k=$KIND_LOG ORDER BY id LIMIT ?)", arrayOf(extra))
            val after = DatabaseUtils.queryNumEntries(db, "q")
            if (after > MAX_ROWS) {
                db.execSQL("DELETE FROM q WHERE id IN (SELECT id FROM q ORDER BY id LIMIT ?)", arrayOf<Any>(after - MAX_ROWS))
            }
            dropped += (total - DatabaseUtils.queryNumEntries(db, "q")).toInt()
        }
        return dropped
    }
}
