# MuPDF speed check: results

Run: 2026-11-09 (numbers from the final run, after the review fixes) on the Boox Note Air 6 C (Android 16, SDK 36.0), MuPDF 1.28.5, with two of the owner's real lecture PDFs. The check works on a copy in the app's cache folder, so the owner's files were never changed. The code is `notes/app/src/androidTest/java/com/fzizzle/epapernotes/check/SpeedCheck.kt`.

## The numbers

Pages are rendered at 1860 px wide, the full screen width. "Strokes" are 50 test strokes of 200 points each, all on page 1. That's more than a real page of handwriting usually has per stroke.

| | Pass mark | PWT "Les véhicules hybrides et électriques" (14.0 MB, 125 pages) | FLE "Cours 3 – B1 Léonard de Vinci" (17.4 MB, 45 pages) |
|---|---|---|---|
| Open | under 1 s | **9 ms** | **0 ms** |
| First page | — | 110 ms | 9 ms |
| Pages 2–10 (median) | under 300 ms | **77 ms** (slowest 129 ms) | **60 ms** (slowest 97 ms) |
| Add 50 strokes | — | 2619 ms (about 50 ms per stroke) | 2561 ms (about 50 ms per stroke) |
| Save (safe replace) | under 2 s | **212 ms** | **202 ms** |
| Reopen and read the strokes back | — | 283 ms, 50 of 50 back | 211 ms, 50 of 50 back |
| Size after a save with no changes | — | 14.2 MB (+0.2) | 17.4 MB (+0.0) |
| Size after saving 50 strokes | — | 15.4 MB | 18.5 MB |

## What it means

**Speed: passes comfortably.** Pages render several times faster than the pass mark, and a full save of a 14–17 MB file takes about a fifth of a second. Building the writing screen on MuPDF as designed is supported by these numbers.

**Adding a stroke takes about 50 ms.** That happens in the background after the pen lifts. The ink itself is drawn instantly by Onyx's fast-ink, so the pen won't feel it. It would matter if hundreds of strokes were added at once, such as pasting a big selection. Plan 2/3 should batch those.

**File size: one finding, already half-fixed.**
- The first run showed about **64 KB per stroke**, because each stroke's drawing was saved uncompressed. Saving now compresses it, and also drops erased strokes from the file (`saveTo` uses MuPDF's `compress,garbage` options). A new test, `savedStrokesAreCompact`, keeps it that way.
- With that fix, these long test strokes cost about **23 KB each**. Real handwriting strokes are shorter (roughly 30–80 points), so expect perhaps 5–10 KB per stroke. That's still around 1 MB for a densely written page.
- Two causes remain:
  - The drawing puts a small circle at *every* point.
  - The stroke's private data and its point list are stored as uncompressed text.
- **Suggested fix, early in plan 2:** draw each stroke as one outline with rounded ends, which should cut the drawing about tenfold, and ask MuPDF to pack those text parts into compressed object streams. Neither changes the design; both are internal.

**One practical lesson for testing.** Gradle's tablet-test command removes the app when it finishes, which deletes the check folder. To run the check, install the app and its tests, let the check create its folder, copy the PDFs in, and run it with `adb shell am instrument`:

```sh
cd notes
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class com.fzizzle.epapernotes.check.SpeedCheck com.fzizzle.epapernotes.test/androidx.test.runner.AndroidJUnitRunner   # creates the folder, then skips
adb shell cp "<pdf on the tablet>" /sdcard/Android/data/com.fzizzle.epapernotes/files/check/
adb shell am instrument -w -e class com.fzizzle.epapernotes.check.SpeedCheck com.fzizzle.epapernotes.test/androidx.test.runner.AndroidJUnitRunner
adb shell cat /sdcard/Android/data/com.fzizzle.epapernotes/files/check/results.txt
```

## For the owner to decide

1. **Go ahead with the writing screen (plan 2) on MuPDF?** The speed is there.
2. **The stroke-size fix:** I suggest doing it first thing in plan 2, as described above. Or you can accept the current size for now.

## Update: smaller strokes (plan 2, Task 1)

Each stroke is now drawn as one outline with rounded ends, rather than a circle at every point. Its coordinates are written to 0.1 pt, and saves pack small objects into compressed object streams (`objstms`). Rerun on the same two PDFs, same test strokes:

| | PWT (14.0 MB) | FLE (17.4 MB) |
|---|---|---|
| Size after a save with no changes | 13.7 MB (object streams make the file itself smaller) | 17.3 MB |
| Size after saving 50 strokes | 13.9 MB (+0.2 MB, about 4 KB per stroke) | 17.5 MB (+0.2 MB) |
| Add 50 strokes | 622 ms (about 12 ms per stroke) | 552 ms |
| Save (safe replace) | 109 ms | 114 ms |
| Reopen and read the strokes back | 329 ms, 50 of 50 | 238 ms, 50 of 50 |

These 200-point test strokes are long, and real handwriting strokes are shorter. A densely written page should now add well under 0.5 MB.
