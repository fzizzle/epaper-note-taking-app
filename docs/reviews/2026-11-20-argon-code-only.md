# Codebase Review: Jot (`notes/`) & Pen Probe (`probe/`)

## Executive Summary

This codebase consists of two Gradle projects targeting Onyx Boox e-paper tablets (such as the Max Lumi 2, API 28+):
- **`notes/` ("Jot")**: A single-document-at-a-time PDF note-taking app built directly on the Android `View` system, MuPDF (`com.artifex.mupdf:fitz:1.25.6`), and the Onyx Pen SDK (`onyxsdk-pen:1.4.11`). It features a custom crash-safe append-only journal (`ChangeJournal`) that persists vector ink (`InkItem`), raster snips (`ImageItem`), and page rotations (`RotatePages`) before folding them into standard PDF `/Ink` and `/Stamp` annotations on save.
- **`probe/` ("E-Paper Pen Probe")**: A diagnostic app for measuring raw touch/hover vs. Onyx `TouchHelper` fast-ink behavior and logging event telemetry.

### What the Codebase Does Well
1. **E-Paper Domain Engineering**: The interaction between Onyx's hardware/driver fast-ink layer (`TouchHelper.setRawDrawingRenderEnabled`) and Android's view hierarchy is notoriously tricky. Extracting testable state helpers like `StrokeHandover`, `EraseSettle`, `HoverErase`, `PageBitmaps`, `InkOverlay`, and `Viewport` shows strong domain modeling.
2. **Crash-Safe Storage Architecture**: `DocumentSession` + `ChangeJournal` + `FileIdentity` prevents data loss across process kills and detects external file modifications (e.g., background sync tools) with a rescue-to-copy fallback (`CopyNames.nextCopyTarget`).
3. **High-Value Unit & Instrumentation Test Suite**: 42 JVM unit test classes and 24 on-device (`androidTest`) classes exercise codecs, affine transforms, journal recovery, MuPDF annotation round-tripping, and bitmap lifecycle.

---

## Master Ranking of Findings by Impact

| Rank | Impact | Status | Category | Summary | Primary Locations |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | **High** | Confirmed | Performance & Correctness | `DocumentSession.record` blocks the caller thread (UI thread or Onyx pen thread) on unbounded `journalReady.await()`, synchronous `fd.sync()`, Base64 PNG encoding, and full-journal re-reads | `notes/.../doc/DocumentSession.kt:401-420, 474-493`, `notes/.../doc/ChangeJournal.kt:136-178, 309-321` |
| **2** | **High** | Confirmed | Correctness & UX | `SelectionBox.drag` uses `max(xRatio, yRatio)` instead of projection, making it impossible to shrink a selection unless dragging inward on **both** axes simultaneously | `notes/.../tools/SelectionBox.kt:34-46` |
| **3** | **High** | Confirmed | Performance | Every autosave hashes the entire existing PDF before saving and the entire temp PDF after saving (2× full-file SHA-256 reads per save); document open reads the entire PDF twice (SHA-256 + `file.readBytes()`) with a TOCTOU race and up to 64 MB heap allocation | `notes/.../doc/DocumentSession.kt:155-160, 211-212, 851-859`, `notes/.../pdf/PdfFile.kt:607-616` |
| **4** | **High** | Confirmed | Correctness | `StrokeOutline` divides by zero (`NaN` coordinates written into PDF appearance stream) when two non-identical points have a subnormal distance (`hypot(dx, dy) == 0f`) | `notes/.../ink/StrokeOutline.kt:108-125` |
| **5** | **High** | Confirmed | Architecture & Testability | God-class activities (`WritingActivity.kt` at 2,258 LOC and `LibraryActivity.kt` at 1,095 LOC) embed complex, untestable UI/fast-ink state machines across dozens of mutable fields | `notes/.../ui/WritingActivity.kt:1-2258`, `notes/.../ui/LibraryActivity.kt:1-1095` |
| **6** | **Medium-High** | Confirmed | Correctness & UX | `Eraser.hit` for `ImageItem` ignores `radiusPt`, breaking `TapSelect.pick` reach tolerance (`18px`) on snips; `Lasso.selects` only checks the single center point of `ImageItem` and discrete un-resampled vertices of `InkItem` | `notes/.../tools/Eraser.kt:28-30`, `notes/.../tools/TapSelect.kt:35-39`, `notes/.../tools/Lasso.kt:12-23` |
| **7** | **Medium-High** | Confirmed | UX & Correctness | Adding a page (`addPageAfter`) wipes the entire undo/redo stack even though `DocumentSession.renumbered` already supports lossless page renumbering for all change types | `notes/.../doc/DocumentSession.kt:638, 1134-1140, 1271-1280` |
| **8** | **Medium-High** | Confirmed | Performance | Opening a multi-hundred-page PDF synchronously calls `file.layout(page)` and `pdf.hasOwnItems(page)` for every page in the document before or immediately after showing the first page | `notes/.../ui/WritingActivity.kt:664-665, 1701-1709` |
| **9** | **Medium-High** | Confirmed | Correctness | `PdfFile.add` desynchronizes `idsByPage` and `itemsByPage` if `annotations.read(dict)` returns `null`, and re-parses the PDF annotation dictionary it just created; `ItemAnnotations.pngs` caches by MuPDF object number without clearing on `forgetAllPages()` | `notes/.../pdf/PdfFile.kt:353-356, 507-516`, `notes/.../pdf/ItemAnnotations.kt:66, 204, 237-241` |
| **10** | **Medium** | Confirmed | Correctness | `ChangeJournal.rewrite` leaks `.new` temp files on I/O failure; `ThumbnailCache.publish` and `ContinuePicture.store` rename temp files without `fsync` | `notes/.../doc/ChangeJournal.kt:119-127`, `notes/.../library/ThumbnailCache.kt:82-91`, `notes/.../ui/ContinuePicture.kt:27-35` |
| **11** | **Medium** | Confirmed | Architecture | Leaky `PdfPages` abstraction forces 6 unsafe `(pdf as PdfFile)` casts in `WritingActivity` | `notes/.../doc/PdfPages.kt:11-58`, `notes/.../ui/WritingActivity.kt:473, 494, 657, 831, 1251, 1982` |
| **12** | **Medium** | Confirmed | Performance | `PdfFile.drawSolidBand` allocates ~1.8 MB JVM `ByteArray` copies per band via JNI (`layer.samples`) and blends pixel-by-pixel in Kotlin; `PageView.drawStroke` issues 1 JNI `Canvas.drawLine` call per point for opaque overlay strokes | `notes/.../pdf/PdfFile.kt:210-245`, `notes/.../ui/PageView.kt:863-878` |
| **13** | **Medium** | Confirmed | Correctness & Performance | `probe/`: `ProbeLog` opens/writes/closes a file on the UI thread on every motion event; `ProbeSurface.surfaceChanged` wipes existing ink; `ProbeSurface.onGenericMotionEvent` mislabels all generic motion events as `HOVER` | `probe/.../ProbeLog.kt:27-33`, `probe/.../ProbeSurface.kt:53-56, 111-114` |
| **14** | **Medium** | Confirmed | Maintainability & Testing | Process-wide static state in `DocumentSession.openSessions` and `NameIndex.shared` leaks across unclosed sessions/tests, causing 15-second stalls and permanently dirty `anyOpen()` state | `notes/.../doc/DocumentSession.kt:134, 152, 1332`, `notes/.../ui/NameIndex.kt:324-366` |
| **15** | **Low-Medium** | Confirmed | Performance | `FileOps.delete` lists `journalDir` twice per deleted PDF (2N directory scans for N PDFs in a deleted folder); `LibraryActivity.list` re-lists `root.listFiles()` on every subfolder navigation | `notes/.../library/FileOps.kt:108-115, 224-239`, `notes/.../ui/LibraryActivity.kt:540-543` |
| **16** | **Low-Medium** | Speculative | Correctness | `SafeSave.replace` and `ChangeJournal.rewrite` use `StandardCopyOption.ATOMIC_MOVE`, which can throw `AtomicMoveNotSupportedException` on some Android FUSE / external SD card mounts | `notes/.../storage/SafeSave.kt:64-66`, `notes/.../doc/ChangeJournal.kt:122-126` |
| **17** | **Low** | Speculative | UX & Performance | `storeContinuePicture()` renders at fixed `CONTINUE_WIDTH_PX = 300` without aspect-ratio height clamping (`thumbWidthPx`), risking large bitmap allocation on tall scroll PDFs during `onPause()` | `notes/.../ui/WritingActivity.kt:1975-1990, 2240` |

