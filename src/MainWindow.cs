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
            VerticalContentAlignment = VerticalAlignment.Center
        };
        var newEvent = MakeButton("New Event…");
        var export = MakeButton("Export CSV");
        var toolbar = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(24, 16, 24, 8) };
        toolbar.Children.Add(new TextBlock
        {
            Text = "Event:", FontSize = 18, Foreground = _theme.ForegroundBrush,
            VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 10, 0)
        });
        toolbar.Children.Add(_eventBox);
        toolbar.Children.Add(newEvent);
        toolbar.Children.Add(export);

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
        export.Click += (_, _) => Export();
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
        Padding = new Thickness(20, 8, 20, 8), Margin = new Thickness(12, 0, 0, 0),
        Background = _theme.PrimaryBrush, Foreground = _theme.OnPrimaryBrush,
        BorderThickness = new Thickness(0)
    };

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
            _list.Items.Insert(0, new RosterRow(name, Pretty(DateTime.Now.ToString("o"))));
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
