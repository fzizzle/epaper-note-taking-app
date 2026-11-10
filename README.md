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

Jot does a small set of things and aims to do each one well.

- **Your folders as the library:** a thumbnail grid of one folder, folders first, with a breadcrumb to go back up and a refresh button. Long-press a tile to rename, move or delete it, and make new folders from the top bar. Changes made outside the app show up by themselves. To import a batch of PDFs, copy them into a folder. The library folder can be on the SD card, and a "Continue" row reopens the last file.
- **Fast pen:** Boox's fast-ink drawing mode, with pressure-sensitive black ink.
- **Highlighter:** a see-through yellow stroke that never hides the text under it.
- **Stroke eraser:** touch a stroke and the whole stroke goes.
- **Lasso select:** move, resize, copy, paste and delete your handwriting within a document.
- **Snip:** copy a region of the page as a picture (printed content and ink together) and paste it onto another page.
- **Undo and redo.**
- **Pen side buttons:** hold button 1 to lasso-select and button 2 to erase. Let go and you're back to the pen.
- **Page rotation:** rotate one page or all of them, saved into the PDF itself.
- **Pages and touch:** one page at a time. Turn pages with the ‹ › buttons or the volume buttons. The pen writes, two fingers zoom and one finger moves a zoomed page. A resting palm is ignored.
- **Easier-to-read pages:** sharp text when zoomed in, a contrast setting, trimmed margins, and dark text drawn pure black so the colour screen shows it at full sharpness.
- **Minimal interface:** one thin toolbar strip, and nothing else on screen. The status bar comes back with a swipe down from the top.
- **Never loses writing:** autosave and crash-safe saving. Coming in plan 5: if the file changed on disk while you were writing, both versions are kept.

Handwriting is stored as standard PDF annotations, a separate layer over the original page. Any PDF viewer on your laptop shows it, and the page underneath is never modified.

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
```
