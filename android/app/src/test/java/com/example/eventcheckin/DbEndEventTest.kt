package com.example.eventcheckin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Ending an event, executed against a real SQLite engine using [Db]'s own
 * schema and statements — the same approach and the same limits as
 * `DbDeleteEventTest`.
 *
 * Ending is the opposite of deleting and the tests say so: nothing is removed,
 * the event simply stops being selectable. The v2 → v3 migration is exercised
 * here too, against a table built in the OLD shape, because "existing events
 * survive the upgrade" is a claim about an `ALTER TABLE` that a fresh-install
 * schema would never run.
 */
class DbEndEventTest {

    private lateinit var con: Connection

    /** The events table as v2 shipped it, before ended_at existed. Copied from
     *  the v2 schema and kept here rather than in production code: it is
     *  history, and only an upgrade test has any business creating it. */
    private val createEventsV2 =
        "CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "name TEXT NOT NULL, created_at TEXT NOT NULL)"

    @Before
    fun open() {
        con = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys=ON")
    }

    @After
    fun close() = con.close()

    private fun freshSchema() {
        for (sql in listOf(
            Db.CREATE_META, Db.CREATE_PEOPLE, Db.CREATE_EVENTS,
            Db.CREATE_ATTENDANCE, Db.CREATE_ROSTER)) exec(sql)
        exec("INSERT INTO events(id,name,created_at) VALUES(1,'Yesterday','t')")
        exec("INSERT INTO events(id,name,created_at) VALUES(2,'Today','t')")
        exec("INSERT INTO people(uid_hash,name,enrolled_at) VALUES('AA','Jane Doe','t')")
        exec("INSERT INTO attendance(event_id,uid_hash,tapped_at) VALUES(1,'AA','t')")
    }

    /** The whole feature: an ended event leaves the picker and nothing else
     *  changes. `listEvents` is what fills the picker on both twins. */
    @Test
    fun `an ended event leaves the picker and an active one stays`() {
        freshSchema()
        assertEquals(listOf(2L, 1L), activeEventIds())
        exec(Db.END_EVENT, "2026-08-20T18:00:00-07:00", 1)
        assertEquals(listOf(2L), activeEventIds())
    }

    /** Ending is bookkeeping, not deletion — the distinction the two features
     *  turn on. Everything an export or an audit would need is still there. */
    @Test
    fun `ending keeps the event row its attendance and its people`() {
        freshSchema()
        exec(Db.END_EVENT, "2026-08-20T18:00:00-07:00", 1)
        assertEquals(1, count("SELECT COUNT(*) FROM events WHERE id=1"))
        assertEquals(1, count(Db.COUNT_ATTENDANCE, 1))
        assertEquals(1, count("SELECT COUNT(*) FROM people"))
        assertNotNull(scalar("SELECT ended_at FROM events WHERE id=1"))
    }

    /** A new event is active the moment it is created: ended_at defaults to
     *  NULL rather than to anything clever. */
    @Test
    fun `a newly created event is active`() {
        freshSchema()
        exec("INSERT INTO events(name,created_at) VALUES('Fresh','t')")
        assertNull(scalar("SELECT ended_at FROM events WHERE name='Fresh'"))
        assertEquals(3, activeEventIds().size)
    }

    /** Ending is scoped to one event — the failure that would silently close
     *  every open event on the tablet at once. */
    @Test
    fun `ending one event does not end the others`() {
        freshSchema()
        exec(Db.END_EVENT, "2026-08-20T18:00:00-07:00", 1)
        assertNull(scalar("SELECT ended_at FROM events WHERE id=2"))
    }

    /** The v2 → v3 upgrade path: an installed database whose events table
     *  predates the column. Rows must survive and come back ACTIVE, since an
     *  event that was open before the upgrade was not ended by installing an
     *  APK. */
    @Test
    fun `the v2 to v3 migration adds the column and leaves existing events active`() {
        exec(createEventsV2)
        exec("INSERT INTO events(id,name,created_at) VALUES(7,'Older event','t')")

        exec(Db.ALTER_EVENTS_ADD_ENDED_AT)

        assertEquals(1, count("SELECT COUNT(*) FROM events WHERE id=7"))
        assertEquals("Older event", scalar("SELECT name FROM events WHERE id=7"))
        assertNull(scalar("SELECT ended_at FROM events WHERE id=7"))
        assertEquals(listOf(7L), activeEventIds())
    }

    private fun activeEventIds(): List<Long> =
        con.prepareStatement(Db.LIST_ACTIVE_EVENTS).use { s ->
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(r.getLong(1)) }
            }
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

    private fun scalar(sql: String): String? =
        con.prepareStatement(sql).use { s ->
            s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        }
}