---

## 1. Correctness Bugs & Risky Code

### Confirmed Problems

#### 1.1 `DocumentSession.record` / `undo` / `redo` Blocks the UI Thread and Pen Callback Thread on Unbounded `journalReady.await()` and Synchronous Disk `fsync`
- **Locations**:
  - `notes/app/src/main/java/com/fzizzle/epapernotes/doc/DocumentSession.kt:377-378, 401-420, 434-493`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/doc/ChangeJournal.kt:136-178, 309-321`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/ui/WritingActivity.kt:1233, 1280-1292, 1323, 1407`
- **Problem**:
  1. `DocumentSession.record()` is invoked directly on the caller's thread: on the Onyx pen callback thread for `onPenStroke` (`WritingActivity.kt:1323`), and **on the Android main/UI thread** for selection trace (`WritingActivity.kt:1233`), selection move/resize/duplicate/delete/paste (`recordFromScreen` at `WritingActivity.kt:1280-1292`), and `undo()` / `redo()` (`WritingActivity.kt:1407`).
  2. Line 402 of `DocumentSession.kt` calls `journalReady.await()` with **no timeout**. Meanwhile, `openOnWorker()` only counts down `journalReady` at line 230 (or 241 in `finally`) *after* waiting up to 15 seconds for a previous session (`previousSessionWaitMs`, line 152), hashing the entire PDF file (`identityOf(file)`, line 156), opening the PDF, and running `rescueIntoCopy` (line 228) if an old journal mismatched. Any UI-thread call to `record()` before `openOnWorker()` finishes freezes the UI thread (risking an Android ANR).
  3. Even after `journalReady` is signaled, `commit()` (`DocumentSession.kt:474-493`) acquires `synchronized(lock)` and synchronously executes `journal.append(change)` -> `writeLines()` -> `out.fd.sync()` (`ChangeJournal.kt:314`) on the calling thread.
  4. Worse, if `change` contains an `ImageItem` (e.g., pasting, duplicating, moving, deleting, or undoing a snip) whose SHA-256 hash is not yet in `journal.written`:
     - If `written` is `null` (which happens on a fresh `ChangeJournal` instance before any `rewrite`, or after any prior failed write where `written = null` at `ChangeJournal.kt:162`), `picturesInFile()` (`ChangeJournal.kt:172-178, 224-233`) calls `dropCutShortTail()` and reads + SHA-256-hashes the entire existing journal file on the UI thread.
     - Then `ChangeJournal.append` (`ChangeJournal.kt:150-157`) SHA-256-hashes the PNG bytes, Base64-encodes the entire PNG into a single string, writes it to disk, and forces an `fd.sync()`—all synchronously on the UI thread!
- **Fix**:
  - Do not allow UI actions before `SessionEvent.Opened` has been delivered, or use a non-blocking check on the UI thread.
  - Maintain the in-memory `undoable` / `redoable` / `unsaved` lists and assign item IDs under `lock` in memory, then enqueue the disk journal append (`journal.append` + `fd.sync()`) onto a dedicated single-threaded journal I/O executor (or the session worker) so the UI thread and pen callback thread never block on flash storage `fsync()` or Base64 encoding.

