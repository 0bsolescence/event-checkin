using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Media.Imaging;

namespace BadgeCheckIn;

/// <summary>
/// Kiosk-style WPF main window. All branding — title, palette, logo — comes
/// from the Theme loaded at startup; the code carries no organization-specific
/// strings. Reader callbacks arrive on a background thread and are marshalled
/// onto the UI thread via SafeDispatch, refused once shutdown has begun.
/// </summary>
public sealed class MainWindow : Window
{
    private readonly Db _db;
    private readonly PcscReader _reader = new();
    private readonly Theme _theme;

    private readonly ComboBox _eventBox;
    private readonly TextBlock _count;
    private readonly ListView _list;
    private readonly TextBlock _status;

    private long? _eventId;

    private bool _closing;

    // True while an enroll prompt is open: further taps are ignored so two
    // quick unknown-badge reads cannot stack dialogs (mirrors the Android
    // twin's tapBusy guard — the modal prompt still pumps dispatcher work,
    // so queued reader callbacks would otherwise open a second prompt).
    private bool _tapBusy;

    private const string EnrollText = "New badge — enter this person's name:";

    /// <summary>A personnel roster is a few thousand names; anything larger is not one.</summary>
    private const long MaxRosterBytes = 5 * 1024 * 1024;

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

    public MainWindow()
    {
        _theme = Theme.Load(DataDir);
        _db = new Db(Path.Combine(DataDir, "checkin.db"));

        Title = _theme.AppTitle;
        Width = 960; Height = 720;
        MinWidth = 640; MinHeight = 480;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = _theme.BackgroundBrush;
        FontSize = 16;

        // -- Header: logo + title left, live headcount right, on the primary color.
        var titleRow = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        if (_theme.ResolvedLogoPath is string logoPath)
        {
            try
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.UriSource = new Uri(logoPath);
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.EndInit();
                bmp.Freeze();
                titleRow.Children.Add(new Image { Source = bmp, Height = 56, Margin = new Thickness(0, 0, 16, 0) });
            }
            catch (Exception)
            {
                // Corrupt or unreadable logo: run without one rather than take the kiosk down.
            }
        }
        titleRow.Children.Add(new TextBlock
        {
            Text = _theme.AppTitle, FontSize = 34, FontWeight = FontWeights.Bold,
            Foreground = _theme.OnPrimaryBrush, VerticalAlignment = VerticalAlignment.Center
        });

        _count = new TextBlock
        {
            Text = "0", FontSize = 64, FontWeight = FontWeights.Bold,
            Foreground = _theme.AccentBrush, HorizontalAlignment = HorizontalAlignment.Right
        };
        var countPanel = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        countPanel.Children.Add(new TextBlock
        {
            Text = "HEADCOUNT", FontSize = 13, FontWeight = FontWeights.SemiBold,
            Foreground = _theme.OnPrimaryBrush, HorizontalAlignment = HorizontalAlignment.Right
        });
        countPanel.Children.Add(_count);

        var headerRow = new DockPanel { LastChildFill = true };
        DockPanel.SetDock(countPanel, Dock.Right);
        headerRow.Children.Add(countPanel);
        headerRow.Children.Add(titleRow);
        var header = new Border
        {
            Background = _theme.PrimaryBrush,
            Padding = new Thickness(24, 12, 24, 12),
            Child = headerRow
        };

