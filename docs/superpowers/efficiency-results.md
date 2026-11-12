# Efficiency plan: results

Plan: [efficiency](plans/2026-11-12-efficiency.md). Branch: `efficiency`. Tablet: Boox Note Air 6 C, Android 16, MuPDF 1.28.5.

Numbers driven over adb are from the **debug** build, before and after alike: the adb shortcuts that open a file or set the library folder only exist there. Pen strokes can't be scripted (the tablet blocks injected pen events), so the pen-side numbers come from the owner's own session log of 2026-11-12 (release build of main) and from the owner's check at the end.

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
