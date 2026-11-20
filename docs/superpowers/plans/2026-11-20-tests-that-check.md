# Tests That Check What They Claim: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the tests in tier 1 of `TODO.md` ("Reviews (2026-11-20)") fail when the behaviour they name breaks. Today each one would pass even if that behaviour were gone.

**Architecture:** Mostly test changes. The app code changes in three places, and only so that a test can see what happens: `FakeScheduler` (a test file) records the delay each timer was given, `ContinuePicture` gets a way to swap in the function that writes the file, and `FolderWatch` counts how many subfolder watches it has started. What the app does stays the same. Every task ends with a **bite check**: break the behaviour on purpose, watch the fixed test fail, then put the code back.

**Tech Stack:** As now (Kotlin 2.0.21, MuPDF `fitz:1.28.5`, JUnit 4.13.2). Nothing new.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` §7.3 (every stroke survives a crash). The findings come from Neon's review, `docs/reviews/2026-11-20-neon-code-only.md` §6.1.

**Where this plan sits:** the first plan from the 2026-11-20 reviews, run before any fix in tiers 2 to 4. Those fixes lean on the journal, save and Continue tests, so those tests have to work first. The plan runs on its own branch, `test-fixes`, from `main`.

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, and `test/…` and `androidTest/…` work the same way.

## What was checked before writing this

- **Journal torn-line tests: the problem is real.** In a `pnj2` journal, `ItemCodec.decode` accepts only `ink` and `img` items. The tests append `add 1 pn1 hl …`, which never decodes, newline or not.
- **Erased-stroke test: the problem is real.** `PdfFile.saveTo` saves with `"compress,garbage,objstms"`, and its own comment says that objstms compresses the stroke text. The MuPDF calls the new test uses (`countObjects`, `newIndirect`, `resolve`, `isDictionary`, `get(String)`, `isNull`) are all in fitz 1.28.5's API jar.
- **Two of Neon's seven items are left out of this plan:**
  - **`IndexCheckTest` and `MemoryIndexStore`.** A folder read before the card went missing holds true facts, so it does no harm when kept. The harm would be in a drop, and the existing tests already check that nothing is dropped. This isn't a test that fails to check its claim.
  - **`InkOverlayTest.strokesSurviveAChange`.** Keeping strokes on a page that had no erase or move may be on purpose, or it may waste memory. That's a question for the memory work in tier 4, not a broken test.

  Task 6 moves both out of tier 1, with these reasons.

## Decisions for the owner (approve before Task 1)

All three approved by the owner on 2026-11-20.

1. **Delete `FolderWatchTest.aFileArrivingDuringTheListingIsSeen`.** What it means to check, that the library starts the watch before it reads the folder, happens in `LibraryActivity` (line 513), which has no tests. What it actually checks is that a file is seen after the watch starts, and `newPdfIsNoticed` already covers that. Keeping it under its current name says something is tested when it isn't. An activity test for that order goes in tier 6.
2. **Two small seams in app code.** `ContinuePicture` gets a fourth constructor parameter, `write`, whose default is the same `writeBytes` it calls now. `FolderWatch` gets a counter, `childWatchesStarted`. Neither changes what the app does.
3. **Commit the five reviews and the new section of `TODO.md` on `main` before branching.** Both are uncommitted on `main` right now.

## Global Constraints

- Every fixed test must fail when the behaviour it names is broken. Each task's bite check shows that, and the plan isn't done until each bite check has failed as expected and the code has been put back.
- No change to what the app does. The two seams default to exactly the current code.
- `doc/` and `storage/` stay free of Android classes, because they run in laptop tests.
- fitz 1.28.5: never `PDFObject.push(PDFObject)` or `addObject` (`NoRefcountTrapsTest`).
- Readable code: plain names, comments that say why.
- Toolchain: `source ~/.local/opt/android-env.sh`; `cd notes && ./gradlew :app:testDebugUnitTest -q`.
- Tablet tests: `./gradlew :app:installDebug :app:installDebugAndroidTest`, then `adb shell am instrument -w -e class <Class>[#method] com.fzizzle.epapernotes.test/androidx.test.runner.AndroidJUnitRunner`. Don't use `connectedDebugAndroidTest`, which uninstalls the app.
- Another session may be using the checkout and the tablet. Never `git add -A`, and wait for a quiet minute (`adb logcat -d -s Notes | tail`) before installing. Put the release build back on the tablet at the end (`./gradlew :app:installRelease`).

---

### Task 1: The journal's torn-line tests use lines that would parse

**Files:**
- Test: `test/doc/ChangeJournalTest.kt` (`tornLastLineIsIgnored`, `lineCutBetweenPointsIsIgnored`, and one new test)

**Interfaces:**
- Consumes: `ChangeCodec.encode(c: Change, pictureName: (ByteArray) -> String? = { null }): String` and `ChangeCodec.decode(line: String, picture: (String) -> ByteArray? = { null }): Change?`, both existing (`main/doc/ChangeCodec.kt`).

- [ ] **Step 1: Rewrite the two tests and add a control test.** Replace `tornLastLineIsIgnored`:

```kotlin
    /** A power cut can cut the last line short. The complete lines must still come back. */
    @Test fun tornLastLineIsIgnored() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        j.append(add(1, dot))
        // Cut inside the last number: "…,1.0" still reads as a stroke, so only the missing newline gives it away.
        val torn = ChangeCodec.encode(add(2, line)).dropLast(2)
        assertTrue("the torn line must be one that would parse", ChangeCodec.decode(torn) != null)
        j.file.appendText(torn)
        assertEquals(listOf(add(0, dot), add(1, dot)), j.read()!!.changes)
    }
```

Replace `lineCutBetweenPointsIsIgnored`:

```kotlin
    /** Cut exactly between two points, the line still parses, as a shorter, wrong stroke. It must be dropped. */
    @Test fun lineCutBetweenPointsIsIgnored() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        val cut = ChangeCodec.encode(add(1, line)).substringBeforeLast(' ') // the first point only
        val parsed = ChangeCodec.decode(cut) as AddItems
        assertEquals("the cut line must parse, as one point", 1, (parsed.items.single() as InkItem).stroke.points.size)
        j.file.appendText(cut)
        assertEquals(listOf(add(0, dot)), j.read()!!.changes)
    }
```

Add this after it, as the control: the same line with its newline is read, so the two tests above fail only because the newline is missing.

```kotlin
    /** The same line written whole is read: what the two tests above drop, they drop for the missing newline alone. */
    @Test fun aWholeLineAddedByHandIsRead() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        j.file.appendText(ChangeCodec.encode(add(1, line)) + "\n")
        assertEquals(listOf(add(0, dot), add(1, line)), j.read()!!.changes)
    }
```

- [ ] **Step 2: Run them.** `./gradlew :app:testDebugUnitTest --tests '*ChangeJournalTest*' -q`. Expected: all of them pass.

- [ ] **Step 3: Bite check.** In `main/doc/ChangeJournal.kt`, `read()`, make the line that drops the torn tail keep it:

```kotlin
        val complete = text.lines().filter { it.isNotEmpty() } // BITE CHECK: torn tail kept
```

Run the same command. Expected: `tornLastLineIsIgnored` and `lineCutBetweenPointsIsIgnored` fail, and `aWholeLineAddedByHandIsRead` passes. Then put the line back exactly as it was:

```kotlin
        val complete = text.substring(0, text.lastIndexOf('\n') + 1).lines().filter { it.isNotEmpty() }
```

Run the tests again and check that everything passes. Then check that `git diff notes/app/src/main` is empty.

- [ ] **Step 4: Commit.**

```bash
git add notes/app/src/test/java/com/fzizzle/epapernotes/doc/ChangeJournalTest.kt
git commit -m "tests: the journal's torn-line tests use lines that would parse"
```

---

### Task 2: The autosave test checks the timer, not just the constant

**Files:**
- Modify: `test/doc/Fakes.kt` (`FakeScheduler`)
- Test: `test/doc/DocumentSessionTest.kt` (`autosaveWaitsTenSeconds`)

**Interfaces:**
- Produces: `FakeScheduler.postedDelays: MutableList<Pair<String, Long>>`, one entry for every `postDelayed` call, in order and never cleared.
- Consumes: `DocumentSession.AUTOSAVE_DELAY_MS`, and the autosave key `"autosave"`, which is `private const val AUTOSAVE` in `DocumentSession`. The test spells it out.

- [ ] **Step 1: Record the delays in `FakeScheduler`.** In `test/doc/Fakes.kt`, change `FakeScheduler`:

```kotlin
class FakeScheduler : Scheduler {
    val delayed = linkedMapOf<String, () -> Unit>()

    /** Every postDelayed, with its delay, in order: the fake runs delayed work only when told, so this is how a test sees the timing asked for. */
    val postedDelays = mutableListOf<Pair<String, Long>>()
```

and its `postDelayed`:

```kotlin
    override fun postDelayed(key: String, delayMs: Long, task: () -> Unit) {
        postedDelays += key to delayMs
        delayed[key] = task
    }
```

- [ ] **Step 2: Rewrite the test.** In `DocumentSessionTest.kt`, replace `@Test fun autosaveWaitsTenSeconds() = assertEquals(10_000L, DocumentSession.AUTOSAVE_DELAY_MS)` with:

```kotlin
    /** A stroke starts the autosave's ten seconds, and the next stroke starts them again, under the same key. */
    @Test fun autosaveWaitsTenSecondsAfterTheLastStroke() {
        assertEquals(10_000L, DocumentSession.AUTOSAVE_DELAY_MS)
        val s = session()
        val before = scheduler.postedDelays.size
        s.addStroke(0, dot)
        s.addStroke(0, line)
        val autosaves = scheduler.postedDelays.drop(before).filter { it.first == "autosave" } // DocumentSession.AUTOSAVE
        assertEquals(listOf("autosave" to 10_000L, "autosave" to 10_000L), autosaves)
        assertTrue("one timer waiting, not two", scheduler.delayed.containsKey("autosave"))
    }
```

- [ ] **Step 3: Run it.** `./gradlew :app:testDebugUnitTest --tests '*DocumentSessionTest*' -q`. Expected: everything passes. If `autosaves` is empty, `addStroke` doesn't reach `scheduleAutosave` through the fake, so stop and find out why before going on.

- [ ] **Step 4: Bite check.** In `main/doc/DocumentSession.kt`, `scheduleAutosave()`, give the timer the retry delay:

```kotlin
        scheduler.postDelayed(AUTOSAVE, RETRY_DELAY_MS) { save() } // BITE CHECK
```

Run the command again. Expected: `autosaveWaitsTenSecondsAfterTheLastStroke` fails. Then put back `AUTOSAVE_DELAY_MS`, run again and check that everything passes, and check that `git diff notes/app/src/main` is empty.

- [ ] **Step 5: Commit.**

```bash
git add notes/app/src/test/java/com/fzizzle/epapernotes/doc/Fakes.kt notes/app/src/test/java/com/fzizzle/epapernotes/doc/DocumentSessionTest.kt
git commit -m "tests: the autosave test checks the timer the session sets, not just the constant"
```

---

### Task 3: An erased stroke is looked for through MuPDF, not in compressed bytes (tablet)

**Files:**
- Test: `androidTest/pdf/PdfFileStrokesTest.kt` (`erasedStrokeIsGoneFromFile`, plus a new private helper beside `openRaw`)

**Interfaces:**
- Consumes: `openRaw(file: File): PDFDocument` (existing in the test class); fitz `PDFDocument.countObjects(): Int`, `PDFDocument.newIndirect(num: Int, gen: Int): PDFObject`, `PDFObject.resolve()`, `.isDictionary`, `.get(String)` and `.isNull`; `StrokeCodec.KEY`.

- [ ] **Step 1: Add the helper** below `openRaw`:

```kotlin
    /**
     * How many objects in [file] carry stroke text, found by asking MuPDF. A plain text search of the
     * bytes finds nothing either way: saveTo packs objects into compressed object streams ("objstms").
     */
    private fun objectsHoldingStrokeText(file: File): Int {
        val doc = openRaw(file)
        try {
            return (1 until doc.countObjects()).count { n ->
                val o = doc.newIndirect(n, 0).resolve()
                o != null && o.isDictionary && !o.get(StrokeCodec.KEY).isNull
            }
        } finally {
            doc.destroy()
        }
    }
```

(`StrokeCodec` is already imported by the test, since `writeTheOldWay` uses `StrokeCodec.KEY`. If it isn't, add `import com.fzizzle.epapernotes.ink.StrokeCodec`. The helper uses neither `push` nor `addObject`.)

- [ ] **Step 2: Rewrite the test:**

```kotlin
    /** Erased handwriting must really leave the file, not just stop being shown. */
    @Test fun erasedStrokeIsGoneFromFile() {
        val first = File(dir, "erase1.pdf")
        val second = File(dir, "erase2.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.add(0, line); it.saveTo(first) }
        assertEquals("the stroke is found in the file before the erase", 1, objectsHoldingStrokeText(first))
        PdfFile.open(first).use { it.delete(0, it.strokes(0).single().id); it.saveTo(second) }
        assertEquals(0, objectsHoldingStrokeText(second))
    }
```

- [ ] **Step 3: Run it on the tablet.** Wait for a quiet minute, then `./gradlew :app:installDebug :app:installDebugAndroidTest` and `adb shell am instrument -w -e class com.fzizzle.epapernotes.pdf.PdfFileStrokesTest com.fzizzle.epapernotes.test/androidx.test.runner.AndroidJUnitRunner`. Expected: `OK`. If the first assertion fails with 0, the helper can't see objects inside object streams, so stop and report it rather than loosening the assertion.

- [ ] **Step 4: Bite check.** In `main/pdf/PdfFile.kt`, `saveTo`, drop `garbage`, so that an erased stroke is only unlinked and stays in the file:

```kotlin
        doc.save(target.path, "compress,objstms") // BITE CHECK
```

Install and run the same class. Expected: `erasedStrokeIsGoneFromFile` fails on the second assertion. Then put back `"compress,garbage,objstms"`, install and run again, and check that it passes. Check that `git diff notes/app/src/main` is empty.

- [ ] **Step 5: Commit.**

```bash
git add notes/app/src/androidTest/java/com/fzizzle/epapernotes/pdf/PdfFileStrokesTest.kt
git commit -m "tests: an erased stroke is looked for through MuPDF, not in compressed bytes"
```

---

### Task 4: A Continue picture cut off mid-write never replaces the last whole one (tablet)

**Files:**
- Modify: `main/ui/ContinuePicture.kt` (primary constructor, `store`)
- Test: `androidTest/ui/ContinuePictureTest.kt` (`aHalfWrittenPictureIsNeverLoaded`)

**Interfaces:**
- Produces: `ContinuePicture(dir: File, whose: () -> File?, setWhose: (File?) -> Unit, write: (File, ByteArray) -> Unit = { f, bytes -> f.writeBytes(bytes) })`. The secondary constructor `(cacheDir, prefs)` stays as it is and gets the default.

- [ ] **Step 1: Add the seam.** In `main/ui/ContinuePicture.kt`:

```kotlin
class ContinuePicture(
    private val dir: File,
    private val whose: () -> File?,
    private val setWhose: (File?) -> Unit,
    /** How the bytes reach the disk; a test swaps it to play a kill halfway through. */
    private val write: (File, ByteArray) -> Unit = { f, bytes -> f.writeBytes(bytes) },
) {
```

and in `store`, change `tmp.writeBytes(png)` to `write(tmp, png)`.

- [ ] **Step 2: Rewrite the test.** Add `import java.io.IOException` and `import org.junit.Assert.assertThrows`, and replace `aHalfWrittenPictureIsNeverLoaded`:

```kotlin
    /** The app killed while a new picture was being written: the last whole picture is still the one shown. */
    @Test fun aHalfWrittenPictureIsNeverLoaded() {
        val pdf = File("/sdcard/Documents/a.pdf")
        picture.store(pdf, png(Color.RED))
        val killedMidWrite = ContinuePicture(dir, { whose }, { whose = it }) { f, bytes ->
            f.writeBytes(bytes.copyOf(20)) // part of the picture is on disk…
            throw IOException("killed") // …when the app dies
        }
        assertThrows(IOException::class.java) { killedMidWrite.store(pdf, png(Color.BLUE)) }
        val shown = picture.load(pdf)
        assertNotNull("the last whole picture is still there", shown)
        assertEquals(Color.RED, shown!!.getPixel(0, 0))
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.endsWith(".tmp") })
    }
```

(Pure red survives RGB_565 exactly, so `getPixel` returns `Color.RED`.)

- [ ] **Step 3: Run it on the tablet.** `adb shell am instrument -w -e class com.fzizzle.epapernotes.ui.ContinuePictureTest com.fzizzle.epapernotes.test/androidx.test.runner.AndroidJUnitRunner`, after installing as in Task 3. Expected: `OK`.

- [ ] **Step 4: Bite check.** In `store`, write straight onto the picture instead of through the temp file:

```kotlin
            write(file, png); setWhose(pdf) // BITE CHECK
```

Install and run. Expected: `aHalfWrittenPictureIsNeverLoaded` fails, either because `shown` is null or because the pixel isn't red. Then put `store` back to its Step 1 form (`write(tmp, png)`, then `if (tmp.renameTo(file)) setWhose(pdf)`), install and run again, and check that it passes. `git diff` should show only the Step 1 seam.

- [ ] **Step 5: Commit.**

```bash
git add notes/app/src/main/java/com/fzizzle/epapernotes/ui/ContinuePicture.kt notes/app/src/androidTest/java/com/fzizzle/epapernotes/ui/ContinuePictureTest.kt
git commit -m "tests: a Continue picture cut off mid-write never replaces the last whole one"
```

---

### Task 5: The folder watch tests can see a second watch (tablet)

**Files:**
- Modify: `main/library/FolderWatch.kt` (`watchChildren`, plus a new counter)
- Test: `androidTest/library/FolderWatchTest.kt` (`watchingChildrenTwiceKeepsOneObserverEach` split in two; `aFileArrivingDuringTheListingIsSeen` deleted, decision 1)

**Interfaces:**
- Produces: `FolderWatch.childWatchesStarted: Int` (internal, read-only from outside), which goes up by one each time `watchChildren` starts a subfolder's watch.

- [ ] **Step 1: Add the counter.** In `FolderWatch`, below `children`:

```kotlin
    /**
     * How many subfolder watches [watchChildren] has started. The events from a second watch on
     * the same folder are merged with the first one's in the debounce, so no test could tell
     * them apart without this.
     */
    internal var childWatchesStarted = 0
        private set
```

and in `watchChildren`, after `.also { it.startWatching() }`, on the next line inside the loop:

```kotlin
            childWatchesStarted++
```

- [ ] **Step 2: Rewrite the tests.** Delete `aFileArrivingDuringTheListingIsSeen` along with its comment. Replace `watchingChildrenTwiceKeepsOneObserverEach` with these two:

```kotlin
    /** Passing the same subfolders again starts no second watch: only a folder new to the list, or one dropped and passed again, gets one. */
    @Test fun watchingChildrenTwiceKeepsOneObserverEach() {
        val week = File(scratch, "Week 2").apply { mkdirs() }
        val other = File(scratch, "Week 3").apply { mkdirs() }
        watch.watchChildren(listOf(week))
        watch.watchChildren(listOf(week))
        assertEquals(1, watch.childWatchesStarted)
        watch.watchChildren(listOf(week, other))
        assertEquals(2, watch.childWatchesStarted) // only Week 3 was new
        watch.watchChildren(emptyList())
        watch.watchChildren(listOf(week))
        assertEquals(3, watch.childWatchesStarted) // dropped, so watched afresh
    }

    /** A folder no longer passed isn't watched any more. */
    @Test fun aSubfolderNoLongerPassedIsNotWatched() {
        val week = File(scratch, "Week 2").apply { mkdirs() }
        Thread.sleep(1000)
        watch.watchChildren(listOf(week))
        watch.watchChildren(emptyList())
        calls.set(0)
        File(week, "d.pdf").writeText("x")
        Thread.sleep(1000)
        assertEquals(0, calls.get())
    }
```

- [ ] **Step 3: Run them on the tablet.** `adb shell am instrument -w -e class com.fzizzle.epapernotes.library.FolderWatchTest com.fzizzle.epapernotes.test/androidx.test.runner.AndroidJUnitRunner`, after installing. Expected: `OK`.

- [ ] **Step 4: Bite check.** In `watchChildren`, delete the line `if (folder in children) continue`. Install and run. Expected: `watchingChildrenTwiceKeepsOneObserverEach` fails at the first `assertEquals` (2, not 1). Then put the line back, install and run again, and check that it passes. `git diff` should show only the Step 1 counter.

- [ ] **Step 5: Commit.**

```bash
git add notes/app/src/main/java/com/fzizzle/epapernotes/library/FolderWatch.kt notes/app/src/androidTest/java/com/fzizzle/epapernotes/library/FolderWatchTest.kt
git commit -m "tests: the folder watch tests can see a second watch; drop one that tested nothing new"
```

---

### Task 6: Whole run, release build back, TODO updated

**Files:**
- Modify: `TODO.md` (section "Reviews (2026-11-20)", tiers 1, 4 and 6)

- [ ] **Step 1: Run every test.** Laptop: `./gradlew :app:testDebugUnitTest -q`, expected to pass. Tablet: `adb shell am instrument -w -e notPackage com.fzizzle.epapernotes.check com.fzizzle.epapernotes.test/androidx.test.runner.AndroidJUnitRunner`, expected `OK`. Note any failure that was already there on `main` rather than fixing it here.
- [ ] **Step 2: Put the release build back:** `./gradlew :app:installRelease`.
- [ ] **Step 3: Update `TODO.md`.** In tier 1, tick the five items done. Mark the `FolderWatchTest` item as done too, saying that the "arriving during the listing" test was deleted, by decision 1. Move the `IndexCheckTest` item out of tier 1 into tier 6, worded as "not a broken test: a folder read before the card went missing holds true facts; the harm would be in a drop, which is tested." Move the `InkOverlayTest` item into tier 4, beside the `InkOverlay.left()` item, worded as "decide whether keeping strokes on pages with no change is on purpose before changing the test." Add to tier 6: "An activity test that the library starts its folder watch before it reads the folder (`LibraryActivity`, 'Watched before it's read')."
- [ ] **Step 4: Commit.**

```bash
git add TODO.md
git commit -m "todo: tier 1 of the 2026-11-20 reviews done; two items moved, with reasons"
```

## Review Focus

1. **Each bite check really failed.** The record of each task should show the failing run, and `git diff notes/app/src/main` after Tasks 1 to 3 should be empty.
2. **The new assertions aren't weaker than the old ones.** Task 3 still requires the stroke to be gone from the file, now checked object by object. Task 4 still requires that no half-written picture is ever shown, and now also that the last good picture survives.
3. **The two seams don't change what the app does.** `ContinuePicture.write` defaults to `writeBytes` on the temp file. `childWatchesStarted` is only ever increased.
