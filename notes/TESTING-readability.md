# Easier-to-read pages: owner checklist

About 15 minutes. This is the last step of the [readability plan](../docs/superpowers/plans/2026-11-10-review-readability.md) (Task 6). Each part was already tried on its own; this checks them together, on the build from the `review-readability` branch. Use a test copy of a PDF, not an original.

## What changed

Only the picture on screen changes. The PDF, your ink, snips and the library's thumbnails are untouched, and other apps see no difference.

- **Sharp zoom.** After a zoom or a move, the part of the page on screen is drawn again at its true size, a moment after your fingers lift. No setting: always on.
- **Contrast**, under ⋯: Normal, Stronger or Strongest. Darkens pale text and thin lines; white stays white. Remembered for all documents.
- **Dark text as black**, under ⋯, with a tick, **on unless turned off**. Nearly black text that is tinted (slate, dark blue-grey) is shown black, so the colour screen draws it at its full 300 ppi instead of 150. Clearly coloured things (green and blue formulas, the highlighter) keep their colour.
- **Trim margins**, under ⋯, with a tick. Each page is fitted to its printed area plus a small border, not to the whole sheet. Ink in the margin counts as printed, so nothing you write is cut off.
- **Cleaner page turns.** A page turn, and the refresh after a zoom, use REGAL: little flash and no ghost of the last page.
- **Whole pixels.** At normal size the page is drawn exactly on the screen's pixels, not half a pixel off, so text is a little crisper.

## Checks

- [ ] **R1. Sharp zoom.** On a page with small text, pinch to about 3×. Is the text crisp within a moment of lifting your fingers? Write a word: does it land under the pen? Move the page with one finger and zoom back out: is a blurry or misplaced patch ever left behind?
- [ ] **R2. Contrast.** On a pale deck, try Normal, Stronger and Strongest in turn. Is each step easier to read than the last, with no step making the page worse? Does the highlighter still look yellow, and is text under a highlight still readable?
- [ ] **R3. Snip at Strongest.** With Contrast on Strongest, snip part of a slide and paste it on another page. Does the pasted picture look like the page around it, not darker than it (it should not have the contrast applied twice)?
- [ ] **R4. Dark text as black.** In your workbook, turn the tick off and on. With it on: are headings and body text sharp and black? Are green and blue formulas still coloured, and the highlighter still yellow? Look closely at pale colours: very pale tints (the soft edges of formulas, pastel boxes) turn grey too. Does any page look wrong because of that?
- [ ] **R5. Snip with dark text as black.** With the tick on, snip a heading and paste it. Does the pasted snip keep the page's true colours (it looks like the page with the tick off)?
- [ ] **R6. Trim margins.** On a deck with wide white margins, turn Trim on. Is the page larger, with nothing cut off? Write right at the edge of the printed area, turn the page and come back: is the stroke still fully on screen? Try a blank page, and a page that is one full picture: are they shown whole?
- [ ] **R7. Trim, zoomed, turned.** With Trim on and zoomed in, turn the tablet, then rotate a page from ⋯. Does the page refit with no leftover offset, and does your writing stay in place?
- [ ] **R8. Page turns.** Turn pages on a text slide and on a dark slide. Is there a ghost of the last page, or a flash that bothers you? Can you write straight away after the turn?
- [ ] **R9. Whole pixels.** At normal size, is text as crisp as you'd expect? Does ink land under the pen at normal size and zoomed in?
- [ ] **R10. The PDF is unchanged.** Open the same test copy on the laptop afterwards. Does it look as before, apart from your ink? (No contrast, no darker text, no trimming.)

When done, the results go in `docs/superpowers/readability-results.md`.

## Known (2026-11-10)

- At Trim margins with a heavy trim, the page can be slightly soft at normal size until you zoom in a little (the picture is capped at 1.5× the screen width, and the sharp patch only comes above 1.05×).
- After a jump to a page not yet measured for trim, the page can show grey for a moment while its new fit is drawn. Writing still lands in the right place.
