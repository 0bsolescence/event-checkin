package com.example.eventcheckin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Deletion semantics, executed rather than reviewed. The schema and the DELETE
 * statements come from [Db] itself (`Db.CREATE_*`, `Db.DELETE_*`), so a test
 * here cannot pass against tables or SQL the app does not actually use — they
 * are the same constant strings [Db.deleteEvent] runs on the device.
 *
 * What this deliberately does NOT cover: Android's `SQLiteDatabase` wrapper —
 * `beginTransaction`/`endTransaction` and the foreign-key pragma set in
 * `onConfigure` are device behavior and stay on the manual checklist. This
 * connection enables foreign keys explicitly for the same reason the app does:
 * without them the ordering rule below is unenforced and meaningless.
 */
class DbDeleteEventTest {

    private lateinit var con: Connection

    @Before
    fun open() {
        con = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys=ON")
        for (sql in listOf(
            Db.CREATE_META, Db.CREATE_PEOPLE, Db.CREATE_EVENTS,
            Db.CREATE_ATTENDANCE, Db.CREATE_ROSTER)) exec(sql)

        // Two events, two people, one of whom attended both.
        exec("INSERT INTO events(id,name,created_at) VALUES(1,'Kept','t')")
        exec("INSERT INTO events(id,name,created_at) VALUES(2,'Doomed','t')")
        exec("INSERT INTO people(uid_hash,name,enrolled_at) VALUES('AA','Jane Doe','t')")
        exec("INSERT INTO people(uid_hash,name,enrolled_at) VALUES('BB','Bob Roe','t')")
        exec("INSERT INTO attendance(event_id,uid_hash,tapped_at) VALUES(1,'AA','t')")
        exec("INSERT INTO attendance(event_id,uid_hash,tapped_at) VALUES(2,'AA','t')")
        exec("INSERT INTO attendance(event_id,uid_hash,tapped_at) VALUES(2,'BB','t')")
        exec("INSERT INTO roster(name,added_at) VALUES('Unclaimed Person','t')")
    }

    @After
    fun close() = con.close()

    /** The whole feature in one assertion pair: the event and its check-ins go,
     *  another event's check-ins stay. */
    @Test
    fun `deleting an event takes its attendance and no one else's`() {
        deleteEvent(2)
        assertEquals(0, count("SELECT COUNT(*) FROM events WHERE id=2"))
        assertEquals(0, count("SELECT COUNT(*) FROM attendance WHERE event_id=2"))
        assertEquals(1, count("SELECT COUNT(*) FROM events WHERE id=1"))
        assertEquals(1, count("SELECT COUNT(*) FROM attendance WHERE event_id=1"))
    }

    /** People are event-independent: someone who attended only the deleted event
     *  stays enrolled, so their next badge tap is a check-in and not another
     *  name prompt. Imported roster names are equally untouched. */
    @Test
    fun `people enrollments and the roster pool survive a deletion`() {
        deleteEvent(2)
        assertEquals(2, count("SELECT COUNT(*) FROM people"))
        assertEquals(1, count("SELECT COUNT(*) FROM roster"))
    }

    /** The ordering rule, proved rather than asserted: with foreign keys on, the
     *  event row cannot go first while attendance still references it. This is
     *  why [Db.deleteEvent] runs the two statements in the order it does. */
    @Test
    fun `deleting the event before its attendance violates the foreign key`() {
        val e = try {
            exec(Db.DELETE_EVENT, 2)
            null
        } catch (e: SQLException) {
            e
        }
        assertTrue(
            "expected a foreign-key failure, got ${e?.message}",
            e?.message?.contains("FOREIGN KEY constraint failed") == true)
    }

    /** A throw between the two deletes must leave the event intact — the
     *  transaction [Db.deleteEvent] wraps them in, exercised here through JDBC's
     *  equivalent. Half a deletion would strand an event with no check-ins or,
     *  worse, check-ins with no event. */
    @Test
    fun `an abandoned transaction leaves the event and its attendance intact`() {
        con.autoCommit = false
        exec(Db.DELETE_ATTENDANCE_FOR_EVENT, 2)
        con.rollback()
        con.autoCommit = true
        assertEquals(1, count("SELECT COUNT(*) FROM events WHERE id=2"))
        assertEquals(2, count("SELECT COUNT(*) FROM attendance WHERE event_id=2"))
    }

    /** The number the confirmation dialog states out loud. Wrong here and the
     *  operator is told the wrong thing is about to be destroyed. */
    @Test
    fun `the confirmation count is scoped to one event`() {
        assertEquals(2, count(Db.COUNT_ATTENDANCE, 2))
        assertEquals(1, count(Db.COUNT_ATTENDANCE, 1))
        assertEquals(0, count(Db.COUNT_ATTENDANCE, 99))
    }

    /** Deleting an event nobody attended is the ordinary case for a test event:
     *  it removes exactly one row and disturbs nothing. */
    @Test
    fun `deleting an event with no check-ins removes only the event`() {
        exec("INSERT INTO events(id,name,created_at) VALUES(3,'Empty','t')")
        deleteEvent(3)
        assertEquals(0, count("SELECT COUNT(*) FROM events WHERE id=3"))
        assertEquals(3, count("SELECT COUNT(*) FROM attendance"))
        assertEquals(2, count("SELECT COUNT(*) FROM events"))
    }

    /** The same two statements, in the same order, that [Db.deleteEvent] runs. */
    private fun deleteEvent(id: Long) {
        con.autoCommit = false
        exec(Db.DELETE_ATTENDANCE_FOR_EVENT, id)
        exec(Db.DELETE_EVENT, id)
        con.commit()
        con.autoCommit = true
    }

    private fun exec(sql: String, vararg args: Any) =
        con.prepareStatement(sql).use { s ->
            args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
            s.execute()
        }

    private fun count(sql: String, vararg args: Any): Int =
        con.prepareStatement(sql).use { s ->
            args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
            s.executeQuery().use { r -> r.next(); r.getInt(1) }
        }
}
