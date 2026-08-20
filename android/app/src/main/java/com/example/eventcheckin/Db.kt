package com.example.eventcheckin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Local SQLite store — the Android twin of the Windows app's Db.cs, same
 * schema and semantics. Privacy contract: the raw badge UID
 * is NEVER stored — only a salted SHA-256 hash, so taps can be matched to
 * enrolled names without the database ever containing a credential number.
 * Attendance rows are append-only from the UI.
 *
 * Same honest limit as the Windows build: the salt lives in the same DB, and
 * short (4-byte) UID spaces are brute-forceable offline by anyone who obtains
 * the file. The hash prevents casual disclosure, not determined recovery.
 * The salt is per-INSTALL: an Android install and a Windows install never
 * share enrollments — deliberate, there is no cross-device linkage.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "checkin.db", null, VERSION) {

    val salt: String by lazy {
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key='salt'", null).use { c ->
            c.moveToFirst(); c.getString(0)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE people (uid_hash TEXT PRIMARY KEY, name TEXT NOT NULL, " +
                "enrolled_at TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, created_at TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE attendance (event_id INTEGER NOT NULL REFERENCES events(id), " +
                "uid_hash TEXT NOT NULL REFERENCES people(uid_hash), " +
                "tapped_at TEXT NOT NULL, UNIQUE(event_id, uid_hash))")
        db.execSQL(CREATE_ROSTER)
        // Per-install random salt, created once. Clearing the app's data re-keys everything.
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        db.execSQL("INSERT INTO meta(key,value) VALUES('salt',?)", arrayOf(salt))
    }

    /** Additive only: an upgrade must never drop a table an event's attendance
     *  or enrollments live in. v1 → v2 adds the imported-roster pool. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(CREATE_ROSTER)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun hashUid(uid: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(salt.toByteArray(Charsets.US_ASCII) + uid).toHex()

    fun lookupName(uidHash: String): String? =
        readableDatabase.rawQuery(
            "SELECT name FROM people WHERE uid_hash=?", arrayOf(uidHash)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Insert-or-update, never REPLACE: with foreign keys enforced, REPLACE
     *  deletes an existing people row that attendance may already reference,
     *  which would throw instead of re-enrolling. */
    fun enroll(uidHash: String, name: String) {
        val inserted = writableDatabase.insertWithOnConflict("people", null, ContentValues().apply {
            put("uid_hash", uidHash)
            put("name", name)
            put("enrolled_at", now())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted == -1L) {
            writableDatabase.update(
                "people",
                ContentValues().apply { put("name", name) },
                "uid_hash=?", arrayOf(uidHash))
        }
    }

    fun createEvent(name: String): Long =
        writableDatabase.insertOrThrow("events", null, ContentValues().apply {
            put("name", name)
            put("created_at", now())
        })

    fun listEvents(): List<Pair<Long, String>> =
        readableDatabase.rawQuery("SELECT id,name FROM events ORDER BY id DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
        }

    /** @return false if this person already checked in to this event. */
    fun recordTap(eventId: Long, uidHash: String): Boolean = try {
        writableDatabase.insertOrThrow("attendance", null, ContentValues().apply {
            put("event_id", eventId)
            put("uid_hash", uidHash)
            put("tapped_at", now())
        })
        true
    } catch (e: SQLiteConstraintException) {
        // Only the UNIQUE(event_id, uid_hash) violation means "already checked
        // in". Android does not surface SQLite extended result codes on this
        // exception (the Windows twin checks code 2067), so match the message;
        // an FK or NOT NULL failure must surface, not be mislabeled.
        if (e.message?.contains("UNIQUE constraint failed") == true) false else throw e
    }

    /** Adds an imported name to the unmatched pool. Name only — the credential
     *  number that came with it is deliberately not stored. */
    fun addRosterName(name: String): Boolean =
        writableDatabase.insertWithOnConflict("roster", null, ContentValues().apply {
            put("name", name)
            put("added_at", now())
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    /** Imported names nobody has tapped a badge against yet. Anyone already
     *  enrolled is excluded even if a later import put their name back. */
    fun rosterNames(): List<String> =
        readableDatabase.rawQuery(
            "SELECT name FROM roster WHERE name NOT IN (SELECT name FROM people) " +
                "ORDER BY name COLLATE NOCASE", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Leaves the pool once bound to a badge, however the name was chosen. */
    fun removeRosterName(name: String) {
        writableDatabase.delete("roster", "name=? COLLATE NOCASE", arrayOf(name))
    }

    fun attendance(eventId: Long): List<Pair<String, String>> =
        readableDatabase.rawQuery(
            "SELECT p.name, a.tapped_at FROM attendance a " +
                "JOIN people p ON p.uid_hash = a.uid_hash " +
                "WHERE a.event_id=? ORDER BY a.tapped_at",
            arrayOf(eventId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }

    /** Audit export: name, event, timestamp — nothing else leaves the device.
     *  Same layout as the Windows CSV (CRLF rows, quoted cells, TOTAL row);
     *  timestamps differ from .NET's in fractional-second precision. */
    fun buildCsv(eventId: Long, eventName: String): String {
        val rows = attendance(eventId)
        val sb = StringBuilder("Name,Event,CheckedInAt\r\n")
        for ((name, at) in rows) sb.append("${cell(name)},${cell(eventName)},$at\r\n")
        sb.append("${cell("TOTAL HEADCOUNT: ${rows.size}")},,\r\n")
        return sb.toString()
    }

    companion object {
        /** 2 added the roster pool; see onUpgrade before bumping again. */
        const val VERSION = 2

        private const val CREATE_ROSTER =
            "CREATE TABLE IF NOT EXISTS roster (name TEXT PRIMARY KEY COLLATE NOCASE, " +
                "added_at TEXT NOT NULL)"

        fun now(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        /** CSV-quotes AND neutralizes spreadsheet formulas: a value starting
         *  with = + - @ gets a leading apostrophe so Excel shows text, not a
         *  formula. */
        private fun cell(v: String): String {
            val s = if (v.isNotEmpty() && v[0] in "=+-@") "'$v" else v
            return "\"${s.replace("\"", "\"\"")}\""
        }

        private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
    }
}
