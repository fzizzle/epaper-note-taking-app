# Review fixes: owner checklist

About 15 minutes. These check the bug fixes from the [review of 2026-11-10](../docs/superpowers/review-2026-11-10.md). Use a test copy of a PDF, not an original.

The laptop tests (265) and the tablet tests (68) all pass on this build, and it is installed on the tablet. On the test copy, old strokes were drawn again once after opening and not again on the next opening.

## What changed

- Highlighter and pen strokes no longer have a small hole at each end and corner. Old strokes are drawn again, once per file, a few seconds after it opens.
- After moving a zoomed page with one finger, the ink lands under the pen.
- Fingers are ignored while the pen is writing, so a palm can't cut a stroke or move the page.
- The pen can't write in the margin beside the page.
- A zoomed slide that is shorter than the screen no longer turns the page when dragged up or down.
- A very tall first page gets a small, narrow thumbnail.
- A stroke begun in the instant the screen updates (just after an erase or undo) is kept.
- A highlighter corner drawn fast, with its points far apart, is solid.
- If the tablet's storage is full, strokes are still kept and saved, and a message says so.

## Checks

- [ ] **R1. Stroke ends.** Highlight a line of text with a zig-zag at the end. No pale gaps at the ends or corners. Leave and reopen: still none.
- [ ] **R2. Old strokes.** Open a file with highlights made before this build. Within a few seconds their gaps disappear. Leave, reopen: no second redraw.
- [ ] **R3. Move, then write.** Pinch in, move the page with one finger, write a word. The ink is under the pen. Leave and reopen: it is where you wrote it.
- [ ] **R4. Short page.** On a landscape slide, pinch in a little (the slide still shorter than the screen) and drag up and down. The page doesn't turn. On an upright page zoomed in, pushing past the top or bottom still turns it.
- [ ] **R5. Palm.** While writing a long stroke, rest your palm on the screen. The stroke isn't cut and the page doesn't move. Lift the pen: two fingers zoom as before.
- [ ] **R6. Erase and carry on.** Erase a word and start writing again straight away. Do it several times. No stroke, or start of a stroke, goes missing.
- [ ] **R7. Dialogs.** Tap the page number, then Cancel. Open ⋯ and close it. Each time the screen is clean (no leftover box) and the pen writes at once.
- [ ] **R8. Margin.** At normal size, try to write in the white band beside the page. Nothing appears.
- [ ] **R9. Leave and kill.** Write a line, press ←, and swipe the app away at once. Reopen the file: the line is there, and there is no message about unsaved writing being kept.
- [ ] **R10. Tall page.** If you have a PDF with one very long page, its tile shows a narrow picture, and the grid doesn't keep flickering. (A tall picture made by an older build is replaced the first time its tile is shown.)

When done, the results go in `docs/superpowers/review-fixes-results.md`.
