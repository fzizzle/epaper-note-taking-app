# Conflict Copy and Journal Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the bugs the [2026-11-16 review](../review-2026-11-16.md) found in the code that keeps writing safe when a file changes elsewhere: copies piling up on a full tablet, a stroke landing on the wrong page after a reload, a copy made from a half-changed file, and four smaller ones in the journal.

**Architecture:** No new structure. `DocumentSession` gets one way into a "(my edits)" copy, used by all three places that make one, and that way either fully happens or leaves nothing behind. A changed file with nothing unsaved is no longer swapped in by the session on its own: the session asks, the screen holds the pen, and only then is the new version opened. A copy is opened and checked before the journal that holds its writing is cleared.

**Tech Stack:** As now (Kotlin 2.0.21, MuPDF `fitz:1.28.5`, JUnit 4.13.2). Nothing new.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` §4 (lost writing is the worst failure), §7.3 (crash safety), §7.4 (a file changed elsewhere: both versions kept). Findings: `docs/superpowers/review-2026-11-16.md`; "review A1" below is a row there.

**Where this plan sits:** the first of three plans from the review. The conflict copies it fixes are on `main` (merged as PR #30), so it runs on a branch of its own from `main`, and it extends their open owner checklist (`notes/TESTING-conflicts.md`) rather than adding a new one. Run it before the [screen and library fixes](2026-11-16-screen-and-library-fixes.md): both touch `WritingActivity.show`.

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, `test/…` and `androidTest/…` likewise.

## Decisions for the owner (approve before Task 1)

1. **Writing in the moment a changed file is noticed goes into a copy.** Today a changed file with nothing unsaved is swapped in at once, in the background, while the pen is still live on the old pages. New rule: the screen first holds the pen (after the stroke in progress, never cutting it), then the new version is opened. A stroke that got in before the hold was written on the old version, so it's treated like any unsaved writing: the old version with that stroke becomes the "(my edits)" copy, and the changed file is left as it is (spec §7.4).
2. **A copy is checked before it's trusted.** After a "(my edits)" copy is written it's opened once: it must open without repair and have as many pages as the document on screen. If not, the copy is removed, the writing stays in the app, and the owner is told as for any failed save. Costs one extra open per copy (1 to 36 ms on the workbooks).
3. **Only if Task 3's test on the tablet shows a damaged copy can be made:** PDFs up to 64 MB are read into memory when opened, so nothing that happens to the file afterwards can reach the open document. Costs the file's size in memory (14 MB for a lecture deck) and nothing in time. Not built unless the test shows the fault.
4. **No copy when there's nothing to put in it.** Writing from an earlier session that is all on pages the file no longer has is set aside as before, with its message, but no empty copy is made and the file itself is opened. Writing that is already in the file makes no copy and no message.

## Global Constraints

- "Every stroke must survive a crash, kill, or power loss from the moment the pen lifts" (spec §7.3). A file something else changed is never saved into (spec §7.4). No fix here may weaken either.
- The ordering rule in `DocumentSession` stays: a change is journaled, added to `unsaved` and posted to the worker inside one `synchronized(lock)` block.
- A journal is cleared only after the file or copy that holds its changes is safely on disk, and never before its replacement is written whole.
- Whoever waits on the session always gets an answer, whatever goes wrong (as `changePages` does): catch `Throwable`, not `Exception`, on those paths.
- fitz 1.28.5: never `PDFObject.push(PDFObject)` or `addObject`.
- `doc/` and `storage/` stay free of Android classes: they run in laptop tests.
- Readable code: small changes, plain names, comments that say why. The owner declined splitting `WritingActivity.kt`: change it as little as each fix needs.
- Toolchain: `source ~/.local/opt/android-env.sh`; `cd notes && ./gradlew :app:testDebugUnitTest -q`. Tablet tests with `installDebug` + `installDebugAndroidTest` + `adb shell am instrument -w -e notPackage com.fzizzle.epapernotes.check …` (not `connectedDebugAndroidTest`, which uninstalls the app). Another session may be using the checkout and the tablet: never `git add -A`, and wait for a quiet minute (`adb logcat -d -s Notes | tail`) before installing. The adb path hook changes `shared_prefs/library.xml`: back it up with `run-as` before a check and restore it before installing release. Release build back on the tablet at the end.

## Review Focus

1. **The tablet's own storage fills up while a synced file changes under unsaved writing.** One message, at most one copy on disk however long it lasts, and every stroke still comes back after a kill (Task 1: `aCopyWhoseJournalCantBeStartedLeavesNoCopyBehind`, `strokesAfterAFailedSwitchSurviveAKill`).
2. **The owner comes back to Jot and starts writing at once, while a sync has just replaced the file.** The stroke is in a copy of the pages it was written on, never on another page of the new version (Task 2: `aStrokeBeforeTheReloadGoesIntoACopyOfTheOldVersion`).
3. **The new version has fewer pages than the old one, and a stroke from the last old page is handed over late.** It isn't recorded, and saving doesn't stop for the rest of the session (Task 2: `aLateStrokeOnAPageTheNewVersionLacksIsNotRecorded`).
4. **A file overwritten in place by a USB copy rather than replaced.** A copy that doesn't open clean is never the only place the writing is (Task 3: `aCopyThatDoesNotOpenCleanIsNotTrusted`, and the tablet test).
5. **A session that moves into a copy, and later into a second one.** After it closes, the library cleans temporary files in that folder again (Task 1: `aSessionThatMovedTwiceFreesBothCopies`).

---

### Task 1: One way into a copy, all or nothing (review A1, A7, A8)

**Files:**
- Modify: `main/doc/DocumentSession.kt` (`switchTo`, `fileChanged`, `rescueIntoCopy`, `saveRepairedToCopy`, `close`, `copyGateKey`, the class comment)
- Test: `test/doc/DocumentSessionTest.kt`

**Interfaces:**
- Produces: `private fun continueInCopy(p: PdfPages, why: CopyReason, notPlaced: Int = 0): File` in `DocumentSession`, worker thread only. In order: the name from `CopyNames.myEdits(file, clock())`; `SafeSave.createNew(copy) { p.saveTo(it) }`; `switchTo(copy)`; `inkRedrawn = false`; `repairDecided = true`; `waitingForRepairDecision = false`; `events(SessionEvent.SavedToCopy(copy, why, notPlaced))`. If anything after the copy is on disk throws, the copy is deleted (it's this call's own file, made a moment ago, and nothing refers to it yet) and the failure is rethrown. Each caller keeps its own handling of that failure.
- Produces: `switchTo(copy)` prepares before it changes anything. The copy's journal is made as a local, `rewrite(base, remaining)` is called on it, and only when that has returned are `journal`, `file`, `known`, `unsaved`, `applied` and the gate key changed, in one `synchronized(lock)` block, and the old journal cleared last. If the rewrite throws, the new journal's file is removed (`clear()`) and the session is exactly as it was.
- Produces: `copyGateKeys: MutableSet<String>` in place of `copyGateKey: String?`; `close()` removes every one from `openSessions`.

- [ ] **Step 1: Write the failing tests** in `DocumentSessionTest`, beside `aConflictCopyThatCanNotBeSavedIsTriedAgain`. A helper `breakTheJournalOf(file: File)` like `breakTheJournal` (its journal file replaced by a folder), and `myEditsCopies()` listing files in `pdf.parentFile` whose name contains `(my edits`.
  - `aCopyWhoseJournalCantBeStartedLeavesNoCopyBehind`: a stroke; the file's text changed on disk; `breakTheJournalOf` the copy's journal path (`CopyNames.myEdits(pdf, clock)`); `saveNow()`. Assert one `SaveFailed`, `myEditsCopies()` empty, `s.file == pdf`. Mend the journal path, `scheduler.runDelayed()`: exactly one copy, holding the stroke; one `SavedToCopy(CHANGED_WHILE_OPEN)`.
  - `strokesAfterAFailedSwitchSurviveAKill`: as above up to the failed switch, then a second stroke, then `reopenAfterKill()`: both strokes are rescued (`SavedToCopy(RESCUED)`, two strokes in the copy).
  - `aRescueWhoseJournalCantBeStartedOpensTheFilePlain`: a journal with one stroke on another base; the copy's journal path broken; `session()`. Assert no `OpenFailed`, one `EarlierWritingKept`, `myEditsCopies()` empty, `s.file == pdf`.
  - `aSessionThatMovedTwiceFreesBothCopies`: conflict copy, then that copy changed on disk with a new stroke, `saveNow()` (second copy), `close()`. `DocumentSession.anyOpenIn(pdf.parentFile)` is false.
  - `aDamagedFilesCopyNeverReplacesAFileThatArrived`: `saveRepairedToCopy()` with a file already at the copy's first name: that file is untouched and the copy has the next name (today it uses `SafeSave.replace`).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*DocumentSessionTest*' -q`: the five fail.
- [ ] **Step 3: Implement** `continueInCopy` and the new `switchTo` as in Interfaces, and call `continueInCopy` from `fileChanged` (reason `CHANGED_WHILE_OPEN`), `rescueIntoCopy` (`RESCUED`, inside its `try`, so a failure takes its existing way out: journal set aside, file opened plain) and `saveRepairedToCopy` (`DAMAGED`; catch `Throwable`).
- [ ] **Step 4: Bring the comments up to date:** the class comment's fourth point (a changed file's writing goes into a copy, not "stays in the journal"), `changePages` ("leaves no note" is now "leaves a renumbering note"), `markSaving` and `restartJournal` (a journal on another base is rescued into a copy at the next opening, not set aside).
- [ ] **Step 5: Run the laptop tests**: all pass, the existing conflict tests included.
- [ ] **Step 6: Commit.** `git commit -m "conflicts: one way into a copy, which either happens whole or leaves nothing behind"`

