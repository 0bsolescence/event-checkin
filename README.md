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

## Setup menu

**Setup…** on the toolbar opens the operator menu — everything done *between*
events, kept off the surface that gets touched *during* one:

- **Import theme…** — pick a `.json`; it is validated, copied to the data
  directory, and applied **immediately, without a restart**. The app then offers
  to import its logo.
- **Import logo image…** — pick a `.png`/`.jpg`; it is stored under the name the
  current theme references, or `logo.png` when it names nothing usable.
- **Reset theme to neutral** — behind a confirmation, deletes the theme and logo
  files and repaints neutral immediately.
- **Import Roster…** — as documented below.
- **Show badge id on next tap** — the one-shot diagnostic, described below.
- **Delete Event…** — the destructive one, deliberately at the bottom.

This replaces copying files into `%LOCALAPPDATA%\BadgeCheckIn` by hand, which
was easy to get wrong and needed a restart to take effect. The same five actions
sit behind Android's **⋮ Setup** menu; the two twins now mirror each other.

Nothing malformed can take the kiosk down. A file that is not a theme, one over
64 KB, an image over 4 MB or larger than 4000 × 4000 pixels, or an unreadable
file is refused with a message and **nothing on disk changes** — the theme is
validated before it is written, so what is stored is always loadable.

## Theming

The binary carries no organization branding. The app loads `theme.json` from
`%LOCALAPPDATA%\BadgeCheckIn` at startup and again after an import; if the file
is absent or invalid it runs with a neutral default ("Event Check-In", plain
palette).

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
- `logoPath` is optional. When it is absent, names something unusable, or does
  not resolve, the app falls back to `logo.png` in the same directory — the same
  rule the Android twin has always had, which is what lets one unmodified theme
  file brand both platforms.
- `logoPath` must otherwise be a relative path resolving to a file
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
  carries a number word (`number`, `no`, `#`, `id`) wins. Failing that, a header
  containing "external" **and** a number word — Virtual Keypad's real export
  calls the card field `External_Number`. "External" on its own is not enough.
- **Never selected**, and pinned by tests on both twins: `Number` (Virtual
  Keypad's internal row id) and `Code` (**the user's keypad PIN**). Neither
  carries a credential/card/external word, so neither can be chosen — a PIN
  quietly treated as a badge number would be both wrong and a disclosure.
- **Value normalization**: a leading `letters_` tag is stripped, so
  `R_123456` reaches everything downstream as `123456`. Only the prefix goes;
  what the remaining digits mean is the open question below, not something to
  guess at here.
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

## Ending an event

**End Event…** on the toolbar closes an event out for the books. It is the
ordinary end-of-event action, and it is not destructive:

1. A confirmation names the event and its check-in count.
2. **The attendance CSV is written first** — `attendance_<event>_<stamp>.csv` in
   the data directory, exactly like Export CSV.
3. Only then is the event marked ended.

The export is the precondition, not a courtesy: if it fails (on Android, if the
save dialog is cancelled), **the event is not ended and nothing changes**. There
is no path that closes an event with no record off the machine.

An ended event leaves the picker and stops accepting taps. Its rows — the event,
its attendance, the people who attended — are all kept; only its selectability
changes. If ending the last active event leaves none, the app asks for a new one
exactly as it does on first launch.

Ended events are archived history in v1: there is no UI to reopen one, and
Delete Event only reaches active events, which is why ending and deleting
compose rather than overlap. Ending is what you do to a real event when it is
over; deleting is what you do to an event that should not exist.

## Deleting an event

**Setup → Delete Event…** removes the selected event and every check-in
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

## Show badge id on next tap (diagnostic)

Both apps carry a one-shot reading tool under Setup. Arm it and the **next**
badge tapped shows its raw id instead of checking anyone in:

```
Bytes: 7

Hex (as read):        045A3B2C1D0E6F
Hex (byte-reversed):  6F0E1D2C3B5A04

Decimal (as read):        1225110096514671
Decimal (byte-reversed):  31259240873810436
```

