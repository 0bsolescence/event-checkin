# Android theming + roster import — build report

> Written 2026-08-20. Both features ordered the same day; delivered on
> `main` at `5d58fb7`. Everything below compiles and unit-tests clean.
> **Nothing below has been exercised on the tablet.** The manual checklist at
> the end is the postcondition list.

## What shipped

### Feature 1 — Android runtime theming (ports the Windows `Theme.cs` contract)

`Theme.kt` loads `theme/theme.json` from the app's private files directory:
`appTitle` plus `primary` / `accent` / `background` / `foreground` hex colors and
an optional `logoPath`. Same neutral defaults compiled in as Windows
(`#3F3F46` / `#E0E0E0` / `#FFFFFF` / `#1A1A1A`, "Event Check-In"), same
per-field fallback when a value is missing or unparseable, same
weighted-luminance rule choosing black or white text over the primary color,
same rule that `logoPath` must resolve **inside** the theme directory (rooted
paths and `..` traversal rejected before any read).

The Android UI previously had no header at all. It now draws one the way the
Windows window does — logo and title left, `HEADCOUNT` label and count right,
on the primary color — and the theme reaches the header, the status line, the
list rows, the spinner and the buttons. The activity theme moved to
`Theme.Material3.Light.NoActionBar`: the app draws its own header, so a system
title bar would have doubled it, and a system dark mode would have repainted
background and foreground underneath the imported palette. Insets are applied so
the primary color reaches under the status and navigation bars.

**Affordance:** a `⋮` button at the right of the header opens **Setup**;
long-pressing the header title opens the same menu. Four actions: Import
theme…, Import logo image…, Reset theme to neutral, Import roster (CSV)…. All
of it via SAF, so no permission was added. Reset deletes the theme directory
and repaints neutral, behind a confirmation.

Two deliberate divergences from Windows, both forced by SAF handing over a
content URI rather than a file copy, and both documented in `android/README.md`:

1. An imported logo is always written as `logo.png`, and that file is used when
   `logoPath` is absent or does not resolve. This is what lets the unmodified
   `theme.bft.json` (which names `bft-logo-icon.png`) work on both platforms.
2. Colors accept hex only — `#RGB`, `#RRGGBB`, `#AARRGGBB`. WPF's named colors
   have no equivalent here.

Nothing malformed can take the kiosk down. Theme > 64 KB, logo > 4 MB or over
4000 × 4000 px, a file that is not JSON, or one that is not a decodable image is
refused with a status message and **nothing on disk changes** — the theme is
validated before it is written, so what is stored is always loadable. Import
applies immediately; no restart.

### Feature 2 — Personnel roster import (both platforms)

`Roster.kt` and `Roster.cs` are mirrors, pure, no UI and no database. Header
detection is by content, not position:

- **Name** — exactly `Name`; then `Full Name` / `Display Name` /
  `Employee Name` / `Person Name`; then a `First Name` + `Last Name` pair joined
  with a space; then any header containing "name" (one not qualified by
  first/last/middle/nick preferred).
- **Credential** — any header containing "credential" or "card"; one that also
  carries a number word (`number`, `no`, `#`, `id`) wins.
- Quoted cells with embedded commas and newlines, doubled quotes, CR / LF /
  CRLF, and a UTF-8 BOM are all handled. Rows with no name, and repeats of a
  name already imported, are counted as skipped. A file with no name column is
  refused; nothing is stored.

Two resolution modes, and **only the second runs today**:

- **MAPPED** — `credentialToUidBytes` / `CredentialToUidBytes` would turn a
  credential number into badge UID bytes and pre-enroll the person as
  `hashUid(bytes)`. It is a clearly-marked placeholder returning `null`, behind
  a `CREDENTIAL_MAPPING_ENABLED = false` flag, because whether the access
  system's credential number relates to the NFC UID at all is unverified —
  Step 4 of `THURSDAY-HANDOFF.md`. A guessed transform would silently enroll
  people under each other's names. Flipping the flag is the whole change once
  the verdict exists; the provisional hex/decimal transform underneath it is
  already written and tested.
- **PICKER** — the working path, correct whatever Step 4 says. Imported rows are
  stored as **names only**; the credential number is dropped and never written
  to the database. An unknown badge then shows a searchable single-choice list
  of unclaimed roster names plus a "Type a name" escape hatch. Choosing a name
  binds it to that badge exactly as typing it would, and removes it from the
  pool. With no roster imported, the flow is unchanged from before.

Privacy contract intact: raw UIDs still never stored (salted SHA-256 only),
attendance still append-only, no new permissions, no INTERNET.

**Migration.** Android `Db` went from version 1 with a no-op `onUpgrade` to
version 2 with a real one that adds the `roster` table and touches nothing else,
so an upgrade over the installed build keeps existing events, people and
attendance. Windows creates tables `IF NOT EXISTS`, so the additive
`CREATE TABLE` is enough and an existing `checkin.db` picks it up unchanged.

## Commits

| sha | what |
|---|---|
| `6227d30` | Windows: roster import + picker for unknown badges |
| `218b4d5` | Android: runtime theming + the roster import twin |
| `5d58fb7` | Both cross-vendor audit findings folded |

Pushed to `0bsolescence/event-checkin` `main`; local HEAD and `origin/main` both
at `5d58fb7`.

## Builds and verification

| what | result |
|---|---|
| `gradle assembleDebug` | BUILD SUCCESSFUL |
| `gradle testDebugUnitTest` | **20 tests, 0 failures, 0 errors** |
| APK sha256 | `a4d67b7e6d777956ead2c5817e18709eefa88a5d6ee7b6c189a483f10c61ead8` |
| `aapt2 dump permissions` | `android.permission.NFC` only (plus the AndroidX self-scoped receiver permission, pre-existing) |
| `dotnet build -c Release` | 0 warnings, 0 errors |
| C# ↔ Kotlin parity harness | **39/39 checks match** |