#### 1.2 `SelectionBox.drag` Cannot Shrink a Selection Along a Single Axis
- **Location**: `notes/app/src/main/java/com/fzizzle/epapernotes/tools/SelectionBox.kt:34-46`
- **Problem**:
  ```kotlin
  val sx = if (corner == 1 || corner == 2) 1f else -1f
  val sy = if (corner == 2 || corner == 3) 1f else -1f
  val w = box.width
  val h = box.height
  val minRatio = max(MIN_SIDE_PT / w, MIN_SIDE_PT / h)
  val ratio = max(max((w + sx * dx) / w, (h + sy * dy) / h), minRatio)
  ```
  Because `ratio` takes `max((w + sx * dx) / w, (h + sy * dy) / h)`, if the user grabs a corner handle and drags inward predominantly along one axis (for example, dragging the bottom-right corner left with `dx = -50f, dy = 0f`, or even slightly downward `dy = +2f`), `(h + sy * dy) / h` is `>= 1.0f`. Taking `max(xRatio, yRatio)` clamps `ratio >= 1.0f`, so **the box refuses to shrink at all unless both `sx * dx < 0` and `sy * dy < 0` simultaneously**, and even then, the shrinkage is limited by whichever axis moved *less*.
- **Fix**:
  Project the new corner position onto the diagonal from the anchor `(ax, ay)` to the grabbed corner, or use the diagonal distance ratio:
  ```kotlin
  val newW = w + sx * dx
  val newH = h + sy * dy
  val diagSq = w * w + h * h
  val projectedRatio = (newW * w + newH * h) / diagSq
  val ratio = max(projectedRatio, minRatio)
  ```
  Projecting `(newW, newH)` onto the diagonal `(w, h)` gives smooth, natural aspect-ratio-locked scaling in every drag direction (including single-axis drags).

#### 1.3 `StrokeOutline` Division by Zero Emits `NaN` Coordinates into PDF Content Streams
- **Location**: `notes/app/src/main/java/com/fzizzle/epapernotes/ink/StrokeOutline.kt:108-125`
- **Problem**:
  `withoutRepeats` (lines 120–126) only filters out points that are strictly `==` to their predecessor:
  ```kotlin
  if (i == 0 || p.x != points[i - 1].x || p.y != points[i - 1].y) {
      out.add(p)
  }
  ```
  If two consecutive points differ by a tiny subnormal float (e.g., `dx = 1e-25f, dy = 0f`) where `p.x != prev.x` is `true`, `hypot(dx, dy)` in 32-bit `Float` underflows to `0f`:
  - `segmentNormal` (lines 115–118) computes `val len = hypot(dx, dy)` (`0f`) and returns `PointF(-dy / len, dx / len)` = `PointF(NaN, NaN)`.
  - `isSharpTurn` (lines 108–113) divides by `len1 * len2` (`0f`), yielding `cosine = NaN`, so `cosine < SHARP_TURN_COS` evaluates to `false`.
  - `buildPath` then interpolates `NaN` normals and formats `"NaN NaN l"` directly into the PDF appearance stream (`ItemAnnotations.kt:142`).
- **Fix**:
  In `withoutRepeats` (or inside `segmentNormal` / `isSharpTurn`), filter out consecutive points where `hypot(p.x - prev.x, p.y - prev.y) <= 1e-5f` so every retained segment has a guaranteed positive finite length.

#### 1.4 `PdfFile.add` Cache Desynchronization and `ItemAnnotations.pngs` Stale Object-Number Cache
- **Locations**:
  - `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/PdfFile.kt:353-356, 507-516`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/ItemAnnotations.kt:66, 204, 237-241`
- **Problem**:
  1. In `PdfFile.add` (`PdfFile.kt:507-516`):
     ```kotlin
     val dict = annotations.add(p, item)
     idsByPage[page]?.let { it += item.id }
     itemsByPage[page]?.let { cached ->
         annotations.read(dict)?.let { cached += it }
     }
     ```
     First, `itemsByPage[page]` already has the exact `Item` in hand (`item`)—calling `annotations.read(dict)` unnecessarily re-parses the PDF annotation dictionary (and for an `InkItem`, parses the `/InkList` PDF arrays back into `StrokePoint`s!). Second, if `annotations.read(dict)` ever returns `null`, `idsByPage[page]` has `item.id` appended while `itemsByPage[page]` does not, violating the invariant that `idsByPage` and `itemsByPage` match. Simply doing `itemsByPage[page]?.add(item)` is both faster and guaranteed consistent.
  2. In `ItemAnnotations` (`ItemAnnotations.kt:66, 204, 237-241`), `pngs` is a `HashMap<Int, ByteArray>()` keyed by MuPDF's indirect object number (`ref.asIndirect()`). When `PdfFile.forgetAllPages()` (`PdfFile.kt:353-356`) is called (for example, during `removePage` at `PdfFile.kt:398`), `idsByPage` and `itemsByPage` are cleared, and subsequent `saveTo` runs `doc.save(target.path, "compress,garbage,objstms")`. However, `annotations.pngs` is **never cleared** in `forgetAllPages()`. If an image annotation was on a deleted page (`doc.deletePage(page)` does not call `annotations.forget(dict)`!), its indirect object number remains in `annotations.pngs`.
- **Fix**:
  - Replace `annotations.read(dict)?.let { cached += it }` in `PdfFile.add` with `cached += item`.
  - When deleting a page in `PdfFile.removePage` or clearing caches in `forgetAllPages()`, clear or prune `annotations.pngs` so freed object numbers cannot collide or leak memory.

#### 1.5 `ChangeJournal.rewrite` Leaks `.new` Temp Files on Failure, and `ThumbnailCache` / `ContinuePicture` Skip `fsync`
- **Locations**:
  - `notes/app/src/main/java/com/fzizzle/epapernotes/doc/ChangeJournal.kt:114-128`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/library/ThumbnailCache.kt:82-91`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/ui/ContinuePicture.kt:27-35`
- **Problem**:
  - Unlike `SafeSave.replace` (`SafeSave.kt:60-72`), which wraps the temp-file write and `Files.move` in a `try/catch` that deletes the temp file on failure, `ChangeJournal.rewrite` (`ChangeJournal.kt:119-127`) writes to `File(dir, file.name + ".new")` and calls `Files.move` without cleaning up `.new` if an `IOException` occurs during `writeLines`, `fd.sync()`, or `Files.move`.
  - Furthermore, `FileOps.journalFilesOf` (`FileOps.kt:234-239`) matches `$stem-*.journal` and `$stem-*.journal.new`, while `DocumentSession.journalFile` (`DocumentSession.kt:1322`) only matches `<hash16>.journal` (`name.endsWith(".journal")`). Wait: if a file is named `<hash16>.journal.new`, `FileOps.moveOnDisk` (`FileOps.kt:166-175`) will move it during a file rename, and if `rewrite` fails, the orphaned `.new` file stays in `journals/` forever.
  - In `ThumbnailCache.publish` (`ThumbnailCache.kt:86-89`) and `ContinuePicture.store` (`ContinuePicture.kt:30-31`), `tmp.writeBytes(bytes)` is immediately followed by `tmp.renameTo(target)` with no `fd.sync()`. On an unclean shutdown or battery drain on an e-paper tablet, metadata (`renameTo`) can be committed before data blocks, leaving a 0-byte or corrupted PNG in the thumbnail cache.
- **Fix**:
  - Reuse `SafeSave` (or add a `try/catch` cleanup block) in `ChangeJournal.rewrite`, `ThumbnailCache.publish`, and `ContinuePicture.store`.

#### 1.6 `DocumentSession.openOnWorker` Reads the File Twice with a TOCTOU Race Between `identityOf(file)` and `PdfFile.open`
- **Locations**:
  - `notes/app/src/main/java/com/fzizzle/epapernotes/doc/DocumentSession.kt:155-160, 211-212`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/PdfFile.kt:607-616`
