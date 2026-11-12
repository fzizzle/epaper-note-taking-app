# Efficiency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the repeated work and the avoidable panel updates found in the performance review of 2026-11-12, and prove each gain with a number measured on the tablet.

**Architecture:** Measure first, as the [speed plan](2026-11-10-review-speed.md) did: a "before" table from the tablet. Then eight independent changes, each one commit and each re-measured. Nothing changes how files are stored. Two changes alter what the owner sees or when a file is written, and are decisions below.

**Tech Stack:** As now. Nothing new.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` §2.1 (an immediate pen), §4 (a laggy pen and lost writing are failures), §7.2–7.3 (autosave, crash safety), §6.1 (thumbnails cached and fast).

**Where the findings come from:** the app's own log on the tablet, release build of main (b584251), evening of 2026-11-12, and a read of the code. Measured: a page turn is about 165 ms; an erase is about 250 ms and one flash (erase 7–43 ms, redraw 55 ms, fast-ink pause 165 ms); with Trim margins on each next page is drawn at two widths (`render requested page=1 width=1675`, then `width=1729`); the app holds 288 MB (160 MB native) while sitting in the library after a document. The rest is read from the code and not measured yet: Task 1 measures it.

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, `test/…` and `androidTest/…` likewise.

## Decisions for the owner (approve before Task 1)

1. **A run of erases ends in one flash, not one per erase** (Task 4). Today every erase redraws the page and flashes at once: five erases in six seconds were five flashes in the log. Proposed: the redraw waits until the eraser has rested for 0.4 s. The price: erased ink stays on screen up to 0.4 s longer, and through the whole run if you erase stroke after stroke without a rest. Writing straight after an erase still works as now.
2. **Autosave 10 seconds after the pen stops, not 3** (Task 5). Today every 3-second pause rewrites the whole PDF (14 MB for the test file, 190 ms, about five flushes to the card). Strokes are safe from the moment the pen lifts either way, through the journal. Leaving the document or the app still saves at once. The price: a sync app sees the new writing up to 7 seconds later. Spec §7.2 says "a short moment after the pen stops"; it would say "about ten seconds".
3. **Thumbnails appear in groups** (Task 7). In a folder not seen yet, each tile today goes grey and then gets its picture by itself: one panel update per tile. Proposed: pictures that are ready within 120 ms of each other are shown together.
4. **Not in this plan:** the eraser's trail and a flash-free erase (their own plan, in TODO.md); saving only the changes during a session (declined on 2026-11-10; decision 2 covers most of its gain); remembering that a file has no old mirrored ink, and reading the file after showing page 1 at open. The last two are measured in Task 1 and planned only if they cost over 100 ms on the owner's files.

## Global Constraints

- "Every stroke must survive a crash, kill, or power loss from the moment the pen lifts" (spec §7.3): no change here may delay or skip the journal write.
- "Saving never blocks or stutters the pen. It runs in the background." (spec §7.2).
- A speed-up that isn't measured isn't kept: each task ends with its number in `docs/superpowers/efficiency-results.md`, before and after, on the release build. A change that gains under 10% of its own measure is reverted. Each task is one commit, so it can be reverted alone.
- All MuPDF access stays on the document's single worker thread. Page pictures (`PageBitmaps`) are touched on the UI thread only.
- fitz 1.28.5: never `PDFObject.push(PDFObject)` or `addObject`.
- No new libraries or threads. Readable code: small files, plain names, comments that say why. `WritingActivity.kt` is changed as little as possible.
- Toolchain: `source ~/.local/opt/android-env.sh`; laptop tests `./gradlew :app:testDebugUnitTest` (355 pass today). Tablet tests without wiping the app: `./gradlew :app:installDebug :app:installDebugAndroidTest`, then `adb shell am instrument -w -e notPackage com.fzizzle.epapernotes.check com.fzizzle.epapernotes.test/androidx.test.runner.AndroidJUnitRunner`. Put the release build back afterwards (`./gradlew :app:installRelease`). Wait for a quiet tablet; another session may share the checkout, so never `git add -A`.
- Findings made on the way go in `docs/superpowers/findings-log.md`, newest first.

## Review Focus

1. **Erase a word, then carry straight on writing.** The new stroke is kept and drawn live, and the erased ink leaves the screen once the pen rests (Task 4: `aNewEraseStartsTheWaitAgain`, and the tablet check).
2. **Leaving a document while a page picture is still being made.** The picture that arrives afterwards is freed, and nothing draws a freed picture (Task 3: `aPictureArrivingAfterReleaseIsFreed`).
3. **The app is killed nine seconds after the last stroke**, before the longer autosave. Every stroke is back at the next opening (Task 5: `aKillBeforeTheAutosaveLosesNothing`).
4. **The app is started with the SD card out.** The thumbnails of the card's PDFs are still there when the card is back (Task 8: `pruneKeepsThumbnailsWhoseStorageIsOut`).
5. **A pen stroke that crosses from one band of the solid-ink step into the next.** It is solid along its whole length, with no gap or doubled row at the join (Task 6: `aStrokeAcrossTwoBandsIsWhole`).

---

### Task 1: Measure

**Files:**
- Modify: `main/doc/DocumentSession.kt` (`openOnWorker`, `redrawFaultyInkFrom`), `androidTest/check/SpeedCheck.kt`
- Create: `docs/superpowers/efficiency-results.md`

- [ ] **Step 1: Add two timing lines** (to `System.err`, as the session's other timings):
  - `DocumentSession: open ms=<total> identity=<FileIdentity.of> journal=<read and rewrite or start> pdf=<open(file)>` at the end of `openOnWorker`.
  - `DocumentSession: ink check ms=<sum of the time spent in redrawFaultyInk> pages=<n> redrawn=<k>` when `redrawFaultyInkFrom` passes the last page.
- [ ] **Step 2: Extend `SpeedCheck`** with `renderingAnInkedPage`: on a blank A4 test PDF add 300 `wavyStroke`s spread over the whole page (the first near the top-left corner, the last near the bottom-right), then time `render(0, 1742)` five times and report the median, and the same for `render(0, 2790)` (the 1.5× width).
- [ ] **Step 3: Run on the tablet, release build, and fill in the "before" table** in `efficiency-results.md`:
  - `adb shell dumpsys meminfo com.fzizzle.epapernotes` (TOTAL PSS, Native Heap, Graphics) in the library at launch, in a document after ten page turns, and back in the library.
  - Trim margins on, ten page turns forward through pages not yet seen: the count of `render requested` lines (expected today: about two per turn).
  - Five erases within six seconds: the count of `show: pause fast-ink` lines.
  - Ten minutes of ordinary writing by the owner, or the last session's log: the count of `saved` lines, and `save ms=` of each.
  - After a reboot, open the owner's test file: the `open ms=` line. Then the `ink check ms=` line.
  - A folder of 30 or more PDFs never opened since the app started: the time from `library listed` to the last tile's picture, and the count of `SDM … update_to_display` lines in that time (`adb logcat -s SDM`).
  - `library listed … ms=` for the root and for one course folder, three visits each.
- [ ] **Step 4: Commit.** `git commit -m "notes: timings for opening and the old-ink check; a speed check for inked pages"`

### Task 2: With Trim margins, a neighbour page is drawn once

Today `PageView.readyNeighbours` asks for a neighbour's picture before its printed area is measured, at the whole-page width, and again at the trimmed width once the measure arrives. Both pictures (about 18 MB each) stay in the cache.

**Files:**
- Modify: `main/ui/PageView.kt` (`readyNeighbours`), `main/ui/WritingActivity.kt` (`onCreate`, `printedAreaFound`)

**Interfaces:**
- Produces: `PageView.mayReadyAhead: (page: Int) -> Boolean` (default `{ true }`), and `PageView.readyAhead()` (asks for the neighbours' pictures now, as a draw would; nothing while pinching or before layout).

- [ ] **Step 1:** In `PageView`, `readyNeighbours` skips a neighbour for which `mayReadyAhead(page)` is false. Add `readyAhead()`.
- [ ] **Step 2:** In `WritingActivity`, keep a `measuredOnce: HashSet<Int>` beside `printedAreas`: a page goes in when `printedAreaFound` hears of it, box or null, and comes out wherever `printedAreas.remove` is called. Set `pages.mayReadyAhead = { !trimMargins || (it in measuredOnce && it !in measuring) }`.
- [ ] **Step 3:** `printedAreaFound` calls `pages.readyAhead()` for a page that isn't the one on show, on every path, including the early return for an unchanged box. (`refitLater` already readies neighbours on the changed-box path; one call covers both.)
- [ ] **Step 4: Laptop tests pass.** `./gradlew :app:testDebugUnitTest`
- [ ] **Step 5: Tablet check.** Repeat Task 1's ten page turns with Trim margins on: one `render requested` per new page, each at its final width. With Trim margins off: neighbours are still made ahead (a turn shows the page with no `rendered` line between `turn` and `resume fast-ink`). Write both counts in the results.
- [ ] **Step 6: Commit.** `git commit -m "writing: with Trim margins a neighbour page is drawn once, at its trimmed width"`

### Task 3: Page pictures are freed when the document is left

Nothing frees `PageView`'s pictures or sharp patch when the screen closes; they wait for the garbage collector, which sees only their small Java side.

**Files:**
- Modify: `main/ui/PageBitmaps.kt`, `main/ui/PageView.kt`, `main/ui/WritingActivity.kt` (`onDestroy`)
- Test: `test/ui/PageBitmapsTest.kt`, create `androidTest/ui/PageViewReleaseTest.kt`

**Interfaces:**
- Produces: `PageBitmaps.clear()` (every picture handed to `onEvicted`, total back to 0); `PageView.release()` (UI thread; frees every page picture and the patch; from then on a picture that arrives is recycled at once and `onDraw` draws only the background).

- [ ] **Step 1: Write the failing tests.**

```kotlin
// PageBitmapsTest
@Test fun clearHandsEveryPictureBack() {
    val back = mutableListOf<String>()
    val cache = PageBitmaps<String>(100, { 10 }) { back += it }
    cache.put(0, 100, "a"); cache.put(1, 100, "b")
    cache.clear()
    assertEquals(setOf("a", "b"), back.toSet())
    assertNull(cache.get(0, 100))
    cache.put(2, 100, "c") // the total started again from 0: nothing is pushed out
    assertEquals(2, back.size)
}
```

```kotlin
// PageViewReleaseTest (on the main thread, through InstrumentationRegistry.runOnMainSync)
@Test fun aPictureArrivingAfterReleaseIsFreed() {
    val view = PageView(context)
    view.release()
    val late = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
    view.pageRendered(0, 10, late)
    assertTrue(late.isRecycled)
    val patch = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
    view.regionRendered(0, Box(0f, 0f, 1f, 1f), token = 0, version = 0, bitmap = patch)
    assertTrue(patch.isRecycled)
}
```

- [ ] **Step 2: Run them; both fail** (`clear` and `release` don't exist).
- [ ] **Step 3: Implement** `clear()` and `release()`. `WritingActivity.onDestroy` calls `pages.release()` after `handler.removeCallbacksAndMessages(null)`, if `pages` is initialised.
- [ ] **Step 4: Run the tests; they pass.**
- [ ] **Step 5: Tablet check.** Open a document, turn ten pages, leave, wait five seconds, `dumpsys meminfo`: write Native Heap and TOTAL PSS beside Task 1's figure. If Native Heap is still over 100 MB, the rest is MuPDF's own store: note it in the findings log, and try `com.artifex.mupdf.fitz.Context.emptyStore()` after `pages?.close()` in `DocumentSession.close` (check the method exists in 1.28.5 first; keep it only if the figure drops).
- [ ] **Step 6: Commit.** `git commit -m "writing: page pictures are freed when the document is left"`

### Task 4: A run of erases ends in one flash (decision 1)

**Files:**
- Create: `main/ui/EraseSettle.kt`, `test/ui/EraseSettleTest.kt`
- Modify: `main/ui/WritingActivity.kt` (`erase`, the `pages.onPageShown` handler, `retryShow`)

**Interfaces:**
- Produces: `class EraseSettle(private val restMs: Long = EraseSettle.REST_MS)` with `fun erased(nowMs: Long)` (an erase hit just now) and `fun waitMs(nowMs: Long): Long` (how long a show must still wait; 0 when it may go now, and always 0 before any erase). `const val REST_MS = 400L`.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun noEraseNoWait() = assertEquals(0L, EraseSettle().waitMs(1_000))
@Test fun aShowWaitsOutTheRest() {
    val s = EraseSettle(); s.erased(1_000)
    assertEquals(340L, s.waitMs(1_060))
    assertEquals(0L, s.waitMs(1_400))
    assertEquals(0L, s.waitMs(9_000))
}
@Test fun aNewEraseStartsTheWaitAgain() {
    val s = EraseSettle(); s.erased(1_000); s.erased(1_300)
    assertEquals(400L, s.waitMs(1_300))
}
```

