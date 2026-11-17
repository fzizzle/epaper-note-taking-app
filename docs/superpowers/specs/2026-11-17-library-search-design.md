# Library search: Design

Date: 2026-11-17. Owner-approved approach: a saved name index, checked against folder times (option 2 below).
This replaces Task 8 of the [improvements plan](../plans/2026-11-16-improvements.md), which walked the card on every search.

## 1. Intent and why

The owner wants to find a PDF without tapping through folders. Today the library holds about 200 PDFs in about 100 folders, but the owner asked for a method that stays fast in a **huge** library (tens of thousands of PDFs in thousands of folders), so the design is sized for that, not for today.

Searching **names** is what gets built now. Searching the **text inside the PDFs** (the workbooks have a real text layer) is wanted later; this design leaves room for it and builds nothing for it (§9).

## 2. Goals

- Type part of a name, see every PDF in the library whose name contains it, from any folder, with the folder it's in. Tap one to open it.
- A search answers in **under 100 ms with 20,000 PDFs**, whatever state the card is in, because it never reads the card.
- What a search finds matches what is on the card. Where it can't yet (a check still running), the screen says so.
- Capitals and accents don't matter ("resume" finds "Résumé").

## 3. Non-goals

- Searching folder names (owner, 2026-11-17: file names only). A result still shows its folder.
- Searching inside PDFs, or handwriting (spec §3). Text inside PDFs is §9, for later.
- Search as you type. Every key would refresh the e-ink panel.
- Using the index for anything else now (the thumbnail draw-ahead walk, folder counts). It could later.
- Search limited to the folder on show.

## 4. Anti-goals: failure even if it works

- **A search that silently misses a file that's on the card.** This is why Android's own file database was ruled out (§5).
- **A file that's on the card treated as gone because the card is out** (spec: a card that's out is not a deleted folder).
- **Searching makes the library slower.** Folder visits, thumbnails and opening a document must not get noticeably slower while the index is checked or built.
- **More screen refreshes than today** (owner, 2026-11-09): one refresh per search. Only in a library too big to check within a second: a small update of the "Checking for changes…" line when it goes, and one redraw of the list if the check changed what was found.
- **The index treated as the truth.** It is a cache. Opening a file, renaming, moving and deleting always act on the card, never on what the index says.

## 5. Approaches considered