Four readings because byte order is exactly the thing no access system
documents, and a UID that looks unrelated read one way is the same number read
the other.

It exists to settle one question: does a Virtual Keypad `External_Number`
(`R_123456`) relate to the UID the reader sees? Hold a known badge's number next
to this block and you can see the answer or rule it out — Step 4 of
`THURSDAY-HANDOFF.md`. Until that verdict exists, credential mapping stays off
and every imported row falls to the name picker.

**Nothing is stored.** The reading is not hashed, written, logged or exported;
the badge is not enrolled and not checked in; the toggle disarms itself after
that single tap, and it is never persisted, so it cannot survive into a later
session and eat somebody's check-in. On Windows the text is selectable so the
number can be copied rather than mis-transcribed.

## Use

1. Plug in the reader, run the exe. Status bar shows "Reader ready".
2. First time only: **Setup… → Import theme…** for branding.
3. **New Event…** → name it (e.g. `All-Hands BBQ 2026-08-21`).
4. Optionally **Setup… → Import Roster…** so names can be picked instead of
   typed.
5. People tap as they come in. Unknown badge → pick from the roster, or type a
   name (enrolled forever after). Duplicate taps at the same event are refused,
   not doubled.
6. **Export CSV** any time → `attendance_<event>_<stamp>.csv` with name, event,
   timestamp, and a TOTAL HEADCOUNT row. Attach to the purchase file.
7. When the event is over, **End Event…** — it exports and then closes the event
   out, taking it off the picker.
8. Visitors without badges: paper sign-in line.

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
- [ ] Roster import on the REAL Virtual Keypad export: the summary dialog must
      say **Name column: Name** and **Credential column: External_Number**. If
      it names `Code`, stop — that is the keypad PIN, and no build that does
      that goes near a real roster.
- [ ] Show badge id on next tap: arm it, tap a badge. The reading appears, the
      person is **not** checked in and the headcount does not move; the next tap
      after that checks in normally. Compare the four readings against that
      badge's `External_Number` and send me both.
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
- [ ] **Setup… → Import theme…** with `themes/local/theme.bft.json`: the title,
      header, headcount, buttons, list and status bar repaint **immediately, no
      restart**. Say yes to the logo prompt, pick `bft-logo-icon.png`, and the
      logo appears in the header. Close and reopen: branding persists. This is
      the path that replaced the manual copy — if it does not work, say so
      before anything else.
- [ ] Malformed input is refused, not fatal: rename a `.txt` to `.json` and
      import it; import a non-image file as a logo. Each shows a message and
      leaves the previous branding intact.
- [ ] Awkward `logoPath`: import a theme whose `logoPath` carries a directory
      (`"images/brand.jpg"`), then import a logo for it. **The logo must
      appear** — it is stored as `logo.png` and picked up by the fallback. This
      one is compile-verified only; the path logic cannot be executed on Linux.
- [ ] **Setup… → Reset theme to neutral** → confirmation → neutral look, logo
      gone, immediately. Do this before any screenshot that leaves the agency.
- [ ] End Event…: the confirmation names the event and its count; **No** changes
      nothing; **Yes** writes the CSV (the dialog shows the path — open it and
      check the rows) and then the event leaves the picker. The people who
      attended still tap through at the next event. Ending the last active event
      prompts for a new one.
- [ ] Upgrade path: run this build against an EXISTING `checkin.db` created by
      the previous version. Every event still appears in the picker (the
      `ended_at` column is added by ALTER on first open, and existing events
      stay active), and their attendance is intact.
- [ ] Delete Event: the confirmation names the event and its check-in count;
      **No** leaves everything in place; **Yes** removes the event and its
      attendance only — another event's list is unchanged, the people who
      attended stay enrolled (their next tap does not ask for a name), and the
      newest remaining event is selected. Deleting the last event prompts for a
      new one, and cancelling that prompt leaves an empty combo that refuses
      taps with "Create/select an event first." rather than crashing.

## License

Apache-2.0 — see `LICENSE` and `NOTICE`. Copyright 2026 Daniel Miller.