### Task 2: A changed file is opened only with the pen held (review A2, A9; decision 1)

**Files:**
- Modify: `main/doc/SessionEvent.kt`, `main/doc/DocumentSession.kt` (`fileChanged`, `redrawFaultyInkFrom`, new `reload`), `main/ui/WritingActivity.kt` (`show`, `onPause`, a new `holdForReload`)
- Test: `test/doc/DocumentSessionTest.kt`

**Interfaces:**
- Produces: `data object ChangedElsewhere : SessionEvent` (the file changed on disk and nothing is unsaved: hold the pen, then call `reload`) and `data object NotReloaded : SessionEvent` (no new version was opened after all: let the pen go). `Reloaded` keeps its meaning.
- Produces: `fun reload()` on `DocumentSession`, any thread; the work runs on the worker. It always answers with exactly one of `Reloaded`, `NotReloaded` or `OpenFailed`, whatever is thrown. In order:
  1. Read the file's identity. Unreadable, or the same contents as `known`: `NotReloaded`.
  2. `open(file)` into a local, the old PDF still open. If it throws: `NotReloaded`, and the old version stays on screen.
  3. In one `synchronized(lock)` block: if `unsaved` isn't empty, nothing is swapped. Otherwise the journal is rewritten on the new base (a failure is a `JournalFailed`, as now), the undo and redo lists are emptied, `pageChanges += { if (it < newCount) it else null }` with the new version's page count, and `pageNumbering++`.
  4. Not swapped: close the new one, send `NotReloaded`, and go the copy way (`continueInCopy`, as `fileChanged` does with unsaved writing).
  5. Swapped: close the old PDF, `pages` is the new one, `applied = 0`, `known` the new identity, `inkRedrawn = false`, a new ink pass (below), `checkRepair`, then `HistoryChanged(false, false)` and `Reloaded`.
