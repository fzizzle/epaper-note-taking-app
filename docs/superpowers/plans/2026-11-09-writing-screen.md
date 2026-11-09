# Writing Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open one of the owner's PDFs, scroll and zoom through it with fingers, and write on it with the pen and highlighter in Boox fast-ink. Every stroke is safe from the moment the pen lifts, and is autosaved into the PDF in the background.

**Architecture:** Plain-Kotlin parts are unit-tested on the laptop's JVM: page layout and screen-to-PDF mapping, the crash journal, file identity and copy naming, and the document session that decides when to save. The session talks to the PDF through a small `PdfPages` interface that `PdfFile` implements. Tests use a fake, and the tablet tests use the real thing. The Android parts are thin: a page view for fingers, a pen wrapper around Onyx's `TouchHelper`, and the writing activity that wires them together. They're checked by hand on the tablet. A temporary start screen lists PDFs until the real library arrives in plan 4. The plan ends with a session where the owner writes with the pen on the tablet. That's a **hard stop**.

**Tech Stack:** As in plan 1 (Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1, compile/target SDK 35, min SDK 29, MuPDF `fitz:1.28.5`, JUnit 4.13.2, androidx.test). New: Onyx `com.onyx.android.sdk:onyxsdk-pen:1.5.9` and `org.lsposed.hiddenapibypass:hiddenapibypass:4.3`, set up the same way as in `probe/`.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md`, sections §6.2, §6.3 (pen and highlighter only), §7.2 and §7.3, including the 2026-11-09 decision on damaged files. Facts about pens and touch: `docs/superpowers/probe-results.md`. Speed and size: `docs/superpowers/speed-check-results.md`.

**Where this plan sits:** plan 2 of 5. Plan 3 adds the eraser, lasso, snip, paste, undo/redo, the rotation menu and the pen buttons. Plan 4 adds the library. Plan 5 adds conflict copies and crash replay onto a changed file. Until plan 5, a file that changed on disk is never overwritten: the app stops saving it and keeps the journal.

## Global Constraints

- "Writing is never lost" (spec §2.6, §4). "Every stroke must survive a crash, kill, or power loss from the moment the pen lifts", using a journal in app-private storage that's cleared "once the PDF save is confirmed" (spec §7.3).
- "No save button. Ink is written into the PDF automatically a short moment after the pen stops, when leaving the document, and when the app is backgrounded." "Saving never blocks or stutters the pen" (spec §7.2).
- "One finger scrolls. Two fingers pinch-zoom and pan. The pen never scrolls, and a finger never draws." "A resting palm or hand must neither draw nor move the page." Pages are "a vertical continuous scroll". The orientation is "portrait only, locked" (spec §6.2).
- The top strip is "about 5% of the height, always visible, one row". In this plan it holds `← library · pen · highlighter` plus the page indicator "4 / 32". Plan 3 adds the rest (spec §6.2).
- Pen: "Black, pressure-sensitive line weight, a single style". Highlighter: "wide, see-through … yellow" (spec §6.3).
- "Tapping a PDF opens it at the page where it was last left", and that position is kept "in the app's private storage, never in the owner's folders" (spec §6.1).
- Damaged files: when MuPDF had to repair a file, warn before writing to it, with the choice "save into this file anyway" or "keep the original and save my writing to a copy" (spec §7.3, owner-approved 2026-11-09).
- From the probe: turn Onyx's side-button-erase **off** (`enableSideBtnErase(false)`). Button 2 arrives as an eraser (`ONYX_ERASE`). Plan 3 makes it erase; here erase strokes are ignored and never saved.
- Readable code (spec §5): small files, plain names, comments that explain *why*.
- Only MuPDF code lives in `pdf/`. Only Onyx code lives in `ui/PenInput.kt`.
- The Android toolchain is user-local: run `source ~/.local/opt/android-env.sh` first. To run on the tablet, use `./gradlew :app:connectedDebugAndroidTest`, which uninstalls the app afterwards. For hand checks, use `./gradlew :app:installDebug`.
- Only the owner approves design changes (spec §5). Task 10 is a hard stop.

## Review Focus

1. **The app is killed between pen lift and autosave** (swiped away, battery died). The stroke must be back on reopen (Task 6: `strokeSurvivesKillBeforeSave`).
2. **Power is lost partway through writing a journal line.** Replay the complete lines and ignore the torn last one. Never refuse the whole journal (Task 4: `tornLastLineIsIgnored`).
3. **A sync app changes the PDF while it's open.** No save may overwrite it. The journal is kept and the owner is told (Task 5: `changedOnDiskBlocksSaveAndKeepsJournal`).
4. **Writing while zoomed in and scrolled, on a rotated page with an offset MediaBox.** The stroke must land at the right PDF spot (Task 7: `mapsThroughZoomScrollAndRotation`).
5. **A save fails** (storage full, card removed). The owner is told, the journal is kept, and the next autosave tries again (Task 5: `failedSaveKeepsJournalAndRetries`).

---

### Task 1: Smaller strokes

The speed check measured about 23 KB per 200-point stroke. Two things cause it: a circle at every point, and stroke data stored as uncompressed text. This task fixes both.

**Files:**
- Modify: `notes/app/src/main/java/com/fzizzle/epapernotes/ink/StrokeOutline.kt`
- Modify: `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/PdfFile.kt` (`saveTo`)
- Test: `notes/app/src/test/java/com/fzizzle/epapernotes/ink/StrokeOutlineTest.kt`, `notes/app/src/androidTest/java/com/fzizzle/epapernotes/pdf/PdfFileStrokesTest.kt`

**Interfaces:**
- Consumes and produces the same signatures as plan 1: `StrokeOutline.bounds(stroke): Box` and `StrokeOutline.contentStream(stroke): String`. Only what they produce changes.

`contentStream`, the new shape:
1. Drop consecutive duplicate points.
2. One point: one circle, as before.
3. Otherwise, one closed polygon. At each point, use the average of the normals of its neighbouring segments (an end point uses its single segment's normal). Offset by half the width at that point. Go forward along the left offsets, then back along the right offsets: `m`, `l` …, `h`, then fill with `f` (non-zero, so self-overlaps at tight curls fill solid).
4. Then a circle at the first point, at the last point, and at every point where the direction turns by more than 45°. The circles round the ends and fill the notch at sharp corners.

- [ ] **Step 1: Write the failing tests.** In `StrokeOutlineTest`:
  - `straightStrokeHasOnlyEndCaps`: a 200-point straight pen stroke gives exactly 8 ` c` curve operators, which is two circles.
  - `sharpCornerGetsACircle`: a 3-point stroke with a 90° turn gives 12, which is three circles.
  - `longStrokeIsSmall`: a 200-point wavy stroke (`y = 4·sin(i/5)`, varying pressure) gives a content stream shorter than 6000 characters.

  Keep every existing test. In `PdfFileStrokesTest`, tighten `savedStrokesAreCompact` to wavy strokes (the same formula as `SpeedCheck.wavyStroke`), with fewer than 4000 bytes per stroke.

- [ ] **Step 2: Run the tests and confirm they fail.** Run `./gradlew :app:testDebugUnitTest` and `./gradlew :app:connectedDebugAndroidTest`. Expected: the 3 new JVM tests and `savedStrokesAreCompact` fail.

- [ ] **Step 3: Implement the outline above.** Change `saveTo` to use `doc.save(target.path, "compress,garbage,objstms")`. `objstms` packs the strokes' text (the point list and `/PNotesStroke`) into compressed object streams. Update the `saveTo` comment.

- [ ] **Step 4: Run the tests and confirm they pass.** Same two commands. Expected: all pass, including `appearanceKeepsPressureAfterSave`, `strokeIdsStayTheSameAcrossSave` and `foreignAnnotationsSurviveSave`. If `objstms` renumbers objects so the ids change, drop `objstms` and record a ruling. The new outline alone must still meet the 4000-byte test.

- [ ] **Step 5: Rerun the speed check** (see "One practical lesson" in `speed-check-results.md`). Add the new size row to that file. Expected: under 1 MB added for 50 wavy strokes per PDF.

- [ ] **Step 6: Commit.** `git commit -m "notes: one outline per stroke and object streams, for much smaller files"`

### Task 2: Page-to-display mapping and the repair flag

**Files:**
- Create: `notes/app/src/main/java/com/fzizzle/epapernotes/geometry/Affine.kt`
- Modify: `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/PdfFile.kt`
- Test: `notes/app/src/test/java/com/fzizzle/epapernotes/geometry/AffineTest.kt`, `notes/app/src/androidTest/java/com/fzizzle/epapernotes/pdf/PdfFileOpenRenderRotateTest.kt`

**Interfaces:**
- Produces:
  - `data class Pt(val x: Float, val y: Float)`
  - `data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float)`, with the same meaning as a PDF matrix: `x' = a·x + c·y + e` and `y' = b·x + d·y + f`. It has:
    - `fun map(p: Pt): Pt`
    - `fun inverse(): Affine`
    - `fun then(next: Affine): Affine`, which applies `this` first, then `next`
    - `companion fun scale(s: Float)` and `companion fun translate(dx: Float, dy: Float)`
  - On `PdfFile`:
    - `fun pdfToDisplay(page: Int): Affine`, which maps PDF user space to display points (origin top-left, y down, rotation applied). It's built from MuPDF's `page.transform`.
    - `val wasRepaired: Boolean`, from `PDFDocument.wasRepaired()`, read once at open.

- [ ] **Step 1: Write the failing tests.**
  - JVM: `mapsPoint`, `inverseUndoes` (within 1e-4), `thenAppliesInOrder`.
  - Tablet: `pdfToDisplayHandlesOffsetAndRotation`. Use an offset MediaBox `Box(100,100,712,892)` and rotation 90. PDF `Pt(150,842)` maps to display `Pt(742,50)` within 0.5. Unrotated, it maps to `Pt(50,50)`.
  - Tablet: `blankFileWasNotRepaired`, which expects `false`.

- [ ] **Step 2: Run them and confirm they fail.** Expected: compilation failure.

- [ ] **Step 3: Implement.** MuPDF's `Matrix` has public fields `a`–`f`.

- [ ] **Step 4: Run them and confirm they pass.**

- [ ] **Step 5: Commit.** `git commit -m "notes: map PDF space to the screen, and report repaired files"`

### Task 3: File identity and copy names

**Files:**
- Create: `notes/app/src/main/java/com/fzizzle/epapernotes/storage/FileIdentity.kt`, `notes/app/src/main/java/com/fzizzle/epapernotes/storage/CopyNames.kt`
- Test: `notes/app/src/test/java/com/fzizzle/epapernotes/storage/FileIdentityTest.kt`, `notes/app/src/test/java/com/fzizzle/epapernotes/storage/CopyNamesTest.kt`

**Interfaces:**
- Produces:
  - `data class FileIdentity(val size: Long, val modifiedMs: Long, val sha256: String)`, with:
    - `companion fun of(file: File): FileIdentity`. The hash is hex, lowercase, over the whole file. Size and time alone can miss an edit that keeps both, and a 17 MB hash takes well under a second.
    - `fun encode(): String`, which gives `"<size> <modifiedMs> <sha256>"`
    - `companion fun decode(text: String): FileIdentity?`
  - `object CopyNames { fun myEdits(original: File, now: LocalDateTime): File }`. It returns `<name> (my edits YYYY-MM-DD).pdf` next to the original. If that exists, it uses `… (my edits YYYY-MM-DD HH-mm).pdf`. If that exists too, it appends ` 2`, ` 3` … before `)`. These are the spec §7.4 names. Plan 5 reuses them for conflicts.

- [ ] **Step 1: Write the failing tests.**
  - `sameContentSameIdentity`
  - `editedContentDifferentHash`: same size, new bytes, time forced back with `setLastModified`, and the hash differs.
  - `identityRoundTrips`
  - `decodeRejectsGarbage`
  - `copyNameUsesDate`: `Lecture 3.pdf` becomes `Lecture 3 (my edits 2026-11-09).pdf`.
  - `copyNameAddsTimeWhenTaken`
  - `copyNameAddsCounterWhenBothTaken`

- [ ] **Step 2: Run them and confirm they fail.**

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run them and confirm they pass.**

- [ ] **Step 5: Commit.** `git commit -m "notes: file identity and (my edits …) copy names"`

### Task 4: The crash journal

**Files:**
- Create: `notes/app/src/main/java/com/fzizzle/epapernotes/doc/StrokeJournal.kt`
- Test: `notes/app/src/test/java/com/fzizzle/epapernotes/doc/StrokeJournalTest.kt`

**Interfaces:**
- Consumes: `Stroke`, `StrokeCodec` (plan 1), `FileIdentity` (Task 3).
- Produces:
  - `data class PendingStroke(val page: Int, val stroke: Stroke)`
  - `data class JournalContents(val base: FileIdentity, val strokes: List<PendingStroke>)`
  - `class StrokeJournal(val file: File)`, with:
    - `fun start(base: FileIdentity)`, which truncates and writes the header
    - `fun append(page: Int, stroke: Stroke)`, which appends one line and fsyncs before it returns
    - `fun read(): JournalContents?`, which returns null when there's no file or no valid header
    - `fun clear()`, which deletes the file
  - `companion fun forDocument(journalDir: File, pdf: File): StrokeJournal`, named `<sha256 of the absolute path>.journal`. One journal per PDF lives in app-private storage, never in the owner's folders.

The format is plain text, one item per line. The header is `pnj1 <FileIdentity.encode()>`, and each stroke is `add <page> <StrokeCodec text>`. Plan 3 adds `del` lines. `read()` skips any line it can't parse, which covers a torn last line from a power cut. It never discards the lines it can parse.

- [ ] **Step 1: Write the failing tests.**
  - `appendedStrokesReadBack`
  - `tornLastLineIsIgnored`: write 2 good lines, then append half a line with no newline, and expect 2 strokes back.
  - `missingFileReadsNull`
  - `startReplacesOldContent`
  - `clearDeletes`
  - `journalNamesDifferPerPdf`
  - `baseIdentityIsKept`

- [ ] **Step 2: Run them and confirm they fail.**

- [ ] **Step 3: Implement.** Append with `FileOutputStream(file, true)`: write the line plus `\n`, then `fd.sync()` before `close()`.

- [ ] **Step 4: Run them and confirm they pass.**

- [ ] **Step 5: Commit.** `git commit -m "notes: crash journal so a stroke is safe the moment the pen lifts"`

### Task 5: The document session

This is the heart of "writing is never lost". It decides when to save, what to do when a save can't happen, and what to replay on open.

**Files:**
- Create:
  - `notes/app/src/main/java/com/fzizzle/epapernotes/doc/PdfPages.kt`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/doc/Scheduler.kt`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/doc/DocumentSession.kt`
- Modify: `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/PdfFile.kt` (`: PdfPages`)
- Test: `notes/app/src/test/java/com/fzizzle/epapernotes/doc/DocumentSessionTest.kt`, with a `FakePdfPages` and a `FakeScheduler` in the same test folder

**Interfaces:**
- Consumes: `StrokeJournal`, `FileIdentity`, `CopyNames`, `SafeSave`, `Stroke`.
- Produces:
  - `interface PdfPages : Closeable`, with:
    - `val pageCount: Int`
    - `val wasRepaired: Boolean`
    - `fun addStroke(page: Int, stroke: Stroke): Int`
    - `fun saveTo(target: File)`

    `PdfFile` implements it as it already is.
  - `interface Scheduler`, with:
    - `fun post(task: () -> Unit)`, which runs on the document's single worker thread
    - `fun postDelayed(key: String, delayMs: Long, task: () -> Unit)`, which replaces any pending task with the same key
    - `fun cancel(key: String)`

    `class WorkerScheduler : Scheduler` wraps one `ScheduledThreadPoolExecutor(1)`. It's the only thread that touches `PdfPages`.
  - `sealed interface SessionEvent`, with these cases:
    - `Saved`
    - `SaveFailed(message: String)`
    - `ChangedOnDisk`
    - `NeedsRepairDecision`
    - `Replayed(count: Int)`
    - `SavedToCopy(copy: File)`
  - `class DocumentSession(pdf: File, journal: StrokeJournal, open: (File) -> PdfPages, scheduler: Scheduler, events: (SessionEvent) -> Unit)`, with:
    - `fun start()`. On the worker, it first removes stale temp files with `SafeSave.cleanStale(pdf.parentFile)`, left by a save that crashed (spec §7.3). Then it opens the PDF and reads the journal:
      - **A journal whose base matches the file:** replay its strokes into the PDF, emit `Replayed(n)`, and schedule a save.
      - **A journal whose base doesn't match:** emit `ChangedOnDisk`, leave the journal untouched, and don't replay (plan 5 replays into a copy).
      - **No journal:** start one with the file's identity.

      If the file was repaired, emit `NeedsRepairDecision` and hold all saves until the owner decides.
    - `fun addStroke(page: Int, stroke: Stroke)`. It appends to the journal **on the calling thread, before returning**: that's the "from the moment the pen lifts" guarantee. Then it posts `pdf.addStroke` to the worker, and reschedules the save with key `"autosave"` for `AUTOSAVE_DELAY_MS = 3000`.
    - `fun saveNow()`, for leaving the document or going to the background. It cancels the pending autosave and posts a save.
    - `fun saveIntoRepairedFile()` and `fun saveRepairedToCopy()`: the owner's two answers to `NeedsRepairDecision`. The copy goes to `CopyNames.myEdits(pdf, now)` through `SafeSave.replace`. After that the session keeps working in the copy (spec §7.4), with a fresh journal based on the copy.
    - `fun close()`, which saves if anything is unsaved, then closes the PDF on the worker.

  A save, on the worker:
  1. If nothing is unsaved, do nothing.
  2. If the file's identity no longer matches the identity recorded at open or at the last save, emit `ChangedOnDisk`, keep the journal, and stop. No more saves are attempted for this file.
  3. Otherwise run `SafeSave.replace(pdf) { pages.saveTo(it) }`. Record the new identity, restart the journal with it, and emit `Saved`.
  4. If the save throws, emit `SaveFailed(message)`. Keep the journal and the unsaved flag, so the next autosave or `saveNow` tries again.

- [ ] **Step 1: Write the failing tests.** `FakePdfPages` records strokes and saves, writes a small file on `saveTo`, and can be told to throw on save or to report `wasRepaired`. `FakeScheduler` runs `post` tasks immediately and keeps delayed ones until `runDelayed()`.
  - `strokeIsJournaledBeforeAddStrokeReturns`
  - `staleTempFilesAreCleanedOnStart`
  - `autosaveRunsAfterDelayAndClearsJournal`: after the save, the journal holds the header and no strokes.
  - `newStrokeReschedulesAutosave`: two strokes leave one pending autosave.
  - `saveNowSavesImmediately`
  - `nothingUnsavedMeansNoSave`
  - `changedOnDiskBlocksSaveAndKeepsJournal`: change the file's bytes after `start()`, add a stroke and save. Expect `ChangedOnDisk`, the file unchanged, and the stroke still in the journal.
  - `failedSaveKeepsJournalAndRetries`: the first save throws and gives `SaveFailed`. The second succeeds and gives `Saved`, with the journal cleared.
  - `matchingJournalIsReplayedOnStart`: expect `Replayed(2)` and 2 strokes in the fake.
  - `mismatchedJournalIsKeptNotReplayed`
  - `repairedFileHoldsSavesUntilDecision`: no save before `saveIntoRepairedFile()`, and one after.
  - `repairedToCopyLeavesOriginalAndContinuesInCopy`: the original is unchanged, the copy exists with the strokes, and later saves go to the copy.

- [ ] **Step 2: Run them and confirm they fail.**

- [ ] **Step 3: Implement.** Comment each branch with the spec rule it serves.

- [ ] **Step 4: Run them and confirm they pass.**

- [ ] **Step 5: Commit.** `git commit -m "notes: document session: journal, autosave, and safe stops"`

### Task 6: The session on the tablet, with real files

**Files:**
- Test: `notes/app/src/androidTest/java/com/fzizzle/epapernotes/doc/DocumentSessionDeviceTest.kt`

**Interfaces:**
- Consumes: `DocumentSession`, `WorkerScheduler`, `PdfFile`, `StrokeJournal` and `TestPdfs`. `TestPdfs` is in the `pdf` androidTest package, so import it.

- [ ] **Step 1: Write the tests.**
  - `strokeSurvivesKillBeforeSave`: start a session on a blank test PDF, add a stroke, then simulate a kill. Abandon the session without `close()`, shut down its executor with `shutdownNow()`, and close the `PdfFile`. A new session on the same file must emit `Replayed(1)` and, after `saveNow()`, the file holds 1 stroke.
  - `autosaveWritesTheFile`: add a stroke and wait up to 6 s. The file now holds the stroke, and the journal has no strokes.
  - `repairedFileIsNotWrittenWithoutDecision`. Use `TestPdfs.truncated`. If `PdfFile.open` refuses it, skip the test with `assumeTrue`, because there's nothing to protect. If it opens, it should be repaired. Add a stroke, call `saveNow()`, and wait 1 s: the file's bytes are unchanged.

- [ ] **Step 2: Run them.** Run `./gradlew :app:connectedDebugAndroidTest`. Expected: all tablet tests pass. These check the real worker thread and real fsync, so they may pass on the first run. If so, note that in the ledger, as in plan 1 Task 6.

- [ ] **Step 3: Commit.** `git commit -m "notes: tablet tests for crash recovery and autosave"`

### Task 7: Page layout and screen-to-PDF mapping

**Files:**
- Create: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/PageLayout.kt`, `notes/app/src/main/java/com/fzizzle/epapernotes/ui/Viewport.kt`
- Test: `notes/app/src/test/java/com/fzizzle/epapernotes/ui/PageLayoutTest.kt`, `notes/app/src/test/java/com/fzizzle/epapernotes/ui/ViewportTest.kt`

