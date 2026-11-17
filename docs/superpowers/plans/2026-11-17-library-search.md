# Library Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A search of the whole library by file name that answers in under 100 ms with 20,000 PDFs, from a saved name index kept in step with the card.

**Architecture:** The rules (matching, the index check's decisions, result order) are plain Kotlin in `library/` with laptop tests, written against two small interfaces: the store (`IndexStore`) and the disk (`IndexDisk`). The SQLite store, the index's threads and the search screen live in `ui/` beside the other Android parts, with tablet tests. The index is a cache in Jot's private storage; nothing reads it to act on the owner's files.

**Tech Stack:** As now: Kotlin, Android's built-in SQLite (`android.database.sqlite`), JUnit 4. No new libraries.

**Spec:** `docs/superpowers/specs/2026-11-17-library-search-design.md` (read it first; § numbers below are its sections). Background: spec `2026-11-07-epaper-pdf-notes-design.md` §6.1; measurements in `docs/superpowers/findings-log.md` (2026-11-17).

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, `test/…` and `androidTest/…` likewise.

## Global Constraints

- The index is a cache: if it can't be opened, its layout version is old, or the library folder setting changes, it is deleted and built again (§7.1). Opening, renaming, moving and deleting always act on the card (anti-goal 5).
- A search never reads the card; it answers from the index. Target: under 100 ms with 20,000 PDFs (§2).
- A card that's out is not a deleted library: a check that finds the library folder missing changes nothing (§7.2, anti-goal 2).
- Hidden names and temporary files (`FolderListing.isHidden`) are left out; only `.pdf` (any case); linked folders are not followed (§7.1).
- Match key: name without `.pdf`, NFD with combining marks dropped, then `lowercase(Locale.ROOT)`. Every query word must be in the key, any order. Empty query matches nothing (§6).
- Refreshes: one per search. Only when the check takes longer than the 1 s wait: a partial update of the note line alone when it clears, and one list redraw if the hits changed (§6, anti-goal 4).
- The check and the build run on a background-priority thread of their own, never `disk`; they write one transaction per 50 folders; they pause while a document is open (§7.3).
- `library/`, `storage/` and `tools/` stay free of Android classes. Readable code: small files, plain names, comments that say why.
- Looks follow the library's: black outlines, `LibraryLook`, glyphs drawn in code (`StripButton`).
- Toolchain: `source ~/.local/opt/android-env.sh`; `cd notes && ./gradlew :app:testDebugUnitTest -q`. Tablet tests with `installDebug` + `installDebugAndroidTest` + `adb shell am instrument -w -e class <Class> com.fzizzle.epapernotes.test/androidx.test.runner.AndroidJUnitRunner`. Never `git add -A`; wait for a quiet tablet before installing. Never the owner's `SD card/Workbooks`: scratch folders only, `shared_prefs/library.xml` backed up with `run-as` and restored. Release build back on the tablet at the end.

## Small amendments to the design (made while planning; tell the owner)

Copy these into the design doc in Task 1's commit:

1. §7.2 "Every folder the library lists gives the index what it just read… No extra card reads": instead, after each listing the index re-reads that one folder on its own thread (one more read, 2–4 ms). The library's listing opens small folders out and doesn't carry folder times, so handing it over would mean changing `FolderListing`.
2. §7.2 "When the library is first shown after Jot starts": that check is a **full re-read**. While Jot wasn't running, the card may have been in a laptop, which is the case full re-reads are for.
3. §6 "the last row says Showing 200 of 1,234…": that text goes in the note line above the results instead of a row.
4. §11: the note line is updated with `EpdController.invalidate(<that view>, UpdateMode.GU)` (`PanelRefresh.line`); `PenInput.refreshAlone` no longer exists.

## Review Focus

1. **The card is taken out while a check or the first build is running.** The check stops with `RootMissing` partway, nothing already indexed is dropped, and the next check after the card is back picks up (Task 2: `aRootThatGoesMissingMidCheckDropsNothing`).
2. **A folder renamed on a laptop, holding thousands of PDFs.** Its old path's rows are all gone and its new path's are all there after one check; a search never shows both (Task 2: `aFolderRenamedOnALaptopMovesInTheIndex`; Task 3: `dropRemovesEverythingUnder`).
3. **A query with `%`, `_` or `\` in it** ("50%", "my_notes") matches those characters literally, in the store as in `NameMatch` (Task 1: `likeSpecialsAreEscaped`; Task 3: `percentAndUnderscoreAreLiteral`).
4. **Two searches in a row while a slow check runs.** Only the newest's answer is shown, and the check isn't started twice (Task 4: `aSearchDuringACheckWaitsForThatCheck`; Task 6 step 7 by hand).
5. **A result tapped whose file was deleted on a laptop a moment ago.** "That file isn't there any more.", its folder re-read, the search run again without that row; nothing opens (Task 6 step 7 by hand).

---

### Task 1: Matching names (`NameMatch`) and the result order

**Files:**
- Create: `main/library/NameMatch.kt`, `main/library/IndexTypes.kt`
- Test: `test/library/NameMatchTest.kt`
- Modify: `docs/superpowers/specs/2026-11-17-library-search-design.md` (the amendments above)

**Interfaces:**
- Produces, in `IndexTypes.kt`:
  - `data class Hit(val folder: String, val fileName: String)`: `folder` is the path from the library folder with `/` between names, `""` for the library folder itself; `fileName` includes `.pdf`.
  - `data class Matches(val total: Int, val hits: List<Hit>)`
- Produces, `object NameMatch`:
  - `fun key(name: String): String`: the match key of a name without `.pdf`.
  - `fun words(query: String): List<String>`: the query split on whitespace, each through `key`, empties dropped.
  - `fun matches(name: String, query: String): Boolean`
  - `fun likePattern(word: String): String`: `%word%` with `\`, `%` and `_` escaped by `\` (for `LIKE ? ESCAPE '\'`).
  - `fun arrange(hits: List<Hit>, limit: Int): List<Hit>`: natural order of the folder path (compared name by name with `NaturalOrder`, so `Week 2/` comes before `Week 10/`), then of the file name; the first `limit`.
  - `fun folderShown(folder: String): String`: `"ML Machine Learning › Confusion-Matrix Metrics"`; `""` for the library folder.
- Constant: `const val SEARCH_LIMIT = 200` in `NameMatch`.

- [ ] **Step 1: Failing laptop tests** in `NameMatchTest`:
  - `everyWordMustBeInTheName`: `matches("Thermo Week 2 answers", "week ther")` true; `matches("Thermo Week 2 answers", "week chem")` false.
  - `capitalsAndAccentsAreIgnored`: `matches("Résumé", "resume")` and `matches("resume", "RÉSUMÉ")` true.
  - `anEmptyQueryMatchesNothing`: `matches("a", "")` and `matches("a", "   ")` false; `words("  ")` empty.
  - `likeSpecialsAreEscaped`: `likePattern("50%")` == `"%50\\%%"`; `likePattern("a_b")` == `"%a\\_b%"`; `likePattern("a\\b")` == `"%a\\\\b%"`.
  - `arrangeOrdersByFolderThenName`: hits in folders `"Week 10"`, `"Week 2"`, `""` come back `""`, `"Week 2"`, `"Week 10"`; within one folder `"Lecture 10.pdf"` after `"Lecture 2.pdf"`; `arrange(…, 2)` keeps the first two.
  - `folderShownJoinsWithArrows`: `folderShown("A/B")` == `"A › B"`, `folderShown("")` == `""`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest -q --tests '*NameMatchTest'`. Expected: fails to compile (`NameMatch` missing).
- [ ] **Step 3: Implement** `IndexTypes.kt` and `NameMatch` as above (`java.text.Normalizer`, `\p{Mn}` removed).
- [ ] **Step 4: Run** the same command. Expected: all pass.
- [ ] **Step 5: Copy the four amendments** into the design doc (§6, §7.2, §11) as decisions made while planning.
- [ ] **Step 6: Commit.** `git add` the four files; `git commit -m "search: how names are matched and results ordered"`.

### Task 2: The index check (`IndexCheck`), on the laptop

**Files:**
- Create: `main/library/IndexCheck.kt`, `main/library/FileIndexDisk.kt`
- Modify: `main/library/IndexTypes.kt`
- Test: `test/library/IndexCheckTest.kt`, `test/library/MemoryIndexStore.kt` (a fake store, test sources only)

**Interfaces:**
- Consumes: `NameMatch.key`, `Hit`, `Matches` (Task 1).
- Produces, in `IndexTypes.kt`:
  - `data class IndexedPdf(val fileName: String, val size: Long, val modified: Long)`
  - `data class DiskFolder(val time: Long, val folders: List<String>, val pdfs: List<IndexedPdf>)`: one listing; `folders` are names.
  - `data class KnownFolder(val time: Long, val readAt: Long, val folders: List<String>)`: `folders` are the names of its subfolders the index holds.
  - `interface IndexDisk { fun timeOf(folder: File): Long?; fun read(folder: File): DiskFolder? }`: null when not there or not a folder.
  - `interface IndexStore { fun folder(path: String): KnownFolder?; fun put(path: String, read: DiskFolder, readAt: Long); fun drop(path: String); fun <T> inBatch(block: () -> T): T; fun search(words: List<String>, cap: Int): Matches; var complete: Boolean }`. `put` creates or updates the folder's row (under its parent's) and replaces its PDFs; it leaves subfolder rows alone. `drop` removes the folder and everything under it. `complete` is true once a full re-read has finished.
  - `sealed interface CheckResult { data class Done(val foldersRead: Int) : CheckResult; data object RootMissing : CheckResult }`
- Produces, `class IndexCheck(root: File, store: IndexStore, disk: IndexDisk, clock: () -> Long, pause: () -> Boolean = { false }, batch: Int = 50)`:
  - `fun run(full: Boolean): CheckResult`
  - `fun reread(folders: List<File>)`: each folder (if inside the library) re-read as a "changed" folder would be, including dropping vanished subfolders and reading new ones in full; a folder no longer there is dropped.
  - `const val TRUST_AFTER_MS = 2000L` (companion).
- Produces, `class FileIndexDisk : IndexDisk`: `read` lists with `Files.newDirectoryStream` and `Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)` (one read per entry for kind, size and time); links, hidden and temporary names (`FolderListing.isHidden`) and non-PDFs left out.

The check, from the library folder down (the algorithm the tests pin):

```
run(full):
  if disk.timeOf(root) == null: return RootMissing
  visit(""), batching store writes `batch` folders per inBatch; between batches, while pause(): sleep 500 ms
  if full: store.complete = true
