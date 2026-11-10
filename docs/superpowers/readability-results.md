# Easier-to-read pages: results

The owner session (Task 6) of the [readability plan](plans/2026-11-10-review-readability.md), on the build of 8f6571e, with the [checklist](../../notes/TESTING-readability.md). Branch `review-readability`, merged 2026-11-10.

## Verdict

Owner, 2026-11-10: "it looks good". No problems reported, and nothing to change before merging. The checks weren't reported one by one.

Checked earlier, task by task:

- sharp zoom: "works well";
- contrast: "that works";
- trim margins: "works fine";
- REGAL for page turns and after a zoom: the owner's choice from a side-by-side comparison.

## What was built

| Task | What | Where |
|---|---|---|
| 1 | The tablet's own display settings tried first: they do very little | [trial](readability-trial.md) |
| 2 | Sharp zoom: the part on screen drawn again at its true size | `PageView` patch, `PdfFile.renderRegion` |
| 3 | Contrast: Normal / Stronger / Strongest under ⋯ | `PageLook`, `ReadingPrefs` |
| 4 | REGAL for a page shown and after a zoom | main, PR #15 |
| 5 | Trim margins: a tick under ⋯ | `ContentBounds`, `Viewport` fit |
| 7 | The page drawn on whole pixels | `Viewport` |
| 8 | Dark text as black: a tick under ⋯, on unless turned off (owner) | `PageLook` |

Timings on the tablet: contrast plus dark text on a full 1860×2480 page takes 20 ms at Strongest; a sharp patch takes under 300 ms on a slide.

## Design changes recorded

Spec §6.2 has a paragraph on the reading settings. One change from the plan: dark text as black is on by default (the owner's choice), where the plan had it off.

## Left open

- Under Strongest, a highlight just drawn shows a little paler until the page picture is redrawn with it.
- Not measured: whether fingers-up after a zoom makes one panel update or two. It needs a real pinch with `adb logcat -s SDM` running.
- The minor review findings left as they are, with reasons, in [the handover](readability-handover.md).