- Changes: `fileChanged` with nothing unsaved and a readable file sends `ChangedElsewhere` and does nothing else. It sends it each time the change is noticed; the screen ignores a second one while the first is being handled.
- Produces: `private var inkPass = 0` in `DocumentSession`, worker only. `redrawFaultyInkFrom(page, redrawnHere, pass = inkPass)` returns at once when `pass != inkPass`. A reload does `inkPass++`, resets `inkCheckNs` and `inkCheckRedrawn`, and starts the new pass with the usual delay.

- [ ] **Step 1: Write the failing tests**, and change the three existing ones that expect a reload straight from `checkFile()` or a save (`aFileChangedWhileOpenWithNothingUnsavedIsOpenedAgain`, `redrawnInkAloneNeverMakesAConflictCopy`, `lookingAtAnUnchangedFileDoesNotReadIt` if it counts events) to call `reload()` after `ChangedElsewhere`.
  - `aChangedFileWithNothingUnsavedAsksFirst`: file changed, `checkFile()`: one `ChangedElsewhere`, no `Reloaded`, the old fake still open (`fake.closed` false).
  - `reloadOpensTheNewVersion`: then `reload()` with `opening` handing out a second fake of 3 pages: `Reloaded`, the first fake closed, `pageNumbering == 1`, the journal's base is the new file's identity.
  - `aStrokeBeforeTheReloadGoesIntoACopyOfTheOldVersion`: `ChangedElsewhere`, then `addStroke(0, dot)`, then `reload()`: `NotReloaded` and `SavedToCopy(CHANGED_WHILE_OPEN)`; the copy holds the stroke; `pdf.readText()` is the other program's text.
  - `aLateStrokeAfterAReloadKeepsItsPage`: reload onto a 5-page fake; `addStroke(2, dot, numbering = 0)` lands on page 2.
  - `aLateStrokeOnAPageTheNewVersionLacksIsNotRecorded`: reload onto a 3-page fake; `addStroke(4, dot, numbering = 0)` returns null; no `WritingStopped`; a later `addStroke(0, line)` is saved.
  - `aNewVersionThatCanNotBeOpenedLeavesTheOldOneOnScreen`: `opening` throws `OutOfMemoryError` on the second call: `NotReloaded`, no `OpenFailed`, `strokesOn` still works on the first fake.
  - `aReloadOfAFileThatChangedBackAnswersNotReloaded`.
  - `anInkRedrawUnderWayStopsAtAReload`: with `fake.duringRedraw` used to trigger the reload in the middle of the first pass, the first fake sees no `redrawFaultyInk` call after the reload, and the second fake is walked once from page 0.