visit(path):
  t = disk.timeOf(dir) ?: (if disk.timeOf(root) == null: stop with RootMissing else store.drop(path); return)
  known = store.folder(path)
  if !full && known != null && t == known.time && known.readAt - t >= TRUST_AFTER_MS:
      known.folders.forEach { visit(path/it) }; return
  read = disk.read(dir) ?: (same as a null time above; return)
  store.put(path, read, clock())
  (known?.folders ?: []) - read.folders -> store.drop each
  read.folders.forEach { visit(path/it) }   // new ones have no row, so they're read in full
```

Between visits, if `disk.timeOf(root)` turns null (checked once per batch, and before any drop), stop and return `RootMissing`: a card taken out mid-check must never look like folders deleted.

- [ ] **Step 1: The fake store** `MemoryIndexStore : IndexStore` (maps by path; `search` uses `NameMatch` on keys, returns `Matches(total, first cap)`); and failing tests in `IndexCheckTest` using a `TemporaryFolder`, `FileIndexDisk`, and a clock the test sets:
  - `aFirstFullReadFindsEveryPdf`: 3 folders deep, 5 PDFs; `run(full = true)` → `Done`; `search(["week"], 100)` finds the two named "Week…"; `complete` true.
  - `anUnchangedFolderIsNotListedAgain`: wrap `FileIndexDisk` to count `read` calls; after a full run with the clock at folder time + 10 s, `run(false)` reads 0 folders.
  - `aChangedFolderIsListedAndItsPdfsReplaced`: add a PDF, set the folder's `setLastModified` +5 s; `run(false)` → only that folder read; the new PDF found, a deleted one gone.
  - `aFolderReadTooSoonAfterItsTimeIsListedAgain`: clock = folder time + 1 s at the first run; second run (folder time unchanged) reads it again.
  - `aFolderRenamedOnALaptopMovesInTheIndex`: rename `A/Sub` to `A/Moved` with 3 PDFs in it, parent time +5 s; after `run(false)`, hits are under `A/Moved` only.
  - `aFolderGoneWithoutItsParentsTimeMovingIsDropped`: delete `A/Sub`, set `A`'s time back to what it was; `run(false)` drops `A/Sub`.
  - `aMissingRootChangesNothing`: full run, then delete the root folder; `run(false)` → `RootMissing`; `search` still finds everything.
  - `aRootThatGoesMissingMidCheckDropsNothing`: a disk wrapper whose `timeOf(root)` turns null after the 2nd folder, `batch = 1`; `run(true)` → `RootMissing`; folders read before still there, nothing dropped.
  - `hiddenTemporaryAndLinkedAreLeftOut`: `.hidden.pdf`, a `SafeSave` temp name, `notes.txt`, `.git/a.pdf`, a symlink to a folder holding a PDF: none indexed.
  - `rereadPicksUpAChangeWithoutAFolderTimeMoving`: add a PDF and put the folder's time back; `run(false)` misses it; `reread(listOf(folder))` finds it.
  - `aFullRunTrustsNoFolderTime`: as the previous test, `run(true)` finds it.
- [ ] **Step 2: Run** `--tests '*IndexCheckTest'`. Expected: fails to compile.
- [ ] **Step 3: Implement** the types, `FileIndexDisk` and `IndexCheck`.
- [ ] **Step 4: Run** `--tests '*IndexCheckTest'` and then the whole laptop suite. Expected: all pass.
- [ ] **Step 5: Commit.** `git commit -m "search: the index check, which re-reads only folders whose time moved"`.

### Task 3: The SQLite store (`NameIndexDb`), on the tablet

**Files:**
- Create: `main/ui/NameIndexDb.kt`
- Test: `androidTest/ui/NameIndexDbTest.kt`

**Interfaces:**
- Consumes: `IndexStore`, `DiskFolder`, `KnownFolder`, `Matches`, `Hit` (Task 2), `NameMatch.key`, `NameMatch.likePattern` (Task 1).
- Produces: `class NameIndexDb private constructor(…) : IndexStore, Closeable` with `companion fun open(context: Context, root: File, name: String = "library-index.db"): NameIndexDb`. `open` deletes and remakes the database when it can't be opened, its layout version differs (`LAYOUT = 1`), or the root it was made for (kept in a `meta` table) isn't `root.path`. Write-ahead logging on (`enableWriteAheadLogging`), so `search` on one thread doesn't wait for `put` on another.

Tables (names are the builder's; these are the needs): folders (id, path unique, parent id, time, read_at); pdfs (id, folder id, file name, match key, size, modified), with an index on folder id; meta (key, value: layout, root, complete). `drop(path)` deletes the folder row, every row whose path starts with `path + "/"`, and their PDFs. `search` is one `SELECT … WHERE key LIKE ? ESCAPE '\' AND key LIKE ? …` joined to folders for the path, `LIMIT cap`, plus a `COUNT(*)` with the same `WHERE` for `total`.

