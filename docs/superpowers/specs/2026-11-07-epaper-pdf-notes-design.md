# Jot (first called E-paper PDF Notes): Design

Date: 2026-11-07
Target device: Boox Note Air 6 C (Android, colour Kaleido e-ink, EMR pen)

## 1. Intent and why

The owner annotates lecture slides (PDF exports of PowerPoint decks) on a Boox Note Air 6 C. The stock Boox Notes app writes well, but it gets in the way:

- PDFs have to be imported one at a time.
- Sync only works through Boox's proprietary service.
- A PDF page can't be rotated once it's imported.
- Extra buttons on third-party EMR pens can't be mapped. The owner bought a two-button pen to get quick access to the eraser, and the app ignores both buttons.
- There's no highlighter they can find.
- The UI is large and crowded with features, and it gets in the way of writing.
- Moving around a page needs two fingers and works badly.

This app is a **thin layer for viewing and writing on top of ordinary PDF files in ordinary folders**, in the spirit of Obsidian over a folder of Markdown files. The files are the source of truth. The app never imports, copies into a private store, or syncs. The owner syncs the folder however they like (Syncthing, a cloud folder, USB).

It should be lightweight and fast, and it should do a small set of core things very well: **writing, erasing, selecting, and copy and paste**.

## 2. Goals

1. A pen that feels as immediate as the stock app (Boox fast-ink drawing).
2. PDFs live in the owner's folder tree, and the app reflects that tree exactly, including changes made outside the app.
3. Handwriting is stored as a separate layer in the PDF file itself (standard PDF annotations). It is visible in other PDF viewers, and the original page content is never modified.
4. Both pen side buttons are used: button 1 for **lasso select**, button 2 for **stroke eraser**, each active **only while the button is held**.
5. Minimal UI: one thin toolbar strip, nothing else permanently on screen.
6. Writing is never lost: autosave, crash safety, and conflict handling that keeps both versions.

## 3. Non-goals (first version)

- Blank notebooks (lined or grid paper). The design must leave room for them, but they are not built now.
- Partial (pixel) eraser. Stroke eraser only.
- Copy and paste between different documents.
- More than one pen colour or style, and more than one highlighter colour.
- Any sync, account, cloud, or login.
- Landscape-specific layouts. The screen may turn (section 6.2), but nothing is designed especially for landscape.
- Support for devices other than the Note Air 6 C. Don't lock the app to it needlessly, but don't test or design for others.
- Text recognition, search inside handwriting, shapes, templates, layers UI, or any other feature not listed here.

## 4. Anti-goals: failure even if it technically works

- **Lost writing.** This is the worst possible failure. A failed or silent autosave, a crash that loses recent strokes, or a corrupted PDF after a crash mid-save. Saving is the most protected part of the design (section 7).
- **A laggy pen.**
- **A crowded screen**, or UI that grows features over time.
- **Notes held anywhere other than the owner's own files**, such as a private database that becomes the real copy.

## 5. How it gets made (decided)

