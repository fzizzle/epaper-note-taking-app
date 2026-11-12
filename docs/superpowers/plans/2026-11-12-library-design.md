# Library Design Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A library that tells you more at a glance and takes fewer taps: small topic folders opened out, progress and "has writing" on tiles, a colour per course, a Continue row with a picture of the page you left, a compact list view, your place kept when you come back, a helpful empty folder, and a setting to start straight in the last document.

**Architecture:** The folder tree on disk stays the only truth: nothing here writes to the owner's folders. What a tile shows beyond the file's name comes from three places the app already has or can derive: the remembered last page (app storage), facts read from the PDF when its thumbnail is made (thumbnail cache), and the path itself (course colour, opened-out folders). Each feature's rule is a small Android-free file with laptop tests; the tiles and rows are custom-drawn Views, as now.

**Tech Stack:** As now: plain framework Views (`GridView`, `BaseAdapter`), no AndroidX.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` §1 ("a thin layer … over ordinary PDF files in ordinary folders"), §2.2 (the app reflects the tree exactly, including outside changes), §4 (notes held nowhere but the owner's files; a crowded screen is a failure), §6.1.

**Owner's words (2026-11-12):** "I like all the library design features"; "by the continue button … it should have a thumbnail of where you were at last time"; open straight into the last document "as a setting. add this to the plan".

**Runs after** the [efficiency plan](2026-11-12-efficiency.md): its Tasks 7–9 change the same thumbnail and listing code.

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, `test/…` and `androidTest/…` likewise.

## Decisions for the owner (approve before Task 1)

1. **Opened-out folders.** A subfolder holding one to three PDFs and no folders is shown as its PDFs, side by side, each tile headed with the folder's name and then "workbook" or "answer key" in grey. A long-press on such a tile also offers "Folder: ‹name›" with Open, Rename, Move and Delete, so the folder itself is still reachable. A switch in Settings, on unless turned off. This reverses a line of spec §6.1 ("the app reflects that tree exactly") for how it looks, not for what's on disk.
2. **Progress bar.** Under a PDF's picture, a thin bar filled to the page you left, for files you've opened. Unopened files have none.
3. **"Has writing" mark.** A small filled corner on the tile's picture when the PDF holds your ink. It's read from the PDF itself when the thumbnail is made, so it's right for files that arrive from the laptop too. It needs thumbnails switched on.
4. **Course colour.** Each folder directly inside the library folder gets one of eight colours, chosen from its first word (the course code), as a stripe down the tile's left. Inside a course, the line under the top strip takes its colour. Task 3 puts the eight on the tablet for you to check they can be told apart.
5. **Continue row.** A picture of the page you left, with your ink, beside two lines: the name, and "page 12 of 30". At the library folder only, as now.
6. **List view.** A button in the top strip switches between tiles and a compact list: name, "12 / 30", the progress bar, the writing mark, no pictures. One column upright, two sideways. In the list, opened-out PDFs read "‹folder› — workbook".
7. **Coming back from a document** scrolls its tile into view if it's in the folder on show and marks it with the grey frame a long-pressed tile gets, until the next touch.
8. **An empty folder** says how to fill it: "Nothing here yet. Copy PDFs into ‹SD card › Workbooks › …› from a computer or a sync app, and they show up here by themselves."
9. **Start in the last document.** A tick in Settings, off unless turned on. With it on, starting the app opens the file you were last in, at its page; Back then leads to the library. If that file didn't finish opening the last time it was tried this way, the app starts in the library once and says so, so a file that crashes the app can't lock you out.

## Global Constraints

- Nothing is written into the owner's folders: positions, facts and pictures live in the app's own storage or cache (spec §6.1, §4).
- The grid updates by itself when files change on disk (spec §6.1): opened-out folders are watched like the folder on show.
- Everything that reads the owner's folders runs on the `disk` thread or the thumbnail thread, never the screen's.
- A listing that changes nothing redraws nothing (no e-ink refresh): entries compare equal when what they show is equal.
- No animations, libraries, fonts or image assets. Colour only where it carries meaning.
- Readable code; `LibraryActivity.kt` grows as little as possible.
- Toolchain: `source ~/.local/opt/android-env.sh`; `./gradlew :app:testDebugUnitTest`; tablet tests with `installDebug` + `installDebugAndroidTest` + `adb shell am instrument …`; to try library behaviour without touching the owner's Workbooks, back up `shared_prefs/library.xml` with `run-as`, use `--es root` on a scratch folder, then restore it; release build back afterwards. Never `git add -A`.

## Review Focus

1. **A PDF dropped by a sync app into an opened-out folder.** If it makes four, the group closes back into a folder tile by itself; if it makes three, the third tile appears (Task 4: `aFourthPdfClosesTheFolderAgain`, and the tablet check).
2. **The last document was deleted or its card is out, with "start in the last document" on.** The app starts in the library, quietly (Task 7: `aMissingFileStartsInTheLibrary`).
3. **A document that crashes the app as it opens, with the setting on.** The next start is the library, with a message, not the same crash (Task 7: `anOpenThatNeverFinishedIsNotTriedAgain`).
4. **The file behind Continue is renamed or moved in the library.** The row keeps its picture and its page (Task 5: `thePictureFollowsARename`).
5. **Two opened-out folders whose PDFs have the same names.** Their tiles are told apart by the folder heading, and a long-press acts on the right file (Task 4: `sameNamesInTwoFoldersStayApart`).

---

### Task 1: Facts about a PDF, kept with its thumbnail

**Files:**
- Create: `main/library/PdfFacts.kt`, `test/library/PdfFactsTest.kt`
- Modify: `main/library/ThumbnailCache.kt`, `main/ui/ThumbnailLoader.kt`, `main/doc/PdfPages.kt`, `main/pdf/PdfFile.kt`, `test/doc/Fakes.kt`, `main/ui/LastPageStore.kt`, `main/ui/WritingActivity.kt` (`onPause`)
- Test: `test/library/ThumbnailCacheTest.kt`, `androidTest/ui/ThumbnailLoaderTest.kt`

**Interfaces:**
- Produces:
  - `data class PdfFacts(val pages: Int, val hasInk: Boolean) { fun encode(): String; companion object { fun decode(text: String): PdfFacts? } }` (`"30 1"`; anything else decodes to null).
  - `ThumbnailCache.storeFacts(pdf: PdfEntry, facts: PdfFacts)` and `facts(pdf: PdfEntry): PdfFacts?`, in `<hash>-<size>-<modified>.facts` beside the PNG; `store` and `pruneMissing` delete old and orphaned `.facts` as they do PNGs.
  - `PdfPages.hasOwnItems(page: Int): Boolean` (in `PdfFile`: `idsFor(page).isNotEmpty()`). The writing-screen plan uses it too; whichever runs first adds it.
  - `ThumbnailLoader.factsFor(pdf: PdfEntry): PdfFacts?` (memory only, main thread), filled whenever a thumbnail is rendered or read from the cache; `onReady` fires for it as for a picture.
  - `LastPageStore.set(path, page, pageCount)`, `pageCount(path): Int` (0 when unknown) and `has(path): Boolean`. `move` and `remove` carry and drop the count too.

- [ ] **Step 1: Failing tests:** `factsRoundTrip`, `rubbishDecodesToNothing`; `factsAreStoredBesideTheThumbnail`, `aChangedFileHasNoFactsYet`, `pruneRemovesOrphanedFacts`; on the tablet `aRenderedThumbnailBringsItsFacts` (a 3-page test PDF with one stroke on page 1: `PdfFacts(3, true)`; the clean one: `PdfFacts(3, false)`).
- [ ] **Step 2: Implement.** `ThumbnailLoader.render` reads `pageCount` and looks for ink page by page, stopping at the first page with any, and at 300 pages (log `facts <name> ms=…`). A cached thumbnail without a `.facts` file is rendered again once. `WritingActivity.onPause` stores the page count with the page.
- [ ] **Step 3: All tests pass. Commit.** `git commit -m "library: a PDF's page count and whether it has writing, kept with its thumbnail"`

### Task 2: Progress and "has writing" on tiles

**Files:**
- Create: `main/library/TileMarks.kt`, `test/library/TileMarksTest.kt`
- Modify: `main/ui/LibraryTiles.kt` (`LibraryAdapter`, `TileView`), `main/ui/LibraryActivity.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces: `data class TileMarks(val progress: Float?, val hasInk: Boolean, val stripe: Int?)` (`stripe` is filled by Task 3) and `fun progressOf(opened: Boolean, lastPage: Int, pageCount: Int): Float?`: null when never opened or the count is unknown; else `(lastPage + 1) / pageCount`, within 0..1. `LibraryAdapter` takes `marksFor: (Entry) -> TileMarks`; `TileView.show(…, marks)` redraws only when they differ.

