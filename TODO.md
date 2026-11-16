# To do

The four sections below come from the [project review of 2026-11-10](docs/superpowers/review-2026-11-10.md). Each has a plan, written but not started, and each plan opens with decisions for the owner to approve. Suggested order: bugs, then speed, design gaps and readability in any order.

## Review (2026-11-16)

A read-through of the whole app for bugs, with suggestions: [findings](docs/superpowers/review-2026-11-16.md). Nothing was run on the tablet, and no way to lose writing in normal use was found. Three plans, written and not started; each opens with decisions for the owner to approve. Suggested order as listed.

- [ ] Owner approves the decisions in each plan (4, 5 and 6 of them). (Conflict copy plan: decisions approved 2026-11-16.)
- [ ] **[Conflict copy and journal fixes](docs/superpowers/plans/2026-11-16-conflict-copy-fixes.md)** (rows A1–A10), built on the branch `conflict-fixes`. **Owner checklist open:** [notes/TESTING-conflicts.md](notes/TESTING-conflicts.md), checks C4 to C6 and the line under C1.
  - [x] One way into a "(my edits)" copy, which happens whole or leaves nothing behind: no pile of copies on a full tablet (Task 1).
  - [x] A changed file is opened only once the pen is held; a stroke before that goes into a copy (Task 2).
  - [x] What MuPDF does when a file is overwritten under it, tried on the tablet; a copy is checked before it's trusted (Task 3). The fault was real, so files up to 64 MB are now read into memory when opened for writing.
  - [x] No copy with nothing in it (Task 4).
  - [x] The journal's notes (Task 5).
- [ ] **[Writing screen and library fixes](docs/superpowers/plans/2026-11-16-screen-and-library-fixes.md)** (rows B1–B7, C1–C3, D1–D2), ending in an owner checklist.
  - [ ] A rebuilt screen comes back to the right file; a keyboard or dark mode no longer rebuilds it (Task 1).
  - [ ] A page that couldn't be drawn is asked for again (Task 2).
  - [ ] A selection or a paste stops at the page's edge (Task 3).
  - [ ] Does the pen mark the page through the notification shade? Checked first, fixed only if so (Task 4).
  - [ ] Two windows open at once (Task 5).
  - [ ] A rename or move never replaces a file; a moved file never inherits another's writing; the folder watch (Task 6).
  - [ ] A snip over handwriting hides it on screen too (Task 7).
  - [ ] Very long file names can be saved (Task 8).
- [ ] **[Improvements](docs/superpowers/plans/2026-11-16-improvements.md)** (rows E1–E8), each its own task, ending in an owner checklist.
  - [ ] The page is remembered at each turn (Task 1); tiles carry their name as text (Task 2); the Onyx SDK download is checked (Task 3).
  - [ ] Back lets go first (Task 4).
  - [ ] The first-run folder choice (Task 5).
  - [ ] Deleted files are kept for 30 days and can be put back (Task 6).
  - [ ] "Open with Jot" (Task 7).
  - [ ] Search by name (Task 8) and an order setting (Task 9).
- Publishing on the Play Store was looked into the same day and is **not planned**: [what it would take](docs/superpowers/play-store-findings.md).

## Owner feedback (2026-11-10)

From using the app on the tablet. Worked through on the branch `owner-feedback-fixes`. UI and UX points wait for a UI session: [UI feedback](docs/superpowers/ui-feedback.md).

