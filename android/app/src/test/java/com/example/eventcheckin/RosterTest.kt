package com.example.eventcheckin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Roster import is pure, so the shapes a badge-access export can arrive in are
 *  pinned here rather than discovered on a tablet at an event. */
class RosterTest {

    @Test
    fun `quoted cells keep commas newlines and doubled quotes`() {
        val rows = Roster.parseCsv("a,\"b,1\",\"say \"\"hi\"\"\"\r\n\"two\nlines\",y,z\n")
        assertEquals(listOf("a", "b,1", "say \"hi\""), rows[0])
        assertEquals(listOf("two\nlines", "y", "z"), rows[1])
    }

    @Test
    fun `blank rows and a leading BOM are dropped`() {
        val rows = Roster.parseCsv("\uFEFFName\n\n\nJane\n,,\n")
        assertEquals(listOf(listOf("Name"), listOf("Jane")), rows)
    }

    @Test
    fun `exact Name header wins over other columns containing name`() {
        val cols = Roster.detectColumns(listOf("User Name", "Name", "Credential Number"))
        assertEquals(1, cols.full)
        assertEquals(2, cols.credential)
    }

    @Test
    fun `first and last name columns pair up when there is no full name column`() {
        val cols = Roster.detectColumns(listOf("First Name", "Last Name", "Card #"))
        assertEquals(-1, cols.full)
        assertEquals(0, cols.first)
        assertEquals(1, cols.last)
        assertEquals(2, cols.credential)
        assertTrue(cols.hasName)
    }

    @Test
    fun `a numbered credential header beats a bare one`() {
        val cols = Roster.detectColumns(listOf("Name", "Card Format", "Card Number"))
        assertEquals(2, cols.credential)
    }

    @Test
    fun `a header with no name column is refused rather than guessed at`() {
        val result = Roster.import("Badge,Department\n1234,Ops\n")
        assertEquals(Roster.NO_NAME_COLUMN, result.error)
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun `a header alone imports nothing`() {
        assertEquals(Roster.NO_ROWS, Roster.import("Name,Credential\n").error)
    }

    @Test
    fun `split name columns are joined and the credential is carried`() {
        val result = Roster.import("First Name,Last Name,Credential\nJane,Doe,4711\n")
        assertNull(result.error)
        assertEquals(listOf(Roster.Entry("Jane Doe", "4711")), result.entries)
        assertEquals("Credential", result.credentialHeader)
    }

    @Test
    fun `blank and duplicate names are skipped not imported twice`() {
        val result = Roster.import("Name\nJane Doe\n\n  \njane doe\nBob Roe\n")
        assertEquals(listOf("Jane Doe", "Bob Roe"), result.entries.map { it.name })
        assertEquals(1, result.skipped)
    }

    @Test
    fun `a row missing its credential still imports the name`() {
        val result = Roster.import("Name,Credential Number\nJane Doe,\n")
        assertEquals(1, result.entries.size)
        assertNull(result.entries[0].credential)
    }

    @Test
    fun `filtering names is case insensitive and empty query keeps everything`() {
        val pool = listOf("Jane Doe", "Bob Roe")
        assertEquals(pool, Roster.filterNames(pool, "  "))
        assertEquals(listOf("Jane Doe"), Roster.filterNames(pool, "dO"))
        assertTrue(Roster.filterNames(pool, "zz").isEmpty())
    }

    /** The load-bearing one: until Step 4 of the hand-off produces a verdict,
     *  no credential maps to a UID and every imported row must fall to the
     *  picker. A green test here is what keeps the app from pre-enrolling people
     *  under a guessed transform. */
    @Test
    fun `credential mapping is disabled pending the hardware verdict`() {
        for (c in listOf("4711", "0A1B2C3D", "0x0A1B2C3D", "", "not a number"))
            assertNull(Roster.credentialToUidBytes(c))
    }

    @Test
    fun `the provisional transform reads hex as hex and decimal as decimal`() {
        assertEquals("0A1B2C3D", Roster.provisionalUidBytes("0A:1B:2C:3D")?.toHex())
        assertEquals("1267", Roster.provisionalUidBytes("4711")?.toHex())
        assertNull(Roster.provisionalUidBytes(""))
        assertNull(Roster.provisionalUidBytes("not a number"))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
}
