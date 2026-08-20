package com.example.eventcheckin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The badge-id diagnostic's arithmetic. It exists to answer one question — does
 * a Virtual Keypad `External_Number` relate to the UID the reader sees — and a
 * wrong number here would answer it wrongly and permanently, since the verdict
 * decides whether credential mapping is ever switched on.
 */
class BadgeIdTest {

    /** Hand-checkable: 0x04A2 = 1186, and the same bytes the other way,
     *  0xA204 = 41476. */
    @Test
    fun `two bytes read four ways`() {
        val r = BadgeId.describe(byteArrayOf(0x04, 0xA2.toByte()))
        assertEquals(2, r.byteLength)
        assertEquals("04A2", r.hex)
        assertEquals("A204", r.hexReversed)
        assertEquals("1186", r.decimal)
        assertEquals("41476", r.decimalReversed)
    }

    /** A 4-byte NXP UID, the common case. The high bit is set, which is exactly
     *  where a signed reading would go negative. */
    @Test
    fun `a four byte uid with the high bit set stays unsigned`() {
        val r = BadgeId.describe(byteArrayOf(0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte()))
        assertEquals("9ABCDEF0", r.hex)
        assertEquals("F0DEBC9A", r.hexReversed)
        assertEquals("2596069104", r.decimal)
        assertEquals("4041129114", r.decimalReversed)
    }

    /** A 7-byte UID: too wide for an Int, and wide enough that getting the
     *  byte order wrong produces a plausible-looking wrong answer. */
    @Test
    fun `a seven byte uid keeps full width in both directions`() {
        val r = BadgeId.describe(
            byteArrayOf(0x04, 0x5A, 0x3B, 0x2C, 0x1D, 0x0E, 0x6F))
        assertEquals(7, r.byteLength)
        assertEquals("045A3B2C1D0E6F", r.hex)
        assertEquals("6F0E1D2C3B5A04", r.hexReversed)
        assertEquals("1225110096514671", r.decimal)
        assertEquals("31259240873810436", r.decimalReversed)
    }

    /** Leading zero bytes are part of the hex reading and not part of the
     *  decimal one — the display shows both so neither has to be guessed. */
    @Test
    fun `a leading zero byte is kept in hex and dropped in decimal`() {
        val r = BadgeId.describe(byteArrayOf(0x00, 0x01, 0x02))
        assertEquals("000102", r.hex)
        assertEquals("020100", r.hexReversed)
        assertEquals("258", r.decimal)
        assertEquals("131328", r.decimalReversed)
    }

    @Test
    fun `one byte reads the same in both directions`() {
        val r = BadgeId.describe(byteArrayOf(0x2A))
        assertEquals("2A", r.hex)
        assertEquals("2A", r.hexReversed)
        assertEquals("42", r.decimal)
        assertEquals("42", r.decimalReversed)
    }

    /** The UI rejects an empty tag id before it gets here; if it ever arrives
     *  anyway, the diagnostic reports nothing rather than throwing in front of
     *  a queue of people. */
    @Test
    fun `an empty uid is described rather than thrown at`() {
        val r = BadgeId.describe(ByteArray(0))
        assertEquals(0, r.byteLength)
        assertEquals("", r.hex)
        assertEquals("0", r.decimal)
        assertEquals("0", r.decimalReversed)
    }
}
