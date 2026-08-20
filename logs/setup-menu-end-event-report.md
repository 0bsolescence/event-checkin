# Windows Setup menu + End Event, both twins — build report

> Written 2026-08-20 (afternoon charter extension, same day as the event-deletion
> work in `delete-event-report.md`). On `main` at `ce60769`. Both builds and all
> unit tests are green from a clean clone of the pushed tree.
> **Nothing below has been exercised on the tablet or on Windows.** The two
> checklists at the end are the postcondition list, and the Windows one carries
> more weight than usual because the WPF layer cannot be executed on these nodes
> at all.

## Feature A — Windows Setup menu, with theme import that applies at runtime

**The manual branding path is dead.** Copying a file into `%LOCALAPPDATA%` and
renaming it to `theme.json` was easy to get wrong — it was, by the principal,
who followed the instructions and still had an unbranded kiosk — and it needed a
restart to take effect.

**Setup…** on the toolbar now opens a menu with the same five actions Android
keeps behind **⋮ Setup**: Import theme…, Import logo image…, Reset theme to
neutral, Import Roster…, and Delete Event… at the bottom behind a separator. It
is a `ContextMenu` hung off the button and opened under it, at the same font
size as the rest of the window, so it works under a finger.

- **Import theme…** validates BEFORE it writes. A file that does not parse as a
  `Theme`, one over 64 KB, or one that cannot be read is refused with a message
  and **nothing on disk changes** — so what sits in the data directory is always
  loadable. It then offers to import the logo immediately.
- **Import logo image…** decodes the header first (`BitmapFrame` with
  `DelayCreation`), which proves the file is an image and that the kiosk can
  draw it, before a byte is written. Ceilings match Android exactly: 4 MB,
  4000 × 4000 px, 16 MP. The header draws it 56px tall, so the load path also
  caps `DecodePixelHeight` — a small file can still decode to gigabytes.
- **Reset theme to neutral** deletes `theme.json`, the referenced logo and
  `logo.png` behind a confirmation, then repaints neutral immediately.

**Runtime application** is the point of the feature. The brushes moved out of the
constructor into `ApplyTheme()`, which the constructor also calls, so there is
one description of what "themed" means and an import repaints title, header,
headcount, toolbar buttons, list and status bar with **no restart** — matching
the Android twin's `applyTheme`. Logos load through a frozen `BitmapImage` with
`CacheOption.OnLoad`, so the file is never left locked: a locked `logo.png`
could not be replaced by the next import or deleted by Reset.

**A divergence removed.** `Theme` now falls back to `logo.png` when `logoPath` is
absent or does not resolve — the rule Android has always had. One unmodified
theme file now brands both platforms, which is what `themes/local/INSTALL.md`
had been apologising for.

## Feature B — End Event, both twins

Ending closes an event out for the books, and it is deliberately **not**
deletion. The two compose: ending is what happens to a real event when it is
over; deleting is what happens to an event that should not exist.

The flow is identical on both platforms:

1. A confirmation names the event and its check-in count.
2. **The attendance CSV is written first** — Windows to the data directory,
   exactly like Export CSV, with the path shown; Android through the existing
   SAF save dialog.
3. Only then is `ended_at` set.

**The export is the precondition, not a courtesy.** If the Windows write throws,
or the Android save is cancelled or fails, the event is **not** ended and
nothing changes — the status says so in those words. There is no path that
closes an event with no record off the device.

An ended event leaves the picker (`ended_at IS NULL` filters both `ListEvents`
implementations) and stops accepting taps. Its event row, attendance and people
are all kept. Ending the last active event falls into the same empty state as
deleting it, so the app asks for a new event. Ended events are archived history
in v1: nothing reopens one, and Delete Event only reaches what is in the picker.

On Android the end state lives in its own pending fields — never `pendingCsv`,
so a plain export can never end anything — and is saved **all-or-nothing** in
instance state, because the activity can be recreated while the picker is open.
A half-restored end would either write a CSV and end nothing or end an event
whose CSV never reached the picker; losing the state entirely ends nothing,
which is the safe way to fail.

### Schema

`events` gains a nullable `ended_at`. Existing rows get NULL, which reads as
active: **installing an update must not end anybody's event.**

- **Android** — database version 2 → 3, additive `ALTER TABLE` in `onUpgrade`.
- **Windows** — this database has never carried a version, and
  `CREATE TABLE IF NOT EXISTS` leaves an installed table untouched, so the
  column would never have appeared on an existing `checkin.db`. The `ALTER` is
  guarded by a `PRAGMA table_info` check instead.

