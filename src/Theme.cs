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
/// foreground (hex colors), optional logoPath — a relative path resolved
/// inside the directory containing theme.json only.
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
        try
        {
            var path = Path.Combine(dir, "theme.json");
            if (File.Exists(path))
                theme = JsonSerializer.Deserialize<Theme>(File.ReadAllText(path),
                    new JsonSerializerOptions { PropertyNameCaseInsensitive = true }) ?? new Theme();
        }
        // A broken theme file must not take the kiosk down — run neutral instead.
        catch (Exception)
        {
            theme = new Theme();
        }
        theme.Resolve(dir);
        return theme;
    }

    private void Resolve(string dir)
    {
        try
        {
            // JSON null or whitespace falls back per-field, not whole-theme.
            if (string.IsNullOrWhiteSpace(AppTitle)) AppTitle = "Event Check-In";

            PrimaryBrush = ParseBrush(Primary, Color.FromRgb(0x3F, 0x3F, 0x46));
            AccentBrush = ParseBrush(Accent, Color.FromRgb(0xE0, 0xE0, 0xE0));
            BackgroundBrush = ParseBrush(Background, Color.FromRgb(0xFF, 0xFF, 0xFF));
            ForegroundBrush = ParseBrush(Foreground, Color.FromRgb(0x1A, 0x1A, 0x1A));

            var p = PrimaryBrush.Color;
            var luminance = (0.299 * p.R + 0.587 * p.G + 0.114 * p.B) / 255.0;
            OnPrimaryBrush = Frozen(luminance > 0.5 ? Colors.Black : Colors.White);

            ResolvedLogoPath = ResolveLogoPath(dir);
        }
        catch (Exception)
        {
            // Belt over the per-field suspenders: whatever a theme file manages
            // to throw, the kiosk starts neutral instead of dying.
            AppTitle = "Event Check-In";
            PrimaryBrush = Frozen(Color.FromRgb(0x3F, 0x3F, 0x46));
            AccentBrush = Frozen(Color.FromRgb(0xE0, 0xE0, 0xE0));
            BackgroundBrush = Frozen(Color.FromRgb(0xFF, 0xFF, 0xFF));
            ForegroundBrush = Frozen(Color.FromRgb(0x1A, 0x1A, 0x1A));
            OnPrimaryBrush = Frozen(Colors.White);
            ResolvedLogoPath = null;
        }
    }

    /// <summary>Only a file inside the directory containing theme.json qualifies.
    /// Rooted and UNC logo paths are rejected before any filesystem probe:
    /// File.Exists on an attacker-chosen \\host\share\logo.png makes Windows
    /// reach out over SMB and can leak NetNTLM credentials.</summary>
    private string? ResolveLogoPath(string dir)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(LogoPath)) return null;
            var p = LogoPath.Trim();
            if (p.StartsWith(@"\\") || p.StartsWith("//") || Path.IsPathRooted(p)) return null;
            var themeDir = Path.GetFullPath(dir);
            var resolved = Path.GetFullPath(Path.Combine(themeDir, p));
            if (!resolved.StartsWith(themeDir + Path.DirectorySeparatorChar,
                                     StringComparison.OrdinalIgnoreCase))
                return null; // ..\ traversal escaping the theme directory
            return File.Exists(resolved) ? resolved : null;
        }
        catch (Exception) { return null; } // any path weirdness degrades to no logo
    }

    private static SolidColorBrush ParseBrush(string? hex, Color fallback)
    {
        var c = fallback;
        try
        {
            if (!string.IsNullOrWhiteSpace(hex) && ColorConverter.ConvertFromString(hex) is Color parsed)
                c = parsed;
        }
        catch (Exception) { } // unparseable color falls back per-field
        return Frozen(c);
    }

    private static SolidColorBrush Frozen(Color c)
    {
        var b = new SolidColorBrush(c);
        b.Freeze();
        return b;
    }
}