- **Problem**:
  In `openOnWorker`:
  1. Line 156 computes `val diskIdentity = identityOf(file)` by streaming the entire file through SHA-256 (`FileIdentity.of(file)`).
  2. Line 211 calls `open(file)`, which in `WritingActivity.kt:471` calls `PdfFile.open(f, inMemory = true)`.
  3. `PdfFile.open` checks `file.length() in 1..IN_MEMORY_LIMIT_BYTES` and then calls `file.readBytes()` to load the file into memory a second time.
  If an external process (e.g., Syncthing, Nextcloud, or MTP file transfer) modifies the PDF between step 1 and step 3:
  - `known` (`DocumentSession.kt:157`) stores the SHA-256 of the *old* file, while MuPDF opens the *new* bytes (or vice versa).
  - Subsequent `fileUnchanged()` checks at save time (`DocumentSession.kt:851`) compare the on-disk file against the stale `known` identity, either triggering a false conflict rescue copy (`CopyNames.nextCopyTarget`) or overwriting an external change without realizing the in-memory PDF differed from `known`.
- **Fix**:
  When opening in-memory (`<= 64 MB`), read the bytes once, compute `FileIdentity` (SHA-256 + byte length + `lastModified`) directly from that `ByteArray`, and pass the same `ByteArray` to `Document.openDocument(bytes, "pdf")`.

#### 1.7 `ProbeSurface` & `ProbeLog` Bugs in `probe/`
- **Locations**:
  - `probe/app/src/main/java/com/fzizzle/epapernotes/probe/ProbeSurface.kt:53-56, 111-114`
  - `probe/app/src/main/java/com/fzizzle/epapernotes/probe/ProbeLog.kt:27-33`
  - `probe/app/src/main/java/com/fzizzle/epapernotes/probe/OnyxInput.kt:30-32`
- **Problem**:
  1. `ProbeLog.record` (`ProbeLog.kt:31`) calls `file.appendText(line + "\n")` synchronously on the UI thread for every single `onTouchEvent`, `onHoverEvent`, and `onGenericMotionEvent`. At 200+ Hz stylus sampling, opening, writing, and closing a `FileOutputStream` on every motion event injects substantial I/O jitter directly into the touch latency being probed.
  2. `ProbeSurface.surfaceChanged` (`ProbeSurface.kt:111-114`) unconditionally allocates a blank white `Bitmap` and overwrites `bitmap`, erasing all drawn strokes whenever the surface dimensions change (e.g., system bars or rotation).
  3. `ProbeSurface.onGenericMotionEvent` (`ProbeSurface.kt:53-56`) hardcodes `EventSource.HOVER` instead of distinguishing generic motion sources, and returns `true` without calling `super.onGenericMotionEvent(event)` (which can swallow non-hover generic motion events before `onHoverEvent` sees them, or duplicate hover events).
  4. `OnyxInput.turnOn` (`OnyxInput.kt:30`) passes `Rect(0, 0, view.width, view.height)` to `setLimitRect` without updating it when `ProbeSurface` resizes in `surfaceChanged`.

---

### Speculative / Edge-Case Risks

#### 1.8 `StandardCopyOption.ATOMIC_MOVE` Compatibility on External / FUSE Mounts
- **Locations**: `notes/app/src/main/java/com/fzizzle/epapernotes/storage/SafeSave.kt:64-66`, `notes/app/src/main/java/com/fzizzle/epapernotes/doc/ChangeJournal.kt:122-126`
- **Risk**: While moving a temp file within the same directory (`parent`) normally succeeds with `ATOMIC_MOVE` on ext4/f2fs, some Android FUSE wrappers or removable exFAT/FAT32 SD card mounts can throw `AtomicMoveNotSupportedException`. Falling back to `StandardCopyOption.REPLACE_EXISTING` when `AtomicMoveNotSupportedException` is caught would harden saving on removable SD cards (`StorageVolumes.kt` explicitly supports removable SD cards).

#### 1.9 `ImageItem.transformed` Only Transforms Two Corners Instead of Four
- **Location**: `notes/app/src/main/java/com/fzizzle/epapernotes/ink/Item.kt:77-82`
- **Risk**: `ImageItem.transformed(t: Affine)` maps only `(box.x0, box.y0)` and `(box.x1, box.y1)` and takes `min`/`max`. Under the 0°/90°/180°/270° axis-aligned rotations currently produced by `PdfFile.pdfToDisplay`, two opposite corners still span the rotated bounding box (though the bitmap pixels inside `ImageItem.png` are *not* rotated when pasted across pages with different `/Rotate` values in `Clipboard.placedOn` at `Clipboard.kt:26-34`!). Note that `SelectionLayer.screenBox` (`SelectionLayer.kt:385-388`) transforms all four corners; `ImageItem.transformed` should do the same (and ideally `Box.transformed(t: Affine)` should live on `Box` itself).

