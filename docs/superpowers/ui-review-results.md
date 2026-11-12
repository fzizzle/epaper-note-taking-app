# UI review fixes: results

The owner session (Task 7) of the [UI review plan](plans/2026-11-12-ui-review.md), on the release build of b584251, with the [checklist](../../notes/TESTING-ui-review.md). Branch `ui-review`, PR #23.

## Verdict

Owner, 2026-11-12: all checks pass (U1–U9). No problems reported, and nothing to change before merging.

## What was built

| Task | What | Where |
|---|---|---|
| 1 | PDF tiles leave out the part of the name the folder's PDFs share: "answer key", "workbook" | `library/TileNames`, `LibraryTiles` |
| 2 | Folder tiles are a small folder mark; the count sits clear of the name | `LibraryTiles` |
| 3 | The open file's name in the strip, beside ←, cut from the front | `CentredStrip` (`StripPlacement`), `WritingActivity` |
| 4 | Paste always on the island, grey until something is copied | `WritingActivity.updateStrip` |
| 5 | Menus outlined in black, teal gone, ⋯ split in two, the long-pressed tile marked, Go to page padded | `res/values/themes.xml`, `menu_frame.xml`, `LibraryChanges`, `WritingActivity` |
| 6 | The copy message shortened after three, the Paste bubble like the strip, the pen mark inside its circle | `CopyMessage`, `ReadingPrefs`, `StripButton` |
| Review fix | Paste's change after a copy or snip is put on the panel even once fast-ink is back | `WritingActivity` |

Laptop tests: 355 pass.

## Design changes recorded

In the spec: §6.2's strip now holds the file name and Paste; §6.3 and §11 say Paste sits on the island, greyed out until something is copied.

## Still open

- The eraser's missing trail while dragging with button 2, and the flash after each erase: their own plan.
- Deferred from the branch review: the name set after "save to a copy" isn't put on the panel straight away; a folder refresh while a long-press menu is open could move the grey mark to another tile.