- [ ] **Step 2: Run**: they fail. **Step 3: Implement** the session side as in Interfaces.
- [ ] **Step 4: The screen.** In `WritingActivity.show`:
  - `ChangedElsewhere` (it needs the screen: add it to `needsTheScreen`): if `reloadWanted` is already set, nothing. Otherwise set it and call `holdForReload()`: `if (holdForPageChange("reload")) session.reload() else handler.postDelayed(retryReload, 300)`, where `retryReload` calls `holdForReload()` again. `holdForPageChange` already refuses while the pen is down or fingers are moving the page, so a stroke in progress is never cut.
  - `Reloaded`: `reloadWanted = false`, the toast as now, `pagesRenumbered(...)` as now (it ends the hold once the pages are laid out).
  - `NotReloaded`: `reloadWanted = false`, `releasePageChangeHold()`.
  - `onPause` removes `retryReload` and clears `reloadWanted`; `onResume` already calls `checkFile()`, which asks again.
  - `OpenFailed` during a held reload can't happen by the contract above; `PageNotChanged` after `Reloaded` no longer lets the pen go early, because the hold is the same flag `pagesRenumbered` clears.
- [ ] **Step 5: Run the laptop tests**, then on the tablet (debug build, scratch copy, `library.xml` backed up): open the scratch PDF, press Home, `adb push` a PDF with a different page count over it, come back. The log shows `reload` (the hold), then `pages renumbered`, `pages laid out again: N pages`; the toast shows; the pen writes after it.
- [ ] **Step 6: Commit.** `git commit -m "conflicts: a changed file is opened only once the pen is held; a stroke before that goes into a copy"`

