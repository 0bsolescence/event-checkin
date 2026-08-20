package com.example.eventcheckin

/**
 * Personnel-roster import. A roster is a CSV exported from the badge-access
 * system (DMP EverOn / Virtual Keypad); importing it lets a tap resolve to a
 * name without anyone typing at the kiosk.
 *
 * Everything here is pure — no Android types — so the parsing, the header
 * detection and the credential transform are unit testable off-device, and the
 * Windows twin (`../src/Roster.cs`) is a line-for-line mirror.
 *
 * PRIVACY: a credential number is used only to derive a badge hash at import
 * time; it is never written to the database. Rows whose credential cannot be
 * turned into a UID keep the NAME ONLY and wait to be picked at first tap.
 */
object Roster {

    /** Column indexes found in a header row; -1 when absent. */
    data class Columns(val full: Int, val first: Int, val last: Int, val credential: Int) {
        val hasName: Boolean get() = full >= 0 || (first >= 0 && last >= 0)
    }

    data class Entry(val name: String, val credential: String?)

    data class Result(
        val entries: List<Entry> = emptyList(),
        val nameHeader: String? = null,
        val credentialHeader: String? = null,
        /** Rows dropped: no name, or a duplicate of a name already taken. */
        val skipped: Int = 0,
        val error: String? = null,
    )

    const val NO_NAME_COLUMN =
        "No name column found. The header needs a column containing \"name\", " +
            "or separate \"First Name\" and \"Last Name\" columns."
    const val NO_ROWS = "That file has no rows to import."

    /**
     * Accepted header shapes, in the order they are preferred:
     * name — exactly `Name`; then `Full Name` / `Display Name` / `Employee Name`;
     * then `First Name` + `Last Name` as a pair; then any header containing
     * "name" (one not qualified by first/last/middle/nick wins over one that is).
     * credential — any header containing "credential" or "card"; one that also
     * carries a number word (`number`, `no`, `#`, `id`) wins.
     */
    fun detectColumns(header: List<String>): Columns {
        val h = header.map { it.trim().lowercase() }
        val first = h.indexOfFirst { it.contains("first") && (it.contains("name") || it == "first") }
        val last = h.indexOfFirst { it.contains("last") && (it.contains("name") || it == "last") }

        var full = h.indexOfFirst { it == "name" }
        if (full < 0) full = h.indexOfFirst { c ->
            listOf("full name", "display name", "employee name", "person name").any { c.contains(it) }
        }
        // Only once no explicit full-name column exists, and only when there is
        // no first/last pair to join: an export carrying "Username" alongside
        // "First Name" and "Last Name" must import people, not login handles.
        if (full < 0 && !(first >= 0 && last >= 0)) {
            full = h.indexOfFirst { c ->
                c.contains("name") && listOf("first", "last", "middle", "nick").none { c.contains(it) }
            }
            if (full < 0) full = h.indexOfFirst { it.contains("name") }
        }

        val candidates = h.indices.filter { h[it].contains("credential") || h[it].contains("card") }
        val numbered = Regex("""number|\bno\b|#|\bid\b""")
        val credential = candidates.firstOrNull { numbered.containsMatchIn(h[it]) }
            ?: candidates.firstOrNull() ?: -1

        return Columns(full, first, last, credential)
    }

    fun import(text: String): Result {
        val rows = parseCsv(text)
        if (rows.size < 2) return Result(error = NO_ROWS)
        val header = rows.first()
        val cols = detectColumns(header)
        if (!cols.hasName) return Result(error = NO_NAME_COLUMN)

        val entries = ArrayList<Entry>()
        val taken = HashSet<String>()
        var skipped = 0
        for (row in rows.drop(1)) {
            val name = nameOf(row, cols)
            // A blank name, or a second row for a name already taken, is dropped
            // rather than guessed at: a picker with two identical entries is worse
            // than one.
            if (name.isEmpty() || !taken.add(name.lowercase())) {
                skipped++
                continue
            }
            val credential =
                if (cols.credential >= 0) row.getOrNull(cols.credential)?.trim()?.ifEmpty { null }
                else null
            entries.add(Entry(name, credential))
        }
        return Result(
            entries = entries,
            nameHeader = header.getOrNull(if (cols.full >= 0) cols.full else cols.first)?.trim(),
            credentialHeader = header.getOrNull(cols.credential)?.trim(),
            skipped = skipped)
    }

    private fun nameOf(row: List<String>, cols: Columns): String {
        val full = row.getOrNull(cols.full)?.trim().orEmpty()
        if (full.isNotEmpty()) return full
        return listOf(cols.first, cols.last)
            .mapNotNull { row.getOrNull(it)?.trim()?.ifEmpty { null } }
            .joinToString(" ")
    }

    /** RFC 4180 shaped: quoted cells may hold commas, newlines and doubled
     *  quotes; CR, LF and CRLF all end a row; a UTF-8 BOM is stripped. Rows that
     *  are entirely blank are dropped. */
    fun parseCsv(text: String): List<List<String>> {
        val s = text.removePrefix("\uFEFF")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { row.add(cell.toString()); cell.setLength(0) }
        fun endRow() { endCell(); rows.add(row); row = ArrayList() }
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> endCell()
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                    endRow()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    /** Case-insensitive substring match, for the unknown-badge name picker. */
    fun filterNames(names: List<String>, query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return names
        return names.filter { it.lowercase().contains(q) }
    }

    // PROVISIONAL, AND DELIBERATELY OFF. Whether the access system's credential
    // number is derivable from the NFC UID is unverified: Step 4 of
    // THURSDAY-HANDOFF.md compares a Virtual Keypad credential number against
    // the id the tablet reads from the same badge, and that verdict supplies the
    // real transform. Until it exists, no transform is known to be correct, and
    // a guessed one would silently pre-enroll the wrong people under each
    // other's names. Off means every imported row falls to the picker, which
    // works either way. Flipping this to true is the whole change.
    private val CREDENTIAL_MAPPING_ENABLED = false

    /** @return the badge UID bytes this credential number belongs to, or null
     *  when no mapping is known — the caller then keeps the name only. */
    fun credentialToUidBytes(credential: String): ByteArray? =
        if (CREDENTIAL_MAPPING_ENABLED) provisionalUidBytes(credential) else null

    /** The placeholder transform Step 4 will either confirm, correct or delete:
     *  read the credential as hex if it looks like hex, otherwise as a decimal
     *  integer, big-endian and minimal-width. Unproven against real hardware. */
    internal fun provisionalUidBytes(credential: String): ByteArray? {
        val s = credential.trim().removePrefix("0x").removePrefix("0X")
            .filterNot { it == ' ' || it == ':' || it == '-' }
        if (s.isEmpty() || s.length > 20) return null
        val hex = s.all { it in "0123456789abcdefABCDEF" }
        if (!hex) return null
        if (s.any { it !in "0123456789" } && s.length % 2 == 0)
            return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val n = s.toLongOrNull() ?: return null
        if (n <= 0) return null
        var width = 0
        var v = n
        while (v > 0) { width++; v = v shr 8 }
        return ByteArray(width) { ((n shr (8 * (width - 1 - it))) and 0xFF).toByte() }
    }
}