- [ ] **Step 2: Run them; they fail.**
- [ ] **Step 3: Implement `EraseSettle`.**
- [ ] **Step 4: Use it in `WritingActivity`.** `erase`'s callback calls `eraseSettle.erased(SystemClock.uptimeMillis())` on the UI thread when `hits > 0`, before `expectRedraw`. In `pages.onPageShown`, where `showWithoutFastInk()` is called today: if `eraseSettle.waitMs(now) > 0`, set `showPending = true`, remove `retryShow` from the handler and post it again after that wait; otherwise show as now. `retryShow` already refuses while the pen is down and the pen's lift already starts it again, so writing during the wait is covered by what is there. The held clean refresh after a zoom is not touched.
- [ ] **Step 5: Run all laptop tests.**
- [ ] **Step 6: Tablet check with the owner's pen**, `adb logcat -s Notes`: five erases within six seconds give one or two `show: pause fast-ink` lines, not five; one erase alone is shown about 0.4 s after it; erase then write at once: the stroke is drawn live and kept, and the erased ink goes when the pen rests; an erase by each route (button 2 held from above, button 2 pressed close, the strip's eraser). Counts into the results.
- [ ] **Step 7: Update** TODO.md's known bug ("flashes after each erase": now once per run) and spec §6.3 (stroke eraser: the page is drawn again once the eraser rests).
- [ ] **Step 8: Commit.** `git commit -m "writing: a run of erases is shown once, when the eraser rests"`

