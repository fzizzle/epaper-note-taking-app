# Owner check: search by name

For the library search of 2026-11-17 ([design](../docs/superpowers/specs/2026-11-17-library-search-design.md)). Use the real library, or a copy with a few folders. Nothing here changes your files except where a step says so (use a copy of a PDF for the rename).

Tick each box when it works. Write down what you saw when it doesn't.

## 1. Finding by name

1. [ ] **S1. Half a name.** Tap the magnifier in the top row. Type the first half of a PDF's name.
   - **Expected:** that PDF is in the list, with its folder shown. Other PDFs with those letters in their names are too.
2. [ ] **S2. Two words, other order.** Type two words from a long name, the second one first.
   - **Expected:** the same PDF is found.
3. [ ] **S3. An accent.** Search for a name that has an accent, typed without it (for example `ecole` for `école`). Then the other way round.
   - **Expected:** found both ways. Capitals don't matter.
4. [ ] **S4. Nothing there.** Type something no PDF has in its name.
   - **Expected:** a plain "nothing found" line. No crash, no endless "Checking".
5. [ ] **S5. A long list.** Search for one letter, such as `a`.
   - **Expected:** the list fills, with a note above it saying it shows 200 of the total. It scrolls and nothing stalls.

## 2. Files that change

6. [ ] **S6. Copied from the laptop.** Take the memory card out, put it in the laptop, copy a PDF with an odd name into a library folder, and put the card back. Search for the new name.
   - **Expected:** it is found, after at most a short "Checking for changes…" line.
7. [ ] **S7. Card out while Jot is in the background.** Press Home (Jot stays open in Recents). Take the card out, copy a PDF onto it from the laptop, put it back, and return to Jot. Search for that PDF.
   - **Expected:** it is found.
8. [ ] **S8. Pushed by the sync.** With Jot open, push a PDF to the library with the sync. Search for it.
   - **Expected:** found at once.
9. [ ] **S9. A rename in Jot.** Rename a copy of a PDF in Jot. Search for its new name, then its old one.
   - **Expected:** the new name finds it at once. The old name doesn't.

## 3. Going back

10. [ ] **S10. Back from results.** Search, then press Back.
    - **Expected:** you are in the library where you were.
11. [ ] **S11. Back from a document.** Search, tap a result, then press Back in the document.
    - **Expected:** you are back at the results, with your search still there. Back again leaves the search.

## 4. Refreshes and speed

12. [ ] **S12. Refreshes.** Type, delete letters and open and close the search a few times.
    - **Expected:** no more full-screen flashes than elsewhere in the library. Tell me anything that refreshes more than it used to.
13. [ ] **S13. Speed.** Use the library as you normally do: open folders, scroll, Refresh.
    - **Expected:** nothing slower than before. Tell me anything that is.

## 5. Preview while typing

14. [ ] **P1. Matches as you type.** Tap the magnifier and type a few letters of a PDF's name, then stop.
    - **Expected:** within about half a second, the matching PDFs show under the box, each with its folder, 5 rows at a time. With more than 5, a scroll bar is on the right (it doesn't fade) and the box scrolls through them all, up to 200. Past 200, the line under the box says "Showing 200 of …".
15. [ ] **P2. One letter.** Clear the box and type a single letter.
    - **Expected:** nothing previewed. A second letter brings the preview.
16. [ ] **P3. Nothing there.** Type something no PDF has in its name.
    - **Expected:** "Nothing found." under the box.
17. [ ] **P4. Tap a match.** Tap one of the previewed PDFs.
    - **Expected:** the dialog closes and that PDF opens. Back returns to the library folder you were in. The magnifier then has your typed words filled in.
18. [ ] **P5. Search still works.** Type, then press Search.
    - **Expected:** the full results list, as before.
19. [ ] **P6. Refreshes.** Type quickly, pause, type again, delete letters.
    - **Expected:** no flashing; only the preview area under the box updates, once per pause, not at every key. The dialog doesn't jump or change size.
20. [ ] **P7. Last search filled in.** Search for something, go Back, tap the magnifier again.
    - **Expected:** the old words are there, selected, with their matches already previewed.

## Questions

- Are 5 preview rows right, and is the pause before they show too long or too short?

- Are 200 results enough, or did you want more?
- Did you wait on "Checking for changes…" long enough to notice?
