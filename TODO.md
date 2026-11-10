# To do

The four sections below come from the [project review of 2026-11-10](docs/superpowers/review-2026-11-10.md). Each has a plan, written but not started, and each plan opens with decisions for the owner to approve. Suggested order: bugs, then speed, design gaps and readability in any order.

## Review: bugs to fix first

[Plan](docs/superpowers/plans/2026-11-10-review-bug-fixes.md), ending in an owner checklist (hard stop).

- [ ] Owner approves the plan and its 6 decisions.
- [ ] Stroke ends and corners are filled; old strokes are drawn again (Task 1).
- [ ] The pen follows a zoomed page moved with one finger (Task 2).
- [ ] A stroke is kept even when the journal can't be written (Task 3).
- [ ] Nothing fails silently on the document's worker (Task 4).
- [ ] A kill mid-save or a changed date no longer sets writing aside (Task 5).
- [ ] The "damaged file" question pauses the pen; a copy is what's continued (Task 6).
- [ ] A short zoomed page doesn't turn; the pen can't write beside the page (Task 7).
- [ ] The pen's on, off and palm rules (Task 8; each checked on the tablet first).
- [ ] Tall first pages get a capped thumbnail (Task 9).
- [ ] Owner checklist (Task 10, hard stop).

## Review: speed

[Plan](docs/superpowers/plans/2026-11-10-review-speed.md). Needs Task 5 of the bug fixes first. Every change is measured on the tablet and kept only if it gains.

- [ ] Owner approves the plan and its 3 decisions.
- [ ] Measure: the "before" table (Task 1).
- [ ] A save only rereads the file when something touched it (Task 2).
- [ ] Adding a stroke costs the same on a full page (Task 3).
- [ ] A finished stroke doesn't redraw the page (Task 4).
- [ ] Page pictures are freed at once; fewer are made (Task 5).
- [ ] Dragging a selection redraws less (Task 6).
- [ ] A snip is written to the journal once (Task 7).
- [ ] Small ones, then the results (Task 8).

## Review: missing against the design

[Plan](docs/superpowers/plans/2026-11-10-review-design-gaps.md). Start after the SD-card work is committed. Conflict copies stay with plan 5.

- [ ] Owner approves the plan and its 5 decisions.
- [ ] Stale temporary files are cleaned up (Task 1).
- [ ] The library misses no change on disk (Task 2).
- [ ] A release build, minSdk 30, adb shortcuts only in debug (Task 3).
- [ ] The fast-ink rules, testable on the laptop (Task 4; only if decision 5 is approved).

## Review: easier-to-read pages

[Plan](docs/superpowers/plans/2026-11-10-review-readability.md), ending in an owner session (hard stop). Nothing in it changes the PDF.

- [ ] Owner approves the plan and its 5 decisions.
- [ ] Try the tablet's own display settings first, no code (Task 1).
- [ ] Sharp text when zoomed in (Task 2).
- [ ] A contrast setting (Task 3; skipped if Task 1 is enough).
- [ ] Cleaner page turns and a gentler refresh after zooming (Task 4).
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
  - [ ] Gentler refresh after zooming: the full flash works but feels a bit weird (owner, 2026-11-09). Try a softer e-ink mode that still clears fast-ink's leftovers.
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
