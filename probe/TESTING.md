# Pen Probe: test checklist

This checks whether your Boox Note Air 6 C tells apps which pen side button you're pressing. Allow about 10 minutes.

## Before you start (one time)

1. On the tablet, open **Settings → About** and tap the build number 7 times. This turns on Developer options.
2. In **Developer options**, turn on **USB debugging**.
3. Connect the tablet to the laptop by USB, and accept the "Allow USB debugging?" prompt on the tablet.
4. On the laptop:

   ```sh
   source ~/.local/opt/android-env.sh
   cd ~/.t3/projects/epaper-note-taking-app/probe
   adb devices              # the tablet should be listed
   ./gradlew :app:installDebug
   ```

5. Open **Pen Probe** on the tablet.

## The tests

Tap **Mark** before each item, so its section is easy to find in the log. Do every item twice: once with **Fast-ink: OFF**, then tap the toggle and do it again with **Fast-ink: ON**. Fast-ink freezes the screen while it's on, so tap **Show log** to see what was recorded.

- [ ] **M1. Basic writing.** Write a few words. With fast-ink off, the big "Buttons:" line and the log update as you write. With fast-ink on, the ink appears quickly. Then tap **Show log**.
- [ ] **M2. Hovering.** Hold the pen just above the screen, without touching it. Press and release **button 1**, then **button 2**.
- [ ] **M3. Hold, then write.** Hold **button 1**, then touch down and draw a line. Lift the pen, then let go of the button. Do the same with **button 2**.
- [ ] **M4. Mid-stroke.** Start drawing a line. Press **button 1** partway through, then release it before lifting. Do the same with **button 2**. If your pen has an eraser end, write with that end too.
  Then tap **Side-erase** so it reads OFF, turn fast-ink back on, and repeat M3 and M4 once more.
- [ ] **M5. Hand and fingers.** Write normally with your palm resting on the screen. Then scroll with one finger, and pinch with two.
- [ ] **M6. Anything odd.** Note anything Boox did by itself. For example, a menu popped up when you pressed a button, ink appeared where your palm was, or the app closed. If it closes, note which button you'd pressed and reopen it.

Jot a word or two about M5 and M6, because those are things the log can't show.

## Afterwards

Pull the log over to the laptop:

```sh
adb pull /sdcard/Android/data/com.fzizzle.epapernotes.probe/files/probe-log.txt
```

Then tell Claude it's done. The log, plus your notes for M5 and M6, go into `docs/superpowers/probe-results.md`.
