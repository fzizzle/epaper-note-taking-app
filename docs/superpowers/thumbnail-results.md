# Lighter thumbnails: results

Plan: [lighter thumbnails](plans/2026-11-13-lighter-thumbnails.md). Branch: `thumbnails`. Tablet: Boox Note Air 6 C, debug build (the adb shortcuts only exist there), before and after alike.

Measured with the `folder ready` log line: from the moment a folder is asked for until every PDF tile on screen has its picture; `goes` is how many times the grid was drawn with new pictures on the way (each one a refresh of the panel). A scratch library folder holds copies of two of the owner's courses: ML (11 topics, 23 PDFs, all shown opened out) and CTM (15 topics, 30 PDFs).

## Before (2026-11-13, main at be19a62)

| Visit | ML | CTM |
|---|---|---|
| First visit (no thumbnails yet) | 637 to 664 ms, 5 goes, 23 drawn | 766 ms, 6 goes, 30 drawn |
| Second visit (thumbnails in memory) | 72 to 78 ms, 1 go | 81 ms, 1 go |
| Back from a document | 170 to 192 ms, 1 go | 165 ms, 1 go |
| After the app is restarted (thumbnails on disk only) | 193 ms, 2 goes (grey pages, then pictures) | |

A thumbnail drawn from its PDF took 24 ms on average in the app, of which storing it in the cache (the PNG written, the old versions swept from a folder of about 500 files) took 5.7 ms.

## After, task by task

| Task | Measure | Before | After | Kept? |
|---|---|---|---|---|
| 2. A cached thumbnail isn't drawn again for its facts | tablet test `aCachedThumbnailWithoutFactsIsNotDrawnAgain`: draws for a cached thumbnail with no facts file | 1 (failed first) | 0; facts read from the PDF in about 1 ms | yes |
| 2. Storing sweeps old versions by name | time to store one thumbnail, cold ML visit, two runs | 5.7 ms | 4.3 to 4.6 ms (a thumbnail in all: 24 → 23 ms) | yes: over 10% of its own measure |
| 3. Thumbnails at the size they're shown (240 by at most 340) | a thumbnail in memory (16-bit) / on disk / the time to make one | 254 KB / 36 KB / 6 ms PNG | 163 KB / 27 KB / 4 ms PNG. A folder visit takes as long as before (ML first visit 638 ms): drawing the page and opening the PDF cost the same at either size | yes: a third less memory and disk |
| 4. One go per folder | first visit, ML / CTM: time, refreshes | 637 to 664 ms, 5 goes / 766 ms, 6 goes | 542 ms, 2 goes / 686 ms, 2 goes (the folder, then all the missing ones together) | yes |
| 4. One go per folder | after the app is restarted (thumbnails on disk only), ML | 193 ms, 2 goes (grey pages, then pictures) | 159 ms, 1 go | yes |
| 4. One go per folder | second visit / back from a document | 72 to 81 ms, 1 go / 165 to 192 ms, 1 go | 98 to 105 ms, 1 go / 167 to 196 ms, 1 go: no change (back from a document waits for the document's last save) | |
| 5. Draw ahead | a folder visited for the first time, once the pass has been through it (ML) | 542 ms, 2 goes, 23 drawn while watching | 162 ms, 1 go, none drawn | yes |
| 5. Draw ahead | the pass itself | | 53 PDFs in 1.3 s, 348 in about 7 s of drawing (20 ms each); the owner's real library, already mostly cached: 3 drawn, 0.9 s to look through it | |
| 5. Draw ahead | a document opened while the pass runs | 4 thumbnails were drawn alongside the document's first page (the loader paused only once the library had stopped) | none drawn from the tap until the library is back: the loader now pauses as soon as a document is asked for | yes |
| 6. A gentler panel update (REGAL) for the library | the owner's eye | | in the checklist | owner decides |

## Tests

514 laptop tests and 124 tablet tests pass (2026-11-13), after the final review's fixes.

With everything in, the scratch library opened 4 seconds after the app started (drawing ahead done by then): ML first visit 172 ms, 1 go; CTM first visit 202 ms, 1 go; second visits 51 to 63 ms; back from a document 154 ms; after a restart 152 ms, 1 go.

## Found by the final review, and fixed

- **A tile could keep its old picture after Refresh.** Reading thumbnails ahead from the cache could race Refresh's drop and put an old picture back in memory, after its file was gone, so it was never made again. Now a thumbnail being dropped is never read ahead, and one read while a drop began or ended isn't kept; and nothing is read ahead after Refresh at all. Test `warmDoesNotBringBackAThumbnailARefreshDropped` (failed first).
- **A stray thumbnail cost a whole-screen refresh.** One that arrived for a folder already left refreshed the grid with nothing changed. Now only a change to a tile on screen refreshes it.
- Three tests proved less than their names; they now pause a pass under way, keep the thumbnails made before the card goes out, and allow at most one picture drawn ahead before a tile on screen. A new test covers when a folder counts as still waiting.

## In short

A folder now comes on in one refresh whenever its thumbnails exist, and after the first minute in the library they all do: the whole library's thumbnails are drawn ahead, about 20 ms each, while the library is open and idle. A folder whose thumbnails are still missing comes on in two refreshes, not five or six. Each thumbnail takes a third less memory and disk. Making one costs the same as before: opening the PDF and drawing its page, not the pixels, take the time.