### Task 5: Autosave ten seconds after the pen stops (decision 2)

**Files:**
- Modify: `main/doc/DocumentSession.kt` (`AUTOSAVE_DELAY_MS`), `main/doc/Scheduler.kt` (`WorkerScheduler`)
- Test: `test/doc/DocumentSessionTest.kt`, `test/doc/WorkerSchedulerTest.kt`

- [ ] **Step 1: Write the failing test** in `DocumentSessionTest`, in the style of its kill-and-reopen tests: `aKillBeforeTheAutosaveLosesNothing` adds three strokes, never runs the delayed autosave (`FakeScheduler.runDelayed` is not called), opens a new session on the same file and journal folder, and asserts `SessionEvent.Replayed(3)` and three items on the page. And `autosaveWaitsTenSeconds`: `assertEquals(10_000L, DocumentSession.AUTOSAVE_DELAY_MS)`.
- [ ] **Step 2: Run them.** The first may pass already (it pins what must stay true); the second fails.
- [ ] **Step 3: Set `AUTOSAVE_DELAY_MS = 10_000L`**, with a comment giving the reason (the journal keeps strokes safe; each save rewrites the whole file). `saveNow` and `RETRY_DELAY_MS` are untouched.
- [ ] **Step 4: `WorkerScheduler`'s executor gets `removeOnCancelPolicy = true`**, so a replaced autosave timer is dropped instead of waking the thread for nothing. `WorkerSchedulerTest`'s tests still pass.
- [ ] **Step 5: Tablet check.** Write for a minute with pauses of four to eight seconds: no `saved` line until ten seconds after the last stroke. Write, then press Back at once: `saved` follows the `leave:` lines. Write, then `adb shell am force-stop com.fzizzle.epapernotes` within five seconds, reopen: "Recovered n change(s)". Counts of `saved` into the results beside Task 1's.
- [ ] **Step 6: Update spec §7.2** ("about ten seconds after the pen stops").
- [ ] **Step 7: Commit.** `git commit -m "doc: autosave ten seconds after the pen stops, not three"`

