# To do

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
  - [ ] Owner approves the plan and its 11 decisions.

## Later (not in the first version)

- Blank notebooks
- Partial eraser
- Copy and paste between documents
- More pen colours
