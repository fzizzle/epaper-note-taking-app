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
