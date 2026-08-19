# Android scaffold report — Event Check-In twin

- **Date:** 2026-08-19
- **Raven:** android scaffold (execution leg)
- **Commit:** `40ed6d9` on `main` (not pushed, per brief) — 15 files, 734 insertions, all under `android/`
- **Status: SCAFFOLD, UNCOMPILED.** This node has no Android SDK and no Gradle; nothing here has been compiled, installed, or tapped. The README carries the stamp, build steps, and the full verification checklist. No claim of "builds" is made anywhere.

## What was built

`~/Projects/badge-checkin/android/` — unbranded Android twin ("Event Check-In") of the Windows kiosk. Kotlin, single activity, minSdk 26 / target 35, AGP 8.7.3 + Kotlin 2.1.0 via version catalog (versions pinned from documentation, flagged as unresolved in the catalog itself).

### Semantics mirrored from `src/Db.cs` / `src/MainForm.cs` (read at HEAD d394343)

| Windows | Android |
|---|---|
| PC/SC Get-UID APDU | NFC reader mode (foreground-only), `Tag.id` = ISO 14443 anti-collision UID; NFC-A\|B, NDEF check skipped |
| Per-install 16-byte random salt in `meta` | Identical (`SecureRandom`, uppercase hex, same table) |
| `SHA256(ASCII(saltHex) ‖ uid)` uppercase hex | Byte-identical construction (`Charsets.US_ASCII`, `%02X`) |
| Schema: meta/people/events/attendance + `UNIQUE(event_id, uid_hash)` | Identical DDL, plain `SQLiteOpenHelper` (chosen over Room — lighter to review, brief's call) |
| Duplicate tap: catch extended code 2067 only | Catch `SQLiteConstraintException` only when message says `UNIQUE constraint failed`; FK/NOT-NULL still surface (Android hides extended codes — noted in comment) |
| Enroll-on-first-tap dialog; blank/cancel → "Enrollment cancelled." | Identical flow and wording; check-in re-reads the selected event after the dialog |
| CSV `Name,Event,CheckedInAt` CRLF + `TOTAL HEADCOUNT` row, formula-neutralized cells (= + - @ → leading apostrophe), never overwrite | Identical bytes; delivered via SAF `CreateDocument` (system save-as, uniquifies on collision, zero storage permission) |
| Local-only, no network code | **Manifest requests only `android.permission.NFC`; `INTERNET` absent — the absence is the privacy claim**, verifiable with `aapt dump permissions` |

Extras consistent with the contract: `allowBackup="false"` (salt+hashes stay off backups); brand surface confined to `colors.xml` (3 colors) + `app_name` — theme-file-ready, ships neutral, no agency branding; `applicationId` is the deliberate placeholder `com.example.eventcheckin` (Play rejects it, forcing the permanent-identifier decision before any submission).

### Verification checklist (in android/README.md)

Mirrors the Windows one: compile gate first, then the **UID-stability gate flagged as the assumption that kills the app** (Random-ID DESFire presents a changing 4-byte `0x08…` ID → enrollment never sticks), two-cards-two-people, restart persistence, duplicate refusal, formula-cell rendering, permission dump on the built APK, Wi-Fi-off end-to-end. Plus a cross-device note: per-install salts mean Windows and Android rosters never link — expected, not a bug.

## Incident (caught and corrected)

My first commit (`9abb5c2`, deleted) accidentally swept in **wpf-raven's staged-but-uncommitted work** (LICENSE/NOTICE, MainWindow.cs, Theme.cs, themes/, README edits) — `git add android/` stacked onto their populated index. Corrected immediately: `git reset --soft HEAD~1`, then `git commit -- android/`. Verified postcondition: `git show --stat 40ed6d9` lists exactly the 15 android files; `git status` shows the WPF work re-staged and untouched. wpf-raven should be aware their staging area was disturbed-and-restored in that window.

## Not done / open

- Not pushed; no remote touched (per brief).
- No Gradle wrapper binary shipped (cannot generate without Gradle); README says `gradle wrapper --gradle-version 8.11.1` or open in Android Studio.
- No launcher icon (system default); harmless for a scaffold, wants one before any real distribution.
- First `assembleDebug` on an SDK-bearing machine is the gate for everything.
