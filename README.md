<img src="assets/icon.svg" width="64" height="64" alt="">

# E-paper PDF Notes

A lightweight Android app for handwriting on PDFs, built for the **Boox Note Air 6 C**.

It sits on top of your own folders, like Obsidian over a folder of Markdown files. Your PDFs stay where you put them, and the app shows exactly what's on disk. Your handwriting is saved back into those same files. Nothing gets imported, and there's no account or proprietary sync. Sync the folder any way you like: Syncthing, a cloud folder, or USB.

> **Status:** design approved, not built yet. See the [design document](docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md).

## Why

The stock Boox Notes app writes well but gets in the way:

- PDFs have to be imported one at a time, and sync only works through Boox's own service.
- You can't rotate a page once a PDF is imported.
- The extra buttons on third-party EMR pens can't be mapped.
- There's no highlighter.
- The interface is crowded, and moving around a page takes two fingers.

## What it does

The app does a small set of things and aims to do each one well.

- **Your folders as the library:** a thumbnail grid of your folder tree with a refresh button. Rename, move, delete and create folders with a long-press. Changes you make outside the app show up on their own. To import a batch of PDFs, copy them into a folder.
- **Fast pen:** uses Boox's fast-ink drawing mode, with pressure-sensitive black ink.
- **Highlighter:** a see-through yellow stroke that never hides the text under it.
- **Stroke eraser:** touch a stroke and the whole stroke is removed.
- **Lasso select:** move, resize, copy, paste and delete your handwriting within a document.
- **Snip:** copy a region of the page as a picture (printed content and ink together) and paste it onto another page.
- **Pen side buttons:** hold one to lasso-select and the other to erase. Let go and you're back to the pen.
- **Page rotation:** rotate one page or all of them, saved into the PDF itself.
- **Touch:** the pen writes, one finger scrolls and two fingers zoom.
- **Minimal interface:** one thin toolbar strip, and nothing else on screen.
- **Never loses writing:** autosave, crash-safe saving, and if the file changed on disk while you were writing, both versions are kept.

Handwriting is stored as standard PDF annotations, a separate layer over the original page. Any PDF viewer on your laptop shows it, and the page underneath is never modified.

## Not in the first version

Blank notebooks, the partial eraser, copy and paste between documents, extra pen colours, sync, and landscape screen orientation.

## Tech

- Kotlin, native Android.
- Boox's Onyx pen SDK for low-latency e-ink drawing.
- MuPDF for rendering PDFs and writing annotations.

## Licence

AGPL-3.0 (see [LICENSE](LICENSE)), because the app uses MuPDF.

## Building

The app lives in `notes/`. To set up a computer for building and installing, see [notes/SETUP.md](notes/SETUP.md): it also explains how to install on the tablet without wiping the app's data. The Android tools are installed in the home folder, so load them first:

```sh
source ~/.local/opt/android-env.sh
cd notes
./gradlew :app:assembleDebug              # build the app
./gradlew :app:testDebugUnitTest          # tests that run on the laptop
./gradlew :app:connectedDebugAndroidTest  # tests that run on the tablet; it UNINSTALLS the app afterwards, see notes/SETUP.md
./gradlew :app:installRelease             # the faster build, for everyday use on the tablet
```

The release build is the one to use day to day: strokes are added in about half the time (see [the speed results](docs/superpowers/review-speed-results.md)). It's signed with the same key as the debug build, so either installs over the other and the app keeps its data. The adb shortcuts for checks (`am start … --es path` and `--es root`) only work in the debug build, so install that again before checking anything over adb.
