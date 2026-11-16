# Writing Screen and Library Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the bugs the [2026-11-16 review](../review-2026-11-16.md) found in the writing screen and the library: the screen reopening the wrong file after it's rebuilt, a page left grey, ink saved beside the page, a rename that can replace another file, and the smaller ones.

**Architecture:** No new structure. Each task is a small change in the file that owns the bug, with a laptop test wherever the code is plain Kotlin. Two rules become small functions of their own so they can be tested on the laptop (`KeepOnPage`, the temporary file's name). What can only be seen on the tablet is checked there by log lines and gathered into one owner checklist at the end: a **hard stop**.

**Tech Stack:** As now. Nothing new.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` §4 (lost writing is the worst failure; the app never surprises), §6.1 (the library works on the owner's real files), §6.2 (the writing screen). Findings: `docs/superpowers/review-2026-11-16.md`; "review B1" below is a row there.

**Where this plan sits:** the second of three plans from the review. Run it after the [conflict copy fixes](2026-11-16-conflict-copy-fixes.md) are merged (both change `WritingActivity.show`), on a branch of its own from `main`.

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, `test/…` and `androidTest/…` likewise.

## Decisions for the owner (approve before Task 1)

1. **A selection or a paste stops at the page's edge.** Today a paste tap or a drag beside the page saves the items outside the page, where they can't be seen or picked up again. New rule: what's moved or pasted is pushed back just far enough to lie on the page. Something bigger than the page stays covering it.
2. **Plugging in a keyboard or page-turner, or the tablet switching to dark mode, no longer restarts the writing screen.** The screen simply carries on. A change of font size or display size still restarts it (rare, and the layout depends on them), and after this plan a restart comes back to the right file.
3. **Fast-ink goes off while another window is in front of the page** (the notification shade, a system question), as it does for the app's own menus. Built only if Task 4's check shows the pen marks the page through such a window, and only if the tablet's own floating button doesn't trigger it: a pause is a screen refresh.
4. **A snip pasted over handwriting hides it on screen too.** Today the screen shows the handwriting on top, while the file, the thumbnail and every other viewer show the snip on top. The screen will match the file. Pen ink over highlighter, and highlighter over pen ink, look as they do now.
5. **A file with a very long name can be saved.** Names of about 244 bytes and more (some 80 Chinese or 120 Cyrillic characters) open and take ink but can never be saved, because the temporary file's name is too long. Its temporary file and its "(my edits)" copy get a shortened name instead.

## Global Constraints

- "A failed or silent autosave" and lost writing are the worst failures (spec §4). The library never replaces, deletes or moves a file the owner didn't ask it to.
- A journal is never overwritten, and never deleted except with its own PDF (`FileOps`' class comment).
- Nothing here may refresh the screen more often than today (owner, 2026-11-09), or pause fast-ink where it isn't paused today, except decision 3.
- The Onyx SDK stays in `PenInput` and `PanelRefresh`.
- `doc/`, `storage/`, `library/` and `tools/` stay free of Android classes: they run in laptop tests.
- Readable code: small changes, plain names, comments that say why. The owner declined splitting `WritingActivity.kt`: change it as little as each fix needs.
- Toolchain: `source ~/.local/opt/android-env.sh`; `cd notes && ./gradlew :app:testDebugUnitTest -q`. Tablet tests with `installDebug` + `installDebugAndroidTest` + `adb shell am instrument -w -e notPackage com.fzizzle.epapernotes.check …`. Never `git add -A`; wait for a quiet tablet before installing. Library checks on a scratch folder only (`--es root`, with `shared_prefs/library.xml` backed up by `run-as` and restored). Release build back on the tablet at the end.
- Onyx fast-ink strokes can't be scripted over adb: what needs the pen goes into the owner checklist.

## Review Focus

1. **Android clears the app from memory while it's in the background, after the writing moved into a "(my edits)" copy.** Coming back shows the copy with all the writing, not the changed original (Task 1, tablet check with "Don't keep activities").
2. **A file whose name differs from another's only in how an accent is stored (a Mac's "é" against a PC's).** Renaming or moving one onto the other is refused; neither file is replaced (Task 6: `aNameTheDiskAlreadyHoldsIsNeverReplaced`).
3. **A paste tapped in the grey band beside a narrow page.** The pasted items lie wholly on the page (Task 3: `aBoxPastTheRightEdgeIsPushedBack`, and the tablet check).
4. **A selection scaled up larger than the page.** It still covers the page and can be picked up again; nothing throws (Task 3: `aBoxWiderThanThePageCoversIt`).
5. **A page of 1,500 strokes and no snips.** It draws as fast as before (Task 7: `SpeedCheck`'s page-draw line, before and after).

---

### Task 1: A rebuilt screen comes back to the right file (review B1, B2, B7; decision 2)

**Files:**
- Modify: `main/ui/WritingActivity.kt` (`onCreate`, new `onSaveInstanceState`, `offerRotation`, `askBeforeRemoving`), `main/AndroidManifest.xml` (both activities)

**Interfaces:**
- Produces: `private const val STATE_PATH = "path"` in `WritingActivity`'s companion. `onSaveInstanceState` puts the current `path` under it; `onCreate` reads `savedInstanceState?.getString(STATE_PATH) ?: intent.getStringExtra(EXTRA_PATH)`. The intent can't carry it: Android rebuilds a screen from the intent it was first started with.

- [ ] **Step 1: Save and restore the path** as in Interfaces, with a comment saying why (after "saved to a copy" the session writes into the copy; the first intent still names the original).
- [ ] **Step 2: Manifest.** Both activities: `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|navigation|uiMode"`, and the comment above each says what the new four are (a keyboard or clip-on page turner connecting, dark mode). `WritingActivity` needs no new code for them: nothing it draws depends on them.
- [ ] **Step 3: Questions on a closed screen.** `offerRotation` and `askBeforeRemoving` check `isDestroyed` beside `isFinishing`, as `layoutFailed` and `show` do.
- [ ] **Step 4: Check on the tablet** (debug build, scratch PDF, `library.xml` backed up):
  - `adb shell cmd uimode night yes`, then `no`, with a document open: the log shows no `leave: destroy`, and undo is still black after a stroke made before it.
  - `adb shell settings put global always_finish_activities 1` (Android then takes a screen down as soon as it's left, and rebuilds it from its saved state). Open the scratch PDF by the path hook, turn to page 3, press Home and come back: the log shows `leave: destroy`, then the same file opened again at page 3. This proves the path is saved and read back; the copy itself needs unsaved writing at the moment the file is replaced, which needs the pen, so it is in the owner checklist (Task 9). Put the setting back to 0.
- [ ] **Step 5: Commit.** `git commit -m "writing: a rebuilt screen opens the file it was writing into; a keyboard or dark mode no longer rebuilds it"`

### Task 2: A page that couldn't be drawn is asked for again (review B3)

**Files:**
- Modify: `main/ui/WritingActivity.kt` (`pages.requestRender`, `pages.requestRegionRender`, `storeContinuePicture`)

- [ ] **Step 1: Catch `Throwable`, not `Exception`,** in the three places a page picture is made on the worker, with one comment at the first: running out of memory for one picture must still reach `renderFailed`, or that page at that width is never asked for again and stays grey until the file is reopened (`layOutPages` already does this). Nothing else changes: `renderFailed` and `regionFailed` already clear what was waiting.
- [ ] **Step 2: Read `PageView.renderFailed`** and confirm it removes the `inFlight` entry and doesn't leave a `dropped` entry that would swallow the next good picture; if it leaves one, clear it there, with a line in `test/ui/PageBitmapsTest.kt` if the bookkeeping lives in `PageBitmaps`.
- [ ] **Step 3: Laptop tests pass. Commit.** `git commit -m "writing: a page whose picture ran out of memory is drawn again, not left grey"`

### Task 3: Nothing is saved beside the page (review B4; decision 1)

**Files:**
- Create: `main/tools/KeepOnPage.kt`, `test/tools/KeepOnPageTest.kt`
- Modify: `main/ui/WritingActivity.kt` (`selectionListener.moved`, `selectionListener.pasteAt`)

**Interfaces:**
- Produces: `object KeepOnPage { fun shift(box: Box, pageWidth: Float, pageHeight: Float): Pt }` — how far to move `box` (display points, the page from 0,0 to its width and height) so it lies on the page. Along each direction on its own: a box that fits is moved the least that puts it between 0 and the page's size; a box bigger than the page is moved the least that makes it cover the page from edge to edge. `Pt(0f, 0f)` when nothing is needed.
- Produces: `fun onPage(items: List<Item>, pdfToDisplay: Affine, pageWidth: Float, pageHeight: Float): List<Item>` in the same file: the items' corners in display space as `duplicateOf` works them out, `shift` of that box, and the items moved by it (`transformed` with the shift taken back into PDF space, scale 1). The same list when the shift is zero.

- [ ] **Step 1: Write the failing tests** in `KeepOnPageTest`, on a 600 by 800 page: `aBoxOnThePageIsLeftAlone` (shift 0,0); `aBoxPastTheRightEdgeIsPushedBack` (`Box(580f, 100f, 640f, 150f)` gives `Pt(-40f, 0f)`); `aBoxAboveThePageComesDown` (`Box(10f, -30f, 60f, 20f)` gives `Pt(0f, 30f)`); `aBoxWhollyBesideThePageComesOntoIt` (`Box(700f, 900f, 750f, 950f)` gives `Pt(-150f, -150f)`); `aBoxWiderThanThePageCoversIt` (`Box(50f, 0f, 750f, 100f)` gives `Pt(-50f, 0f)`; `Box(-200f, 0f, 500f, 100f)` gives `Pt(100f, 0f)`); `onPageMovesInkAndSnipsTogether` (an ink item and an image item past the edge, on a page turned a quarter: both boxes end on the page, their distance apart unchanged).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*KeepOnPageTest*' -q`: fail. **Step 3: Implement.** **Step 4: Run**: pass.
- [ ] **Step 5: Use it.** In `moved`, `after` goes through `onPage(after, pdfToDisplay[page], pageSizes[page].w, pageSizes[page].h)` before it's recorded. In `pasteAt`, the placed items go through it the same way. The selection is then set on what was recorded, as now, so the box on screen is where the items really are.
- [ ] **Step 6: Check on the tablet** over adb where it can be: with something copied (a selection made by a traced lasso stroke with fast-ink off), arm Paste and `adb shell input tap` in the grey band beside the page. The log's `pasted` line follows, and a screenshot shows the items against the page's edge with their selection box on the page.
- [ ] **Step 7: Commit.** `git commit -m "writing: a selection or a paste stops at the page's edge"`

### Task 4: Does the pen mark the page through another window? (review B5; decision 3)

**Files:**
- Modify: `main/ui/WritingActivity.kt` (new `onWindowFocusChanged`)

- [ ] **Step 1: Log only.** `onWindowFocusChanged(hasFocus)` writes `Log.i(TAG, "focus=$hasFocus fast-ink=${pen.drawsInk} dialog=$dialogOpen")` and calls super. Install the debug build.
- [ ] **Step 2: Find out when it fires,** from the log, for each of: the notification shade pulled down (`adb shell cmd statusbar expand-notifications`, then `collapse`), the tablet's floating navigation button tapped and its menu opened, a volume key (the app takes those), a toast, the app's own ⋯ menu and Go to page, the power menu. Into `docs/superpowers/findings-log.md` as a table: what was done, did focus go, was fast-ink on at that moment.
- [ ] **Step 3: Owner check, with the pen** (it can't be scripted): pull the shade down and write on it. Does a line follow the pen there, and is it on the page when the shade is closed? Record the answer in the findings log.
- [ ] **Step 4: Build decision 3 only if** Step 3 says yes and Step 2 shows the floating button doesn't take focus. Losing focus with no dialog of the app's own open: if the pen isn't writing, `pausePenAndRedraw()`; if it is, the pause waits for the lift as a tool change does (`pauseWhenLifted`). Gaining focus while `resumed`: `handler.removeCallbacks(resumeFastInk); handler.postDelayed(resumeFastInk, SHOW_PAUSE_MS)`; `resumeFastInk` already asks `fastInkWanted()`. Otherwise leave the log line in, say so in the review's row B5, and close it.
- [ ] **Step 5: Commit.** `git commit -m "writing: window focus is logged"` or, if built, `"writing: fast-ink goes off while another window is in front of the page"`

### Task 5: Two windows open at once (review B6)

**Files:**
- Modify: `main/ui/WritingActivity.kt` (`dialogOpen` and the nine places that set it)

**Interfaces:**
- Produces: `private var windowsOpen = 0` and `private val dialogOpen get() = windowsOpen > 0`. Each place that set `dialogOpen = true` does `windowsOpen++`; each dismiss listener does `windowsOpen = (windowsOpen - 1).coerceAtLeast(0)` before it posts `resumeFastInk`.

- [ ] **Step 1: Make the change,** with one comment at the field: the damaged-file question can come from the session while a menu or the page pad is open, and the first of the two to close must not turn fast-ink back on under the other. Every reader of `dialogOpen` stays as it is.
- [ ] **Step 2: Check on the tablet** that one window still behaves: open ⋯ and close it; the log shows `resume fast-ink` once, about `SHOW_PAUSE_MS` after.
- [ ] **Step 3: Commit.** `git commit -m "writing: fast-ink waits for every open window to close, not the first"`

### Task 6: The library never replaces a file, and a moved file never takes on another's writing (review C1, C2, C3)

**Files:**
- Modify: `main/library/FileOps.kt` (`carry`, `moveKeepingCase`, `StrandedRename.recover`, the constructor), `main/library/FolderWatch.kt` (`watchChildren`)
- Test: `test/library/FileOpsTest.kt`, `androidTest/library/FolderWatchTest.kt`

**Interfaces:**
- Produces: a constructor parameter on `FileOps`, `private val onDisk: (File) -> Boolean = File::exists`, with a comment: the disk decides whether two names are the same file (it may ignore case, or how an accent is stored), not a comparison of text. Tests replace it.
- Produces: in `carry`, after `waitForSessions` and before the move: when `onDisk(to)` and `to` isn't `from` itself (`Files.isSameFile` on the two, caught as false when it throws), `Result.Refused("There's already a '${to.name}' ${where}.")` with the wording `rename` and `move` use today. A rename that the disk says is the same file (case only, or the accent's form only) goes by the hidden name, as a case-only rename does now: `moveKeepingCase` takes that way when the names are equal ignoring case **or** the disk says they are the same file.
- Produces: in `carry`, for each PDF moved: a journal already at its new path that belongs to no file there (`liveJournalFilesOf(moved)` before `moveJournals` runs) is set aside under `keptName`, whether or not the moved PDF has a journal of its own. `freshAttempt` already does this.

- [ ] **Step 1: Write the failing laptop tests** in `FileOpsTest`.
  - `aNameTheDiskAlreadyHoldsIsNeverReplaced`: `FileOps(…, onDisk = { it.name == "Résumé.pdf" || it.exists() }, moveOnDisk = { _, _ -> moved = true; true })`; `rename(pdf("Other.pdf"), "Résumé")`: `Refused`, `moved` false.
  - `aFileArrivingWhileWaitingForASaveIsNotReplaced`: an open session on the file being renamed, closed by a thread that first writes a file at the new name (as `renameWaitsForTheOpenSession` sets a session up): `Refused`; the arrived file's text is unchanged.
  - `aMoveOntoANameTheDiskHoldsIsRefused`: the same for `move` into a folder.
  - `aLeftoverJournalAtTheNewNameIsSetAsideForAFileWithNone`: a journal with one stroke under `Week 2.pdf`'s path and no such file; `Week 3.pdf` with no journal; `rename(Week 3, "Week 2")`: `Done`; no journal at `Week 2.pdf`'s key; one `.kept-` file holding the stroke.
  - `aStrandedFileIsNotPutBackOverAFileTheDiskHolds`: `StrandedRename.recover(folder, onDisk)` leaves a stranded file alone when `onDisk` says its name is there. (`recover` gains the same parameter, defaulting to `File::exists`.)
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*FileOpsTest*' -q`: fail. **Step 3: Implement.** **Step 4: Run**: all of `FileOpsTest` passes, the case-only rename tests included.
- [ ] **Step 5: The watch.** Failing tablet test in `FolderWatchTest`: `theFolderOnShowIsNotAlsoWatchedAsAChild` — `watch(folder)`, `watchChildren(listOf(folder, child))`, `watchChildren(listOf(child))`, then a file created in `folder` still calls `onChange` within a second. Implement: `watchChildren` leaves out `watching`. Run it.
- [ ] **Step 6: Commit.** `git commit -m "library: a rename or move never replaces a file the disk already holds, and a moved file never inherits a journal"`

### Task 7: A snip over handwriting hides it on screen too (review D2; decision 4)

**Files:**
- Modify: `main/pdf/PdfFile.kt` (`drawWithSolidInk`)
- Test: `androidTest/pdf/PdfFileItemsTest.kt`

- [ ] **Step 1: Measure before:** `check/SpeedCheck`'s page-draw time for the 1,500-stroke page (137 ms on 2026-11-10), three runs.
- [ ] **Step 2: Write the failing tablet tests.** `aSnipPastedOverInkHidesItOnScreenToo`: on the blank test PDF, a black pen stroke across the middle, then an image item (a plain white PNG) whose box covers it; `render(0, 600)` at the stroke's middle is white, as `render(0, 600, smoothInk = true)` is. `inkOverASnipStillShows`: the snip first, then the stroke: black. `highlighterOverPenInkLooksAsBefore`: pen then highlighter over it: the pixel is as black as with the pen alone.
- [ ] **Step 3: Implement.** The annotations are drawn in runs, in the page's own order. Only an annotation that isn't ink (`type != PDFAnnotation.TYPE_INK`) and comes after solid ink ends a run: solid ink gathered so far is drawn (`drawSolid`), then a draw device takes the next stretch. A page with no such annotation (the usual one: pen ink only, or snips under the ink) is drawn exactly as today, in one device pass and one `drawSolid`, and the comment says that this is the case that must stay fast. Highlighter strokes stay in the device pass they're in now, so ink against ink looks as before. Form fields last, as now.
- [ ] **Step 4: Run** `PdfFileItemsTest`, `PdfFileStrokesTest` and `PdfFileOpenRenderRotateTest` on the tablet. **Measure after** as in Step 1: within the run-to-run spread of before, or the change is reverted and the row stays open.
- [ ] **Step 5: Commit.** `git commit -m "writing: a snip pasted over ink hides it on screen, as it does in the file"`

### Task 8: Very long file names can be saved (review D1; decision 5)

**Files:**
- Modify: `main/storage/SafeSave.kt` (`tempFileFor`), `main/storage/CopyNames.kt` (`myEdits`, `attempt`)
- Test: `test/storage/SafeSaveTest.kt`, a new `test/storage/CopyNamesTest.kt` if none exists

**Interfaces:**
- Produces: `const val MAX_NAME_BYTES = 255` in `SafeSave`. `tempFileFor(target)`: as now when `".${target.name}.pnotes-tmp"` is at most that many bytes in UTF-8; otherwise `".<first 32 hex digits of the sha256 of the name>.pnotes-tmp"`. `isTempFile` and `cleanStale` match both.
- Produces: `CopyNames.myEdits` and `attempt` cut the original's name, whole characters from its end, until the new name with its bracket and `.pdf` and then its own temporary name both fit.

- [ ] **Step 1: Write the failing tests.** `aVeryLongNameStillHasATempFileThatFits` (a name of 250 bytes of "é": the temp name is at most 255 bytes, starts with a dot, `isTempFile` is true, and two different long names give two different temp names); `aVeryLongNameIsStillSaved` (`replace` on it writes the file); `myEditsOfAVeryLongNameFits` (name at most 255 bytes, ends ` (my edits 2026-11-09).pdf`, starts with the original's first characters, not cut inside a character); `anAttemptOfAVeryLongNameFits`.
- [ ] **Step 2: Run**: fail. **Step 3: Implement.** **Step 4: Run**: pass, with all of `SafeSaveTest` and `FileOpsTest`.
- [ ] **Step 5: Commit.** `git commit -m "storage: a file with a very long name can be saved and copied"`

### Task 9: Results, checklist, owner session (hard stop)

- [ ] **Step 1:** All laptop tests and all tablet tests.
- [ ] **Step 2:** `notes/TESTING-review-2.md`: after a "(my edits)" copy is made (as in C1 of `notes/TESTING-conflicts.md`), leave Jot for another app for a while and come back (is the copy still the file on screen, with all the writing?); paste beside the page and drag a selection past each edge (does it stop at the edge?); scale a selection up past the page; a snip pasted over writing (hidden on screen?) and writing over a snip (shows?); writing on the shade (Task 4, Step 3); a Bluetooth keyboard or page-turner connected while a document is open (does the page stay, is undo still there?); rename a file onto an existing name in another case and with an accent (refused?); anything that refreshes more than before.
- [ ] **Step 3:** Spec §6.2: a selection or paste stops at the page's edge; a snip over ink hides it on screen. `TODO.md`: the items under "Review (2026-11-16)" ticked, with Task 4's outcome. The review's rows marked fixed.
- [ ] **Step 4:** Release build on the tablet. **Stop for the owner's session.**