### One judgment call worth flagging

The brief's toolbar list (picker + New Event + Export CSV + Setup) was written
about moving Import Roster and predates Feature B's own button; it also did not
mention Delete Event, which was already on the toolbar from this morning. I
resolved it by **role**, and the twins now mirror each other exactly:

- **Toolbar** — what gets touched *during* an event: picker, New Event…,
  End Event…, Export CSV, and Setup… as the door to the rest.
- **Setup menu** — what happens *between* events, including the destructive
  Delete Event…, which moved off the Windows toolbar to match Android.

Say the word if the intent was the literal four and Delete/End should sit
elsewhere; it is a two-line change on each side.

## Commits

| sha | what |
|---|---|
| `8e1a914` | Windows: Setup menu with runtime theme import, and End Event |
| `5995add` | Android: End Event twin — export first, then close the event out |
| `e03b647` | Fold codex P2: a `logoPath` with a directory collapses to `logo.png` |
| `ce60769` | Theme import: state the verified BOM behavior instead of a no-op TrimStart |

Pushed to `0bsolescence/event-checkin` `main`; local HEAD and `origin/main` both
at `ce60769`, working tree clean apart from the pre-existing untracked `dist/`.

## Builds and verification

| what | result |
|---|---|
| `gradle clean assembleDebug testDebugUnitTest` | BUILD SUCCESSFUL, no compiler warnings |
| unit tests | **31 tests, 0 failures, 0 errors** (5 new in `DbEndEventTest`) |
| APK sha256 | `f9f9210513fd28b876e8e82ac5705840cfb224b3de274694dd645cbf0298ca8b` |
| `aapt2 dump permissions` | `android.permission.NFC` only (plus the pre-existing AndroidX self-scoped receiver permission) |
| `dotnet build -c Release` | 0 warnings, 0 errors |
| C# storage-layer harness (executed) | **28/28 checks pass** |

All of it re-run against a **fresh clone of pushed `ce60769`**, with the same
APK sha256 byte for byte.

### What the harness now proves about Windows

The throwaway `net8.0` console harness that compiles the real `Db.cs` on Linux
grew eleven checks: an event ends, leaves `ListEvents`, and keeps its
attendance, its people and its exportability; an active event is unaffected; and
— the one that matters for anyone with an installed build — **a legacy database
whose `events` table predates `ended_at` is opened, upgraded by the
PRAGMA-guarded `ALTER`, and still lists its event as ACTIVE**, with a second
open confirming the upgrade is a no-op the next time.

`DbEndEventTest` pins the same claims on the Android side against a real SQLite
engine, including the v2 → v3 migration against a table built in the **old**
shape — the claim an upgrade actually rests on, which a fresh-install schema
would never exercise.

### What is NOT verified by anything but a compiler

`Theme.cs` and the whole WPF layer pull in `System.Windows.Media`, so they
cannot run on these nodes. The Setup menu's rendering and placement, the runtime
repaint, the logo decode ceilings, and `NormalizedLogoFileName`'s path handling
are **compile-time claims only**. That is what the Windows checklist is for.

## Cross-vendor audit (`codex review`, OpenAI lineage)

Run against `461b880..5995add`, then twice more after changes. Two findings,
handled differently, and the difference is the point.

**P2 — a `logoPath` with a directory imported a logo that never appeared. REAL,
FIXED in `e03b647`.** `NormalizedLogoFileName` stripped `images/brand.jpg` to
its basename, so the importer wrote `DataDir\brand.jpg` while the theme still
pointed at `images\brand.jpg` and the fallback still looked for `logo.png`.
Neither resolves: the import would have reported success and shown no logo —
precisely the silent-success failure class this project keeps ruling against,
and in the feature whose entire reason for existing is that branding silently
did not apply. Any directory component now collapses to `logo.png`, which is
where the fallback looks.

**P2 — "a BOM-bearing theme validates then loads neutral". DOES NOT REPRODUCE,
refuted by execution.** The reasoning was sound about `JsonSerializer` (a string
carrying U+FEFF does throw) but wrong about how the string is produced:
`File.ReadAllText` detects and strips a UTF-8 BOM. Checked rather than argued —
a real BOM-bearing file on disk (`EF BB BF 7B`) comes back with `{` as its first
character, deserializes fine, and survives the importer's full round trip;
`File.WriteAllText` also writes the copy without a BOM. No behavior changed.
What did need fixing was *why* it was filed: a `TrimStart('﻿')` that could never
match implied the opposite. Removed in `ce60769`, with the verified fact in a
comment. (Android still strips the BOM — it decodes the bytes itself, a
different path.)

