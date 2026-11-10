# Speed plan: results

Run: 2026-11-10 on the Boox Note Air 6 C (Android 16), debug build, MuPDF 1.28.5. Plan: [review-speed](plans/2026-11-10-review-speed.md). Branch: `review-speed`.

**State: finished (2026-11-10).** Laptop tests (287) and tablet tests (77) pass, and the owner checked the screen-side changes with the pen: all fine, so every task is kept.

## Measured on the tablet

From `SpeedCheck` (`notes/app/src/androidTest/.../check/SpeedCheck.kt`). The full-page test adds 1,500 short strokes to one blank page, one at a time.

| | Before | After |
|---|---|---|
| Add a stroke, nearly empty page (median of strokes 1–50) | 4.0 ms | 2.7 ms |
| Add a stroke at 500 on the page | 14.8 ms | 3.0 ms |
| Add a stroke at 1,000 on the page | 29.0 ms | 4.1 ms |
| Add a stroke at 1,500 on the page | 43.5 ms | 5.5 ms |
| Add all 1,500 strokes | 35.9 s | 5.9 s |
| Read the page's 1,500 strokes, first time | 1,003 ms | 982 ms |
| Read them again (what each later erase or lasso does) | 1,003 ms | 0 ms |
| Save a page with 1,500 strokes (`saveTo`) | 160 ms | 155 ms |

On a copy of the owner's test file (14.1 MB, 125 pages), unchanged by this work and listed for reference: open 2 ms, fingerprint of the whole file 11 ms, pages 2–10 render in 80 ms (median), save with safe replace 190 ms.

## Measured on the laptop

| | Before | After |
|---|---|---|
| Journal size after one 200 KB snip is added and moved ten times | 5,600,927 bytes | 269,002 bytes |

## What was done, task by task

| Task | Result |
|---|---|
| 1. Measure | Done. Timing lines: `DocumentSession: save ms=… check=… write=… swap=… bytes=…`, `DocumentSession: apply ms=… changes=…` (over 5 ms), and `draw ms=… overlay=…` under the `Notes` tag (over 8 ms). |
| 2. Fewer file reads per save | **Dropped.** The idea was to trust the file's "last changed" stamp and skip reading the file. Its tablet check failed: the tablet's storage records that stamp only to the second, so a rewrite within the same second goes unseen. Both commits are reverted. Little is lost: fingerprinting a 14 MB file takes 11 ms, and the bug-fix plan had already cut the reads per save from two to one. |
| 3. Adding a stroke on a full page | Done and measured (table above). The app now remembers each page's stroke ids and decoded strokes instead of rereading them, and a stroke added on its own skips a MuPDF drawing step whose result was thrown away. Loading the page still grows a little with what is on it, which is why 1,500 strokes cost 5.5 ms and not 2.7 ms. |
| 4. A finished stroke doesn't redraw the page | Built, pen-checked. A new stroke no longer redraws the view while fast-ink is drawing; the view catches up once, 500 ms after the pen rests. After more than 150 hand-drawn strokes, the page picture is renewed quietly in the background. The three dialogs and leaving the screen now redraw as they pause fast-ink, so the last stroke can't be missing behind them. |
| 5. Page pictures freed at once | Built, pen-checked. Pictures are freed as they leave the cache, with a guard so a freed picture is never drawn. A neighbour page arriving no longer redraws the screen. Left out: delaying the rebuild of the page just left, because flipping forward and straight back would have shown a grey page. |
| 6. Dragging a selection | Built, pen-checked. The preview shapes are built once and shifted on each pen move; the lasso line is extended instead of rebuilt. The preview keeps its thin grey line at any zoom. |
| 7. A snip is journaled once | Done and measured (table above). Journals written by earlier builds still read. Covered by 16 new laptop tests, including a kill in the middle of a save. |
| 8. Small ones | Built, checked on the tablet. One page load per page at layout; after a rotation only the turned pages are read again; no Onyx calls during a pinch; the library's Continue row and tiles redraw only when they change; Refresh redraws the thumbnails on screen now and the rest as they scroll into view. |

## Finishing steps

1. ~~Run the tablet tests on a free tablet.~~ **Done 2026-11-10:** all 77 pass, among them `MuPdfStressTest` (so the earlier crash was the other install) and the three tests new in this branch (`aMovedSnipIsNotEncodedAgain`, `layoutIsSizeAndTransformTogether`, `refreshDrawsTheRestOnlyWhenTheyComeOnScreen`). The speed check repeated the table above: 1,500 strokes in 5.9 s, `saveTo` 156 ms, reread 0 ms, safe-replace save 185 ms.

2. **Check the screen-side changes with a pen** (Tasks 4, 5, 6, 8). **Done 2026-11-10 by the owner: all fine.** With `adb logcat -s Notes` running:
   - Write a few hundred strokes on one page. `draw ms=` lines appear only after the pen rests, never between strokes. Once past 150 strokes, a line `render requested … (quiet: …)` then `rendered … (quiet)` appears, with no flash and no `show: pause fast-ink` after it.
   - Write, then straight away: tap ⋯, tap the page number, turn the page and come back, pinch, choose lasso, erase, sleep and wake. Each time the last stroke is on screen.
   - Erase and undo many times, turn pages quickly, pinch in and out. No crash, and no log line containing `recycled bitmap` or `its picture was freed`.
   - Lasso about twenty strokes and a snip; drag and resize, at normal size and zoomed in. The grey preview is as thin as before and lands where the ink lands.
   - Rotate one page, then all pages, then undo. Ink lands under the pen on turned and unturned pages.
   - Pinch, then write at once: the line is as wide as the saved ink, and the pen writes up to the page edge but not beyond.
   - In a big folder press Refresh: visible tiles redraw; the others redraw as they scroll into view, never showing the old picture.

3. **Decide what to keep.** **Kept all:** no task caused a visible change. The plan's rule was to revert anything that gains under 10%. Tasks 3 and 7 clear that easily. Tasks 4, 5, 6 and 8 are not measured: if any causes a visible change in step 2, revert that task's commit alone (each task is one commit).

4. **Not taken up:** saving only the changes during a session (the plan's decision 3). A save of the 14 MB file takes about 190 ms on the worker thread, so it does not look needed.

## Commits on the branch

Oldest first: timings (`7e1be4d`); the stamp, added then reverted (`c1a7b9c`, `9199b55`, `6120701`, `ab43be3`); stroke ids and items remembered (`ec13da1`); strokes skip MuPDF's drawing step (`aff6b6e`); speed check fixes (`51c14b7`); Task 4 (`69e5122`); Task 5 (`97f7d5f`); Task 6 (`1358690`); Task 8 (`780c358`); Task 7 (`4c12430`).
