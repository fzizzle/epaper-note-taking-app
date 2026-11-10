# Review Bug Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the bugs found in the [2026-11-10 review](../review-2026-11-10.md), sections 1 and 2: the six to fix first, then the smaller ones that are cheap and certain.

**Architecture:** No new structure. Each task is a small change in the file that owns the bug, with a laptop test wherever the code is plain Kotlin. The pen-timing fixes in `WritingActivity` can't be unit-tested, so they are checked on the tablet by log lines and gathered into one owner checklist at the end: a **hard stop**.

**Tech Stack:** As in plan 4 (Kotlin 2.0.21, AGP 8.7.3, MuPDF `fitz:1.28.5`, Onyx `onyxsdk-pen:1.5.9`, JUnit 4.13.2). Nothing new.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` §4 (lost writing is the worst failure), §6.2 (a resting palm neither draws nor moves the page), §7.2–§7.3 (autosave, crash safety). Findings: `docs/superpowers/review-2026-11-10.md`; a number like "review 1.3" below is a section there.

**Where this plan sits:** the first of four plans from the review. The [speed plan](2026-11-10-review-speed.md) builds on Task 5 here (`SafeSave.replace`'s new hook). Run this one first.

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, `test/…` and `androidTest/…` likewise.

## Decisions for the owner (approve before Task 1)

1. **Old strokes are drawn again, once per file.** Strokes saved so far have the small holes at their ends (review 1.1). When a file is opened, its old strokes are redrawn in the background and saved, the same way mirrored ink was fixed. About 21 ms a stroke, once. Without this, only new strokes are right.
2. **A stroke is kept even when the journal can't be written.** Today's rule is "journal first, or nothing". With a full tablet that rule loses the stroke and crashes. New rule: keep the stroke in memory, save the PDF at once, and show "Storage is full: writing can't be kept safe until there's room." (review 1.3).
3. **A file whose date changed but whose contents didn't counts as unchanged** (review 2.2). Only size and fingerprint decide.
4. **Fingers are ignored while the pen is writing** (review 2.7). A palm that lands mid-stroke no longer pauses fast-ink or moves a zoomed page. Fingers work again as soon as the pen lifts.
5. **The pen can't write beside the page** (review 2.6). Its area becomes the page itself rather than the whole view.
6. **Thumbnails are at most 450 px tall** (review 2.3). A very tall first page gets a narrower thumbnail, centred in its tile.

## Global Constraints

- "Every stroke must survive a crash, kill, or power loss from the moment the pen lifts" (spec §7.3). "A failed or silent autosave" is the worst failure (spec §4). No fix here may weaken either.
- The ordering rule in `DocumentSession` stays: a change is journaled, added to `unsaved` and posted to the worker inside one `synchronized(lock)` block.
- fitz 1.28.5: never `PDFObject.push(PDFObject)` or `addObject` (refcount bug). `put` with a number, name or string is fine.
- Only MuPDF code lives in `pdf/`. `doc/` and `storage/` stay free of Android classes (they run in laptop tests).
- Readable code: small changes, plain names, comments that say *why* (spec §5). The owner declined splitting `WritingActivity.kt`: change it as little as each fix needs.
- No libraries, no new threads, nothing that redraws the screen more often (owner, 2026-11-09).
- Toolchain: `source ~/.local/opt/android-env.sh`; Gradle in `notes/`. Laptop: `./gradlew :app:testDebugUnitTest`. Tablet: `./gradlew :app:connectedDebugAndroidTest` uninstalls the app; restore with `adb shell appops set --uid com.fzizzle.epapernotes MANAGE_EXTERNAL_STORAGE allow`. Another session may be using the checkout and the tablet: build in this worktree only, and wait for a quiet minute (`adb logcat -d -s Notes | tail`) before installing.
- Onyx fast-ink strokes can't be scripted over adb. `adb shell input stylus motionevent DOWN|MOVE|UP x y` only reaches the app while fast-ink is paused.

## Review Focus

1. **A full tablet in the middle of a lecture.** Strokes keep landing in the PDF, one message is shown, and nothing crashes (Task 3: `strokeIsKeptWhenTheJournalCantBeWritten`).
2. **The app swiped away during the leave-save.** Reopening shows no "unsaved writing was kept" message and no doubled strokes (Task 5: `killedAfterTheSwapReplaysOnlyTheRest`).
3. **A sync app that resets file dates.** Unsaved writing from a crash still comes back (Task 5: `journalReplaysWhenOnlyTheDateChanged`).
4. **A highlighter stroke with a tight zig-zag.** Solid colour at each end and each corner, never darker where pieces overlap (Task 1: `highlighterIsSolidJustInsideItsEnd`, and the existing `strokeIsFilledInOneGo`).
5. **A zoomed landscape slide moved sideways with one finger, then written on.** The ink lands under the pen and the page doesn't turn (Task 2 tablet check; Task 7: `shortZoomedPageNeverReportsPastTheEdge`).

---

### Task 1: Stroke ends and corners are filled (review 1.1)

**Files:**
- Modify: `main/ink/StrokeOutline.kt:122-130` (`circle`), `main/pdf/ItemAnnotations.kt:85-148` (`addInk`, `drawInk`, `isFaulty`)
- Test: `test/ink/StrokeOutlineTest.kt`, `androidTest/pdf/PdfFileStrokesTest.kt`

**Interfaces:**
- Produces: `ItemAnnotations.DRAWING_VERSION = 2` and a private annotation key next to the stroke data (name it in the style of `StrokeCodec.KEY`, for example `PNotesDrawing`). `drawInk` writes it with `dict.put(key, DRAWING_VERSION)`. `isFaulty` is also true for one of the app's ink strokes whose key is missing or below 2.

- [ ] **Step 1: Write the failing laptop test** `capsTurnTheSameWayAsTheBody` in `StrokeOutlineTest`: for a three-point highlighter stroke with a sharp corner, split `contentStream` into subpaths (each ends in `h`), take every `m`, `l` and `c` end point, compute each subpath's signed area with the shoelace sum, and assert all have the same sign.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*StrokeOutlineTest*'` — expect `capsTurnTheSameWayAsTheBody` to fail (body negative, circles positive, or the reverse).
- [ ] **Step 3: Reverse `circle`.** Keep the start point `(cx + r, cy)` and go round the other way: next `(cx, cy − r)`, then `(cx − r, cy)`, then `(cx, cy + r)`. Update the comment at line 69 to say why direction matters with a non-zero fill.
- [ ] **Step 4: Run the laptop tests** — all of `StrokeOutlineTest` passes.
- [ ] **Step 5: Write the tablet test** `highlighterIsSolidJustInsideItsEnd` in `PdfFileStrokesTest`: a horizontal highlighter stroke 200 pt long on the blank test PDF; render the page; the pixel half a radius inside each end, on the centre line, satisfies the file's own `isYellow`. Also `oldDrawingIsRedrawnOnce`: write a stroke, remove the new key from its annotation with `openRaw`, save, reopen; `redrawFaultyInk(0, 25)` returns `redrawn == 1`, and a second call returns 0.
- [ ] **Step 6: Add the drawing-version key** as in Interfaces. Extend `isFaulty`; leave the `/Matrix` rule as it is.
- [ ] **Step 7: Run the tablet tests** for `PdfFileStrokesTest` (quiet tablet first) — all pass, including `faultyInkDrawingIsRedrawnOnce` and `inkWrittenTheOldWayIsRedrawnAndSavedOnce`.
- [ ] **Step 8: Commit.** `git commit -m "notes: stroke ends and corners are filled, and old strokes are drawn again"`

