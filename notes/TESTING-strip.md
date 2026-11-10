# Writing strip: owner checklist

About 10 minutes. These check the redesigned strip at the top of the writing screen. Use a test copy of a PDF, not an original.

## What changed

- The tools, undo and redo sit together on a capsule (the "island") in the middle of the strip. ← stays at the left, and ⋯ and ‹ 1 / 125 › at the right.
- The status bar is hidden while writing, so the strip sits at the very top with equal space above and below. Swipe down from the top edge to see the status bar for a moment.
- Tapping Highlighter, Eraser or Lasso now picks it out on the island right away, with one quick flash. Before, the screen never showed it, because fast-ink was running. Holding a pen button doesn't change the island (it would flash at every press).
- Button 2 now erases however you press it. Before, it only erased if it was already held as the pen came near the screen; pressed with the pen already hovering, it was ignored and the pen wrote.
- The island is white with a black outline; the picked tool is a black circle. (A black island was tried too; the owner chose the outlined one, 2026-11-10, and the ⋯ switch is gone.)

## Checks

- [x] **S1. Looks.** Which one do you want to keep? Owner: the outlined (white) island (2026-11-10).
- [ ] **S2. Tool taps.** Tap each tool in turn. Is the tapped one picked out at once, every time, with just one quick flash? Tap the tool that's already picked: nothing should flash. Write a stroke after each tap: is it the right tool, and does nothing go missing?
- [ ] **S3b. Button 2 erases.** Write a few words. Hover, press button 2, and drag over a word: does it go when the pen lifts? Try again pressing button 2 before bringing the pen near the screen. Let go and write: is it ink again?
- [ ] **S4. Write straight after.** Press a pen button and touch down at once, then write normally right after letting go. Does every stroke do the right thing, with nothing cut or lost?
- [ ] **S5. Undo and redo.** After a stroke, does Undo turn from grey to black on screen? After an undo, does Redo?
- [ ] **S6. Top of the page.** Write near the very top of the page, just under the strip. Does the ink land under the pen, with the status bar out of the way?
- [ ] **S7. Turn the tablet.** In landscape, does the strip still fit, with the island in the middle?

When done, the results go in `docs/superpowers/strip-results.md`.

## Open (2026-11-10)

- **Button 2 still a bit buggy** (owner). What exactly is still to be found out: the log shows it erasing.
- **Each erase refreshes the page** (to remove what was erased and the grey trail), and so does a finger or palm touch on the page even without moving. Both were already so before this branch. A quiet refresh without the pause was tried: it only exists in a flashing mode, so it was dropped.