- **Kotlin**, native Android.
- **Boox's Onyx pen SDK** for drawing. It gives the fast-ink (raw drawing) mode the stock app uses. Plain Android canvas drawing is too laggy on e-ink.
- **MuPDF** (Artifex's official Android library) for rendering pages and for reading and writing annotations. Owner-approved 2026-11-09, see section 10.
- **Licence: AGPL-3.0** for the whole app, as MuPDF requires. The owner plans to open-source the app anyway (owner-approved 2026-11-09).
- **Readable code.** The owner will read the code but not write it. Small files, plain names, comments that explain *why*, and no cleverness where something boring works.
- **Process rule:** any architectural or feature change from this document must be explained to the owner in plain words and approved before it's made. Only the owner gives these approvals. Treat them as hard stop points in the plan, especially the PDF library choice and the side-button test result (section 9).

## 6. Behaviour

### 6.1 Library (folder screen)

- On first launch the owner picks one **root folder**. The app needs broad file access to read and write files in place. For a personal sideloaded app, "All files access" is the expected route (builder's call, see section 10).
- The folder screen is a **thumbnail grid** of subfolders and PDFs. Folders show a folder tile, and PDFs show their first page.
- A **breadcrumb** at the top shows the path. Tap a segment to go up.
- A **refresh** button and a **new folder** button sit at the top.
- The grid **updates automatically** when files change on disk (added, removed, renamed, moved, or modified by a laptop, a sync app, or USB), and refresh forces a rescan.
- **Long-press a tile** for a small menu: rename, move, delete. Delete asks for confirmation, because it deletes the real file. A PDF's menu also has **Fresh attempt** (owner's decision, 2026-11-12): a copy beside it named `<name> (attempt 2).pdf` (then 3, 4, …) without the owner's ink and pasted snips, to try a workbook again. Dotted pages the owner added stay, empty; marks made in other apps stay; the original isn't touched; a name already taken is never written over.
- Thumbnails are cached in the app's own cache storage and regenerated only when a file changes, so the grid stays fast. Since 2026-11-13 ([lighter thumbnails plan](../plans/2026-11-13-lighter-thumbnails.md)): they are made at the size a tile shows them (240 px wide); while the library is open and idle, the thumbnails of every PDF in it that has none are drawn ahead, quietly, and never while a document is open; a folder is shown once its visible thumbnails are read from the cache, so it comes on in one refresh, and any still missing come on together.
- Tapping a PDF opens it **at the page where it was last left**. That position is remembered in the app's private storage, never in the owner's folders.
- "Bulk import" is just copying many PDFs into a folder. There is no import feature.

What the library shows beyond that (owner's decisions, 2026-11-12; [library design plan](../plans/2026-11-12-library-design.md)). Nothing here is written into the owner's folders: it comes from the app's own storage, from the PDF itself, or from the path.

- **Small folders are opened out.** A folder holding one to three PDFs and no folders is shown as its PDFs, where the folder would be, each headed with the folder's name and then its own short name ("workbook", "answer key"). A topic that is just a workbook and its key then needs no tap to get into. The folder is still there on disk and is reached from a long-press on one of its PDFs ("Folder: ‹name›": Open, Rename, Move, Delete). A fourth PDF, or a folder inside, makes it a folder tile again. A switch in Settings, on unless turned off. This is the one place the screen doesn't show the tree exactly as it is on disk.
- **How far you are.** Under the picture of a PDF you've opened, a thin bar filled to the page you left.
- **Whether you've written on it.** A black corner on the picture when the PDF holds your ink. It's read from the PDF when its thumbnail is made, so it's right for a file that arrives from a laptop, and it needs thumbnails switched on.
- **A colour per course.** Each folder directly inside the library folder gets one of eight colours, as a stripe beside its tile; inside a course, the line under the top strip takes its colour. A course takes the colour its code's letters give, or the next free one, so no two share while there are colours left.
- **Continue** shows a picture of the page you left, with your ink, beside the file's name and "page 12 of 30". At the library folder only.
- **A list view.** A button at the top switches between tiles and a compact list: name, "12 / 30", the bar, the writing mark, no pictures. One column upright, two sideways. The choice is kept.
- **Coming back from a document**, its tile is brought into view if it isn't, and marked grey until the next touch.
- **An empty folder** says how to fill it: copy PDFs into the folder it names, from a computer or a sync app.
- **Start in the last document.** A switch in Settings, off unless turned on: starting the app opens the file you were last in, at its page, and Back leads to the library. If that open didn't finish the last time, the app starts in the library once and says so, so a file that crashes the app can't lock the owner out.

### 6.2 Writing screen

Layout: the PDF pages fill the screen. A **thin top strip** (a little under 5% of the height, 4.4% since 2026-11-10, always visible, one row) holds, left to right:

`← library · file name · pen · highlighter · eraser · lasso select · snip · undo · redo · paste · fit · ⋯ page menu · ‹ page ›`

How the strip is laid out (owner's decision, 2026-11-10): ← sits at the left edge, followed by the open file's name in grey, cut from the front when it's too long so its end shows; it never moves the island (owner, UI review decision 2, 2026-11-12). The tools, undo and redo sit together on a capsule (the "island") centred on the screen. ⋯ and the page indicator sit at the right edge. The island picks out the chosen tool. Tapping a tool shows it at once, with one quick flash, because fast-ink is on. Holding a pen button doesn't change the island: that would flash the screen at every press (owner's choice). The status bar is hidden on the writing screen, so the strip sits at the very top with even space around it. The island is white with a black outline (owner's choice, 2026-11-10).

The page menu holds **rotate this page**, **rotate all pages**, **add a dotted page after this one** (and **remove this added page**, on a page the app added; section 6.5), and the reading settings below. On the last page, › becomes **+**, which adds a dotted page after it.

Added on 2026-11-12 (owner's decisions; [writing screen design plan](../plans/2026-11-12-writing-screen-design.md)):

- **The line under the strip shows where you are.** It is black from the left as far as the page on show and grey after, with a small black tick under it for each page that has writing. It takes 8 pixels and only changes when the page does.
- **Fit** sits at the right, before ⋯: grey at normal size, black when zoomed in, where a tap brings the whole page back with one refresh. It is always there, so nothing on the strip moves.
- **"Go to page" is a pad of big buttons** (0 to 9, ⌫, Go), not a text field: the system keyboard is slow to come and flashes on e-ink.
- **Holding ‹ goes to the first page, holding › to the last.**

Easier-to-read pages (owner's decisions, 2026-11-10; [readability plan](../plans/2026-11-10-review-readability.md)). These change only the picture on screen: the PDF, the ink, snips and thumbnails are never touched, and the settings are kept in the app's own preferences, the same for all documents. **Sharp zoom**, always on: after a zoom or a move, the part of the page on screen is drawn again at its true size a moment after the fingers lift. Under ⋯: **Contrast** (Normal, Stronger, Strongest: a gamma curve that darkens greys and keeps white and black), **Dark text as black** (on unless turned off: nearly grey pixels are shown neutral and darker, so tinted dark text is drawn black at the screen's 300 ppi instead of as dithered colour at 150 ppi; clear colours keep their colour), and **Trim margins** (each page fitted to its printed area plus a small border, with ink counted as printed). A page turn and the refresh after a zoom use the REGAL update: little flash and no ghost. At normal size the page is drawn on whole pixels. The tablet's own per-app display settings were tried first and did very little, since the page is a picture to them. Left out: inverting dark slides, removing coloured backgrounds, reflowing text, and per-document settings.

Nothing else is permanently on screen except a small page indicator with **‹ and › buttons** on either side of it (for example "‹ 4 / 32 ›").

Orientation: **follows the tablet** (owner's decision, 2026-11-09). The design first locked portrait, but Android 16 on the Note Air 6 C overrides an app's orientation lock on large screens, and its opt-outs have no effect. The app handles a turn correctly: the page refits and strokes stay in place. To keep the screen upright, the owner uses Boox's own rotation lock. Landscape slides are still best handled by rotating the PDF pages (section 6.4).

Navigation:

- **Zoom stays through a page turn** (owner's decision, 2026-11-12): turned with ‹ ›, the volume buttons or a push past the edge, the new page is shown as zoomed, at the same place on the page, for slides laid out alike; a push past the bottom lands at the next page's top, past the top at the previous page's bottom. "Keep zoom when turning" under ⋯ turns it off; then a turn shows the whole page, as it did before. "Go to page" and a held arrow always show the whole page.
- **One page at a time** (owner's decision, 2026-11-09, replacing the continuous scroll). The page is fitted to the screen, and **‹ ›** in the strip go to the previous and next page. Tapping the page number opens a small "Go to page" box; ‹ and › are greyed out on the first and last page (lightweight additions the owner allowed, 2026-11-09).
- **Two fingers pinch-zoom. When zoomed in, one finger moves around the page, and pushing on past the page's top or bottom edge turns the page.** At normal size a one-finger swipe does nothing, so pages only turn on purpose. The pen never moves the page, and a finger never draws.
- A resting palm or hand must neither draw nor move the page.

### 6.3 Tools

**Pen.** Black, pressure-sensitive line weight, a single style.

**Highlighter.** A wide, see-through stroke that never hides the text under it, in yellow. One colour only.

**Stroke eraser.** Touching any part of a stroke removes the whole stroke. This applies to pen strokes, highlighter strokes, and pasted snips. The page is drawn again, with one refresh, once the eraser rests: 0.4 s after the last erase and with button 2 let go or the pen lifted away, and 3 s after a run's first erase at the latest (owner's decision, 2026-11-12: one refresh for a run of erases, not one each).

**Lasso select.** Draw a loop. Every ink stroke and pasted snip inside the loop gets selected. Printed PDF content is never selected. A tap with the lasso selects the one stroke or snip under it, the top one where they overlap; a tap on nothing selects nothing (owner, 2026-11-12). The selection can be:

- dragged to move it,
- resized with corner handles (proportional scaling), which the owner does very often,
- copied, then pasted elsewhere **in the same document** (another page or the same page),
- deleted.

**How copy, delete, and paste are reached (designer's call).** While something is selected, a small action bar appears next to the selection with **copy**, **duplicate** (a copy a little down and right of the original, which becomes the selection, ready to drag; owner, 2026-11-12) and **delete**, and it disappears when the selection is cleared. A **Paste** button sits on the island, greyed out until something is copied or snipped, so the tools never move (owner, UI review decision 3, 2026-11-12; before, it appeared only after a copy and shifted the tools). Tap it, then tap the page with the pen where the content should land. The clipboard holds one item and lives only for the session.

**Snip.** A separate tool from lasso. Drag a rectangle to capture that region **as a picture**, including the printed PDF content and any ink on it. Paste it onto another page in the same document. Once pasted it acts as a single image object: it can be moved, resized, deleted, and lasso-selected, but not edited stroke by stroke.

**Pen side buttons.** Button 1 switches to lasso select and button 2 to the stroke eraser. Each is active **only while held**, and releasing returns to whatever tool was active before (normally pen or highlighter). The mapping is fixed, with no swap: the tablet reports button 2 as the pen's eraser end (owner-approved 2026-11-09, see [probe results](../probe-results.md)). A button counts only if it's held when the pen touches down. A press partway through a stroke takes effect on the next stroke, because that's how the pen reports it (owner-approved 2026-11-09).

**Undo and redo** cover everything in the current session: strokes, erases, moves, resizes, pastes, deletes, rotations.

### 6.4 Page rotation

Typical use is once per document: a deck of landscape slides gets rotated so it fits the way the tablet is held.

- **Rotate all pages** and **rotate this page**, each in 90° steps.
- The rotation is saved into the PDF (the page's rotation property), so it looks the same in other viewers.
- Ink already on a page turns with the page.
- The first time a file is opened, if most of its pages are sideways and the tablet is held upright, the app offers "Rotate all pages?" once (owner, 2026-11-12). Whatever the answer, it isn't asked for that file again.

### 6.5 Added pages

Owner's decisions, 2026-11-12 ([pages plan](../plans/2026-11-12-pages.md)). For working that doesn't fit on a slide.

- **Add a dotted page after this one**, from ⋯ on any page, or **+** in place of › on the last page. The new page is an ordinary page of the PDF, drawn with plain PDF drawing, so every other viewer shows it. It is the same size and way up as the page it follows: dots 5 mm apart, about 0.4 mm across, mid grey.
- **It can't be undone with Undo, and it empties the undo list.** Every later page's number changes, and the undo list is kept by page number. Instead, ⋯ shows **Remove this added page** on a page the app added, never on the PDF's own pages. It asks first if the page has writing on it.
- **It saves the file at once.** Everything written so far goes into the PDF first, then the page is added and the file saved the usual safe way. If the file can't be saved into right now (changed elsewhere, or a damaged file waiting for the owner's answer), the page isn't added and a message says why.
- While the page is being added or removed, the pen and every action that names a page wait, and a short message says what is happening ("Adding a page…"): about a tenth of a second for a small file, longer for a big one, since the whole file is written again.
- A stroke the pen hands over just as a page is added still goes to the page it was written on: each stroke carries the page numbering it began under, and the session moves it to that page's new number.
- On the last page, + ignores a tap in the first 0.6 s after the page arrives, so a quick second tap on › can't add a page by mistake.
- This is not blank notebooks (section 3): pages are added to an existing PDF.

## 7. Storage and saving: never lose writing

### 7.1 Where ink lives

- Ink is stored **in the PDF file** as standard annotations: pen and highlighter strokes as ink annotations (the highlighter translucent), pasted snips as image or stamp annotations. Other PDF viewers show them, can hide them, and the original page content is untouched.
- The app must reopen its own strokes as **editable strokes with pressure intact**, not just as pictures. Standard ink annotations don't carry per-point pressure, so the builder will likely need to store the app's own stroke data alongside each annotation (for example in a private key on the annotation) while also writing a normal appearance that other viewers render correctly. How exactly is the builder's call.
- Annotations the app didn't create (made in other apps) must be kept intact on save. Showing them is fine; editing them is not required.

### 7.2 Autosave

- No save button. Ink is written into the PDF automatically about ten seconds after the pen stops (owner's decision, 2026-11-12; three seconds at first, which rewrote the whole file at every pause to think), when leaving the document, and when the app is backgrounded. Until then it's safe in the journal (section 7.3).
- Saving never blocks or stutters the pen. It runs in the background.

### 7.3 Crash safety

- **Every stroke must survive a crash, kill, or power loss from the moment the pen lifts**, even before the PDF has been rewritten. The expected approach: append each change to a small journal in the app's private storage immediately, and replay it on next open if the PDF save didn't complete. Clear the journal once the PDF save is confirmed.
- **The PDF on disk must never be left half-written.** Write the new version to a temporary file in the same folder, flush it to disk, then atomically replace the original. Temporary files must not show up in the library grid, and stale ones from a crash must be cleaned up.
- **Damaged files: warn first** (owner-approved 2026-11-09). MuPDF quietly repairs a damaged PDF when it opens it, and a save would then replace the original with the repaired version, losing anything it couldn't recover. So when a file needed repair, the app tells the owner before anything is written to it. It offers two choices: **save into this file anyway**, or **keep the original and save my writing to a copy**. The copy is named like a conflict copy (section 7.4). Until the owner chooses, strokes are still kept safe in the journal.

### 7.4 Conflicts (file changed on disk while open)

The app records the file's identity (for example its size, modified time, and/or a hash) when it opens it, and checks again before every save.

- **Changed on disk, no unsaved writing in the app:** reload quietly.
- **Changed on disk, and the app has writing the disk version doesn't have:** **keep both.** Leave the disk version as it is, and save the app's version next to it as `<name> (my edits YYYY-MM-DD).pdf` (add a time or counter if that name exists). Tell the owner briefly what happened. The naming format is a design-time call and can be adjusted.
- **After a conflict copy is made, the open document switches to the copy** (owner's decision). All further strokes, autosaves, and undo history continue in the copy, so a session's writing stays together in one file.
- **Crash replay onto a changed file:** if the journal holds strokes that never reached the PDF, and the PDF has changed on disk since, treat it as a conflict. The strokes never go into the new disk version. They are put on a copy of it, saved beside it as `(my edits …)`, and that copy is then the open document. (Owner's decision, 2026-11-13, replacing "a copy of the version they were drawn on": that version is gone by then, and keeping it would mean a private copy of every open PDF after each save.) Strokes on a page the new version no longer has can't be placed: they are kept aside, and the owner is told how many.
- **Changes are looked for** at every save, and each time the writing screen comes back in front.
- **When writing isn't reaching a file** (a save that keeps failing, the tablet's storage full, a change that couldn't be written), the strip says "Not saved" in place of the file's name for as long as it lasts, and a tap there says why.
- Never silently discard either version.

## 8. Room for later (design for, don't build)

- **Blank notebooks:** the page and ink model shouldn't assume every page comes from an existing PDF. A notebook could simply be a PDF of blank or lined pages that the app creates.
- Partial eraser, more colours, cross-document paste: don't paint the code into a corner, but add no hooks or settings for them now.

## 9. Known risk: verify first

**It's unknown whether the Note Air 6 C reports both pen side buttons to third-party apps separately**, and whether they arrive while the Onyx SDK's fast-ink mode is active. Android has standard codes for a stylus's primary and secondary buttons, but Boox firmware or the SDK may intercept or merge them.

The first piece of work should be a tiny throwaway test app the owner installs that just shows which button is pressed, both in normal touch handling and with the Onyx raw-drawing mode on. The outcome goes back to the owner before the main build relies on it. If the buttons can't be told apart, that's a design change the owner needs to decide on (process rule, section 5).

A similar early check is worthwhile for palm rejection and finger-vs-pen separation while raw drawing is on.

**Outcome (2026-11-09):** the test was run, and the buttons can be told apart in both modes. The results and the decisions they led to are in [probe-results.md](../probe-results.md).

## 10. Left to the builder

These are yours to decide. Report the main choices to the owner in plain words, and get approval for anything that's architectural.

- **PDF library: decided, MuPDF** (owner-approved 2026-11-09). It must render fast on this hardware, read and write annotations and page rotation, and preserve foreign annotations. MuPDF does all of this in one library. PDFium was the alternative, but it has no maintained Android wrapper that writes annotations. Android's own PdfRenderer only gains annotation editing in API 36.1, and the tablet runs 36.0. MuPDF is AGPL, so the app is AGPL-3.0 and its source is published. A quick speed check on the tablet comes first in the main build.
- **File access method** (All files access vs the Storage Access Framework), as long as in-place editing, atomic replace, and change detection all work.
- The exact format for stroke data stored inside annotations.
- The journal format and how often background saves run.
- Snip resolution, and paste placement (where on the page a paste lands).
- How change detection works (file observers, rescans on resume, or both), as long as the grid stays in step with the disk.
- Thumbnail size and caching details.
- Pressure curve and stroke smoothing.
- Icons and exact sizing of the top strip.
- Test strategy and project structure, within the readability rule.

## 11. Decisions made by the designer, not the owner

The owner can overturn any of these:

- Ink stored as PDF annotations rather than a sidecar file (the owner preferred a separate layer and was flexible).
- Highlighter colour is yellow.
- Lasso select also picks up pasted snips, not just strokes.
- The "last page" position is kept in app-private storage.
- The conflict copy naming format.
- Rotation in 90° steps, from the page menu in the toolbar.
- ~~Pages scroll vertically and continuously.~~ Replaced by the owner on 2026-11-09: one page at a time (section 6.2).
- Copy and delete live in a small action bar next to the selection, and Paste sits on the island, greyed out until there's something to paste (owner, 2026-11-12). You place a paste with a pen tap.
- After a crash, strokes that never reached the PDF are replayed into a "my edits" copy if the file changed on disk in the meantime.
- On the writing screen (2026-11-12): the progress line's size (a 4 px line, 3 px ticks); 14 points for how far a duplicate lands; 6 points as the most a lasso stroke may span and still be a tap; 1.2 wide to 1 tall as "sideways"; the refresh of a zoomed page turn waits for the sharp picture, so it comes as one.
- For added pages and fresh attempts (2026-11-12): the dot spacing, size and grey; an added page is marked with a private key on the page so only those can be removed; the `(attempt N)` naming; a fresh attempt keeps added pages and other apps' marks.
- In the library (2026-11-12): three PDFs as the most a folder may hold and still be opened out; the eight course colours and how a course gets one; the look of the progress bar, the writing mark and the list rows; the Continue picture's size (96 dp tall); a file's writing is looked for in its first 300 pages.
- ~~Portrait orientation is locked.~~ Replaced on 2026-11-09: the screen follows the tablet, because Android 16 overrides the lock (section 6.2).
