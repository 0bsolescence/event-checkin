# Event Check-In

A small Windows 11 kiosk app for event attendance: tap your organization badge
on a USB desk reader, your name and time land on the roster, and the headcount
exports to CSV for the audit file. Built for catered events where auditors need
a defensible attendee count (doors propped open, outdoor events — the wall
readers can't see you, this can).

**Status: COMPILES CLEAN, UNVERIFIED against hardware.** Originally built as
WinForms 2026-08-19, converted to WPF the same day, both on a Linux node via
cross-targeting: `dotnet build -c Release` succeeds with 0 warnings / 0 errors.
It has never RUN: no Windows box, no reader, no card has touched it. The
checklist below is the postcondition list — the app is not real until those
boxes check on Windows 11 with a real reader and card.

## Privacy contract (deliberate, load-bearing)

- The raw badge UID is **never stored** — taps are matched via a salted
  SHA-256 hash (per-install random salt). Honest limit, per cross-vendor
  audit 2026-08-19: the salt lives in the same DB, and short UID spaces
  (4-byte) are brute-forceable offline by anyone who obtains the file. The
  hash prevents casual disclosure, not determined recovery — so treat
  `checkin.db` as sensitive and keep it on organization equipment.
- **Exported** records: name, event, timestamp — nothing else leaves in the
  CSV. The local DB additionally keeps enrollment/creation timestamps, the
  salted hashes, and the salt.
- Local only: no network calls anywhere in the code. Data lives in
  `%LOCALAPPDATA%\BadgeCheckIn` (DB + exports), so the exe itself can run
  from read-only media or Program Files.
- Attendance is a public record once filed with the audit documentation; that's
  a feature, and the minimal schema is what makes it a safe one.

## Hardware

- **HID OMNIKEY 5022 CL** (13.56 MHz, ISO 14443, standard Windows CCID driver —
  no driver install needed on Windows 11). ACS ACR122U also works in principle
  (same Get-UID pseudo-APDU); untested.
- Works with any 13.56 MHz ISO 14443 badge (e.g. MIFARE DESFire EV2 access
  cards, a common choice for physical access control). The app reads the
  ISO 14443 UID, not any vendor credential file — hence enroll-on-first-tap
  instead of a roster import.

## Build (on Windows, .NET 8 SDK installed)

```powershell
cd src
dotnet publish -c Release
# Output: src\bin\Release\net8.0-windows\win-x64\publish\BadgeCheckIn.exe
```

Single self-contained exe; copy it anywhere (a thumb drive works). Data is
created under `%LOCALAPPDATA%\BadgeCheckIn` on first run, never beside the exe.

## Theming

The binary carries no organization branding. At startup the app loads
`theme.json` from `%LOCALAPPDATA%\BadgeCheckIn`; if the file is absent or
invalid it runs with a neutral default ("Event Check-In", plain palette).

Schema (see `themes/theme.example.json`):

```json
{
  "appTitle": "Event Check-In",
  "primary": "#3F3F46",
  "accent": "#E0E0E0",
  "background": "#FFFFFF",
  "foreground": "#1A1A1A",
  "logoPath": "logo.png"
}
```

- `primary` colors the header, buttons, and status bar; header text flips
  black/white automatically for contrast.
- `accent` colors the big headcount number.
- `logoPath` is optional and must be a relative path resolving to a file
  inside the directory containing `theme.json` — nowhere else. Absolute and
  UNC paths are rejected outright: probing an attacker-supplied network path
  (`\\host\share\logo.png`) would make Windows reach out over SMB and can
  leak NetNTLM credentials. A missing, corrupt, or unreadable logo (or a
  malformed theme file) never stops the kiosk; it degrades to unbranded.
- `themes/local/` is gitignored: organization themes and logos stay on the
  machine and never enter the repo.

## Use

1. Plug in the reader, run the exe. Status bar shows "Reader ready".
2. **New Event…** → name it (e.g. `All-Hands BBQ 2026-08-21`).
3. People tap as they come in. Unknown badge → one-time name prompt (enrolled
   forever after). Duplicate taps at the same event are refused, not doubled.
4. **Export CSV** → `attendance_<event>_<stamp>.csv` with name, event,
   timestamp, and a TOTAL HEADCOUNT row. Attach to the purchase file.
5. Visitors without badges: paper sign-in line.

## Verification checklist (run before calling any of this real)

- [x] `dotnet build -c Release` compiles clean (verified 2026-08-19 for the
      WPF conversion, Linux cross-target, 0 warnings / 0 errors). Re-confirm
      `dotnet publish` once on Windows.
- [ ] Reader enumerates: status bar names the reader within ~2 s of plug-in.
- [ ] A badge taps → UID APDU returns SW 90 00 (log a debug read).
- [ ] **UID stability:** the SAME card tapped 5 times across reader re-plugs
      and app restarts always resolves to the same person. (DESFire *can* be
      configured for random UIDs; if your organization's cards are, enrollment
      will not stick, taps prompt for a name every time, and this design needs
      a fallback — read the vendor credential file or fall back to name-only
      check-in. Test this FIRST; it's the one assumption that kills the app.)
- [ ] TWO different cards resolve to two different people (no hash collision /
      truncated-UID surprise).
- [ ] Enrollment survives app restart (SQLite file persists).
- [ ] Duplicate tap at same event refused; same person at a NEW event records.
- [ ] Export row count == number of distinct people who tapped; TOTAL matches.
- [ ] Machine with no network: everything above still works (local-only claim).
- [ ] Theme applies: with a `theme.json` in place, title/colors/logo change;
      with it removed, the neutral default returns.

## License

Apache-2.0 — see `LICENSE` and `NOTICE`. Copyright 2026 Daniel Miller.
