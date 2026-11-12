# Writing Screen Design Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eight small things that make the writing screen quicker to move around in: a progress line, a Fit button, zoom that stays through page turns, a number pad for "Go to page", held arrows, Duplicate, tap-to-select, and a one-time offer to rotate a sideways deck.

**Architecture:** Each is its own task and its own commit, with its rule in a small Android-free file tested on the laptop and a thin hookup in the screen. Nothing changes the PDF or how it's saved. Every new thing on the strip changes only at moments when the panel refreshes anyway (a page turn, a zoom), so none adds a flash.

**Tech Stack:** As now. Nothing new.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md` §2.5 ("Minimal UI: one thin toolbar strip, nothing else permanently on screen"), §4 ("A crowded screen, or UI that grows features over time" is a failure), §6.2, §6.3, §6.4.

**Owner's words (2026-11-12):** "the writing screen design features all look good". The + on the last page is in the [pages plan](2026-11-12-pages.md).

Paths below are under `notes/app/src/`. `main/…` means `main/java/com/fzizzle/epapernotes/…`, `test/…` likewise.

## Decisions for the owner (approve before Task 1)

1. **Progress line.** The grey hairline under the strip becomes 4 px thick: black from the left up to the page you're on, grey after. A page you've written on gets a small black tick hanging 6 px under the line. It takes 8 px of the page's room.
2. **Fit button.** A new button at the right, before ⋯: grey at normal size, black when zoomed in. It always sits there, so nothing moves (as Paste does).
3. **Zoom stays through a page turn**, at the same place on the page. Pushing past the bottom of a zoomed page lands at the top of the next, and past the top lands at the bottom of the one before. A tick under ⋯, "Keep zoom when turning", on unless turned off. Today a turn always goes back to the whole page.
4. **Number pad.** "Go to page" becomes a box of big buttons (0–9, ⌫, Go) in place of the system keyboard.
5. **Holding ‹ goes to the first page, holding › to the last.**
6. **Duplicate** sits between Copy and Delete on the selection's bar. The copy lands a little down and right of the original, selected, ready to drag.
7. **A tap with the lasso selects the stroke or snip under it**, the top one where they overlap. A tap on nothing does nothing, as an empty loop does today.
8. **"Rotate all pages?"** is offered once, the first time a file is opened, when most of its pages are wider than tall and the tablet is held upright.

## Global Constraints

- "Minimal UI: one thin toolbar strip, nothing else permanently on screen" (spec §2.5). One row; the tools on the island never move.
- A strip redraw while fast-ink is on flashes the whole screen. Nothing here may redraw the strip while the pen can write, except at a page turn or after a finger gesture, which refresh anyway.
- The pen never moves the page; a finger never draws (spec §6.2). No task may pause fast-ink while the pen is down (`pen.isWriting`).
- No animations, libraries, fonts or image assets. Glyphs are drawn in `StripButton`, like the others.
- Readable code; `WritingActivity.kt` grows as little as possible: rules go in their own files.
- Toolchain: `source ~/.local/opt/android-env.sh`; `./gradlew :app:testDebugUnitTest`; tablet checks on the debug build, on the owner's test copy, release build back afterwards. Never `git add -A`.

## Review Focus

1. **A one-page document.** The progress line is all black, Fit and the pad work, and holding an arrow does nothing (Task 1: `onePageIsAllProgress`; Task 4: `aOnePageDocumentAcceptsOnlyOne`).
2. **Typing a page number past the end, or 0.** Go lands on the last page, or the first; the pad never shows more digits than the page count has (Task 4: `aNumberPastTheEndGoesToTheLastPage`).
3. **A zoomed turn onto a page of another size or way up.** The new page is never shown off its own edges (Task 3: `aTurnOntoASmallerPageStaysOnThePage`).
4. **A tap with the lasso where a highlight lies under pen ink.** The pen stroke is selected, not both (Task 7: `theTopItemUnderATapIsPicked`).
5. **Duplicate at the page's bottom-right corner.** The copy stays on the page (Task 6: `aDuplicateStaysOnThePage`).

---

### Task 1: The progress line

**Files:**
- Create: `main/ui/ProgressMarks.kt`, `main/ui/ProgressRule.kt` (the view), `test/ui/ProgressMarksTest.kt`
- Modify: `main/doc/PdfPages.kt`, `main/pdf/PdfFile.kt`, `test/doc/Fakes.kt`, `main/ui/WritingActivity.kt`

**Interfaces:**
- Produces:
  - `PdfPages.hasOwnItems(page: Int): Boolean` (in `PdfFile`: `idsFor(page).isNotEmpty()`, which decodes nothing). The library plan uses it too; whichever plan runs first adds it.
  - `object ProgressMarks { fun reachedPx(page: Int, pageCount: Int, widthPx: Int): Int; fun tickPx(page: Int, pageCount: Int, widthPx: Int): Int }`: the line is black up to `reachedPx` (the right edge of the page's share of the width; the full width on the last page), and a page's tick sits at the middle of its share.
  - `class ProgressRule(context) : View` with `fun show(page: Int, pageCount: Int, written: Set<Int>)`; it redraws only when one of the three changed.

- [ ] **Step 1: Write the failing tests:** `theFirstOfTenReachesATenth` (`reachedPx(0, 10, 1000) == 100`), `theLastPageReachesTheEnd` (`reachedPx(9, 10, 1001) == 1001`), `onePageIsAllProgress`, `aTickSitsMidShare` (`tickPx(0, 10, 1000) == 50`), `ticksNeverLeaveTheLine` (0 ≤ tick < width for every page of 500 on a 1860 px line).
- [ ] **Step 2: Run; they fail. Step 3: Implement** `ProgressMarks` and `ProgressRule` (8 px tall: the 4 px line at the top, grey `RULE` then black; ticks 3 px wide in the 6 px below, overlapping the line by 2).
- [ ] **Step 4: Hook up.** `ProgressRule` takes the hairline's place in `onCreate`. After the first layout, one worker task reads `hasOwnItems` for every page and posts the set (log `written pages n=… ms=…`). A stroke adds its page (`write`); a `PageChanged` page is asked again (an erase may have emptied it). `updateIndicator` calls `show`. Under fast-ink the panel takes the new line at the next page turn or refresh; nothing is forced.
- [ ] **Step 5: Laptop tests pass. Tablet check:** the line moves with ‹ › and Go to page; ticks match the pages written on; writing on a fresh page gives it a tick by the next page turn; no flash is added (count `SDM update_to_display` lines over ten strokes, before and after).
- [ ] **Step 6: Commit.** `git commit -m "writing: the line under the strip shows where you are and which pages have writing"`

### Task 2: The Fit button

**Files:**
- Modify: `main/ui/Viewport.kt`, `main/ui/PageView.kt`, `main/ui/StripButton.kt` (`Glyph.FIT`: four corner marks pointing outwards), `main/ui/WritingActivity.kt`
- Test: `test/ui/ViewportTest.kt`

**Interfaces:**
- Produces: `Viewport.fit()` (zoom 1, centred, same page); `PageView.fitPage(): Boolean` (false if already at normal size; else drops the patch, fits, redraws, calls `onViewportChanged`).

- [ ] **Step 1: Failing test** `fitGoesBackToTheWholePageOnTheSamePage`: go to page 3, `zoomAround(3f, 100f, 100f)`, `fit()`; `zoom == 1f`, `page == 3`, `pageBoundsPx()` equals a fresh viewport's at page 3.
- [ ] **Step 2: Implement.** The button goes in the right row before ⋯ (`gaps = 1`), `isEnabled = false` at first. `updateIndicator` sets `isEnabled = zoom > 1.05f`. Its tap, refused while `pen.isWriting`: `if (pages.fitPage()) showWithoutFastInk()`.
- [ ] **Step 3: Tablet check:** pinch in: the button turns black with the zoom's own refresh; tap it: one refresh, the whole page, the pen writes at once and in the right place; at normal size it's grey and dead.
- [ ] **Step 4: Commit.** `git commit -m "writing: a Fit button goes back to the whole page"`

### Task 3: Zoom stays through a page turn

**Files:**
- Modify: `main/ui/Viewport.kt`, `main/ui/PageView.kt`, `main/ui/ReadingPrefs.kt`, `main/ui/WritingActivity.kt`
- Test: `test/ui/ViewportTest.kt`

**Interfaces:**
- Produces: `enum class Land { SAME_PLACE, TOP, BOTTOM }`; `Viewport.turn(forward: Boolean, keepZoom: Boolean, land: Land = Land.SAME_PLACE): Boolean` (with `keepZoom` false it is today's `next()`/`previous()`, which stay and call it); `PageView.keepZoom: Boolean`; `ReadingPrefs.keepZoom: Boolean` (default true).

- [ ] **Step 1: Failing tests:** `aZoomedTurnKeepsZoomAndPlace` (same-sized pages: `zoom` and `pageBoundsPx()` unchanged, `page` + 1); `aTurnOntoASmallerPageStaysOnThePage` (the next page half the height: after the turn `visiblePagePart()` lies within the page's size); `landingAtTheTop` (`Land.TOP`: `visiblePagePart().y0 == 0f`), `landingAtTheBottom`; `withoutKeepZoomATurnFitsThePage`; `atTheLastPageNothingChanges`.
- [ ] **Step 2: Implement `Viewport.turn`.**
- [ ] **Step 3: `PageView.turn(forward, land)`** uses it with `keepZoom`. The push-past-the-edge turn in `onTouchEvent` passes `Land.TOP` going forward and `Land.BOTTOM` going back; the strip's arrows and the volume keys pass `SAME_PLACE`. When the turn leaves the page zoomed, it calls `askForPatch()` so the part on screen is sharp, and `readyNeighbours` no longer returns early when zoomed while `keepZoom` is on (the neighbours' pictures are at their fitted width either way). `goTo` (Go to page, undo's jump) still fits the page.
- [ ] **Step 4: ⋯ menu:** "Keep zoom when turning", a tick in the screen-only group, kept in `ReadingPrefs`.
- [ ] **Step 5: Tablet check:** zoom to the top-right quarter, press › five times: each page shows the same quarter, sharp, with one refresh per turn; the pen writes in the right place straight after each; push past the bottom: the next page's top; with the tick off, a turn fits the page as before; Trim margins on and off.
- [ ] **Step 6: Spec §6.2** (navigation) says so. **Commit.** `git commit -m "writing: zoom stays through a page turn"`

### Task 4: A number pad for "Go to page"

**Files:**
- Create: `main/ui/PageEntry.kt`, `main/ui/PagePad.kt`, `test/ui/PageEntryTest.kt`
- Modify: `main/ui/WritingActivity.kt` (`askForPage`)

**Interfaces:**
- Produces: `class PageEntry(private val pageCount: Int) { val text: String; fun press(digit: Int); fun back(); fun page(): Int? }`. `text` is the digits typed, at most as many as `pageCount` has; a leading 0 is not taken; `page()` is the 0-based page, kept within the document, or null when nothing is typed. `fun showPagePad(activity: Activity, current: Int, pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit)`.

- [ ] **Step 1: Failing tests:** `digitsBuildANumber` (1, 2 → "12", `page() == 11`); `aNumberPastTheEndGoesToTheLastPage` (count 30: 4, 5 → `page() == 29`); `noMoreDigitsThanTheCountHas` (count 30: 1, 2, 3 → "12"); `aLeadingZeroIsNotTaken`; `backRemovesTheLastDigit`; `nothingTypedIsNoPage`; `aOnePageDocumentAcceptsOnlyOne`.
- [ ] **Step 2: Implement `PageEntry`,** then `PagePad`: an `AlertDialog` titled "Go to page", its view a line "‹typed or the current page in grey› of 125" above a 3 × 4 grid of plain `TextView` buttons (1–9, ⌫, 0, Go), each at least 64 dp tall, black on white with the menus' black outline, no ripple. No `EditText`, so no keyboard.
- [ ] **Step 3: `askForPage` uses it;** the pause and resume of fast-ink around the dialog stay exactly as they are.
- [ ] **Step 4: Tablet check:** tap the page number, 4, 5, Go; ⌫; Go with nothing typed closes without moving; a tap outside closes; no keyboard appears; the pen works after closing.
- [ ] **Step 5: Commit.** `git commit -m "writing: a number pad for Go to page, without the keyboard"`

### Task 5: Held arrows

**Files:**
- Modify: `main/ui/WritingActivity.kt`

- [ ] **Step 1:** `previousButton` and `nextButton` get a long-click: to page 0 and to the last page, through `pages.goTo(…)` and `showWithoutFastInk()`, refused while `pen.isWriting || screenPenDown`. The long-click returns true, so the lift doesn't also turn one page. Content descriptions say it ("Previous page. Hold for the first page.").
- [ ] **Step 2: Tablet check:** hold › on page 3 of 30: page 30, one refresh; hold ‹: page 1; a short tap still turns one page; a held greyed-out arrow does nothing.
- [ ] **Step 3: Commit.** `git commit -m "writing: holding an arrow goes to the first or last page"`

### Task 6: Duplicate

**Files:**
- Modify: `main/ui/SelectionLayer.kt` (`Listener`, `barRects`, `pressBar`, `drawBar`), `main/tools/Clipboard.kt`, `main/ui/WritingActivity.kt` (`selectionListener`)
- Test: `test/tools/ClipboardTest.kt`

**Interfaces:**
- Produces: `SelectionLayer.Listener.duplicate()`; `fun duplicateOf(items: List<Item>, pdfToDisplay: Affine, pageSize: SizePt, byPt: Float = 14f): List<Item>`: the items with fresh ids, moved `byPt` right and down as shown, or as far as keeps their box on the page (not at all in a direction with no room).

- [ ] **Step 1: Failing tests:** `aDuplicateHasFreshIdsAndIsMoved` (ids all differ from the originals'; each point is 14 pt right and down in display space); `aDuplicateOnATurnedPageMovesRightAndDownAsShown` (a page rotated 90°); `aDuplicateStaysOnThePage` (a box ending 5 pt from the right and bottom edges: moved 5 pt each way); `aSnipIsDuplicatedWhole`.
- [ ] **Step 2: Implement `duplicateOf`.** The bar becomes three boxes, "Copy", "Duplicate", "Delete", laid out as the two are now. `duplicate()` in `WritingActivity`: `recordFromScreen(AddItems(page, duplicateOf(…)))`, then `setSelection(page, done.items, notShownYet = true)`, as Paste does. The clipboard is left as it was.
- [ ] **Step 3: Tablet check:** lasso a word, Duplicate: a grey copy beside it, selected; drag it; Undo removes the copy; on a rotated page; a snip.
- [ ] **Step 4: Commit.** `git commit -m "writing: Duplicate on the selection's bar"`

### Task 7: A tap with the lasso selects what's under it

**Files:**
- Create: `main/tools/TapSelect.kt`, `test/tools/TapSelectTest.kt`
- Modify: `main/doc/DocumentSession.kt` (`select`)

**Interfaces:**
- Produces: `object TapSelect { const val TAP_SPAN_PT = 6f; fun isTap(loop: List<Pt>): Boolean; fun pick(items: List<Item>, loop: List<Pt>): List<Item> }`. `isTap`: the loop's box is under `TAP_SPAN_PT` both ways. `pick`: the last, in drawing order, of `Eraser.hits(items, listOf(centre, centre))`, as a list of one, or empty.

- [ ] **Step 1: Failing tests:** `aSmallScribbleIsATap`, `aLoopIsNotATap`, `aTapOnAStrokePicksIt`, `theTopItemUnderATapIsPicked` (a highlighter stroke under a pen stroke: the pen stroke), `aTapOnNothingPicksNothing`, `aTapInsideASnipPicksIt`. If `Eraser.hits` finds nothing for a trail of one repeated point, give it two points 0.1 pt apart and say so in a comment.
- [ ] **Step 2: Implement;** `DocumentSession.select` answers `TapSelect.pick` when `isTap(loop)`, else `Lasso.selects` as now.
- [ ] **Step 3: Tablet check,** with the strip's lasso and with button 1 held: tap a letter: its stroke is selected; tap empty paper: the grey dot is wiped and nothing else; a loop still selects everything inside.
- [ ] **Step 4: Spec §6.3 (lasso). Commit.** `git commit -m "tools: a tap with the lasso selects the stroke under it"`

### Task 8: "Rotate all pages?", once

**Files:**
- Create: `main/ui/RotateOffer.kt`, `test/ui/RotateOfferTest.kt`
- Modify: `main/ui/LastPageStore.kt`, `main/ui/WritingActivity.kt`

**Interfaces:**
- Produces: `LastPageStore.has(path: String): Boolean`; `object RotateOffer { fun shouldOffer(sizes: List<SizePt>, viewWidthPx: Int, viewHeightPx: Int, openedBefore: Boolean): Boolean }`: true when not opened before, the view is taller than wide, and more than half the pages are at least 1.2 times wider than tall.

- [ ] **Step 1: Failing tests:** `aSidewaysDeckOnAnUprightTabletIsOffered`, `notASecondTime`, `notWhenTheTabletIsHeldSideways`, `notForUprightPages`, `notForNearlySquarePages` (1.1 wide to 1 tall), `aMixedFileGoesByTheMajority`.
- [ ] **Step 2: Implement.** In `onCreate`, read `openedBefore = lastPages.has(path)` before anything stores a page. In the first layout's `then`, if `shouldOffer`: store the page now (so it's never asked again, whatever the answer), and show an `AlertDialog`, "These pages are sideways. Rotate all pages?", "Rotate all" (`rotate(all = true)`) and "Leave them", with the pen paused as for the other dialogs. It waits for the repair question if that is open (`dialogOpen`).
- [ ] **Step 3: Tablet check:** a fresh copy of a landscape deck: asked once; "Rotate all" turns every page and Undo turns them back; reopen: not asked; an upright workbook: never asked.
- [ ] **Step 4: Spec §6.4. Commit.** `git commit -m "writing: a sideways deck is offered a rotation the first time it's opened"`

### Task 9: Spec, checklist, owner session (hard stop)

- [ ] **Step 1: Spec §6.2:** the strip line reads `← library · file name · pen · highlighter · eraser · lasso select · snip · undo · redo · paste · fit · ⋯ page menu · ‹ page ›`; the progress line; the number pad; held arrows. §6.3: Duplicate. §11: the designer's calls (the tick's size, 14 pt for a duplicate, 6 pt for a tap, the 1.2 ratio).
- [ ] **Step 2:** `notes/TESTING-writing-design.md`: each task's tablet check in plain words, numbered W1–W8, and one question per decision ("is the line too heavy?", "is keep-zoom right as the default?").
- [ ] **Step 3:** TODO.md section; all laptop and tablet tests run once more; release build on the tablet. **Stop for the owner's session.**
