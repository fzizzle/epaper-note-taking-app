# Review Design Gaps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the gaps against the design found in the [2026-11-10 review](../review-2026-11-10.md), sections 3 and 5: stale temporary files, a library that misses some changes on disk, the build set-up, and laptop tests for the timing rules of the writing screen.

**Architecture:** Three small library and build changes, then one piece of groundwork: the rules that decide when fast-ink is on are lifted out of `WritingActivity` into a plain class that can be tested on the laptop. That last task changes structure, so it is a decision for the owner and can be left out without affecting the others.

**Tech Stack:** As in plan 4. Nothing new.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` §6.1 ("The grid updates automatically when files change on disk"), §7.3 ("Temporary files must not show up in the library grid, and stale ones from a crash must be cleaned up"), §5 (readable code, test strategy is the builder's call). Findings: `docs/superpowers/review-2026-11-10.md`.

**Where this plan sits:** the third of four plans from the review. Independent of the other three, except that Task 3 (release build) changes the numbers in the [speed plan](2026-11-10-review-speed.md).

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, `test/…` and `androidTest/…` likewise.

## Decisions for the owner (approve before Task 1)

1. **Stale temporary files are removed when a folder is listed,** but only those more than ten minutes old and with no document open or still saving in that folder. They are hidden full-size copies left by a crash mid-save.
2. **Folder tiles keep their "N PDFs" count up to date** by watching each subfolder shown, not only the folder on show.
3. **A release build for daily use.** Installed over the debug build it would need the app removed first (different signing), which wipes the app's own data: remembered pages, unsaved journals, the All files access grant. Proposed instead: sign the release build with the debug key, so it installs over what's there and nothing is lost. A proper private key can come later, when the app is published.
4. **The adb test shortcuts (`--es path`, `--es root`) work only in the debug build.** Checks over adb then need the debug build installed.
5. **Lifting the fast-ink rules out of `WritingActivity` (Task 4).** The owner declined splitting that file. This is narrower than a split: one class of about 120 lines holding only the on/off decisions, so they can be tested without a tablet. Most bugs in the review were in exactly these rules. Approve or leave out.

## Global Constraints

- "Temporary files must not show up in the library grid, and stale ones from a crash must be cleaned up." (spec §7.3)
- "The grid updates automatically when files change on disk (added, removed, renamed, moved, or modified by a laptop, a sync app, or USB), and refresh forces a rescan." (spec §6.1)
- Never delete anything in the owner's folders except a file `SafeSave.isTempFile` recognises. Never touch the temporary file of a save that may be running.
- No file reading on the UI thread; no extra redraws of the grid (each can flash the panel).
- If minify is ever switched on, MuPDF (`com.artifex.mupdf.**`) and Onyx (`com.onyx.**`) must be kept whole: both are reached through JNI and reflection. This plan leaves minify off.
- The SD-card work in progress (`Volume.kt`, `StorageVolumes.kt`) touches `LibraryActivity.kt` and `FileOps.kt`. Start this plan only after that work is committed, and rebase onto it.
- Toolchain and tablet rules as in the bug fixes plan.

## Review Focus

1. **Leaving a document and landing in its folder while the last save is still running.** The save's temporary file is not removed (Task 1: `aFreshTempFileIsLeftAlone`, `aFolderWithAnOpenSessionIsLeftAlone`).
2. **A sync app drops the last file of a batch just as the folder is opened.** It appears without a refresh (Task 2: `aFileArrivingDuringTheListingIsSeen`).
3. **A folder deleted and recreated by a sync app.** New files in it still appear (Task 2: `aRecreatedFolderIsWatchedAgain`).
4. **A folder of 200 subfolders.** Watching them costs no visible time and doesn't hit the system's watch limit (Task 2: only folders with tiles on screen are watched, at most 64).
5. **Installing the release build over the debug build.** Remembered pages, journals and file access all survive (Task 3, tablet check).

---

### Task 1: Stale temporary files are cleaned up (spec §7.3)

**Files:**
- Modify: `main/storage/SafeSave.kt:66-73` (`cleanStale`), `main/doc/DocumentSession.kt` (companion), `main/ui/LibraryActivity.kt` (the listing task on the `disk` thread, around `:255-268`), `main/library/FileOps.kt:208-209`
- Test: `test/storage/SafeSaveTest.kt`, `test/doc/DocumentSessionTest.kt`, `test/library/FileOpsTest.kt`

**Interfaces:**
- Produces:
  - `SafeSave.cleanStale(folder: File, olderThanMs: Long = STALE_AFTER_MS, now: Long = System.currentTimeMillis()): Int` with `const val STALE_AFTER_MS = 10 * 60 * 1000L`: deletes only temporary files last modified before `now - olderThanMs`.
  - `DocumentSession.anyOpenIn(folder: File): Boolean` — true while any session, open or still closing, is on a file directly in `folder` (read from `openSessions`' keys).

- [ ] **Step 1: Write the failing tests.**
  - `SafeSaveTest`: `aFreshTempFileIsLeftAlone`, `anOldTempFileIsRemoved`, `otherHiddenFilesAreNeverTouched` (`.stfolder`, `.nomedia`, `.Lecture.pdf.swp`), `subfoldersAreNotEntered`.
  - `DocumentSessionTest`: `anyOpenInIsTrueUntilTheSessionHasClosed` (true after `start`, still true after `close()` until the fake scheduler has run the close task, then false); `aCopySessionCountsForItsFolderToo`.
  - `FileOpsTest.anUnreadableDestinationRefusesTheMove` (review 2.11): with a destination whose `listFiles()` is null, `move` returns a failure and nothing is renamed.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.** In the library's listing task, after listing and off the UI thread: `if (!DocumentSession.anyOpenIn(shown)) SafeSave.cleanStale(shown)`, logging the count when above zero. In `FileOps.isTaken`, a null `listFiles()` counts as taken.
- [ ] **Step 4: Run all laptop tests** — pass.
- [ ] **Step 5: Commit.** `git commit -m "notes: temporary files left by a crashed save are removed when their folder is listed"`

### Task 2: The library misses no change on disk (spec §6.1, review 2.10)

**Files:**
- Modify: `main/library/FolderWatch.kt`, `main/ui/LibraryActivity.kt` (`showListing` at `:271-289`, the listing request, `tilesOnScreenChanged`)
- Test: `androidTest/library/FolderWatchTest.kt`

**Interfaces:**
- Produces:
  - `fun FolderWatch.watch(folder: File)` may be called again for the same folder and then replaces its observer (a fresh inode after delete-and-recreate).
  - `fun FolderWatch.watchChildren(folders: List<File>)`: watches up to `MAX_CHILDREN = 64` subfolders for `CREATE`, `DELETE`, `MOVED_FROM`, `MOVED_TO` only, fires the same debounced `onChange`, and replaces the previous set. Folders already watched keep their observer.
  - `private var sawSelfEvent` inside `FolderWatch`: set on `DELETE_SELF` or `MOVE_SELF`; `val needsRewatch: Boolean` reads and clears it.

- [ ] **Step 1: Write the failing tablet tests** in `FolderWatchTest` (scratch folder the test creates and removes): `aFileArrivingDuringTheListingIsSeen` (start the watch, then create a file before "listing" returns: `onChange` fires); `aRecreatedFolderIsWatchedAgain` (delete and recreate the folder, call `watch` again, create a file: `onChange` fires); `aFileAddedInsideASubfolderIsSeen`; `watchingChildrenTwiceKeepsOneObserverEach` (one change, one `onChange` after the debounce).
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
  - Start the watch **before** the listing is read: when the library asks for a folder other than `shownFolder`, call `watch.watch(folder)` at the request, not in `showListing`.
  - In `showListing`, call `watch.watch(shown)` again when `watch.needsRewatch`.
  - In `tilesOnScreenChanged`, pass the on-screen `FolderEntry` files to `watch.watchChildren(...)`. A change inside one leads to the ordinary re-listing, and `showListing` already skips the grid when nothing differs.
- [ ] **Step 4: Run the tablet tests** — pass. Then by hand: `adb shell touch` a PDF into a subfolder of the folder on show; the tile's count goes up within a second, and logcat shows one "library listed" line, not several.
- [ ] **Step 5: Commit.** `git commit -m "notes: the library sees files that arrive early, in recreated folders and inside subfolders"`

### Task 3: Build set-up (review section 3)

**Files:**
- Modify: `notes/app/build.gradle.kts`, `main/ui/LibraryActivity.kt:114-124`, `README.md`, the memory note on adb shortcuts is the owner's to update

- [ ] **Step 1: `minSdk = 30`.** The library already calls Android 11 methods; this makes the build say so.
- [ ] **Step 2: A release build type,** if decision 3 was approved:

```kotlin
buildTypes {
    release {
        // Signed with the debug key so it installs over the debug build and keeps the app's
        // own data (remembered pages, journals, file access). A private key comes with publishing.
        signingConfig = signingConfigs.getByName("debug")
        // Off: shrinking would strip classes MuPDF and Onyx reach through JNI and reflection.
        isMinifyEnabled = false
    }
}
```

- [ ] **Step 3: The adb shortcuts only in a debug build,** if decision 4 was approved: wrap the two `intent.getStringExtra(...)` blocks in `if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)`.
- [ ] **Step 4: Build both:** `./gradlew :app:assembleDebug :app:assembleRelease` — both succeed; `./gradlew :app:testDebugUnitTest :app:lintDebug` — tests pass and lint reports no `NewApi` error.
- [ ] **Step 5: Install the release build over the debug build** on a quiet tablet (`adb install -r app/build/outputs/apk/release/app-release.apk`). Check: the library opens on the same folder, "Continue" is still there, a document opens at its remembered page, and file access wasn't asked for again. Then run the speed plan's Task 1 measurements once on each build and add both columns to `docs/superpowers/review-speed-results.md` (create the file with just this table if the speed plan hasn't run).
- [ ] **Step 6: README:** add how to build and install the release build, and that adb shortcuts need the debug build.
- [ ] **Step 7: Commit.** `git commit -m "notes: a release build, minSdk 30, and adb shortcuts only in debug"`

### Task 4: The fast-ink rules, testable on the laptop (owner decision 5)

Skip this task unless decision 5 was approved.

**Files:**
- Create: `main/ui/FastInkRules.kt`, `test/ui/FastInkRulesTest.kt`
- Modify: `main/ui/WritingActivity.kt` (the fields at `:108-185`, `toolStateChanged`, `showWithoutFastInk`, `resumeFastInk`, `screenPen`, `onPenDown`, the finger gesture and dialog handlers)

**Interfaces:**
- Produces: a class with no Android imports. Every input the rules depend on is an event; every effect is a returned action for `WritingActivity` to carry out.

```kotlin
/** What the writing screen must do next. */
enum class InkAction { PAUSE, REDRAW, RESUME_NOW, RESUME_AFTER_SHOW, RESUME_AFTER_CLEAN_REFRESH, GIVE_SCREEN_THE_PEN, TAKE_PEN_FROM_SCREEN, RETRY_SHOW_WHEN_IDLE }

