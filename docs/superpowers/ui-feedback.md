# UI feedback for a UI/UX session

Collected from the owner's use of the app on the tablet. Nothing here is decided or planned yet; it waits for a session focused on the UI and UX.

## Text and sizes

- **Text on the start page (the library) is huge** (owner, 2026-11-10). Found: the tablet's system font size is set to 130% (`adb shell settings get system font_scale` gives 1.3), and the app sizes all its text in `sp`, which follows that setting. To decide: should the app follow the system font size, follow it only partly, or use fixed sizes?
- **The Paste bubble is enormous** (owner, 2026-11-10). It appears on a finger long-press once something is copied (`WritingActivity.offerPaste`): 20 sp text with 14 dp and 28 dp padding, so the font size above makes it bigger still. Its size should match the strip's buttons.

## Copy and paste

- **Copy gave no sign it had worked**: the only change was the Paste button appearing in the strip. A toast now says "Copied. To paste, hold a finger on the page, or tap Paste." after Copy and after a snip. To review: whether a toast is the right signal, and its wording.

## Strip

- **Every tool tap flashes the whole screen once.** It's the price of showing the tapped tool while fast-ink is on: Onyx stops passing the app's drawing to the panel for the whole app, and letting it through refreshes the screen. The owner chose the flash over a strip that only catches up later (2026-11-10). It's not so bad in the tablet's high refresh rate mode. Worth another look together with the "getting the buttons out of the way" ideas in [TODO.md](../../TODO.md).
- **Two island looks are still on trial** (black, or white with an outline), switched from ⋯. Once one is chosen, the other and the menu item go.

## Page

- The area beside the page is now shaded light grey, so it's clear where the page, and writing, ends (owner, 2026-11-10). To review: the shade.
