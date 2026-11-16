# Owner check: writing screen and library fixes

For the fixes of 2026-11-16 (review rows B1 to B7, C1 to C3, D1 and D2). Use a copy of a PDF in a scratch folder, never a real workbook. Most of this needs the pen, so it could not be tried over adb.

## 1. Undo after dark mode

1. Open the scratch PDF and write a word.
2. Pull the notification shade down and switch dark mode on, then off again (or ask Claude to switch it over adb: the tablet's own dark-mode switch may not be the one Android uses). Go back to Jot.
   - **Expected:** the page stays as it was. The word is still there, and undo still takes it away.

## 2. Coming back to a "(my edits)" copy

1. Do C1 in [TESTING-conflicts.md](TESTING-conflicts.md) until the strip shows the "(my edits …)" name.
2. Press Home, and tell Claude. Claude clears Jot from memory over adb, as Android does when it needs the room.
3. Open the Recents screen and tap Jot.
   - **Expected:** the "(my edits …)" copy is on screen, with all your writing. It is not the changed original.

Claude also checked this over adb on 2026-11-16, with pasted snips instead of handwriting: the copy came back.

## 3. A keyboard or page-turner

Skip this if you have neither.

1. Open the scratch PDF, write a word, then connect a Bluetooth keyboard or page-turner.
   - **Expected:** the page stays as it is. No restart, and undo still takes the word away.

## 4. Pasting and moving stop at the page's edge

1. Snip something and arm Paste. Tap in the grey band beside the page.
   - **Expected:** the snip lands on the page, flush against the edge.
2. Lasso some writing and drag it past each edge in turn: left, right, top, bottom.
   - **Expected:** it stops at the edge each time and can still be picked up again.
3. Make a selection larger by pulling a corner out past the page.
   - **Expected:** it covers the page. Nothing breaks, and you can still pick it up.

## 5. A snip over writing

1. Write a word. Snip something and paste it over the word.
   - **Expected:** the snip hides the word on screen.
2. Now write a word over a snip.
   - **Expected:** the word shows on top.
3. Write a word, paste a snip somewhere else on the page, then draw highlighter over the word.
   - **Expected:** the highlighter looks as it did before: the word stays black under it.

## 6. The notification shade

1. Pull the notification shade down. Write on it with the pen.
   - **Expected, tell me which:** a line follows the pen on the shade, or nothing shows.
2. Close the shade.
   - **Expected, tell me which:** the line is on the page, or it is gone.
3. Optional: switch on the tablet's floating button, tap it, and see if a menu opens over the page. Write on that menu too, if it does.

## 7. Renaming onto a name that's taken

1. In a scratch folder, put two PDFs: one called "Resume.pdf", and one with an accented name, "Résumé.pdf" (typed from the Mac, if you can).
2. Rename or move one onto the other's name, written in another case ("RESUME.pdf") or with the accent the other way.
   - **Expected:** no file is replaced or lost. Tell me whether Jot refused, or made a second file that looks the same.

## 8. A very long name

1. Give a scratch PDF a name of about 80 Chinese or 120 Cyrillic characters, open it and write a word.
   - **Expected:** the word is saved. Leave and come back: it is there.

## 9. Refreshes

1. Use the pen, the menus, the page pad and the strip for a few minutes as you normally do.
   - **Expected:** no more flashes than before. Closing a menu or the page pad resumes the pen once, with at most the usual flash.
   - Tell me anything that refreshes more than it used to.

## Questions

- In 6, does the pen mark the page through the shade? If it does, I'll make the pen pause there, at the cost of one refresh.
- Does the floating button (6, step 3) open a menu over the page?
- Anything that looked different from before?
