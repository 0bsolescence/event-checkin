# External_Number + badge-id diagnostic, both twins — build report

> Written 2026-08-20. On `main` at `68c07b0`, verified from a clean clone.
> Both builds and all 42 unit tests are green, and the cross-vendor audit was
> clean on the first pass. **Nothing below has been exercised on the tablet or
> on Windows.** The two things the principal has to actually do are at the end.

## Why this exists

The real Virtual Keypad export header turned up:

```
Number,Name,Active,External_Number,Profiles,Arm/Disarm Areas,Access Areas,Code,Type
```

`Name` already resolved. The card field did not — it is called
`External_Number` and nothing in it says "credential" or "card", so imports were
falling through to name-only with the credential column reported as "none
found". And the deeper question was still open: whether an `External_Number`
(`R_123456`) relates at all to the UID the reader sees.

## 1 — External_Number as a credential column

`detectColumns` / `DetectColumns` now also accept a header containing
**"external" AND a number word**. Both halves are required: "external" on its
own is generic, and a column that is not a number cannot be a credential. An
explicitly named credential/card column still outranks it — a system that says
"card number" means it, while "external" only usually does.

**What can never be selected**, now pinned by tests on both twins:

- **`Number`** — Virtual Keypad's internal row id.
- **`Code`** — the user's **keypad PIN**.

Neither carries a credential/card/external word, so neither is a candidate. This
is the one that matters: a PIN quietly imported as a badge number would be both
wrong and a disclosure, and it would look like success while doing it. The test
imports the real header with a real-shaped row (`41,Jane Doe,Yes,R_123456,…,9182,Card`)
and asserts that neither `41` nor `9182` appears anywhere in what was imported.

**Value normalization.** A leading `letters_` tag is stripped once, at import,
so `R_123456` reaches everything downstream — the transform included — as
`123456`. Only the prefix goes. What the remaining digits *mean* is the open
question, and the code deliberately does not guess: `R_123_456` normalizes to
`123_456`, not to something clever.

**`CREDENTIAL_MAPPING_ENABLED` is still `false`**, still pinned by its own test
on both sides. Nothing here turns mapping on; it only makes the evidence
collectable.

## 2 — "Show badge id on next tap"

A one-shot diagnostic in Setup on both twins. Arm it, and the **next** badge
tapped shows its raw id instead of checking anyone in:

```
Bytes: 7

Hex (as read):        045A3B2C1D0E6F
Hex (byte-reversed):  6F0E1D2C3B5A04

Decimal (as read):        1225110096514671
Decimal (byte-reversed):  31259240873810436
```

Four readings because byte order is exactly the thing no access system
documents, and a UID that looks unrelated read one way is the same number read
the other. That display alone may answer the mapping question on the spot.

**How it is safe:**

- It runs **before anything touches the badge** — no hash, no lookup, no
  enrollment, no check-in — and works with no event selected, because the point
  is to read a UID, not to record a tap.
- It **disarms before the dialog opens**, so a second badge arriving mid-reading
  behaves completely normally.
- **Nothing is stored.** Not hashed, not written, not logged, not exported. The
  reading lives as long as the dialog and then it is gone.
- The armed flag is **never persisted** — not in Android's instance state, not
  on disk. A diagnostic that outlives the session it was armed in is one that
  eats somebody's check-in later.
- The text is **selectable/copyable** on both platforms (a read-only `TextBox`
  on Windows rather than a `MessageBox`), because this number has to leave the
  device and mis-transcribing it would answer the question wrongly.

`BadgeId.kt` / `BadgeId.cs` are pure mirrors holding the arithmetic, so it is
unit tested off-device.

**On the name:** it is `BadgeId`, not `Uid`, on both sides for one
Windows-specific reason — `UIElement.Uid` is an inherited string property, so
`Uid.Describe(...)` inside a WPF control does not compile. The build caught it;
the reason is in both files so nobody renames it back.

`THURSDAY-HANDOFF.md` Step 4 is rewritten around this: find your
`External_Number`, arm the diagnostic, tap once, send both. It also now says
plainly not to photograph or transcribe anyone else's badge — one badge, yours,
is the whole test.

## Commits

| sha | what |
|---|---|
| `4ebe972` | Windows: read External_Number as the credential, and a one-shot badge-id readout |
| `68c07b0` | Android: the External_Number and badge-id twins |