- [ ] **Step 1: Failing tablet tests** in `NameIndexDbTest` (each opens a database named for the test in `context.cacheDir`, deleted after):
  - `putThenSearchFindsByEveryWord`: put `""` with `Thermo Week 2 answers.pdf`, `A` with `Résumé.pdf`; `search(words("week ther"))` → one hit, folder `""`; `search(words("resume"))` → folder `"A"`.
  - `putReplacesAFoldersPdfs`: put `A` with x, y; put `A` with y, z → hits y, z only.
  - `dropRemovesEverythingUnder`: `A`, `A/B`, `A/B/C`, `AB` each with a PDF; `drop("A")` → only `AB`'s PDF left (`AB` isn't under `A`).
  - `knownFolderListsItsSubfolders`: after puts of `A`, `A/B`, `A/C`, `folder("A")!!.folders` == `["B", "C"]` in any order, with its time and readAt.
  - `percentAndUnderscoreAreLiteral`: `50% done.pdf`, `500 done.pdf`, `my_notes.pdf`, `myXnotes.pdf`; `"50%"` finds only the first; `"my_notes"` only the third.
  - `aDifferentRootStartsAfresh`: open for `/x`, put; open again for `/y`; `search` finds nothing, `complete` false.
  - `capCountsAllButReturnsCap`: 300 matching PDFs; `search(…, cap = 50)` → `total` 300, 50 hits.
  - `twentyThousandNamesAnswerInUnder100Ms`: 4,000 folders × 5 PDFs put in batches; time `search(words("week 3"))` five times; median under 100 ms; logged as `index search ms=<n> found=<n>`.
