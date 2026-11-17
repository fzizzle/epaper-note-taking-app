# Findings log

What was found while debugging on the tablet: causes, measurements and the tricks that got them. Newest first. Bugs and to-dos themselves live in [TODO.md](../../TODO.md).

## 2026-11-17: what a search of the library would cost, and whether Android's file database can be trusted

For the [library search design](specs/2026-11-17-library-search-design.md). Release build on the tablet, untouched. All reads over `adb shell` (which goes through Android's storage layer, as Jot does); the owner's `Workbooks` only read. The scratch folders on the card (`jot-ms-test`, `.jot-mtime-test`) were removed afterwards.

**Reading the card.** The library on the card: 107 folders, 198 PDFs. `find . -name "*.pdf"` over it: 0.44 s the first time, then 0.17–0.19 s (three runs). About 4 ms per folder cold, 1.7 ms warm, so a walk of 5,000 folders would take 10–20 s.

**Folder times on the card.** In a scratch folder on the card, read with `stat -c %Y`: adding a file, renaming one and deleting one each moved the time of the folder they were in; none moved its parent's; appending to a file moved nothing. Times are whole seconds. Only writes made on the tablet were tried, not a card written in a laptop.

**MediaStore.** `content query --uri content://media/3331-3031/file --projection _data --where "_data LIKE '/storage/3331-3031/Workbooks/%.pdf'"` returned 196 rows; the card holds 198. Missing: `ML Machine Learning/Decision Boundaries on Two Features/… — workbook.pdf` and `… — workbook (attempt 2).pdf` (Jot's fresh attempt, 2026-11-13). Couldn't be reproduced: in a scratch folder a new file, a rename, a hidden temp moved into place, and a move over an existing file were all in MediaStore within 2 s. Cause unknown; the 2026-11-16 entry above saw MediaProvider log `Database update failed while renaming …pnotes-tmp` once. Shell trick: `content query` needs the `--where` in double quotes inside the `adb shell "…"` string, with the `LIKE` pattern in single quotes, or it fails with "Invalid token".

**SQLite on Android** (from the web, not measured): the system's SQLite has FTS3/FTS4, not FTS5; FTS5 (and its trigram tokenizer) needs a bundled SQLite.

## 2026-11-16: a "(my edits)" copy after Android clears the app, and a case-only rename on the tablet's own storage

Final review. Debug build, scratch folder `/sdcard/Documents/JotScratch/`, `library.xml` backed up and restored byte for byte, scratch files and their journal removed.

**Rebuilt screen (review B1).** Leaving for another app doesn't clear Jot from memory on this tablet, and `always_finish_activities` does nothing on this ROM, so the process was killed instead. Opened a copy of the 125-page "PWT test copy.pdf" by the path hook, snipped and pasted with `input stylus motionevent` (works while fast-ink is off for the tool), pushed a 3-page PDF over the file with `adb push`, pasted again. The save went into `scratch (my edits 2026-11-16).pdf` and the strip showed that name. Then Home, `adb shell am kill com.fzizzle.epapernotes` (no process left), `KEYCODE_APP_SWITCH` and a tap on Jot's card:

```
ActivityManager: Start proc 23211:com.fzizzle.epapernotes/u0a405 for next-top-activity {…/…ui.WritingActivity}
Notes: written pages n=7 of 125 ms=6
```

The rebuilt screen opened the 125-page copy, not the 3-page original its intent named; the screenshot shows the copy's name in the strip and both pasted snips. Side note: MediaProvider logged `Database update failed while renaming …/.scratch (my edits 2026-11-16).pdf.pnotes-tmp`; the copy was written whole all the same.

**Case-only rename on internal storage (review C, `RemovableVolumeTest.renameChangingOnlyCase`).** The same steps in a scratch folder under `/sdcard/Documents/JotScratch/` (a throwaway test class, not kept): `Files.isSameFile` says `lecture.pdf` and `Lecture.pdf` are one file there (both `dev=53,ino=69457`), the rename returns `Done` and the folder holds only `Lecture.pdf`. So the rename is not refused on the tablet's own storage.

## 2026-11-16: a snip over ink, and what drawing in runs costs

Question (review D2, decision 4): on screen, pen ink showed on top of a snip pasted over it, because `PdfFile.drawWithSolidInk` drew all solid ink after everything else. Now the annotations go in runs, in the page's order: something that isn't ink and comes after solid ink ends a run. Does the usual page (pen ink only) draw as fast as before?

`SpeedCheck.addingStrokesToAFullPage` now also draws its 1,500-stroke page at width 1742 (five draws, median) and logs a checksum of its pixels. Debug build, before and after alike; each cell is the medians of three runs (all fifteen draws in brackets).

| Page | Before | After |
|---|---|---|
| 1,500 pen strokes, width 1742 | 130, 129, 128 ms (127–132) | 130, 129, 128 ms (128–133) |
| 300 pen strokes, width 1742 (`renderingAnInkedPage`) | 52, 53, 52 ms (50–53) | 53, 53, 52 ms (50–53) |
| 300 pen strokes, width 2790 | 108, 109, 108 ms (108–110) | 112, 111, 111 ms (111–113) |
| The same two pages, before and after interleaved twice (six runs each) | 1,500: 128–131 ms; 300 at 2790: 111–112 ms | 1,500: 127–131 ms; 300 at 2790: 109–110 ms |

- No change: the first width-2790 gap reversed when the builds were installed in turns, so it was the tablet drifting, not the code. The 2026-11-10 figure of 137 ms for the 1,500-stroke page wasn't from `SpeedCheck`; 128–131 ms is today's number for the same kind of page.
- Every pixel is the same: the checksums (1,500 strokes −1799003082, 300 strokes 1349238510) match in every run, before and after.
- A page with a snip over its ink costs one more MuPDF pass and one more solid-ink pass per snip that ends a run; not measured, as such pages are rare.
- `AndroidDrawDevice(bitmap, x, y, false)` draws over what the picture holds; the three-argument one clears it to white first.
- Review fix: at first any snip after pen ink ended a run, so writing, pasting a figure elsewhere, then highlighting the writing turned the pen olive (`ff000000` became `ff655d17`). Now a snip ends a run only when it lies over some of the run's pen strokes (each stroke's box, looked up only when a snip follows ink). After the fix: 1,500 strokes 128, 130, 129 ms (127–133), checksums unchanged.

## 2026-11-16: which other windows take focus from the writing screen

Question (review B5): when the notification shade or a menu is in front of the page, does the page lose window focus, so fast-ink could be switched off there? `WritingActivity.onWindowFocusChanged` now logs `focus=... fast-ink=... dialog=...` (tag `Notes`; log only, nothing changes). Debug build on the tablet, scratch PDF open, fast-ink on unless noted:

| What was done | Did the page lose focus? | Fast-ink on at that moment |
|---|---|---|
| Shade pulled down (`cmd statusbar expand-notifications`) | yes, `focus=false`; `focus=true` when collapsed | yes |
| Volume key (the app's page turn) | no | not applicable (page turned, short pause and resume as before) |
| Toast ("Adding a page...") | no (the toast window is not focusable) | not applicable |
| The app's own page menu (open, then BACK) | yes, `focus=false` with `dialog=true`; `focus=true` on close, then fast-ink resumes | no (already paused by the menu) |
| Go to page (tap the page number, then BACK) | yes, same as the page menu | no (already paused) |
| Power menu (`input keyevent --longpress KEYCODE_POWER`, then BACK) | yes, `focus=false` with `dialog=false`; `focus=true` after BACK | yes |
| Floating navigation button | not triggered: the button is off on this tablet (`floating_button_status_key` 0, and setting it over adb brings nothing up). Needs the owner to switch it on and tap it | not known |

- Headline: the shade and the power menu take focus while fast-ink stays on, and the app is not told (`dialog=false`). The app's own menus take focus too, but those already pause fast-ink.
- Also seen: opening a 125-page deck with landscape pages showed the app's "rotate all pages" offer, which is one of those dialogs (`focus=false dialog=true`, then `focus=true` and "resume fast-ink" on BACK).
- Step 3 (the pen over the shade) waits on the owner: pull the shade down and write on it; does a line follow the pen there, and is it on the page when the shade closes? Until the owner answers, decision 3 (fast-ink off while another window is in front) is not built and the log line stays.

## 2026-11-16: a file overwritten in place under an open document

Question (review A3): what does MuPDF do when the file under an open document is changed by something else, and can a "(my edits)" copy then be damaged? `PdfFileChangedUnderneathTest` on the tablet: A is 40 pages (150 KB), B is 3 pages (11 KB), each case on a fresh copy of A, opened, page 0 drawn, then:

| Case | Opened by path (before) | Opened from memory (after) |
|---|---|---|
| A overwritten in place with B (file cut to B's length), stroke, `saveTo(copy)` | no error; copy of 3 pages (B's), `wasRepaired` false, page 30 doesn't exist | copy of 40 pages, not repaired |
| same overwrite, then `render(30, 600)` | no error: a blank page, `pageCount` of the open document now 3 | A's page 30, identical to a render before the overwrite |
| B moved over A with `ATOMIC_MOVE` (as Syncthing does), stroke, `saveTo(copy)` | copy of 40 pages, not repaired, stroke on page 0 | the same |

- The fault is real: MuPDF reads the open file as it goes, so an overwrite in place changes the document under the app, including what it counts: the open document itself said 3 pages after the overwrite. The copy opened clean, with someone else's 3 pages and the owner's writing on page 0.
- So a page count read from the open document is no check against this: the copy matches it. The session compares a copy with the count it holds itself (taken at opening, on reload and on opening again, moved by its own page adds and removes). That catches this overwrite when the other file has another page count, and a repaired copy. It does not catch an overwrite by a file with the same page count.
- Decision 3 was therefore needed and is built: `PdfFile.open(file, inMemory = true)` reads files of up to 64 MB into memory (`IN_MEMORY_LIMIT_BYTES`). Only the writing screen's document asks for it; the library's thumbnails and facts open by path as before. All three cases give A whole when opened that way.
- A rename leaves the old file alone (the open handle follows the old file), so it was never the problem.
- Cost, on the 14 MB lecture deck (125 pages, `SpeedCheck`, two runs each): open 11 and 12 ms before, 26 ms after (the read of the file; the fingerprint, which also reads it, takes 14 ms); first page 202 and 214 ms before, 173 and 195 ms after (no change); reopen and read strokes the same. Memory (native heap allocated, after opening and drawing page 1): 19.2 MB before, 32.9 MB after, 13.7 MB more: the file's size. After closing, both are back to 16.3 MB. The Java heap does not grow: the bytes are copied into MuPDF and let go.
- Files over 64 MB still open by path and keep the exposure: for them the session's own page count and the repair check are the only protection, and a same-page-count overwrite gets through.

## 2026-11-13: opened-out folders and thumbnails

Owner: opening the small folders out is expensive and a bit slow with all the thumbnails. The tablet's log (release build of main, be19a62) for the ML course, 11 topics of two PDFs each:

| Visit | Listing | Thumbnails drawn | Shown on screen |
|---|---|---|---|
| First, after the library design build | 260 ms | 22, about 30 ms each, 641 ms in all | in five goes (5, 6, 5, 5, 1 tiles) over 0.8 s |
| Back from a document | 26 to 266 ms | 1 (the file just written) | the folder 380 to 500 ms after the screen came back |
| CTM course, thumbnails cached | 52 ms | none | all 30 in one go |

- Why the first visit drew everything: thumbnails cached before the library design build have no `.facts` file beside them, so each was drawn again once to read its page count and writing mark (`ThumbnailLoader.load`). This happens once per file.
- Why five goes: the loader draws a tile in about 30 ms, so about four or five finish in each 120 ms gathering window (`TILE_BATCH_MS`), and each window is a refresh.
- Opening out doubles the PDF tiles in a course (each topic's two PDFs instead of one folder tile), and each one needs its thumbnail.
- Reading the page count and looking for writing cost 0 to 1 ms per file: not the slow part.

## 2026-11-12: owner session

### Button 2 does nothing when pressed close to the screen

Owner: button 2 "usually fails the first 2–3 attempts": no ink, no erase. It happens when button 2 is pressed while the pen already hovers close to the screen.

Raw pen events (`adb shell getevent -lt /dev/input/event5`, the pen is `onyx_emp_istaric_pen`):

| Case | Tool reported | Touch | Button 2 | Pressure (of 4095) |
|---|---|---|---|---|
| Writing | `BTN_TOOL_BRUSH` | `BTN_TOUCH` down | no | median 2651 |
| Button 2 pressed higher up (works) | `BTN_TOOL_RUBBER` | `BTN_TOUCH` down | no | median 445, max ~500 |
| Button 2 pressed close (fails) | `BTN_TOOL_BRUSH` | **none** | `BTN_STYLUS2` down | median 433 |
| Hovering | `BTN_TOOL_BRUSH` | none | no | 0 |

So in the failing case the pen presses on the screen with the button held, but never reports a touch and never turns into the eraser. Onyx's raw input sees no stroke, so it neither draws nor calls its erase callback. Android sends only `ACTION_HOVER_MOVE` events, with tool = stylus, `buttonState = 64` (`BUTTON_STYLUS_SECONDARY`) and pressure 0.07–0.13. Plain hovering reads exactly 0.

The erases that did reach the app all hit their strokes. The trails were placed correctly: the screen-to-page mapping was checked, and it is the same before and after the first erase.

Fix (branch `button2-hover-erase`): `ui/HoverErase.kt` turns hover samples with button 2 held and pressure above 0.02 into an eraser trail. The trail is handed over when the pressure drops, the button is let go or the pen leaves. A real touch cancels it. Owner: "works much more reliably".

### Every erase flashes the erased part of the screen

Owner, after the fix above. Not fixed; it's listed under known bugs in [TODO.md](../../TODO.md). The app log for each erase that hits, by either route:

```
erase page=0 hits=1 ms=32
render requested page=0 width=1742 version=7
rendered page=0 width=1742 version=7 of 7
show: pause fast-ink
resume fast-ink          (about 165–175 ms later)
```

The system log for the same moment shows `SurfaceFlinger: Post enabled!`, `HWEpdcManager: ### EUS, s 4 c 0`, then `Pid … State change to: toPause` and, about 165 ms later, `State change to: running`. The panel update during the pause from `showWithoutFastInk` is the flash. It's the same refresh as the "each erase refreshes the page" note in [notes/TESTING-strip.md](../../notes/TESTING-strip.md). It's seen more often now that button 2 always erases.

### The pen-width fix of PR #21 doesn't hold

Owner: the ink is thicker before picking another tool than after, so the live line is wider than the saved ink. The tablet's release build was checked to contain PR #21: `StrokeStyle.liveFountainWidth` is in the installed APK's dex. The pen's pressure range is 0–4095 (`getevent -p`, `ABS_PRESSURE max 4095`), matching `PenPoints.MAX_PRESSURE`. So the live line, drawn by the system, doesn't follow the SDK's `NeoFountainPenWrapper.computeStrokePoints` curve the fix was matched to. Screenshots can't capture the live line: the system draws it straight onto the panel.

**Measured with phone photos** of the same strokes before (pen selected, live line) and after a tap on Lasso (the app's copy). The phone moved between shots. The scale between them came from the printed step boxes: about 214 px wide in the first photo and 261–262 in the second, so 1.224×. Each line's thickness was taken as its darkness summed across the line, divided by full-ink darkness, the median over about 150–230 columns. That holds up under camera blur.

| Line | Live (photo 1 px) | Saved (photo 2 px ÷ 1.224) | Saved ÷ live |
|---|---|---|---|
| Under the formula | 6.30 | 5.48 | 0.87 |
| Under the sentence | 6.01 | 4.91 | 0.82 |
| Bottom line | 5.98 | 4.92 | 0.82 |

The strokes' saved pressures, read from the PDF (`/PNotesStroke`), have medians of 0.56–0.64, with 10–90% between 0.49 and 0.78: the owner's normal writing. So at that pressure, the system's live line is about 1.2× the SDK curve. There's no data yet for very light or very firm strokes.

Fix: the saved pen is now 1.2 × (0.8 + 2.4 p²) pt, so 0.96–3.84 pt. The live line keeps W = 2.4 × px/pt − 1 (`StrokeStyle.liveSpan`), so it doesn't change. Strokes saved earlier are drawn wider too, because the app draws every stroke from its pressure.

### Tricks

- Raw pen events can be **read** with `adb shell getevent -lt /dev/input/event5`. Only injecting them is blocked.
- The tablet's log buffer only holds a few minutes, so run `adb logcat -G 16M` before a session.
- To check what an installed release build contains: pull it (`adb shell pm path …`, `adb pull`), unzip the `classes*.dex` files and look with `~/Android/Sdk/build-tools/35.0.0/dexdump -d`. Release builds keep class and method names.
- Installing a debug build over the release build keeps the app's files, settings and All files access.
