# Findings log

What was found while debugging on the tablet: causes, measurements and the tricks that got them. Newest first. Bugs and to-dos themselves live in [TODO.md](../../TODO.md).

## 2026-11-13: opened-out folders and thumbnails

Owner: opening the small folders out is expensive and a bit slow with all the thumbnails. The tablet's log (release build of main, be19a62) for the ML course, 11 topics of two PDFs each:

| Visit | Listing | Thumbnails drawn | Shown on screen |
|---|---|---|---|
| First, after the library design build | 260 ms | 22, about 30 ms each, 641 ms in all | in five goes (5, 6, 5, 5, 1 tiles) over 0.8 s |
| Back from a document | 26 to 266 ms | 1 (the file just written) | the folder 380 to 500 ms after the screen came back |
| CTM course, thumbnails cached | 52 ms | none | all 30 in one go |

- Why the first visit drew everything: thumbnails cached before the library design build have no `.facts` file beside them, so each was drawn again once to read its page count and writing mark (`ThumbnailLoader.load`). This happens once per file.
- Why five goes: the loader draws a tile in about 30 ms, so about four or five finish in each 120 ms gathering window (`TILE_BATCH_MS`), and each window is a refresh.
- Opening out doubles the PDF tiles in a course (each topic's two PDFs instead of one folder tile), and each one needs its thumbnail.
- Reading the page count and looking for writing cost 0 to 1 ms per file: not the slow part.

## 2026-11-12: owner session

### Button 2 does nothing when pressed close to the screen

Owner: button 2 "usually fails the first 2–3 attempts": no ink, no erase. It happens when button 2 is pressed while the pen already hovers close to the screen.

Raw pen events (`adb shell getevent -lt /dev/input/event5`, the pen is `onyx_emp_istaric_pen`):

| Case | Tool reported | Touch | Button 2 | Pressure (of 4095) |
|---|---|---|---|---|
| Writing | `BTN_TOOL_BRUSH` | `BTN_TOUCH` down | no | median 2651 |
| Button 2 pressed higher up (works) | `BTN_TOOL_RUBBER` | `BTN_TOUCH` down | no | median 445, max ~500 |
| Button 2 pressed close (fails) | `BTN_TOOL_BRUSH` | **none** | `BTN_STYLUS2` down | median 433 |
| Hovering | `BTN_TOOL_BRUSH` | none | no | 0 |

So in the failing case the pen presses on the screen with the button held, but never reports a touch and never turns into the eraser. Onyx's raw input sees no stroke, so it neither draws nor calls its erase callback. Android sends only `ACTION_HOVER_MOVE` events, with tool = stylus, `buttonState = 64` (`BUTTON_STYLUS_SECONDARY`) and pressure 0.07–0.13. Plain hovering reads exactly 0.

The erases that did reach the app all hit their strokes. The trails were placed correctly: the screen-to-page mapping was checked, and it is the same before and after the first erase.

Fix (branch `button2-hover-erase`): `ui/HoverErase.kt` turns hover samples with button 2 held and pressure above 0.02 into an eraser trail. The trail is handed over when the pressure drops, the button is let go or the pen leaves. A real touch cancels it. Owner: "works much more reliably".

### Every erase flashes the erased part of the screen

Owner, after the fix above. Not fixed; it's listed under known bugs in [TODO.md](../../TODO.md). The app log for each erase that hits, by either route:

```
erase page=0 hits=1 ms=32
render requested page=0 width=1742 version=7
rendered page=0 width=1742 version=7 of 7
show: pause fast-ink
resume fast-ink          (about 165–175 ms later)
```

The system log for the same moment shows `SurfaceFlinger: Post enabled!`, `HWEpdcManager: ### EUS, s 4 c 0`, then `Pid … State change to: toPause` and, about 165 ms later, `State change to: running`. The panel update during the pause from `showWithoutFastInk` is the flash. It's the same refresh as the "each erase refreshes the page" note in [notes/TESTING-strip.md](../../notes/TESTING-strip.md). It's seen more often now that button 2 always erases.

### The pen-width fix of PR #21 doesn't hold

Owner: the ink is thicker before picking another tool than after, so the live line is wider than the saved ink. The tablet's release build was checked to contain PR #21: `StrokeStyle.liveFountainWidth` is in the installed APK's dex. The pen's pressure range is 0–4095 (`getevent -p`, `ABS_PRESSURE max 4095`), matching `PenPoints.MAX_PRESSURE`. So the live line, drawn by the system, doesn't follow the SDK's `NeoFountainPenWrapper.computeStrokePoints` curve the fix was matched to. Screenshots can't capture the live line: the system draws it straight onto the panel.

**Measured with phone photos** of the same strokes before (pen selected, live line) and after a tap on Lasso (the app's copy). The phone moved between shots. The scale between them came from the printed step boxes: about 214 px wide in the first photo and 261–262 in the second, so 1.224×. Each line's thickness was taken as its darkness summed across the line, divided by full-ink darkness, the median over about 150–230 columns. That holds up under camera blur.

| Line | Live (photo 1 px) | Saved (photo 2 px ÷ 1.224) | Saved ÷ live |
|---|---|---|---|
| Under the formula | 6.30 | 5.48 | 0.87 |
| Under the sentence | 6.01 | 4.91 | 0.82 |
| Bottom line | 5.98 | 4.92 | 0.82 |

The strokes' saved pressures, read from the PDF (`/PNotesStroke`), have medians of 0.56–0.64, with 10–90% between 0.49 and 0.78: the owner's normal writing. So at that pressure, the system's live line is about 1.2× the SDK curve. There's no data yet for very light or very firm strokes.

Fix: the saved pen is now 1.2 × (0.8 + 2.4 p²) pt, so 0.96–3.84 pt. The live line keeps W = 2.4 × px/pt − 1 (`StrokeStyle.liveSpan`), so it doesn't change. Strokes saved earlier are drawn wider too, because the app draws every stroke from its pressure.

### Tricks

- Raw pen events can be **read** with `adb shell getevent -lt /dev/input/event5`. Only injecting them is blocked.
- The tablet's log buffer only holds a few minutes, so run `adb logcat -G 16M` before a session.
- To check what an installed release build contains: pull it (`adb shell pm path …`, `adb pull`), unzip the `classes*.dex` files and look with `~/Android/Sdk/build-tools/35.0.0/dexdump -d`. Release builds keep class and method names.
- Installing a debug build over the release build keeps the app's files, settings and All files access.