- [ ] **Step 1: Failing tests:** `neverOpenedHasNoBar`, `anUnknownCountHasNoBar`, `theFirstOfThirtyIsAThirtieth`, `theLastPageIsFull`, `aStalePagePastTheEndIsFull` (page 40 of 30 → 1f).
- [ ] **Step 2: Implement.** The bar: 3 dp tall, the picture's width, directly under it, grey with the reached part black. The mark: a filled black triangle, 12 dp, in the picture's top-right corner. `LibraryActivity` builds marks from one `LastPageStore` read per entry, done on the `disk` thread with the listing and kept with it, not per draw.
- [ ] **Step 3: Tablet check** in a scratch folder: an unopened PDF (no bar, no mark); open it, go to page 5 of 10, leave (half a bar); write a stroke, leave (the mark, once the thumbnail redraws); thumbnails off (bars stay, marks go). **Commit.** `git commit -m "library: tiles show how far you are and whether you've written"`

### Task 3: A colour per course

**Files:**
- Create: `main/library/CourseColours.kt`, `test/library/CourseColoursTest.kt`
- Modify: `main/ui/LibraryTiles.kt`, `main/ui/LibraryActivity.kt`

**Interfaces:**
- Produces: `object CourseColours { val PALETTE: List<Int>; fun courseOf(root: File, file: File): String?; fun indexFor(code: String): Int }`. `courseOf`: the first word of the name of the folder directly under `root` that holds `file` (or is it); null for `root` itself and for a PDF lying directly in `root`. `indexFor`: a sum of the code's upper-cased characters' values, modulo the palette's size, so it never changes between runs or app versions. `PALETTE`: eight ARGB colours, starting from `#C0392B, #D68910, #B7950B, #1E8449, #148F77, #1F618D, #6C3483, #AF4D98`.

