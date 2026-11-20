<p align="center">
  <img src="assets/icon.svg" width="96" height="96" alt="Jot logo: an ink stroke and a green dot on a lilac circle">
</p>

<h1 align="center">Jot</h1>

<p align="center">Handwriting on your own PDFs, on the <b>Boox Note Air 6 C</b>.</p>

Jot is a lightweight Android app that works on top of your own folders, the way Obsidian works over a folder of Markdown files. Your PDFs stay where you put them, and the app shows exactly what's on disk. Your handwriting is saved back into those same files. Nothing gets imported, and there's no account or proprietary sync. Sync the folder any way you like: Syncthing, a cloud folder, or USB.

> **Status:** in use on the tablet. The writing screen, the tools, the library, the readability settings, dotted pages and conflict copies are built, and the owner's test sessions have passed. Still open: the flash after each erase and a trail while erasing. See [TODO.md](TODO.md) and the [design document](docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md).

## Screenshots

### One toolbar

<img src="assets/screenshots/toolbar.png" alt="The toolbar strip: back, then pen, highlighter, eraser, lasso, snip, undo and redo on a centred island, then the page menu, previous page, page number and next page">

Back · pen, highlighter, eraser, lasso, snip, undo, redo · page menu · ‹ page › . Nothing else covers the page.

### Your own folders

Jot shows the folders on disk as they are, as deep as you like. Here, courses hold topics, and topics hold PDFs. The breadcrumb goes back up.

<table>
  <tr>
    <td width="33%" valign="top"><img src="assets/screenshots/folders-1-courses.png" alt="The library folder: one folder per course, and a Continue row for the last file"></td>
    <td width="33%" valign="top"><img src="assets/screenshots/folders-2-topics.png" alt="A course folder: one folder per topic, each showing how many PDFs it holds"></td>
    <td width="33%" valign="top"><img src="assets/screenshots/folders-3-pdfs.png" alt="A topic folder: its PDFs as thumbnails, with handwriting showing on the tiles"></td>
  </tr>
  <tr>
    <td align="center">Courses</td>
    <td align="center">Topics</td>
    <td align="center">PDFs, with your ink on the thumbnails</td>
  </tr>
</table>

## Why

I wanted a note-taking app that was fast and focused. I also needed it to work with third-party EMR pens, including their side buttons. I couldn't find one that did all of this that wasn't hidden behind a subscription, so I built one.

## What it does

- **Library:** your folders as a thumbnail grid. Rename, move, delete and make folders. Changes made outside the app show up by themselves. Works from the SD card.
- **Search:** a magnifier in the top row finds PDFs anywhere in the library by the words of their name.
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
- **Sync-safe:** if a file changes on disk while you're writing in it, both versions are kept. Your writing goes on in a copy named `<name> (my edits DATE).pdf`.

Handwriting is saved as standard PDF annotations, so any PDF viewer shows it. The original page is never changed.

## Not in the first version

Blank notebooks, the partial eraser, copy and paste between documents, extra pen colours, and built-in sync.

## Tech

- Kotlin, native Android.
- Boox's Onyx pen SDK for low-latency e-ink drawing.
- MuPDF for rendering PDFs and writing annotations.

## Licence

AGPL-3.0 (see [LICENSE](LICENSE)), because the app uses MuPDF.

## Building

The app lives in `notes/`. Its home-screen name is Jot, but the app id stays `com.fzizzle.epapernotes`, so files, settings and the All files access grant from earlier builds carry over. The launcher icon is an adaptive vector in `notes/app/src/main/res/`, and [assets/icon.svg](assets/icon.svg) is the same drawing for this page.

To set up a computer for building and installing, see [notes/SETUP.md](notes/SETUP.md). It also explains how to install on the tablet without wiping the app's data. Setup puts the Android tools in your home folder, with a script that loads them, so run that script first:

```sh
source ~/.local/opt/android-env.sh
cd notes
./gradlew :app:assembleDebug              # build the app
./gradlew :app:testDebugUnitTest          # tests that run on the laptop
./gradlew :app:connectedDebugAndroidTest  # tests that run on the tablet; it UNINSTALLS the app afterwards, see notes/SETUP.md
./gradlew :app:installRelease             # the faster build, for everyday use on the tablet
```

The release build is the one to use day to day: strokes are added in about half the time (see [the speed results](docs/superpowers/review-speed-results.md)). It's signed with the same key as the debug build, so either installs over the other and the app keeps its data. The adb shortcuts for checks (`am start … --es path` and `--es root`) only work in the debug build, so install that again before checking anything over adb.
