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
  ISO 14443 UID, not any vendor credential file. Whether that UID is derivable
  from the access system's credential number is **unverified** — which is why a
  roster import resolves names by picker rather than by pre-enrolling badges
  (see "Roster import" below).

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

## Roster import

**Import Roster…** takes a personnel list exported from the badge-access
system (DMP EverOn / Virtual Keypad) so that taps resolve to names without
anyone typing at the door. The same feature exists on the Android twin.

Accepted CSV shapes — the header is sniffed, not positional:

- **Name**: a column named exactly `Name` wins; then `Full Name`,
  `Display Name`, `Employee Name`, `Person Name`; then a `First Name` +
  `Last Name` pair, joined with a space; then any header containing "name".
- **Credential**: any header containing "credential" or "card"; one that also
  carries a number word (`number`, `no`, `#`, `id`) wins.
- Quoted cells, embedded commas and newlines, CRLF or LF, and a UTF-8 BOM are
  all handled. Rows with no name, and repeats of a name already imported, are
  counted as skipped rather than guessed at. A file with no name column is
  refused with a message; nothing is stored.

There are two resolution modes, and today only the second one runs:

- **MAPPED** — if a credential number can be turned into badge UID bytes, the
  person is enrolled at import time and their first tap simply checks them in.
  **This is off.** `Roster.CredentialToUidBytes` is a marked placeholder that
  returns null, because whether the credential number relates to the NFC UID at
  all is unverified (Step 4 of `THURSDAY-HANDOFF.md`). Guessing a transform
  would silently enroll people under each other's names.
- **PICKER** — the working path, and correct either way. Imported rows are kept
  as **names only**; the credential number is dropped and never written to the
  database. An unknown badge then shows a searchable list of unclaimed roster
  names plus a "Type a Name" escape hatch. Choosing a name binds it to that
  badge exactly like typing it would, and removes it from the unclaimed pool.

## Deleting an event

**Delete Event…** on the toolbar removes the selected event and every check-in
recorded against it, in one transaction, behind a confirmation that names the
event and counts the records about to go. Cancelling changes nothing. Enrolled
people and imported roster names are left alone — a person exists independently
of any event, and un-enrolling them would make their next tap at a different
event ask for a name again. Afterwards the newest remaining event is selected;
if that was the last one, the app asks for a new event exactly as it does on
first launch.

The tension is deliberate and worth naming: **attendance is append-only from the
UI**, a privacy and audit posture, so there is no "remove one check-in" anywhere
in either app — a single record cannot be quietly edited away. Deleting an event
is event-level housekeeping for a test event or one created by mistake, and it
takes the whole record with it, visibly. Export the CSV first if the record
matters; nothing is recoverable afterwards.

The Android twin does the same thing from its **⋮ Setup** menu.

## Use

1. Plug in the reader, run the exe. Status bar shows "Reader ready".
2. **New Event…** → name it (e.g. `All-Hands BBQ 2026-08-21`).
3. Optionally **Import Roster…** so names can be picked instead of typed.
4. People tap as they come in. Unknown badge → pick from the roster, or type a
   name (enrolled forever after). Duplicate taps at the same event are refused,
   not doubled.
5. **Export CSV** → `attendance_<event>_<stamp>.csv` with name, event,
   timestamp, and a TOTAL HEADCOUNT row. Attach to the purchase file.
6. Visitors without badges: paper sign-in line.

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
- [ ] Roster import: a real export from the access system is accepted, the
      dialog names the columns it found, and the count adds up. A file with no
      name column, an empty file, and a 10 MB file are each refused with a
      message and no crash.
- [ ] After import, an unknown badge shows the name picker; searching narrows
      it; picking a name checks that person in and removes them from the pool;
      the same name is not offered for a second badge.
- [ ] "Type a Name" still works with a roster loaded, and is the only path when
      no roster has been imported.
- [ ] Toolbar fits: at the 960px default and dragged down to the 640px minimum,
      the picker and all four buttons stay visible — they wrap onto a second
      row rather than clipping. (Cross-vendor review caught the clip; the
      wrapping fix is compiled but not yet seen rendered on Windows.)
- [ ] Delete Event: the confirmation names the event and its check-in count;
      **No** leaves everything in place; **Yes** removes the event and its
      attendance only — another event's list is unchanged, the people who
      attended stay enrolled (their next tap does not ask for a name), and the
      newest remaining event is selected. Deleting the last event prompts for a
      new one, and cancelling that prompt leaves an empty combo that refuses
      taps with "Create/select an event first." rather than crashing.

## License

Apache-2.0 — see `LICENSE` and `NOTICE`. Copyright 2026 Daniel Miller.
