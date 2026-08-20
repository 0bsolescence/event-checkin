# Event Check-In (Android)

The unbranded Android twin of the Windows badge check-in kiosk one directory
up: the phone or tablet **is** the badge reader. NFC reader mode reads the ISO
14443 anti-collision UID (`Tag.id`) of whatever badge is tapped against the
device, matches it to an enrolled name via a salted hash, and keeps a
timestamped roster per event with live headcount and CSV export.

**Status: COMPILES CLEAN, runs on hardware; the theming and roster features
below are UNVERIFIED on a device.** `assembleDebug` and the JVM unit tests both
pass on l7440 (SDK 35, Gradle 8.14). The base check-in flow was tested on a real
tablet 2026-08-19. Runtime theming and roster import were added 2026-08-20 and
have only been compiled and unit-tested — nobody has imported a theme or a CSV
on the tablet yet.

## Semantics (mirrors the Windows app exactly)

- **Enroll on first tap.** Unknown badge → one-time name dialog; enrolled
  forever after. Cancel/blank → "Enrollment cancelled.", nothing stored.
- **Duplicate taps at the same event are refused**, not doubled — enforced by
  `UNIQUE(event_id, uid_hash)` in SQLite, same schema as the Windows DB.
- **Events** are created, selected, ended and deleted in-app; the same person
  taps fresh at each new event. **Ending** exports the CSV and closes the event
  for the books, keeping every row; **deleting** takes its check-ins with it.
  Both leave enrollments alone (see below), and deletion is the only removal
  either app offers.
- **CSV export**: `Name,Event,CheckedInAt` + a `TOTAL HEADCOUNT: n` row, CRLF,
  quoted cells, formula-neutralized (a leading `=` `+` `-` `@` gets an
  apostrophe so Excel renders text, not a formula). Same schema and escaping
  as the Windows export — equivalent, not byte-identical: the timestamps'
  fractional-second precision differs between .NET's `"o"` format and Java's
  `ISO_OFFSET_DATE_TIME`.
- **Export path**: Storage Access Framework save-as dialog — the user picks
  where the file lands (Drive, Downloads, USB). No storage permission exists
  or is needed, and SAF uniquifies filenames so an earlier audit snapshot is
  never overwritten.

## Setup menu (theming and roster import)

The **⋮** button at the right of the header opens Setup; long-pressing the
header title opens the same menu, for anyone who reaches for that first. It
holds six actions: Import theme, Import logo image, Reset theme to neutral,
Import roster (CSV), Show badge id on next tap, and Delete event. The imports
all arrive through the Storage Access Framework, so none of them costs a
permission and no file manager needs one either.

**Delete event** sits here rather than on the toolbar on purpose: the toolbar is
what door staff touch during an event, and a destructive action does not belong
one mis-tap from Export. The Windows twin puts it on the toolbar next to New
Event because that window has no Setup menu to hold it — same semantics, each
platform's own shape.

The document pickers filter widely (`*/*` for JSON and CSV) on purpose: file
providers hand those types over as `application/octet-stream` often enough that
a strict filter would make the very file the operator came to pick
unselectable. Images filter to `image/*`.

### Theming

Same contract as the Windows twin: branding is a file, never a build. The app
loads `theme/theme.json` from its private files directory at startup and again
after an import, and repaints immediately — no restart. Absent or invalid, it
runs neutral, and **Reset theme** deletes the theme directory to get back there.

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

- `primary` paints the header and the status line (and reaches under the system
  bars); text over it flips black/white by the same weighted-luminance rule
  `Theme.cs` uses. `accent` colors the big headcount number. `background` and
  `foreground` own the list area.
- `logoPath` must resolve to a file **inside** the theme directory; a rooted
  path or a `..` traversal is rejected before any read.
- Two deliberate divergences from Windows, both because the theme arrives over
  SAF rather than as a file copy: an imported logo is always written as
  `logo.png`, and that file is used when `logoPath` is absent or does not
  resolve; and colors accept hex only (`#RGB`, `#RRGGBB`, `#AARRGGBB`) — WPF's
  named colors have no equivalent here.
