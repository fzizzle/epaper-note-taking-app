# Review Speed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the repeated work found in the [2026-11-10 review](../review-2026-11-10.md), section 4, so that writing on a full page, erasing, dragging a selection and autosaving all cost less, and prove each gain with a number measured on the tablet.

**Architecture:** Measure first: a few timing log lines and a fixed set of test files give a "before" table. Then seven independent changes, each small and each re-measured. Nothing changes how files are stored or what the owner sees, except one optional step (saving only the changes during a session) that is left as a decision.

**Tech Stack:** As in plan 4. Nothing new.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` §2.1 (a pen as immediate as the stock app), §7.2 ("Saving never blocks or stutters the pen"), §4 (a laggy pen is a failure). Findings: `docs/superpowers/review-2026-11-10.md` section 4.

**Where this plan sits:** the second of four plans from the review. It needs Task 5 of the [bug fixes plan](2026-11-10-review-bug-fixes.md) (`SafeSave.replace`'s `beforeSwap`, `FileIdentity.sameContentAs`). Run that plan first.

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, `test/…` and `androidTest/…` likewise.

## Decisions for the owner (approve before Task 1)

1. **Trusting the file's "changed" stamp before a save.** Today each save reads the whole PDF to check nothing else changed it. Proposed: first compare size, date, the file's internal number and its "last changed" stamp, which no sync app can set back. If all four are as the app left them, skip the read. If any differs, read and compare as today. Task 2 checks on the tablet that the stamp really moves on every outside change before this is switched on.
2. **Refresh redraws only the thumbnails on screen** (review 4.6, last point), the rest as they scroll into view. Today it redraws the whole folder.
3. **Not in this plan unless asked: saving only the changes during a session.** It would make each autosave much smaller, with a full tidy rewrite when the document is left. It changes how the PDF is written, needs its own crash-safety design, and should wait until Task 1's numbers show autosave is still slow after Task 2.

## Global Constraints

- "Saving never blocks or stutters the pen. It runs in the background." (spec §7.2). "Every stroke must survive a crash … from the moment the pen lifts" (spec §7.3): no change here may delay the journal write.
- A speed-up that isn't measured isn't kept: each task ends with its number in `docs/superpowers/review-speed-results.md`, before and after. A change that gains under 10% is reverted.
- The debug build is slower than a release build. All numbers are taken on the same build type, and the table says which.
- fitz 1.28.5: never `PDFObject.push(PDFObject)` or `addObject`.
- All MuPDF access stays on the document's single worker thread. Page pictures (`PageBitmaps`) are touched on the UI thread only.
- No new libraries or threads. Readable code; `WritingActivity.kt` is changed as little as possible.
- Toolchain and tablet rules as in the bug fixes plan: `source ~/.local/opt/android-env.sh`; `./gradlew :app:testDebugUnitTest`; for the tablet use `installDebug` + `installDebugAndroidTest` + `adb shell am instrument`, and wait for a quiet tablet.

## Review Focus

1. **A page with 1,500 strokes.** Adding the next stroke costs the same as on an empty page, within a factor of two (Task 3: `addingAStrokeDoesNotGrowWithThePage`).
2. **A sync app rewrites the file while it is open, keeping its size and date.** The next save still refuses to write over it (Task 2: `sameSizeAndDateButNewBytesIsStillCaught`).
3. **Erase, undo, erase, undo, fifty times.** Memory stays flat and no picture is drawn after it was freed (Task 5: `evictedPicturesAreHandedBack`, and the tablet check).
4. **A snip moved ten times, then the app killed.** The journal replays to the same picture in the same place, and the journal is far smaller than ten copies of the picture (Task 7: `aMovedSnipIsJournaledOnce`, `replayRebuildsAMovedSnip`).
5. **A journal written by today's build, opened by the new one.** Every change still replays (Task 7: `journalsFromBeforeStillRead`).

---

### Task 1: Measure

**Files:**
- Modify: `main/doc/DocumentSession.kt` (`save`, `applyPending`), `main/ui/PageView.kt` (`onDraw`), `androidTest/check/SpeedCheck.kt`
- Create: `docs/superpowers/review-speed-results.md`

- [ ] **Step 1: Add timing lines** (to `System.err` in `doc/`, `Log.i("Notes", …)` in `ui/`):
  - `save ms=<total> check=<identity check> write=<saveTo> swap=<rest> bytes=<file size>`
  - `apply ms=<n> changes=<k>` when over 5 ms
  - `draw ms=<n> overlay=<strokes drawn>` when over 8 ms
- [ ] **Step 2: Extend `SpeedCheck`** with `addingStrokesToAFullPage`: on a blank test PDF, add 1,500 short strokes one at a time through `PdfFile.apply(AddItems(...))`, logging the time of stroke 1, 500, 1,000 and 1,500; then one `saveTo`.
- [ ] **Step 3: Run on the tablet and fill in the "before" table:** save time on a small (1 MB) and a large (30 MB or more) lecture file; stroke 1 against stroke 1,500; `draw ms` after 300 strokes on one page; an erase from pen-up to the page being shown (`erase … ms=` to the next `rendered` line); a snip dragged for five seconds (count of `draw` lines over 8 ms).
- [ ] **Step 4: Commit.** `git commit -m "notes: timings for saving, adding strokes and drawing"`

### Task 2: Fewer whole-file reads per save (review 4.1)

**Files:**
- Create: `main/storage/FileStamp.kt`
- Modify: `main/doc/DocumentSession.kt` (`openOnWorker`, `save`, `switchTo`)
- Test: `test/storage/FileStampTest.kt`, `androidTest/storage/FileStampDeviceTest.kt`, `test/doc/DocumentSessionTest.kt`

**Interfaces:**
- Consumes: `FileIdentity.sameContentAs`, `SafeSave.replace(target, beforeSwap, write)` from the bug fixes plan, Task 5.
- Produces: `data class FileStamp(val size: Long, val modifiedMs: Long, val changedNs: Long, val fileNumber: Long)` with `companion fun of(file: File): FileStamp?` — read with `Files.readAttributes(path, "unix:size,lastModifiedTime,ctime,ino")`; null when the storage doesn't offer them, which makes every caller fall back to reading the file.

- [ ] **Step 1: Write the failing tests.**
  - `FileStampTest`: `sameFileUntouchedGivesTheSameStamp`; `rewritingWithTheSameSizeAndDateChangesTheStamp` (write new bytes of equal length, then `setLastModified` back: the stamp differs); `replacingByRenameChangesTheStamp`.
  - `FileStampDeviceTest` (tablet, in a scratch folder under `/sdcard/Documents` that the test creates and removes): the same three, on the storage the owner's files live on. If SD-card roots have landed by then, also on the card.
  - `DocumentSessionTest.sameSizeAndDateButNewBytesIsStillCaught`: open, add a stroke, rewrite the file from outside with equal length and the old date, save: `ChangedOnDisk`, nothing written.
- [ ] **Step 2: Run the tablet test before writing any session code.** If `rewritingWithTheSameSizeAndDateChangesTheStamp` fails on the tablet's storage, stop: keep `FileStamp` out, record the result, and skip to Step 5 with only the smaller gain already made by the bug fixes plan (one read per save instead of two).
- [ ] **Step 3: Implement.** The session keeps `knownStamp: FileStamp?` beside `known`, taken right after opening and right after each swap. In `save`: when `FileStamp.of(file) == knownStamp` and it isn't null, skip `FileIdentity.of(file)`; otherwise compare as today.
- [ ] **Step 4: Run all laptop tests** — pass.
- [ ] **Step 5: Measure** the `save … check=` figure on the large file, add it to the results, and commit. `git commit -m "notes: a save only rereads the file when something touched it"`

### Task 3: Adding a stroke costs the same on a full page (review 4.3)

**Files:**
- Modify: `main/pdf/PdfFile.kt:139-153` (`apply`), `:181-190` (`onPage`), `:226-234` (`idsOn`)
- Test: `androidTest/pdf/PdfFileStrokesTest.kt`, `androidTest/check/SpeedCheck.kt`

**Interfaces:**
- Produces: inside `PdfFile`, `private val idsByPage = HashMap<Int, HashSet<String>>()`: filled from `idsOn(page)` the first time a page is edited, kept in step by every add and remove, and cleared for the pages named by a `RotatePages`. Nothing outside `PdfFile` changes.

- [ ] **Step 1: Write the tablet tests:** `replayingAnAddTwiceAddsItOnce` and `aMoveOfAnErasedItemBringsNothingBack` (pin today's replay behaviour, if not pinned already); `addingAStrokeDoesNotGrowWithThePage` in `SpeedCheck` — stroke 1,500 takes under twice the time of stroke 100.
- [ ] **Step 2: Run:** the two behaviour tests pass already; the timing test fails.
- [ ] **Step 3: Implement** the id cache. Then try the second half separately, and keep it only if every render and save test still passes: for an `AddItems`, skip `p.update()` when the annotation's drawing was set directly by `drawInk` (MuPDF then has nothing to regenerate).
- [ ] **Step 4: Run the tablet tests** for `pdf/` and the timing test — pass.
- [ ] **Step 5: Measure, record, commit.** `git commit -m "notes: adding a stroke no longer rereads the page's other strokes"`

### Task 4: A finished stroke doesn't redraw the page (review 4.2)

**Files:**
- Modify: `main/ui/PageView.kt:155-159` (`addStroke`), `:227-282` (`onDraw`, `drawStroke`), `main/ui/WritingActivity.kt:433-438` (`write`), `main/ui/InkOverlay.kt`
- Test: `test/ui/InkOverlayTest.kt`

**Interfaces:**
- Produces:
  - `fun PageView.addStroke(page: Int, stroke: Stroke, redraw: Boolean)`. `WritingActivity.write` passes `redraw = !pen.drawsInk`: with fast-ink on, the panel ignores the frame anyway, and `pausePenAndRedraw` draws before anything is shown.
  - `fun InkOverlay.count(page: Int): Int` and `const val InkOverlay.BAKE_AFTER = 150`.
  - When the page on show has more than `BAKE_AFTER` overlay strokes and the pen is idle (hook into the existing `retryShow` idle wait: no stroke for `SHOW_RETRY_IDLE_MS`), `PageView` asks for a fresh rendering with `ensureRendered` **without** marking the page changed and without calling `onPageShown` for it, so nothing flashes; once it arrives `newerThan` is empty again.

- [ ] **Step 1: Write the failing tests** in `InkOverlayTest`: `countFollowsAddsAndLeaving`; `aRenderingAtTheLatestVersionLeavesNothingToDraw` (pins what the quiet re-render relies on).
- [ ] **Step 2: Implement.** In `drawStroke`, also stop allocating per point: map into two reused float fields instead of a `List<Pt>`.
- [ ] **Step 3: Run laptop tests** — pass.
- [ ] **Step 4: Check on the tablet:** write 300 strokes on one page; `draw … overlay=` never exceeds about 150, no `draw` line appears between strokes, and the panel never flashes on its own while writing.
- [ ] **Step 5: Measure, record, commit.** `git commit -m "notes: a new stroke no longer redraws every stroke on the page"`

### Task 5: Page pictures are handed back, and fewer are made (review 4.4, 4.6)

**Files:**
- Modify: `main/ui/PageBitmaps.kt`, `main/ui/PageView.kt:71-73`, `:131-150`, `:193-205`
- Test: `test/ui/PageBitmapsTest.kt`

**Interfaces:**
- Produces: `class PageBitmaps<B : Any>(maxBytes: Long, sizeOf: (B) -> Long, onEvicted: (B) -> Unit = {})`. `onEvicted` is called for every entry that leaves through `put` (replaced or pushed out) or `invalidate`. `PageView` passes `{ it.bitmap.recycle() }`, and also recycles a rendering that arrives for a dropped request.

- [ ] **Step 1: Write the failing tests** in `PageBitmapsTest`: `evictedPicturesAreHandedBack`, `replacedPictureIsHandedBack`, `invalidatedPagesAreHandedBack`, `thePictureJustPutIsNeverHandedBack`.
- [ ] **Step 2: Implement.** Guard the one risk: `onDraw` must never hold a picture across a `put`. It doesn't today (it reads and draws in one pass on the UI thread); say so in a comment.
- [ ] **Step 3: Stop the redundant redraw:** in `pageRendered`, call `invalidate()` only when `page == viewport?.page` (review 4.6: a neighbour arriving changes nothing visible).
- [ ] **Step 4: Stop the throw-away-and-rebuild:** in `forget(page)`, when the page left is a neighbour of the page arrived at, don't request it in the same `onDraw`; let the first idle moment (the same hook as Task 4) pre-render neighbours. Turning straight back still works: the stale picture plus overlay is shown, as today.
- [ ] **Step 5: Run laptop tests; on the tablet** erase and undo fifty times and watch `adb shell dumpsys meminfo com.fzizzle.epapernotes | grep -i graphics` stay level; turn pages back and forth quickly and confirm no crash and no "recycled bitmap" error in logcat.
- [ ] **Step 6: Measure, record, commit.** `git commit -m "notes: page pictures are freed at once, and neighbours aren't redrawn for nothing"`

### Task 6: Dragging a selection redraws less (review 4.5)

**Files:**
- Modify: `main/ui/SelectionLayer.kt:129-141`, `:243-282`

**Interfaces:**
- Produces: inside `SelectionLayer`, `private var previewPaths: List<Path>` built once when the selection is set or the page is laid out anew, in page-display space; `drawPreview` draws them under `canvas.concat(matrix of the drag so far)` rather than re-mapping every point. A traced line keeps one `Path` and adds a `lineTo` per pen move.

- [ ] **Step 1: Implement** the two caches. Clear `previewPaths` wherever the selection, the page, or the zoom changes.
- [ ] **Step 2: Check on the tablet,** using the scripted pen (it reaches the app while a selection is up): lasso twenty strokes, drag them for five seconds with `adb shell input stylus motionevent`, and compare the count of `draw` lines over 8 ms with Task 1's. Then resize and check the preview still matches where the ink lands.
- [ ] **Step 3: Measure, record, commit.** `git commit -m "notes: a dragged selection is shifted, not rebuilt, on each pen move"`

### Task 7: A snip is written to the journal once (review 4.6)

**Files:**
- Modify: `main/doc/ChangeCodec.kt`, `main/doc/ChangeJournal.kt`, `main/ink/ItemCodec.kt`, `main/pdf/ItemAnnotations.kt:150-179` (`addImage`)
- Test: `test/doc/ChangeCodecTest.kt` (or `ChangeJournalTest.kt`), `test/ink/ItemCodecTest.kt`, `androidTest/pdf/PdfFileItemsTest.kt`

**Interfaces:**
- Produces:
  - A journal line `img <sha256 of the PNG> <base64>` written the first time a picture appears in a journal file; later item lines carry `@<sha256>` where the base64 was. `ChangeJournal.rewrite` writes each picture still referred to once, before the changes.
  - `ItemCodec.encode(item: Item, pictureRef: (ByteArray) -> String?)` and `decode(text: String, picture: (String) -> ByteArray?)`: the journal supplies both lambdas; a null ref means "write the bytes inline", which is also what today's journals hold, so they still read.
  - `ItemAnnotations.addImage` records `pngs[number] = item.png` for the image object it creates, so the next `items()` doesn't re-encode it (review 4.6, second point).

- [ ] **Step 1: Write the failing tests:** `aMovedSnipIsJournaledOnce` (a 200 KB picture added and moved ten times: the journal file is under 400 KB); `replayRebuildsAMovedSnip` (read back: the last `ReplaceItems.after` holds the same bytes); `rewriteKeepsPicturesStillReferredTo`; `journalsFromBeforeStillRead` (a journal text with inline base64, copied from today's format); `aCutShortImgLineLosesOnlyWhatFollows`; tablet: `aMovedSnipIsNotEncodedAgain` (after a move, `items(page)` returns the very same `ByteArray` contents without calling `encode` — assert by timing under 5 ms, or by a counter visible to tests).
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.** The journal keeps `private val written = HashSet<String>()` per file, reset by `rewrite`, `start` and `clear`.
- [ ] **Step 4: Run all laptop tests and the `pdf/` tablet tests** — pass.
- [ ] **Step 5: Measure** a snip dragged three times and undone once (journal bytes, UI-thread time in `recordFromScreen`), record, commit. `git commit -m "notes: a snip's picture goes into the journal once"`

### Task 8: Small ones, then the results (review 4.6)

**Files:**
- Modify: `main/ui/WritingActivity.kt:267-273`, `:344-358`; `main/pdf/PdfFile.kt:51-70`; `main/ui/LibraryActivity.kt:271-289`, `:345-358`; `main/ui/LibraryTiles.kt:116-135`
- Modify: `docs/superpowers/review-speed-results.md`

**Interfaces:**
- Produces: `fun PdfFile.layout(page: Int): Pair<SizeF, Affine>` — size and transform from one page load.

- [ ] **Step 1: One page load per page at layout.** `layOutPages` uses `layout(page)`. After a rotation it re-reads only the pages in `PageChanged.pages` and reuses the rest.
- [ ] **Step 2: No Onyx calls during a pinch.** In `onViewportChanged`, skip `updatePenWidth()` and `pen.setLimit(...)` while a finger gesture is under way (`pages` already knows: expose `val fingersDown`), and do both once in `onFingerGestureEnd`.
- [ ] **Step 3: Library.** Set `continueRow.text` and `emptyNote.text` only when the text differs. In `LibraryTiles.show`, don't `invalidate()` when name, picture and mode are all unchanged. If decision 2 was approved: Refresh passes only the on-screen PDFs to `ThumbnailLoader.redraw`, and marks the rest of the folder not-done so they redraw as they scroll into view.
- [ ] **Step 4: Run all laptop tests; time opening a 200-page file** (`library` tap to first `rendered` line) before and after.
- [ ] **Step 5: Finish `review-speed-results.md`:** the full before and after table, which changes were reverted for gaining too little, and whether autosave on the large file is now fast enough or decision 3 should be taken up.
- [ ] **Step 6: Commit.** `git commit -m "notes: smaller savings at layout, pinch and in the library; speed results"`

## Not in this plan

- Re-rendering only the changed patch of a page after an erase (review 4.4, "later"). The [readability plan](2026-11-10-review-readability.md), Task 2, adds drawing a region at true size; a patch re-render should reuse that once it exists.
- A release build, which may be the largest single gain: [design gaps plan](2026-11-10-review-design-gaps.md), Task 3. Take the "before" numbers of this plan again after it lands.
