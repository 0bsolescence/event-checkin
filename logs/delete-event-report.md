# Event deletion, both twins — build report

> Written 2026-08-20. Ordered and delivered the same day, on `main` at
> `eaa1133`. Everything below compiles, unit-tests and (on the Windows storage
> layer) runs clean. **Nothing below has been exercised on the tablet or on
> Windows.** The manual checklist at the end is the postcondition list.

## What shipped

One feature, mirrored: delete an event and the attendance recorded against it.

### The semantics, identical on both platforms

`Db.deleteEvent` / `Db.DeleteEvent` removes the event's attendance rows and then
the event row **in one transaction**. Statement order is load-bearing, not
stylistic: with foreign keys enforced, deleting the event row while attendance
still references it fails the constraint. Foreign keys are on in both twins —
Android sets the pragma in `onConfigure`, and Microsoft.Data.Sqlite enables them
by default (verified by reading the pragma back on a live connection, not
assumed).

**People and imported roster names are deliberately untouched.** A person exists
independently of any event; un-enrolling them here would make their next tap at
a different event ask for a name again. Someone whose only appearance was at the
deleted event stays enrolled.

**The design tension, stated rather than papered over.** Attendance is
append-only from the UI as a privacy and audit posture — there is deliberately
no "remove one check-in" in either app, so a single record cannot be quietly
edited away. Deleting an *event* is therefore event-level housekeeping (a test
event, one created by mistake) that takes the whole record with it, visibly and
behind a confirmation, rather than reintroducing row-level editing by the back
door. Nothing is recoverable afterwards. That paragraph is in the code above
both implementations, not only here.

**The confirmation is mandatory and says what it is doing:** the event name, the
number of check-ins about to go ("1 check-in" / "12 check-ins" — Android via a
plurals resource, Windows via a ternary), that the records are removed
permanently and cannot be recovered, and — only when the count is non-zero — to
export the CSV first. Cancel is the default on both: Windows makes **No** the
default button so a stray Enter or Escape cancels; on Android the button, the
back key and a tap outside all leave the event exactly as it was. No path
deletes without an explicit confirm.

**After deletion** the newest remaining event is selected. If that was the last
event, both apps fall into the existing empty state and prompt for a new one.

### Two platform-shaped choices, both deliberate

**Windows: a toolbar button next to New Event.** That window has no Setup menu
to hold it.

**Android: a fifth entry in the ⋮ Setup menu**, alongside the theme and roster
actions — *not* a toolbar button and *not* a long-press on the event spinner.
The toolbar is what door staff touch during an event, and a destructive action
does not belong one mis-tap from Export; a long-press is invisible to anyone who
does not already know it exists, which is the wrong property for the one
irreversible action in the app. Setup is already the operator-only surface, it
is already reachable two ways (⋮ or long-pressing the title), and its entries
already spell themselves out. Documented in `android/README.md`.

### A crash path the feature exposed, fixed with it

`RefreshEvents` on Windows cleared the combo but not `_eventId`. Before
deletion existed nothing could empty that list, so it never mattered. With
deletion it does: delete the last event, cancel the new-event prompt, tap a
badge, and the app would have inserted attendance referencing an event that no
longer exists — a **foreign-key** failure, not a UNIQUE one, so it escapes
`RecordTap`'s deliberately-2067-only catch and takes the kiosk down. The stale
selection is now dropped before the prompt opens. Android already nulled its
selection in the same place; its `refreshEvents` now also raises the new-event
prompt itself, so `onCreate` and the post-deletion path share one empty state
and the twins match line for line.

Also mirrored from the enroll prompt: the tap guard (`_tapBusy` / `tapBusy`) is
held while the confirmation is on screen, so a badge presented mid-question
cannot stack an enroll dialog on top of a destructive one.

### No schema change

This is DML against the existing v2 schema. Android's `Db.VERSION` stays at 2,
no `onUpgrade` runs, and an installed build upgrades with events, people and
attendance intact.

## Commits

| sha | what |
|---|---|
| `2c469f8` | Windows: delete an event and its attendance, behind a confirmation |
| `055dc04` | Android: the event-deletion twin, in the ⋮ Setup menu |
| `eaa1133` | Fold codex P2: wrap the toolbar so the fifth control cannot clip |
| `e32ec9c` | This report |
| `fd20dfa` | Add `DbDeleteEventTest`, missed by the pathspec commit that referenced it |

Pushed to `0bsolescence/event-checkin` `main`; local HEAD and `origin/main` both
at `fd20dfa`.

**A miss worth recording.** `git commit -- android/` committed the
`build.gradle.kts` and version-catalog entries wiring up the new test but *not*
the test file, because a pathspec commit does not pick up an untracked file.
For three commits the pushed tree carried a test dependency and a claim with no
test behind it, while the local build kept passing. Caught by reading
`git status` after the push rather than trusting the commit, and fixed in
`fd20dfa` — then confirmed the hard way, below.

## Builds and verification

| what | result |
|---|---|
| `gradle clean assembleDebug testDebugUnitTest` | BUILD SUCCESSFUL |
| unit tests | **26 tests, 0 failures, 0 errors** (6 new in `DbDeleteEventTest`) |
| APK sha256 | `c77773b95116130626b9fb12fb48345a11b4c40ce522fc1eb2601488852d3abb` |
| `aapt2 dump permissions` | `android.permission.NFC` only (plus the pre-existing AndroidX self-scoped receiver permission) |
| sqlite-jdbc in the APK | **absent** — 0 matching entries in the zip; test classpath only |
| `dotnet build -c Release` | 0 warnings, 0 errors |
| C# storage-layer harness (executed) | **17/17 checks pass** |