Pushed to `0bsolescence/event-checkin` `main`; local HEAD and `origin/main` both
at `68c07b0`, working tree clean apart from the pre-existing untracked `dist/`.
All four new files (`BadgeId.kt`, `BadgeId.cs`, `BadgeIdTest.kt`, and the
renamed sources) were explicitly `git add`ed and confirmed present in the pushed
tree before the report — this morning's untracked-test lesson, applied.

## Builds and verification

| what | result |
|---|---|
| `gradle clean assembleDebug testDebugUnitTest` | BUILD SUCCESSFUL, no compiler warnings |
| unit tests | **42 tests, 0 failures, 0 errors** (5 new roster, 6 new badge-id) |
| APK sha256 | `e2353c0037f61fb5cead7ea7f8297f683e4989a5925d075eed7cc425c2344260` |
| `aapt2 dump permissions` | `android.permission.NFC` only |
| `dotnet build -c Release` | 0 warnings, 0 errors |
| C# ↔ Kotlin parity harness | **58/58 checks match** |

All re-run against a fresh clone of pushed `68c07b0`, same APK sha byte for
byte.

**The parity harness is back**, as the theming report said it should be the
moment `Roster.cs` and `Roster.kt` diverge again. It compiles the real
`Roster.cs` and `BadgeId.cs` on Linux (both are pure — no WPF) and runs every
case the Kotlin tests pin: the real header row, both never-pick columns, all
nine normalization cases, the older precedence rules that had to keep working,
all six UID readings, and that credential mapping is still disabled. 58/58.
Scratch, not committed.

**One thing worth recording about the tests themselves.** Three of the decimal
expectations in `BadgeIdTest` were wrong when I wrote them by hand — I computed
them, found the mismatch, and corrected the test against computed values before
committing. A diagnostic whose test agrees with a mistake is worse than no test,
because this number decides whether credential mapping is ever switched on.

## Cross-vendor audit (`codex review`, OpenAI lineage)

Run against `570a463..68c07b0`. **Clean on the first pass**, no findings:
*"The badge-ID diagnostic is consistently implemented across both platforms,
suppresses normal check-in processing for the diagnostic tap, and the roster
changes preserve existing precedence while adding the intended External_Number
handling. No actionable correctness defects were identified."*

As before, codex could not run either build itself (no `dotnet` on its PATH);
every build and test result above is mine, run directly.

## What the principal actually needs to do

This is the point of the whole change — two readings, side by side:

1. **Virtual Keypad → Users admin → your own user → note the
   `External_Number`** (it looks like `R_123456`; the digits are what matter).
2. **On the tablet: Setup (⋮) → Show badge id on next tap → tap your badge
   once.** Send the whole block — bytes, both hex readings, both decimal
   readings.

Then Munin rules the mapping:

- **If any of the four readings lines up with those six digits**, that is the
  transform. `credentialToUidBytes` gets the real implementation, the flag flips
  in both twins, and a roster import pre-enrolls everyone so nobody picks a name
  at the door.
- **If none does**, nothing changes: the picker path is already the shipped
  behaviour and works either way, and the placeholder stays as documentation of
  a question that was asked and answered.

**Do not photograph or transcribe anyone else's badge.** One badge, yours, is
the whole test.

## Manual test checklist — tablet

- [ ] Import the REAL Virtual Keypad export. The summary dialog must say
      **Name column: Name** and **Credential column: External_Number**. If it
      names `Code`, stop — that is the keypad PIN, and no build that does that
      goes near a real roster.
- [ ] Counts add up, and **pre-enrolled is still 0** (mapping is off).
- [ ] After the import, an unknown badge still shows the name picker.
- [ ] Setup → Show badge id on next tap: the status line says it is armed.
- [ ] The next tap shows the reading, the person is **NOT** checked in, and the
      headcount does not move.
- [ ] The tap after that checks in normally — the toggle really is one-shot.
- [ ] Arm it with no event selected: it still reads the badge rather than
      complaining about an event.
- [ ] The text can be selected and copied.

## Manual test checklist — Windows (whenever its reader arrives)

- [ ] Setup… → Import Roster… on the real export names the same two columns.
- [ ] Setup… → Show badge id on next tap arms; the next tap opens the Badge id
      window with all four readings and does not check anyone in.
- [ ] The text is selectable and copies cleanly out of the window.
- [ ] Close and tap again: normal check-in.

## Still gating everything

Unchanged: **UID stability** (Step 1). If a DMP CSM-2P badge randomizes its UID,
enrollment never sticks and the mapping question is moot — the diagnostic will
show a different id on every tap, which is itself the answer. Tap the same badge
twice while you are in there; if the two readings differ, tell me before
anything else.
