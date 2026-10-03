package org.openmcx.client.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One line of history: a call or a short data message. */
data class HistoryEntry(
    val kind: String, // "call" or "sds"
    val id: String,
    val direction: String, // "in" or "out"
    val peer: String?,
    val group: String?,
    val summary: String,
    val at: Long,
)

/**
 * Call and SDS history on the device (SQLite). It survives process death and reboot;
 * the protocol engine keeps only the recent state in memory.
 */
class HistoryStore(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history (kind TEXT NOT NULL, id TEXT NOT NULL, direction TEXT NOT NULL, peer TEXT, " +
            "grp TEXT, summary TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (kind, id))")
        db.execSQL("CREATE INDEX history_at ON history (at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Insert or update (by kind and id): a message's notifications and a call's outcome change it. */
    fun put(entry: HistoryEntry) {
        writableDatabase.insertWithOnConflict("history", null, ContentValues().apply {
            put("kind", entry.kind); put("id", entry.id); put("direction", entry.direction); put("peer", entry.peer)
            put("grp", entry.group); put("summary", entry.summary); put("at", entry.at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The user cleared the history. */
    fun clear() { writableDatabase.delete("history", null, null) }

    fun recent(limit: Int = 100): List<HistoryEntry> = readableDatabase.query("history", null, null, null, null, null,
        "at DESC", limit.toString()).use { c ->
        List(c.count) {
            c.moveToNext()
            HistoryEntry(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6))
        }
    }
}
