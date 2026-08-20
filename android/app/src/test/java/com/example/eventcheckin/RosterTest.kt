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

    /** A first/last pair outranks any generic column that merely contains
     *  "name": an export with a Username field must import people, not login
     *  handles. Caught by cross-vendor review 2026-08-20. */
    @Test
    fun `a first and last pair beats a generic name column`() {
        val cols = Roster.detectColumns(listOf("Username", "First Name", "Last Name", "Card Number"))
        assertEquals(-1, cols.full)
        assertEquals(1, cols.first)
        assertEquals(2, cols.last)
        assertEquals(
            listOf("Jane Doe"),
            Roster.import("Username,First Name,Last Name\njdoe,Jane,Doe\n").entries.map { it.name })
    }

    @Test
    fun `a generic name column is still used when there is no pair`() {
        assertEquals(0, Roster.detectColumns(listOf("Username", "Department")).full)
    }

    /** The REAL Virtual Keypad export header, as it actually arrives. Name is
     *  the exact match at index 1 and External_Number is the card field at
     *  index 3 — this row is the whole reason the external rule exists. */
    @Test
    fun `the real Virtual Keypad export header resolves Name and External_Number`() {
        val cols = Roster.detectColumns(VIRTUAL_KEYPAD_HEADER)
        assertEquals(1, cols.full)
        assertEquals(3, cols.credential)
        assertTrue(cols.hasName)
    }

    /** The load-bearing negative. `Number` is Virtual Keypad's internal row id
     *  and `Code` is the user's keypad PIN; picking either would be wrong, and
     *  picking Code would put PINs through the credential path. Neither may be
     *  selected, and neither may reach an Entry. */
    @Test
    fun `Number and Code are never chosen and never stored`() {
        val cols = Roster.detectColumns(VIRTUAL_KEYPAD_HEADER)
        assertEquals(0, VIRTUAL_KEYPAD_HEADER.indexOf("Number"))
        assertEquals(7, VIRTUAL_KEYPAD_HEADER.indexOf("Code"))
        assertTrue("Number must not be the credential column", cols.credential != 0)
        assertTrue("Code must not be the credential column", cols.credential != 7)

        val result = Roster.import(
            VIRTUAL_KEYPAD_HEADER.joinToString(",") + "\n" +
                "41,Jane Doe,Yes,R_123456,Staff,All,All,9182,Card\n")
        assertEquals(listOf("Jane Doe"), result.entries.map { it.name })
        // The row id and the PIN appear nowhere in what was imported.
        val credentials = result.entries.mapNotNull { it.credential }
        assertEquals(listOf("123456"), credentials)
        assertTrue("the keypad PIN must never be imported", credentials.none { it == "9182" })
        assertTrue("the row id must never be imported", credentials.none { it == "41" })
        assertEquals("External_Number", result.credentialHeader)
        assertEquals("Name", result.nameHeader)
    }

    /** "External" on its own is generic — a column that is not a number cannot
     *  be a credential. */
    @Test
    fun `an external column without a number word is not a credential`() {
        assertEquals(-1, Roster.detectColumns(listOf("Name", "External System", "Active")).credential)
    }

    /** A system that says "card number" means it; "external" only usually does. */
    @Test
    fun `a card column outranks an external one`() {
        val cols = Roster.detectColumns(listOf("Name", "External_Number", "Card Number"))
        assertEquals(2, cols.credential)
    }

    /** Virtual Keypad writes R_123456; only the digits relate to the card, and
     *  the transform must never see the tag. */
    @Test
    fun `a leading alpha tag is stripped from credential values`() {
        assertEquals("123456", Roster.normalizeCredential("R_123456"))
        assertEquals("123456", Roster.normalizeCredential("  R_123456  "))
        assertEquals("123456", Roster.normalizeCredential("ABC_123456"))
        // Only a LEADING letters_ prefix, and only the first one.
        assertEquals("123_456", Roster.normalizeCredential("R_123_456"))
        assertEquals("4711", Roster.normalizeCredential("4711"))
        assertEquals("0A1B2C3D", Roster.normalizeCredential("0A1B2C3D"))
        // Nothing usable left, or nothing to begin with.
        assertNull(Roster.normalizeCredential("R_"))
        assertNull(Roster.normalizeCredential("   "))
        assertNull(Roster.normalizeCredential(null))
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

    private companion object {
        /** Verbatim from the real export, 2026-08-20. */
        val VIRTUAL_KEYPAD_HEADER = listOf(
            "Number", "Name", "Active", "External_Number", "Profiles",
            "Arm/Disarm Areas", "Access Areas", "Code", "Type")
    }
}