The unit tests are plain JVM JUnit — no Robolectric, no device — because the
tested code (CSV parsing, header detection, name filtering, color parsing and
the luminance rule, the logo size ceiling) deliberately holds no Android types.

The Windows side has no test project, so instead of trusting review alone, every
case the Kotlin tests pin was run against the C# implementation through a
throwaway harness in the scratchpad. All 39 match, including the header
precedence order, BOM and quoted-cell handling, duplicate/blank-name skipping,
and that credential mapping stays disabled. That harness is scratch, not
committed; re-create it if `Roster.cs` and `Roster.kt` ever diverge again.

## Cross-vendor audit (`codex review`, OpenAI lineage)

Run against the pushed diff `8b45df1..218b4d5`. Two P2 findings, both real, both
fixed in `5d58fb7` with a test that fails without the fix.

**P2 — name-column precedence contradicted its own documentation. FIXED.**
The generic "contains name" search ran *before* the First/Last pair was
considered, so an export carrying `Username` alongside `First Name` and
`Last Name` would have imported login handles as people. This is exactly the
shape a Virtual Keypad export can have, so it was a live risk, not theoretical.
The generic fallbacks now run only when no first/last pair exists. Fixed
identically in both twins; pinned by
`a first and last pair beats a generic name column` and mirrored in the parity
harness.

**P2 — logo import bounded bytes but not pixels. FIXED.**
A well-compressed image under the 4 MB ceiling can decode to gigabytes, and
`applyTheme()` then decoded it in full — an out-of-memory kill at the door, from
a file the importer had already accepted. Decoded dimensions are now checked
before anything is written (4000 × 4000, 16 MP), and `loadLogo` re-checks and
downsamples toward the size a 44dp header can use, so the heap cost is bounded
even for a file that arrived by some path other than the importer (adb, for
instance). `OutOfMemoryError` is caught deliberately — a kiosk that loses its
logo is working, one that dies is not. Pinned by
`logo dimensions are bounded independently of file size`.

**Second pass** over the same diff after the fixes: *"No discrete, actionable
correctness issues were identified in the changed code."* Note that codex could
not run the builds itself (no `dotnet` on its PATH); the build results above are
mine, run directly.

## Manual test checklist — Daniel's tablet

Install the new APK over the existing one (do **not** uninstall first — the
upgrade path is one of the things being tested).

**Migration**
- [ ] Existing events, people and attendance all survive the upgrade. This is
      the v1 → v2 migration; if anything is missing, stop and tell me.

**Theming** (files are in `themes/local/`, install steps in its `INSTALL.md`)
- [ ] Get `theme.bft.json` and `bft-logo-icon.png` onto the tablet. Setup (`⋮`
      or long-press the title) → Import theme… → pick the JSON. Header, status
      line and title repaint immediately, no restart.
- [ ] It offers the logo next — say yes, pick the PNG. Logo appears in the
      header at a sensible size.
- [ ] Force-close and reopen: branding persists.
- [ ] Headcount number is legible in the accent color against the primary.
- [ ] Reset theme to neutral → confirmation → back to the shipped grey look and
      the logo is gone. **Do this before the tablet leaves BFT hands or before
      any screenshot that goes outside the agency.**
- [ ] Malformed input is refused, not fatal: rename any `.txt` to `.json` and
      import it; import a non-image file as a logo. Each shows a message and
      leaves the previous theme intact.

**Roster**
- [ ] Export the personnel list from Virtual Keypad as CSV, get it on the
      tablet, Setup → Import roster (CSV)…
- [ ] **Check the summary dialog's column names.** A wrong column match is the
      one failure mode that looks like success — if it says it matched a login
      or ID field instead of a person's name, send me the header row.
- [ ] Counts add up: names imported = pre-enrolled + waiting + skipped, and
      "pre-enrolled" should be **0** until Step 4 is answered.
- [ ] Tap an unenrolled badge → the name picker appears instead of the text
      prompt. Typing narrows the list. Picking a name checks that person in.
- [ ] That name is **not** offered again when a second unknown badge taps.
- [ ] "Type a name" still reaches the free-text prompt and works.
- [ ] Cancel out of the picker: status says enrollment cancelled, nothing
      stored, and the next tap behaves normally.
- [ ] Import a file with no name column (any CSV without one) → refused with a
      message, no crash, nothing stored.

**Regression — the base flow still has to work**
- [ ] Duplicate tap at the same event refused, headcount does not double.
- [ ] CSV export still lands via the share sheet and opens cleanly.

## What waits on the Step 4 verdict

Step 4 of `THURSDAY-HANDOFF.md`: compare your own credential number in Virtual
Keypad's Users admin against the id the tablet showed for your badge.

- **They relate** → tell me the transform (or both raw numbers and I will work
  it out). `credentialToUidBytes` gets the real implementation, the flag flips
  in both twins, and a roster import pre-enrolls everyone so nobody picks a name
  at the door at all. The provisional hex/decimal transform is already written
  and tested underneath the flag, so this is a small change.
- **They do not relate** → nothing to do. The PICKER path is already the shipped
  behavior and is unaffected; the placeholder and its flag stay as documentation
  of a question that was asked and answered.

Either way the feature works as shipped. Step 4 only decides whether people pick
their name once or never have to.

Still gating everything, unchanged from the Thursday hand-off: **UID stability**
(Step 1). If a DMP CSM-2P badge randomizes its UID, enrollment never sticks and
the roster picker will re-prompt for the same person on every tap. That test is
still the one that decides whether any of this is real.