---

## 2. Performance (Rendering, I/O & Pen Latency)

### Confirmed Problems

#### 2.1 Redundant Full-File SHA-256 Hashing on Every Autosave (100 MB Read + 50 MB Write for a 50 MB PDF)
- **Locations**:
  - `notes/app/src/main/java/com/fzizzle/epapernotes/doc/DocumentSession.kt:156, 851, 859, 898, 910-926, 1226`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/storage/FileIdentity.kt:19-40`
- **Problem**:
  `DocumentSession` schedules an autosave 10 seconds after the last edit (`AUTOSAVE_DELAY_MS = 10_000L`, `DocumentSession.kt:1329`). Inside `saveOnWorker()` (`DocumentSession.kt:837-904`):
  1. Line 851 calls `fileUnchanged(was)` -> `identityOf(file) == was`, which reads the **entire existing PDF from disk** and computes its SHA-256 hash.
  2. Line 855 calls `SafeSave.replace(file) { p.saveTo(it) }`, writing the entire PDF to `.saving-...tmp` and calling `fsync`.
  3. Inside the `SafeSave.replace` `beforeSwap` callback (line 859), it calls `written = identityOf(temp)`, which reads the **entire newly-written temp PDF back from disk** to compute its SHA-256 hash before moving it!
  On a 50 MB textbook PDF, every single autosave (every 10 seconds of pause while taking notes) performs **100 MB of sequential reads + SHA-256 hashing and 50 MB of writes** on the single document worker thread (`scheduler`). While `saveOnWorker` holds the worker thread for several seconds on eMMC/SD flash storage, any page render (`requestRender`), region render after erase (`requestRegionRender`), or page turn is completely blocked behind the save.
- **Fix**:
  1. Notice `checkFile()` (`DocumentSession.kt:916`) already uses a fast path: `if (file.length() == was.size && file.lastModified() == was.modifiedMs) return`. Using `(size, modifiedMs)` (or Linux `StructStat` `st_ino`, `st_mtime`, `st_size` via `android.system.Os.stat`) avoids reading 50 MB before every save when the file's mtime and size have not changed.
  2. If a content hash of the written file is still desired for `known`, compute the SHA-256 incrementally while streaming/copying or only hash when `checkFile` detects an mtime/size change on `onResume`.

#### 2.2 Synchronous Re-Encoding of Snip PNGs After Every Autosave
- **Locations**:
  - `notes/app/src/main/java/com/fzizzle/epapernotes/doc/DocumentSession.kt:1132-1156`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/doc/ChangeJournal.kt:114-128, 146-160`
- **Problem**:
  When an autosave succeeds, `restartJournal()` (`DocumentSession.kt:1154`) calls `journal.rewrite(unsaved, extraPictures)`. Normally `unsaved` is empty after a save, so `journal.rewrite` clears `written` (`HashSet<String>()`) and truncates the journal file.
  If the user then moves, resizes, or deletes an existing `ImageItem` on the page (which wraps the old and new `ImageItem` in `ReplaceItems` or `RemoveItems`), `journal.append(change)` sees that the image's SHA-256 hash is no longer in `written`, and **re-hashes, Base64-encodes, writes, and `fsync`s the entire PNG on the UI thread** (`WritingActivity.kt:1280` -> `session.record`).
- **Fix**:
  Move `journal.append` off the UI thread (see §1.1), and consider keeping a small content-addressed sidecar cache for recent snip PNGs in the journal directory (or only writing `img` blobs for items that are *newly added*, not for items being removed whose bytes already live in the saved PDF, unless needed for `undo()` across a process crash—which itself is cleared on process death anyway because `undoable` is not restored from the journal on cold start!).
  *Wait—note that insight*: `ChangeJournal` is **only** ever replayed forward on startup (`DocumentSession.kt:189-203`: `for (entry in journalEntries) pdf.apply(entry)`). `RemoveItems(page, items)` in `PdfFile.apply` (`PdfFile.kt:308`: `val gone = change.items.mapTo(HashSet()) { it.id }`) **only uses `item.id`** during journal replay! Yet `ChangeCodec` and `ChangeJournal.picturesIn` (`ChangeJournal.kt:239-241`) serialize the full `ImageItem` (including writing its entire Base64 PNG `img` line to the journal!) even for `RemoveItems` and the `removed` half of `ReplaceItems`!

