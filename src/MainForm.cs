namespace BadgeCheckIn;

public sealed class MainForm : Form
{
    private readonly Db _db;
    private readonly PcscReader _reader = new();

    private readonly ComboBox _eventBox = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 320 };
    private readonly Button _newEvent = new() { Text = "New Event…", AutoSize = true };
    private readonly Button _export = new() { Text = "Export CSV", AutoSize = true };
    private readonly Label _count = new() { Text = "Headcount: 0", AutoSize = true, Font = new Font(FontFamily.GenericSansSerif, 16, FontStyle.Bold) };
    private readonly ListView _list = new() { View = View.Details, Dock = DockStyle.Fill, FullRowSelect = true };
    private readonly Label _status = new() { Dock = DockStyle.Bottom, Text = "Starting reader…", Padding = new Padding(6), AutoSize = false, Height = 28 };

    private long? _eventId;

    private bool _closing;

    /// <summary>Data lives in %LOCALAPPDATA%\BadgeCheckIn — the exe itself can sit
    /// anywhere, including read-only media or Program Files.</summary>
    public static string DataDir
    {
        get
        {
            var d = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "BadgeCheckIn");
            Directory.CreateDirectory(d);
            return d;
        }
    }

    public MainForm()
    {
        Text = "BFT Event Check-In";
        Width = 640; Height = 560;
        _db = new Db(Path.Combine(DataDir, "checkin.db"));

        _list.Columns.Add("Name", 340);
        _list.Columns.Add("Checked in", 240);

        var top = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(6) };
        top.Controls.AddRange([new Label { Text = "Event:", AutoSize = true, Padding = new Padding(0, 8, 0, 0) },
                               _eventBox, _newEvent, _export, _count]);
        Controls.Add(_list);
        Controls.Add(top);
        Controls.Add(_status);

        _newEvent.Click += (_, _) => NewEvent();
        _export.Click += (_, _) => Export();
        _eventBox.SelectedIndexChanged += (_, _) => SelectEvent();

        _reader.StatusChanged += s => SafeInvoke(() => _status.Text = s);
        _reader.CardTapped += uid => SafeInvoke(() => OnTap(uid));

        Load += (_, _) => { RefreshEvents(); _reader.Start(); };
        FormClosing += (_, _) => _closing = true;
        FormClosed += (_, _) => { _reader.Dispose(); _db.Dispose(); };
    }

    private void RefreshEvents()
    {
        _eventBox.Items.Clear();
        foreach (var (id, name) in _db.ListEvents()) _eventBox.Items.Add(new EventItem(id, name));
        if (_eventBox.Items.Count > 0) _eventBox.SelectedIndex = 0; else NewEvent();
    }

    private void NewEvent()
    {
        var name = Prompt("Event name (e.g. \"All-Hands BBQ 2026-08-21\"):", "New Event");
        if (string.IsNullOrWhiteSpace(name)) return;
        _db.CreateEvent(name.Trim());
        RefreshEvents();
    }

    private void SelectEvent()
    {
        if (_eventBox.SelectedItem is not EventItem ev) return;
        _eventId = ev.Id;
        _list.Items.Clear();
        foreach (var (name, at) in _db.Attendance(ev.Id))
            _list.Items.Add(new ListViewItem([name, Pretty(at)]));
        UpdateCount();
    }

    /// <summary>Marshals reader-thread callbacks onto the UI thread, refusing them
    /// once shutdown has begun (handle destroyed / db disposed race).</summary>
    private void SafeInvoke(Action action)
    {
        if (_closing || IsDisposed || !IsHandleCreated) return;
        try { BeginInvoke(() => { if (!_closing && !IsDisposed) action(); }); }
        catch (InvalidOperationException) { /* handle torn down mid-call */ }
    }

    private void OnTap(byte[] uid)
    {
        if (_eventId is not long ev) { _status.Text = "Create/select an event first."; return; }
        var hash = _db.HashUid(uid);
        var name = _db.LookupName(hash);
        if (name is null)
        {
            name = Prompt("New badge — enter this person's name:", "Enroll");
            if (string.IsNullOrWhiteSpace(name)) { _status.Text = "Enrollment cancelled."; return; }
            _db.Enroll(hash, name.Trim());
            name = name.Trim();
        }
        if (_db.RecordTap(ev, hash))
        {
            _list.Items.Insert(0, new ListViewItem([name, Pretty(DateTime.Now.ToString("o"))]));
            _status.Text = $"Checked in: {name}";
            System.Media.SystemSounds.Beep.Play();
        }
        else _status.Text = $"{name} is already checked in.";
        UpdateCount();
    }

    private void Export()
    {
        if (_eventId is not long ev || _eventBox.SelectedItem is not EventItem item) return;
        var path = _db.ExportCsv(ev, item.Name, DataDir);
        _status.Text = $"Exported: {path}";
        MessageBox.Show($"Attendance exported to:\n{path}", "Export complete");
    }

    private void UpdateCount() => _count.Text = $"Headcount: {_list.Items.Count}";

    private static string Pretty(string iso) =>
        DateTime.TryParse(iso, out var dt) ? dt.ToString("yyyy-MM-dd HH:mm:ss") : iso;

    private static string? Prompt(string text, string caption)
    {
        using var f = new Form { Width = 420, Height = 150, Text = caption,
                                 FormBorderStyle = FormBorderStyle.FixedDialog,
                                 StartPosition = FormStartPosition.CenterParent,
                                 MinimizeBox = false, MaximizeBox = false };
        var lbl = new Label { Left = 12, Top = 12, Width = 380, Text = text };
        var box = new TextBox { Left = 12, Top = 40, Width = 380 };
        var ok = new Button { Text = "OK", Left = 312, Top = 72, Width = 80, DialogResult = DialogResult.OK };
        f.Controls.AddRange([lbl, box, ok]);
        f.AcceptButton = ok;
        return f.ShowDialog() == DialogResult.OK ? box.Text : null;
    }

    private sealed record EventItem(long Id, string Name)
    {
        public override string ToString() => Name;
    }
}
