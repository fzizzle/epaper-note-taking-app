# PDF Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Start the real app with its PDF layer: open a PDF with MuPDF, render pages, rotate them, write and read back the app's own pressure-sensitive strokes as PDF ink annotations, keep other apps' annotations intact, and save without ever leaving a half-written file. Then measure all of that on the tablet with one of the owner's real lecture PDFs.

**Architecture:** A new Gradle project in `notes/`, separate from the throwaway `probe/`. Pure-Kotlin code with no Android or MuPDF types (stroke model, the text format for stroke data, stroke outline geometry, safe file replace) is unit-tested on the laptop's JVM. One class, `PdfFile`, is the only code that touches MuPDF. It's tested with instrumented tests that run on the tablet. A final speed check, also run on the tablet, is a **hard stop**: its numbers go to the owner before any screen is built.

**Tech Stack:** Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Gradle 8.11.1, compileSdk/targetSdk 35, minSdk 29, MuPDF `com.artifex.mupdf:fitz:1.28.5` (from `https://maven.ghostscript.com`), JUnit 4.13.2, `androidx.test.ext:junit:1.2.1`, `androidx.test:runner:1.6.2`.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` (§5, §6.4, §7.1, §7.3). Pen-button and touch facts come from `docs/superpowers/probe-results.md`.

**Where this plan sits.** The design is too big for one plan, so it's split into five, each producing working software:

1. **PDF foundation** (this plan): project, PDF layer, safe save, speed check.
2. Writing screen: page view, scroll and zoom, pen and highlighter in fast-ink, autosave and the crash journal (§6.2, §7.2, §7.3).
3. Tools: stroke eraser, lasso, snip, paste, undo and redo, rotation menu, pen buttons (§6.3, §6.4).
4. Library screen: root folder, thumbnail grid, file changes, rename/move/delete (§6.1).
5. Conflicts: file identity, reload, "(my edits …)" copies, crash replay onto a changed file (§7.4).

Plans 2–5 are written after this one's speed check has been approved.

## Global Constraints

- Kotlin, native Android (spec §5).
- MuPDF is the PDF library, and the app is AGPL-3.0, see the repo's `LICENSE` (spec §5, owner-approved 2026-11-09).
- Readable code (spec §5): small files, plain names, comments that explain *why*, and no cleverness where something boring works.
- "Writing is never lost" (spec §2.6, §4). A failed save must leave the original file untouched and must never be silent.
- "The PDF on disk must never be left half-written": write a temporary file in the same folder, flush it to disk, then atomically replace the original. Temporary files must not show up in the library, and stale ones are cleaned up (spec §7.3).
- "Annotations the app didn't create … must be kept intact on save" (spec §7.1).
- Ink annotations are standard PDF ink annotations that other viewers render, and the highlighter is translucent (spec §7.1).
- "Ink already on a page turns with the page", and rotation is saved as the page's rotation property (spec §6.4).
- Pen: black, pressure-sensitive line weight, one style. Highlighter: wide, see-through, yellow, one colour (spec §6.3).
- The target device is the Boox Note Air 6 C: Android 16 (SDK 36.0), screen 1860×2480 px portrait at 300 dpi, CPU `arm64-v8a`. Ship `arm64-v8a` only, but don't use device-specific APIs in this plan.
- The Android toolchain is user-local. Run `source ~/.local/opt/android-env.sh` before any Gradle or adb command.
- Only the owner approves design changes (spec §5). The speed check (Task 7) is a hard stop.

## Review Focus

1. **Rotation inherited from the page tree.** Many PDFs set `/Rotate` on a parent `/Pages` node, not on each page. `rotation()` must report the inherited value, and `setRotation()` must still work (Task 4: `inheritedRotationIsReported`).
2. **Pages whose MediaBox doesn't start at 0,0.** Exports from PowerPoint and scanners often have offset boxes. A stroke must render exactly where its points say, before and after rotation (Task 5: `strokeRendersWhereDrawn_offsetMediaBox`).
3. **Other apps' annotations of other kinds.** A highlight from another viewer, a link and a free-text note must all survive a save with their type and position unchanged (Task 5: `foreignAnnotationsSurviveSave`).
4. **A save that fails partway** (disk full, app killed). The original file must be byte-for-byte unchanged, and no temp file is left behind (Task 6: `failedWriteLeavesOriginalUntouched`).
5. **A damaged or password-protected PDF** in the owner's folder. Opening it must fail with a clear `PdfOpenException`, not crash the app (Task 4: `garbageFileFailsCleanly`).

---

### Task 1: App project that builds and installs

**Files:**
- Create: `notes/settings.gradle.kts`, `notes/build.gradle.kts`, `notes/gradle.properties`, `notes/.gitignore`, `notes/app/build.gradle.kts`, `notes/app/src/main/AndroidManifest.xml`, `notes/app/src/main/java/com/fzizzle/epapernotes/MainActivity.kt`
- Create (copied): `notes/gradlew`, `notes/gradlew.bat`, `notes/gradle/wrapper/*`, copied from `probe/`
- Modify: `README.md`, with a "Building" section

**Interfaces:**
- Produces: package `com.fzizzle.epapernotes`, application ID `com.fzizzle.epapernotes`, and the commands `./gradlew :app:testDebugUnitTest` (laptop) and `./gradlew :app:connectedDebugAndroidTest` (tablet), both run from `notes/`.

- [ ] **Step 1: Create the Gradle project.** Copy `probe/settings.gradle.kts`, `probe/build.gradle.kts` and `probe/gradle.properties`, then change them:
  - Set `rootProject.name = "epaper-notes"`.
  - Dependency repos are `google()`, `mavenCentral()` and `maven("https://maven.ghostscript.com")`. No Boox repo yet, because the Onyx SDK comes in plan 2.
  - App module: namespace and applicationId `com.fzizzle.epapernotes`, minSdk 29, compile and target 35, Java and JVM target 17, `ndk { abiFilters += "arm64-v8a" }`, and `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`.
  - Dependencies: `com.artifex.mupdf:fitz:1.28.5`, `junit:junit:4.13.2` (test), `androidx.test.ext:junit:1.2.1` and `androidx.test:runner:1.6.2` (androidTest).
  - Manifest: `MainActivity` is the launcher, with `android:screenOrientation="portrait"` and label "E-paper Notes". For now `MainActivity` shows only a text view with the app name.

- [ ] **Step 2: Build.** Run `./gradlew :app:assembleDebug` in `notes/`. Expected: BUILD SUCCESSFUL. If MuPDF fails to resolve, report the error. Don't change its version silently.

- [ ] **Step 3: Install on the tablet.** Run `adb devices` (expect `4e982bb5 device`), then `./gradlew :app:installDebug`. Expected: "E-paper Notes" opens and shows its name.

- [ ] **Step 4: Add a "Building" section to `README.md`** with the env script, the `notes/` folder, and the three commands (assemble, unit tests, tablet tests).

- [ ] **Step 5: Commit.**

```bash
git add notes README.md
git commit -m "notes: empty app project with MuPDF that installs on the tablet"
```

### Task 2: Stroke model and its stored text format

**Files:**
- Create: `notes/app/src/main/java/com/fzizzle/epapernotes/ink/Stroke.kt`
- Create: `notes/app/src/main/java/com/fzizzle/epapernotes/ink/StrokeCodec.kt`
- Test: `notes/app/src/test/java/com/fzizzle/epapernotes/ink/StrokeCodecTest.kt`

**Interfaces:**
- Produces:
  - `enum class StrokeKind { PEN, HIGHLIGHTER }`
  - `data class StrokePoint(val x: Float, val y: Float, val pressure: Float)`. `x` and `y` are in **PDF user space** (points, y pointing up, the page's own unrotated coordinates, the same space as its MediaBox). Pressure is 0..1. Storing raw PDF space is why ink turns with the page for free (spec §6.4): rotation only changes how the page is displayed.
  - `data class Stroke(val kind: StrokeKind, val points: List<StrokePoint>)`, with at least one point.
  - `object StrokeCodec { const val KEY = "PNotesStroke"; fun encode(stroke: Stroke): String; fun decode(text: String): Stroke? }`. `decode` returns `null` for anything it doesn't recognise. Such annotations are then treated as foreign: kept and shown, but not edited.

The format is plain text so it stays readable in a PDF inspector: `pn1 <kind> <x>,<y>,<p> <x>,<y>,<p> …`. `<kind>` is `pen` or `hl`. `x` and `y` have 2 decimals, `p` has 3 decimals, and the decimal point is always `.`, whatever the device locale. The `pn1` prefix is a version tag, so the format can change later without breaking old files.

- [ ] **Step 1: Write the failing tests.**

```kotlin
class StrokeCodecTest {
    private val pen = Stroke(StrokeKind.PEN, listOf(StrokePoint(10f, 20.5f, 0.25f), StrokePoint(11.126f, 21f, 1f)))

    @Test fun encodesPen() = assertEquals("pn1 pen 10.00,20.50,0.250 11.13,21.00,1.000", StrokeCodec.encode(pen))
    @Test fun roundTrip() = assertEquals(StrokeCodec.decode(StrokeCodec.encode(pen))!!.points.size, 2)
    @Test fun highlighterKind() = assertEquals(StrokeKind.HIGHLIGHTER,
        StrokeCodec.decode("pn1 hl 1.00,2.00,0.500")!!.kind)
    @Test fun singlePointStroke() = assertEquals(1, StrokeCodec.decode("pn1 pen 5.00,5.00,0.100")!!.points.size)
    @Test fun unknownVersionIsNull() = assertNull(StrokeCodec.decode("pn9 pen 1,2,3"))
    @Test fun garbageIsNull() = assertNull(StrokeCodec.decode("pn1 pen 1.00,abc"))
    @Test fun emptyPointsIsNull() = assertNull(StrokeCodec.decode("pn1 pen"))
    @Test fun ignoresDeviceLocale() {
        val old = Locale.getDefault()
        try { Locale.setDefault(Locale.GERMANY); assertEquals("pn1 pen 10.00,20.50,0.250 11.13,21.00,1.000", StrokeCodec.encode(pen)) }
        finally { Locale.setDefault(old) }
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail.** Run `./gradlew :app:testDebugUnitTest`. Expected: compilation failure, because `StrokeCodec` is undefined.

- [ ] **Step 3: Implement `Stroke.kt` and `StrokeCodec.kt`** to the interfaces above. Use `String.format(Locale.ROOT, …)`.

- [ ] **Step 4: Run the tests and confirm they pass.** Same command. Expected: 8 tests pass.

- [ ] **Step 5: Commit.**

```bash
git add notes/app/src/main/java/com/fzizzle/epapernotes/ink notes/app/src/test
git commit -m "notes: stroke model and its stored text format"
```

### Task 3: Stroke outline and appearance drawing

Other viewers draw an ink annotation from its *appearance stream*, a small block of PDF drawing commands. MuPDF's automatic appearance draws one fixed width, which would lose pressure. So the app writes its own: each stroke becomes a filled outline whose width follows the pressure. MuPDF renders that same appearance in the app, so the app and other viewers look the same.

**Files:**
- Create: `notes/app/src/main/java/com/fzizzle/epapernotes/ink/StrokeStyle.kt`
- Create: `notes/app/src/main/java/com/fzizzle/epapernotes/ink/StrokeOutline.kt`
- Test: `notes/app/src/test/java/com/fzizzle/epapernotes/ink/StrokeOutlineTest.kt`

**Interfaces:**
- Consumes: `Stroke`, `StrokePoint`, `StrokeKind` (Task 2).
- Produces:
  - `data class StrokeStyle(val rgb: FloatArray, val opacity: Float, val minWidth: Float, val maxWidth: Float)`, with `companion fun of(kind: StrokeKind): StrokeStyle`. The values: **pen** `rgb = [0,0,0]`, `opacity = 1`, width 0.6–2.0 pt. **Highlighter** `rgb = [1, 0.92, 0.23]`, `opacity = 0.4`, width 12–12 pt. Width at a point = `minWidth + (maxWidth - minWidth) * pressure`. The pressure curve is the builder's call (spec §10). These are starting values, to be tuned on the tablet in plan 2.
  - `data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float)`, a rectangle in PDF user space.
  - `object StrokeOutline { fun bounds(stroke: Stroke): Box; fun contentStream(stroke: Stroke): String }`. `bounds` covers every point, padded by half the widest width at that point. `contentStream` returns PDF drawing operators in PDF user space: for each pair of neighbouring points, a filled quad between their offset edges, plus a round cap at each point drawn as a filled 4-Bézier circle. All of it is preceded by `<r> <g> <b> rg`, with numbers in `Locale.ROOT` and 2 decimals. A single-point stroke is just one dot.

- [ ] **Step 1: Write the failing tests.**

```kotlin
class StrokeOutlineTest {
    private fun pen(vararg p: StrokePoint) = Stroke(StrokeKind.PEN, p.toList())

    @Test fun penWidthFollowsPressure() {
        assertEquals(0.6f, StrokeStyle.of(StrokeKind.PEN).widthAt(0f), 1e-4f)
        assertEquals(2.0f, StrokeStyle.of(StrokeKind.PEN).widthAt(1f), 1e-4f)
    }
    @Test fun highlighterIsTranslucentYellow() {
        val s = StrokeStyle.of(StrokeKind.HIGHLIGHTER)
        assertEquals(0.4f, s.opacity, 1e-4f); assertArrayEquals(floatArrayOf(1f, 0.92f, 0.23f), s.rgb, 1e-4f)
    }
    @Test fun boundsPadByHalfWidth() {
        val b = StrokeOutline.bounds(pen(StrokePoint(100f, 100f, 1f), StrokePoint(200f, 100f, 1f)))
        assertEquals(Box(99f, 99f, 201f, 101f), b)
    }
    @Test fun singlePointIsADot() {
        val cs = StrokeOutline.contentStream(pen(StrokePoint(50f, 50f, 0.5f)))
        assertTrue(cs.startsWith("0.00 0.00 0.00 rg"))
        assertEquals(4, Regex(" c\\b").findAll(cs).count())   // one circle = 4 Bézier curves
        assertTrue(cs.trimEnd().endsWith("f"))
    }
    @Test fun usesDotDecimalsInAnyLocale() {
        val old = Locale.getDefault()
        try { Locale.setDefault(Locale.GERMANY); assertFalse(StrokeOutline.contentStream(pen(StrokePoint(1.5f, 1.5f, 0.5f))).contains(',')) }
        finally { Locale.setDefault(old) }
    }
}
```

  `widthAt(pressure: Float): Float` is a method on `StrokeStyle`.

- [ ] **Step 2: Run the tests and confirm they fail.** Run `./gradlew :app:testDebugUnitTest`. Expected: compilation failure.

- [ ] **Step 3: Implement `StrokeStyle.kt` and `StrokeOutline.kt`.** For each segment, take the unit normal of the segment, offset each end by half its own width, and fill the quad (`m l l l h f`). Draw a circle of radius half-width at every point, so joins and caps are round. If two neighbouring points are equal, skip the quad and keep the circle.

- [ ] **Step 4: Run the tests and confirm they pass.** Same command. Expected: 13 tests pass in total.

- [ ] **Step 5: Commit.**

```bash
git add notes/app/src/main/java/com/fzizzle/epapernotes/ink notes/app/src/test
git commit -m "notes: pressure-width stroke outline as PDF drawing commands"
```

### Task 4: Open, render and rotate with MuPDF

**Files:**
- Create: `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/PdfFile.kt`
- Create: `notes/app/src/androidTest/java/com/fzizzle/epapernotes/pdf/TestPdfs.kt`
- Test: `notes/app/src/androidTest/java/com/fzizzle/epapernotes/pdf/PdfFileOpenRenderRotateTest.kt`

**Interfaces:**
- Consumes: `Box` (Task 3), used by `TestPdfs` for page boxes.
- Produces:
  - `class PdfOpenException(message: String, cause: Throwable?) : Exception`
  - `class PdfFile : Closeable`, the only class that imports `com.artifex.mupdf.fitz`. Its methods:
    - `companion fun open(file: File): PdfFile`. Throws `PdfOpenException` if the file isn't a readable, unencrypted PDF (`Document.openDocument(path)`, then `asPDF()`, then `needsPassword()`).
    - `val pageCount: Int`
    - `fun displaySize(page: Int): SizeF`, the page size in points as shown, with rotation applied (`loadPage(page).bounds`).
    - `fun render(page: Int, widthPx: Int): Bitmap`, the page plus all its annotations, fitted to `widthPx` (`AndroidDrawDevice.drawPageFitWidth`).
    - `fun rotation(page: Int): Int`, which returns 0, 90, 180 or 270. It reads `/Rotate` from the page dictionary, walking `/Parent` links when it's missing, then normalises it (negative values and multiples of 360).
    - `fun setRotation(page: Int, degrees: Int)`, which normalises `degrees` and writes `/Rotate` on the page dictionary itself.
    - `fun saveTo(target: File)`, a full write to `target` (`PDFDocument.save(path, "")`). Task 6 wraps it in the safe replace. `PdfFile` never writes over its own source file.
  - `TestPdfs` (androidTest helper) builds test files with MuPDF's own API: `fun blank(dir: File, pages: Int = 2, mediaBox: Box = Box(0f, 0f, 612f, 792f), parentRotate: Int? = null): File`, which writes a PDF with plain white, empty pages, and `fun garbage(dir: File): File`, which writes 1 KB of random bytes named `bad.pdf`.

- [ ] **Step 1: Write the failing tests** (instrumented, in `app/src/androidTest`).

```kotlin
@RunWith(AndroidJUnit4::class)
class PdfFileOpenRenderRotateTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test fun opensAndCountsPages() = PdfFile.open(TestPdfs.blank(dir, pages = 3)).use { assertEquals(3, it.pageCount) }
    @Test fun rendersAtRequestedWidth() = PdfFile.open(TestPdfs.blank(dir)).use { assertEquals(1860, it.render(0, 1860).width) }
    @Test fun garbageFileFailsCleanly() { assertThrows(PdfOpenException::class.java) { PdfFile.open(TestPdfs.garbage(dir)) } }
    @Test fun inheritedRotationIsReported() = PdfFile.open(TestPdfs.blank(dir, parentRotate = 90)).use { assertEquals(90, it.rotation(0)) }
    @Test fun rotationSurvivesSave() {
        val out = File(dir, "rot.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.setRotation(1, 450); it.saveTo(out) }
        PdfFile.open(out).use {
            assertEquals(0, it.rotation(0)); assertEquals(90, it.rotation(1))
            assertEquals(792f, it.displaySize(1).width, 0.5f)   // a rotated portrait page is shown landscape
        }
    }
}
```

- [ ] **Step 2: Run them on the tablet and confirm they fail.** Run `./gradlew :app:connectedDebugAndroidTest`. Expected: compilation failure.

- [ ] **Step 3: Implement `PdfFile` (the members above) and `TestPdfs`.** Run all MuPDF calls on whatever thread calls `PdfFile`: MuPDF objects aren't thread-safe, so one `PdfFile` is used from one thread. Write that down in the class comment. Plan 2 will give each open document its own worker thread.

- [ ] **Step 4: Run the tests and confirm they pass.** Same command. Expected: 5 tests pass.

- [ ] **Step 5: Commit.**

```bash
git add notes/app/src/main/java/com/fzizzle/epapernotes/pdf notes/app/src/androidTest
git commit -m "notes: open, render and rotate PDFs with MuPDF"
```

### Task 5: Write and read the app's strokes, keep everyone else's annotations

**Files:**
- Modify: `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/PdfFile.kt`
- Modify: `notes/app/src/androidTest/java/com/fzizzle/epapernotes/pdf/TestPdfs.kt`
- Test: `notes/app/src/androidTest/java/com/fzizzle/epapernotes/pdf/PdfFileStrokesTest.kt`

**Interfaces:**
- Consumes: `Stroke`, `StrokeCodec` (Task 2). `StrokeStyle`, `StrokeOutline`, `Box` (Task 3). `PdfFile`, `TestPdfs` (Task 4).
- Produces, on `PdfFile`:
  - `data class StoredStroke(val id: Int, val stroke: Stroke)`. `id` is the annotation's PDF object number, which stays stable across a save.
  - `fun strokes(page: Int): List<StoredStroke>`, the ink annotations on the page whose `/PNotesStroke` decodes.
  - `fun addStroke(page: Int, stroke: Stroke): Int`, which creates an ink annotation and returns its `id`.
  - `fun deleteStroke(page: Int, id: Int)`, which removes only the app's own stroke. Ignore an `id` that isn't one of the app's strokes.
  - `fun foreignAnnotationCount(page: Int): Int`, which counts every annotation on the page that isn't the app's.
  - `TestPdfs.withForeignAnnotations(dir: File): File`, a one-page PDF holding a highlight, a link and a free-text annotation, made with MuPDF's API.

`addStroke` does this, in order:
1. `createAnnotation(TYPE_INK)`.
2. Set the ink list from the points. Convert from PDF space to MuPDF's page space with `page.transform`, because the ink-list API works in page space.
3. Set `setColor(style.rgb)`, `setOpacity(style.opacity)` and `setBorderWidth(style.maxWidth)`. These are for viewers that ignore appearances and redraw ink from the ink list.
4. Put `/PNotesStroke` (from `StrokeCodec.encode`) into `getObject()`.
5. Call `update()`.
6. Replace the appearance with the pressure outline. Call `setAppearance` with `StrokeOutline.bounds` as the box, a resources dictionary holding one ExtGState `/GS0 << /ca opacity >>`, and the content `"/GS0 gs " + StrokeOutline.contentStream(stroke)`.

  Then check that a second `update()` or a save doesn't regenerate the appearance. If MuPDF regenerates it, the stroke would lose its pressure widths. The test `appearanceKeepsPressureAfterSave` catches this.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@RunWith(AndroidJUnit4::class)
class PdfFileStrokesTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private val line = Stroke(StrokeKind.PEN, (0..50).map { StrokePoint(100f + it * 4, 400f, it / 50f) })

    @Test fun strokeRoundTripsThroughSave() {
        val out = File(dir, "ink.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.addStroke(0, line); it.saveTo(out) }
        PdfFile.open(out).use { assertEquals(line, it.strokes(0).single().stroke) }
    }
    @Test fun strokeRendersWhereDrawn_offsetMediaBox() {
        PdfFile.open(TestPdfs.blank(dir, mediaBox = Box(100f, 100f, 712f, 892f))).use { pdf ->
            pdf.addStroke(0, Stroke(StrokeKind.PEN, listOf(StrokePoint(406f, 496f, 1f), StrokePoint(406f, 497f, 1f))))
            val bmp = pdf.render(0, 612)                      // 1 px per point
            assertTrue(isDark(bmp.getPixel(306, 396)))  // the page centre: x 406-100, y 892-496 (PDF y points up)
            pdf.setRotation(0, 90)
            val r = pdf.render(0, 792)
            assertTrue(isDark(r.getPixel(r.width / 2, r.height / 2)))  // the centre stays the centre after rotation
        }
    }
    @Test fun appearanceKeepsPressureAfterSave() {
        val out = File(dir, "ap.pdf")
        val wedge = Stroke(StrokeKind.PEN, listOf(StrokePoint(100f, 400f, 0f), StrokePoint(500f, 400f, 1f)))
        PdfFile.open(TestPdfs.blank(dir)).use { it.addStroke(0, wedge); it.saveTo(out) }
        PdfFile.open(out).use {
            val bmp = it.render(0, 2448)  // 4 px per point
            assertTrue(darkHeightAt(bmp, x = 480 * 4) > darkHeightAt(bmp, x = 120 * 4) * 2)  // thick end is thicker
        }
    }
    @Test fun highlighterIsTranslucent() {
        PdfFile.open(TestPdfs.blank(dir)).use {
            it.addStroke(0, Stroke(StrokeKind.HIGHLIGHTER, listOf(StrokePoint(100f, 400f, 1f), StrokePoint(300f, 400f, 1f))))
            val px = it.render(0, 612).getPixel(200, 392)
            assertTrue(Color.red(px) > 150 && Color.blue(px) < Color.red(px))  // yellow tint, the page still shows through
        }
    }
    @Test fun deleteRemovesOnlyThatStroke() = PdfFile.open(TestPdfs.blank(dir)).use {
        val a = it.addStroke(0, line); it.addStroke(0, line)
        it.deleteStroke(0, a)
        assertEquals(1, it.strokes(0).size)
    }
    @Test fun foreignAnnotationsSurviveSave() {
        val out = File(dir, "foreign.pdf")
        PdfFile.open(TestPdfs.withForeignAnnotations(dir)).use {
            assertEquals(3, it.foreignAnnotationCount(0)); assertTrue(it.strokes(0).isEmpty())
            it.addStroke(0, line); it.deleteStroke(0, it.strokes(0).single().id); it.saveTo(out)
        }
        PdfFile.open(out).use { assertEquals(3, it.foreignAnnotationCount(0)) }
        // also compare each foreign annotation's /Subtype and /Rect before and after (helper in TestPdfs)
    }
}
```

  `isDark(px)` means every RGB channel is below 100. `darkHeightAt(bmp, x)` counts the dark pixels in column `x`. Both live in `TestPdfs`.

- [ ] **Step 2: Run them on the tablet and confirm they fail.** Run `./gradlew :app:connectedDebugAndroidTest`. Expected: compilation failure.

- [ ] **Step 3: Implement the members above.** If `appearanceKeepsPressureAfterSave` fails because MuPDF regenerates the appearance, look for a MuPDF flag or call that keeps a custom appearance. If none exists, stop and report: storing pressure strokes would then need a different approach, and that's the owner's call.

- [ ] **Step 4: Run the tests and confirm they pass.** Same command. Expected: 11 instrumented tests pass.

- [ ] **Step 5: Commit.**

```bash
git add notes/app/src/main/java/com/fzizzle/epapernotes/pdf notes/app/src/androidTest
git commit -m "notes: write and read pressure strokes as PDF ink annotations"
```

### Task 6: Safe replace for saving

**Files:**
- Create: `notes/app/src/main/java/com/fzizzle/epapernotes/storage/SafeSave.kt`
- Test: `notes/app/src/test/java/com/fzizzle/epapernotes/storage/SafeSaveTest.kt`

**Interfaces:**
- Produces: `object SafeSave` with these members:
  - `fun tempFileFor(target: File): File`, which returns `<same folder>/.<name>.pnotes-tmp`. The leading dot hides it in most file managers, and plan 4's library also skips it by name.
  - `fun isTempFile(name: String): Boolean`, true for names starting with `.` and ending in `.pnotes-tmp`.
  - `fun replace(target: File, write: (File) -> Unit)`. It deletes any old temp file, calls `write(temp)`, flushes the temp file to disk (`FileOutputStream(temp, true).use { it.fd.sync() }`), then moves it over `target` with `Files.move(…, ATOMIC_MOVE, REPLACE_EXISTING)`. If `write`, the flush or the move throws, it deletes the temp file and rethrows. The caller has to tell the owner, so a failure is never silent.
  - `fun cleanStale(folder: File): Int`, which deletes leftover temp files in `folder` (not in subfolders) and returns how many it removed. It's called when a folder is opened. Saving runs on one worker thread, so no save can be in progress then (plan 2).

- [ ] **Step 1: Write the failing tests.**

```kotlin
class SafeSaveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun replacesContent() {
        val f = tmp.newFile("a.pdf").apply { writeText("old") }
        SafeSave.replace(f) { it.writeText("new") }
        assertEquals("new", f.readText()); assertFalse(SafeSave.tempFileFor(f).exists())
    }
    @Test fun failedWriteLeavesOriginalUntouched() {
        val f = tmp.newFile("a.pdf").apply { writeText("old") }
        assertThrows(IOException::class.java) { SafeSave.replace(f) { it.writeText("half"); throw IOException("disk full") } }
        assertEquals("old", f.readText()); assertFalse(SafeSave.tempFileFor(f).exists())
    }
    @Test fun tempNameIsHidden() = assertEquals(".a.pdf.pnotes-tmp", SafeSave.tempFileFor(File("/x/a.pdf")).name)
    @Test fun recognisesTempFiles() { assertTrue(SafeSave.isTempFile(".a.pdf.pnotes-tmp")); assertFalse(SafeSave.isTempFile("a.pdf")) }
    @Test fun cleansStaleTempsOnly() {
        tmp.newFile(".a.pdf.pnotes-tmp"); tmp.newFile("a.pdf")
        assertEquals(1, SafeSave.cleanStale(tmp.root)); assertTrue(File(tmp.root, "a.pdf").exists())
    }
    @Test fun worksWhenTargetDoesNotExistYet() {
        val f = File(tmp.root, "copy.pdf")   // plan 5 creates "(my edits …)" copies this way
        SafeSave.replace(f) { it.writeText("x") }; assertEquals("x", f.readText())
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail.** Run `./gradlew :app:testDebugUnitTest`. Expected: compilation failure.

- [ ] **Step 3: Implement `SafeSave.kt`** to the interface above.

- [ ] **Step 4: Run the tests and confirm they pass.** Same command. Expected: 19 unit tests pass.

- [ ] **Step 5: Add one instrumented test to `PdfFileStrokesTest`:** `safeSaveOverSourceFile`. Open a test PDF, add a stroke, call `SafeSave.replace(source) { pdf.saveTo(it) }`, close it, reopen the source, and expect 1 stroke. This proves MuPDF can keep a file open while it's replaced underneath. Run `./gradlew :app:connectedDebugAndroidTest`. Expected: 12 instrumented tests pass.

- [ ] **Step 6: Commit.**

```bash
git add notes/app/src/main/java/com/fzizzle/epapernotes/storage notes/app/src/test notes/app/src/androidTest
git commit -m "notes: safe replace so a save never leaves a half-written PDF"
```

### Task 7: Speed check on the tablet (hard stop)

**Files:**
- Create: `notes/app/src/androidTest/java/com/fzizzle/epapernotes/check/SpeedCheck.kt`
- Create: `docs/superpowers/speed-check-results.md`

**Interfaces:**
- Consumes: `PdfFile`, `SafeSave`, `Stroke` (Tasks 2–6).
- Produces: the results file. No code is used later.

`SpeedCheck` is an instrumented test class. It's skipped (`assumeTrue`) unless a PDF exists in `/sdcard/Android/data/com.fzizzle.epapernotes/files/check/`, so it never runs by accident. For each PDF there, it logs one line per measurement with tag `SpeedCheck`, and appends the same lines to `files/check/results.txt`. It never modifies the owner's file: it works on a copy in the cache folder.

What it measures:
- File size and page count.
- Time to open.
- Render time for each of the first 10 pages at 1860 px wide (full screen width). Report the first page (cold) separately from the median of the rest.
- Time to add 50 strokes of 200 points each to page 1.
- Time for `SafeSave.replace` with `saveTo`, on the copy.
- Time to reopen the saved copy and read its strokes back, which must be 50 strokes.

Proposed pass marks, to be confirmed with the owner: open under 1 s, page render (median) under 300 ms, save under 2 s.

- [ ] **Step 1: Implement `SpeedCheck`** as above, using `System.nanoTime()`.

- [ ] **Step 2: Ask the owner to copy in 1–2 real lecture PDFs, ideally the biggest ones they have.** On the laptop, run `adb shell mkdir -p /sdcard/Android/data/com.fzizzle.epapernotes/files/check`, then `adb push "<pdf>" /sdcard/Android/data/com.fzizzle.epapernotes/files/check/`.

- [ ] **Step 3: Run the check.** Run `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fzizzle.epapernotes.check.SpeedCheck`, then pull `files/check/results.txt` with `adb pull`. Expected: one block of numbers per PDF, and the stroke count back is 50.

- [ ] **Step 4: Write `docs/superpowers/speed-check-results.md`:** the device, the MuPDF version, a table per PDF with the numbers and the pass marks, and a plain-words verdict. Do the numbers support building the writing screen on MuPDF as designed? If not, what would change? For example, a slow save would need incremental saves, and that's a design question.

- [ ] **Step 5: Commit and stop.**

```bash
git add notes/app/src/androidTest/java/com/fzizzle/epapernotes/check docs/superpowers/speed-check-results.md
git commit -m "notes: MuPDF speed check on the tablet, with results"
```

Hand the results to the owner and stop. Plan 2 (the writing screen) is written only after the owner has seen the numbers and approved going ahead.