Every row above was then re-run against a **fresh clone of pushed `fd20dfa`**,
not the working tree — the postcondition for "it is actually in the repo" after
the missing-file miss. Same results, and the same APK sha256 byte for byte from
a clean checkout.

### How the deletion is actually tested

The brief's fallback applied — the existing test source set is plain JVM JUnit
over pure logic, and `Db` is a `SQLiteOpenHelper`, so nothing there could reach
it. Rather than settle for a shape-of-the-SQL test, both twins are pinned by
**executing the real statements**:

- **Android.** `Db`'s schema and its `DELETE` / `COUNT` statements were
  extracted to constants, and `DbDeleteEventTest` runs *those same constants*
  against a real SQLite engine over `org.xerial:sqlite-jdbc` — a `testImplementation`
  dependency that never enters the APK (verified above). Six tests: deletion
  takes the event's attendance and no one else's; people and the roster pool
  survive; the confirmation count is scoped to one event; a rolled-back
  transaction leaves the event and its attendance intact; the reverse statement
  order fails the foreign key (which is *why* the order is what it is); and
  deleting an event with no check-ins removes exactly one row.
  **What it does not cover:** Android's `SQLiteDatabase` wrapper — its
  `beginTransaction`/`endTransaction` and the `onConfigure` pragma are device
  behavior and stay on the manual checklist.
- **Windows.** There is still no test project, so `Db.cs` — which holds no WPF
  types — was compiled on Linux into a throwaway `net8.0` console harness in the
  scratchpad and run against a real SQLite file. 17/17: the transaction commits
  through `Exec` (commands from `CreateCommand()` do inherit the connection's
  active transaction — the one assumption that could not be checked by
  building), foreign keys report ON, the deletion is scoped correctly, both
  people stay enrolled, the roster pool is untouched, the other event's export
  still lists its attendee, and a re-tap into a fresh event afterwards works.
  That harness is scratch, not committed; re-create it if the storage layers
  ever diverge.

The WPF layout itself cannot be executed on these nodes at all. Every UI claim
about the Windows side is a compile-time claim.

## Cross-vendor audit (`codex review`, OpenAI lineage)

Run against the pushed diff `48969ba..055dc04`. One P2, real, fixed in
`eaa1133`.

**P2 — the new button pushed an existing one off the window. FIXED.**
`Delete Event…` made five touch-sized controls on one horizontal `StackPanel`:
the 360px event picker plus four padded 18pt buttons. That overflows the 960px
default width and badly overflows the 640px minimum, and a `StackPanel` neither
wraps nor scrolls — so `Import Roster…`, the rightmost, would have been clipped
and unreachable. A new feature breaking an existing one, and exactly the class
of thing a build cannot catch. Now a `WrapPanel`, with a bottom margin on the
buttons and the picker as the gap between wrapped rows. Compiles clean; the
wrap is a rendering behavior and is on the Windows checklist.

**Second pass** over the same diff after the fix: *"The event deletion paths are
transactionally scoped, preserve people and roster data, refresh selection
state, and guard badge taps during confirmation. No actionable correctness
defects were identified in the changed code."*

As last time, codex could not run either build itself (no `dotnet` on its PATH);
every build and test result above is mine, run directly.

## Manual test checklist — Daniel's tablet

Install the new APK over the existing one (do **not** uninstall first).

**Nothing lost on the way in**
- [ ] Existing events, people and attendance all survive the upgrade. No
      migration should run — the schema version is unchanged at 2.

**Delete event — the happy path**
- [ ] Create a throwaway event, check two badges in to it, then Setup (⋮ or
      long-press the title) → **Delete event**.
- [ ] The confirmation names *that* event and says **2 check-ins**, warns the
      records go permanently, and tells you to export first.
- [ ] **Cancel three ways — the button, the back key, and a tap outside the
      dialog. After each, the event is still there with both check-ins.**
- [ ] Confirm. The event disappears from the spinner, the newest remaining
      event is selected, and its attendance list is the one on screen.

**What must survive it**
- [ ] Both people who attended the deleted event are still enrolled: tap either
      badge at another event and it checks them in **without** asking for a
      name. This is the load-bearing one — if it prompts, deletion is taking
      people with it.
- [ ] An imported roster's unclaimed names are still offered to an unknown
      badge.
- [ ] Another event's attendance list and headcount are unchanged, and its CSV
      export still contains its rows.

**Edges**
- [ ] Delete an event nobody checked in to: the confirmation says so ("Nobody
      has checked in to it") and offers no export advice.
- [ ] Delete the **last** remaining event → the new-event prompt appears.
      Cancel it: the spinner is empty, the list is empty, headcount is 0, and
      tapping a badge says "Create/select an event first." **It must not
      crash** — that is the specific path this feature could have broken.
      Then create an event and confirm check-ins work normally again.
- [ ] Tap a badge while the delete confirmation is open: nothing happens, no
      second dialog stacks. Answer the dialog, then tap again — normal.
- [ ] Force-close and reopen after a deletion: the event is still gone, the
      remaining ones are intact.

**Windows (whenever it next runs on Windows)**
- [ ] The toolbar fits at the 960px default and at the 640px minimum — the
      picker and all four buttons stay visible, wrapping to a second row rather
      than clipping. Unverified by anything except arithmetic so far.
- [ ] Delete Event…: **No** is the default button; Escape and Enter both
      cancel; Yes deletes the event and its attendance only.

## Still gating everything

Unchanged from the Thursday hand-off and the theming report: **UID stability**
(Step 1). If a DMP CSM-2P badge randomizes its UID, enrollment never sticks and
none of the rest matters. Deletion does not touch that question either way.
