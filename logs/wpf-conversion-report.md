# WPF Conversion Report — 2026-08-19

Task: convert the WinForms kiosk UI to WPF with theme-file-driven branding,
preparing the repo for unbranded open-sourcing under Apache-2.0 (BFT branding
local-only). Base: d394343.

## What changed

- **`src/MainWindow.cs`** (new, replaces `src/MainForm.cs`, deleted): code-built
  WPF window, kiosk layout — primary-colored header with optional logo + themed
  app title + big accent-colored headcount number (64 pt), event picker +
  New Event + Export CSV toolbar with touch-sized buttons, large (20 pt) live
  roster ListView (newest tap on top), primary-colored status bar. WPF prompt
  dialog replaces the WinForms one. Check-in/enroll/export logic is a straight
  port of MainForm's.
- **`src/Theme.cs`** (new): loads `theme.json` from `%LOCALAPPDATA%\BadgeCheckIn`
  at startup; schema appTitle / primary / accent / background / foreground /
  optional logoPath (absolute or relative to the theme file's directory).
  Missing file, malformed JSON, bad hex, or unreadable logo all degrade to the
  committed neutral default ("Event Check-In", plain palette) — the kiosk never
  crashes on a theme problem. Header/button text auto-flips black/white by
  primary-color luminance. All brushes frozen.
- **`src/Program.cs`**: WinForms `Application.Run` → WPF
  `Application { ShutdownMode = OnMainWindowClose }.Run(new MainWindow())`.
- **`src/BadgeCheckIn.csproj`**: `UseWindowsForms` → `UseWPF`; everything else
  (net8.0-windows, single-file publish, EnableWindowsTargeting) unchanged.
- **`src/Db.cs` / `src/PcscReader.cs`**: logic UNCHANGED per brief (they passed
  today's codex audit). One mechanical exception: `using System.IO;` added to
  Db.cs — the WPF SDK excludes System.IO from implicit usings (Path/File
  conflict avoidance with System.Windows.Shapes), so the file no longer
  compiled without it. No statement in either file was touched.

## Threading guard (parity with the audited WinForms semantics)

PcscReader events arrive on its background watch thread. `SafeDispatch`
mirrors MainForm's `SafeInvoke`: refuse when `_closing` or
`Dispatcher.HasShutdownStarted`, `Dispatcher.BeginInvoke` with a second
`_closing` re-check inside the delegate, `InvalidOperationException` swallowed
for the teardown race. `Closing` sets `_closing`; `Closed` disposes reader
then db — same order as before.

## Theming / open-sourcing artifacts

- `themes/theme.example.json` — committed neutral schema example.
- `themes/local/theme.bft.json` — BFT theme ("BFT Event Check-In", primary
  #0078b8, accent #f6b11a, logoPath bft-logo-icon.png). LOCAL ONLY. Note: the
  brief said this file would already exist; it did not — only the png did — so
  it was written fresh.
- `themes/local/INSTALL.md` — one-line copy instruction to
  `%LOCALAPPDATA%\BadgeCheckIn\theme.json`.
- `.gitignore` — added `themes/local/`.
- `LICENSE` — canonical Apache-2.0 text (11,358 bytes, fetched from
  apache.org). `NOTICE` — "Event Check-In / Copyright 2026 Daniel Miller".
  Per Apache convention the LICENSE text stays pristine; the copyright line
  lives in NOTICE and the README License section.
- `README.md` — genericized: neutral "Event Check-In" name, DMP/CSM-2P now an
  example of 13.56 MHz ISO 14443 badges (DESFire EV2), verification checklist
  and honest status stamps kept (still UNVERIFIED against hardware), new
  Theming section, new theme-applies checklist item, License section.

## Verification (postconditions, not exit codes)

- `dotnet build -c Release` in src/: **Build succeeded, 0 Warning(s),
  0 Error(s)**; `src/bin/Release/net8.0-windows/win-x64/BadgeCheckIn.dll`
  mtime 16:15 today (fresh artifact, not a stale WinForms one).
- `git check-ignore -v` matches all three `themes/local/` files against
  `.gitignore:10:themes/local/`; `git ls-files themes/local/ | wc -l` = 0
  (nothing under themes/local is tracked).
- Runtime behavior (theme load, reader, taps) remains UNVERIFIED — no Windows
  box has run this. The README checklist is the gate, unchanged in spirit.

## Decisions taken / flags for team-lead

- **logs/ gitignore exception**: logs/ is not ignored by any pattern, so this
  report commits cleanly with no exception rule needed. Flagged per brief in
  case logs/ is later gitignored.
- **Concurrent `android/` scaffold**: an untracked `android/` Gradle/Kotlin
  tree appeared at 16:14–16:15 (another agent's Android port, package
  com.example.eventcheckin). NOT mine, NOT committed — my commit stages
  explicit paths only. Left untouched for its owner.
- Not pushed, no GitHub repo created — publish stays principal-gated.

## Round 2 — codex P2 fixes (2026-08-19, second commit)

1. **logoPath SMB/credential leak (Theme.cs)**: logoPath is now confined to
   the directory containing theme.json. UNC (`\\` or `//` prefix) and rooted
   paths are rejected BEFORE any filesystem probe — the probe itself
   (`File.Exists` on `\\attacker\share\logo.png`) is what would reach out
   over SMB and leak NetNTLM. Relative paths are resolved via
   `Path.GetFullPath` and must start with the theme dir + separator
   (OrdinalIgnoreCase), killing `..\` traversal. README Theming section
   updated to state the relative-only rule and why.
2. **Fallback gaps**: `Load` and `ResolveLogoPath` now catch `Exception`
   (deliberate — the kiosk must never die to a bad theme); `Resolve` has
   per-field guards (null/whitespace appTitle → "Event Check-In",
   `ParseBrush` catches everything per color) plus a whole-body belt that
   resets every field to hard-coded neutral on any throw. MainWindow's logo
   image decode (`BitmapImage.EndInit`, `CacheOption=OnLoad` so decoding
   happens there) also catches `Exception` and skips the logo.

**Verification honesty**: no Windows runtime here, so the two scenario
postconditions are closed by code-path reasoning, not execution: (a) UNC
logoPath — `p.StartsWith(@"\\")` returns null on the first check, before
`File.Exists`, so no SMB touch is reachable; rooted paths die on
`Path.IsPathRooted` (Windows semantics at runtime; note `C:\...` is only
"rooted" on Windows, which is the deployment target). (b) Corrupt image —
`File.Exists` passes, so the path reaches MainWindow, where `EndInit()`
throws during decode and the new catch-Exception skips the logo; the window
continues building. Both paths end at "app runs neutral/unlogo'd". Build
re-verified: 0 warnings, 0 errors, artifact mtime fresh.