### Task 3: A copy is checked before it's trusted (review A3; decisions 2 and 3)

**Files:**
- Create: `androidTest/pdf/PdfFileChangedUnderneathTest.kt`
- Modify: `main/doc/DocumentSession.kt` (`continueInCopy`), `docs/superpowers/findings-log.md`; only with decision 3: `main/pdf/PdfFile.kt` (`open`)
- Test: `test/doc/DocumentSessionTest.kt`

- [ ] **Step 1: Find out on the tablet** what MuPDF does when the file under an open document is overwritten in place. `PdfFileChangedUnderneathTest`, on two test PDFs from `TestPdfs` of different size and page count (A with 40 pages of text, B with 3), each case on a fresh copy of A, opened with `PdfFile.open`, only page 0 drawn:
  - `overwrittenInPlaceThenSaved`: B's bytes are written over A's file through `FileOutputStream(file)` (same file, cut to B's length); a stroke is applied; `saveTo(copy)`. Record: thrown or not; if not, does `PdfFile.open(copy)` say `wasRepaired`, what is its page count, and does page 30 draw?
  - `overwrittenInPlaceThenDrawn`: the same overwrite, then `render(30, 600)`: thrown, blank, or A's page 30?
  - `replacedByRenameThenSaved`: B is written beside A and moved over it (`Files.move`, `ATOMIC_MOVE`), as Syncthing does. Expected: the copy is A with the stroke, whole.
  These record what happens (log lines and a table in the findings log); only the third asserts.
- [ ] **Step 2: Write up** the table in `docs/superpowers/findings-log.md` under today's date, and say there whether decision 3 is needed: it is if the first case ever gives a copy that opens (repaired or not) without A's 40 pages, or if the second draws anything but A's page or a clean failure.
- [ ] **Step 3: Write the failing laptop tests.**
  - `aCopyThatDoesNotOpenCleanIsNotTrusted`: `opening` hands out, for the copy's path, a fake with `wasRepaired = true`. A conflict copy is attempted: `SaveFailed`, no file with `(my edits` left, the original's journal still holds the stroke.
  - `aCopyWithTheWrongPageCountIsNotTrusted`: likewise with a fake of 2 pages against the open document's 5.
  - `aCopyThatOpensCleanIsUsed`: one extra `open` call, on the copy, closed again (`closed` true on that fake).
- [ ] **Step 4: Implement** in `continueInCopy`, between `SafeSave.createNew` and `switchTo`: `open(copy)` into a local, compare `pageCount` with `p.pageCount`, require `!wasRepaired`, close it in a `finally`, and throw `IOException("the copy of ${file.name} didn't come out whole")` otherwise. The failure path of Task 1 removes the copy.
- [ ] **Step 5 (only if Step 2 says so, and the owner approved decision 3):** `PdfFile.open` reads a file of at most `IN_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024` into a byte array and opens it with `Document.openDocument(bytes, "pdf")`; bigger files open by path as now, with a comment giving the reason and the measured memory (`dumpsys meminfo` before and after on the 14 MB deck, into the findings log). The three tablet cases then all give A whole. Run `SpeedCheck` before and after: opening and the first page must not be slower by more than the file's read time.
- [ ] **Step 6: Run** laptop and tablet tests. **Commit.** `git commit -m "conflicts: a copy is opened and checked before the journal with its writing is cleared"`

### Task 4: No copy with nothing in it (review A4, A5; decision 4)

**Files:**
- Modify: `main/doc/DocumentSession.kt` (`rescueIntoCopy`)
- Test: `test/doc/DocumentSessionTest.kt`

- [ ] **Step 1: Write the failing tests.**
  - `earlierWritingThatFitsNowhereMakesNoCopy`: a journal on another base with one stroke on page 45; the file has 5 pages. Opening: one `EarlierWritingKept`, no `SavedToCopy`, no `(my edits` file, `s.file == pdf`, and a new stroke is saved into `pdf`.
  - `earlierWritingAlreadyInTheFileMakesNoCopy`: a journal on another base with `AddItems` of two strokes; the fake already holds items with those ids. Opening: no `SavedToCopy`, no `EarlierWritingKept`, no copy; the journal is fresh on the file's identity.
  - `earlierWritingPartlyInTheFileStillGoesIntoACopy`: the fake holds one of the two: a copy, as today.
  - `anEraseAmongEarlierWritingStillGoesIntoACopy`: `AddItems` then `RemoveItems` of an item the file holds: a copy, as today (only a journal of nothing but additions the file already has is skipped).
- [ ] **Step 2: Run**: they fail. **Step 3: Implement** at the top of `rescueIntoCopy`: with `placeable` empty, `setAside()` and `journal.start(known!!)` under the lock, and return; with every placeable change an `AddItems` whose items' ids are all among `opened.items(page)` (read once per page), `journal.start(known!!)` and return. Both leave `opened` untouched.
- [ ] **Step 4: Run**: pass. **Commit.** `git commit -m "conflicts: no copy is made when there's nothing to put in it"`

### Task 5: The journal's notes (review A6, A10)

**Files:**
- Modify: `main/doc/ChangeJournal.kt` (`markSaving`, `markRenumbering`, `picturesInFile`), `main/doc/DocumentSession.kt` (`save`'s `catch`)
- Test: `test/doc/ChangeJournalTest.kt`, `test/doc/DocumentSessionTest.kt`

**Interfaces:**
- Produces: `private fun dropCutShortTail()` in `ChangeJournal`: what `picturesInFile` does today before it reads (the file cut back to its last newline, synced), taken out so `markSaving` and `markRenumbering` call it first too. It reads only the file's last block, not the whole file: these notes are written at every save.

- [ ] **Step 1: Write the failing tests.** In `ChangeJournalTest`, beside `anAppendAfterACutShortLineIsNotJoinedToIt`: `aSavingNoteAfterACutShortLineIsNotJoinedToIt` and `aRenumberingNoteAfterACutShortLineIsNotJoinedToIt` (a journal with one whole change, then half a line with no newline appended by hand; the note is written; `read()` gives the one change and the note). In `DocumentSessionTest`: `aPageChangeThatCouldNotBeSavedLeavesNoRenumberingNote`: a stroke, `fake.failNextSave = true`, `addPageAfter(0)` (so `PageNotChanged`), then `ChangeJournal.forDocument(journals, pdf).read()!!.renumbering` is false and the stroke is still in the journal.
- [ ] **Step 2: Run**: they fail. **Step 3: Implement.** In `save`'s `catch`, when `newPageNumber != null && !swapped`: under the lock, `journal.rewrite(known!!, unsaved)` (a rewrite drops notes), with its own `IOException` handled as a journal failure like the others.
- [ ] **Step 4: Run**: pass. **Commit.** `git commit -m "journal: a note is never joined to a cut-short line; a page change that failed leaves no note"`

### Task 6: Checklist and owner session (hard stop)

- [ ] **Step 1:** All laptop tests and all tablet tests.
- [ ] **Step 2:** Add to `notes/TESTING-conflicts.md`: **C4** (come back to Jot after the file was replaced from the laptop and write at once: is the word in a "(my edits)" copy, with the file left as the laptop's?), **C5** (the same, waiting for the "new version" message before writing: no copy, the word in the file), and a line under C1 that only one copy appears.
- [ ] **Step 3:** Spec §7.4: a changed file with nothing unsaved is opened once the pen is held; a copy is checked before it's trusted; nothing to rescue makes no copy. `TODO.md`: the items under "Review (2026-11-16)" ticked. Findings log: Task 3's table.
- [ ] **Step 4:** Release build on the tablet, `library.xml` as the owner left it. **Stop for the owner's session.**