### Task 2: The pen follows a page moved with one finger (review 1.2)

**Files:**
- Modify: `main/ui/WritingActivity.kt:316-329` (`pages.onFingerGestureEnd`)

- [ ] **Step 1: Call `publishPenMapping()` first thing in `onFingerGestureEnd`,** in both branches, with a comment: a one-finger move changes where the page is without a viewport callback, and fast-ink is off until this point anyway. Don't add `onViewportChanged()` to `PageView.onScroll`: it would call the Onyx SDK on every finger movement.
- [ ] **Step 2: Add a log line** in `publishPenMapping`: `Log.i(TAG, "pen mapping page=${v.page} zoom=${v.zoom}")`, so the tablet check can see it.
- [ ] **Step 3: Check on the tablet.** Open the test copy (`adb shell am start -n com.fzizzle.epapernotes/.ui.LibraryActivity --es path '/sdcard/Documents/E-paper Notes test/PWT test copy.pdf'`), pinch in, move the page with one finger, and confirm `adb logcat -d -s Notes | grep "pen mapping"` shows a line after "fingers up". The owner checklist (Task 10) covers the writing itself.
- [ ] **Step 4: Commit.** `git commit -m "notes: the pen follows a zoomed page moved with one finger"`

### Task 3: A journal that can't be written no longer loses the stroke (review 1.3)