- Nothing malformed can take the kiosk down. An oversized file (theme > 64 KB,
  logo > 4 MB or over 4000 × 4000 pixels), a file that is not JSON, or one that
  is not a decodable image is refused with a status message and **nothing on
  disk changes** — the theme is validated before it is written, so what is
  stored is always loadable. The pixel ceiling is separate from the byte one on
  purpose: a well-compressed image far under 4 MB can still decode to gigabytes.
  Whatever passes is downsampled at load time, so the heap cost stays bounded
  even for a file that arrived by some path other than the importer.
- `../themes/local/` is gitignored: organization themes and logos stay on the
  machine and never enter the repo.

### Roster import

Imports a personnel list exported from the badge-access system (DMP EverOn /
Virtual Keypad) so taps resolve to names without typing at the door. Header
detection, the two resolution modes, and why the credential-to-UID transform is
deliberately disabled are documented once in `../README.md` — the rules are
shared code (`Roster.kt` and `../src/Roster.cs` are mirrors), and `Roster.kt`
carries the unit tests for them.

On this side the picker is a searchable single-choice dialog with a "Type a
name" escape hatch, shown in place of the free-text prompt whenever unclaimed
roster names exist. The credential number is never written to the database.

The real Virtual Keypad export was seen on 2026-08-20 and its card field is
`External_Number` (`R_123456`), so both twins now also accept an "external"
header carrying a number word, and strip a leading `letters_` tag from the
value. `Number` (the internal row id) and `Code` (**the keypad PIN**) can never
be selected — pinned by tests on both sides, because a PIN quietly imported as a
badge number would be both wrong and a disclosure.

### Show badge id on next tap

A one-shot diagnostic: arm it in Setup and the **next** badge tapped shows its
raw id — hex and decimal, each read both ways round, plus the byte length —
instead of checking anyone in. Byte order is the reason there are four readings:
which end an access system starts from is exactly what nobody documents.

It exists to settle whether an `External_Number` relates to the UID the reader
sees (Step 4 of `../THURSDAY-HANDOFF.md`). Until that verdict exists, credential
mapping stays off.

**Nothing is stored.** The reading is not hashed, written, logged or exported,
the badge is not enrolled or checked in, and the toggle disarms itself after one
tap. It is deliberately not saved in instance state either: a diagnostic that
survived a recreation the operator did not expect would eat somebody's check-in
later. The text is selectable so the number can be copied rather than
mis-transcribed.

### Ending an event

**End Event…** on the toolbar closes an event out for the books, and it is not
destructive. A confirmation names the event and its check-in count, then the
attendance CSV is saved through the usual SAF picker — **and the event is ended
only once that file is actually written**. Cancelling the save, or a write that
fails, aborts the whole thing and changes nothing: there is no path that closes
an event with no record off the tablet.

An ended event leaves the picker and stops accepting taps; its event row,
attendance and people are all kept. If ending the last active event leaves none,
the app asks for a new one exactly as on first launch. Ended events are archived
history in v1 — no UI reopens one, and Delete event only reaches active events.

This is where ending and deleting divide: ending is what happens to a real event
when it is over, deleting is what happens to an event that should not exist.

The toolbar moved to two rows for this — picker on top, `New Event…`,
`End Event…` and `Export CSV` sharing the second by equal weight. Three
touch-sized buttons beside the spinner on one row would squeeze it to nothing on
a phone and clip the last button, the same failure cross-vendor review caught on
the Windows toolbar.

### Deleting an event

Setup → **Delete event** removes the selected event and every check-in recorded
against it, in one transaction, behind a confirmation naming the event and
counting the records about to go. Cancelling — the button, back, or a tap
outside — changes nothing. Enrolled people and imported roster names are left
alone: a person exists independently of any event, and un-enrolling them would
make their next tap at a different event ask for a name again. Afterwards the
newest remaining event is selected; if that was the last one, the app asks for a
new event exactly as it does on first launch.

The tension is deliberate: **attendance is append-only from the UI**, a privacy
and audit posture, so neither twin offers "remove one check-in" — a single
record cannot be quietly edited away. Deleting an event is event-level
housekeeping for a test event or one created by mistake, and it takes the whole
record with it, visibly. Export first if the record matters. Deletion itself
needed no schema change — it is DML — but *ending* an event did: `events` gained
a nullable `ended_at`, so the database went to **v3**. The upgrade is a single
additive `ALTER TABLE`; existing events come back with `ended_at` NULL, which
reads as active, because installing an APK must not end anybody's event.

