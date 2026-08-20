# Thursday hand-off — Event Check-In hardware testing

> Written 2026-08-19 night by Munin for Daniel's Thursday day-job session.
> Fragment-time friendly: each step is self-contained, stops cleanly, and needs
> no terminal. The goal is HARDWARE TRUTH — the code compiles and the APK runs;
> what's unproven is how it behaves against real BFT badges. Everything below is
> a probe with a pass/fail you can eyeball. No new building required from you.

## The one thing that decides everything: UID stability

Every downstream feature (enroll-once, roster, headcount, the whole Windows
twin) rests on a single unproven assumption: **does a DMP CSM-2P card return the
SAME id every tap?** DESFire cards CAN be configured to randomize their UID. If
BFT's are, enrollment never sticks and the whole design needs its fallback.
Test this FIRST. It's five minutes and it's the gate.

### Step 1 — Stability test (do this before anything else)

- Open the Event Check-In app on the tablet (already installed + working).
- Create a test event ("UID Test").
- Tap ONE badge. Enroll it as "Test Card A".
- Tap the SAME badge 4 more times. **PASS:** it says "already checked in" every
  time (same id → recognized). **FAIL:** it re-prompts for a name (changing id).
- Force-close the app, reopen, tap the same badge again. **PASS:** still
  recognized. **FAIL:** re-prompts.
- Tap a SECOND, different badge. **PASS:** prompts for a new name (distinct id).

Record the verdict in one line. If PASS: the design is proven, proceed to Step
2. If FAIL: STOP building on it — ping me, and we switch to the DMP credential
file or name-only fallback. This result is the single most valuable thing the
session can produce.

## If Step 1 passes — the rest of the checklist

### Step 2 — Real-badge behavior (10 min)
- Enroll 3–4 real badges (yours, and any colleagues who consent — frame it as
  "testing an event check-in tool," never as tracking).
- Confirm the live roster shows newest-on-top, headcount increments correctly.
- Duplicate-tap each: refused, count doesn't double.

### Step 3 — Export truth (5 min)
- Export CSV. Open it. **Check:** row count == distinct people who tapped; the
  TOTAL HEADCOUNT line matches; names and times are right; no weird formula
  cells (the =+-@ neutralization).
- The file lands via the Android share sheet (SAF) — save it somewhere you can
  retrieve, confirm it opens in a spreadsheet cleanly.

### Step 4 — The credential-number question (parked from tonight, optional)
- In Virtual Keypad's Users admin, find your own user, note your credential
  number. Compare it to the id the tablet showed for your badge in Step 1.
- Match → we can build "Import roster" so names resolve without enroll-on-tap.
  No match → enroll-on-first-tap stays the model. Either is fine; this just
  tells us which. Bring me both numbers and I'll tell you which world we're in.

## What NOT to spend the session on
- The Windows exe: needs the OMNIKEY reader, which hasn't arrived. Skip.
- Branding/theming: the WPF branded build waits on this test passing first.
- Any code change: this is a testing session, not a building one. If something's
  broken, capture WHAT (a photo of the screen, the failing step) and hand it to
  me — I fix it, you re-test.

## Boundary reminder
Day-job hours, day-job device, day-job badges. This is BFT's gift being tested
on BFT premises — keep it framed as event check-in for audit documentation,
never staff tracking, and let anyone opt out with a paper line. Your ISO
instincts are the right ones here.

## Where things stand (so you're not reconstructing)
- APK: installed, runs, tested working by you tonight. sha256 starts 567d0dae.
- Repo: github.com/0bsolescence/event-checkin (public, Apache-2.0).
- Both codebases passed two codex audit rounds; 16 findings fixed.
- Open question this session answers: UID stability (the gate) + the
  credential-number match (the roster-import unlock).

## Your one-line dispatch tomorrow
"Munin — Thursday Event Check-In session: run the UID stability test first,
report the verdict, then work the checklist." I'll pick it up from there.
