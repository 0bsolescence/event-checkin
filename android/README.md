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
- **Events** are created and selected in-app; the same person taps fresh at
  each new event.
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
holds four actions: Import theme, Import logo image, Reset theme to neutral, and
Import roster (CSV). Everything arrives through the Storage Access Framework, so
none of it costs a permission and no file manager needs one either.

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
  logo > 4 MB), a file that is not JSON, or one that is not a decodable image is
  refused with a status message and **nothing on disk changes** — the theme is
  validated before it is written, so what is stored is always loadable.
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
- [ ] Setup → Import roster on a real access-system export: the summary dialog
      names the columns it found and the counts add up. A file with no name
      column is refused with a message.
- [ ] After import, an unknown badge shows the name picker; typing narrows it;
      picking a name checks that person in; that name is not offered again for
      a second badge. "Type a name" still reaches the free-text prompt.
- [ ] Upgrading over the previously installed build (not a fresh install) keeps
      existing events, people and attendance — the v1 → v2 migration adds the
      roster table without touching them.

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
    │   │   └── MainActivity.kt      # single activity, NFC reader mode, SAF
    │   └── res/                     # layout + neutral defaults only
    └── src/test/                    # plain JUnit over the pure logic
```

Plain `SQLiteOpenHelper` over Room by choice: zero annotation processing, zero
extra dependencies, and the whole storage layer stays one reviewable file that
matches the Windows `Db.cs` line for line.
