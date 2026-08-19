using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Windows.Media;

namespace BadgeCheckIn;

/// <summary>
/// Branding loaded at startup from theme.json in the data directory
/// (%LOCALAPPDATA%\BadgeCheckIn). If the file is absent or invalid the app
/// runs with the neutral defaults below — no organization branding is
/// compiled into the binary. Schema: appTitle, primary, accent, background,
/// foreground (hex colors), optional logoPath (absolute, or relative to the
/// directory containing theme.json).
/// </summary>
public sealed class Theme
{
    public string AppTitle { get; set; } = "Event Check-In";
    public string Primary { get; set; } = "#3F3F46";
    public string Accent { get; set; } = "#E0E0E0";
    public string Background { get; set; } = "#FFFFFF";
    public string Foreground { get; set; } = "#1A1A1A";
    public string? LogoPath { get; set; }

    [JsonIgnore] public string? ResolvedLogoPath { get; private set; }
    [JsonIgnore] public SolidColorBrush PrimaryBrush { get; private set; } = null!;
    [JsonIgnore] public SolidColorBrush AccentBrush { get; private set; } = null!;
    [JsonIgnore] public SolidColorBrush BackgroundBrush { get; private set; } = null!;
    [JsonIgnore] public SolidColorBrush ForegroundBrush { get; private set; } = null!;
    /// <summary>Black or white, whichever reads against Primary.</summary>
    [JsonIgnore] public SolidColorBrush OnPrimaryBrush { get; private set; } = null!;

    public static Theme Load(string dir)
    {
        var theme = new Theme();
        var path = Path.Combine(dir, "theme.json");
        try
        {
            if (File.Exists(path))
                theme = JsonSerializer.Deserialize<Theme>(File.ReadAllText(path),
                    new JsonSerializerOptions { PropertyNameCaseInsensitive = true }) ?? new Theme();
        }
        // A broken theme file must not take the kiosk down — run neutral instead.
        catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException)
        {
            theme = new Theme();
        }
        theme.Resolve(dir);
        return theme;
    }

    private void Resolve(string dir)
    {
        PrimaryBrush = ParseBrush(Primary, Color.FromRgb(0x3F, 0x3F, 0x46));
        AccentBrush = ParseBrush(Accent, Color.FromRgb(0xE0, 0xE0, 0xE0));
        BackgroundBrush = ParseBrush(Background, Color.FromRgb(0xFF, 0xFF, 0xFF));
        ForegroundBrush = ParseBrush(Foreground, Color.FromRgb(0x1A, 0x1A, 0x1A));

        var p = PrimaryBrush.Color;
        var luminance = (0.299 * p.R + 0.587 * p.G + 0.114 * p.B) / 255.0;
        OnPrimaryBrush = new SolidColorBrush(luminance > 0.5 ? Colors.Black : Colors.White);
        OnPrimaryBrush.Freeze();

        if (!string.IsNullOrWhiteSpace(LogoPath))
        {
            var logo = Path.IsPathRooted(LogoPath) ? LogoPath : Path.Combine(dir, LogoPath);
            if (File.Exists(logo)) ResolvedLogoPath = Path.GetFullPath(logo);
        }
    }

    private static SolidColorBrush ParseBrush(string hex, Color fallback)
    {
        var c = fallback;
        try { if (ColorConverter.ConvertFromString(hex) is Color parsed) c = parsed; }
        catch (FormatException) { }
        var b = new SolidColorBrush(c);
        b.Freeze();
        return b;
    }
}
