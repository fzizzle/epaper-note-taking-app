# Page Readability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make PDF pages easier to read on the tablet without changing the PDF: sharp text when zoomed in, an optional contrast boost, optional trimmed margins, and a cleaner panel update on page turns.

**Architecture:** Everything here changes only the picture drawn on screen. The PDF, the saved ink and snips are untouched, and other viewers see no difference. Settings live in the app's own preferences, never in the owner's folders. Each of the four parts stands alone behind its own task, so the owner can stop after any of them. The plan starts with a no-code trial on the tablet that may make the contrast part unnecessary.

**Tech Stack:** As in plan 4 (MuPDF `fitz:1.28.5`, Onyx `onyxsdk-pen:1.5.9`). Nothing new: no image libraries.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` §2.3 ("the original page content is never modified"), §2.5 (minimal UI), §4 (a crowded screen is a failure), §6.2 (zoom and navigation). Findings and reasoning: `docs/superpowers/review-2026-11-10.md` section 6.

**Where this plan sits:** the fourth of four plans from the review. Run the [bug fixes plan](2026-11-10-review-bug-fixes.md) first: Task 2 there fixes the pen's mapping after a one-finger move, which this plan's Task 2 relies on.

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, `test/…` and `androidTest/…` likewise.

## Decisions for the owner (approve before Task 1)

**Approved, all five (owner, 2026-11-10).** Decision 4 first, during Task 4; decisions 1–3 and 5 afterwards. Decision 5: the page menu (⋯).

These are new features, so each needs a yes or no. Any can be left out.

1. **Sharp zoom.** After a zoom or a move, the part of the page on screen is drawn again at its true size. It arrives a moment after the fingers lift, with the clean refresh that already follows a zoom. No setting, always on.
2. **Contrast.** One choice with three steps, **Normal / Stronger / Strongest**, under ⋯ in the writing screen ("Contrast"), remembered for all documents. It darkens text and thin lines; the highlighter and ink are drawn through the same step, so highlights get slightly deeper too. Snips are cut from the untouched page, so a pasted picture never has it applied twice.
3. **Trim margins.** One switch under ⋯ ("Trim margins"), remembered for all documents. Each page is fitted to its printed area plus a small border, instead of to the whole sheet. Ink in the margin still counts as content, so nothing written is ever cut off.
4. **Cleaner page turns.** A page turn asks the panel for a cleaner update than the default, without the full flash. Which update mode looks best is the owner's call, on the tablet, from two or three candidates (Task 4). This also settles the open to-do "gentler refresh after zooming".
5. **Where the two settings sit.** The page menu (⋯) grows from two entries to four. The alternative is the library's Settings window, which keeps ⋯ short but means leaving the document to change them.

## Global Constraints

- "Handwriting is stored as a separate layer in the PDF file itself … the original page content is never modified." (spec §2.3). Nothing in this plan writes to the PDF.
- "Notes held anywhere other than the owner's own files" is a failure (spec §4): only the two settings are stored, in app-private preferences.
- "Minimal UI: one thin toolbar strip, nothing else permanently on screen." (spec §2.5). No new buttons in the strip.
- Snips must show the page's true colours: `PdfFile.renderRegion` as used for snips is never given a contrast step.
- All MuPDF work stays on the document's worker thread. Page pictures are touched on the UI thread only. The pen's mapping (`publishPenMapping`) must stay exact through every change to how the page is fitted.
- Lightweight: no per-frame filtering (a contrast step runs once per rendering, on the worker), no animation, no libraries (owner, 2026-11-09).
- The thumbnails in the library are not affected by either setting.
- Toolchain and tablet rules as in the bug fixes plan.

## Review Focus

1. **Zoomed to 4× on small text, then writing.** Text is sharp, and ink lands exactly under the pen (Task 2 tablet check; `ViewportTest.visiblePagePartMatchesTheScreen`).
2. **Trim on, then a stroke written in the margin.** The page refits to include it at the next visit; the stroke is never off screen (Task 5: `inkInTheMarginWidensTheTrim`).
3. **Trim on, a blank page, or a page that is one full-bleed picture.** The page is shown whole, as with trim off (Task 5: `aBlankPageIsNotTrimmed`, `aFullBleedPageIsNotTrimmed`).
4. **Contrast at Strongest on a slide with a pale yellow highlight over black text.** The text stays readable through the highlight; pure white stays white and pure black stays black (Task 3: `whiteStaysWhiteAndBlackStaysBlack`, `aPaleHighlightStaysLighterThanTheText`).
5. **Turning the tablet, or rotating a page, with trim on and zoomed in.** The page refits with no leftover offset, and strokes stay in place (Task 5 tablet check).

---

### Task 1: Try the tablet's own settings first (no code)

**Files:**
- Create: `docs/superpowers/readability-trial.md`

- [x] **Step 1: Look at what the tablet applies to the app now:** `adb shell dumpsys oec_service | grep -A30 com.fzizzle.epapernotes` (the app had no entry on 2026-11-10, so it used the system defaults). Record it.
- [x] **Step 2: Ask the owner to try,** on one slide deck they find hard to read: long-press the app's icon → Optimize → Display, and in turn "dark colour enhancement", "text bolding" and "background bleaching". For each: is the page easier to read, does the pen still feel the same, does the highlighter still look yellow, do snips look right when pasted.
- [x] **Step 3: Write up the trial** and the owner's verdict. If one of the tablet's settings does the job with no side effects, drop Task 3 (contrast) from this plan and say so in `TODO.md`.
- [x] **Step 4: Commit.** `git commit -m "docs: what the tablet's own display settings do for the app"`

### Task 2: Sharp text when zoomed in (decision 1)

**Files:**
- Modify: `main/ui/Viewport.kt`, `main/ui/PageView.kt` (`onDraw`, `renderWidth`, `ensureRendered`, `pageRendered`, the gesture end), `main/ui/WritingActivity.kt:256-266` (`pages.requestRender`)
- Test: `test/ui/ViewportTest.kt`

**Interfaces:**
- Consumes: `PdfFile.renderRegion(page: Int, displayBox: Box, pxPerPt: Float): Bitmap` (exists, used for snips).
- Produces:
  - `fun Viewport.visiblePagePart(): Box` — the part of the page on screen, in page-display points, clipped to the page.
  - In `PageView`: `var requestRegionRender: (page: Int, part: Box, pxPerPt: Float, token: Int) -> Unit` and `fun regionRendered(page: Int, part: Box, token: Int, version: Int, bitmap: Bitmap)`. One "sharp patch" is kept at a time: `private class Patch(val page: Int, val part: Box, val bitmap: Bitmap, val version: Int)`. A request made when the fingers lift carries a rising `token`; an answer with an old token, or for a page no longer on show, is recycled and dropped.

- [x] **Step 1: Write the failing tests** in `ViewportTest`: `visiblePagePartMatchesTheScreen` (at zoom 2 around the centre of a 600×800 pt page in a 1200×1600 view: the part is the middle 300×400 pt); `visiblePagePartIsClippedToThePage` (a short page at zoom 1.5); `atNormalSizeThePartIsTheWholePage`.
- [x] **Step 2: Run and confirm they fail.**
- [x] **Step 3: Implement.**
  - Asking: when a finger gesture ends with `zoom > 1.05`, and again whenever the page's ink changes while zoomed (`pageChanged`), ask for `visiblePagePart()` at `pxPerPt()`. The size is at most the view's size in pixels, so it needs less memory than today's 1.5× whole page.
  - Drawing: `onDraw` draws the whole-page picture as today (it is what shows while fingers move), then, when the patch is for this page, this exact part and not stale (`InkOverlay.isStale`), draws the patch over it at its place with **no** bitmap filtering. Overlay strokes newer than the patch's version are drawn on top, as for the page.
  - Dropping: any zoom, move, page turn or rotation drops the patch at once and recycles it; the whole-page picture is on screen until the next one arrives.
  - `renderWidth`: zoomed in, use the fitted width (1×) instead of 1.5×; the patch now carries the sharpness. This is what pays for the patch.
  - Showing: a patch arriving calls `onPageShown`, so it reaches the panel through the existing pause. After a zoom, hold the clean refresh until the patch has arrived or 300 ms have passed, so a zoom ends in one panel update and not two (review 4.6).
- [x] **Step 4: Run laptop tests** — pass.
- [x] **Step 5: Check on the tablet.** (Owner, 2026-11-10: "works well"; tablet tests 85 pass.) On a text-heavy page: zoom to about 3×; small text is crisp within a moment of lifting the fingers. Write a word: it lands under the pen. Erase it: the patch redraws without it. Move the page with one finger, turn the page, zoom back out: no stale patch is ever shown in the wrong place. Logcat shows one region render per gesture, and its time (`Log.i` it) is under 300 ms on a slide.
- [x] **Step 6: Commit.** `git commit -m "notes: zoomed-in text is drawn at its true size"`

### Task 3: Contrast (decision 2; skip if Task 1 made it unnecessary)

**Files:**
- Create: `main/ui/PageLook.kt`, `main/ui/ReadingPrefs.kt`
- Modify: `main/ui/WritingActivity.kt` (`pages.requestRender`, the region render from Task 2, `openPageMenu`), `main/ui/PageView.kt` (a way to drop every rendering)
- Test: `test/ui/PageLookTest.kt`, `androidTest/ui/PageLookDeviceTest.kt`

**Interfaces:**
- Produces:
  - `enum class Contrast(val gamma: Float) { NORMAL(1f), STRONGER(1.5f), STRONGEST(2.2f) }`.
  - `object PageLook { fun table(contrast: Contrast): IntArray; fun apply(pixels: IntArray, table: IntArray) }` — plain Kotlin. `table` has 256 entries: `out = round(255 × (in / 255) ^ gamma)`, applied to red, green and blue alike so colours keep their hue; alpha is left alone.
  - `fun PageLook.applyTo(bitmap: Bitmap, contrast: Contrast)` in the same file's Android half: reads the pixels a band of rows at a time (`getPixels`/`setPixels`, 64 rows per band) so no second full-size array is held. A no-op for `NORMAL`.
  - `class ReadingPrefs(context: Context) { var contrast: Contrast; var trimMargins: Boolean }` over `getSharedPreferences("reading", MODE_PRIVATE)`.
  - `fun PageView.dropAllRenderings()`: forgets every page picture and the sharp patch, and asks for the page on show again.

- [x] **Step 1: Write the failing tests** in `PageLookTest`: `whiteStaysWhiteAndBlackStaysBlack` (for every step: entry 0 is 0, entry 255 is 255); `normalChangesNothing`; `strongerDarkensMidGrey` (128 maps below 128, and Strongest below Stronger); `tablesNeverGoBackwards` (each table is non-decreasing); `aPaleHighlightStaysLighterThanTheText` (highlighter yellow over white, as the page renders it, maps to a value at least 100 levels above black); `alphaIsLeftAlone`.
- [x] **Step 2: Run and confirm they fail.**
- [x] **Step 3: Implement** `PageLook` and `ReadingPrefs`. In `WritingActivity`, both render paths call `PageLook.applyTo(bitmap, contrast)` on the worker, right after MuPDF returns and before the picture is posted to the UI thread; `contrast` is read from a `@Volatile` field set from the preferences. The snip path is left exactly as it is.
- [x] **Step 4: The menu.** In `openPageMenu`, add "Contrast: <current>" which opens a three-entry submenu; choosing one stores it, sets the field, and calls `pages.dropAllRenderings()`. (If decision 5 chose the library's Settings instead, add the row there and read the preference in `onResume`.)
- [x] **Step 5: Tablet test** (2026-11-10: 22 ms at Strongest; tablet suite 86 pass) `PageLookDeviceTest.aFullPageTakesUnderAFrameOrTwo`: `applyTo` on a 1860×2480 picture at `STRONGEST` in under 80 ms. If it's slower, skip pixels that are pure white (most of a slide) before the table lookup, and measure again. A colour filter on the paint is not an option: it can't do a gamma curve, and it would run on every redraw.
- [x] **Step 6: Check on the tablet** (owner, 2026-11-10: "that works") with the owner's hard-to-read deck: each step is visibly darker; the highlighter is still clearly yellow; a snip taken at Strongest and pasted looks like the page beside it, not darker.
- [x] **Step 7: Commit.** `git commit -m "notes: a contrast setting for the page on screen"`

### Task 4: Cleaner page turns, and a gentler refresh after zooming (decision 4)

**Files:**
- Modify: `main/ui/PenInput.kt:84-93` (`cleanRefresh`), `main/ui/WritingActivity.kt` (`turn`, `pagesChanged`, `onFingerGestureEnd`, the constants)

**Interfaces:**
- Produces: `fun PenInput.refresh(view: View, mode: UpdateMode)` replacing `cleanRefresh`, and two constants in `WritingActivity`: `PAGE_TURN_MODE` and `AFTER_ZOOM_MODE`.

- [x] **Step 1: List what the SDK offers.** (2026-11-10) `onyxsdk-device` 1.3.7: None, DU, DU4, GU, GU_FAST, GC, GCC, DEEP_GC, ANIMATION, ANIMATION_QUALITY, ANIMATION_MONO, ANIMATION_X, GC4, REGAL, REGAL_D, REGAL_PLUS, DU_QUALITY, HAND_WRITING_REPAINT_MODE. Which the Note Air 6 C honours is for step 3. The app logs them, and its default update, when a document opens. Original step: Print the names of `UpdateMode.values()` from the Onyx SDK version in the build into logcat once at start-up (debug build only), and note which the Note Air 6 C honours: at least `GC` (the full flash, used today), a regal or "REGAL" mode (clean text, little flash), `GU`, and `DU`.
- [x] **Step 2: A trial switch, for this task only** (2026-11-10). Built as system properties instead of an `am start` extra (the writing screen can't be started from adb): `adb shell setprop debug.epapernotes.show <NAME>` (a page turn, or the page redrawn after an erase, a move or an undo) and `debug.epapernotes.zoom <NAME>`, read at each use in a debug build, so a mode can be changed between two page turns. An empty value is today's behaviour.
- [x] **Step 3: Compare on the tablet, with the owner** (2026-11-10). The page view's default turned out to be GC (system default GU; the owner's Boox refresh setting for the app is Smooth). Page turns: GC flashed hard; GU still flashed; REGAL "looks pretty good", including on erase redraws. After a zoom: the owner saw no flash with GC (Smooth seems to soften it); REGAL "looks ok", no ghosts. One pause and one resume of fast-ink per turn, also when flicking quickly. Original step: For page turns: the default (today), the regal mode, `GU`. For after a zoom: `GC` (today), the regal mode. For each, on a text slide then a dark-background slide: is a ghost of the last page visible, how much does it flash, how long until the pen writes. Also check `adb logcat -s SDM` for how many panel updates each page turn causes; one is the aim.
- [x] **Step 4: Fix the choice in the two constants** (REGAL and REGAL, in `PenInput`; `CLEAN_REFRESH_MS` kept at 450 ms, the owner found the pen's return quick). Original step: remove the trial switch, and set the delay before fast-ink resumes (`CLEAN_REFRESH_MS`, 450 ms today) to what the chosen after-zoom mode needs.
- [x] **Step 5: Tick the to-do** "Gentler refresh after zooming" in `TODO.md`, with the mode chosen.
- [ ] **Step 6: Commit.** `git commit -m "notes: page turns and zooms use the panel update the owner chose"`

### Task 5: Trim margins (decision 3)

**Files:**
- Create: `main/ui/ContentBounds.kt`
- Modify: `main/ui/Viewport.kt`, `main/ui/PageView.kt` (`setPages`, `frame`, `onDraw`, `renderWidth`), `main/ui/WritingActivity.kt` (`layOutPages`, `publishPenMapping`, `openPageMenu`)
- Test: `test/ui/ContentBoundsTest.kt`, `test/ui/ViewportTest.kt`

**Interfaces:**
- Consumes: `ReadingPrefs.trimMargins` (Task 3; if Task 3 was skipped, create `ReadingPrefs` here with only this field).
- Produces:
  - `object ContentBounds { fun of(pixels: IntArray, width: Int, height: Int): Box? }` — plain Kotlin. Returns the smallest box holding every pixel darker than `WHITE_ENOUGH = 245` in any channel, in pixels; null when there are none. Ignores specks: a row or column counts only with at least 2 such pixels.
  - `fun ContentBounds.trim(pageSize: SizePt, content: Box?, pxPerPt: Float): Box` — the box to fit, in page-display points: the content grown by `BORDER_PT = 12` on each side and clipped to the page; the whole page when `content` is null or when the result would be more than 90% of the page's area (a full-bleed page gains nothing).
  - `class Viewport(sizes: List<SizePt>, viewWidthPx: Int, viewHeightPx: Int, private val fit: List<Box>? = null)` — when `fit[page]` is given, "fitted" means that box fills the view, zoom 1 shows that box, and moving and zoom limits apply to it. `pageToScreen()` still maps whole-page display points, so everything built on it (the pen's mapping, the selection layer, `visiblePagePart`) keeps working unchanged. The picture of the whole page is still what's drawn; the view clips it.
  - `fun PageView.setPages(pageSizes: List<SizePt>, pdfToDisplay: List<Affine>, startPage: Int, fit: List<Box>? = null)`.

- [x] **Step 1: Write the failing tests.**
  - `ContentBoundsTest`: `aBlankPageHasNoContent`; `textInTheMiddleIsFound`; `aSingleSpeckIsIgnored`; `aBlankPageIsNotTrimmed`; `aFullBleedPageIsNotTrimmed`; `theBorderNeverLeavesThePage`; `inkInTheMarginWidensTheTrim` (content found on a picture that includes a stroke near the edge reaches that stroke).
  - `ViewportTest`: `aTrimmedPageFillsTheViewWithItsContent` (fit box 100..500 × 100..700 on a 600×800 pt page in a 1200×1800 view: at zoom 1, `screenToPage(0, 0)` is near (100, 100) and `pxPerPt()` is 3); `aTrimmedPageCantBeMovedOutsideItsBox`; `withoutAFitBoxNothingChanges` (every existing test still passes untouched).
- [x] **Step 2: Run and confirm they fail.**
- [x] **Step 3: Implement.**
  - Finding the content: on the worker, per page and on demand (the page on show and its two neighbours, not the whole document at open): render at 200 px wide **with ink** (`PdfFile.render(page, 200)`), run `ContentBounds.of`, and keep the box in a map for the session. A page whose ink changes is measured again when it's next arrived at, not while it's on show, so the page never jumps under the pen.
  - Layout: `layOutPages` passes the boxes it has (the whole page for those not measured yet); when a box arrives for the page on show before the owner has zoomed or written on it, lay out again once.
  - `renderWidth` stays the whole page's width at the fitted scale, which with a trim is larger than the view; cap it at 1.5× the view's width, and let Task 2's sharp patch cover the rest when zoomed.
  - The menu: "Trim margins" with a tick, in `openPageMenu` (or the library's Settings, by decision 5). Changing it lays the pages out again and calls `publishPenMapping()`.
- [x] **Step 4: Run all laptop tests** — pass.
- [ ] **Step 5: Check on the tablet,** on a deck with wide margins and on a scanned paper: text is visibly larger with trim on; write right at the trimmed edge, turn the page and come back — the stroke is inside the view; turn the tablet and rotate a page with trim on — the page refits and strokes stay put; lasso, move, resize and paste all land where the pen is; with trim off everything is as before.
- [x] **Step 6: Commit.** `git commit -m "notes: pages can be fitted to their printed area"`

### Task 6: Owner session (hard stop)

**Files:**
- Create: `notes/TESTING-readability.md`
- Modify: `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` (§6.2: one paragraph on sharp zoom, the two settings and where they sit; §11 is not the place, these are the owner's decisions)

- [ ] **Step 1: Write the checklist,** in the style of `notes/TESTING-library.md`: sharp zoom on small text; each contrast step on a pale deck; a snip pasted at Strongest; trim on a wide-margin deck, with writing at the edge; page turns on text and on dark slides with the chosen update mode; the same PDF opened on a laptop afterwards, unchanged apart from the owner's ink.
- [ ] **Step 2: Update the spec** with what was built and which decisions were left out.
- [ ] **Step 3: Commit, then stop.** The owner runs the checklist; results go in `docs/superpowers/readability-results.md`.

## Not in this plan

- Inverting dark slides, removing coloured backgrounds, or reflowing text: larger features that change what the page looks like rather than how clearly it's drawn. Worth raising only if contrast and trim aren't enough.
- A per-document contrast or trim. One setting for all documents keeps the menu short; add per-document only if the owner finds themselves switching often.
