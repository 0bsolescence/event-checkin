package com.example.eventcheckin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The color rules the Windows Theme.cs applies, pinned on this side too. */
class ThemeColorsTest {

    @Test
    fun `hex parses in three four six and eight digit forms`() {
        assertEquals(0xFF0078B8.toInt(), ThemeColors.parseOrNull("#0078b8"))
        assertEquals(0xFF0078B8.toInt(), ThemeColors.parseOrNull("0078B8"))
        assertEquals(0xFFAABBCC.toInt(), ThemeColors.parseOrNull("#abc"))
        assertEquals(0x80112233.toInt(), ThemeColors.parseOrNull("#80112233"))
    }

    @Test
    fun `garbage falls back per field rather than failing the theme`() {
        for (bad in listOf(null, "", "   ", "#12345", "not-a-color", "#GGGGGG"))
            assertNull(ThemeColors.parseOrNull(bad))
        assertEquals(Theme.DEFAULT_PRIMARY, ThemeColors.parse("nonsense", Theme.DEFAULT_PRIMARY))
    }

    @Test
    fun `on-primary is white over dark and black over light`() {
        assertEquals(ThemeColors.WHITE, ThemeColors.onColor(Theme.DEFAULT_PRIMARY))
        assertEquals(ThemeColors.WHITE, ThemeColors.onColor(0xFF0078B8.toInt()))
        assertEquals(ThemeColors.BLACK, ThemeColors.onColor(0xFFF6B11A.toInt()))
        assertEquals(ThemeColors.BLACK, ThemeColors.onColor(ThemeColors.WHITE))
        assertEquals(ThemeColors.WHITE, ThemeColors.onColor(ThemeColors.BLACK))
    }

    @Test
    fun `alpha does not change which text color is chosen`() {
        assertEquals(ThemeColors.onColor(0xFF0078B8.toInt()), ThemeColors.onColor(0x000078B8))
    }

    /** Byte size does not bound a bitmap — a small file can decode to gigabytes
     *  and take the kiosk with it. Caught by cross-vendor review 2026-08-20. */
    @Test
    fun `logo dimensions are bounded independently of file size`() {
        assertTrue(Theme.isSafeLogoSize(512, 128))
        assertTrue(Theme.isSafeLogoSize(4000, 4000))
        assertFalse(Theme.isSafeLogoSize(4001, 10))
        assertFalse(Theme.isSafeLogoSize(10, 4001))
        assertFalse(Theme.isSafeLogoSize(0, 100))
        assertFalse(Theme.isSafeLogoSize(-1, -1))
    }
}
