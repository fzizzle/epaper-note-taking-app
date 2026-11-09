# Writing screen: owner session results

Session: 2026-11-09, on the Boox Note Air 6 C, mostly on a test copy of a 125-page lecture (`Documents/E-paper Notes test/PWT test copy.pdf`). Checklist: `notes/TESTING-writing.md`.

## What the owner checked

| Check | Result |
|---|---|
| W1. Pen feel | Fine and smooth. The pen widths and pressure curve were left as they were. |
| W2. Highlighter | Works. Its dark blotches were fixed during the session (see below). |
| W3. Zoom | Works, after three fixes (below). The full-screen flash after zooming is "a bit weird but fine for now". |
| W4. Palm | Fine. |
| W5. Safety (app swiped away right after writing) | All strokes were back on reopening. |
| W6. Boox's own reader | The ink shows there: "good and fast". |
| W7. Reopening | Comes back on the same page. |
| W8. Turning the tablet | The screen turns. Android 16 overrides the lock, so the design changed (below). |

## Problems found and fixed

1. **Strokes cut short and painted over.** A redraw one second after each stroke paused fast-ink. If the next stroke had already started, Onyx force-released the pen (`EpdShapeHandler pen force release` in the system log). Now nothing redraws while the owner writes. The page view keeps its own copy of new strokes, and fast-ink is never paused while the pen is down.
2. **Strokes disappearing at random.** Pausing fast-ink showed the page view's last picture, which could be older than the latest strokes. Every pause now redraws straight away.
3. **Strokes vanishing when zooming.** A rendering kept from an earlier zoom, made before the latest strokes, was reused without those strokes on top. Each rendering now records how many strokes it contains (`InkOverlay`), and newer ones are drawn over it.
4. **Highlighter blotches.** Each piece of a stroke was filled separately, so see-through yellow got darker where pieces overlapped. Each stroke is now filled once, in the PDF and on screen.
5. **Larger, skewed strokes while zooming.** 155 screenshots taken over a minute showed the app's own picture was always right. The ghosts were fast-ink's leftover copy on the e-ink panel. A full e-ink refresh after any gesture that moved the page wipes it.
6. **Start screen.** Reopening the app landed on the long list of courses, and its top row sat under the status bar. The list now starts with "▶ Continue: <last file>" and stays below the status bar. It's a temporary screen until the library in plan 4.

## Design changes the owner made

- **One page at a time** with ‹ › buttons replaces the continuous scroll. When zoomed in, one finger moves around the page, and pushing past the top or bottom edge turns the page (spec §6.2).
- **The screen follows the tablet's rotation.** Android 16 on this tablet ignores the portrait lock, and its opt-outs have no effect. The app handles a turn correctly, and the owner uses Boox's rotation lock when wanted (spec §6.2).
- **Damaged files:** warn before saving into a file MuPDF repaired (spec §7.3). This was decided before the session.

## Numbers

Smaller strokes (Task 1): about 4 KB per long stroke, down from about 23 KB. Adding a stroke takes about 12 ms, and saving a 14 MB lecture about 0.11 s (`speed-check-results.md`).

## Left for later

- A gentler refresh after zooming (the flash).
- A screen-recording test of fast writing (the owner asked for one).
- The occasional ~2 s wait before a pinch after a stroke. In the logged tries, fingers arrived within 0.1 s, so this is probably Boox's palm rejection. It needs another look if it comes back.
- Pen buttons: button 1 for lasso and button 2 for the eraser come in plan 3.