        // -- Toolbar: event picker + New Event + Export, sized for touch.
        _eventBox = new ComboBox
        {
            MinWidth = 360, FontSize = 18, Padding = new Thickness(10, 8, 10, 8),
            VerticalContentAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 0, 8)
        };
        var newEvent = MakeButton("New Event…");
        var deleteEvent = MakeButton("Delete Event…");
        var export = MakeButton("Export CSV");
        var importRoster = MakeButton("Import Roster…");
        // WrapPanel, not StackPanel: the picker plus four touch-sized buttons
        // overflow the 960px default width and badly overflow the 640px minimum,
        // and a StackPanel neither wraps nor scrolls — the rightmost button
        // would simply be unreachable. Wrapping keeps every action on screen at
        // any supported size. Flagged by cross-vendor review 2026-08-20.
        var toolbar = new WrapPanel { Margin = new Thickness(24, 16, 24, 8) };
        toolbar.Children.Add(new TextBlock
        {
            Text = "Event:", FontSize = 18, Foreground = _theme.ForegroundBrush,
            VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 10, 8)
        });
        toolbar.Children.Add(_eventBox);
        toolbar.Children.Add(newEvent);
        toolbar.Children.Add(deleteEvent);
        toolbar.Children.Add(export);
        toolbar.Children.Add(importRoster);

        // -- Roster: large live list, newest tap on top.
        var grid = new GridView();
        grid.Columns.Add(new GridViewColumn
        {
            Header = "Name", Width = 480,
            DisplayMemberBinding = new Binding(nameof(RosterRow.Name))
        });
        grid.Columns.Add(new GridViewColumn
        {
            Header = "Checked in", Width = 300,
            DisplayMemberBinding = new Binding(nameof(RosterRow.CheckedIn))
        });
        _list = new ListView
        {
            View = grid, FontSize = 20, Margin = new Thickness(24, 8, 24, 12),
            Foreground = _theme.ForegroundBrush
        };

        // -- Status bar.
        _status = new TextBlock { Text = "Starting reader…", FontSize = 15, Foreground = _theme.OnPrimaryBrush };
        var statusBar = new Border
        {
            Background = _theme.PrimaryBrush,
            Padding = new Thickness(24, 8, 24, 8),
            Child = _status
        };

        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        Grid.SetRow(header, 0);
        Grid.SetRow(toolbar, 1);
        Grid.SetRow(_list, 2);
        Grid.SetRow(statusBar, 3);
        root.Children.Add(header);
        root.Children.Add(toolbar);
        root.Children.Add(_list);
        root.Children.Add(statusBar);
        Content = root;

        newEvent.Click += (_, _) => NewEvent();
        deleteEvent.Click += (_, _) => DeleteEvent();
        export.Click += (_, _) => Export();
        importRoster.Click += (_, _) => ImportRoster();
        _eventBox.SelectionChanged += (_, _) => SelectEvent();

        _reader.StatusChanged += s => SafeDispatch(() => _status.Text = s);
        _reader.CardTapped += uid => SafeDispatch(() => OnTap(uid));

        Loaded += (_, _) => { RefreshEvents(); _reader.Start(); };
        Closing += (_, _) => _closing = true;
        Closed += (_, _) => { _reader.Dispose(); _db.Dispose(); };
    }

    private Button MakeButton(string text) => new()
    {
        Content = text, FontSize = 18,
        // The bottom margin is the gap between wrapped toolbar rows; harmless
        // on the roster picker's single row of buttons.
        Padding = new Thickness(20, 8, 20, 8), Margin = new Thickness(12, 0, 0, 8),
        Background = _theme.PrimaryBrush, Foreground = _theme.OnPrimaryBrush,
        BorderThickness = new Thickness(0)
    };

    private void RefreshEvents()
    {
        _eventBox.Items.Clear();
        foreach (var (id, name) in _db.ListEvents()) _eventBox.Items.Add(new EventItem(id, name));
        if (_eventBox.Items.Count > 0) { _eventBox.SelectedIndex = 0; return; }
        // Nothing left to check anyone in to. Clearing the combo does not clear
        // the selection this class holds, so drop it BEFORE prompting: a tap
        // while the prompt is up must not record attendance against an event
        // that no longer exists (an FK failure, not a duplicate — it would
        // escape RecordTap's 2067-only catch and take the kiosk down).
        _eventId = null;
        _list.Items.Clear();
        UpdateCount();
        NewEvent();
    }

    private void NewEvent()
    {
        var name = Prompt("Event name (e.g. \"All-Hands BBQ 2026-08-21\"):", "New Event");
        if (string.IsNullOrWhiteSpace(name)) return;
        _db.CreateEvent(name.Trim());
        RefreshEvents();
    }

    /// <summary>Deletes the selected event and everything checked in to it, behind
    /// a confirmation that names the event and the number of records going with
    /// it. Cancelling — including closing the box — changes nothing. Sits on the
    /// toolbar next to New Event; the Android twin puts it in its ⋮ Setup menu,
    /// which this window does not have.</summary>
    private void DeleteEvent()
    {
        if (_eventId is not long ev || _eventBox.SelectedItem is not EventItem item)
        {
            _status.Text = "No event selected — nothing to delete.";
            return;
        }
        var count = _db.AttendanceCount(ev);
        var message = count == 0
            ? $"Delete \"{item.Name}\"?\n\nNobody has checked in to it, so no attendance " +
              "records are lost. Enrolled people are not affected."
            : $"Delete \"{item.Name}\" and its {(count == 1 ? "1 check-in" : $"{count} check-ins")}?\n\n" +
              "The attendance records for this event are removed permanently and cannot be " +
              "recovered — export the CSV first if you need the record.\n\n" +
              "Enrolled people are not affected.";
        // No is the default button: a stray Enter or Escape cancels. The tap
        // guard is held across the box for the same reason the enroll prompt
        // holds it — the modal still pumps dispatcher work, so a badge tapped
        // mid-confirmation would otherwise stack an enroll prompt on top of a
        // destructive question.
        MessageBoxResult answer;
        _tapBusy = true;
        try
        {
            answer = MessageBox.Show(this, message, "Delete event",
                                     MessageBoxButton.YesNo, MessageBoxImage.Warning,
                                     MessageBoxResult.No);
        }
        finally { _tapBusy = false; }
        if (answer != MessageBoxResult.Yes) return;
        _db.DeleteEvent(ev);
        // Re-selects the newest remaining event, or asks for a new one when this
        // was the last.
        RefreshEvents();
        _status.Text = $"Deleted event: {item.Name}";
    }

    private void SelectEvent()
    {
        if (_eventBox.SelectedItem is not EventItem ev) return;
        _eventId = ev.Id;
        _list.Items.Clear();
        foreach (var (name, at) in _db.Attendance(ev.Id))
            _list.Items.Add(new RosterRow(name, Pretty(at)));
        UpdateCount();
    }

    /// <summary>Marshals reader-thread callbacks onto the UI thread, refusing them
    /// once shutdown has begun (window closed / db disposed race).</summary>
    private void SafeDispatch(Action action)
    {
        if (_closing || Dispatcher.HasShutdownStarted) return;
        try { Dispatcher.BeginInvoke(() => { if (!_closing) action(); }); }
        catch (InvalidOperationException) { /* dispatcher torn down mid-call */ }
    }

    private void OnTap(byte[] uid)
    {
        if (_tapBusy) return;
        if (_eventId is not long ev) { _status.Text = "Create/select an event first."; return; }
        var hash = _db.HashUid(uid);
        var name = _db.LookupName(hash);
        if (name is null)
        {
            _tapBusy = true;
            try
            {
                name = PromptIdentity();
                if (string.IsNullOrWhiteSpace(name)) { _status.Text = "Enrollment cancelled."; return; }
                name = name.Trim();
                _db.Enroll(hash, name);
                // Claimed: this name is no longer waiting in the unmatched pool.
                _db.RemoveRosterName(name);
            }
            finally { _tapBusy = false; }
        }
        if (_db.RecordTap(ev, hash))
        {
            _list.Items.Insert(0, new RosterRow(name, Pretty(DateTime.Now.ToString("o"))));
            _status.Text = $"Checked in: {name}";
            System.Media.SystemSounds.Beep.Play();
        }
        else _status.Text = $"{name} is already checked in.";
        UpdateCount();
    }

    /// <summary>An unknown badge asks who it belongs to. With an imported roster
    /// that is a search-and-pick from the names nobody has claimed yet; without
    /// one it is the same free-text prompt the app has always used.</summary>
    private string? PromptIdentity()
    {
        var pool = _db.RosterNames();
        if (pool.Count == 0) return Prompt(EnrollText, "Enroll");
        var (picked, typeInstead) = PickFromRoster(pool);
        return typeInstead ? Prompt(EnrollText, "Enroll") : picked;
    }

    private (string? Name, bool TypeInstead) PickFromRoster(List<string> pool)
    {
        var search = new TextBox
        {
            FontSize = 18, Padding = new Thickness(6), Margin = new Thickness(0, 8, 0, 8)
        };
        var list = new ListBox { FontSize = 20, Height = 340, Foreground = _theme.ForegroundBrush };
        foreach (var name in pool) list.Items.Add(name);

        var ok = MakeButton("Check In");
        var type = MakeButton("Type a Name");
        var cancel = MakeButton("Cancel");
        cancel.IsCancel = true;
        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right,
            Margin = new Thickness(0, 12, 0, 0)
        };
        buttons.Children.Add(type);
        buttons.Children.Add(cancel);
        buttons.Children.Add(ok);

        var panel = new StackPanel { Margin = new Thickness(16) };
        panel.Children.Add(new TextBlock
        {
            Text = "Search the imported roster, or type a name instead:",
            FontSize = 16, TextWrapping = TextWrapping.Wrap, Foreground = _theme.ForegroundBrush
        });
        panel.Children.Add(search);
        panel.Children.Add(list);
        panel.Children.Add(buttons);

        var dlg = new Window
        {
            Title = "Who is this badge?", Width = 480, SizeToContent = SizeToContent.Height,
            ResizeMode = ResizeMode.NoResize, ShowInTaskbar = false,
            Owner = IsVisible ? this : null,
            WindowStartupLocation = IsVisible
                ? WindowStartupLocation.CenterOwner : WindowStartupLocation.CenterScreen,
            Content = panel, Background = _theme.BackgroundBrush
        };

        string? chosen = null;
        var typeInstead = false;
        void Choose()
        {
            if (list.SelectedItem is not string picked) return;
            chosen = picked;
            dlg.DialogResult = true;
        }
        search.TextChanged += (_, _) =>
        {
            list.Items.Clear();
            foreach (var name in Roster.FilterNames(pool, search.Text)) list.Items.Add(name);
        };
        list.MouseDoubleClick += (_, _) => Choose();
        ok.Click += (_, _) => Choose();
        type.Click += (_, _) => { typeInstead = true; dlg.DialogResult = true; };
        dlg.Loaded += (_, _) => search.Focus();
        dlg.ShowDialog();
        return (chosen, typeInstead);
    }

    /// <summary>Imports a personnel list exported from the badge-access system.
    /// Nothing here can take the kiosk down: an unreadable, oversized or
    /// unrecognizable file is refused with a message and no rows are stored.</summary>
    private void ImportRoster()
    {
        var picker = new Microsoft.Win32.OpenFileDialog
        {
            Title = "Import personnel roster",
            Filter = "CSV files (*.csv)|*.csv|All files (*.*)|*.*",
            CheckFileExists = true
        };
        if (picker.ShowDialog(this) != true) return;

        string text;
        try
        {
            // A personnel roster is a few thousand names; anything larger is not one.
            if (new FileInfo(picker.FileName).Length > MaxRosterBytes)
            {
                ImportFailed("That file is too large to be a roster (limit 5 MB) — nothing imported.");
                return;
            }
            text = File.ReadAllText(picker.FileName);
        }
        catch (Exception e)
        {
            ImportFailed($"Could not read that file — nothing imported: {e.Message}");
            return;
        }

        var result = Roster.Import(text);
        if (result.Error is string error) { ImportFailed(error); return; }
        if (result.Entries.Count == 0)
        {
            ImportFailed("No usable rows in that file — nothing imported.");
            return;
        }

        int mapped = 0, pooled = 0;
        try
        {
            foreach (var entry in result.Entries)
            {
                // MAPPED: the credential number resolved to badge bytes, so this
                // person is enrolled now and their first tap just checks them in.
                var uid = entry.Credential is null ? null : Roster.CredentialToUidBytes(entry.Credential);
                if (uid is not null)
                {
                    _db.Enroll(_db.HashUid(uid), entry.Name);
                    _db.RemoveRosterName(entry.Name);
                    mapped++;
                }
                // PICKER: name only, waiting to be claimed by a badge.
                else if (_db.AddRosterName(entry.Name)) pooled++;
            }
        }
        catch (Exception e)
        {
            ImportFailed($"Import stopped partway — {mapped + pooled} names were stored: {e.Message}");
            return;
        }

        var summary = $"Imported {result.Entries.Count} names: {mapped} pre-enrolled, " +
                      $"{pooled} waiting for a first tap. {result.Skipped} rows skipped.";
        _status.Text = summary;
        MessageBox.Show(this,
            $"{summary}\n\nName column: {result.NameHeader ?? "?"}. " +
            $"Credential column: {result.CredentialHeader ?? "none found"}.",
            "Import roster");
    }

    private void ImportFailed(string message)
    {
        _status.Text = message;
        MessageBox.Show(this, message, "Import roster");
    }

    private void Export()
    {
        if (_eventId is not long ev || _eventBox.SelectedItem is not EventItem item) return;
        var path = _db.ExportCsv(ev, item.Name, DataDir);
        _status.Text = $"Exported: {path}";
        MessageBox.Show(this, $"Attendance exported to:\n{path}", "Export complete");
    }

    private void UpdateCount() => _count.Text = _list.Items.Count.ToString();

    private static string Pretty(string iso) =>
        DateTime.TryParse(iso, out var dt) ? dt.ToString("yyyy-MM-dd HH:mm:ss") : iso;

    private string? Prompt(string text, string caption)
    {
        var box = new TextBox { FontSize = 18, Padding = new Thickness(6), Margin = new Thickness(0, 12, 0, 12) };
        var ok = new Button
        {
            Content = "OK", IsDefault = true, FontSize = 16, MinWidth = 96,
            Padding = new Thickness(20, 6, 20, 6), HorizontalAlignment = HorizontalAlignment.Right
        };
        var panel = new StackPanel { Margin = new Thickness(16) };
        panel.Children.Add(new TextBlock { Text = text, FontSize = 16, TextWrapping = TextWrapping.Wrap });
        panel.Children.Add(box);
        panel.Children.Add(ok);
        var dlg = new Window
        {
            Title = caption, Width = 460, SizeToContent = SizeToContent.Height,
            ResizeMode = ResizeMode.NoResize, ShowInTaskbar = false,
            Owner = IsVisible ? this : null,
            WindowStartupLocation = IsVisible
                ? WindowStartupLocation.CenterOwner : WindowStartupLocation.CenterScreen,
            Content = panel, Background = _theme.BackgroundBrush
        };
        ok.Click += (_, _) => dlg.DialogResult = true;
        dlg.Loaded += (_, _) => box.Focus();
        return dlg.ShowDialog() == true ? box.Text : null;
    }

    private sealed record RosterRow(string Name, string CheckedIn);

    private sealed record EventItem(long Id, string Name)
    {
        public override string ToString() => Name;
    }
}
