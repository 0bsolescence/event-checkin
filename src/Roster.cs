using System.Text;
using System.Text.RegularExpressions;

namespace BadgeCheckIn;

/// <summary>
/// Personnel-roster import. A roster is a CSV exported from the badge-access
/// system (DMP EverOn / Virtual Keypad); importing it lets a tap resolve to a
/// name without anyone typing at the kiosk.
///
/// Pure parsing only — no UI, no database — and a line-for-line mirror of the
/// Android twin (android/app/src/main/java/com/example/eventcheckin/Roster.kt),
/// which carries the unit tests for these rules.
///
/// PRIVACY: a credential number is used only to derive a badge hash at import
/// time; it is never written to the database. Rows whose credential cannot be
/// turned into a UID keep the NAME ONLY and wait to be picked at first tap.
/// </summary>
public static class Roster
{
    /// <summary>Column indexes found in a header row; -1 when absent.</summary>
    public sealed record Columns(int Full, int First, int Last, int Credential)
    {
        public bool HasName => Full >= 0 || (First >= 0 && Last >= 0);
    }

    public sealed record Entry(string Name, string? Credential);

    /// <param name="Skipped">Rows dropped: no name, or a duplicate of a name already taken.</param>
    public sealed record Result(
        List<Entry> Entries, string? NameHeader, string? CredentialHeader, int Skipped, string? Error);

    public const string NoNameColumn =
        "No name column found. The header needs a column containing \"name\", " +
        "or separate \"First Name\" and \"Last Name\" columns.";
    public const string NoRows = "That file has no rows to import.";

    /// <summary>
    /// Accepted header shapes, in the order they are preferred:
    /// name — exactly <c>Name</c>; then <c>Full Name</c> / <c>Display Name</c> /
    /// <c>Employee Name</c>; then <c>First Name</c> + <c>Last Name</c> as a pair;
    /// then any header containing "name" (one not qualified by
    /// first/last/middle/nick wins over one that is).
    /// credential — any header containing "credential" or "card"; one that also
    /// carries a number word (number, no, #, id) wins.
    /// </summary>
    public static Columns DetectColumns(IReadOnlyList<string> header)
    {
        var h = header.Select(x => x.Trim().ToLowerInvariant()).ToList();
        int IndexOf(Func<string, bool> match)
        {
            for (var i = 0; i < h.Count; i++) if (match(h[i])) return i;
            return -1;
        }

        var first = IndexOf(c => c.Contains("first") && (c.Contains("name") || c == "first"));
        var last = IndexOf(c => c.Contains("last") && (c.Contains("name") || c == "last"));

        var full = IndexOf(c => c == "name");
        if (full < 0)
            full = IndexOf(c => c.Contains("full name") || c.Contains("display name")
                                || c.Contains("employee name") || c.Contains("person name"));
        // Only once no explicit full-name column exists, and only when there is
        // no first/last pair to join: an export carrying "Username" alongside
        // "First Name" and "Last Name" must import people, not login handles.
        if (full < 0 && !(first >= 0 && last >= 0))
        {
            full = IndexOf(c => c.Contains("name")
                                && !c.Contains("first") && !c.Contains("last")
                                && !c.Contains("middle") && !c.Contains("nick"));
            if (full < 0) full = IndexOf(c => c.Contains("name"));
        }

        var candidates = Enumerable.Range(0, h.Count)
            .Where(i => h[i].Contains("credential") || h[i].Contains("card")).ToList();
        var numbered = new Regex(@"number|\bno\b|#|\bid\b");
        var credential = candidates.Count == 0
            ? -1
            : candidates.FirstOrDefault(i => numbered.IsMatch(h[i]), candidates[0]);

        return new Columns(full, first, last, credential);
    }

    public static Result Import(string text)
    {
        var rows = ParseCsv(text);
        if (rows.Count < 2) return new Result([], null, null, 0, NoRows);
        var header = rows[0];
        var cols = DetectColumns(header);
        if (!cols.HasName) return new Result([], null, null, 0, NoNameColumn);

        var entries = new List<Entry>();
        var taken = new HashSet<string>();
        var skipped = 0;
        foreach (var row in rows.Skip(1))
        {
            var name = NameOf(row, cols);
            // A blank name, or a second row for a name already taken, is dropped
            // rather than guessed at: a picker with two identical entries is
            // worse than one.
            if (name.Length == 0 || !taken.Add(name.ToLowerInvariant()))
            {
                skipped++;
                continue;
            }
            var credential = cols.Credential >= 0 ? Cell(row, cols.Credential) : null;
            entries.Add(new Entry(name, string.IsNullOrEmpty(credential) ? null : credential));
        }
        return new Result(
            entries,
            Cell(header, cols.Full >= 0 ? cols.Full : cols.First),
            Cell(header, cols.Credential),
            skipped,
            null);
    }