**Interfaces:**
- Consumes: `Affine`, `Pt` (Task 2).
- Produces:
  - `class PageLayout(pageSizes: List<SizePt>, viewWidthPx: Int, gapPx: Int = 16)`, where `data class SizePt(val w: Float, val h: Float)` is in display points. At zoom 1 every page is scaled to the view width, so a landscape slide is wider per point than a portrait page. Pages stack top to bottom with `gapPx` between them. Members:
    - `fun pageTop(i: Int): Float`
    - `fun pageHeight(i: Int): Float`
    - `fun pageScale(i: Int): Float` (px per point at zoom 1)
    - `val contentHeight: Float`
    - `fun pageAt(contentY: Float): Int`, the nearest page if it lands in a gap
    - `fun visiblePages(top: Float, bottom: Float): IntRange`
  - `class Viewport(layout: PageLayout, viewHeightPx: Int)` with `var scrollX`, `var scrollY` and `var zoom`, where zoom runs from 1 to `MAX_ZOOM = 4`. Members:
    - `fun scrollBy(dx: Float, dy: Float)`, which keeps you inside the content; x stays 0 at zoom 1
    - `fun zoomAround(factor: Float, focusX: Float, focusY: Float)`, which keeps the content point under the focus still
    - `fun screenToPage(x: Float, y: Float): Pair<Int, Pt>`, which gives a page index and a point in display points on that page
    - `fun pageToScreen(page: Int, display: Affine): Affine`, a display-points-to-screen-px transform used to draw a page
    - `fun currentPage(): Int`, the page under the vertical centre, for "4 / 32"

  Screen to PDF is `display.inverse()` of `PdfFile.pdfToDisplay(page)` applied after `screenToPage`. That composition happens in `WritingActivity` (Task 9). The test below checks it with a hand-built `pdfToDisplay`.