## Privacy contract (deliberate, load-bearing)

- The raw badge UID is **never stored** — only a salted SHA-256 hash
  (per-install random 16-byte salt, created on first launch, kept in the same
  DB). Same honest limit as the Windows build: short 4-byte UID spaces are
  brute-forceable offline by anyone who obtains the DB file, so the hash
  prevents casual disclosure, not determined recovery. Treat the app's data
  as sensitive; `allowBackup="false"` reduces its exposure by opting out of
  standard Android backup — it does not control every OEM device-to-device
  transfer path, so it lowers the surface rather than eliminating it.
- The salt is **per-install**: an Android install and a Windows install (or
  two Android devices) never share enrollments and cannot link records to
  each other. Deliberate — each device is its own island.
- **The manifest requests exactly one permission: `android.permission.NFC`.**
  There is no `INTERNET` permission, so the app is *incapable* of a network
  call — the absence is the privacy claim, and anyone can verify it on the
  built APK: `aapt dump permissions app-debug.apk`.
- **A roster import stores names only.** The credential number that came with a
  row is used to try to derive badge bytes and is then dropped — it is never
  written to the database, so importing a personnel list does not put access
  credentials on the tablet.
- No agency branding anywhere. `res/values/colors.xml` and `strings.xml` ship
  neutral defaults only; live branding comes from a theme file the operator
  imports at runtime, which lives in the app's private storage and never in the
  repo or the APK.

## Build (requires an Android SDK)

Easiest: **Android Studio → Open → this `android/` directory**, let it sync,
run on a device. Command line:

```bash
# 1. Install an Android SDK and accept licenses (or let Studio do it).
# 2. Point the build at it:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
# 3. Generate the wrapper (this repo deliberately ships no wrapper binary):
gradle wrapper --gradle-version 8.11.1
# 4. Build and test:
./gradlew assembleDebug testDebugUnitTest
# APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug   # with a device attached, USB debugging on
```

`testDebugUnitTest` runs plain JVM tests over the pure logic — CSV parsing,
header detection, the color rules, and the assertion that credential mapping
stays disabled. No Robolectric and no device needed, because the tested code
holds no Android types.

`DbDeleteEventTest` and `DbEndEventTest` are the exceptions to "pure logic
only", and they are worth the single test-only dependency
(`org.xerial:sqlite-jdbc`, never packaged): they run `Db`'s own schema and its
DELETE / UPDATE / SELECT constants against a real SQLite engine. Deletion
semantics (scoping, statement order under foreign keys, people and roster
surviving, rollback on a mid-way failure), ending semantics (an ended event
leaves the picker while its rows stay), and the **v2 → v3 migration against a
table built in the old shape** are all pinned by execution rather than by
reading the code. They do not exercise Android's `SQLiteDatabase` wrapper; that
part stays on the manual checklist below.

If a pinned version in `gradle/libs.versions.toml` fails to resolve, bump to
the nearest available — nothing depends on those exact numbers.

**Before any store submission:** the `applicationId` is the deliberate
placeholder `com.example.eventcheckin`, which Google Play rejects. The
permanent identifier must be chosen first — it can never change after first
submission.

## Verification checklist (run before calling any of this real)

- [ ] `./gradlew assembleDebug` compiles clean — **the first gate; nothing
      below matters until this passes.**
- [ ] Installs and launches on a physical NFC device running Android 8.0+
      (API 26+). Emulators have no NFC; a real device is mandatory.
- [ ] NFC state handling: airplane-mode/NFC-off shows the "NFC is turned off"
      status; re-enabling and returning to the app shows "Reader ready".
- [ ] **UID stability — test this FIRST, it is the one assumption that kills
      the app:** the SAME card tapped 5 times across app restarts and a device
      reboot always resolves to the same person. DESFire cards *can* be
      configured for Random ID; such a card presents a 4-byte ID starting
      `0x08` that changes on every tap, enrollment will never stick, and the
      name dialog will reappear every time. If that happens this design needs
      the fallback (read the credential file, or name-only check-in) — same
      caveat as the Windows checklist.
- [ ] TWO different cards resolve to two different people (no collision /
      truncated-UID surprise).
