# UI review fixes: owner checklist

About 10 minutes. These check the changes from the UI review of 2026-11-12 ([plan](../docs/superpowers/plans/2026-11-12-ui-review.md)). Look around your Workbooks freely, but do copy and paste in a test copy of a PDF, not an original.

## What changed

- PDF tiles leave out the part of the name every PDF in the folder shares, so a topic reads "answer key" and "workbook". Names without a dash, like "Lecture 1", stay whole.
- Folder tiles are a small folder mark instead of a big box, so a course's topics fit on one screen. The count sits clear of the name.
- The writing strip shows the open file's name next to ←. A long name is cut from the front, so its end shows.
- Paste is always on the island, grey until something is copied, so the tools never move.
- Menus have a black outline and black ticks. ⋯ has a line between rotating and the look settings. The tile you long-press stays grey while its menu is open. Teal is gone from fields and selections, and Go to page has room around its number.
- After a copy or snip, the message says how to paste the first three times, then just "Copied.".
- The Paste bubble (hold a finger on the page) is as tall as a strip button, with round ends.
- The pen's mark sits inside its black circle when the pen is picked.

## Checks

- [ ] **U1. Tile names.** In a topic folder, do the tiles read "workbook" and "answer key"? Long-press one and choose Rename: does it show the full name?
- [ ] **U2. Folder tiles.** In a course folder, do all topics fit on one screen? Is each count clear of its name, even under two-line names?
- [ ] **U3. File name.** Open a workbook. Is its name next to ←, readable, and clear of the tools? Tap a few tools: does it stay put?
- [ ] **U4. Paste's place.** Snip something. Did any tool move? Did Paste turn from grey to black?
- [ ] **U5. Long-press menu.** Long-press a tile. Does the menu have an edge, and is the tile grey? Close it with a tap outside: does the grey go?
- [ ] **U6. ⋯ menu.** Open ⋯. Is there a line between rotating and the look settings, and are the ticks black? Does Contrast's own menu have an edge too?
- [ ] **U7. Copy message.** Copy or snip four times. Is the message short from the fourth time? Hold a finger on the page: does the Paste bubble look like the strip's buttons?
- [ ] **U8. Pen mark.** With the pen picked, does its mark sit inside the black circle?
- [ ] **U9. Landscape.** Turn the tablet. Does all of the above still fit?

When done, the results go in `docs/superpowers/ui-review-results.md`.

## Still open

- **Erasing shows no trail while you drag with button 2, and each erase flashes.** Both depend on how Onyx draws ink, so they need their own plan (see TODO.md, Known bugs).