- [ ] **Step 2: Run** on the tablet (`-e class com.fzizzle.epapernotes.ui.NameIndexDbTest`). Expected: fails to compile.
- [ ] **Step 3: Implement** `NameIndexDb`.
- [ ] **Step 4: Run** the class on the tablet. Expected: all pass; note the search time.
- [ ] **Step 5: Commit.** `git commit -m "search: the name index's SQLite store"`.

### Task 4: The index's threads (`NameIndex`)

**Files:**
- Create: `main/ui/NameIndex.kt`
- Modify: `main/doc/DocumentSession.kt` (companion: `fun anyOpen(): Boolean = openSessions.isNotEmpty()`)
- Test: `androidTest/ui/NameIndexTest.kt`

**Interfaces:**
- Consumes: `IndexCheck`, `FileIndexDisk`, `CheckResult` (Task 2), `NameIndexDb` (Task 3), `NameMatch` (Task 1).
- Produces: `class NameIndex(context: Context, val root: File)` with
  - `companion fun forRoot(context: Context, root: File): NameIndex`: one per process; a different root closes the old one and opens a new one.
  - `fun check(full: Boolean)`: starts a check on the index thread unless one is running (a full asked for while a normal one runs is run after it).
  - `fun reread(folders: List<File>)`: queued on the index thread.
  - `fun search(query: String, onAnswer: (SearchAnswer) -> Unit)`: on the main thread; see below.
  - `data class SearchAnswer(val query: String, val matches: Matches, val note: Note, val rootMissing: Boolean)` and `enum class Note { NONE, CHECKING, STILL_READING }`.
  - `const val CHECK_WAIT_MS = 1000L`; `const val SEARCH_CAP = 5000`: the store is asked for at most this many hits, which are put in order with `NameMatch.arrange(hits, SEARCH_LIMIT)` before an answer goes out; `total` is the store's count. (With more than 5,000 matches the 200 shown are in order among the 5,000 fetched; the note line asks for more words anyway.)
