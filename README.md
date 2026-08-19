# Badge Check-In

A small Windows 11 kiosk app for event attendance at a public agency: tap your
agency badge on a USB desk reader, your name and time land on the roster, and
the headcount exports to CSV for the audit file. Built for catered events where
auditors need a defensible attendee count (doors propped open, outdoor events —
the wall readers can't see you, this can).

**Status: COMPILES CLEAN, UNVERIFIED against hardware.** Built 2026-08-19 on a
Linux node via cross-targeting: `dotnet build` and `dotnet publish` both
succeed with 0 warnings / 0 errors, producing a self-contained
`BadgeCheckIn.exe` (~157 MB — the whole .NET runtime rides inside; normal for
self-contained WinForms). It has never RUN: no Windows box, no reader, no card
has touched it. The checklist below is the postcondition list — the app is not
real until those boxes check on Windows 11 with a CSM-2P.

## Privacy contract (deliberate, load-bearing)

- The raw badge UID is **never stored** — taps are matched via a salted
  SHA-256 hash (per-install random salt). Honest limit, per cross-vendor
  audit 2026-08-19: the salt lives in the same DB, and short UID spaces
  (4-byte) are brute-forceable offline by anyone who obtains the file. The
  hash prevents casual disclosure, not determined recovery — so treat
  `checkin.db` as sensitive and keep it on agency equipment.
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
- Agency cards: DMP CSM-2P = MIFARE DESFire EV2. The app reads the ISO 14443
  UID, not the DMP credential file — hence enroll-on-first-tap instead of a
  roster import.

## Build (on Windows, .NET 8 SDK installed)

```powershell
cd src
dotnet publish -c Release
# Output: src\bin\Release\net8.0-windows\win-x64\publish\BadgeCheckIn.exe
```

Single self-contained exe; copy it anywhere (a thumb drive works). Data is
created under `%LOCALAPPDATA%\BadgeCheckIn` on first run, never beside the exe.

## Use

1. Plug in the reader, run the exe. Status bar shows "Reader ready".
2. **New Event…** → name it (e.g. `All-Hands BBQ 2026-08-21`).
3. People tap as they come in. Unknown badge → one-time name prompt (enrolled
   forever after). Duplicate taps at the same event are refused, not doubled.
4. **Export CSV** → `attendance_<event>_<stamp>.csv` with name, event,
   timestamp, and a TOTAL HEADCOUNT row. Attach to the purchase file.
5. Visitors without badges: paper sign-in line, same as the memo says.

## Verification checklist (run before calling any of this real)

- [x] `dotnet publish` compiles clean (verified 2026-08-19, Linux cross-target,
      0 warnings; exe produced). Re-confirm once on Windows if built there.
- [ ] Reader enumerates: status bar names the OMNIKEY within ~2 s of plug-in.
- [ ] A CSM-2P card taps → UID APDU returns SW 90 00 (log a debug read).
- [ ] **UID stability:** the SAME card tapped 5 times across reader re-plugs
      and app restarts always resolves to the same person. (DESFire *can* be
      configured for random UIDs; if BFT's cards are, enrollment will not
      stick, taps prompt for a name every time, and this design needs the
      fallback — read the DMP credential file or fall back to name-only
      check-in. Test this FIRST; it's the one assumption that kills the app.)
- [ ] TWO different cards resolve to two different people (no hash collision /
      truncated-UID surprise).
- [ ] Enrollment survives app restart (SQLite file persists).
- [ ] Duplicate tap at same event refused; same person at a NEW event records.
- [ ] Export row count == number of distinct people who tapped; TOTAL matches.
- [ ] Machine with no network: everything above still works (local-only claim).

## Open-sourcing note

Written as a gift to a public agency; if BFT (or anyone) wants it published,
it needs: a license choice (MIT or Apache-2.0 suggested), an agency sign-off
that the repo carries no agency data (the .db and .csv files are gitignored),
and a README pass to genericize the DMP/CSM-2P specifics into "13.56 MHz ISO
14443 badges". `.gitignore` already covers `*.db` and `*.csv`.