- [ ] Cross-device sanity: the same card on the Windows app and this app
      enrolls **independently** (different salts). That is expected behavior,
      not a bug — confirm nobody assumes rosters transfer.
- [ ] Enrollment survives app restart and device reboot (DB persists).
- [ ] Duplicate tap at the same event → "already checked in", headcount
      unchanged; the same person at a NEW event records normally.
- [ ] Enroll dialog open + a second tap / event switch mid-dialog does nothing
      surprising (the check-in re-reads the selected event on confirm).
- [ ] Export: row count == distinct people who tapped; TOTAL matches; a person
      enrolled as `=2+5` renders in Excel/Sheets as text, not 7.
- [ ] `aapt dump permissions` on the built APK lists `android.permission.NFC`
      and nothing else (the local-only claim, verified on the artifact).
- [ ] Wi-Fi off, no SIM: everything above still works end to end.
- [ ] Setup → Import theme: the header title, colors and logo change without a
      restart, and survive force-close and relaunch. Reset theme returns the
      neutral look and removes the logo.
- [ ] Malformed input is refused, not fatal: a `.txt` that is not JSON, a
      non-image "logo", and a huge file each show a status message with the
      previous theme still in place.
- [ ] Setup → Import roster on the REAL Virtual Keypad export: the summary
      dialog must say **Name column: Name** and **Credential column:
      External_Number**. If it names `Code`, stop and tell me — that is the
      keypad PIN.
- [ ] Setup → Show badge id on next tap: the status line says it is armed, the
      next tap shows the reading, the person is **not** checked in and the
      headcount does not move. The tap after that checks in normally. Send me
      the block together with that badge's `External_Number`.
- [ ] Setup → Import roster on a real access-system export: the summary dialog
      names the columns it found and the counts add up. A file with no name
      column is refused with a message.
- [ ] After import, an unknown badge shows the name picker; typing narrows it;
      picking a name checks that person in; that name is not offered again for
      a second badge. "Type a name" still reaches the free-text prompt.
- [ ] **End Event…**: the confirmation names the event and its check-in count;
      confirming opens the save dialog. **Cancel the save** — the status says
      the event was NOT ended, and it is still in the picker with its
      attendance. Do it again and save the file: the CSV opens cleanly, the
      event leaves the picker, and its attendees still tap through without a
      name prompt at the next event. Ending the last active event prompts for a
      new one.
- [ ] Setup → Delete event: the confirmation names the event and its check-in
      count; cancelling (button, back, tap outside) changes nothing; confirming
      removes that event and its attendance only. Another event's list is
      unchanged, everyone who attended stays enrolled (their next tap does not
      ask for a name), and the newest remaining event is selected. Deleting the
      last event prompts for a new one; cancelling that prompt leaves an empty
      spinner that refuses taps with "Create/select an event first." rather
      than crashing.
- [ ] Upgrading over the previously installed build (not a fresh install) keeps
      existing events, people and attendance — the v1 → v2 migration adds the
      roster table and v2 → v3 adds `events.ended_at` without touching them.
      **Every existing event must still be in the picker after the upgrade**; if
      one is missing, the migration defaulted it to ended and that is a stop.

## Files

```
android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml        # AGP 8.x + Kotlin 2.x, version catalog
└── app/
    ├── build.gradle.kts             # minSdk 26, no flavors, minify off
    ├── src/main/
    │   ├── AndroidManifest.xml      # NFC only; INTERNET deliberately absent
    │   ├── java/com/example/eventcheckin/
    │   │   ├── Db.kt                # SQLiteOpenHelper twin of ../src/Db.cs
    │   │   ├── Theme.kt             # runtime branding, twin of ../src/Theme.cs
    │   │   ├── Roster.kt            # pure CSV import, twin of ../src/Roster.cs
    │   │   ├── BadgeId.kt           # pure UID readings, twin of ../src/BadgeId.cs
    │   │   └── MainActivity.kt      # single activity, NFC reader mode, SAF
    │   └── res/                     # layout + neutral defaults only
    └── src/test/                    # plain JUnit over the pure logic
```

Plain `SQLiteOpenHelper` over Room by choice: zero annotation processing, zero
extra dependencies, and the whole storage layer stays one reviewable file that
matches the Windows `Db.cs` line for line.