- Threads: one index thread (`Executors.newSingleThreadExecutor` with a thread factory setting `Process.THREAD_PRIORITY_BACKGROUND`) for checks and rereads; one reader thread for searches. `IndexCheck`'s `pause` is `DocumentSession::anyOpen`.

`search(query)`: if `StorageVolumes`/`root.isDirectory` says the library folder is missing, answer `rootMissing = true` at once. Otherwise start `check(full = false)` (or join the one running), and on the reader thread wait up to `CHECK_WAIT_MS` for it. Done in time: search the store, answer once with `Note.NONE`. Not done: search the store and answer with `Note.CHECKING` (`STILL_READING` while `complete` is false); when the check ends, search again and answer a second time with `Note.NONE`. Answers arrive on the main thread; the caller drops those for an older query.

- [ ] **Step 1: Failing tablet tests** in `NameIndexTest`, on a scratch folder in `context.getExternalFilesDir(null)` and a test database:
  - `aSearchAfterACheckAnswersOnce`: 3 PDFs, `check(true)` and wait; `search("week")` → one answer, `Note.NONE`.
  - `aFileAddedSinceIsFoundBecauseTheSearchChecksFirst`: after that, add a PDF to a subfolder and move its time +5 s; `search` finds it in its only answer.
  - `aSlowCheckGivesTwoAnswers`: a `NameIndex` built with a disk whose `timeOf` sleeps 30 ms per folder over 60 folders: first answer `CHECKING`, second `NONE` (test-only constructor taking `IndexDisk`).
  - `aSearchDuringACheckWaitsForThatCheck`: count checks started: two searches 10 ms apart start one check.
  - `aMissingRootAnswersRootMissing`.
- [ ] **Step 2: Run** on the tablet. Expected: fails to compile.
- [ ] **Step 3: Implement** `NameIndex` and `DocumentSession.anyOpen`.
- [ ] **Step 4: Run** the class on the tablet, then the whole tablet suite (`-e notPackage com.fzizzle.epapernotes.check`). Expected: all pass.
- [ ] **Step 5: Commit.** `git commit -m "search: the index's threads, and a search that checks the card first"`.

### Task 5: Keeping the index in step from the library

**Files:**
- Modify: `main/ui/LibraryActivity.kt` (`onResume`, `list`, `refresh`, `useRoot`, `onStart`/`onStop`), `main/ui/LibraryChanges.kt` (constructor, each change)

