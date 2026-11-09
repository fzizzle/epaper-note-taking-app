# Pen and touch probe: results

Test session: 2026-11-09, run by the owner with the Pen Probe app (`probe/`).

- Device: Boox Note Air 6 C (`ro.product.model=NoteAir6C`)
- Android SDK level: 36
- Firmware: `2026-10-16_17-05_4.3.1-rel_10162_eebc55a62`
- Pen: two side buttons, **no eraser end**

The owner's notes: nothing odd happened (no menus popped up, the app never closed). Palm rejection was tested in a short second session (16:29), with the palm resting on the screen while writing. The owner saw nothing wrong.

## Short version

| | Fast-ink off | Fast-ink on |
|---|---|---|
| Button 1, hovering | Seen (`STYLUS_PRIMARY`) | Seen (`STYLUS_PRIMARY`) |
| Button 2, hovering | Seen (`STYLUS_SECONDARY`) | Seen (`STYLUS_SECONDARY`) |
| Button 1 held, then write | Seen on every touch event | Seen on every touch event. Onyx erases instead of drawing if its side-button-erase setting is on |
| Button 2 held, then write | The pen reports as an **eraser** (`tool=ERASER`) | Same, and Onyx calls its **erase** callback |
| Button pressed mid-stroke | Not seen until the pen lifts | Same |
| Android events while fast-ink is on | n/a | Yes, all of them (touch, hover, buttons, fingers) |
| One finger, two fingers | Seen as `FINGER` | Seen as `FINGER`, and Onyx draws no ink for them |
| Resting palm | Two very short taps leaked through (20–35 ms) | Nothing at all: no events, no ink |
| Key presses from the pen | None | None |

**The buttons can be told apart, in both modes.** Button 1 is a normal button flag. Button 2 is wired as the pen's eraser: while it's held, the pen touches the screen as an eraser.

## 1. Can the app tell button 1 and button 2 apart?

Yes, in both modes, but they arrive differently.

**While hovering**, both buttons show up as the standard Android button flags on hover events.

```
16:19:07.782 HOVER fastInk=off tool=STYLUS action=ACTION_HOVER_MOVE buttons=STYLUS_PRIMARY ...
16:19:26.452 HOVER fastInk=off tool=STYLUS action=ACTION_HOVER_MOVE buttons=STYLUS_SECONDARY ...
16:20:02.513 HOVER fastInk=on tool=STYLUS action=ACTION_HOVER_MOVE buttons=STYLUS_PRIMARY ...
16:20:08.928 HOVER fastInk=on tool=STYLUS action=ACTION_HOVER_MOVE buttons=STYLUS_SECONDARY ...
```

**While touching**, button 1 stays a button flag (`STYLUS_PRIMARY`) on the touch events. Button 2 doesn't: the pen's tool type changes from stylus to **eraser**, and the button flag disappears. It only shows up again for a moment at the lift. This was checked by the owner holding button 2 on a pen that has no eraser end.

```
16:25:03.819 HOVER fastInk=off tool=ERASER action=ACTION_HOVER_ENTER buttons=none ...
16:25:07.038 TOUCH fastInk=off tool=ERASER action=ACTION_DOWN buttons=none ...
16:25:09.001 TOUCH fastInk=off tool=ERASER action=ACTION_UP buttons=STYLUS_SECONDARY ...
16:25:09.002 HOVER fastInk=off tool=ERASER action=ACTION_HOVER_ENTER buttons=STYLUS_SECONDARY ...
```

With fast-ink on, Onyx treats button 2 the same way. It calls its erase callback, with the `shortcut` flag false, because to Onyx this is the eraser end:

```
16:26:00.302 HOVER fastInk=on tool=ERASER action=ACTION_HOVER_ENTER buttons=none ...
16:26:00.447 ONYX_ERASE fastInk=on tool=- action=BEGIN shortcut=false ...
16:26:00.453 TOUCH fastInk=on tool=ERASER action=ACTION_DOWN buttons=none ...
```

With fast-ink on, button 1 depends on Onyx's **side-button-erase** setting (`enableSideBtnErase`):

- **On:** Onyx turns button 1 strokes into erase strokes, with `shortcut=true`.

  ```
  16:20:02.800 TOUCH fastInk=on tool=STYLUS action=ACTION_DOWN buttons=STYLUS_PRIMARY ...
  16:20:02.801 ONYX_ERASE fastInk=on tool=- action=BEGIN shortcut=true ...
  ```

- **Off:** button 1 strokes stay normal ink strokes, and the button flag still reaches the app.

  ```
  16:22:09.803 fast-ink ON (limit=Rect(0, 0 - 2480, 1664) sideButtonErase=false)
  16:22:13.015 TOUCH fastInk=on tool=STYLUS action=ACTION_DOWN buttons=STYLUS_PRIMARY ...
  16:22:13.017 ONYX_DRAW fastInk=on tool=- action=BEGIN shortcut=false ...
  ```