class FastInkRules {
    // What is true now.
    var documentShown = false; var dialogOpen = false; var toolWantsFastInk = true
    var onyxWriting = false; var screenPenDown = false; var resumed = false

    fun wanted(): Boolean
    fun onShowRequested(): List<InkAction>      // a page picture is ready, or the page turned
    fun onToolChanged(): List<InkAction>
    fun onOnyxPenDown(): Boolean                // true: Onyx owns this stroke, the app records it
    fun onPenLifted(): List<InkAction>
    fun onFingersDown(): List<InkAction>
    fun onFingersUp(moved: Boolean): List<InkAction>
    fun onDialogOpened(): List<InkAction>
    fun onDialogClosed(): List<InkAction>
    fun onResumeDue(): List<InkAction>          // the delayed resume fired
    fun onScreenPaused(): List<InkAction>
    fun onScreenResumed(): List<InkAction>
}
```

- [ ] **Step 1: Write the tests first, one per rule the code states in its comments today** (they are the specification): `noPauseWhileThePenIsDown`, `aShowSkippedForThePenRunsAfterItRests`, `aToolPauseWaitsForTheLift`, `theScreenTakesThePenOnlyOncePaused`, `noResumeUnderADialog`, `noResumeUnderASelection`, `noResumeWhileTheScreenHasThePenDown`, `fingersUpAfterMovingResumesAfterACleanRefresh`, `fingersUpWithoutMovingResumesAtOnce`, `aStrokeLandingWithFastInkPausedBelongsToTheScreen`, `nothingResumesInTheBackground`, `closingADialogResumesAfterTheShowPause`. Add one for each fix from the bug fixes plan, Task 8, that was kept.
- [ ] **Step 2: Run and confirm they fail** (the class doesn't exist).
- [ ] **Step 3: Implement `FastInkRules`** by moving the conditions out of `WritingActivity` one handler at a time; after each handler, build and run the laptop tests. `WritingActivity` keeps the `Handler`, the delays and the calls to `pen` and `pages`; it loses the flags listed above and the `if`s that read them.
- [ ] **Step 4: Run all laptop tests** — pass. `WritingActivity.kt` should be shorter than before, not longer.
- [ ] **Step 5: On the tablet,** work through `notes/TESTING-writing.md` and `notes/TESTING-tools.md` again in full: this task must change nothing the owner can see.
- [ ] **Step 6: Commit.** `git commit -m "notes: the rules for when fast-ink is on are in one tested place"`

## Not in this plan

- **Conflict copies (spec §7.4):** plan 5, already planned as its own piece of work. Task 5 of the bug fixes plan (the "saving" line in the journal) should land before plan 5 is written, because it removes one false source of conflicts.
- **The two writing-session to-dos** (gentler refresh after zooming, the screen-recording test): the refresh is Task 4 of the [readability plan](2026-11-10-review-readability.md); the recording test stays in `TODO.md` under plan 2.
- **A resting palm (spec §6.2):** bug fixes plan, Task 8.