**Interfaces:**
- Consumes: `NameIndex.forRoot`, `check`, `reread` (Task 4).
- Produces: `LibraryChanges(…, touched: (List<File>) -> Unit)`: after a change that was `Done` or `Made`, the folders it touched: rename, delete, fresh attempt → the target's parent; move → the target's parent and the folder moved into; new folder → its parent.

- [ ] **Step 1:** `LibraryActivity` keeps `index: NameIndex?`, set from `NameIndex.forRoot(this, root)` whenever the root is set (`onResume`, `useRoot`).
- [ ] **Step 2: When checks run** (§7.2 with amendment 2): the first `onResume` with file access in this process → `check(full = true)` (a process-wide flag in `NameIndex`'s companion); `refresh()` → `check(full = true)`; a `BroadcastReceiver` for `Intent.ACTION_MEDIA_MOUNTED` (data scheme `file`), registered in `onStart` and removed in `onStop`, → `check(full = true)` when the root is on the volume that came in (`Volumes.holding`). Searches start their own (Task 4).
- [ ] **Step 3: Between checks:** at the end of `list`'s disk work, `index?.reread(listOf(shown))`; `LibraryChanges`' `touched` → `index?.reread(it)`.
- [ ] **Step 4: Check on the tablet** (debug build, scratch library on the card `/storage/3331-3031/jot-search-scratch` via `--es root`, `library.xml` backed up): `adb logcat -s Notes` shows one `index check full=true foldersRead=<n> ms=<n>` line at start (add that log line in `NameIndex`); rename a PDF in Jot and see `index reread` for its folder; Refresh logs a full check; take the card out and in: one full check.
- [ ] **Step 5:** Laptop suite and tablet suite pass.
- [ ] **Step 6: Commit.** `git commit -m "search: the library keeps the name index in step"`.

### Task 6: The search button and the results

**Files:**
- Modify: `main/ui/StripButton.kt` (`Glyph.SEARCH`), `main/ui/LibraryActivity.kt`, `main/ui/LibraryTiles.kt` (`BreadcrumbBar.showText`), `main/ui/LibraryRows.kt` (a long detail shortened from its start), `main/ui/LibraryChanges.kt` (`askForText` made `internal` for the search dialog), `main/ui/PanelRefresh.kt` (`fun line(view: View)`: `EpdController.invalidate(view, UpdateMode.GU)`)

**Interfaces:**
- Consumes: `NameIndex.search`, `SearchAnswer`, `Note` (Task 4); `NameMatch.arrange`, `folderShown`, `SEARCH_LIMIT` (Task 1).
- Produces: `fun BreadcrumbBar.showText(text: String)`: one segment that, tapped, calls the bar's `onTap` with the folder last given to `show`.

Behaviour is §6 of the design; the decisions it leaves to here:

- State in `LibraryActivity`: `private var search: SearchState?` (`data class SearchState(val query: String, val asked: Int)`), `private var lastQuery = ""` (memory only), and a counter `searched` like `listed`.
- Results are `PdfEntry(File(root, hit.folder + "/" + hit.fileName), name without .pdf, 0, 0)` shown with `adapter.show(entries, thumbnails = false, listView = true)`; `detailFor` returns `NameMatch.folderShown(hit.folder)` for them. `LibraryRows` shortens a detail wider than 45 % of the row from its start (`"… › Topic"`).
- The note line: a `TextView` between the strip and the grid, `GONE` outside search, `VISIBLE` while results show, so clearing its text redraws only it (`PanelRefresh.line`). Texts: `"Checking for changes…"`, `"Still reading the library…"`, and when `total > SEARCH_LIMIT`, `"Showing 200 of 1,234. Type more to narrow it."` (numbers with grouping).
- A second answer with the same hits changes only the note line; with other hits, one list redraw (`PanelRefresh.gentle(grid)`).
- Nothing found: `emptyNote` reads `"Nothing found."`. Library folder missing: `emptyNote` shows the text `showListing` shows for it today.
- While `search != null`: the folder watch's `list()` and `LibraryChanges`' re-list don't replace the results (guard at the top of `showListing`'s caller in `list`); long-press does nothing; Refresh starts a full check and runs the search again; the view switch and New folder first leave the search (as Back does), then act.
- Back (`onBackPressed`) and a tap on the breadcrumb leave the search: `search = null`, `breadcrumb.show(root, folder)`, the note line `GONE`, `list()`.
- `onResume` back from a document opened from results runs the search again instead of `list()`.
- A tap on a result whose file `!isFile`: toast `"That file isn't there any more."`, `index.reread(listOf(parent))`, then the search again.

