package com.example.eventcheckin

import java.math.BigInteger

/**
 * Reads a badge UID four ways for the "Show badge id on next tap" diagnostic.
 * Its whole job is to answer one open question — whether a Virtual Keypad
 * `External_Number` (`R_123456`) relates to the UID the reader sees — by
 * putting the same bytes on screen in the shapes an access system might have
 * encoded them, so the comparison can be made by eye against a known badge.
 *
 * Byte order is the reason there are four: which end a system starts from is
 * exactly the sort of thing that is not written down anywhere, and a UID that
 * looks unrelated big-endian can be the same number backwards.
 *
 * Pure — no Android types — so it is unit tested off-device and the Windows
 * twin (`../src/BadgeId.cs`) is a line-for-line mirror.
 *
 * Named BadgeId rather than Uid for a Windows-specific reason, kept identical
 * here so the twins still match: `UIElement.Uid` is an inherited string
 * property in WPF, so `Uid.Describe(...)` inside a control does not compile.
 *
 * PRIVACY: this is display only. Nothing here hashes, stores, logs or exports
 * a UID; the caller shows the reading and drops it, and the diagnostic
 * suppresses the check-in flow for that tap so the badge is not enrolled by
 * being read.
 */
object BadgeId {

    /** @param decimal the bytes as one unsigned integer, most significant first.
     *  @param decimalReversed the same bytes read from the other end. */
    data class Reading(
        val byteLength: Int,
        val hex: String,
        val hexReversed: String,
        val decimal: String,
        val decimalReversed: String,
    )

    fun describe(uid: ByteArray): Reading {
        val reversed = uid.reversedArray()
        return Reading(
            byteLength = uid.size,
            hex = toHex(uid),
            hexReversed = toHex(reversed),
            decimal = toDecimal(uid),
            decimalReversed = toDecimal(reversed))
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    /** Unsigned: a UID is a bit pattern, never a negative number, so the sign
     *  byte a plain BigInteger(ByteArray) would read is forced positive. */
    private fun toDecimal(bytes: ByteArray): String =
        if (bytes.isEmpty()) "0" else BigInteger(1, bytes).toString()
}