#### 2.3 Opening a Large PDF Synchronously Loads Every Page Twice on Startup (`layOutPages` + `findWrittenPages`)
- **Locations**:
  - `notes/app/src/main/java/com/fzizzle/epapernotes/ui/WritingActivity.kt:654-680, 1701-1709`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/PdfFile.kt:269-280, 323-328, 493-504`
- **Problem**:
  When a document opens in `WritingActivity`:
  1. `layOutPages` (`WritingActivity.kt:664`) runs on the worker before `onReady` and executes:
     ```kotlin
     val sizes = (0 until count).map { file.layout(it).displaySize }
     ```
     `file.layout(it)` calls MuPDF `doc.loadPage(page)`, queries `p.bounds` and `p.transform`, and destroys the page—for **every single page** in the PDF before page 0 can even be displayed.
  2. Immediately after `onReady`, `findWrittenPages()` (`WritingActivity.kt:1704-1706`) posts a worker task that runs:
     ```kotlin
     val pages = (0 until pdf.pageCount).filterTo(HashSet()) { pdf.hasOwnItems(it) }
     ```
     `pdf.hasOwnItems(it)` calls `idsOn(page)` (`PdfFile.kt:493-504`), which loads every page again in MuPDF and inspects every annotation dictionary's `/NM` or `/Contents` across the entire book, populating `idsByPage` for all N pages in memory.
  On a 600-page reference PDF, opening the document calls `doc.loadPage` **1,200 times** on startup.
- **Fix**:
  - In `layOutPages`, load exact sizes for the initial visible page (and its immediate neighbors) first so the first page renders immediately; lazily populate or assume uniform page sizes (updating if a page differs when scrolled near) for the rest.
  - In `findWrittenPages`, yield between batches of pages (or check a cancellation token when the session closes) so the single worker thread isn't monopolized right after open when the user immediately turns to another page.

#### 2.4 `PdfFile.drawSolidBand` Copies Multi-Megabyte ByteArrays Across JNI and Blends in Kotlin
- **Location**: `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/PdfFile.kt:132-245`
- **Problem**:
  To prevent PDF highlighters (`Multiply` blend mode) from tinting dark ink drawn earlier, `drawWithSolidInk` renders the page in 256-row bands and then renders a second transparent `Pixmap(ColorSpace.DeviceRGB, bbox, true)` (`layer`) for the solid annotations.
  In `drawSolidBand` (`PdfFile.kt:223-242`):
  - `val src = layer.samples` allocates a fresh JVM `ByteArray` of size `w * bandAndPadH * 4` (~1.8 MB per band at 1650 px width, or ~18 MB across a full page) and copies all pixels from native memory.
  - `bmp.getPixels(rowPixels, ...)` and `bmp.setPixels(rowPixels, ...)` copy each row out of and back into the Android `Bitmap`, blending pixel-by-pixel in a nested Kotlin loop.
- **Fix**:
  Render `layer` into an Android `Bitmap` (via MuPDF's `AndroidDrawDevice`, which draws directly into an Android `Bitmap`'s native pixel buffer without allocating a `Pixmap.samples` `ByteArray`!) and composite it onto `bmp` using Android's native `Canvas(bmp).drawBitmap(layerBitmap, ...)` with standard `PorterDuff.Mode.SRC_OVER`. That eliminates the 1.8 MB/band JNI `ByteArray` allocation and replaces the Kotlin per-pixel blend loop with Skia's SIMD native blitter.

#### 2.5 `PageView.drawStroke` Issues Per-Point JNI `Canvas.drawLine` Calls for Opaque Overlay Strokes
- **Location**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/PageView.kt:863-882`
- **Problem**:
  While `InkOverlay` holds up to `BAKE_AFTER = 150` strokes in memory before baking (`WritingActivity.kt:2239`), `PageView.onDraw` draws every overlay stroke on every frame during pan/pinch gestures or region refreshes.
  For highlighters (line 855), `drawStroke` builds a single `Path` and calls `canvas.drawPath(highlightPath, inkPaint)` once.
  For opaque strokes (lines 863–878), however, if consecutive points have similar widths (the common case during normal writing), it still calls `canvas.drawLine(fromX, fromY, x, y, inkPaint)` inside the per-point loop—issuing up to hundreds of individual JNI `drawLine` calls per stroke (and leaving butt/round-cap artifacts if `strokeCap` were ever changed).
- **Fix**:
  Use `canvas.drawLines(ptsBuffer, 0, count, inkPaint)` for runs of equal quantized width, or cache a pre-built screen/page-space `Path` per stroke segment inside `InkOverlay` so `onDraw` does not rebuild paths or issue thousands of JNI calls on every pan frame.

---

### Speculative / Minor Performance Ideas

#### 2.6 `LibraryActivity.list` Re-Lists `root.listFiles()` on Every Subfolder Refresh
- **Location**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/LibraryActivity.kt:540-543`
- **Idea**: `courseColours` only depends on the top-level directories under `root`. Caching the top-level folder names and only refreshing them when listing `root` itself avoids an extra `root.listFiles()` syscall on every subfolder navigation and `FolderWatch` event.

#### 2.7 `FileOps.delete` Lists `journalDir` Twice per Deleted PDF
- **Location**: `notes/app/src/main/java/com/fzizzle/epapernotes/library/FileOps.kt:108-115, 224-239`
- **Idea**: `liveJournalFilesOf(pdf)` calls `journalFilesOf(pdf)` (1st `journalDir.listFiles()`) and then calls `journalDir.listFiles()` a 2nd time for `<hash16>.journal`. When deleting a folder with $N$ PDFs, `FileOps.delete` scans `journalDir` $2N$ times. Listing `journalDir` once before the loop avoids the $O(N \times M)$ directory scans.

---

## 3. Architecture & Maintainability

### Confirmed Problems

#### 3.1 God-Class Activities (`WritingActivity.kt` at 2,258 LOC and `LibraryActivity.kt` at 1,095 LOC)
- **Locations**:
  - `notes/app/src/main/java/com/fzizzle/epapernotes/ui/WritingActivity.kt:1-2258`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/ui/LibraryActivity.kt:1-1095`
- **Problem**:
  While many leaf algorithms were extracted into small classes (`Viewport`, `EraseSettle`, `HoverErase`, `StrokeHandover`, `PageBitmaps`), `WritingActivity` still acts as the central coordinator for at least 7 distinct responsibilities:
  1. Programmatic view tree assembly & floating island positioning (`buildLayout`, `placeIslands`, `updateStrip`).
  2. Onyx `TouchHelper` & `EpdController` fast-ink lifecycle (`fastInkWanted`, `pauseWhenLifted`, `screenHasPen`, `onyxOwnsStroke`, `cleanRefreshHeld`, `showPending`, `windowsOpen`, `pageChangeUnderWay` — lines 169–348).
  3. Palm rejection & raw touch filtering (`dispatchTouchEvent`, `screenPenDown`, `fingerGesture`, `hoverSummary`).
  4. Background content-bounds measurement & margin-trimming cache (`printedAreas`, `measuring`, `measuredOnce`, `measurePage`).
  5. Render scheduling, zoom refit timers, and overlay baking (`requestRender`, `requestRegionRender`, `scheduleRefit`, `bakeOverlayIntoBitmap`).
  6. Selection / clipboard / snip workflow (`startSnip`, `finishSnip`, `updateSelectionActions`).
  7. Page add/remove/rotate hold orchestration (`holdForPageChange`, `resumeAfterPageChange`, `reloadDocument`).
  Because the fast-ink pause/resume/hold state machine lives directly inside `WritingActivity` fields and methods (`applyFastInk`, `setFastInk`, `refreshCleanly`, `windowOpened`, `windowClosed`), **none of those state transitions can be unit-tested on the JVM**.