- [ ] **Step 1: Write the failing tests.**
  - `pagesStackWithGaps`
  - `landscapePageFillsWidth`
  - `pageAtFindsPage`
  - `visiblePagesRange`
  - `scrollIsClamped`
  - `noSidewaysScrollAtZoomOne`
  - `zoomKeepsFocusPointStill`
  - `zoomIsClampedTo1And4`
  - `currentPageIsUnderCentre`
  - `mapsThroughZoomScrollAndRotation`: a 2-page layout where page 1 is 792×612 display points (a rotated portrait page with an offset MediaBox, using the `Affine` that Task 2's test expects). Zoom 2 at some focus, scroll partway down. A tap at the screen spot where PDF `Pt(150,842)` is drawn maps back to `(1, Pt(150,842))` within 0.5 pt.

- [ ] **Step 2: Run them and confirm they fail.**

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run them and confirm they pass.**

- [ ] **Step 5: Commit.** `git commit -m "notes: continuous page layout, zoom and screen-to-PDF mapping"`

### Task 8: App shell: Onyx set-up, file access, start list and page view

**Files:**
- Modify: `notes/settings.gradle.kts` (the Boox repo, HTTP, as in `probe/settings.gradle.kts`), `notes/gradle.properties` (`android.enableJetifier=true`), `notes/app/build.gradle.kts` (the Onyx and hiddenapibypass dependencies, plus the `packaging` excludes and `pickFirsts` from `probe/app/build.gradle.kts`), `notes/app/src/main/AndroidManifest.xml`
- Create:
  - `notes/app/src/main/java/com/fzizzle/epapernotes/NotesApp.kt` (the hidden-API bypass, as in `ProbeApp`)
  - `notes/app/src/main/java/com/fzizzle/epapernotes/ui/DevStartActivity.kt` (replaces `MainActivity`, which is deleted)
  - `notes/app/src/main/java/com/fzizzle/epapernotes/ui/WritingActivity.kt`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/ui/PageView.kt`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/ui/PageBitmaps.kt`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/ui/LastPageStore.kt`
- Test: `notes/app/src/test/java/com/fzizzle/epapernotes/ui/PageBitmapsTest.kt`

**Interfaces:**
- Consumes: `PageLayout`, `Viewport` (Task 7), `PdfFile.displaySize`, `render` and `pdfToDisplay`, `DocumentSession`.
- Produces:
  - The manifest declares `MANAGE_EXTERNAL_STORAGE`, which is the "All files access" route (spec §6.1, builder's call, recorded in the ledger). `WritingActivity` gets `screenOrientation="portrait"` and receives the PDF path as the `extra "path"`.
  - `DevStartActivity` is temporary, until plan 4. If All files access isn't granted, it shows a button that opens the system screen for it (`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`). Otherwise it lists every PDF under `/sdcard/Documents`, recursively, sorted by path and skipping `SafeSave.isTempFile` names, as plain text rows. Tapping a row opens `WritingActivity`.
  - `class PageBitmaps(maxBytes: Long, render: (page: Int, widthPx: Int) -> Bitmap)`, an LRU cache keyed by (page, widthPx). It has `fun get(page, widthPx): Bitmap?` (cached, or null), `fun put(…)` and `fun invalidate(page: Int)`. Renders run on the session's worker. The cache is filled from the UI thread when they finish. The budget is `maxBytes = 200 MB`. The test checks the LRU logic with tiny fake sizes, which the constructor's `sizeOf` hook allows.
  - `class PageView : View`. It draws the visible pages' bitmaps through `Viewport.pageToScreen`, and a light grey placeholder while a page is rendering. Re-render at the new width once a pinch ends. It handles **finger** `MotionEvent`s only (`TOOL_TYPE_FINGER`): one finger scrolls and two pinch and pan, using Android's `GestureDetector` and `ScaleGestureDetector`. It ignores stylus and eraser events, because the pen belongs to Onyx (Task 9). It exposes `var onFingerGestureStart: () -> Unit` and `var onFingerGestureEnd: () -> Unit` so fast-ink can be paused while fingers move. Single-finger taps do nothing, so a stray palm tap with fast-ink off is harmless (probe results §4).
  - `class LastPageStore(context)` has `fun get(path: String): Int` and `fun set(path: String, page: Int)`. It uses `SharedPreferences` named `last-page`.
  - `WritingActivity` (pen-less in this task): the top strip is about 5% of the height with `←` and the page indicator, and `PageView` fills the rest. It opens a `DocumentSession` in `onCreate` and scrolls to `LastPageStore.get`. It saves the current page in `onPause`, calls `session.saveNow()` in `onPause` and `session.close()` in `onDestroy`. `←` calls `finish()`. Session events show as short `Toast`s, except `NeedsRepairDecision`, which shows an `AlertDialog` with the two choices from spec §7.3. Copy: title "This file was damaged", message "It was repaired when it opened. Saving into it would replace the original with the repaired version.", buttons "Save into this file" and "Save my writing to a copy". The dialog can't be dismissed without choosing.

- [ ] **Step 1: Write the `PageBitmapsTest` LRU tests** (`evictsLeastRecentlyUsed`, `invalidateDropsAllWidthsOfPage`). Run them and confirm they fail.

- [ ] **Step 2: Implement `PageBitmaps`.** Run the tests and confirm they pass.

- [ ] **Step 3: Implement the rest of this task.** Build and install with `./gradlew :app:installDebug`.

- [ ] **Step 4: Hand check on the tablet.** Grant All files access, then open the 14 MB PWT lecture. Expected:
  - The pages fill the width, and one finger scrolls smoothly through all 125 pages.
  - A pinch zooms in, two fingers pan, and pages sharpen after the pinch.
  - The indicator follows the pages.
  - Go back, reopen, and it returns to the same page.

  Write down anything sluggish in the ledger.

- [ ] **Step 5: Commit.** `git commit -m "notes: app shell with file access, start list and finger-driven page view"`

### Task 9: Pen and highlighter in fast-ink

**Files:**
- Create: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/PenInput.kt`, `notes/app/src/main/java/com/fzizzle/epapernotes/ui/PenPoints.kt`
- Modify: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/WritingActivity.kt`
- Test: `notes/app/src/test/java/com/fzizzle/epapernotes/ui/PenPointsTest.kt`

**Interfaces:**
- Consumes: `Viewport.screenToPage`, `PdfFile.pdfToDisplay`, `Affine.inverse`, `DocumentSession.addStroke`, `PageBitmaps.invalidate`, `StrokeStyle`.
- Produces:
  - `enum class Tool { PEN, HIGHLIGHTER }`
  - `object PenPoints { const val MAX_PRESSURE = 4095f; fun toStroke(kind: StrokeKind, screen: List<FloatArray>, toPdf: (x: Float, y: Float) -> Pair<Int, Pt>): Pair<Int, Stroke>? }`. Each `FloatArray` is `[x, y, rawPressure]` in view px. Onyx reports EMR pressure from 0 to 4095 (probe log: `p=2091.00`). It normalises pressure and clamps it to 0..1. The stroke's page is the page under its **first** point. Points that fall on other pages are dropped. It returns null when no point is left.
  - `class PenInput(view: View, onStroke: (List<FloatArray>) -> Unit)` is the only Onyx code in the app. It has `fun on(limit: Rect)`, `fun off()`, `fun pause()` and `fun resume()` (`setRawDrawingEnabled`), and `fun setTool(tool: Tool, pxPerPt: Float)`:
    - **Pen:** `STROKE_STYLE_FOUNTAIN`, black, width `StrokeStyle.of(PEN).maxWidth * pxPerPt`.
    - **Highlighter:** `STROKE_STYLE_MARKER`, `Color.rgb(255, 235, 59)`, width `12 * pxPerPt`.

    It calls `enableSideBtnErase(false)`. It turns `onRawDrawingTouchPointListReceived` into the `FloatArray` list, and it ignores the erase callbacks (button 2 becomes plan 3's eraser).

- [ ] **Step 1: Write the failing tests.**
  - `pressureIsNormalised`, which expects 2047.5 to give 0.5
  - `pressureIsClamped`
  - `strokeBelongsToFirstPointsPage`
  - `pointsOnOtherPagesAreDropped`
  - `emptyGivesNull`

- [ ] **Step 2: Run them and confirm they fail.**

- [ ] **Step 3: Implement `PenPoints`, then `PenInput`, and wire them into `WritingActivity`:**
  - Add pen and highlighter buttons to the strip (the current one marked), with pen selected at start.
  - The limit rect is the `PageView` area below the strip. `pxPerPt = layout.pageScale(page) * viewport.zoom` for the current page, updated after zoom.
  - `onStroke` turns screen points into PDF points: `Viewport.screenToPage`, then the inverse of that page's `pdfToDisplay`. It then calls `session.addStroke` **on the Onyx callback thread**, so the journal write happens before the next stroke starts.
  - After 1 s with no pen activity (`PEN_IDLE_REDRAW_MS = 1000`), invalidate and re-render the pages that got strokes, so MuPDF's version of the ink replaces Onyx's temporary drawing.
  - `PageView.onFingerGestureStart` calls `pen.pause()`, and `onFingerGestureEnd` calls `pen.resume()`, with the limit rect updated.
  - In `onPause`, call `pen.off()`. In `onResume`, call `pen.on(...)`.

- [ ] **Step 4: Run the unit tests and confirm they pass.** Build and install.

- [ ] **Step 5: Hand check on the tablet.** Expected:
  - The pen writes with low latency and its line follows pressure. The highlighter draws a wide stroke.
  - After about a second, the strokes redraw from the PDF in the same place, including when zoomed in and on a rotated page.
  - Fingers scroll and zoom without drawing.
  - Leave the document, open the same PDF in Boox's own reader, and the ink is there.
  - Note in the ledger anything that needs tuning: width, pressure feel, highlighter colour on Kaleido, redraw flicker. Don't tune beyond simple constants here. Tuning with the owner happens in Task 10.

- [ ] **Step 6: Commit.** `git commit -m "notes: pen and highlighter in fast-ink, saved through the session"`

### Task 10: Owner session on the tablet (hard stop)

**Files:**
- Create: `notes/TESTING-writing.md` (the owner's checklist) and `docs/superpowers/writing-screen-results.md`

- [ ] **Step 1: Write the checklist in `notes/TESTING-writing.md`,** in plain words, about 15 minutes. Ask the owner to use a **copy** of a lecture PDF in `/sdcard/Documents`, because the app now writes into real files.
  - **W1. Writing feel:** pen latency and pressure, compared with Boox Notes.
  - **W2. Highlighter:** looks and readability of the text underneath.
  - **W3. Scrolling and zoom:** one finger, a pinch, writing while zoomed in.
  - **W4. Palm:** write with the palm resting on the screen.
  - **W5. Safety:** write 3 strokes, then swipe the app away from recent apps within 2 seconds. Reopen and the strokes should be there.
  - **W6. Another viewer:** open the file in Boox's reader and check the ink.
  - **W7. Last page:** go back and reopen.
  - **W8. Anything odd.**

- [ ] **Step 2: Run the session with the owner.** Tune the simple constants with them if they ask: pen widths, pressure curve, highlighter colour and width, and the redraw delay. Each change gets its own commit.

- [ ] **Step 3: Write `docs/superpowers/writing-screen-results.md`:** what worked, what was tuned (with the final values), and any design question that came up.

- [ ] **Step 4: Commit and stop.** `git commit -m "notes: owner writing session results"`

Hand the results to the owner and stop. Plan 3 (tools) is written only after the owner has approved the writing screen.