- [ ] **Step 1: Failing tests:** `aTopFolderIsItsOwnCourse` (`courseOf(root, root/"BSA Building Stress Analysis") == "BSA"`), `aDeepFileBelongsToItsTopFolder`, `theLibraryFolderHasNoCourse`, `aLooseFileHasNoCourse`, `theSameCodeAlwaysGetsTheSameColour` (`indexFor("FLM") == indexFor("flm")`, and equals a number written in the test), `everyIndexIsInThePalette`.
- [ ] **Step 2: Implement.** At the library folder, each folder tile gets a 4 dp stripe of its colour down the left of its folder mark. Inside a course, the 2 px line under the top strip is 4 px of the course's colour; at the library folder it's the grey it is now.
- [ ] **Step 3: Tablet check with the owner's real course names** (read-only: just look): are your courses told apart on the colour screen? If two land on the same colour or two colours look alike, change `PALETTE` (not the rule) and note the pick in the findings log. **Commit.** `git commit -m "library: a colour for each course"`

### Task 4: Small folders opened out

**Files:**
- Modify: `main/library/FolderListing.kt`, `main/library/TileNames.kt`, `main/ui/LibraryTiles.kt`, `main/ui/LibraryActivity.kt` (`list`, `tilesOnScreenChanged`), `main/ui/LibraryChanges.kt` (`showMenu`), `main/ui/LibrarySettings.kt`
- Test: `test/library/FolderListingTest.kt`, `test/library/TileNamesTest.kt`

**Interfaces:**
- Produces:
  - `PdfEntry` gains `val group: File? = null`: the opened-out folder it lies in.
  - `FolderListing.list(folder: File, openOutUpTo: Int = 0): List<Entry>`; `const val OPEN_OUT_UP_TO = 3`. A subfolder with no visible subfolders and 1..`openOutUpTo` PDFs is replaced, in its place in the order, by its PDFs, each with `group` set. With 0 it lists as today.
  - `TileNames.grouped(entries: List<PdfEntry>): Map<PdfEntry, Pair<String?, String>>`: heading (the group folder's name, null outside a group) and label (within a group, `labels` over that group's names; outside, over the ungrouped names, as today).
  - `LibraryPrefs.openOutSmallFolders: Boolean` (default true).