- [ ] **Step 1: `Glyph.SEARCH`:** a circle with a short stroke down to the right, drawn in `StripButton` like the others; the button goes in the top row before Refresh, description "Search".
- [ ] **Step 2: The dialog:** a tap asks with `askForText("Search", lastQuery, "Search")`; an entry that `NameMatch.words` makes empty does nothing.
- [ ] **Step 3: The results**, the note line and the breadcrumb text `Search: "<query>"`, as above.
- [ ] **Step 4: Back, the breadcrumb, Refresh, the view switch and New folder** while results show, as above.
- [ ] **Step 5: Tap and the missing file**, and the return from a document.
- [ ] **Step 6:** Laptop suite and tablet suite pass.
- [ ] **Step 7: By hand on the tablet** (debug build, the scratch library from Task 5): half a name; two words the other way round; an accent; nothing; `adb push` a PDF into a folder not opened and search for it at once (found in the only answer); delete a found file with `adb shell rm` and tap its row (Review Focus 5); two searches quickly while a full check runs (Review Focus 4: only the second shows); Back from results and from a document opened from them; `adb logcat -s Notes` for one `search ms=<n> found=<n>` line per answer (add it). Watch for extra refreshes.
- [ ] **Step 8: Commit.** `git commit -m "library: search the whole library by name"`.

### Task 7: Timing a huge library on the tablet

**Files:**
- Create: `androidTest/check/SearchCheck.kt`

**Interfaces:**
- Consumes: `NameIndex` (Task 4), `IndexCheck`, `FileIndexDisk` (Task 2), `FolderListing.list`.

- [ ] **Step 1: `SearchCheck.measure()`** (measuring only, `assumeTrue` the card is in): makes `/storage/3331-3031/jot-search-scratch-big` with 4,000 folders in 40 course folders × 100 topics, 5 one-byte `.pdf` files each (20,000; the index never opens them), names like `"<CODE> Topic <n> — workbook.pdf"`; a test database. Logs under tag `SearchCheck`: time to make the files; the first full read; a check with nothing changed; a check after adding one PDF deep down (with the folder time moved); five searches of a two-word query (median); the median of 20 `FolderListing.list` calls on one topic folder with no check running and again during a full re-read. Deletes the scratch folder and the database at the end, also when it fails (`try/finally`).
- [ ] **Step 2: Run** it on the tablet (`-e class com.fzizzle.epapernotes.check.SearchCheck`). Expected: passes; the search median under 100 ms; a folder listing during a check no more than 1.5× without.
- [ ] **Step 3:** The numbers into `docs/superpowers/findings-log.md` under a 2026-11-17 (or the day's) entry. If the folder listing during a check is slower than 1.5×, stop and report to the owner before going on (design anti-goal 3).
- [ ] **Step 4: Commit.** `git commit -m "search: a timing check on a library of 20,000 PDFs"`.

### Task 8: Owner checklist and records

**Files:**
- Create: `notes/TESTING-search.md`
- Modify: `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` (§6.1: a line on search), `README.md` ("What it does"), `TODO.md`

- [ ] **Step 1:** `notes/TESTING-search.md`, in the style of the other checklists, from design §10: search for half a name, two words in the other order, an accent, something not there; a PDF copied onto the card from the laptop is found after the card goes back in; a PDF pushed by the sync while Jot is open is found at once; a rename in Jot is found under its new name; Back from results and from a document opened from them; nothing refreshes more than before; anything slower than before.
- [ ] **Step 2:** Spec §6.1 gains: "**Search by name** (owner, 2026-11-17; [library search design](2026-11-17-library-search-design.md)): a magnifier in the top row finds PDFs anywhere in the library by every word of their name, from a saved index of names checked against the card on each search." README: one line under "What it does". TODO.md: tick "Search by name" with this plan's link.
- [ ] **Step 3:** Release build back on the tablet (`./gradlew :app:installRelease`); `library.xml` restored.
- [ ] **Step 4: Commit.** `git commit -m "search: owner checklist and records"`.