The pen never sent key presses. The log has no key events at all.

## 2. Is a button held before touch-down visible on the first touch?

Yes, for both buttons, in both modes. For button 1, `STYLUS_PRIMARY` is already on the `ACTION_DOWN` event (see above). For button 2, the tool is already `ERASER` while hovering, before touch-down.

The hover events also show the button **before** the pen lands, often 100–500 ms ahead:

```
16:19:21.063 HOVER fastInk=off tool=STYLUS action=ACTION_HOVER_ENTER buttons=STYLUS_PRIMARY ...
16:19:21.293 TOUCH fastInk=off tool=STYLUS action=ACTION_DOWN buttons=STYLUS_PRIMARY ...
```

**Pressing or releasing a button mid-stroke is not reported during the stroke.** No stroke in the log changed its button state partway through. In a few strokes the change shows up only on the lift event:

```
16:22:17.583 TOUCH fastInk=on tool=STYLUS action=ACTION_DOWN buttons=none ...
16:22:17.776 TOUCH fastInk=on tool=STYLUS action=ACTION_UP buttons=STYLUS_SECONDARY ...
```

So a stroke's tool is fixed when the pen lands.

## 3. Do Android events still arrive while fast-ink is on?

Yes. Touch, hover, button flags, tool type and finger events all keep arriving with fast-ink on, alongside Onyx's own draw and erase callbacks (see the lines above).

## 4. Palm and fingers

- **Palm, fast-ink on:** fully rejected. With the palm resting while writing, and also resting alone with no pen nearby, no finger or palm events reached the app, and Onyx drew only for real pen strokes. Every Onyx draw starts within 2 ms of a pen touch-down:

  ```
  16:29:44.922 ONYX_DRAW fastInk=on tool=- action=BEGIN shortcut=false ...
  16:29:44.930 TOUCH fastInk=on tool=STYLUS action=ACTION_DOWN buttons=none ...
  ```

- **Palm, fast-ink off:** mostly rejected. The palm caused two finger touches to the right of the writing, each 20–35 ms with no movement, so they looked like quick taps. One came 60 ms after the pen lifted. No ink and no scrolling resulted, because the probe ignores fingers.

  ```
  16:29:36.720 TOUCH fastInk=off tool=STYLUS action=ACTION_UP ...
  16:29:36.782 TOUCH fastInk=off tool=FINGER action=ACTION_DOWN ... x=1939 y=753
  16:29:36.803 TOUCH fastInk=off tool=FINGER action=ACTION_UP ... x=1939 y=753
  ```

- **Fingers:** they arrive as `FINGER`, separate from the pen, in both modes. Two-finger gestures arrive as two pointers.

  ```
  16:23:02.851 TOUCH fastInk=on tool=FINGER action=ACTION_DOWN ...
  16:23:48.851 TOUCH fastInk=on tool=FINGER action=ACTION_POINTER_DOWN(0) n=2 ...
  16:23:57.448 TOUCH fastInk=off tool=FINGER action=ACTION_POINTER_DOWN(0) n=2 ...
  ```

  With fast-ink on, finger touches produced no Onyx draw or erase callbacks, so fingers don't leave ink.

## 5. What this means for the design

**Pen buttons (§6.3), mostly fine as written.** The design wants one button for lasso and one for the stroke eraser, each active only while held. That works:

- **Button 2 → stroke eraser.** This is the pen's natural behaviour: holding it makes the pen an eraser, in both modes.
- **Button 1 → lasso.** The app sees it reliably on every touch. Onyx's side-button-erase must be turned **off**, or Onyx erases with button 1 instead.

Owner's decisions (2026-11-09), now recorded in the design (§6.3):

1. **Swapping the buttons (§6.3 says "easy to swap").** Button 2 always reaches the app as "the eraser end", and Onyx's fast-ink treats it as an eraser. Swapping, so that button 2 lassos and button 1 erases, should still be possible in the app's own logic. It goes against the hardware, though, and is likely to look odd in fast-ink. **Decided:** the mapping is fixed as button 1 = lasso, button 2 = eraser, and the swap requirement is dropped.
2. **Mid-stroke presses.** A button pressed after the pen has landed only takes effect on the next stroke. The design says "only while held", and that still holds as long as the button is pressed before the pen touches down. **Decided:** accepted.
3. **Lasso in fast-ink.** With side-button-erase off, Onyx draws normal ink while button 1 is held. The app will need to stop or hide that ink for lasso strokes. The hover events show button 1 before the pen lands, so the app has time to switch modes. That's an implementation detail, not a design change.

**Touch (§6.2), fine as written.** Pen and finger are cleanly separated in both modes, so "the pen never scrolls, and a finger never draws" is workable. The palm is fully rejected while fast-ink is on, which is the mode used for writing. The two short palm taps seen with fast-ink off mean the app shouldn't act on a single finger tap while the pen is near the screen, or just after it lifts. That's an implementation detail, not a design change.