    private static string? Cell(IReadOnlyList<string> row, int index) =>
        index >= 0 && index < row.Count ? row[index].Trim() : null;

    private static string NameOf(IReadOnlyList<string> row, Columns cols)
    {
        var full = Cell(row, cols.Full);
        if (!string.IsNullOrEmpty(full)) return full;
        return string.Join(" ", new[] { cols.First, cols.Last }
            .Select(i => Cell(row, i))
            .Where(s => !string.IsNullOrEmpty(s)));
    }

    /// <summary>RFC 4180 shaped: quoted cells may hold commas, newlines and
    /// doubled quotes; CR, LF and CRLF all end a row; a UTF-8 BOM is stripped.
    /// Rows that are entirely blank are dropped.</summary>
    public static List<List<string>> ParseCsv(string text)
    {
        var s = text.TrimStart('\uFEFF');
        var rows = new List<List<string>>();
        var row = new List<string>();
        var cell = new StringBuilder();
        var quoted = false;
        for (var i = 0; i < s.Length; i++)
        {
            var c = s[i];
            if (quoted && c == '"' && i + 1 < s.Length && s[i + 1] == '"') { cell.Append('"'); i++; }
            else if (c == '"') quoted = !quoted;
            else if (!quoted && c == ',') { row.Add(cell.ToString()); cell.Clear(); }
            else if (!quoted && (c == '\n' || c == '\r'))
            {
                if (c == '\r' && i + 1 < s.Length && s[i + 1] == '\n') i++;
                row.Add(cell.ToString());
                cell.Clear();
                rows.Add(row);
                row = [];
            }
            else cell.Append(c);
        }
        if (cell.Length > 0 || row.Count > 0)
        {
            row.Add(cell.ToString());
            rows.Add(row);
        }
        return rows.Where(r => r.Any(x => !string.IsNullOrWhiteSpace(x))).ToList();
    }

    /// <summary>Case-insensitive substring match, for the unknown-badge name picker.</summary>
    public static List<string> FilterNames(IEnumerable<string> names, string query)
    {
        var q = query.Trim();
        return q.Length == 0
            ? names.ToList()
            : names.Where(n => n.Contains(q, StringComparison.OrdinalIgnoreCase)).ToList();
    }

    // PROVISIONAL, AND DELIBERATELY OFF. Whether the access system's credential
    // number is derivable from the NFC UID is unverified: Step 4 of
    // THURSDAY-HANDOFF.md compares a Virtual Keypad credential number against
    // the id the tablet reads from the same badge, and that verdict supplies the
    // real transform. Until it exists, no transform is known to be correct, and
    // a guessed one would silently pre-enroll the wrong people under each
    // other's names. Off means every imported row falls to the picker, which
    // works either way. Flipping this to true is the whole change.
    private static readonly bool CredentialMappingEnabled = false;

    /// <returns>The badge UID bytes this credential number belongs to, or null
    /// when no mapping is known — the caller then keeps the name only.</returns>
    public static byte[]? CredentialToUidBytes(string credential) =>
        CredentialMappingEnabled ? ProvisionalUidBytes(credential) : null;

    /// <summary>The placeholder transform Step 4 will either confirm, correct or
    /// delete: read the credential as hex if it looks like hex, otherwise as a
    /// decimal integer, big-endian and minimal-width. Unproven against real
    /// hardware.</summary>
    internal static byte[]? ProvisionalUidBytes(string credential)
    {
        var s = credential.Trim();
        if (s.StartsWith("0x", StringComparison.OrdinalIgnoreCase)) s = s[2..];
        s = new string(s.Where(c => c is not (' ' or ':' or '-')).ToArray());
        if (s.Length == 0 || s.Length > 20) return null;
        if (!s.All(Uri.IsHexDigit)) return null;
        if (s.Any(c => !char.IsAsciiDigit(c)) && s.Length % 2 == 0)
            return Convert.FromHexString(s);
        if (!long.TryParse(s, out var n) || n <= 0) return null;
        var width = 0;
        for (var v = n; v > 0; v >>= 8) width++;
        var bytes = new byte[width];
        for (var i = 0; i < width; i++) bytes[i] = (byte)((n >> (8 * (width - 1 - i))) & 0xFF);
        return bytes;
    }
}
