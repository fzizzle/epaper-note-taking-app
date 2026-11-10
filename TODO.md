# To do

The four sections below come from the [project review of 2026-11-10](docs/superpowers/review-2026-11-10.md). Each has a plan, written but not started, and each plan opens with decisions for the owner to approve. Suggested order: bugs, then speed, design gaps and readability in any order.

## Owner feedback (2026-11-10)

From using the app on the tablet. Worked through on the branch `owner-feedback-fixes`. UI and UX points wait for a UI session: [UI feedback](docs/superpowers/ui-feedback.md).

- [x] The writable area stops about 1 cm before the page edge. Found: at the sides the page is narrower than the screen (93 px each side), and ink can't be saved beside the page. That band is now shaded light grey (owner's choice).
- [x] Ink changes after it's written: a stroke thins out once you've finished it. Found (system log): about a second after the pen leaves, Onyx turns the app's own drawing back on ("Post enabled"), and the panel swaps Onyx's hard black line for the app's copy, whose grey rim was 34–45% of its pixels. Built: ink at full strength is drawn solid on screen at the same width (rim 1% on the owner's page); the page, highlighter and thumbnails stay smoothed. Costs 137 ms instead of 91 ms to draw a page of 1,500 strokes. Owner-checked 2026-11-10: it no longer thins. Tablet tests (84) and laptop tests (289) pass.
- [x] Paste a copied item with a finger press: a long-press shows a Paste button there (owner's choice: confirmed). Copy and snip now say so in a toast.
- [x] Turn the page with the volume buttons (also Page Up/Down).
- [x] Palm presses cause screen refreshes. Fixed: a touch only pauses fast-ink once it pinches or moves a zoomed page, and fingers are ignored while the pen is near.
- [x] Tool icons still don't respond to every tap. Fixed: Onyx stops passing the app's drawing to the panel while fast-ink is on; the strip now lets it through for a moment, with one flash per tap (owner's choice over no flash). Switching to the eraser no longer refreshes the screen.
- [x] The battery level can't be checked in the app. Tried in the strip; the owner didn't want it there. The status bar comes back for a moment with a swipe down from the top.
- [x] The eraser is slow (owner, 2026-11-10): the first erase or lasso on a page read all its strokes, 1 s for 1,500. Stroke text is now parsed in one pass: 131 ms.
- [x] Far too many refreshes overall. Found: left to itself the page view got GC, a hard flash, for every page turn and every redraw after an erase, move or undo. Readability plan Task 4 (decision 4, approved 2026-11-10): compared on the tablet with the owner, who chose **REGAL** for both a page shown and the refresh after a zoom (over GC and GU): little flash, no ghost left. Fixed in, trial switch removed.
- [x] A pinch right after writing was dropped: fingers within 0.5 s of the pen were all taken for the palm. A second finger now starts the pinch (a palm is one still touch). Checked with the owner: zooms at once, palm still ignored. Zooming within about a second of writing briefly shows fast-ink's copy of the new ink while the fingers move; the refresh at the lift wipes it (accepted for now).
- [x] Fast-ink could switch back on mid-pinch when a page picture arrived during the gesture; it now waits for the fingers to lift.

## Review: bugs to fix first

[Plan](docs/superpowers/plans/2026-11-10-review-bug-fixes.md), ending in an owner checklist (hard stop).

- [x] Owner said "fix the bugs" (2026-11-10), taken as approval of the 6 decisions; any can still be overturned.
- [x] Stroke ends and corners are filled; old strokes are drawn again (Task 1).
- [x] The pen follows a zoomed page moved with one finger (Task 2).
- [x] A stroke is kept even when the journal can't be written (Task 3).
- [x] Nothing fails silently on the document's worker (Task 4).
- [x] A kill mid-save or a changed date no longer sets writing aside (Task 5).
- [x] The "damaged file" question pauses the pen; a copy is what's continued (Task 6).
- [x] A short zoomed page doesn't turn; the pen can't write beside the page (Task 7).
- [x] The pen's on, off and palm rules (Task 8). Includes a stroke begun during a screen update (review 1.5).
- [x] Tall first pages get a capped thumbnail (Task 9).
- [x] Tablet tests run (68 pass), build installed, 2026-11-10.
- [ ] Owner checklist: [notes/TESTING-review-fixes.md](notes/TESTING-review-fixes.md) (Task 10, hard stop).

## Review: speed

[Plan](docs/superpowers/plans/2026-11-10-review-speed.md). Needs Task 5 of the bug fixes first. Every change is measured on the tablet and kept only if it gains.

**Done and merged (2026-11-10).** Laptop tests (287), tablet tests (77) and the owner's pen checks pass. [Results](docs/superpowers/review-speed-results.md).

- [x] Owner said "execute the speed plan" (2026-11-10), taken as approval of decisions 1 and 2.
- [x] Measure: the "before" table (Task 1).
- [x] A save only rereads the file when something touched it (Task 2): tried and dropped, it failed its tablet check.
- [x] Adding a stroke costs the same on a full page (Task 3): 43.5 ms down to 5.5 ms at 1,500 strokes.
- [x] A finished stroke doesn't redraw the page (Task 4): pen-checked 2026-11-10.
- [x] Page pictures are freed at once; fewer are made (Task 5): pen-checked 2026-11-10.
- [x] Dragging a selection redraws less (Task 6): pen-checked 2026-11-10.
- [x] A snip is written to the journal once (Task 7): 5.6 MB down to 0.27 MB for a snip moved ten times.
- [x] Small ones (Task 8): checked on the tablet 2026-11-10.
- [x] One complete run of the tablet tests on a free tablet (77 pass, 2026-11-10).
- [x] Owner pen checks: all fine (2026-11-10). Merged.

## Review: missing against the design

[Plan](docs/superpowers/plans/2026-11-10-review-design-gaps.md). Start after the SD-card work is committed. Conflict copies stay with plan 5.

- [ ] Owner approves the plan and its 5 decisions.
- [ ] Stale temporary files are cleaned up (Task 1).
- [ ] The library misses no change on disk (Task 2).
- [ ] A release build, minSdk 30, adb shortcuts only in debug (Task 3).
- [ ] The fast-ink rules, testable on the laptop (Task 4; only if decision 5 is approved).

## Review: easier-to-read pages

[Plan](docs/superpowers/plans/2026-11-10-review-readability.md), ending in an owner session (hard stop). Nothing in it changes the PDF.

- [x] Owner approved all 5 decisions (2026-11-10); the two settings go under ⋯ in the writing screen.
- [x] Try the tablet's own display settings first, no code (Task 1): they do very little, since the page is a picture to them ([trial](docs/superpowers/readability-trial.md)). Task 3 stays.
- [x] Sharp text when zoomed in (Task 2): owner-checked 2026-11-10, "works well".
- [ ] A contrast setting (Task 3; skipped if Task 1 is enough).
- [x] Cleaner page turns and a gentler refresh after zooming (Task 4): REGAL for both, owner's choice (2026-11-10).
- [ ] Trim margins (Task 5).
- [ ] Owner session (Task 6, hard stop).

## Next up

- [x] **Run the pen and touch probe:** [plan](docs/superpowers/plans/2026-11-07-pen-and-touch-probe.md)
  - [x] Build tools installed in the home folder (`source ~/.local/opt/android-env.sh`)
  - [x] Pen Probe app built (`probe/`), with 13 unit tests passing and the final review done
  - [x] Install it on the tablet and work through [probe/TESTING.md](probe/TESTING.md)
  - [x] Pull the log, then write up [probe results](docs/superpowers/probe-results.md)
  - [x] Decide on the points in the results (§5): fixed button mapping, mid-stroke presses accepted, palm re-tested

## After that

- [x] Choose a PDF library: MuPDF, so the app is AGPL-3.0 (approved 2026-11-09).
- [x] Write the plan for the main app: split into 5 plans. Plan 1 is the [PDF foundation](docs/superpowers/plans/2026-11-09-pdf-foundation.md), ending in a speed check.
- [x] Carry out plan 1, then review the speed check results (merged as PR #1).
- [x] Owner decisions: go ahead with plan 2, shrink strokes first, warn before saving into a repaired file.
- [x] Carry out plan 2: [writing screen](docs/superpowers/plans/2026-11-09-writing-screen.md), ending in an owner writing session (hard stop).
  - [x] Gentler refresh after zooming: REGAL instead of the full flash, the owner's choice (2026-11-10, readability plan Task 4).
  - [ ] Screen-recording test of writing (owner asked, 2026-11-09): record the screen while writing fast, to check no stroke ever disappears.
- [ ] Plan 3: [tools](docs/superpowers/plans/2026-11-09-tools.md): eraser, lasso, snip, paste, undo/redo, page menu, pen buttons, ending in an owner tool session (hard stop).
  - [x] Owner approves the plan and its 11 decisions.
  - [x] Build the tools (Tasks 1–9), with the fixes from the final review (merged as PR #3, 2026-11-10).
  - [ ] **Owner tool session** (Task 10, hard stop): work through [notes/TESTING-tools.md](notes/TESTING-tools.md) on the tablet, on the test copy.
  - [ ] Write up the results (`docs/superpowers/tools-results.md`) and record any design changes in the spec.
- [ ] Plan 4: [library](docs/superpowers/plans/2026-11-09-library.md): folders and thumbnails, breadcrumb, rename/move/delete, Settings (thumbnails switch, library folder), SD card, fast exit from a document. Approved 2026-11-10.
  - [x] Build the library (Tasks 1–6), SD card support, and the fixes from the final review (merged as PR #4, 2026-11-10). Library folder: SD card › Workbooks.
  - [ ] **Owner library session** (Task 7, hard stop): work through [notes/TESTING-library.md](notes/TESTING-library.md), including its "Also check (tools fixes)" section. Rename, move and delete only on copies or a scratch folder.
  - [ ] Answer the two questions in the checklist: a changed file's tile keeps its old picture until the new one is ready; the folder-delete question also mentions non-PDF files.
  - [ ] Write up the results (`docs/superpowers/library-results.md`) and record any design changes in the spec.
- [ ] Plan 5: conflicts and crash rescue (written only after the owner approves plans 3 and 4).
  - [ ] A file changed on disk while open: keep both versions, save the app's writing as `<name> (my edits YYYY-MM-DD).pdf`, and continue in the copy (spec §7.4).
  - [ ] Crash replay onto a changed file: turn set-aside journals (`.kept-…`) into a "(my edits …)" copy of the version they were drawn on.
  - [ ] A session whose only change is the redraw of old mirrored ink counts as no writing, so it never makes a conflict copy.
  - [ ] Show a lasting notice (not only a toast) when saving has stopped: changed on disk, repeated save failures, or a change that couldn't be written.

## Later (not in the first version)

- Blank notebooks
- Partial eraser
- Copy and paste between documents
- More pen colours

## Ideas to explore: getting the buttons out of the way

From the 2026-11-10 discussion. None are decided. The aim: buttons that stay out of the way of the work but are still quick to reach. Useful input first: which strip buttons get tapped most during the test sessions.

- **Gestures for the frequent actions:** a finger swipe to turn the page at normal size, a two-finger tap or swipe for undo and redo. Reverses spec §6.2 (a swipe at normal size does nothing), and a resting palm could trigger a swipe.
- **Invisible edge tap zones:** a finger tap at the left or right edge turns the page. The pen never moves the page, so it can't be confused with writing, and a tap is harder for a palm to trigger than a swipe.
- **Hide the strip on demand:** a finger double tap hides or shows it, as the Notable app does. One refresh when asked for. Weakest of these, since the strip is only about 5% of the screen.
- **A shorter strip:** keep only the buttons that get used, put the rest behind the menu button, and let the short row sit in a corner.
- **A transparent strip:** glyphs float over the page, each with a thin white outline so it stays readable over dark content, and the page gains the strip's 5%. The buttons would be cut out of the pen's area with the exclude list of `setLimitRect`. Costs nothing in speed; the risk is legibility over a dense page header.
- **Not this:** a strip that turns solid when the pen hovers over it. Each change is an e-ink refresh, it would probably need fast-ink paused each time (untested), and writing near the top of the page would trigger it constantly.