### Task 6: The solid-ink step works in bands

`PdfFile.drawSolid` takes one box around all the page's pen ink, usually the whole page, and holds three buffers of that size at once (a MuPDF pixmap, its bytes, an int array): about 60 MB for a fitted page, 130 MB at the 1.5× width.

**Files:**
- Modify: `main/pdf/PdfFile.kt` (`drawSolid`)
- Test: `androidTest/pdf/PdfFileStrokesTest.kt`

- [ ] **Step 1: Write the test** `aStrokeAcrossTwoBandsIsWhole`: on a blank test PDF add one straight vertical pen stroke at constant pressure from 10% to 90% of the page's height, render at width 1700, and assert that every row between the stroke's ends has the same count of pure black pixels (±1) in the stroke's column range, and no pixel in that range is grey (each is pure black or the page's white). It passes before the change too: it pins the look.
- [ ] **Step 2: Rework `drawSolid`** to go down the ink's area in bands of `SOLID_BAND_ROWS = 256` rows. Per band: a pixmap of the band only, run only the ink annotations whose bounds (transformed by `ctm`) reach into the band, then the same half-covered rule as today. One `IntArray` of a band's size is made once and used for every band; a band no annotation reaches is skipped without a pixmap.
- [ ] **Step 3: Run the tablet tests**; `inkIsSolidButThePageStaysSmooth` and `aStrokeAcrossTwoBandsIsWhole` pass.
- [ ] **Step 4: Measure** with `SpeedCheck.renderingAnInkedPage` at both widths, before and after. Keep the change if it's no slower: its gain is memory (the buffers fall from the page's size to a band's). Revert if rendering is more than 10% slower. Numbers into the results.
- [ ] **Step 5: Commit.** `git commit -m "pdf: solid ink is drawn a band at a time, not through three page-sized buffers"`