**Files:**
- Modify: `main/doc/DocumentSession.kt:270-282` (`commit`), `:480-488` (`restartJournal`); `main/doc/SessionEvent.kt`; `main/ui/WritingActivity.kt:898-920` (`show`)
- Test: `test/doc/DocumentSessionTest.kt`

**Interfaces:**
- Produces: `data object JournalFailed : SessionEvent` — "the journal couldn't be written; the change is kept in memory and saved to the PDF at once". Sent at most once until a journal write next succeeds.

- [ ] **Step 1: Write the failing tests** in `DocumentSessionTest`:
  - `strokeIsKeptWhenTheJournalCantBeWritten`: start a session, then replace the journal file with a folder of the same name. `addStroke` returns without throwing; events hold exactly one `JournalFailed` after three strokes; after the scheduler runs, the fake PDF's saved items hold all three strokes.
  - `saveStillCountsWhenTheJournalCantBeRewritten`: same setup; after the save, `Saved` is sent, no `SaveFailed`, and a second `saveNow()` writes nothing (the fake's save count stays the same).
- [ ] **Step 2: Run and confirm they fail** (the first with an `IOException` out of `addStroke`).
- [ ] **Step 3: Implement.**
  - In `commit`: catch `IOException` from `journal.append`. On failure set a `journalBroken` flag (guarded by `lock`), send `JournalFailed` if the flag was clear, carry on with `unsaved.add` and the history, and after the usual post also `scheduler.post { save() }`. Rewrite the comment above `commit` to state the new rule and why.
  - In `restartJournal`: do the bookkeeping (`unsaved`, `applied`) whether or not `journal.rewrite` throws. A rewrite that works clears `journalBroken`; one that throws `IOException` sets it and sends `JournalFailed` if it was clear.
  - In `WritingActivity.show`: `SessionEvent.JournalFailed -> toast("Storage is full: writing can't be kept safe until there's room.")`.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest --tests '*DocumentSessionTest*'` — all pass.
- [ ] **Step 5: Commit.** `git commit -m "notes: a stroke is kept and saved even when the journal can't be written"`

### Task 4: Nothing fails silently on the document's worker (review 1.4)

**Files:**
- Modify: `main/doc/Scheduler.kt:25-48`, `main/doc/DocumentSession.kt:89-100` (`start`), `:450-477` (`save`), `main/ui/ThumbnailLoader.kt:133-142`
- Test: `test/doc/WorkerSchedulerTest.kt` (new), `test/doc/DocumentSessionTest.kt`, `test/doc/Fakes.kt`

**Interfaces:**
- Produces: `class WorkerScheduler(name: String = "document", private val onError: (Throwable) -> Unit = { System.err.println("worker task failed: $it") })`. Every task given to `post` or `postDelayed` runs inside `try { task() } catch (t: Throwable) { onError(t) }`.
- `FakePdfPages` gains `var failSaveWith: Throwable? = null`, thrown by `saveTo` when set.

- [ ] **Step 1: Write the failing tests.**
  - `WorkerSchedulerTest.aTaskThatThrowsIsReportedAndTheNextStillRuns`: post a task that throws `IllegalStateException("x")`, then one that counts down a latch; `onError` got the exception and the latch opens within 1 s. The same for `postDelayed("k", 10)`.
  - `DocumentSessionTest.openThatBlowsUpReportsOpenFailed`: the `open` lambda throws `OutOfMemoryError()`; events hold one `OpenFailed`, and `addStroke` afterwards returns (the journal latch opened).
  - `DocumentSessionTest.saveThatRunsOutOfMemoryIsReportedAndTriedAgain`: `failSaveWith = OutOfMemoryError()`; after a stroke and `saveNow()`, events hold `SaveFailed`; clear the failure, run the delayed tasks, and `Saved` follows.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.** Wrap tasks in `WorkerScheduler`. In `start`, catch `Throwable` around `openOnWorker()` and send `OpenFailed("${file.name} couldn't be opened.")`. In `save`, catch `Throwable` instead of `Exception`. In `ThumbnailLoader.load`, catch `Throwable` so a PDF too big to draw becomes a grey tile instead of ending the thread.
- [ ] **Step 4: Run all laptop tests** — pass.
- [ ] **Step 5: Commit.** `git commit -m "notes: failures on the document's worker are always reported"`

### Task 5: The journal knows a save is under way, and dates alone don't count as a change (review 2.1, 2.2, 2.13)

**Files:**
- Modify: `main/storage/FileIdentity.kt`, `main/storage/SafeSave.kt:33-49` (`replace`), `main/doc/ChangeJournal.kt`, `main/doc/DocumentSession.kt:102-156` (`openOnWorker`), `:450-477` (`save`)
- Test: `test/storage/FileIdentityTest.kt`, `test/storage/SafeSaveTest.kt`, `test/doc/ChangeJournalTest.kt`, `test/doc/DocumentSessionTest.kt`

**Interfaces:**
- Produces:
  - `fun FileIdentity.sameContentAs(other: FileIdentity?): Boolean` — true when size and sha256 match; the modified time is ignored.
  - `SafeSave.replace(target: File, beforeSwap: (File) -> Unit = {}, write: (File) -> Unit)` — `beforeSwap` gets the finished, flushed temporary file just before it is moved over `target`. If it throws, the swap doesn't happen and the temporary file is removed.
  - `data class PendingSave(val result: FileIdentity, val count: Int)`; `JournalContents` gains `val pendingSave: PendingSave? = null`; `fun ChangeJournal.markSaving(result: FileIdentity, count: Int)` appends the line `saving <size> <modified> <sha256> <count>` and syncs. `read()` returns the last such line as `pendingSave`. Older builds drop the line, since `ChangeCodec.decode` returns null for it.
  - `ChangeJournal.rewrite` also flushes the journal folder after the move, the way `SafeSave.syncFolder` does.

- [ ] **Step 1: Write the failing tests.**
  - `FileIdentityTest.aChangedDateAloneIsTheSameContent`; `aChangedByteIsNot`.
  - `SafeSaveTest.beforeSwapSeesTheNewVersionAndTheOldIsStillInPlace`; `aThrowingBeforeSwapLeavesTheOriginal`.
  - `ChangeJournalTest.savingLineIsReadBack` (base, two changes, `markSaving(id, 2)`, one more change → three changes, `pendingSave == PendingSave(id, 2)`); `aCutShortSavingLineIsIgnored`.
  - `DocumentSessionTest.killedAfterTheSwapReplaysOnlyTheRest`: build the state by hand — a journal with base A, three strokes and `saving B 2`, and the file on disk with identity B holding the first two. A new session sends `Replayed(1)`, no `EarlierWritingKept`, and the PDF ends with three strokes, none doubled.
  - `DocumentSessionTest.journalReplaysWhenOnlyTheDateChanged`: journal with a stroke; `setLastModified` on the file; a new session sends `Replayed(1)`.
  - `DocumentSessionTest.aTouchedFileIsStillSavedInto`: open, `setLastModified`, add a stroke, save: `Saved`, no `ChangedOnDisk`.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
  - `save`: compare with `sameContentAs` instead of `!=`. Call `SafeSave.replace(file, beforeSwap = { temp -> next = FileIdentity.of(temp); synchronized(lock) { journal.markSaving(next, inThisSave) } }) { p.saveTo(it) }`, then `known = next` (no second read of the file). A `markSaving` that throws `IOException` must not stop the save: catch it there and treat it like Task 3's journal failure.
  - `openOnWorker`: three cases, in this order — `old.base.sameContentAs(identity)`: as today. `old.pendingSave?.result?.sameContentAs(identity) == true`: `journal.rewrite(identity, old.changes.drop(count))` and replay those. Otherwise: set aside, as today.
- [ ] **Step 4: Run all laptop tests** — pass.
- [ ] **Step 5: Commit.** `git commit -m "notes: a kill mid-save or a changed date no longer sets writing aside"`

### Task 6: The "damaged file" question, and after "save to a copy" (review 1.6, 2.4)

**Files:**
- Modify: `main/ui/WritingActivity.kt:898-931` (`show`, `askAboutRepair`)

- [ ] **Step 1: In `show`,** return at once when `isFinishing || isDestroyed`, except that `OpenFailed` still toasts with the application context.
- [ ] **Step 2: In `askAboutRepair`,** follow `askForPage`: build with `create()`, set `dialogOpen = true`, `pen.pause()`, and in the dismiss listener clear `dialogOpen` and `handler.postDelayed(resumeFastInk, SHOW_PAUSE_MS)`.
- [ ] **Step 3: On `SavedToCopy`,** set `path = event.copy.path`, store the page with `lastPages.set(path, page)`, and set `LibraryPrefs(this).lastOpened` to the copy, so "Continue" and the remembered page follow the writing.
- [ ] **Step 4: Check on the tablet** with a damaged test PDF in a scratch folder (truncate a copy of the test file by a few hundred bytes): the question shows, pen taps on it leave no ink, and "Save my writing to a copy" then leaving shows the copy under "Continue". Remove the scratch folder.
- [ ] **Step 5: Commit.** `git commit -m "notes: the damaged-file question pauses the pen, and a copy is what's continued"`

### Task 7: A short zoomed page doesn't turn, and the pen stays on the page (review 2.5, 2.6)

**Files:**
- Modify: `main/ui/Viewport.kt:73-100` (`panBy`), `main/ui/WritingActivity.kt:267-273`, `:368-370`
- Test: `test/ui/ViewportTest.kt`

**Interfaces:**
- Produces: `fun Viewport.pageBoundsPx(): FloatArray` returning `[left, top, right, bottom]` of the page on screen, clipped to the view. A plain array rather than Android's `RectF`, so it can be tested on the laptop.

- [ ] **Step 1: Write the failing tests** in `ViewportTest`: `shortZoomedPageNeverReportsPastTheEdge` (a 960×540 pt page in a 1860×2300 view at zoom 1.5: `panBy(0f, 400f)` and `panBy(0f, -400f)` both return 0); `tallZoomedPageStillReportsPastTheEdge` (the existing behaviour, pinned); `pageBoundsAreClippedToTheView`.
- [ ] **Step 2: Run and confirm the first and third fail.**
- [ ] **Step 3: Implement.** `panBy` returns 0 unless `pageHeightPx() > viewHeightPx`. In `WritingActivity`, both `pen.setLimit(...)` and `pen.on(...)` get the rectangle from `pageBoundsPx()`, rounded inwards.
- [ ] **Step 4: Run** `ViewportTest` — pass.
- [ ] **Step 5: Commit.** `git commit -m "notes: a short zoomed page doesn't turn, and the pen can't write beside the page"`

### Task 8: Pen switch-on, switch-off and palm rules (review 1.5, 2.7, 2.8, 2.9)

These four are marked "Likely" in the review. Each step that changes behaviour starts by looking for the fault in the logs, and is dropped (with a note in the commit) if the tablet shows it can't happen.

**Files:**
- Modify: `main/ui/WritingActivity.kt` (`showWithoutFastInk`, `resumeFastInk`, `onFingerGestureStart`, the first-layout callback at `:275-279`, the two dismiss listeners at `:723-726` and `:755-758`, `onResume`, `onPause`, `onDestroy`), `main/ui/PenInput.kt:65-77`, `main/ui/PageView.kt:344-371`

**Interfaces:**
- Produces: `var PageView.fingersAllowed: () -> Boolean = { true }`. When it returns false on a finger's `ACTION_DOWN`, the whole gesture up to its `ACTION_UP` or `ACTION_CANCEL` is swallowed: no `onFingerGestureStart`, no scroll, no scale, no tap.

- [ ] **Step 1: Dialogs (2.9).** Both dismiss listeners use `handler.removeCallbacks(resumeFastInk); handler.postDelayed(resumeFastInk, SHOW_PAUSE_MS)` instead of `resumeFastInk.run()`.
- [ ] **Step 2: Switch-on only while on screen (2.8).** Add `private var resumed = false`, set in `onResume` and cleared first thing in `onPause`. The first-layout callback calls `penOn()` only when `resumed`. `onDestroy` calls `pen.off()` (guard with `::pen.isInitialized`).
- [ ] **Step 3: Palm (2.7).** `pages.fingersAllowed = { !pen.isWriting && !screenPenDown }`. `PenInput.pause()` and `off()` set `isWriting = false`, with a comment: Onyx may send no end callback for a stroke cut by the pause.
- [ ] **Step 4: A stroke begun during a show (1.5).** First add a log line in `resumeFastInk` when it resumes within 150 ms of a pen `ACTION_DOWN` seen in `PageView.dispatchTouchEvent`, install, and ask the owner to erase a word and keep writing at once, ten times. If the line never appears, stop here. If it does: in `showWithoutFastInk`, when `toolState.fastInkOn()`, set `pages.penHandler = screenPen` and `screenHasPen = true` for the pause (`resumeFastInk` already hands the pen back and already waits while `screenPenDown`), and confirm in `SelectionLayer.onPen` that a stroke traced with no button and the pen or highlighter chosen is recorded as ink through `write`; add that path if it isn't there.
- [ ] **Step 5: Install and read the logs** after a short writing session with page turns, an erase, both dialogs and a palm on the screen: no "show skipped" left pending, and "resume fast-ink" follows every pause.
- [ ] **Step 6: Commit.** `git commit -m "notes: the pen's on, off and palm rules have no gaps"`

### Task 9: Tall first pages get a capped thumbnail (review 2.3)

**Files:**
- Modify: `main/ui/ThumbnailLoader.kt:163-178` (`render`), `main/library/ThumbnailCache.kt` (the constant), `main/ui/LibraryTiles.kt:202-260` (only if a narrower picture isn't centred already)
- Test: `androidTest/ui/ThumbnailLoaderTest.kt`

**Interfaces:**
- Produces: `const val THUMB_MAX_HEIGHT_PX = 450` beside `THUMB_WIDTH_PX`.

- [ ] **Step 1: Write the failing tablet test** `tallFirstPageIsCapped`: a one-page PDF 300 pt wide and 6000 pt tall (add a size parameter to the test PDF helper if it has none); the bitmap delivered is at most 450 px tall and at least 20 px wide.
- [ ] **Step 2: Implement.** In `render`, read `displaySize(0)` from the opened file and render at width `min(THUMB_WIDTH_PX, round(THUMB_MAX_HEIGHT_PX * w / h)).coerceAtLeast(20)`.
- [ ] **Step 3: Run the tablet test** — pass; check a normal slide's tile looks as before.
- [ ] **Step 4: Commit.** `git commit -m "notes: a very tall first page gets a small thumbnail"`

### Task 10: Owner checklist (hard stop)

**Files:**
- Create: `notes/TESTING-review-fixes.md`

- [ ] **Step 1: Write the checklist,** in the style of `notes/TESTING-library.md`, one line each:
  1. Highlight a line with a zig-zag: no gaps at the ends or corners. Open an old file: its highlights lose their gaps within a few seconds.
  2. Zoom in, move the page with one finger, write: the ink is under the pen. Leave and reopen: still there.
  3. Zoom a landscape slide a little and drag it up and down: the page doesn't turn.
  4. Rest a palm on the screen while writing: the stroke isn't cut and the page doesn't move.
  5. Erase a word and carry on writing straight away, several times: no stroke goes missing.
  6. Open "Go to page" and the page menu and close each: the screen is clean and the pen writes at once.
  7. Try to write in the margin beside the page: nothing appears.
  8. Leave a document, swipe the app away at once, reopen: no "unsaved writing was kept" message.
- [ ] **Step 2: Commit, then stop.** The owner runs the checklist; results go in `docs/superpowers/review-fixes-results.md`.

## Not in this plan

- Review 2.10 (automatic updates) and section 3 (build and setup): in the [design gaps plan](2026-11-10-review-design-gaps.md).
- Review 2.11: the rename can't be made safe against a file appearing at the same instant with what Java offers; `FileOps.isTaken` treating an unreadable folder as "taken" is one line and goes in the design gaps plan, Task 1.
- Review 2.12 (the storage shown as "0"): left to the SD-card work in progress, which renames storage roots.