- [ ] **Step 1: Failing tests:** `aFolderOfTwoPdfsIsOpenedOut` (in the folder's place, both with `group`), `aFolderWithASubfolderStaysAFolder`, `anEmptyFolderStaysAFolder`, `aFourthPdfClosesTheFolderAgain`, `hiddenAndTemporaryFilesDontCount`, `withZeroNothingIsOpenedOut`, `sameNamesInTwoFoldersStayApart` (two groups each holding "workbook.pdf": the entries differ and keep their own files); `groupedLabelsDropTheSharedStartWithinEachGroup` ("BSA Beams — workbook", "BSA Beams — answer key" in folder "Beams" → heading "Beams", labels "workbook", "answer key").
- [ ] **Step 2: Implement the listing and the names.**
- [ ] **Step 3: Tiles.** A grouped tile's text is the heading in black (one line, cut at the end) over the label in grey. Tiles of one group sit next to each other because they're adjacent in the list; nothing else joins them.
- [ ] **Step 4: Watching and menus.** `tilesOnScreenChanged` also watches the `group` folders of the tiles on screen (within `FolderWatch`'s limit; past it, Refresh catches up). The long-press menu of a grouped tile adds a submenu "Folder: ‹name›" with Open (goes into the real folder, listed there without opening out), Rename, Move and Delete, acting on the folder through the calls a folder tile uses. Settings gets the switch "Open out small folders"; changing it lists again.
- [ ] **Step 5: Tablet check on a scratch tree** shaped like the owner's (course › topic › two PDFs, plus one topic with four PDFs and one with a subfolder): the two-PDF topics show as pairs; copy a third PDF in over adb: it appears; a fourth: the group becomes a folder tile; delete back to two: a pair again; the folder submenu's four actions; the switch off: folders as before. Time `library listed … ms=` for a course of ten topics against today's.
- [ ] **Step 6: Spec §6.1. Commit.** `git commit -m "library: a folder of up to three PDFs is shown opened out"`

### Task 5: Continue shows the page you left

**Files:**
- Create: `main/ui/ContinuePicture.kt`, `androidTest/ui/ContinuePictureTest.kt`
- Modify: `main/ui/WritingActivity.kt` (`onPause`), `main/ui/LibraryActivity.kt` (`buildLibrary`, `showListing`), `main/ui/LibraryChanges.kt` (`lastOpenedMoved`), `main/ui/LibrarySettings.kt` (`LibraryPrefs`)

**Interfaces:**
- Produces: `class ContinuePicture(cacheDir: File, prefs: LibraryPrefs) { fun store(pdf: File, png: ByteArray); fun load(pdf: File): Bitmap?; fun moved(from: File, to: File) }`. One file, `continue.png`, written through a temp file and a rename; `LibraryPrefs.continuePictureOf: File?` says whose page it shows; `load` gives null for any other file.

- [ ] **Step 1: Failing tablet tests:** `aStoredPictureLoadsForItsFile`, `anotherFileGetsNoPicture`, `thePictureFollowsARename`, `aHalfWrittenPictureIsNeverLoaded` (a leftover temp file alone loads nothing).
- [ ] **Step 2: Implement `ContinuePicture`.**
- [ ] **Step 3: Leaving.** In `WritingActivity.onPause`, before `session.saveNow()`: one `session.runOnDocument` task renders the page on show 300 px wide (`render(page, 300, smoothInk = true)`, the page's true colours: no contrast step), compresses it to PNG and stores it. It's on the worker, so leaving waits for nothing.
- [ ] **Step 4: The row.** `continueRow` becomes a row with the picture (96 dp tall, a 1 px grey frame, as tiles have) and two lines: the file's name in black, "Continue · page 12 of 30" in grey (just "Continue" when the count isn't known). The picture is decoded on the `disk` thread with the listing. The row is only rebuilt when the file, page or picture changed. Without a picture it is the two lines alone.
- [ ] **Step 5: Tablet check:** write on page 7, leave: the row shows page 7 with the ink; open another file and leave: the row follows; rename the file in the library: the row keeps its picture; delete it: the row goes.
- [ ] **Step 6: Commit.** `git commit -m "library: Continue shows the page you left"`

### Task 6: List view, your place kept, and the empty folder

**Files:**
- Create: `main/ui/LibraryRows.kt` (`RowView`)
- Modify: `main/ui/StripButton.kt` (`Glyph.LIST`, `Glyph.TILES`), `main/ui/LibraryTiles.kt` (`LibraryAdapter`), `main/ui/LibraryActivity.kt`, `main/ui/LibrarySettings.kt` (`LibraryPrefs.listView`, default false)

- [ ] **Step 1: List view.** A strip button before Refresh switches and shows the other mode's glyph. In list mode `grid.numColumns` is 1 upright and 2 sideways, and the adapter hands out `RowView`s: 56 dp tall, the course stripe, a small folder or page mark, the name (a grouped PDF as "‹folder› — ‹label›"), and at the right "12 / 30" over the progress bar, with the writing mark before the name. A folder's right side is its counts, as its tile says them. The thumbnail loader is given no tiles to show while the list is up (facts already in memory are used; none are fetched).
- [ ] **Step 2: Your place kept.** After the listing that follows a return from a document (`backFromDocument`): if `prefs.lastOpened` is among the entries, the adapter marks it (the grey frame `LibraryChanges.showMenu` puts on a long-pressed tile, in rows too), and `grid.setSelection(position)` only if it isn't on screen already. The mark goes at the next touch on the grid or change of folder.
- [ ] **Step 3: The empty folder.** `emptyText` for a folder that exists and is empty: "Nothing here yet.\n\nCopy PDFs into\n‹`Volumes.whereIs(…)` of the folder on show›\nfrom a computer or a sync app, and they show up here by themselves." The two "library folder missing" texts stay.
- [ ] **Step 4: Tablet check:** switch views in a folder of 30: one refresh each way, the choice kept after a restart; rotate the tablet in list view; open the 25th PDF, come back: it's on screen and framed, and one tap elsewhere clears the frame; a new empty folder shows the hint with the right path, on the card and on the tablet's storage.
- [ ] **Step 5: Commit** (one per step). `git commit -m "library: a compact list view"`, `"library: back from a document, its tile is in view and marked"`, `"library: an empty folder says how to fill it"`

### Task 7: Start in the last document

**Files:**
- Create: `main/library/LaunchOpen.kt`, `test/library/LaunchOpenTest.kt`
- Modify: `main/ui/LibrarySettings.kt` (`LibraryPrefs`, the settings box), `main/ui/LibraryActivity.kt` (`onCreate`, `onResume`), `main/ui/WritingActivity.kt`

**Interfaces:**
- Produces: `LibraryPrefs.openLastAtLaunch: Boolean` (default false) and `launchOpenUnfinished: Boolean`; `object LaunchOpen { enum class Start { LIBRARY, LAST_DOCUMENT, LIBRARY_AFTER_FAILED_OPEN }; fun decide(freshStart: Boolean, settingOn: Boolean, lastExists: Boolean, unfinished: Boolean): Start }`.

- [ ] **Step 1: Failing tests:** `offMeansTheLibrary`, `onWithAFileOpensIt`, `aMissingFileStartsInTheLibrary`, `anOpenThatNeverFinishedIsNotTriedAgain` (`LIBRARY_AFTER_FAILED_OPEN`), `comingBackToARunningAppIsNotAFreshStart` (`freshStart = false` → `LIBRARY`).
- [ ] **Step 2: Implement.** `freshStart` is `savedInstanceState == null` in `onCreate` with the launcher's intent (`ACTION_MAIN`), taken once. In the first `onResume` with file access granted: on `LAST_DOCUMENT`, set `launchOpenUnfinished = true` with `commit()` (it must be on disk before the document opens) and call `open(lastOpened)`; on `LIBRARY_AFTER_FAILED_OPEN`, clear the flag and toast "Didn't reopen ‹name›: it didn't finish opening last time." `WritingActivity` clears the flag where it sets `documentShown = true`, and on `OpenFailed`. Settings gets the tick "Start in the last document".
- [ ] **Step 3: Tablet check:** tick on, force-stop, start from the launcher: the document at its page, Back gives the library with its tile framed; home and back into the running app: wherever you were; the file deleted over adb, start: the library; set the flag by hand (`run-as`, debug build), start: the library and the message, and the start after that opens the document again; tick off: the library.
- [ ] **Step 4: Spec §6.1. Commit.** `git commit -m "library: a setting to start in the last document"`

### Task 8: Spec, checklist, owner session (hard stop)

- [ ] **Step 1: Spec §6.1** rewritten for decisions 1–9; §11 lists the designer's calls (three PDFs as "small", the eight colours, the bar's and mark's look, 96 dp for the Continue picture).
- [ ] **Step 2:** `notes/TESTING-library-design.md`: each task's check in plain words, L1–L7, on a scratch folder for anything that renames, moves or deletes; then a look at the real Workbooks with one question per decision ("are pairs easier than folders?", "can you tell the courses apart?").
- [ ] **Step 3:** TODO.md section; all laptop and tablet tests once more; `library listed` and `library shown` times beside the efficiency results; release build on the tablet. **Stop for the owner's session.**