- **Recommendation**:
  - Extract a pure-Kotlin `FastInkController` state machine (similar to `EraseSettle` and `StrokeHandover`) that takes inputs (`tool`, `windowsOpen`, `pageChangeUnderWay`, `screenHasPen`, `onyxOwnsStroke`, `zoomed`) and emits commands (`EnableRawDrawing`, `PauseRawDrawing`, `ClearRawInk`, `RefreshScreen`).
  - Extract `MarginTrimCache` (managing `printedAreas`, `measuring`, `measuredOnce`, and `ContentBounds`) out of `WritingActivity`.

#### 3.2 Leaky Abstraction Between `PdfPages` and `PdfFile`
- **Locations**:
  - `notes/app/src/main/java/com/fzizzle/epapernotes/doc/PdfPages.kt:11-58`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/ui/WritingActivity.kt:473, 494, 657, 831, 1251, 1982`
- **Problem**:
  `DocumentSession` works against the `PdfPages` interface, and delivers `val pdf: PdfPages` inside `SessionEvent.Opened(pdf, ...)` (`SessionEvent.kt:9`). However, `WritingActivity` immediately does `val file = pdf as PdfFile` in 6 different places (`layOutPages`, `requestRender`, `requestRegionRender`, `measurePage`, `finishSnip`, `storeContinuePicture`) because `layout`, `pdfToDisplay`, `render`, and `renderRegion` are only defined on `PdfFile`. If any other `PdfPages` implementation is passed to `WritingActivity`, it crashes with `ClassCastException`.
- **Recommendation**:
  Either add rendering/layout methods (or a sub-interface `RenderablePdfPages : PdfPages`) to the session type parameter `DocumentSession<P : PdfPages>`, so callers don't perform unchecked downcasts.

#### 3.3 Process-Wide Static Mutable State in `DocumentSession.openSessions` and `NameIndex.shared`
- **Locations**:
  - `notes/app/src/main/java/com/fzizzle/epapernotes/doc/DocumentSession.kt:134, 152, 238, 1265, 1332`
  - `notes/app/src/main/java/com/fzizzle/epapernotes/ui/NameIndex.kt:324-366`
- **Problem**:
  - `DocumentSession.openSessions` is a static `ConcurrentHashMap<String, CompletableFuture<Unit>>()`. If a `DocumentSession` is created and its scheduler is killed or abandoned without calling `close()` (which happens in `DocumentSessionDeviceTest.kt:53, 81, 122, 195`), the `CompletableFuture` in `openSessions` **never completes**. As a result:
    1. `DocumentSession.anyOpen()` permanently returns `true` for the rest of the process lifetime (forcing `NameIndexTest.kt:67-68` to pass a custom `{ false }` lambda to work around leaked sessions from earlier tests!).
    2. Opening another session on the same path blocks for the full 15-second timeout (`PREVIOUS_SESSION_WAIT_MS = 15_000L`).
  - `NameIndex.shared` (`NameIndex.kt:326-366`) is a process-wide static singleton that registers a `BroadcastReceiver` on `appContext` and spawns background threads without a lifecycle teardown hook.
- **Recommendation**:
  Scope `openSessions` to an injectable `SessionRegistry` (with a default application-scoped instance in `NotesApp`), and provide a cleanup method for tests.

#### 3.4 Duplicated Constants Across Packages
- **Locations**:
  - `PDF_SUFFIX_LENGTH = 4`: duplicated in `LibraryActivity.kt:1093`, `NameIndexDb.kt:122`, `SearchPreviewView.kt:147`, `MemoryIndexStore.kt:44`, and magic `4` in `LibraryWalk.kt:21`.
  - `"page-count"`: hardcoded in `PdfFacts.kt:33` and `LibraryChanges.kt:238`.
- **Recommendation**:
  Consolidate file-extension helpers (`File.pdfDisplayName`, `String.stripPdfSuffix()`) and MuPDF metadata keys in a single shared object.

---

## 4. UX Visible in Code

### Confirmed Problems

1. **Corner Resize Cannot Shrink Selection Along One Axis (`SelectionBox.kt:43`)**:
   - As detailed in §1.2, dragging a selection handle inward horizontally or vertically fails to shrink the selection unless the user drags inward on *both* axes at once.
2. **Tap-to-Select Ignores `TAP_REACH_PX` (`18px`) Around Snips (`Eraser.kt:28-30`, `TapSelect.kt:35-39`)**:
   - In `TapSelect.pick`, tapping with the lasso tool searches within `reachPt` (`18px` converted to PDF points) by calling `Eraser.hits(items, listOf(middle), reachPt)`.
   - However, `Eraser.hit` for `ImageItem` (`Eraser.kt:29`) calls `touchesBox(p, q, item.box)` and **completely ignores `radiusPt`**! Thus, tapping 2 pixels outside a small snip fails to select it, whereas tapping 18 pixels outside a pen stroke selects it.
   - Fix: Expand `item.box` by `radiusPt` (`Box(box.x0 - radiusPt, box.y0 - radiusPt, box.x1 + radiusPt, box.y1 + radiusPt)`) in `Eraser.hit` for `ImageItem`.
3. **Lasso Selection Fails on Snips When Loop Doesn't Enclose Exact Center, and on Long Fast Straight Strokes (`Lasso.kt:12-23`)**:
   - For `ImageItem`, `Lasso.selects` only checks `inside((box.x0 + box.x1) / 2f, (box.y0 + box.y1) / 2f, loop)`. Circling most of a diagram snip without crossing its exact geometric center fails to select it. Checking the 4 corners + center (selecting if majority or any overlap when the loop is inside the image) is much more forgiving.
   - For `InkItem`, `Lasso.selects` counts raw `stroke.points` (`insideCount * 2 > points.size`). A fast straight line drawn across a page has very few sample points near its endpoints and almost none in the middle; circling the middle third of the line finds 0 points inside the loop and fails to select it.
4. **Adding a Page Silently Wipes All Undo/Redo History (`DocumentSession.kt:638, 1138-1139`)**:
   - Tapping the `+` button at the bottom of the last page (`WritingActivity.kt:857` -> `session.addPageAfter`) calls `restartJournal` (`DocumentSession.kt:1138-1139`), which executes `undoable.clear(); redoable.clear()`.
   - Ironically, `DocumentSession` *already* has `renumbered(change, newPageNumber)` (`DocumentSession.kt:1271-1280`) and uses it on line 1135 to renumber `unsaved`! Applying `renumbered` to `undoable` and `redoable` instead of clearing them would preserve undo/redo across page additions (and across page removals for all changes not targeting the removed page).
5. **Silent No-Op When Page Actions Are Refused During Active Touch (`WritingActivity.kt:1611-1622`)**:
   - If `holdForPageChange` returns `false` because a palm/finger gesture or pen hover/down state is active (`pen.isWriting || screenPenDown || fingerGesture`), the user's tap on `+`, `Add page`, `Remove page`, or `Rotate` is silently dropped with only a debug log (`"refused, ..."`).

### Speculative / Minor UX Ideas

6. **`storeContinuePicture` Fixed Width on Tall Pages (`WritingActivity.kt:1975-1990`)**:
   - `ThumbnailCache.thumbWidthPx` (`ThumbnailCache.kt:25-32`) carefully caps thumbnail height to `MAX_THUMB_HEIGHT_PX = 480` on tall pages by shrinking the render width. `WritingActivity.storeContinuePicture` bypasses `thumbWidthPx` and always passes `CONTINUE_WIDTH_PX = 300`, which can produce an oversized bitmap and slow PNG encode during `onPause()` on tall single-page PDFs.

---

## 5. Test Coverage & Testability

### Current Strengths
- **Unit tests (`notes/app/src/test/`)**: 42 test classes thoroughly cover `ChangeCodec`, `ItemCodec`, `StrokeCodec`, `ChangeJournal`, `DocumentSession` (using `FakePdfPages`), `Affine`, `Viewport`, `InkOverlay`, `EraseSettle`, `HoverErase`, `StrokeHandover`, `ContentBounds`, `NaturalOrder`, `FileOps`, `NameIndex`, and `FolderListing`.
- **Instrumentation tests (`notes/app/src/androidTest/`)**: 24 test classes verify real MuPDF rendering, solid-ink-over-highlighter banding, PDF `/Ink` and `/Stamp` round-tripping, `SQLite` `NameIndexDb`, and crash recovery.

### Confirmed Coverage Gaps

1. **No Tests for `WritingActivity`, `LibraryActivity`, `SelectionLayer`, or `LibraryChanges`**:
   - There are zero unit or instrumentation tests for `WritingActivity` (2,258 LOC), `LibraryActivity` (1,095 LOC), `SelectionLayer` (390 LOC), or `LibraryChanges` (308 LOC).
   - Extracting the fast-ink state machine and selection layer controller into pure Kotlin classes would allow testing the most regression-prone part of an Onyx app (fast-ink pause/resume/hold transitions around popup windows, zoom, tools, and page changes).
2. **Missing Single-Axis Shrink Test in `SelectionBoxTest.kt`**:
   - `notes/app/src/test/java/com/fzizzle/epapernotes/tools/SelectionBoxTest.kt:52-59` (`draggingPastTheOppositeSideClampsToTheSmallestAllowedBox`) drags by `dx = -500f, dy = -500f` (both axes negative). There is no test for dragging inward along a single axis (e.g., `dx = -50f, dy = 0f`), which allowed the `max(xRatio, yRatio)` bug in `SelectionBox.kt:43` to go undetected.
3. **Missing `ImageItem` Reach Test in `TapSelectTest.kt` / `EraserTest.kt`**:
   - `notes/app/src/test/java/com/fzizzle/epapernotes/tools/TapSelectTest.kt:59-67` tests `aWiderReachPicksAStrokeFurtherOff` for an `InkItem`, and `tappingInsideAPicturePicksIt` (lines 79–87) taps strictly inside `(40, 40)` of `Box(20, 20, 80, 60)`. There is no test tapping 5pt *outside* an `ImageItem` with `reachPt = 10f`, which would have caught that `Eraser.hit` ignores `radiusPt` on `ImageItem`.
4. **Missing Subnormal Near-Duplicate Point Test in `StrokeOutlineTest.kt`**:
   - `notes/app/src/test/java/com/fzizzle/epapernotes/ink/StrokeOutlineTest.kt:59-67` tests exact duplicate points (`PointF(20f, 10f)` repeated), which `withoutRepeats` catches via `!=`, but does not test two points separated by `1e-25f` where `!=` is true and `hypot(dx, dy) == 0f` produces `NaN`.
5. **Leaked Static `DocumentSession.openSessions` Entries Slow Down the Instrumentation Suite by 45+ Seconds**:
   - In `notes/app/src/androidTest/java/com/fzizzle/epapernotes/doc/DocumentSessionDeviceTest.kt:53, 81, 122, 195`, tests simulate process kills via `scheduler.killNow()`. Because `openSessions` is a static map and the killed session never calls `openSessions.remove(key, myTurn)`, subsequent tests opening the same file (`lines 59, 88, 202`) are forced to sit idle for 15 seconds waiting for `PREVIOUS_SESSION_WAIT_MS` to expire, adding 45 seconds of dead wait time to the test run and polluting `DocumentSession.anyOpen()` for `NameIndexTest`.
6. **Minimal Test Coverage in `probe/`**:
   - `probe/app/src/test/` tests `InputLabels` and `ProbeEvent`, but `ProbeLog` (200-line ring buffer + file output) has no unit tests.
