# Lighter, Faster Thumbnails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A folder of the library opens with its thumbnails in one go, in one refresh, on the first visit as on every later one, with less work and less memory per thumbnail.

**Architecture:** Making a thumbnail is already cheap; what the owner feels is the screen being drawn several times and the first visit to a folder doing all the work while they watch. So: the thumbnails of the tiles about to be shown are read from the cache before the folder is shown; any still missing come on in one later go; the whole library's thumbnails are drawn ahead, quietly, so a first visit finds them ready; thumbnails are made at the size they are shown. One trial: a gentler panel update for the library, as the owner chose for page turns.

**Tech Stack:** As now. Nothing new.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` §6.1 ("Thumbnails are cached in the app's own cache storage and regenerated only when a file changes, so the grid stays fast"), §2 goal 5 and §4 (a laggy or crowded screen is a failure). Owner, 2026-11-13: "opening all the small folders is expensive and is a bit slow with all the thumbnails"; "try to find a way to make the thumbnails show but make it lighter or faster".

## What was measured (2026-11-13, before this plan)

From the owner's session log (release build of be19a62) and a check run on the tablet (`androidTest/check/ThumbnailCheck`, 16 of the owner's workbooks, copied):

| What | Time |
|---|---|
| Open a workbook PDF | 1 ms (median), 36 ms worst |
| Draw its first page at 300 px, first time | 6 ms (median), 26 ms worst |
| The same page again | 3 ms |
| Page count and "has writing" over every page | 0.2 ms |
| PNG at 300 px / at 240 px | 6 ms, 36 KB / 4 ms, 26 KB |
| Read one cached thumbnail from the cache (facts file and picture) | 2 ms |
| A whole thumbnail in the app's log (open, draw, PNG, store, on a background thread) | 25 to 30 ms |
| 16 thumbnails on one thread / two / three | 228 / 268 / 260 ms: more threads don't help |
| ML course, first visit after the library design build | 22 thumbnails drawn (641 ms), shown in five goes over 0.8 s |
| CTM course, cached | 30 thumbnails in one go |
| Back from a document to a course | the folder shows 380 to 500 ms later |

So: a cached folder is cheap to fill (30 tiles, about 60 ms), but it is drawn twice: grey pages first, then pictures. A folder with missing thumbnails comes on in several goes, each a refresh of a screen full of pictures. A tile is shown about 233 by 330 px; its thumbnail is made 300 by 424.

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, `test/…` and `androidTest/…` likewise.

## Decisions for the owner (approve before Task 1)

1. **One go per folder.** A folder is shown once its visible thumbnails are read from the cache, waiting at most 0.3 s for them; it no longer appears with grey pages first. Thumbnails still missing come on together when all of them are ready, or after 1.5 s at the latest, not in several goes.
2. **Draw ahead.** While the library is open and nothing else is asked of it, the app draws the thumbnails of every PDF in the library that has none, one at a time at the lowest priority, and stops as soon as a document is opened. For the owner's 160 or so PDFs that is about 5 seconds of work, once; after that only new or changed files.
3. **Thumbnails at the size they're shown:** 240 px wide instead of 300. A third fewer pixels to draw, store and keep in memory; on the panel the tile looks the same. Thumbnails already made stay until their file changes.
4. **A gentler refresh for the library, as a trial.** The owner chose REGAL for page turns (little flash, no ghost). The library's refreshes (a folder opening, thumbnails coming on) would use it too. Compared on the tablet with the owner; kept only if they prefer it.

## Global Constraints

- "Thumbnails are cached in the app's own cache storage and regenerated only when a file changes" (spec §6.1). Nothing is ever written into the owner's folders.
- Drawing thumbnails never slows the pen: no thumbnail work while a document is open (`ThumbnailLoader.pause`), as now.
- One thumbnail thread, as now (measured: more threads don't help).
- The screen's thread never reads files; the disk thread and the thumbnail thread do.
- A listing that changes nothing redraws nothing.
- Each change is measured before and after on the tablet, as in the [efficiency plan](2026-11-12-efficiency.md); a change that gains nothing measurable is reverted, except decisions 1 and 4, which are judged by the owner's eye.
- No new libraries. Readable code: small files, plain names, comments that say why. The Onyx SDK stays in one place (`PenInput`) plus, for decision 4, one small helper.
- Toolchain: `source ~/.local/opt/android-env.sh`; `cd notes && ./gradlew :app:testDebugUnitTest -q`; tablet tests with `installDebug` + `installDebugAndroidTest` + `adb shell am instrument -w -e notPackage com.fzizzle.epapernotes.check …`. To try library behaviour without touching the owner's settings: back up `shared_prefs/library.xml` with `run-as`, use `--es root` on a scratch folder, restore the file after. Never clear the owner's real thumbnail cache; use a scratch library folder for cold visits. Release build back at the end.

## Review Focus

1. **A folder of 60 PDFs, most of them never drawn.** It still opens within 0.3 s with grey pages for the missing ones, and they come on in one go, not 60 (Task 4: `aFolderOfMissingThumbnailsComesOnInOneGo`, tablet check).
2. **A document opened while the drawing ahead is under way.** The drawing stops before the document's first page is drawn, and the pen is as quick as before (Task 5: `drawingAheadStopsWhenPaused`).
3. **A PDF that can't be drawn (damaged, password-protected) in a folder not yet visited.** Drawing ahead skips it for the rest of the session and goes on with the others, not round and round (Task 5: `aBrokenPdfIsSkippedNotRetried`).
4. **The memory card taken out while drawing ahead.** The pass stops without error and nothing is deleted (Task 5: `drawingAheadStopsWhenTheCardGoesOut`).
5. **A thumbnail cached before this plan, at 300 px, next to new ones at 240 px.** Both fill their tiles the same (Task 3: `anOldBiggerThumbnailStillFits`).

---

### Task 1: Measure

**Files:**
- Modify: `main/ui/LibraryActivity.kt` (`list`, `showListing`, `showPendingThumbnails`), `androidTest/check/ThumbnailCheck.kt` (already written: it made the table above)
- Create: `docs/superpowers/thumbnail-results.md`

- [ ] **Step 1: One log line per folder visit:** `folder ready ms=<from listing asked to the last visible thumbnail on screen> goes=<passes that put pictures on tiles, the first draw included> drawn=<thumbnails made for it> cached=<read from the cache>`.
- [ ] **Step 2: Before table** on the debug build, in a scratch library folder (`/sdcard/Documents/jot-scratch`, a copy of one course of the owner's Workbooks): first visit (empty cache for those files), second visit, back from a document, and the same with the app restarted. Also the time `ThumbnailLoader.render` spends in `ThumbnailCache.store` (its directory sweep: 558 files in the cache today).
- [ ] **Step 3: Commit.** `git commit -m "notes: a log line per folder visit, for the thumbnail plan"`

### Task 2: No needless redraws in the cache

**Files:**
- Modify: `main/ui/ThumbnailLoader.kt` (`load`), `main/library/ThumbnailCache.kt` (`store`)
- Test: `test/library/ThumbnailCacheTest.kt`, `androidTest/ui/ThumbnailLoaderTest.kt`

**Interfaces:**
- Produces: `ThumbnailCache.store(pdf, png)` sweeps only the older versions of this file, found by name from the `<hash>.path` record or a directory listing filtered once by the hash prefix with `listFiles { _, name -> name.startsWith("$hash-") }`.

- [ ] **Step 1: Failing tests.** `aCachedThumbnailWithoutFactsIsNotDrawnAgain` (tablet): a thumbnail in the cache with no `.facts` beside it is shown from the cache, its facts are worked out without drawing the page (no `thumbnail … ms=` log line, `ThumbnailLoader` counts draws for tests: `internal val draws: Int`), and `.facts` is written. `storeSweepsOnlyThisFilesOlderVersions` (laptop): other files' thumbnails untouched, this file's older version gone.
- [ ] **Step 2: Run; they fail. Step 3: Implement** (the facts-only path is `loadFacts`, which exists for the list view). **Step 4: Pass. Step 5: Commit.** `git commit -m "library: a cached thumbnail isn't drawn again just for its facts; storing one doesn't list the whole cache"`

### Task 3: Thumbnails at the size they're shown (decision 3)

**Files:**
- Modify: `main/library/ThumbnailCache.kt` (`THUMB_WIDTH_PX = 240`, `THUMB_MAX_HEIGHT_PX = 340`)
- Test: `test/library/ThumbnailCacheTest.kt` (`tallPageGetsANarrowerThumbnail` updated), `androidTest/ui/ThumbnailLoaderTest.kt` (`ordinaryPageKeepsTheUsualWidth` updated)

- [ ] **Step 1:** Update the two tests to 240 and 340 and add `anOldBiggerThumbnailStillFits` (tablet): a 300 by 424 PNG already in the cache is shown, not drawn again (`draws` stays 0). Run: fail.
- [ ] **Step 2: Change the two constants**, with a comment giving the shown size (a tile's picture is about 233 by 330 px on the Note Air 6 C). The loader's check that drops too-tall cached thumbnails uses 450 still (the old limit), so 300 px ones aren't redrawn: name it `OLD_MAX_HEIGHT_PX` there with a comment.
- [ ] **Step 3: Pass. Measure** with `ThumbnailCheck` (PNG time and size at 240) and the memory the loader holds for a folder of 30 (`dumpsys meminfo`, Graphics). **Step 4: Commit.** `git commit -m "library: thumbnails made at the size they're shown"`

### Task 4: One go per folder (decision 1)

**Files:**
- Modify: `main/ui/ThumbnailLoader.kt`, `main/ui/LibraryActivity.kt` (`list`, `showListing`, `thumbnailReady`, `showPendingThumbnails`)
- Test: `androidTest/ui/ThumbnailLoaderTest.kt`

**Interfaces:**
- Produces: `ThumbnailLoader.warm(pdfs: List<PdfEntry>, budgetMs: Long): Int` — called on the disk thread; reads into memory, from the cache only, the thumbnails (and facts) of `pdfs` in order until done or `budgetMs` has passed; returns how many it read. Never draws. Safe beside the loader's own thread (`memory` and `factsInMemory` are already safe to share; a thumbnail read twice is harmless).
- Constants in `LibraryActivity`: `WARM_BUDGET_MS = 300L`, `FIRST_SCREEN_TILES = 35` (seven rows of five: more than a screen), `MISSING_WAIT_MS = 1500L`.

- [ ] **Step 1: Failing tablet tests.** `warmReadsCachedThumbnailsWithoutDrawing` (three PDFs, two cached: `warm` returns 2, `bitmapFor` gives both, `draws` stays 0); `warmStopsAtItsBudget` (budget 0 reads none).
- [ ] **Step 2: Implement `warm`.**
- [ ] **Step 3: Use it.** In `list`, on the disk thread after the listing, `thumbnails?.warm(<the PDFs among the first FIRST_SCREEN_TILES entries, from the top, or from the remembered scroll position if the folder is the one on show>, WARM_BUDGET_MS)` before posting `showListing`, so the tiles are bound with their pictures and drawn once.
- [ ] **Step 4: Missing ones in one go.** `thumbnailReady` no longer flushes every 120 ms: it flushes when no tile on screen is still waiting for its thumbnail (ask the loader: `ThumbnailLoader.waitingOnScreen(): Boolean`, true while any `onScreen` entry has neither a picture in memory nor a failure), or `MISSING_WAIT_MS` after the first one arrived, whichever comes first. Scrolling to new tiles starts a new wait. `TILE_BATCH_MS` goes.
- [ ] **Step 5: Tablet test** `aFolderOfMissingThumbnailsComesOnInOneGo` in `androidTest/ui/`: drive `LibraryActivity` on a scratch root holding 12 fresh copies of a test PDF (with `--es root`, as the existing library tests do, or through the activity's intent in an `ActivityScenario`), and assert from the log that `folder ready … goes=` is at most 2 (the folder, then the missing ones). If an activity-level test is out of reach, say so and check it by hand in Step 6.
- [ ] **Step 6: Measure** as in Task 1: first visit, second visit, back from a document. Into the results.
- [ ] **Step 7: Commit.** `git commit -m "library: a folder comes on with its thumbnails, and any missing ones in one go"`

### Task 5: Draw ahead (decision 2)

**Files:**
- Create: `main/library/LibraryWalk.kt`, `test/library/LibraryWalkTest.kt`
- Modify: `main/ui/ThumbnailLoader.kt` (a third kind of job, below `Show` and `Store`), `main/ui/LibraryActivity.kt` (start it)
- Test: `androidTest/ui/ThumbnailLoaderTest.kt`

**Interfaces:**
- Produces: `object LibraryWalk { fun pdfsUnder(root: File, limit: Int = 2000): Sequence<PdfEntry> }` — every PDF under `root`, folders in natural order, depth first, hidden and temporary files left out as `FolderListing` does, lazily (a folder is listed only when the walk reaches it), stopping after `limit`. `ThumbnailLoader.drawAhead(pdfs: Sequence<PdfEntry>)`: the loader's thread takes from it, one PDF per turn, only when it has no `Show` or `Store` job and isn't paused, and draws into the cache the ones missing; a failure is remembered (`failed`) and skipped.

- [ ] **Step 1: Failing laptop tests** (`LibraryWalkTest`): `walksFoldersInNaturalOrderDepthFirst`, `leavesOutHiddenAndTemporaryFiles`, `stopsAtTheLimit`, `listsAFolderOnlyWhenItGetsThere` (a folder added after the walk started is still found).
- [ ] **Step 2: Failing tablet tests** (`ThumbnailLoaderTest`): `drawingAheadFillsTheCache`; `drawingAheadStopsWhenPaused` (pause after the first: no more draws until resume); `aBrokenPdfIsSkippedNotRetried` (a garbage file among good ones: each good one drawn once, the broken one tried once); `onScreenTilesGoFirst` (a `show` during the pass is served next); `drawingAheadStopsWhenTheCardGoesOut` (the root deleted mid-walk: the pass ends, no exception, nothing deleted from the cache).
- [ ] **Step 3: Implement.** `LibraryActivity` starts it in `onResume` once the first listing is shown, with the library folder as root, and only if thumbnails are on. It runs at `THREAD_PRIORITY_LOWEST`. Log `ahead: drawn=<n> skipped=<n> ms=<total>` when the walk ends.
- [ ] **Step 4: Measure** on the owner's real library (read only: thumbnails go into the app's cache): the time the pass takes, and that writing in a document opened meanwhile shows nothing in `adb logcat -s Notes` between `pause` and `resume` of the loader. Into the results.
- [ ] **Step 5: Commit.** `git commit -m "library: thumbnails are drawn ahead for the whole library, quietly"`

### Task 6: A gentler refresh for the library (decision 4, trial)

**Files:**
- Create: `main/ui/PanelRefresh.kt`
- Modify: `main/ui/LibraryActivity.kt` (`showListing` when the grid changed, the thumbnail flush)

**Interfaces:**
- Produces: `object PanelRefresh { fun gentle(view: View) }` — `EpdController.invalidate(view, UpdateMode.REGAL)`, as `PenInput.showPage` does for a page turn. The second place in the app that uses the Onyx SDK; its comment says so and why.

- [ ] **Step 1:** Use it where the library's grid is redrawn for a new folder or for arriving thumbnails (in place of the plain redraw there). Nothing else in the library changes.
- [ ] **Step 2: Owner trial** (in Task 7's checklist): the release build of Task 5 and of Task 6 side by side, a folder opened, thumbnails coming on, scrolling. Keep Task 6 only if the owner prefers it; otherwise revert its commit.
- [ ] **Step 3: Commit.** `git commit -m "library: a gentler panel update when a folder or its thumbnails come on (trial)"`

### Task 7: Results, checklist, owner session (hard stop)

- [ ] **Step 1:** All laptop and tablet tests. `docs/superpowers/thumbnail-results.md`: the before and after table, what was kept.
- [ ] **Step 2:** `notes/TESTING-thumbnails.md`: open each course for the first time after installing (one go each?), again (instant?), back from a document, a folder of many PDFs scrolled, a document opened right after the library (no slower writing), the refresh trial (decision 4), and anything that looks different on the tiles.
- [ ] **Step 3:** Spec §6.1: thumbnails drawn ahead, shown in one go, made at the size shown. TODO.md: the 2026-11-13 item points here. Release build on the tablet. **Stop for the owner's session.**
