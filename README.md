<p align="center">
  <img src="assets/icon.svg" width="96" height="96" alt="Jot logo: an ink stroke and a green dot on a lilac circle">
</p>

<h1 align="center">Jot</h1>

<p align="center">Handwriting on your own PDFs, on the <b>Boox Note Air 6 C</b>.</p>

Jot is a lightweight Android app that works on top of your own folders, the way Obsidian works over a folder of Markdown files. Your PDFs stay where you put them, and the app shows exactly what's on disk. Your handwriting is saved back into those same files. Nothing gets imported, and there's no account or proprietary sync. Sync the folder any way you like: Syncthing, a cloud folder, or USB.

> **Status:** in use on the tablet. The writing screen, the tools, the library and the readability settings are built and merged. Owner test sessions for the tools and the library are still open, and conflict copies (plan 5) come next. See [TODO.md](TODO.md) and the [design document](docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md).

## Why

The stock Boox Notes app writes well but gets in the way:

- PDFs have to be imported one at a time, and sync only works through Boox's own service.
- You can't rotate a page once a PDF is imported.
- The extra buttons on third-party EMR pens can't be mapped.
- There's no highlighter.
- The interface is crowded, and moving around a page takes two fingers.

## What it does

- **Library:** your folders as a thumbnail grid. Rename, move, delete and make folders. Changes made outside the app show up by themselves. Works from the SD card.
- **Pen:** fast, pressure-sensitive black ink.
- **Highlighter:** see-through yellow.
- **Eraser:** removes a whole stroke.
- **Lasso:** move, resize, copy, paste and delete handwriting.
- **Snip:** copy part of a page as a picture and paste it on another page.
- **Undo and redo.**
- **Pen buttons:** hold button 1 to lasso, button 2 to erase.
- **Rotate pages:** one or all, saved in the PDF.
- **Pages:** one at a time. Turn with the ‹ › buttons or the volume buttons. Two fingers zoom, one finger moves a zoomed page. Palms are ignored.
- **Readability:** sharp zoomed text, a contrast setting, trimmed margins, dark text as pure black.
- **One toolbar strip,** nothing else on screen.
- **Autosave** and crash-safe saving.

Handwriting is saved as standard PDF annotations, so any PDF viewer shows it. The original page is never changed.

## Not in the first version

Blank notebooks, the partial eraser, copy and paste between documents, extra pen colours, and sync.

## Tech

- Kotlin, native Android.
- Boox's Onyx pen SDK for low-latency e-ink drawing.
- MuPDF for rendering PDFs and writing annotations.

## Licence

AGPL-3.0 (see [LICENSE](LICENSE)), because the app uses MuPDF.

## Building

The app lives in `notes/`. Its home-screen name is Jot, but the app id stays `com.fzizzle.epapernotes`, so files, settings and the All files access grant from earlier builds carry over. The launcher icon is an adaptive vector in `notes/app/src/main/res/`, and [assets/icon.svg](assets/icon.svg) is the same drawing for this page.

To set up a computer for building and installing, see [notes/SETUP.md](notes/SETUP.md). It also explains how to install on the tablet without wiping the app's data. The Android tools are installed in the home folder, so load them first:

```sh
source ~/.local/opt/android-env.sh
cd notes
./gradlew :app:assembleDebug              # build the app
./gradlew :app:testDebugUnitTest          # tests that run on the laptop
./gradlew :app:connectedDebugAndroidTest  # tests that run on the tablet; it UNINSTALLS the app afterwards, see notes/SETUP.md
./gradlew :app:installRelease             # the faster build, for everyday use on the tablet
```

The release build is the one to use day to day: strokes are added in about half the time (see [the speed results](docs/superpowers/review-speed-results.md)). It's signed with the same key as the debug build, so either installs over the other and the app keeps its data. The adb shortcuts for checks (`am start … --es path` and `--es root`) only work in the debug build, so install that again before checking anything over adb.