### Task 7: A folder's thumbnails arrive sooner and together (decision 3)

**Files:**
- Modify: `main/ui/ThumbnailLoader.kt` (`run`), `main/ui/LibraryActivity.kt` (`thumbnailReady`)

- [ ] **Step 1:** In `ThumbnailLoader.run`, before a `Show` job set the thread to `Process.THREAD_PRIORITY_DEFAULT`; before a `Store` or `Drop` job back to `THREAD_PRIORITY_BACKGROUND`. Only when it changes.
- [ ] **Step 2:** In `LibraryActivity`, `thumbnailReady` puts the picture in a `pendingThumbnails: HashMap<PdfEntry, Bitmap?>` and, if none is waiting, posts one flush `TILE_BATCH_MS = 120L` later on the main handler; the flush gives every tile on screen its waiting picture in one pass and empties the map. `onStop` removes the flush and empties the map.
- [ ] **Step 3: Tablet tests** (`ThumbnailLoaderTest`) still pass.
- [ ] **Step 4: Measure** as in Task 1: the time from `library listed` to the last picture, and the count of panel updates. Into the results. Owner's look: does the folder fill in calmly?
- [ ] **Step 5: Commit.** `git commit -m "library: thumbnails on screen are read at normal priority and shown in groups"`

### Task 8: Thumbnails survive a launch with the SD card out

`ThumbnailCache.pruneMissing` takes every PDF it can't find for deleted. With the card out that is every PDF on it, and all their thumbnails are deleted and later drawn again.

