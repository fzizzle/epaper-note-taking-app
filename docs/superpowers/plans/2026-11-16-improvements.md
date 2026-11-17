# Review Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the improvements the [2026-11-16 review](../review-2026-11-16.md) suggested: deleted files can be put back, PDFs open from other apps, the first-run folder choice can't swallow the whole storage, Back lets go of a selection first, search and an order setting in the library, plus three small ones that change nothing the owner sees.

**Architecture:** Each improvement is one task, independent of the others, so any decision below that isn't approved is simply left out. The rules go into small plain-Kotlin files in `library/` with laptop tests (the trash, where an incoming PDF's file is, name search, what a tile says aloud); the screens only call them. Nothing here touches how writing is saved.

**Tech Stack:** As now. Nothing new. Task 3 adds a Gradle verification file, not a library.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` §2 (goals: the owner's own folders, nothing imported, a calm screen), §6.1 (library), §6.2 (writing screen). Findings: `docs/superpowers/review-2026-11-16.md`, section E; "review E3" below is a row there.

**Where this plan sits:** the third of three plans from the review, after the [conflict copy fixes](2026-11-16-conflict-copy-fixes.md) and the [screen and library fixes](2026-11-16-screen-and-library-fixes.md) (Task 6 here builds on that plan's `FileOps` changes). On a branch of its own from `main`.

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, `test/…` and `androidTest/…` likewise.

## Decisions for the owner (approve before Task 1; strike any you don't want)

Tasks 1 to 3 need no decision: they change nothing you see.

1. **Back lets go first (Task 4).** With a selection, an armed Paste or the Snip tool active, the tablet's Back gesture puts that down and stays in the document; the next Back leaves. The strip's ← always leaves at once.
2. **The whole storage can't be the library (Task 5).** The first-run chooser opens inside Documents when there is one, "Use this folder" only appears once you're inside a folder, not at the top of the storage or the card, and the chooser gets a "New folder" button. Today one tap on "Use this folder" at the start makes everything on the tablet the library.
3. **Deleted files are kept for 30 days (Task 6).** Delete moves the file or folder into a hidden folder inside the library folder (`.jot-deleted`), on the same storage, so it costs no copying. Settings gets "Deleted files", listing them with **Put back** and **Delete for good**, and **Empty**. Older than 30 days, they are removed when the library opens. Unsaved writing and the remembered page follow a file there and back. To know: a sync app that syncs hidden folders will sync this one too.
4. **"Open with Jot" for PDFs (Task 7).** Jot is offered when a PDF is opened from a file manager or another app. If the PDF is a real file on the tablet's storage or card, it's opened where it is and written into in place, like any file in the library. If it isn't (a browser download not yet saved, a mail attachment), Jot asks once: "Save a copy into your library and open it?", and puts it in a folder called `Inbox` in the library folder. Jot never writes into another app's private copy.
5. **Search by name (Task 8).** A magnifier in the library's top row. Type part of a name: the PDFs anywhere in the library whose names contain it are listed with their folder; a tap opens one; Back returns to the folder you were in. Ignores capitals and accents.
6. **An order setting (Task 9).** Settings: "PDFs in a folder: by name / last changed first". Folders stay in name order. By name stays the default. (Order by last opened would need a new record of when each file was opened: not in this plan.)

## Global Constraints

- "Your PDFs stay where you put them" (spec §2): nothing is imported or copied into the app, except decision 4's copy, which the owner is asked about each time and which lands in the owner's own library folder.
- The library never replaces, deletes or moves a file the owner didn't ask it to. A journal is never overwritten, and never deleted except with its own PDF.
- The screen's thread never reads the disk; the library's `disk` thread does. Every `FileOps` call blocks: call it off the screen's thread.
- Nothing may refresh the screen more often than today (owner, 2026-11-09). A new dialog or list is one refresh when asked for.
- Looks follow the library's: black outlines, no teal, `LibraryLook`, `StripButton` glyphs drawn in code (UI review, 2026-11-12).
- `library/`, `storage/` and `tools/` stay free of Android classes: they run in laptop tests.
- No new libraries. Readable code: small files, plain names, comments that say why.
- Toolchain: `source ~/.local/opt/android-env.sh`; `cd notes && ./gradlew :app:testDebugUnitTest -q`. Tablet tests with `installDebug` + `installDebugAndroidTest` + `adb shell am instrument -w -e notPackage com.fzizzle.epapernotes.check …`. Never `git add -A`; wait for a quiet tablet before installing. Library checks on a scratch folder only (`/sdcard/Documents/jot-scratch` with `--es root`, `shared_prefs/library.xml` backed up by `run-as` and restored): never the owner's SD-card Workbooks. Release build back at the end.

## Review Focus

1. **A deleted folder is put back after a new folder of the same name was made in its place.** Refused with a plain message; neither is touched (Task 6: `puttingBackOntoATakenNameIsRefused`).
2. **The memory card is out when the library opens.** Nothing in the deleted-files folder is counted as old and removed, and nothing throws (Task 6: `nothingIsPurgedWhenTheLibraryFolderIsMissing`).
3. **A PDF opened from another app whose name is already in `Inbox`.** The copy gets the next free name; the one there is never replaced (Task 7: `aSecondCopyOfTheSameNameGetsANumber`).
4. **Another app sends Jot a path outside shared storage, or one that isn't a PDF.** It isn't opened (Task 7: `aPathOutsideTheStorageVolumesIsRefused`, `onlyPdfsAreOpened`).
5. **A search in a library of 2,000 PDFs on the memory card.** The list comes within about a second, the screen stays usable while it's read, and a second search started meanwhile wins (Task 8: `onlyTheNewestSearchIsShown`, and the tablet timing).

---

### Task 1: The page is remembered at each turn (review E4)

**Files:**
- Modify: `main/ui/WritingActivity.kt` (`pageArrived`)

- [ ] **Step 1:** In `pageArrived(page)`, `lastPages.set(path, page, pageCount)`, with a comment: `onPause` does this too, but a crash or a kill in front never reaches it, and the document would reopen where it was last left, not where the owner was. `SharedPreferences.apply` writes in the background, so a turn waits for nothing.
- [ ] **Step 2: Check on the tablet** (debug build, scratch PDF): turn to page 5 with the volume key (`adb shell input keyevent 25` four times), `adb shell am force-stop com.fzizzle.epapernotes`, open it again by the path hook: the log's first `page` line is page 5. Time ten turns before and after from the existing turn log lines: no slower.
- [ ] **Step 3: Commit.** `git commit -m "writing: the page is remembered at each turn, not only on leaving"`

### Task 2: Tiles and rows say what they are (review E7)

**Files:**
- Create: `main/library/TileSpeech.kt`, `test/library/TileSpeechTest.kt`
- Modify: `main/ui/LibraryTiles.kt` (`TileView.show`, `showMarks`), `main/ui/LibraryRows.kt` (`RowView.show`, `showMarks`)

**Interfaces:**
- Produces: `object TileSpeech { fun folder(name: String, pdfCount: Int, folderCount: Int): String; fun pdf(name: String, progress: Float?, hasInk: Boolean): String }`. `folder("Physics", 4, 0)` is `"Folder Physics, 4 PDFs"`; `("Physics", 1, 2)` is `"Folder Physics, 1 PDF, 2 folders"`; `("Empty", 0, 0)` is `"Folder Empty, empty"`. `pdf("Week 2", 0.4f, true)` is `"Week 2, 40% read, has writing"`; `("Week 2", null, false)` is `"Week 2"`. `name` is the full name, not the shortened label a tile draws.

- [ ] **Step 1: Failing tests** in `TileSpeechTest` for the five lines above. **Step 2: Run**: fail. **Step 3: Implement.** **Step 4: Pass.**
- [ ] **Step 5:** `TileView` and `RowView` set `contentDescription` from it in `show` and again in `showMarks`. Nothing drawn changes.
- [ ] **Step 6: Check on the tablet:** `adb shell uiautomator dump` in the scratch library, and the tiles' `content-desc` hold the names. The project's own adb checks can then tap a tile by name; say so in `docs/superpowers/findings-log.md` under tablet tricks.
- [ ] **Step 7: Commit.** `git commit -m "library: tiles and rows carry their name and marks as text, for a screen reader and for checks over adb"`

### Task 3: The Onyx SDK download is checked (review E8)

**Files:**
- Modify: `notes/settings.gradle.kts`
- Create: `notes/gradle/verification-metadata.xml`
- Modify: `notes/SETUP.md`

- [ ] **Step 1: Find out what the plain-HTTP repository really serves.** `cd notes && ./gradlew :app:assembleRelease --refresh-dependencies --info | grep -i "repo.boox.com"` and list the groups downloaded from it (expected: `com.onyx.*`, and whatever of its own dependencies no other repository has).
- [ ] **Step 2: Narrow the repository** to those groups: in `settings.gradle.kts`, the Boox `maven { … }` block gets `content { includeGroupByRegex("…") }` for exactly the groups from Step 1, with a comment: nothing else may ever be answered by a repository without HTTPS.
- [ ] **Step 3: Pin their checksums.** `./gradlew --write-verification-metadata sha256 :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:testDebugUnitTest`, then edit the file down: `<verify-metadata>false</verify-metadata>`, a `<trusted-artifacts>` entry that trusts every group **not** from Step 1 by regex, and the `<components>` of Step 1's groups kept with their `sha256`. So only what comes over plain HTTP is pinned, and a new AndroidX or Kotlin version needs no upkeep here.
- [ ] **Step 4: Prove it bites.** Change one character of one pinned checksum: `./gradlew :app:assembleDebug` fails with "Dependency verification failed". Put it back: it builds, and all four tasks of Step 3 run.
- [ ] **Step 5:** `notes/SETUP.md`: one paragraph on what the file is and the command to run after changing the Onyx SDK's version. **Commit.** `git commit -m "build: only the Onyx SDK comes over plain HTTP, and its files are checked against pinned checksums"`

### Task 4: Back lets go first (review E5; decision 1)

**Files:**
- Create: `main/tools/BackStep.kt`, `test/tools/BackStepTest.kt`
- Modify: `main/ui/WritingActivity.kt` (`onBackPressed`)

**Interfaces:**
- Produces: `enum class BackStep { LET_GO, DISARM_PASTE, LEAVE_SNIP, LEAVE }` and `fun backStep(hasSelection: Boolean, pasteArmed: Boolean, snipping: Boolean): BackStep`, in that order of precedence.

- [ ] **Step 1: Failing tests** in `BackStepTest`: each alone, nothing active gives `LEAVE`, and a selection with Paste armed gives `LET_GO`. **Step 2: Run**: fail. **Step 3: Implement.** **Step 4: Pass.**
- [ ] **Step 5: Use it** in `onBackPressed`, before the "leave: back" log: `LET_GO` does what a tap beside the selection does (`letGo()`, `toolStateChanged()`); `DISARM_PASTE` what a second tap on Paste does; `LEAVE_SNIP` chooses the tool that was chosen before Snip, as finishing a snip does (`toolState.snipDone()`, `toolChosen()`); `LEAVE` goes on as now. Not while the pen is down or a page change is under way: then Back does nothing. The strip's ← still calls `leave()`.
- [ ] **Step 6: Check on the tablet** over adb: choose Snip (`adb shell input tap` on its button), `adb shell input keyevent 4`: the log shows the tool back to the pen and no `leave:`; a second `keyevent 4` leaves.
- [ ] **Step 7: Commit.** `git commit -m "writing: Back puts down a selection, an armed paste or the snip tool before it leaves"`

### Task 5: The first-run folder choice (review E3; decision 2)

**Files:**
- Modify: `main/ui/FolderChooser.kt`, `main/ui/LibrarySettings.kt` (`chooseLibraryFolder`), `main/ui/LibraryChanges.kt` (only if `askForText` is shared from there)

**Interfaces:**
- Produces: two constructor parameters on `FolderChooser`: `topsCanBeChosen: Boolean = true` (false hides the use button while the folder on show is one of `tops`) and `canMakeFolders: Boolean = false` (adds a neutral "New folder" button). `chooseLibraryFolder` passes `false` and `true`; the Move chooser keeps the defaults.

- [ ] **Step 1: Where it starts.** `chooseLibraryFolder` with `start == null` and one storage volume starts in `Documents` when that folder exists (it is already the suggested one), otherwise at the top as now. With two volumes it starts at the list of them, as now.
- [ ] **Step 2: The use button** follows `topsCanBeChosen` in `showRows` (where it already hides for the list of tops).
- [ ] **Step 3: New folder.** The button asks for a name with the library's text dialog and makes the folder through `FileOps(root = <the top the chooser is under>, …).newFolder(folder, name)` on `disk`, so the name rules and the "already there" check are the library's own; a refusal is shown as the library shows one; on success the chooser goes into the new folder. The dialog must stay open on that button: set its click listener after `show()`, as an `AlertDialog` closes on a button set through the builder.
- [ ] **Step 4: Check on the tablet** with the first-run state (debug build; `library.xml` backed up and removed with `run-as`, restored after): the chooser opens in Documents with "Use this folder" showing; "Up" to the top hides it; "New folder" makes `jot-scratch-2` and enters it; the owner's real folder is chosen again from the backup, not by hand.
- [ ] **Step 5: Commit.** `git commit -m "library: the folder chooser starts in Documents, can make a folder, and won't take the whole storage"`

### Task 6: Deleted files can be put back (review E2; decision 3)

**Files:**
- Create: `main/library/Trash.kt`, `test/library/TrashTest.kt`, `main/ui/DeletedFiles.kt`
- Modify: `main/library/FileOps.kt` (`delete` stays for "for good"; new `trash`, `putBack`), `main/ui/LibraryChanges.kt` (`confirmDelete`, `askToDelete`'s wording), `main/ui/LibrarySettings.kt` (a row), `main/ui/LibraryActivity.kt` (the daily purge, beside the thumbnail cache's)

**Interfaces:**
- Produces, in `Trash.kt`:
  - `const val TRASH_FOLDER = ".jot-deleted"`; it starts with a dot, so `FolderListing`, `LibraryWalk`, the folder watch and the thumbnails already leave it out.
  - `data class Trashed(val file: File, val name: String, val deletedAt: LocalDateTime, val backTo: File)` — `file` is where it lies now, `backTo` where it came from.
  - `object Trash { fun placeFor(root: File, target: File, now: LocalDateTime): File; fun list(root: File): List<Trashed>; fun olderThan(root: File, days: Int, now: LocalDateTime): List<Trashed> }`. A deleted `root/Physics/Week 2.pdf` lies at `root/.jot-deleted/2026-11-16 14.05.09/Physics/Week 2.pdf`: the time it was deleted as a folder name (a second deletion in the same second gets ` 2`, ` 3`), then its path from the library folder, so where it came from needs no record of its own. `list` is newest first and skips anything whose folder name isn't a time.
- Produces, in `FileOps`: `fun trash(target: File, now: LocalDateTime = LocalDateTime.now()): Result` (the checks `delete` makes, then `carry` to `Trash.placeFor`, its parent folders made first; journals and remembered pages follow, as for any move) and `fun putBack(item: Trashed): Result` (`carry` back to `item.backTo`, the folders above it made again if they're gone; `Refused("There's already a '…' there.")` if the name is taken; the emptied time folder removed). `delete(target)` is unchanged and is what "Delete for good" and the purge call; `targetProblem` lets all three act inside the trash folder but nothing else does.
- Constant: `KEEP_DELETED_DAYS = 30`.

- [ ] **Step 1: Failing laptop tests** in `TrashTest` (with `FileOps` on a temporary folder, as `FileOpsTest` sets one up): `aDeletedFileLiesUnderTheTimeAndItsOldPath`; `itsJournalAndPageGoWithItAndComeBack` (a journal with a stroke and a remembered page: both under the trash path after `trash`, both back under the old path after `putBack`); `aDeletedFolderComesBackWhole`; `puttingBackOntoATakenNameIsRefused` (both files untouched); `puttingBackRemakesAFolderThatIsGone`; `twoDeletionsInOneSecondDontCollide`; `theListIsNewestFirstAndSkipsStrangers`; `onlyOldOnesAreOldEnough` (29 days no, 31 yes); `nothingIsPurgedWhenTheLibraryFolderIsMissing` (`olderThan` on a root that doesn't exist is empty and doesn't throw); `aFileOpenInTheWritingScreenWaitsForItsSave` (as `renameWaitsForTheOpenSession`); `theTrashIsNotListedOrWalked` (`FolderListing.list(root)` and `LibraryWalk.pdfsUnder(root)` show nothing from it).
- [ ] **Step 2: Run**: fail. **Step 3: Implement** `Trash` and the two `FileOps` calls. **Step 4: Pass**, with all of `FileOpsTest`.
- [ ] **Step 5: The delete question** calls `trash` and says so: "Delete 'Week 2.pdf'? It's kept for 30 days under Settings › Deleted files." (the folder question likewise, keeping its counts).
- [ ] **Step 6: Settings › Deleted files** (`DeletedFiles.kt`, in the look of the settings and the folder chooser): the row shows how many there are; a tap lists them (name, the folder it came from, the day), read on `disk`. A tap on one offers **Put back** and **Delete for good**; the list's own button is **Empty**, which asks once first. Each runs through `LibraryChanges.change`, so the library re-lists and refusals show as they do for a move.
- [ ] **Step 7: The purge.** Once a day, on `disk`, when the library starts and its folder is there: `FileOps.delete` for each of `Trash.olderThan(root, KEEP_DELETED_DAYS, now)`. Log `deleted files: purged=<n>`.
- [ ] **Step 8: Check on the tablet** in the scratch library: delete a PDF with a remembered page, put it back, open it: it opens at that page. Delete a folder of three, Empty: `adb shell ls -a` shows `.jot-deleted` empty. The grid never shows the trash folder.
- [ ] **Step 9: Commit.** `git commit -m "library: deleted files are kept for 30 days and can be put back"`

### Task 7: "Open with Jot" (review E1; decision 4)

**Files:**
- Create: `main/library/IncomingPdf.kt`, `test/library/IncomingPdfTest.kt`, `main/ui/OpenFromOutsideActivity.kt`
- Modify: `main/AndroidManifest.xml`, `docs/superpowers/findings-log.md`

**Interfaces:**
- Produces: `object IncomingPdf { fun fileFor(scheme: String?, authority: String?, path: String?, documentId: String?, dataColumn: String?, volumes: List<Volume>): File?; fun inboxName(displayName: String?, taken: (String) -> Boolean): String }`.
  - `fileFor` gives the real file a link points at, or null: a `file` link's path; for `com.android.externalstorage.documents`, a document id `primary:Documents/a.pdf` under the primary volume's folder and `1A2B-3C4D:Notes/a.pdf` under the volume whose folder is named `1A2B-3C4D`; otherwise `dataColumn` (the media store's `_data`) when it's given. Whatever it finds must end in `.pdf` (any case), be absolute and normalized with no `..` left, and lie under one of `volumes`; else null. It doesn't touch the disk.
  - `inboxName("Lecture 3.pdf") { false }` is `"Lecture 3.pdf"`; taken once, `"Lecture 3 (2).pdf"`; a null or empty name is `"Opened.pdf"`; a name without `.pdf` gets it; `/` and a leading dot are replaced so `FileOps`' name rules hold.
- Produces: `OpenFromOutsideActivity`, exported, with no screen of its own, for `VIEW` of `application/pdf` with `content` and `file` links. It finishes at once after starting the next screen.

- [ ] **Step 1: Find out what the tablet's apps send.** A debug-only log line of the incoming link (scheme, authority, path) in a first cut of the activity; open the same PDF from the Boox file manager, from Files (the system picker's "open with"), from the browser's downloads and from a mail attachment. Into the findings log as a table, with whether `fileFor` could find the real file for each. If the Boox file manager uses a link `fileFor` doesn't know, add its form to `fileFor` and to the tests.
- [ ] **Step 2: Failing laptop tests** in `IncomingPdfTest`: `aFileLinkIsItsPath`; `aStorageDocumentOnTheTabletIsFound`; `aStorageDocumentOnTheCardIsFound`; `aMediaStorePathIsUsed`; `aPathOutsideTheStorageVolumesIsRefused` (`/data/data/…`, `/storage/emulated/0/../../data/x.pdf`); `onlyPdfsAreOpened` (`a.txt` is null); `anUnknownLinkGivesNull`; `aSecondCopyOfTheSameNameGetsANumber`; `aNameWithASlashOrALeadingDotIsMadeSafe`.
- [ ] **Step 3: Run**: fail. **Step 4: Implement** `IncomingPdf`. **Step 5: Pass.**
- [ ] **Step 6: The activity.** Without All files access, or with no library folder chosen yet: start `LibraryActivity` (it asks for both) with a toast "Set Jot up first, then open the PDF again." With a real file from `fileFor` that exists: `LibraryPrefs.lastOpened` set, `WritingActivity` started with its path. Otherwise the question "Save a copy into your library and open it?" (**Save and open** / **Cancel**); on yes, on a background thread, the link's bytes are written to `<library>/Inbox/<inboxName>` through `SafeSave.createNew` (which never replaces a file), the folder made if needed, and that copy is opened; a failure is a toast and nothing is left behind. The manifest entry's comment says why this door is safe to leave open: it only ever opens a PDF on shared storage, which the owner can open from the library anyway, and writes nothing until the owner writes.
- [ ] **Step 7: Check on the tablet:** each of Step 1's four ways opens the PDF; a stroke made in one opened in place is in that file afterwards (`adb pull` and look); Back from it returns to the app it came from; the `Inbox` copy shows in the library.
- [ ] **Step 8: Commit.** `git commit -m "Jot can be chosen to open a PDF from another app: in place if it's a real file, else a copy in Inbox"`

### Task 8: Search by name (review E6; decision 5)

> **Replaced (2026-11-17)** by the [library search design](../specs/2026-11-17-library-search-design.md): the owner asked for a search that stays fast in a huge library, which a walk of the card per search doesn't. Don't build this task; build that design's plan.

**Files:**
- Create: `main/library/NameSearch.kt`, `test/library/NameSearchTest.kt`
- Modify: `main/ui/StripButton.kt` (`Glyph.SEARCH`), `main/ui/LibraryActivity.kt` (the top row, a search state, `onBackPressed`), `main/ui/LibraryTiles.kt` (`BreadcrumbBar`, to show the search's title)

**Interfaces:**
- Produces: `object NameSearch { fun matches(name: String, query: String): Boolean; fun find(root: File, query: String, limit: Int = 200): List<PdfEntry> }`. `matches` ignores capitals and accents (both sides through `java.text.Normalizer` NFD with the combining marks dropped, then lower case with `Locale.ROOT`) and wants every word of the query somewhere in the name: `matches("Thermo Week 2 answers", "week ther")` is true. An empty query matches nothing. `find` runs `LibraryWalk.pdfsUnder(root)` through it and stops at `limit`.
- Constant in `LibraryActivity`: `SEARCH_LIMIT = 200`.

- [ ] **Step 1: Failing laptop tests** in `NameSearchTest`: `everyWordMustBeInTheName`; `capitalsAndAccentsAreIgnored` (`"Résumé"` against `"resume"`, and the other way); `anEmptyQueryFindsNothing`; `findWalksTheWholeLibraryInItsOrder`; `findStopsAtTheLimit`; `hiddenAndDeletedFilesAreNotFound` (a file under `.jot-deleted` and a temporary file).
- [ ] **Step 2: Run**: fail. **Step 3: Implement.** **Step 4: Pass.**
- [ ] **Step 5: The glyph and the button.** `Glyph.SEARCH`: a circle with a short stroke down to the right, drawn like the others. It goes in the library's top row beside Refresh, with the description "Search".
- [ ] **Step 6: The screen.** A tap asks for the text (the library's text dialog, action "Search"). The search runs on `disk`; only the newest one's answer is shown (a counter, as `list` uses `listed`). Results are shown as rows whatever the view setting, each with its folder's path from the library folder as its detail, under a breadcrumb reading `Search: "<query>"`; none found shows the empty-folder hint's place with "Nothing found." A tap opens the PDF; a long-press does nothing here. Back, or a tap on the breadcrumb, returns to the folder that was on show, listed again. The folder watch and the thumbnail loader are left as they are while results show.
- [ ] **Step 7: Tablet test** `onlyTheNewestSearchIsShown` isn't reachable without driving the activity: check by hand over adb instead, and time one search on the owner's real library (read only) from the log line `search ms=<n> found=<n>`. Into the findings log.
- [ ] **Step 8: Commit.** `git commit -m "library: search the whole library by name"`

### Task 9: An order setting (review E6; decision 6)

**Files:**
- Modify: `main/library/FolderListing.kt` (`list`), `main/ui/LibrarySettings.kt` (`LibraryPrefs`, a row), `main/ui/LibraryActivity.kt` (passes it on)
- Test: `test/library/FolderListingTest.kt`

**Interfaces:**
- Produces: `enum class PdfOrder { NAME, LAST_CHANGED }` in `FolderListing.kt`; `FolderListing.list(folder, openOutUpTo = 0, order: PdfOrder = PdfOrder.NAME)`; `LibraryPrefs.pdfOrder: PdfOrder` (stored by name, `NAME` when missing or unknown).

- [ ] **Step 1: Failing tests** in `FolderListingTest`: `lastChangedPutsTheNewestPdfFirst`; `foldersStayInNameOrder`; `aSmallFolderOpenedOutKeepsItsPlaceAndOrdersItsOwnPdfs`; `sameDateFallsBackToTheName`. **Step 2: Run**: fail. **Step 3: Implement.** **Step 4: Pass.**
- [ ] **Step 5: The setting:** a row "PDFs in a folder" with detail "By name" or "Last changed first"; a tap switches it and the library re-lists. The Move chooser and search aren't affected.
- [ ] **Step 6: Commit.** `git commit -m "library: a setting to list a folder's PDFs last changed first"`

### Task 10: Results, checklist, owner session (hard stop)

- [ ] **Step 1:** All laptop tests and all tablet tests.
- [ ] **Step 2:** `notes/TESTING-improvements.md`, one section per decision built: Back with a selection, with Paste armed, with Snip; delete a file and a folder and put each back, delete for good, Empty; open a PDF from the Boox file manager and from the browser, write in each, find the writing afterwards; search for half a name, with an accent, for nothing; switch the order and back; anything that refreshes more than before. The first-run chooser is described, not asked of the owner (it needs the settings cleared).
- [ ] **Step 3:** Spec §6.1 (deleted files, search, order, open from other apps, the folder choice) and §6.2 (Back). README "What it does": the lines for what was built. `TODO.md`: the items under "Review (2026-11-16)" ticked. Findings log: what the tablet's apps send (Task 7), the search timing (Task 8).
- [ ] **Step 4:** Release build on the tablet, `library.xml` as the owner left it. **Stop for the owner's session.**