- [x] The writable area stops about 1 cm before the page edge. Found: at the sides the page is narrower than the screen (93 px each side), and ink can't be saved beside the page. That band is now shaded light grey (owner's choice).
- [x] Ink changes after it's written: a stroke thins out once you've finished it. Found (system log): about a second after the pen leaves, Onyx turns the app's own drawing back on ("Post enabled"), and the panel swaps Onyx's hard black line for the app's copy, whose grey rim was 34–45% of its pixels. Built: ink at full strength is drawn solid on screen at the same width (rim 1% on the owner's page); the page, highlighter and thumbnails stay smoothed. Costs 137 ms instead of 91 ms to draw a page of 1,500 strokes. Owner-checked 2026-11-10: it no longer thins. Tablet tests (84) and laptop tests (289) pass.
- [x] Ink still thinned (owner, 2026-11-10, after picking another tool). Found (measured with Onyx's own fountain-pen code on the tablet, `check/FountainWidthCheck`): Onyx draws a point 2 + (W + 1) × pressure² px wide for a set width W, but the saved ink followed pressure in a straight line, so firm strokes were saved up to 40% thinner (8 px live, 5 px saved at full pressure). Built: the pen's saved width follows pressure squared, 0.8 to 3.2 pt, and Onyx gets the matching width; now equal at every pressure at 2.5 px/pt, within half a pixel at other page sizes. Strokes already saved keep their old width. **Owner check:** write firmly, pick another tool: does the ink stay as it was? Owner, 2026-11-12: no, the live line is thicker (see Owner session (2026-11-12) below). Fixed by the 1.2× wider saved ink; owner, 2026-11-12: "it works now".
- [x] Paste a copied item with a finger press: a long-press shows a Paste button there (owner's choice: confirmed). Copy and snip now say so in a toast.
- [x] Turn the page with the volume buttons (also Page Up/Down).
- [x] Palm presses cause screen refreshes. Fixed: a touch only pauses fast-ink once it pinches or moves a zoomed page, and fingers are ignored while the pen is near.
- [x] Tool icons still don't respond to every tap. Fixed: Onyx stops passing the app's drawing to the panel while fast-ink is on; the strip now lets it through for a moment, with one flash per tap (owner's choice over no flash). Switching to the eraser no longer refreshes the screen.
- [x] The battery level can't be checked in the app. Tried in the strip; the owner didn't want it there. The status bar comes back for a moment with a swipe down from the top.
- [x] The eraser is slow (owner, 2026-11-10): the first erase or lasso on a page read all its strokes, 1 s for 1,500. Stroke text is now parsed in one pass: 131 ms.
- [x] Far too many refreshes overall. Found: left to itself the page view got GC, a hard flash, for every page turn and every redraw after an erase, move or undo. Readability plan Task 4 (decision 4, approved 2026-11-10): compared on the tablet with the owner, who chose **REGAL** for both a page shown and the refresh after a zoom (over GC and GU): little flash, no ghost left. Fixed in, trial switch removed.
- [x] A pinch right after writing was dropped: fingers within 0.5 s of the pen were all taken for the palm. A second finger now starts the pinch (a palm is one still touch). Checked with the owner: zooms at once, palm still ignored. Zooming within about a second of writing briefly shows fast-ink's copy of the new ink while the fingers move; the refresh at the lift wipes it (accepted for now).
- [x] Fast-ink could switch back on mid-pinch when a page picture arrived during the gesture; it now waits for the fingers to lift.

## Owner session (2026-11-12)

Passed: the library, the review fixes, palm rejection, the other tools, copy and paste, move and resize, and tool taps on the strip. How each problem was found: [findings log](docs/superpowers/findings-log.md).

- [x] **Button 2 often did nothing** (owner: "usually fails the first 2–3 attempts"; no ink and no erase). Found (raw pen events, `getevent`): pressed while the pen already hovers close to the screen, the pen sends the button and the tip's pressure but never a touch, and stays a pen instead of becoming the eraser. So Onyx neither draws nor erases, and Android sends only hover events with the button flag (`buttons=64`) and pressure about 0.1; plain hovering reads 0. Built (branch `button2-hover-erase`): those hover samples become an eraser trail (`HoverErase`). Owner: "works much more reliably". The erases that already worked went through Onyx's erase callback and all hit; the misses never reached the app.
- [x] **Pen ink still thins after picking another tool**: the live line is thicker than the saved ink. The tablet runs the PR #21 fix, so Onyx's live line doesn't follow the SDK's `computeStrokePoints` curve that fix was matched to. Next: measure the real live line (screenshots while writing). Owner, 2026-11-12: still "slightly thinner" after a tool tap. Measured from the owner's photos before and after a tap: the saved copy was 82–87% of the live width at normal pressure ([findings log](docs/superpowers/findings-log.md)). Built: saved pen ink 1.2× wider (0.96–3.84 pt), live line unchanged. **Owner check:** write lightly, normally and firmly, then tap a tool: does each line keep its width? Owner, 2026-11-12: "it works now".
- [ ] The eraser gives no sign while dragging (owner asked for one, e.g. a different hover look). On the hover route there's no touch to draw from.
- [x] "The content stays when I go to a different page": owner, 2026-11-12: closed, it's fine.
- [x] The strip doesn't change for a held pen button: as decided on 2026-11-10 (it would flash at each press). Owner, 2026-11-12: leave it as it is for now.

### Known bugs (not being fixed yet)

- **The erased part of the screen flashes after each erase** (owner, 2026-11-12). Since the [efficiency plan](docs/superpowers/plans/2026-11-12-efficiency.md), Task 4: once per run of erases, when the eraser rests, not after each one; the flash itself is still there. Seen in the log for every erase that hits, by either route (Onyx's erase callback or the hover route): the page is drawn again without the item, then `showWithoutFastInk` pauses fast-ink for about 165 ms (`show: pause fast-ink` … `resume fast-ink`; SurfaceFlinger `toPause` … `running`, with `Post enabled!` and `HWEpdcManager ### EUS` as it starts), and the panel update in that pause is the flash. The same as the "each erase refreshes the page" note in [notes/TESTING-strip.md](notes/TESTING-strip.md). Noticed more now that button 2 erases every time. A quiet refresh without the pause was tried before: it only exists in a flashing mode.

## UI review (2026-11-12)

The UI and UX review on the tablet, and its fixes: [plan](docs/superpowers/plans/2026-11-12-ui-review.md), branch `ui-review`. Owner approved all seven decisions (2026-11-12).

- [x] PDF tiles leave out the part of the name they all share: "workbook", "answer key" (Task 1).
- [x] Folder tiles are a small folder mark, with the count clear of the name (Task 2).
- [x] The writing strip shows the open file's name (Task 3).
- [x] Paste stays on the island, grey until something is copied (Task 4).
- [x] Menus outlined in black, teal gone, ⋯ split in two, the long-pressed tile marked, Go to page padded (Task 5).
- [x] A shorter copy message after the first three, a Paste bubble like the strip, the pen mark inside its circle (Task 6).
- [x] Owner checklist: [notes/TESTING-ui-review.md](notes/TESTING-ui-review.md): all checks pass (owner, 2026-11-12). [Results](docs/superpowers/ui-review-results.md).
- [ ] Erase trail and erase flash: their own plan (see Known bugs above).
- [x] The two left from the branch review (2026-11-13, branch `leftovers`): the name of the copy is put in the strip at once after "save to a copy"; the grey mark of a long-pressed tile stays with its file when the folder refreshes under the open menu.

## Owner session (2026-11-13)

All four checklists pass: efficiency, library design, writing screen design, dotted pages and fresh attempts; and the original library checklist again.

- [x] **Opening a course with small folders opened out is slow, with all the thumbnails** (owner, 2026-11-13). Measured from the tablet's log ([findings log](docs/superpowers/findings-log.md)): a course now shows about twice as many PDF tiles (22 to 30, each topic's two PDFs, where there used to be 11 to 15 folder tiles), and the first visit after the library design build drew every one of them again (22 × about 30 ms) because the cached thumbnails had no page count or writing mark yet. They came onto the screen in five goes over 0.8 s, so five refreshes. Later visits read them from the cache and show them in one go. Coming back from a document to a course took 380 to 500 ms before the folder showed. Measured further on the tablet (`check/ThumbnailCheck`): a thumbnail is cheap to make (about 15 ms) and to read from the cache (2 ms); what's felt is the folder drawn twice (grey, then pictures) and missing ones coming on in several goes. [Plan](docs/superpowers/plans/2026-11-13-lighter-thumbnails.md), built on the branch `thumbnails`: a folder now comes on in one refresh once the library has drawn its thumbnails ahead, two on a first visit before that (five or six before). [Results](docs/superpowers/thumbnail-results.md). Owner checklist [notes/TESTING-thumbnails.md](notes/TESTING-thumbnails.md): all checks pass, the gentler refresh kept (owner, 2026-11-13: "it works well").

## Dotted pages and fresh attempts (2026-11-12)

[Plan](docs/superpowers/plans/2026-11-12-pages.md). Built on the branch `pages`, each task by its own agent with a review after it.

- [x] Owner approved decisions 1–5 (2026-11-12).
- [x] MuPDF's page calls are safe to build on (Task 1): 200 rounds of adding and removing, three runs on the tablet, no crash.
- [x] Dotted paper as plain PDF drawing (Task 2).
- [x] A dotted page can be added after any page, and an added page removed (Task 3).
- [x] Adding or removing a page saves first, then renumbers (Task 4). A stroke that arrives late keeps its page.
- [x] The writing screen: ⋯ "Add a dotted page after this one", "Remove this added page", + on the last page (Task 5). Tried on the tablet over adb; writing on a new page needs the pen.
- [x] Fresh attempt in the library (Task 6).
- [x] The dots' strength: 0.5 grey, kept (owner passed checklist P2, 2026-11-13).
- [x] Owner checklist: [notes/TESTING-pages.md](notes/TESTING-pages.md). All checks pass (owner, 2026-11-13). The questions in the checklist weren't answered one by one, so what was built stays as it is.
- For plan 5 (conflict copies): a journal set aside by a page change holds old page numbers against a file that no longer exists, and its first changes are already in the new file. It must never be replayed onto the current file.

## Writing screen design (2026-11-12)

[Plan](docs/superpowers/plans/2026-11-12-writing-screen-design.md). Built on the branch `writing-screen-design`.

- [x] Owner approved decisions 1–8 (2026-11-12).
- [x] The line under the strip shows where you are and which pages have writing (Task 1).
- [x] A Fit button (Task 2) and zoom kept through a page turn (Task 3). Tried on the tablet with an injected pinch (`check/ZoomCheck`).
- [x] A number pad for Go to page (Task 4).
- [x] Holding an arrow goes to the first or last page (Task 5).
- [x] Duplicate on the selection's bar (Task 6). Not tried on the tablet: a selection needs the pen.
- [x] A tap with the lasso selects the stroke under it (Task 7). Not tried on the tablet: it needs the pen.
- [x] A sideways deck is offered a rotation the first time it's opened (Task 8).
- [x] Owner checklist: [notes/TESTING-writing-design.md](notes/TESTING-writing-design.md). All checks pass (owner, 2026-11-13). The questions in the checklist weren't answered one by one, so what was built stays as it is.

## Library design (2026-11-12)

[Plan](docs/superpowers/plans/2026-11-12-library-design.md). Built on the branch `library-design`.

- [x] Owner approved decisions 1–9 (2026-11-12).
- [x] A PDF's page count and whether it has writing, kept with its thumbnail (Task 1).
- [x] Tiles show how far you are and whether you've written (Task 2). The writing mark hasn't been seen on the tablet: it needs the pen.
- [x] A colour for each course (Task 3). The plan's rule gave FLM and PBM the same colour; a course now takes the next free one.
- [x] A folder of up to three PDFs is shown opened out (Task 4).
- [x] Continue shows the page you left (Task 5).
- [x] A list view, your place kept, and a hint in an empty folder (Task 6).
- [x] A setting to start in the last document (Task 7).
- [x] Owner checklist: [notes/TESTING-library-design.md](notes/TESTING-library-design.md). All checks pass (owner, 2026-11-13). The questions in the checklist weren't answered one by one, so what was built stays as it is.

## Efficiency (2026-11-12)

[Plan](docs/superpowers/plans/2026-11-12-efficiency.md), from the performance review of 2026-11-12. [Results](docs/superpowers/efficiency-results.md).

- [x] Owner approved decisions 1–3 (2026-11-12).
- [x] Measure: the "before" table (Task 1).
- [x] With Trim margins a neighbour page is drawn once (Task 2): 23 page pictures for ten turns down to 13.
- [x] Page pictures are freed when the document is left (Task 3): 145 MB of native memory down to 65 MB back in the library.
- [x] A run of erases is shown once, when the eraser rests (Task 4): built, not yet tried with the pen.
- [x] Autosave ten seconds after the pen stops (Task 5).
- [x] The solid-ink step works in bands (Task 6): the same picture to the pixel, through a tenth of the memory.
- [x] Thumbnails on screen are read at normal priority and shown in groups (Task 7): one pass over a folder's tiles, not five or six.
- [x] Thumbnails survive a start with the SD card out (Task 8).
- [x] Small ones (Task 9): 16-bit cached thumbnails, one read per subfolder, idle scroll callbacks skipped.
- [x] Owner checklist: [notes/TESTING-efficiency.md](notes/TESTING-efficiency.md). All checks pass (owner, 2026-11-13). The questions in the checklist weren't answered one by one, so what was built stays as it is.

Next, each with its plan and its own decisions: [library design](docs/superpowers/plans/2026-11-12-library-design.md), [writing screen design](docs/superpowers/plans/2026-11-12-writing-screen-design.md), [dotted pages and fresh attempts](docs/superpowers/plans/2026-11-12-pages.md).

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
- [x] Owner checklist: [notes/TESTING-review-fixes.md](notes/TESTING-review-fixes.md) (Task 10, hard stop). Passed in the owner session of 2026-11-12.

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

[Plan](docs/superpowers/plans/2026-11-10-review-design-gaps.md). Conflict copies stay with plan 5.

- [x] Owner approved decisions 1–4 (2026-11-10). Decision 5 (Task 4) waits until after the owner's tools and library sessions.
- [x] Stale temporary files are cleaned up (Task 1): only ones over ten minutes old, never while a document in the folder is open or saving. Checked on the tablet.
- [x] The library misses no change on disk (Task 2): watched before it's read, again after a folder is recreated, and the subfolders on screen too. Checked on the tablet: a PDF dropped into a subfolder updates its count.
- [x] A release build, minSdk 30, adb shortcuts only in debug (Task 3): about twice as fast at adding strokes ([speed results](docs/superpowers/review-speed-results.md)); installs over the debug build keeping the app's data. Now on the tablet.
- [ ] The fast-ink rules, testable on the laptop (Task 4; decision 5, after the owner's tools and library sessions).

## Review: easier-to-read pages

[Plan](docs/superpowers/plans/2026-11-10-review-readability.md), ending in an owner session (hard stop). Nothing in it changes the PDF.

**Done and merged (2026-11-10).**

- [x] Owner approved all 5 decisions (2026-11-10); the two settings go under ⋯ in the writing screen.
- [x] Try the tablet's own display settings first, no code (Task 1): they do very little, since the page is a picture to them ([trial](docs/superpowers/readability-trial.md)). Task 3 stays.
- [x] Sharp text when zoomed in (Task 2): owner-checked 2026-11-10, "works well".
- [x] A contrast setting (Task 3): Normal / Stronger / Strongest under ⋯, owner-checked 2026-11-10.
- [x] Cleaner page turns and a gentler refresh after zooming (Task 4): REGAL for both, owner's choice (2026-11-10).
- [x] Trim margins (Task 5): a tick under ⋯, owner-checked 2026-11-10.
- [x] The page drawn on whole pixels, not half a pixel off (Task 7; owner asked 2026-11-10): a screenshot matches a 1:1 render; owner: looks good.
- [x] Dark text as black: tinted near-black text shown black, so the colour screen draws it at 300 ppi, not 150 (Task 8; owner asked 2026-11-10): on unless turned off (owner), 20 ms a page; owner: looks good.
- [x] Checklist written and spec §6.2 updated (Task 6, steps 1–2).
- [x] Owner session (Task 6): "it looks good" (2026-11-10). [Results](docs/superpowers/readability-results.md). Merged.

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
- [x] Plan 3: [tools](docs/superpowers/plans/2026-11-09-tools.md): eraser, lasso, snip, paste, undo/redo, page menu, pen buttons, ending in an owner tool session (hard stop).
  - [x] Owner approves the plan and its 11 decisions.
  - [x] Build the tools (Tasks 1–9), with the fixes from the final review (merged as PR #3, 2026-11-10).
  - [x] **Owner tool session** (Task 10, hard stop): work through [notes/TESTING-tools.md](notes/TESTING-tools.md) on the tablet, on the test copy. Passed in the owner session of 2026-11-12.
  - [x] Write up the results (`docs/superpowers/tools-results.md`) and record any design changes in the spec. Recorded in "Owner session (2026-11-12)" above instead; no design changes.
- [x] Plan 4: [library](docs/superpowers/plans/2026-11-09-library.md): folders and thumbnails, breadcrumb, rename/move/delete, Settings (thumbnails switch, library folder), SD card, fast exit from a document. Approved 2026-11-10.
  - [x] Build the library (Tasks 1–6), SD card support, and the fixes from the final review (merged as PR #4, 2026-11-10). Library folder: SD card › Workbooks.
  - [x] **Owner library session** (Task 7, hard stop): work through [notes/TESTING-library.md](notes/TESTING-library.md), including its "Also check (tools fixes)" section. Rename, move and delete only on copies or a scratch folder. All checks pass (owner, 2026-11-12 and again 2026-11-13).
  - [x] Answer the two questions in the checklist: a changed file's tile keeps its old picture until the new one is ready; the folder-delete question also mentions non-PDF files. Not answered one by one; both stay as built.
  - [x] Write up the results (`docs/superpowers/library-results.md`) and record any design changes in the spec. Recorded here instead; no design changes.
- [x] Plan 5: conflicts and crash rescue. Built 2026-11-13 on the branch `leftovers`, without a plan document of its own: the owner said "fix the problems" after the inspection of that day, and made the one open decision (below). 524 laptop tests and 126 tablet tests pass.
  - [x] A file changed on disk while open, with unsaved writing: both versions are kept. The file is left as it is, the app's version is saved as `<name> (my edits YYYY-MM-DD).pdf`, and the session continues in the copy, undo list and all (spec §7.4). A file deleted or moved elsewhere is treated the same way.
  - [x] Changed on disk with nothing unsaved: the new version is opened in its place. Looked for at every save and each time the writing screen comes back in front (size and date first, so an unchanged file isn't read). Tried on the tablet over adb: a 124-page file swapped for a 125-page one while the screen slept showed 125 pages on waking.
  - [x] Crash replay onto a changed file: the writing is put on a copy of the file as it is now, never into the file (owner's decision, 2026-11-13: the version it was written on is gone by then, and keeping a private copy of every open PDF would cost a full copy after each save). Writing on a page the file no longer has can't be placed: it's kept aside and the owner is told how many changes. Journals already set aside as `.kept-…` are left alone; the tablet had none (checked 2026-11-13).
  - [x] A journal from a kill in the middle of adding or removing a page is never rescued: the journal notes the page change before the save ("renumbering"), and such a journal is set aside as before.
  - [x] A session whose only change is the redraw of old ink counts as no writing: the new version is opened, and no copy is made.
  - [x] A lasting notice when writing isn't reaching a file: the strip says "Not saved · tap" where the file's name is, and a tap says why. For a save that failed twice running, a full tablet (until the next good save), and a change that couldn't be written (until the file is closed).
  - [ ] **Owner check:** [notes/TESTING-conflicts.md](notes/TESTING-conflicts.md). The copy made while writing needs the pen, so it has only been run by the tests, not on the screen.

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