Measured on the owner's tablet, 2026-11-17 (findings log): reading a folder on the memory card costs about 1.7 ms warm and 4 ms cold, so a full walk of 5,000 folders takes 10–20 s. A folder's time on the card changes when a file is added, renamed or deleted directly in it (not in its parent, and not when a file's contents change).

1. **Walk the card on every search** (the improvements plan's Task 8). No saved state. Fine up to about 2,000 PDFs, then 10 s and more per search. Rejected for the scale asked for.
2. **A saved name index, checked against folder times. Chosen.** Searches read only the index. The index is kept in step by re-reading only the folders whose time changed: the method Linux's `locate` uses.
3. **Android's file database (MediaStore).** Kept by the system and instant to query, but on the tablet it knew 196 of the 198 PDFs, the two missing ones both in a folder Jot had written in, for a reason not found. Its matching ignores neither accents nor word order. Rejected (anti-goal 1).
4. Also looked at: a newer SQLite bundled for FTS5's substring index (a new library; only pays off at millions of names), and an index kept only in memory (a huge library waits 10–20 s for its first search after every restart). Both rejected.

Within option 2, the owner chose to keep the index in **Android's built-in SQLite** rather than a plain file (2026-11-17): the later text search needs SQLite's FTS4 anyway, so there is one store, not two. The cost: the store's tests run on the tablet.

## 6. Behaviour (what the owner sees)

- **A magnifier** in the library's top row, beside Refresh, drawn in code like the other glyphs, described "Search".
- A tap asks for the text in the library's usual text dialog, action **Search**. The last query is filled in, selected, so a new one replaces it by typing.
- **Results** are shown as rows, whatever the tiles/list setting: each PDF's name and, as its detail, its folder's path from the library folder (`ML Machine Learning › Confusion-Matrix Metrics`). The breadcrumb reads `Search: "recall"`. They come in natural order of folder path, then name, as the library orders things.
- **Matching:** every word of the query must be somewhere in the name (the name without `.pdf`), in any order: `week ther` finds "Thermo Week 2 answers". Capitals and accents are ignored. An empty query does nothing.
- **Many results:** the first 200 are shown, and the note line above the results says "Showing 200 of 1,234. Type more to narrow it." (not a row).
- **None:** where the empty-folder hint goes, "Nothing found."
- **Every search checks the card first** (owner, 2026-11-17), so a PDF pushed in a minute ago is found. The search waits up to 1 s for the check. The owner's library checks in about 0.2 s, so results come once and are current.
- **When the check takes longer** (a huge library), the results from the index are shown at once with a line above them: "Checking for changes…". When the check ends, the search runs again: the line goes with a small update of that line alone (no full-screen refresh), and the list is redrawn only if the results changed.
- **While the index is first being built**, the same, with the line "Still reading the library…".
- **Card out:** "The SD card isn't in." (as the library says it now). The index is left alone.
- **Tap** opens the PDF, as from a folder. If the file is no longer there (the index was behind), it says "That file isn't there any more." and the search runs again after that folder is re-read. **Long-press** does nothing in results.
- **Back**, or a tap on the breadcrumb, returns to the folder that was on show, re-listed. Back from a document opened from results returns to the results, run again.
- The folder watch and the thumbnail loader are left as they are while results show.

## 7. The index

### 7.1 What it holds

A SQLite database in Jot's private storage (`library-index.db`), never in the owner's folders. Two tables:

- **folders:** each folder in the library: its path from the library folder, its parent, the folder time when it was last read, and when it was read.
- **pdfs:** each PDF: its folder, its file name, a **match key** (the name without `.pdf`, decomposed (NFD) with combining marks dropped, then lower case with `Locale.ROOT`), its size and its time. Each PDF row has a stable id, kept while the file keeps its path (§9 needs it).

The same rules as the library decide what is in it: hidden names and temporary files (`FolderListing.isHidden`) are left out, only `.pdf` files (any case), and **linked folders are not followed** (no loops, nothing outside the library).

It is a cache: if it can't be opened, its layout version is old, or the library folder setting changes, it is deleted and built again. Nothing in it is ever needed to keep the owner's work.

### 7.2 Keeping it in step

**The check** visits every folder the index knows, from the library folder down:

- It reads the folder's time (one call per folder, no listing).
- **Unchanged:** the folder's PDFs and subfolders are kept as they are; go on into its subfolders.
- **Changed, or not known yet:** list it (one read gets names, kinds, sizes and times together, not one call per detail); replace its PDFs; subfolders gone from the listing are dropped with everything under them; new subfolders are read in full; known ones are checked in turn.
- **A time too close to the read to trust:** a folder whose time is within 2 s of when it was read may have changed again in the same second without its time moving, so it is listed again at the next check. (Card times are kept to the second, or coarser.)
- **The library folder itself is missing:** stop, change nothing. If it is on a card that's out, that's why; if not, the next check sees what's there.

**A full re-read** lists every folder, trusting no folder time. Folder times were only measured for writes made on the tablet (findings log, 2026-11-17), not for a card written in a laptop, so a full re-read is used where the card may have been written elsewhere or the owner asks for certainty. At 5,000 folders it takes 10–20 s in the background; it is rare.

**When the check runs:**

- When the library is first shown after Jot starts: a full re-read, since while Jot wasn't running the card may have been in a laptop.
- When a memory card goes in (it may have been changed in a laptop): a full re-read.
- On Refresh: a full re-read.
- On every search (owner, 2026-11-17), as §6 says. A search asked for while a check is already running doesn't start another; it waits for that one.

Never on every return from a document or every folder visit: at 5,000 folders a check takes seconds of card reads.

**Between checks** the index is told directly:

- **Every folder the library lists** (a visit, a re-list after the folder watch fires) is re-read by the index on its own thread after the listing (one more read, 2-4 ms). The library's listing opens small folders out and doesn't carry folder times, so handing it over would mean changing `FolderListing`.
- **After a rename, move, delete, new folder or fresh attempt made in Jot**, the folders it touched (where the file was, where it went) are re-read by the index.

### 7.3 Threads and speed

- The check and the first build run on a thread of their own at background priority, never the library's `disk` thread, so folder visits don't wait behind them. They write in batches, one transaction per few dozen folders, so a search waits at most one batch.
- Searches read the database on the index's reading side (SQLite in write-ahead mode, so reads don't wait for writes) and never touch the card.
- Searching is one query over the match keys: one `LIKE` per word (with `%` and `_` in the query escaped), sorted in natural order in Kotlin. Only the newest search's answer is shown (a counter, as `list` uses `listed`).
- The check pauses while a document is open: the writing screen's saves and page reads come first.

## 8. Code shape

As the project does already: the rules in plain Kotlin in `library/`, with laptop tests; the Android parts beside the screens.

- `library/NameMatch`: the match key and "does this name match this query", plus the `LIKE` patterns for a query. Laptop tests.
- `library/IndexCheck`: the check's decisions (which folders to keep, list, drop or read in full; the 2 s rule; stopping when the library folder is missing), written against a small interface for the store and one for the disk, so it runs on the laptop with fakes.
- The SQLite store (implements the store interface), the index's threads, the card-in receiver, and the search screen code in `ui/`. Tablet tests for the store.
- `LibraryActivity` gains the search state and Back handling; `LibraryChanges` tells the index which folders a change touched; `list` hands each listing to the index.

## 9. Room for later: text inside PDFs (design for, don't build)

A later search of the words inside PDFs would use the same button and results, and add to the index:

- An FTS4 table (built into Android's SQLite; tokenizer `unicode61` with diacritics removed) holding each page's text, keyed by the PDF row's id and the page number. Text comes from MuPDF, which Jot already uses.
- Text read again only when a PDF's size or time changes, in the background, never while a document is open.
- Results gain a page number and open at that page.
- FTS4 matches whole words and word starts (`recall`, `rec*`), not the middle of words.

Nothing of this is built now: no table, no setting, no hook. The PDF rows' stable ids and their size and time are already there for other reasons.

## 10. Checking it

- **Laptop:** the match rules (every word, any order, accents and capitals, empty query, `%` and `_` in a query); the check's decisions on fakes (unchanged folder not listed; a changed one replaced; a folder moved on a laptop found under its new path and dropped under its old; a missing library folder changes nothing; a folder read within 2 s of its time is listed again next time); hidden and temporary files and linked folders left out.
- **Tablet, on a scratch library** on the memory card (never the owner's `Workbooks`; library folder switched by the debug hook, `shared_prefs/library.xml` backed up and restored), generated with 20,000 small PDFs in 4,000 folders: time the first build, a check with nothing changed, a check after a change, and a search (target under 100 ms); compare a folder visit's time with a check running and without. Into the findings log.
- **Owner checklist** `notes/TESTING-search.md`: search for half a name, two words in the other order, an accent, nothing; a PDF copied onto the card from the laptop is found after the card goes back in; a rename in Jot is found under its new name at once; Back from results and from a document opened from them; nothing refreshes more than before.

## 11. Left to the builder

- Table and column names, indexes, the layout version scheme, batch size.
- How the card-in event is noticed (a broadcast receiver, or the volume list compared on resume).
- How the "Checking for changes…" line is updated alone with `EpdController.invalidate(<that view>, UpdateMode.GU)` (`PanelRefresh.line`); `PenInput.refreshAlone` no longer exists. It stays within the library's look.
- Whether the 200-result cap and the 1 s wait need tuning after the tablet timings (report changes).

## 12. Decisions made by the designer, not the owner

- The check's other triggers (§7.2); full re-reads on card-in and on Refresh.
- The 1 s wait for the check before results show.
- The 2 s rule for times too close to a read.
- Results shown as rows whatever the view setting; 200 shown at most.
- Back from a document opened from results returns to the results.
- The check pauses while a document is open.
- The results order: folder path, then name.
- Made while planning: after each listing the index re-reads that one folder itself, instead of being handed what the library read (§7.2).
- Made while planning: the check when the library is first shown after Jot starts is a full re-read (§7.2).
- Made while planning: "Showing 200 of N" goes in the note line above the results, not in a row (§6).
- Made while planning: the note line is updated with `EpdController.invalidate(<that view>, UpdateMode.GU)` (`PanelRefresh.line`) (§11).
