using System.Security.Cryptography;
using System.Text;
using Microsoft.Data.Sqlite;

namespace BadgeCheckIn;

/// <summary>
/// Local SQLite store. Privacy contract (matches the memo sent to the CEO):
/// the raw badge UID is NEVER stored — only a salted SHA-256 hash, so taps
/// can be matched to enrolled names without the database ever containing a
/// credential number. Attendance rows are append-only from the UI.
/// </summary>
public sealed class Db : IDisposable
{
    private readonly SqliteConnection _con;

    public Db(string path)
    {
        _con = new SqliteConnection($"Data Source={path}");
        _con.Open();
        Exec("""
            CREATE TABLE IF NOT EXISTS meta   (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS people (uid_hash TEXT PRIMARY KEY, name TEXT NOT NULL,
                                               enrolled_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT,
                                               name TEXT NOT NULL, created_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS attendance (event_id INTEGER NOT NULL REFERENCES events(id),
                                               uid_hash TEXT NOT NULL REFERENCES people(uid_hash),
                                               tapped_at TEXT NOT NULL,
                                               UNIQUE(event_id, uid_hash));
            """);
        // Per-install random salt, created once. Deleting the DB re-keys everything.
        if (Scalar("SELECT value FROM meta WHERE key='salt'") is null)
            Exec("INSERT INTO meta(key,value) VALUES('salt',$v)",
                 ("$v", Convert.ToHexString(RandomNumberGenerator.GetBytes(16))));
        Salt = (string)Scalar("SELECT value FROM meta WHERE key='salt'")!;
    }

    public string Salt { get; }

    public string HashUid(byte[] uid) =>
        Convert.ToHexString(SHA256.HashData(Encoding.ASCII.GetBytes(Salt).Concat(uid).ToArray()));

    public string? LookupName(string uidHash) =>
        (string?)Scalar("SELECT name FROM people WHERE uid_hash=$h", ("$h", uidHash));

    public void Enroll(string uidHash, string name) =>
        Exec("INSERT OR REPLACE INTO people(uid_hash,name,enrolled_at) VALUES($h,$n,$t)",
             ("$h", uidHash), ("$n", name), ("$t", DateTime.Now.ToString("o")));

    public long CreateEvent(string name)
    {
        Exec("INSERT INTO events(name,created_at) VALUES($n,$t)",
             ("$n", name), ("$t", DateTime.Now.ToString("o")));
        return (long)Scalar("SELECT last_insert_rowid()")!;
    }

    public List<(long id, string name)> ListEvents()
    {
        var list = new List<(long, string)>();
        using var cmd = _con.CreateCommand();
        cmd.CommandText = "SELECT id,name FROM events ORDER BY id DESC";
        using var r = cmd.ExecuteReader();
        while (r.Read()) list.Add((r.GetInt64(0), r.GetString(1)));
        return list;
    }

    /// <returns>false if this person already checked in to this event.</returns>
    public bool RecordTap(long eventId, string uidHash)
    {
        try
        {
            Exec("INSERT INTO attendance(event_id,uid_hash,tapped_at) VALUES($e,$h,$t)",
                 ("$e", eventId), ("$h", uidHash), ("$t", DateTime.Now.ToString("o")));
            return true;
        }
        catch (SqliteException e) when (e.SqliteErrorCode == 19) { return false; } // UNIQUE hit
    }

    public List<(string name, string tappedAt)> Attendance(long eventId)
    {
        var list = new List<(string, string)>();
        using var cmd = _con.CreateCommand();
        cmd.CommandText = """
            SELECT p.name, a.tapped_at FROM attendance a
            JOIN people p ON p.uid_hash = a.uid_hash
            WHERE a.event_id = $e ORDER BY a.tapped_at
            """;
        cmd.Parameters.AddWithValue("$e", eventId);
        using var r = cmd.ExecuteReader();
        while (r.Read()) list.Add((r.GetString(0), r.GetString(1)));
        return list;
    }

    /// <summary>Audit export: name, event, timestamp. Nothing else leaves the box.</summary>
    public string ExportCsv(long eventId, string eventName, string dir)
    {
        var rows = Attendance(eventId);
        var safe = string.Join("_", eventName.Split(Path.GetInvalidFileNameChars()));
        var path = Path.Combine(dir, $"attendance_{safe}_{DateTime.Now:yyyyMMdd_HHmm}.csv");
        var sb = new StringBuilder("Name,Event,CheckedInAt\r\n");
        foreach (var (name, at) in rows)
            sb.Append($"\"{name.Replace("\"", "\"\"")}\",\"{eventName.Replace("\"", "\"\"")}\",{at}\r\n");
        sb.Append($"\"TOTAL HEADCOUNT: {rows.Count}\",,\r\n");
        File.WriteAllText(path, sb.ToString(), Encoding.UTF8);
        return path;
    }

    private void Exec(string sql, params (string, object)[] args)
    {
        using var cmd = _con.CreateCommand();
        cmd.CommandText = sql;
        foreach (var (k, v) in args) cmd.Parameters.AddWithValue(k, v);
        cmd.ExecuteNonQuery();
    }

    private object? Scalar(string sql, params (string, object)[] args)
    {
        using var cmd = _con.CreateCommand();
        cmd.CommandText = sql;
        foreach (var (k, v) in args) cmd.Parameters.AddWithValue(k, v);
        return cmd.ExecuteScalar();
    }

    public void Dispose() => _con.Dispose();
}
