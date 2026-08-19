# Event Check-In (Android)

The unbranded Android twin of the Windows badge check-in kiosk one directory
up: the phone or tablet **is** the badge reader. NFC reader mode reads the ISO
14443 anti-collision UID (`Tag.id`) of whatever badge is tapped against the
device, matches it to an enrolled name via a salted hash, and keeps a
timestamped roster per event with live headcount and CSV export.

**Status: SCAFFOLD, UNCOMPILED.** Written 2026-08-19 on a Linux node with no
Android SDK, no Gradle, and no Android device present. It has never been
compiled, never installed, never seen a tag. Kotlin/XML were written to mirror
the verified-compiling Windows source (`../src/`), but until `assembleDebug`
runs clean on a machine with the SDK, treat every claim below as design
intent, not fact. Dependency versions in `gradle/libs.versions.toml` were
pinned from documentation, not resolved against a repository.

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
- No agency branding anywhere. The entire brand surface is
  `res/values/colors.xml` (three colors) and `app_name` in `strings.xml`; an
  agency skin is a resource overlay or product flavor, never a code edit.

## Build (requires Android SDK; none exists on the node that wrote this)

Easiest: **Android Studio → Open → this `android/` directory**, let it sync,
run on a device. Command line:

```bash
# 1. Install an Android SDK and accept licenses (or let Studio do it).
# 2. Point the build at it:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
# 3. Generate the wrapper (this repo deliberately ships no wrapper binary):
gradle wrapper --gradle-version 8.11.1
# 4. Build:
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug   # with a device attached, USB debugging on
```

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

## Files

```
android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml        # AGP 8.x + Kotlin 2.x, version catalog
└── app/
    ├── build.gradle.kts             # minSdk 26, no flavors, minify off
    └── src/main/
        ├── AndroidManifest.xml      # NFC only; INTERNET deliberately absent
        ├── java/com/example/eventcheckin/
        │   ├── Db.kt                # SQLiteOpenHelper twin of ../src/Db.cs
        │   └── MainActivity.kt      # single activity, NFC reader mode, SAF export
        └── res/                     # layout + neutral theme (the only brand surface)
```

Plain `SQLiteOpenHelper` over Room by choice: zero annotation processing, zero
extra dependencies, and the whole storage layer stays one reviewable file that
matches the Windows `Db.cs` line for line.
