# Efficiency plan: results

Plan: [efficiency](plans/2026-11-12-efficiency.md). Branch: `efficiency`. Tablet: Boox Note Air 6 C, Android 16, MuPDF 1.28.5.

Numbers driven over adb are from the **debug** build, before and after alike: the adb shortcuts that open a file or set the library folder only exist there. Pen strokes can't be scripted (the tablet blocks injected pen events), so the pen-side numbers come from the owner's own session log of 2026-11-12 (release build of main) and from the owner's check at the end.

## Verdict

Owner, 2026-11-13: every check in the [owner checklist](../../notes/TESTING-efficiency.md) passes, including the run of erases ending in one refresh (E1) and the ten-second autosave (E2). Both decisions stand as built.

## Before (2026-11-12, main at 6c82ee9)

| What | How | Before |
|---|---|---|
| Memory at launch, in the library | `dumpsys meminfo`, after a fresh start | 124 MB total, 10 MB native |
| Memory in a document after ten page turns | the 125-page test copy | 285 MB total, 147 MB native |
| Memory back in the library, six seconds after leaving | | 274 MB total, 145 MB native |
| Page pictures made for ten page turns, Trim margins on | a workbook copy; `render requested` lines | 23 (the first page, then two per page: width 1675, then 1729) |
| The same on a deck whose pages fill the sheet | the test copy | 13 (one per page) |
| Panel refreshes for five erases in six seconds | owner's log; `show: pause fast-ink` lines | 5 |
| Saves in three minutes of writing and erasing | owner's log; `saved` lines | 6, each 3 s after a pause |
| Opening the test copy (14 MB), files already read once | `open ms=` | 133 ms: identity 57, journal 19, MuPDF 56 |
| The old-ink check after opening, 125 pages | `ink check ms=` | 18 ms |
| A page with 300 pen strokes spread over it, width 1742 | `SpeedCheck.renderingAnInkedPage`, median of 5 | 53 ms |
| The same at width 2790 (the widest picture) | | 115 ms |
| A folder of 36 PDFs after a fresh start, thumbnails cached | frames drawn (`dumpsys gfxinfo`) | 12 |
| Listing the library folder (7 folders, SD card) | `library listed … ms=` | 47–53 ms |
| Listing a course folder (9 folders, SD card) | | 24–38 ms |
| Listing a folder of 36 PDFs (tablet storage) | | 21–49 ms |

Notes:

- Opening wasn't timed after a reboot: restarting the owner's tablet wasn't worth it for one number. With the file already read once, the whole-file read is 57 ms of 133.
- The old-ink check costs 18 ms for 125 pages. It needs no plan (decision 4's limit was 100 ms).
- The panel's own log (`SDM update_to_display`) shows nothing for the library screen, so a folder's fill is counted in frames drawn.

## After, task by task

| Task | What | Before | After | Kept? |
|---|---|---|---|---|
| 2. A neighbour page drawn once with Trim margins | page pictures made for ten page turns on a workbook | 23 | 13: one per page, each at its final width, still made ahead of the turn | yes |
| 3. Page pictures freed on leaving | memory back in the library, six seconds after ten page turns | 274 MB total, 145 MB native | 200 MB total, 65 MB native. The 55 MB of native memory left over launch is under the plan's 100 MB limit, so MuPDF's own store was left alone | yes |
| 4. A run of erases ends in one refresh | refreshes for five erases in six seconds | 5 | not measured yet: it needs the pen. In the owner checklist | owner decides |
| 5. Autosave ten seconds after the pen stops | saves in the owner's three minutes of writing and erasing | 6 | by the same log's pauses it would be 3 (the pauses longer than ten seconds); on the tablet in the owner checklist | owner decides |
| 6. Solid ink a band at a time | a page with 300 pen strokes, width 1742 / 2790 | 53 ms / 116 ms, through three page-sized buffers (about 60 MB / 130 MB at once) | 51 ms / 109 ms, through a 256-row band (about 6 MB / 9 MB). Every pixel the same: the page's checksum is unchanged | yes: no slower, and the memory is the gain |
| 7. Thumbnails read at normal priority and shown in groups | a folder of 36 PDFs after a fresh start: passes over the tiles to put pictures on them (`thumbnails shown` lines) | 5–6 passes, the last about 190 ms after the folder shows (measured with the grouping set to 0 ms, the priority change already in) | 1 pass, about 200 ms after the folder shows | yes. The priority change wasn't timed apart from the grouping |
| 8. Thumbnails survive a start with the card out | laptop test `pruneKeepsThumbnailsWhoseStorageIsOut` | every thumbnail of the card's PDFs deleted by the daily tidy-up | kept | yes |
| 9. Small ones: thumbnails cached without a see-through channel | tablet test `aCachedThumbnailIsReadAt16Bit` | a thumbnail read from the cache was 32-bit (the test failed before the fix) | 16-bit: half the memory for each tile's picture. Thumbnails cached earlier stay 32-bit until their file changes or Refresh | yes |
| 9. Small ones: a subfolder read once, the stale-file check by name first | listing the library folder (7 folders) / a course folder (9 folders), SD card | 47–53 ms / 24–38 ms | 38–49 ms / 18–25 ms | yes |
| 9. Small ones: scroll callbacks that change nothing are skipped | not measured: it only saves work on the screen's thread during a scroll | | | yes |

## Tests

2026-11-12: 367 laptop tests pass (355 before, 12 new); 92 tablet tests pass (the debug build, `-e notPackage …check`), run before the two review fixes below, which are covered by a laptop test and a start of the app on the tablet.

One tablet test had to change: `autosaveWritesTheFile` waited six seconds for an autosave that now comes after ten.

## Found on the way

- The first try at skipping idle scroll callbacks crashed the library at start: the grid calls its scroll listener once while it's being built. Seen on the tablet before it was committed, and guarded.
- Drawn a band at a time, MuPDF smooths the first and last rows of each band slightly differently from the same rows inside a bigger picture, so a stroke's edge was a pixel off where two bands met. Each band is now drawn with two spare rows above and below, and the result is the same as before to the pixel.
- The plan's 0.4 s rest wouldn't have merged the owner's erases, which come 1.0–1.6 s apart with button 2 held. The wait now also holds while button 2 is held near the screen, for three seconds at most.

## Found by the final review, and fixed

- **A crash.** Task 9's edit to the stale-file check put a comment in front of its "folder can't be read" guard, switching the guard off. The library would have crashed at every start with the SD card out. Fixed, with a laptop test (`cleaningAFolderThatIsNotThereRemovesNothing`, which failed first) and a start on the tablet with the library folder deleted: the note shows, no crash.
- **A tile could stay grey.** Thumbnails arriving in the last 120 ms before the library was left were dropped, and coming back to an unchanged folder never asked for them again. They are now put on their tiles as the library is left. No test: it's a timing of the screen that can't be driven.
- Left as they are, minor: a held-back show after an erase can land inside the refresh that follows a zoom (an extra flash at worst); thumbnails of a card that never comes back stay in the cache.

## Not measured by a computer

Task 4 (erases) and Task 5 (autosave) on the tablet, and how a folder filling in looks: the pen can't be scripted. They are in the [owner checklist](../../notes/TESTING-efficiency.md).

## The two "only if slow" items

- The old-ink check at opening: 18 ms for 125 pages. No plan needed.
- Reading the whole file before page 1: 57 ms of a 133 ms opening for the 14 MB test copy, with the file already read once. Not timed after a restart of the tablet. No plan for now.