**Files:**
- Modify: `main/library/ThumbnailCache.kt` (`pruneMissing`), `main/ui/ThumbnailLoader.kt` (constructor, `run`), `main/ui/LibraryActivity.kt` (`newThumbnails`)
- Test: `test/library/ThumbnailCacheTest.kt`

**Interfaces:**
- Produces: `ThumbnailCache.pruneMissing(exists: (File) -> Boolean = File::isFile, storageIn: (File) -> Boolean = { true })`; `ThumbnailLoader(cache, storageIn: (File) -> Boolean = { true }, onReady)`.

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test fun pruneKeepsThumbnailsWhoseStorageIsOut() {
    val onCard = entry(File("/storage/3331-3031/Workbooks/a.pdf"))
    val deleted = entry(File(folder, "gone.pdf"))
    cache.store(onCard, png); cache.store(deleted, png)
    cache.pruneMissing(exists = { false }, storageIn = { !it.path.startsWith("/storage/3331-3031") })
    assertNotNull(cache.cached(onCard))
    assertNull(cache.cached(deleted))
}
```

(`entry`, `png`, `folder`: as the file's other tests build them.)

- [ ] **Step 2: Run it; it fails.**
- [ ] **Step 3: Implement:** a PDF that doesn't exist counts as live while `storageIn(pdf)` is false. `LibraryActivity.newThumbnails` passes `{ Volumes.holding(<the volumes in now, from ui/StorageVolumes.kt>, it) != null }`, read when asked, not once.
- [ ] **Step 4: Run the laptop tests; they pass.**
- [ ] **Step 5: Commit.** `git commit -m "library: a card that's out doesn't cost its thumbnails"`

### Task 9: Small ones

Each is a few lines; one commit each, so any can be reverted alone.

**Files:**
- Modify: `main/ui/ThumbnailLoader.kt` (`render`), `main/library/FolderListing.kt`, `main/storage/SafeSave.kt` (`cleanStale`), `main/ui/LibraryActivity.kt` (`tilesOnScreenChanged`)
- Test: `androidTest/ui/ThumbnailLoaderTest.kt`, `test/library/FolderListingTest.kt`

- [ ] **Step 1: Thumbnails are stored without a see-through channel.** `full.setHasAlpha(false)` before `compress`, so the cached PNG is read back at 16-bit (`inPreferredConfig` is ignored for a PNG with alpha). Test `aCachedThumbnailIsReadAt16Bit`: after one PDF is shown and the loader restarted, the picture handed to `onReady` has `config == Bitmap.Config.RGB_565`. Thumbnails cached before stay as they are until their file changes or Refresh.
- [ ] **Step 2: A subfolder is listed once, not twice.** `FolderListing.list` counts a subfolder's PDFs and folders from one `listFiles()`. `FolderListingTest` passes unchanged.
- [ ] **Step 3: `SafeSave.cleanStale` tests the name before asking the disk** (`isTempFile(f.name) && f.isFile && …`).
- [ ] **Step 4: Scrolling doesn't wake the loader for nothing.** `tilesOnScreenChanged` remembers the first position and count it last reported and returns early when both, and the adapter's entries, are the same.
- [ ] **Step 5: Measure** `library listed … ms=` as in Task 1; into the results.

### Task 10: Tablet run, results, owner check (hard stop)

- [ ] **Step 1:** One complete run of the laptop tests and the tablet tests on a quiet tablet. Counts into the results.
- [ ] **Step 2:** Fill in `docs/superpowers/efficiency-results.md`: the before and after table, what was kept and what was reverted, and Task 1's two "only if slow" measures (the old-ink check, opening after a reboot) with a line each on whether they need a plan.
- [ ] **Step 3:** Install the release build. Write `notes/TESTING-efficiency.md` for the owner: erase several strokes in a row and say how the single refresh feels (decision 1); write for a few minutes, leave and reopen (nothing lost); turn pages with Trim margins on; open a folder not seen yet and watch it fill in; use a session as normal and report anything that feels slower.
- [ ] **Step 4:** Tick the plan's lines in TODO.md under a new "Efficiency" section. **Stop for the owner's session.**