**Third pass**, over the same diff after both changes: *"The schema migrations,
active-event filtering, export-before-ending workflow, activity-state
restoration, and runtime theme application appear internally consistent. No
actionable correctness issue was identified in the changed code."*

codex still cannot run either build itself (no `dotnet` on its PATH); every
build and test result above is mine, run directly.

## Manual test checklist — Daniel's tablet (Android)

Install over the existing build; do **not** uninstall first.

**The upgrade, first**
- [ ] **Every event that was in the picker before the update is still there.**
      v2 → v3 adds `ended_at` as NULL; if an event is missing, the migration
      defaulted it to ended and that is a stop-everything.
- [ ] Existing people and attendance intact; a known badge still checks in
      without a name prompt.

**End Event — the abort path first, because it is the one that matters**
- [ ] Create an event, check two badges in, tap **End Event…**. The
      confirmation names the event and says **2 check-ins**.
- [ ] Confirm, then **cancel the save dialog**. Status says the event was NOT
      ended; it is still in the picker with both check-ins on screen.
- [ ] Do it again and actually save. The CSV opens cleanly and has both rows
      plus the TOTAL line.
- [ ] The event is gone from the picker, and the newest remaining event is
      selected.
- [ ] Both people who attended it still tap through at another event **without**
      a name prompt.
- [ ] End the last active event → the new-event prompt appears. Cancel it: empty
      spinner, empty list, headcount 0, and a tap says "Create/select an event
      first." rather than crashing.
- [ ] Tap a badge while the End Event confirmation is open: nothing stacks.

**Layout**
- [ ] The toolbar's two rows look right in the orientation the kiosk will
      actually sit in, and all three buttons are readable and tappable.

**Regression**
- [ ] Setup → Delete event still behaves as it did this morning.
- [ ] Export CSV (not ending) still exports and does **not** end anything.

## Manual test checklist — Windows

Everything here is unverified by execution; treat the whole list as the test.

**Setup menu and branding — the reason this exists**
- [ ] **Setup… → Import theme…** with `themes/local/theme.bft.json`. Title,
      header, headcount, buttons, list and status bar repaint **immediately, no
      restart**.
- [ ] Say yes to the logo prompt and pick `bft-logo-icon.png` — the logo appears
      in the header at a sensible size.
- [ ] Close and reopen the app: branding persists.
- [ ] **Setup… → Reset theme to neutral** → confirmation → neutral look and the
      logo gone, immediately. Do this before any screenshot that leaves the
      agency.
- [ ] Malformed input is refused, not fatal: a `.txt` renamed to `.json`, and a
      non-image file as a logo. Each shows a message and leaves the previous
      branding in place.
- [ ] Awkward `logoPath`: a theme whose `logoPath` carries a directory
      (`"images/brand.jpg"`), then import a logo for it. **The logo must
      appear** — it is stored as `logo.png` and found by the fallback. This is
      the codex P2 fix, compile-verified only.
- [ ] The toolbar fits at the 960px default and at the 640px minimum — picker
      and all four buttons visible, wrapping rather than clipping.
- [ ] Setup… opens under the button and its items are large enough to hit.

**End Event**
- [ ] The confirmation names the event and its count; **No** changes nothing.
- [ ] **Yes** writes the CSV, shows the path, and the event then leaves the
      picker. Open the file and check the rows.
- [ ] People who attended still tap through at the next event without a prompt.
- [ ] Ending the last active event prompts for a new one.

**Upgrade**
- [ ] Run this build against an **existing** `checkin.db` from the previous
      version: every event still appears in the picker and its attendance is
      intact. The `ended_at` column is added on first open by the
      PRAGMA-guarded `ALTER`.

**Regression**
- [ ] Setup… → Import Roster… still works from its new home.
- [ ] Setup… → Delete Event… still works from its new home.

## Still gating everything

Unchanged: **UID stability** (Step 1 of `THURSDAY-HANDOFF.md`). If a DMP CSM-2P
badge randomizes its UID, enrollment never sticks and none of the rest matters.
Neither of these features touches that question.
