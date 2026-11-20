# Codebase Review: E-Paper Notes (`notes/`) & Pen Probe (`probe/`)

This review evaluates the Android e-paper note-taking application (`notes/`) and the companion pen-input diagnostic app (`probe/`) strictly from the source files, build scripts, manifests, and tests in this workspace.

Every finding in **Sections 1–6** is a **confirmed problem** observed directly in the code and ranked by impact (**Critical**, **High**, **Medium**, **Low**). **Section 7** separates **speculative ideas and longer-term enhancements** from confirmed issues.

---

## Executive Summary & Overall Assessment

The codebase is remarkably disciplined in its core domain design: pure-Kotlin logic modules (`doc/`, `ink/`, `geometry/`, `tools/`, `library/`, `storage/`) are cleanly decoupled from Android APIs, MuPDF JNI lifetime is unusually careful (with a dedicated static check in `NoRefcountTrapsTest.kt`), file persistence uses atomic write-fsync-rename (`SafeSave`), and e-ink rendering/fast-ink trade-offs are thoughtfully engineered.

At the same time, there are **concrete correctness, concurrency, memory, UX, build-security, and test-validity bugs** that materially affect reliability and responsiveness:

| Rank | Severity | Area | Confirmed Finding | Key Locations |
|---|---|---|---|---|
| 1 | **Critical** | Correctness / Latency | Synchronous `fsync()` + `lock` contention on the UI thread and Onyx pen callback thread during `record()`, `undo()`, and `redo()` | `DocumentSession.kt:401-493`, `ChangeJournal.kt:136-144`, `WritingActivity.kt:1233,1288,1407` |
| 2 | **Critical** | Correctness / UX | `onPause()` stores the "Continue" thumbnail *before* `session.saveNow()`, immediately invalidating its file stamp on every save (and capturing the old path on copy-switch) | `WritingActivity.kt:1964-1966,1976-1991`, `ContinuePicture.kt:29-48` |
| 3 | **High** | Concurrency | Shared mutable `BitmapFactory.Options` (`ThumbnailLoader.SMALL`) mutated concurrently from `LibraryActivity.disk` (`warm`) and `thumbnails` (`load`) threads | `ThumbnailLoader.kt:109,294,443` |
| 4 | **High** | Memory / Correctness | `InkOverlay.left(page)` never evicts strokes on pages without an erase/move (`changedAt == 0`), leaking every written stroke in memory for the session | `InkOverlay.kt:48-55`, `PageView.kt:361-366` |
| 5 | **High** | Correctness / Crash | Symlink handling inconsistency: `LibraryWalk` can stack-overflow on a directory symlink cycle; `FolderListing`/`FolderChooser` show symlinks that `FileOps` refuses | `LibraryWalk.kt:16-25`, `FolderListing.kt:37-52`, `FolderChooser.kt:111-112` |
| 6 | **High** | Correctness / Concurrency | `NameIndexDb.inBatch` uses exclusive transactions (defeating WAL concurrency) and commits partial `put()` writes on `Outcome.ROOT_MISSING` | `NameIndexDb.kt:84-93`, `IndexCheck.kt:63-75,102-105` |
| 7 | **High** | Build Security | Plain-HTTP Maven repository (`http://repo.boox.com/...`) with `isAllowInsecureProtocol = true` and no `content { includeGroup(...) }` scoping | `notes/settings.gradle.kts:16-19`, `probe/settings.gradle.kts:15-18` |
| 8 | **High** | Tests (False Positives) | Multiple tests do not verify what they claim (`erasedStrokeIsGoneFromFile`, `lineCutBetweenPointsIsIgnored`, `aFileArrivingDuringTheListingIsSeen`, `watchingChildrenTwiceKeepsOneObserverEach`, `aHalfWrittenPictureIsNeverLoaded`) | `PdfFileStrokesTest.kt:511-518`, `ChangeJournalTest.kt:45,78-84`, `FolderWatchTest.kt:79-85,112-127`, `ContinuePictureTest.kt:62-67` |
| 9 | **High** | Architecture | God Activities (`WritingActivity.kt` at 2,258 lines; `LibraryActivity.kt` at 1,095 lines) with ~45 mutable fields and zero UI/Activity instrumentation tests | `WritingActivity.kt`, `LibraryActivity.kt` |
| 10 | **High** | Reliability | Permanent `LibraryChanges.busy` deadlock on any `Error` (e.g., `OutOfMemoryError`), plus uncancelled/racy UI callbacks and zero-dimension crashes | `LibraryChanges.kt:158-188`, `StripButton.kt:87-121`, `PageView.kt:236-240` |

---

## 1. Confirmed Correctness Bugs & Risky Code

### Critical

#### 1.1 Synchronous `fsync()` and worker-lock contention on UI and pen threads
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/doc/DocumentSession.kt:401-493`, `notes/app/src/main/java/com/fzizzle/epapernotes/doc/ChangeJournal.kt:136-144,309-321`, `notes/app/src/main/java/com/fzizzle/epapernotes/ui/WritingActivity.kt:1233,1288,1407`
* **Problem**: `DocumentSession.record()`, `undo()`, and `redo()` acquire `synchronized(lock)` and synchronously invoke `journal.append(change)`, which calls `out.fd.sync()` (an unbuffered disk `fsync`) on the **calling thread** (`ChangeJournal.kt:142`). `record()` also calls `journalReady.await()` (`DocumentSession.kt:402`).
  * `WritingActivity` invokes `recordFromScreen()` (move, paste, duplicate, delete at `1288`), `undoOrRedo()` (`1407`), and `selectionListener.traced()` → `write()` (`1233`) directly on the **Android UI thread**.
  * `addStroke()` (`WritingActivity.kt:1066`) invokes `record()` on Onyx's raw-input callback thread.
  * Worse, the background worker thread holds the *same* `lock` during `restartJournal()` (`DocumentSession.kt:1132-1161`), `openOnWorker()` (`262-298`), `switchTo()` (`1097-1114`), and `reloadOnWorker()` (`981-997`) while performing full file rewrites (`SafeSave.write`) + `fsync` + `syncFolder()`.
* **Impact**: Every move, paste, duplicate, delete, undo, redo, or traced stroke executes synchronous flash `fsync()` on the UI thread, and stalls completely whenever a background save or reload is rewriting the journal. On e-paper tablets with slow flash or an active background save of a large journal (e.g., containing PNG snips), this causes input jank, dropped pen events, or ANRs.
* **Fix**: Move journal writes off the UI/pen threads onto a dedicated single-threaded journal/worker queue (or decouple in-memory state + history update from asynchronous journal flush while still preserving strict write ordering before PDF save). Keep `lock` strictly for fast in-memory state transitions; never do disk I/O or `fsync` while holding `lock`.

#### 1.2 `onPause()` invalidates the "Continue" thumbnail on every save
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/WritingActivity.kt:1963-1991`, `notes/app/src/main/java/com/fzizzle/epapernotes/ui/ContinuePicture.kt:29-48`
* **Problem**: In `WritingActivity.onPause()`:
  ```kotlin
  pages.viewport?.let { lastPages.set(path, it.page, pageCount) }
  storeContinuePicture()
  session.saveNow()
  ```
  Both `storeContinuePicture()` (`session.runOnDocument { ... }`) and `session.saveNow()` post to `session`'s FIFO worker thread. Because `storeContinuePicture()` is queued **first**, it renders the page and calls `picture.store(pdfFile, png)`, which writes the **pre-save** `stamp(pdfFile)` (`lastModified` + `length`) into `LibraryPrefs.continueStamp` (`ContinuePicture.kt:34-36`). Immediately afterward, `session.saveNow()` saves the PDF via `SafeSave.write()`, changing both `length` and `lastModified`.
  When `LibraryActivity` resumes and calls `ContinuePicture.load(pdf)` (`ContinuePicture.kt:46`), `prefs.continueStamp != stamp(pdf)`, so `load()` returns `null` and discards the freshly rendered Continue thumbnail.
  Additionally, `storeContinuePicture()` captures `File(path)` on the UI thread (`1978`); if `session.saveNow()` saves to a new copy (`SavedToCopy`), the picture is stored for the old file instead of `session.file`.
* **Impact**: Whenever a user leaves a document with unsaved writing, the Continue card loses its preview picture and falls back to a blank/name-only state (even though the app just spent CPU/disk rendering and compressing a PNG).
* **Fix**: Call `session.saveNow()` **before** `storeContinuePicture()`, and pass/read the session's current file (`session.file` exposed safely or passed into `runOnDocument`) inside the worker callback so the stamp matches the post-save file (including any copy created during save).

---

### High

#### 1.3 Data race on shared mutable `BitmapFactory.Options` in `ThumbnailLoader`
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/ThumbnailLoader.kt:109,294,443`
* **Problem**: `ThumbnailLoader.Companion.SMALL` is a single shared `BitmapFactory.Options` instance (`443`). `BitmapFactory.decodeFile(path, SMALL)` mutates fields on the passed `Options` object (`outWidth`, `outHeight`, `outMimeType`, `outConfig`) on every call. `ThumbnailLoader.warm()` (`109`, called from `LibraryActivity`'s `disk` executor at `LibraryActivity.kt:507`) and `ThumbnailLoader.load()` (`294`, running on the `thumbnails` thread) call `BitmapFactory.decodeFile(..., SMALL)` concurrently.
* **Impact**: Undefined/racy behaviour inside Android's native `BitmapFactory` decoding pipeline (corrupted `out*` metadata, decode failures, or intermittent native/framework errors under concurrent folder open + thumbnail load).
* **Fix**: Allocate a fresh `BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }` per decode (or per thread via `ThreadLocal`). Allocation cost is negligible compared to PNG decoding.

#### 1.4 `InkOverlay.left(page)` leaks all strokes for every page without an erase/move
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/InkOverlay.kt:48-55`, `notes/app/src/main/java/com/fzizzle/epapernotes/ui/PageView.kt:361-366`
* **Problem**: When leaving a page, `PageView.forget(page)` calls `dropRenderings(page)` (discarding all cached and in-flight bitmaps for that page) and then `ink.left(page)`. The next visit will render the page fresh at `ink.version(page)` (which includes all strokes written so far). However, `InkOverlay.left(page)` only executes:
  ```kotlin
  p.strokes.removeAll { it.first < p.changedAt }
  ```
  For normal note-taking without an erase/move/paste on that page, `p.changedAt == 0`, so **zero strokes are ever removed** from `p.strokes` until the entire document is closed or pages are renumbered (`clear()`).
* **Impact**: Every stroke written across every page in a long study session is retained in `InkOverlay` in heap memory (duplicating what is already in MuPDF/disk) and checked on `newerThan()`/`count()`. (Note: `InkOverlayTest.kt:79` codifies this unintended retention; see Section 6.)
* **Fix**: Because `PageView.forget(page)` guarantees no rendering older than `p.version` remains cached or in flight for `page`, `InkOverlay.left(page)` can safely call `p.strokes.clear()` while preserving `p.version` and `p.changedAt`.

#### 1.5 Symlink handling inconsistency and infinite recursion (`StackOverflowError`)
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/library/LibraryWalk.kt:16-25`, `notes/app/src/main/java/com/fzizzle/epapernotes/library/FolderListing.kt:37-52`, `notes/app/src/main/java/com/fzizzle/epapernotes/ui/FolderChooser.kt:111-112` (contrast with `FileIndexDisk.kt:37` and `FileOps.kt:259,279,303`)
* **Problem**: `FileIndexDisk` and `FileOps` explicitly ignore/refuse symbolic links (`Files.isSymbolicLink`), specifically to prevent escaping the library root or looping. However:
  1. `LibraryWalk.walk()` does **not** check `Files.isSymbolicLink`. A directory symlink pointing to an ancestor (or symlink cycle) causes unbounded recursion (`yieldAll(walk(sub))`) and crashes the thumbnail thread with `StackOverflowError` if no PDFs hit the 2,000 limit.
  2. `FolderListing.list()` does **not** filter symlinks, so the UI displays symlinked PDFs/folders that `FileOps` refuses to rename, move, or delete (and that `NameIndex` never indexes).
  3. `FolderChooser` lists symlinked folders (`111-112`), letting the user pick a destination that `FileOps.move` immediately refuses.
* **Fix**: Centralize the "visible library child" predicate (non-hidden, non-symlink, PDF/directory) and use it consistently in `FolderListing`, `LibraryWalk`, `FolderChooser`, and `FileIndexDisk`.

#### 1.6 `NameIndexDb.inBatch` breaks WAL concurrency and commits partial writes on `ROOT_MISSING`
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/NameIndexDb.kt:18,84-93`, `notes/app/src/main/java/com/fzizzle/epapernotes/library/IndexCheck.kt:63-75,102-105`
* **Problem**: Two coupled bugs:
  1. `NameIndexDb.kt:18` states WAL mode lets search queries run concurrently while the background check writes. However, `inBatch()` (`85`) calls `db.beginTransaction()` (**EXCLUSIVE** lock), not `db.beginTransactionNonExclusive()` (**IMMEDIATE** lock). In SQLite on Android, `beginTransaction()` blocks concurrent readers on other connections/threads for the entire batch.
  2. In `IndexCheck.visit()` (`102-105`), `store.put(path, read, clock())` writes to the DB **before** checking `if (vanished.isNotEmpty() && rootMissing()) return Outcome.ROOT_MISSING`. Returning `true` from `inBatch` (`69`) exits `block()` normally, so `NameIndexDb.inBatch` (`88`) calls `db.setTransactionSuccessful()` and **commits** the partial batch instead of rolling it back! If an SD card is removed mid-batch, any earlier folders mutated in that batch are committed.
* **Fix**: Use `db.beginTransactionNonExclusive()` in `NameIndexDb.inBatch`. Check `if (vanished.isNotEmpty() && rootMissing())` *before* `store.put(...)`, and allow `inBatch` to roll back (e.g., return a boolean commit flag or throw a dedicated cancellation signal) when `ROOT_MISSING` aborts a batch.

#### 1.7 `LibraryChanges.busy` deadlocks permanently on `Error` (e.g., `OutOfMemoryError`)
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/LibraryChanges.kt:158-188`
* **Problem**: `runOp()` increments `running++` on the UI thread (`159`), then runs `work()` on `disk` inside `try { ... } catch (e: Exception) { ... }` (`162-167`). `makeFreshAttempt()` opens and rewrites an entire PDF via MuPDF inside `work()`. If MuPDF or bitmap/buffer allocation throws an `OutOfMemoryError` (an `Error`, not an `Exception`), the `catch` block is bypassed, `activity.runOnUiThread { running--; ... }` never runs, and `busy` stays `true` permanently.
* **Impact**: Once `running > 0` leaks, `LibraryActivity` refuses to open any PDF (`LibraryActivity.kt:717`) and refuses all subsequent file operations (`LibraryChanges.kt:37,51,83,104,119,144`) until the user force-kills the app.
* **Fix**: Catch `Throwable` (or decrement `running` unconditionally in a `finally` block posted back to the UI thread), matching the pattern already used in `DocumentSession.save()` (`880`) and `ThumbnailLoader.load()` (`297`).

---

### Medium

#### 1.8 `DocumentSession.setAside()` invokes external callback while holding `lock` and lacks filename collision retry
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/doc/DocumentSession.kt:1264-1268` (called under `lock` at `194,267,275,284,291,1153`)
* **Problem**:
  1. `setAside()` invokes `events(SessionEvent.EarlierWritingKept(kept))` synchronously while holding `lock`. Any listener calling back into a `DocumentSession` method that acquires `lock` will re-enter/deadlock, and if `events(...)` throws (as `DocumentSessionTest.kt:1942` demonstrates), `restartJournal()` aborts *before* `journal.rewrite()` runs, leaving the old journal un-rewritten without setting `journalBroken = true`.
  2. `kept` is named `journal.file.name + ".kept-" + System.currentTimeMillis()` and copied with `overwrite = false` without collision handling (`FileAlreadyExistsException` if called twice in the same millisecond — compare `FileOps.keptName()` at `FileOps.kt:245-252`, which retries with a counter suffix).
* **Fix**: Return the `kept` `File` from `setAside()` and emit `SessionEvent.EarlierWritingKept(kept)` **after** releasing `lock`. Share the collision-safe `keptName()` helper with `FileOps`.

#### 1.9 `DocumentSession` edge-case state bugs (`rotate`, `changePages`, `save`, `reloadOnWorker`, `awaitClosed`)
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/doc/DocumentSession.kt`
* **Problems**:
  1. **`rotate()` lacks `numbering` guard (`683-688`)**: Unlike `addStroke`, `record`, `erase`, and `select`, `rotate(pageNumbers)` does not capture `numbering: Int = pageNumbering` or check `staleNumbering`. A queued rotate after an insert/delete rotates the wrong page indices.
  2. **`changePages()` doesn't cancel/invalidate `REDRAW_INK` (`597-649`)**: `reloadOnWorker()` cancels `REDRAW_INK` and increments `inkPass` (`1017-1018`), but `changePages()` does neither. If `redrawFaultyInkFrom()` is mid-document when a page is inserted or deleted, its `nextPage` index shifts, skipping or re-checking pages.
  3. **`save()` error handling on `newPageNumber != null` (`884-886`)**: When `changePages()` fails *before* `beforeSwap` (e.g., `p.saveTo` fails on a full disk), `save()` still unconditionally calls `dropRenumberingNote()` (rewriting the journal needlessly on a full disk) and emits `SaveFailed`, and then `changePages()` (`642`) ALSO emits `PageNotChanged` — firing two failure notifications for one action.
  4. **`reloadOnWorker()` contradicts its own KDoc (`929-930` vs `980`)**: KDoc says late strokes on the old version are "never moved onto the new version's pages", yet line `980` sets `val toNewNumbers = { if (it < newCount) it else null }`, silently placing a late stroke drawn on the old version's page `k` onto the new version's page `k` (which may now be completely different content).
  5. **`awaitClosed()` doesn't handle `InterruptedException` (`1352-1360`)** (and its KDoc at `1334-1338` was accidentally left above `anyOpenIn()`).

#### 1.10 `PageView.setPages` when `width == 0` has an infinite-post and stale-overwrite race
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/PageView.kt:236-240`
* **Problem**:
  ```kotlin
  if (width == 0) {
      post { setPages(pageSizes, pdfToDisplay, startPage, fit) }
      return
  }
  ```
  If `PageView` is detached or never laid out (`width == 0`), this continuously re-posts itself every frame (`release()` does not cancel it). If `setPages()` is called twice before layout completes (e.g., open followed by reload or trim-margins update), both lambdas stay queued and can execute out of order or overwrite newer state.
* **Fix**: Store a single `pendingSetPages` field and apply it inside `onSizeChanged()` when `w > 0 && h > 0`, clearing it in `release()`.

#### 1.11 `StripButton.onSizeChanged` crashes on zero dimension
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/StripButton.kt:87-121`
* **Problem**: If `onSizeChanged(w, h, ...)` is called with `w == 0` or `h == 0`, `s = minOf(w, h).toFloat()` is `0f`. Line `91` passes `floatArrayOf(0f, 0f)` to `DashPathEffect`, which throws `IllegalArgumentException("intervals must have a positive sum")`, and line `112` divides by `len = 0f` producing `NaN` coordinates.
* **Fix**: Guard with `if (w <= 0 || h <= 0) return` at the top of `onSizeChanged()`.

#### 1.12 Highlighter overlay ignores `stroke.widthScale` (and single-point pen dot bug)
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/PageView.kt:857,869-875`
* **Problem**:
  1. Line `857` sets `inkPaint.strokeWidth = style.maxWidth * pxPerPt` instead of `style.widthAt(1f, stroke.widthScale) * pxPerPt`. Resized highlighters render at unscaled width in the overlay until MuPDF re-renders the page.
  2. Lines `869-875` draw a single-point pen stroke as `canvas.drawLine(x, y, x, y, inkPaint)`. On Android Skia/HWUI, zero-length lines can render nothing depending on backend/transform (the highlighter branch at line `855` explicitly worked around this with `x + 0.01f`, but the pen branch omitted it).
* **Fix**: Use `style.widthAt(1f, stroke.widthScale) * pxPerPt` at line `857`, and use `canvas.drawPoint(x, y, inkPaint)` or `x + 0.01f` for single-point pen strokes.

#### 1.13 `StrokeHandover` non-atomic multi-field `@Volatile` race
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/StrokeHandover.kt:17-61`
* **Problem**: `begun`, `liftedAt`, and `received` are three separate `@Volatile` fields read and written across Onyx's input thread and the UI thread without synchronization. For example, in `cut(now)` (`54-56`), if Onyx's thread calls `begun()` between `if (begun && liftedAt == 0L)` and `liftedAt = now`, the UI thread stamps the old cut time onto the brand-new stroke, potentially allowing a reload while the user is actively writing.
* **Fix**: Either synchronize all methods on `StrokeHandover` (uncontended monitor takes nanoseconds) or pack the state into a single immutable `AtomicReference<State>`.

#### 1.14 `PenInput.showAlone` uncancelled delayed `enterScribbleMode` callback
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/PenInput.kt:136-141`
* **Problem**: `showAlone()` posts an anonymous lambda `handler.postDelayed({ if (!paused && !drawsInk) enterScribbleMode() }, POST_OPEN_MS)` without storing/cancelling it. If `pause()` and `resume()` (or another `showAlone()`) occur within `POST_OPEN_MS` (150ms), the earlier delayed callback still fires early and re-enters scribble mode before the latest window's draw has settled.
* **Fix**: Store a named `Runnable` field and `handler.removeCallbacks(reenterScribble)` in `showAlone()`, `pause()`, and `off()`.

#### 1.15 `LibraryActivity.switchThumbnails` races with `pendingThumbnails` and omits `startDrawingAhead()`
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/LibraryActivity.kt:953-958`
* **Problem**: When disabling thumbnails, `switchThumbnails(false)` closes `thumbnails` and calls `redrawTiles()`, but does **not** clear `pendingThumbnails` or remove `showPendingThumbnails` from `handler`. A queued batch can fire shortly after and stamp bitmaps onto views when thumbnails are off. Conversely, when re-enabling thumbnails (`switchThumbnails(true)`), `startDrawingAhead()` is never called, so background thumbnail generation doesn't resume until the next folder change or resume.
* **Fix**: Cancel `showPendingThumbnails` and clear `pendingThumbnails` when closing `thumbnails`; call `startDrawingAhead()` when turning thumbnails back on.

#### 1.16 Refresh in `ThumbnailLoader` does not clear `.facts` or `factsInMemory`
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/ThumbnailLoader.kt:241-252`
* **Problem**: The `Drop` job deletes `cache.fileFor(pdf)` (`.png`) and removes `memory.remove(key)`, but never deletes `cache.factsFileFor(pdf)` (`.facts`) or removes `factsInMemory[key]`. A user hitting Refresh on a folder gets stale page counts and writing marks if a file's size/mtime didn't change (e.g., same-second edit on FAT/exFAT) or if facts were corrupt.
* **Fix**: Delete the `.facts` file and remove `factsInMemory.remove(key(pdf))` inside `Drop`.

#### 1.17 `PdfFile.apply(ReplaceItems)` skips `p.update()` when moving strokes
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/PdfFile.kt:396-404,507-515`
* **Problem**: `RemoveItems` calls `remove(...)` and returns `UPDATE_PAGE` (`398`) because MuPDF requires `page.update()` after `deleteAnnotation()`. However, `ReplaceItems` (`400-404`) calls `remove(...)` and then returns `add(...)`'s result — which is `LEAVE_PAGE_ALONE` (`515`) whenever all moved items are strokes (no `ImageItem`s). Thus `p.update()` is skipped after deleting the old stroke annotations.
* **Fix**: Return `UPDATE_PAGE` from `ReplaceItems` whenever `move.before.isNotEmpty()` (or unconditionally when `remove()` deletes anything).

#### 1.18 `PdfFile.drawSolidBand` unpremultiplication lacks upper clamp
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/PdfFile.kt:231-236`
* **Problem**: `((s[o].toInt() and 0xff) * 255 / a)` assumes premultiplied RGB channels never exceed `a`. If MuPDF antialiasing/rounding produces a channel value even 1 greater than `a` (possible with certain PDF color spaces/blends), the result exceeds 255 and overflows via `shl 16` / `shl 8` into the adjacent color channel, creating bright color-speckle artifacts.
* **Fix**: Add `.coerceAtMost(255)` to each channel before shifting.

#### 1.19 `StrokeCodec` / `ItemCodec` accept `NaN`/`Infinity` and non-positive `widthScale`
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ink/StrokeCodec.kt:79-105`, `notes/app/src/main/java/com/fzizzle/epapernotes/ink/ItemCodec.kt:58`
* **Problem**: Kotlin's `String.toFloatOrNull()` parses `"NaN"`, `"Infinity"`, and `"-Infinity"` as valid floats. Neither `StrokeCodec` nor `ItemCodec` checks `.isFinite()`, and `StrokeCodec` accepts `widthScale <= 0f`. A malformed/corrupt journal or PDF annotation containing `NaN` propagates into `StrokeOutline.kt:108-122` and writes `NaN` operators into PDF content streams.
* **Fix**: Reject `!x.isFinite()` (and `widthScale <= 0f`, `pressure !in 0f..1f`, `x0 > x1` / `y0 > y1`) during decoding.

#### 1.20 Filename validation and `StrandedRename` length bugs in `FileOps`
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/library/FileOps.kt:83-85,188,284-289,334`
* **Problems**:
  1. `StrandedRename.hiddenName()` (`334`) prepends `.` and appends `.pnotes-rename` (15 extra bytes) without checking the 255-byte UTF-8 filesystem limit (contrast `SafeSave.tempFileFor`, which explicitly trims to 255 bytes). Case-only renaming a file whose name is >240 bytes fails.
  2. `nameProblem()` (`284-289`) only checks blank, `/`, `\`, leading `.`, and `..`. It does not check the 255-byte limit, NUL/control characters, or FAT/exFAT-forbidden characters (`? * : < > | "`) that fail on SD cards with an unhelpful generic error.
  3. `rename()` (`83-85`) runs the `StrandedRename` leftover-name check even when the rename is *not* case-only, producing a confusing refusal message if an unrelated stranded file happens to share the target name.

#### 1.21 `WritingActivity` subtle lifecycle, resource-leak, and mapping bugs
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/WritingActivity.kt`
* **Problems**:
  1. **Bitmap leaks on exception (`841-857`, `1247-1274`)**: Both `printedAreaOf()` and `captureSnip()` call `bitmap.recycle()` outside a `try/finally`, leaking native bitmap memory if `getPixels()` or `compress()` throws; both also catch `Exception` rather than `Throwable` (so OOM crashes the worker thread).
  2. **Duplicate `pageArrived()` calls (`706,709,1674`)**: `setPages()` invokes `onViewportChanged()` which calls `pageArrived()`, and then `layOutPages()` calls `pageArrived()` again right after (`709`), running trim-margins measurement and hover/state setup twice on open and on shorter-PDF reload (`1674`).
  3. **Unlatched `penMapping` in `onHoverEraseTrail` (`1025`)**: Unlike regular pen strokes which latch `penMapping` at pen-down (`996`), hover-erase converts the trail using the *current* `penMapping` at trail end. If a zoom/refit/page turn occurred while dragging, the erase hits the wrong coordinates or page.
  4. **Armed pen paste taps allowed off-page (`SelectionLayer.kt:231`)**: Finger long-press paste checks page bounds (`offerPaste`), but pen-tap paste when paste is armed does not check whether the tap is inside the page bounds, pasting items into the grey margin outside the page.

#### 1.22 `LibraryActivity` main-thread disk I/O, unchecked list indexing, and incomplete `reread`
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/LibraryActivity.kt:217,421,425,449,551`, `notes/app/src/main/java/com/fzizzle/epapernotes/ui/LibraryChanges.kt:112-117,139-140`
* **Problems**:
  1. **Main-thread disk I/O**: `LibraryActivity.kt:217` (`last?.isFile`) and `449` (`root?.isDirectory`) hit storage synchronously on the UI thread (potentially stalling on an unmounted/slow SD card).
  2. **Unchecked `entries[position]` (`421,425`)**: Tile click/long-click listeners index `entries[position]` without a bounds check (`entries.getOrNull(position)`); rapid clicks during a refresh/folder switch can throw `IndexOutOfBoundsException`.
  3. **Incomplete `reread` for opened-out subfolders (`551`)**: `index?.reread(listOf(shown))` only rereads `shown`, not any small topic subfolders opened out inline by `FolderListing.OPEN_OUT_UP_TO`. Changes inside opened-out folders are missed by the name index until a full check.
  4. **Stale `ContinuePicture` on delete (`LibraryChanges.kt:139-140`)**: Deleting the last-opened file does not clear `ContinuePicture`, and `askToDelete` for folders runs `pdfCountInside` asynchronously while `busy` is still `false`, allowing concurrent operations before the dialog even appears.

---

### Low

#### 1.23 Native MuPDF `Buffer` objects not explicitly destroyed in `ItemAnnotations`
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/ItemAnnotations.kt:160,221,272,306`
* **Problem**: While the codebase diligently destroys `Pixmap`, `DisplayList`, `DrawDevice`, `PDFAnnotation`, and `PDFPage`, `Buffer` instances created via `Buffer()` (`306`) and returned by `pixmap.asPNG()` (`272`) are never `.destroy()`ed, relying on GC finalization for native memory.
* **Fix**: Wrap `Buffer` usage in `try { ... } finally { buf.destroy() }`.

#### 1.24 Minor edge-case bugs
* **`Viewport.scaleToFit` divide-by-zero (`Viewport.kt:193-194`)**: If a malformed PDF page has `w == 0f` or `h == 0f` (or a zero-area fit box), `viewWidthPx / (box.x1 - box.x0)` produces `Infinity`/`NaN`.
* **`FolderWatch.stop()` callback race (`FolderWatch.kt:86-103`)**: An in-flight `FileObserver.onEvent()` on Android's inotify thread can call `changed()` *after* `stop()` executes `handler.removeCallbacks(fire)`, re-arming a firing callback after stop. Guard with an `@Volatile var stopped = false` checked inside `fire` and `changed()`.
* **`FileIdentity.of()` byte-count mismatch (`FileIdentity.kt:29-41`)**: Uses `file.length()` queried separately rather than summing the exact bytes fed into SHA-256; if the file is concurrently growing, the returned `size` and `sha256` can disagree.
* **`CopyNames` extension handling (`CopyNames.kt:20-35`)**: `myEdits()` hardcodes `.pdf` instead of preserving `${original.extension}` (unlike `attempt()`), and `attempt()` produces a trailing dot (`name (attempt 2).`) if a file has no extension.
* **`probe/` issues**: `MainActivity.kt:28` force-unwraps `getExternalFilesDir(null)!!` (crashes if external storage is unavailable); `ProbeLog.kt:30` does synchronous `file.appendText(...)` on every 240 Hz motion event (distorting the very latency it probes!); `ProbeSurface.kt:112,117` replaces `ink` bitmap on resize without recycling the previous one; `targetSdk = 35` (`probe/app/build.gradle.kts:15`) triggers Android 15 edge-to-edge without applying window insets, causing UI overlap with system bars.

---

## 2. Performance & Memory (Rendering, Pen Latency, Library)

### High

#### 2.1 Hardcoded 200 MB bitmap cache with no `onTrimMemory` / `onLowMemory` eviction
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/PageView.kt:158`, `notes/app/src/main/java/com/fzizzle/epapernotes/ui/ThumbnailLoader.kt:431`, `notes/app/src/main/java/com/fzizzle/epapernotes/pdf/PdfFile.kt:612`
* **Problem**: `PageView` allocates up to **200 MB** in `PageBitmaps`, plus up to **15 MB** for a zoomed sharp patch, plus MuPDF holds up to **64 MB** (`MAX_MEMORY_PDF_BYTES`) of PDF bytes in native heap, plus `LibraryActivity`'s `ThumbnailLoader` holds up to **40 MB** (`MEMORY_BYTES`) when returning to the library — and neither `NotesApp` nor the Activities implement `onTrimMemory()` / `onLowMemory()` to shrink caches under memory pressure. On 3–4 GB RAM e-paper tablets, this pushes the process close to LMK kills and increases GC pauses.
* **Fix**: Size `PageBitmaps` proportionally to `ActivityManager.memoryClass` (with a sensible cap), and implement `ComponentCallbacks2.onTrimMemory` in `WritingActivity` and `LibraryActivity` to trim non-visible pages/thumbnails when backgrounded or under memory pressure.

#### 2.2 `O(N²)` directory scans when building the thumbnail cache
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/library/ThumbnailCache.kt:59-60`
* **Problem**: Every call to `ThumbnailCache.store()` calls `dir.list()` to find and delete older versions of that file's thumbnail. When drawing thumbnails ahead across a library of `N = 2,000` PDFs, `dir` grows to ~6,000 files (`.png`, `.facts`, `.path`), so `dir.list()` scans `O(N)` entries **per PDF** — roughly `6,000,000` directory entry reads total.
* **Fix**: Maintain an in-memory map of `prefix -> currentFileName` (or sweep old versions during `pruneMissing()` / only when an existing cache entry is known to be replaced, e.g., by recording the current filename in `.path` or a small index).

#### 2.3 Redundant full SQLite `LIKE` scans and double sorting during search
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/NameIndexDb.kt:99-104`, `notes/app/src/main/java/com/fzizzle/epapernotes/ui/NameIndex.kt:290-291`, `notes/app/src/main/java/com/fzizzle/epapernotes/library/NaturalOrder.kt:15-31`
* **Problem**:
  1. `NameIndexDb.search()` runs **two** separate full-table `LIKE '%word%'` scans (`SELECT COUNT(*)` then `SELECT ... ORDER BY ... LIMIT 5000`) on every keystroke.
  2. `NameIndexDb.search()` sorts the result with `NameMatch.arrange(hits, cap)` (`104`), and then `NameIndex.found()` (`290-291`) calls `NameMatch.arrange(...)` a **second time** on the already-arranged list.
  3. `NameMatch.arrange()` splits folder paths on `'/'` inside the comparator (`O(M log M)` list allocations) and `NaturalOrder` allocates `BigInteger` objects for digit runs on every comparison.
* **Fix**: Remove the duplicate `NameMatch.arrange()` call; compare digit runs by length + lexicographical scan (zero `BigInteger` allocations); compare path segments directly by index scan without `split('/')`; consider SQLite FTS5 or a single query fetching `cap + 1` rows when exact total count past 200 isn't strictly required (or compute `COUNT(*)` only if results hit `cap`).

### Medium

#### 2.4 `Lasso` and `Eraser` inner-loop inefficiencies
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/tools/Lasso.kt:10-29`, `notes/app/src/main/java/com/fzizzle/epapernotes/tools/Eraser.kt:29,37-49`
* **Problem**:
  1. `Lasso.selects()` runs ray-casting point-in-polygon over every point of every stroke on the page without a bounding-box rejection test first. On dense note pages (thousands of points across hundreds of strokes), a simple polygon-bbox vs. stroke-bbox check eliminates >90% of work.
  2. `Eraser.strokeHits()` computes the stroke's bbox in 4 separate passes (`minOf`/`maxOf`) plus a 5th pass allocating `Pt` objects in the inner loop (`43-49`).
  3. **Behavioural bug in `Eraser.kt:29`**: For `ImageItem`, `Eraser` checks exact box containment (`trail.any { it.x in b.x0..b.x1 && it.y in b.y0..b.y1 }`) and completely ignores `radiusPt` (as well as trail segments crossing a narrow snip between sample points). Because `TapSelect.pick()` delegates to `Eraser.hits()` (`TapSelect.kt:46`), tapping near the edge of a small snip does not benefit from `reachPt`.

#### 2.5 `ChangeJournal` peak heap multiplication on large snip journals
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/doc/ChangeJournal.kt:115-124,176,256`
* **Problem**: `read()` reads the entire journal into a single `String` via `file.readText()` (`176`) and `split('\n')`; `rewrite()` builds the entire journal in memory in a `StringBuilder` (`115-124`) before writing. With PNG snips Base64-encoded inline, a journal with 10–20 snips can easily reach 10–20 MB, spiking Java heap by 3–4× during read/rewrite. Streaming line-by-line with `BufferedReader` / `BufferedWriter` cuts peak memory to O(single change).

#### 2.6 `PageView` rendering / pen-latency observations
* **Positive**: The separation between Onyx's hardware raw-input fast-ink, `InkOverlay` (zero-allocation point mapping in `drawStroke`), and deferred MuPDF baking (`BAKE_AFTER`) is well designed for e-ink.
* **Confirmed bottleneck**: Aside from the UI/pen-thread `fsync()` in **1.1** (which is the single largest latency risk), `PageView.drawStroke` (`865-875`) calls `canvas.drawLine()` once *per point segment* with a changing `strokeWidth`. On a densely inked page before baking (up to 40 strokes × hundreds of points), this issues thousands of JNI `drawLine` calls per invalidate. Batching consecutive segments with nearly identical width or capping `BAKE_AFTER` by total point count (not just stroke count — one long scribble can have 2,000 points) prevents UI-thread draw stalls.

---

## 3. Architecture & Maintainability

### High

#### 3.1 God Activities: `WritingActivity` (2,258 lines) and `LibraryActivity` (1,095 lines)
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/WritingActivity.kt`, `notes/app/src/main/java/com/fzizzle/epapernotes/ui/LibraryActivity.kt`
* **Problem**: While the lower-level domain packages (`doc/`, `ink/`, `tools/`, `library/`) are impressively modular and unit-tested, all orchestration collapses into two monolithic Activities:
  * `WritingActivity` is **2,258 lines** with ~45 mutable fields mixing: toolbar/strip construction, Onyx fast-ink pause/resume/handover state machine, clean-refresh scheduling, erase-settle debounce, reload-on-external-change hold, trim-margins background measurement, snip capture, undo/redo, page add/delete/rotate, progress bar, and error banners.
  * `LibraryActivity` is **1,095 lines** mixing: volume switching, folder navigation, tile/list adapter & view binding, thumbnail batching, live search overlay, Continue card, settings dialogs, and permission/storage-grant handling.
* **Impact**: Subtle cross-feature bugs (like **1.2** `onPause` ordering, **1.15** `switchThumbnails` stale queue, **1.21** duplicate `pageArrived` and unlatched hover-erase mapping) thrive in these huge stateful Activities, and none of this orchestration can be JVM-tested.
* **Fix**: Extract cohesive, unit-testable controllers:
  * From `WritingActivity`: `FastInkController` (scribble mode, `StrokeHandover`, `EraseSettle`, clean refresh, reload hold), `TrimMarginsController`, and `SnipSelectionController`.
  * From `LibraryActivity`: `LibraryViewModel` / state holder + dedicated `LibraryAdapter` and `SearchController`.

### Medium

#### 3.2 Static global registry `DocumentSession.openSessions` couples lifecycle and leaks in tests
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/doc/DocumentSession.kt:1332-1360`
* **Problem**: `openSessions` is a `companion object` `ConcurrentHashMap` queried globally by `FileOps`, `FolderListing`, `NameIndex`, and new `DocumentSession` instances. Any unclosed session (especially in tests or after an abnormal error) permanently makes `DocumentSession.anyOpen()` return `true`, stalling `NameIndex` checks and `FileOps` waits across the process.
* **Fix**: Encapsulate active-session tracking in an injectable `SessionRegistry` owned by `NotesApp` (with a fresh instance per test).

#### 3.3 `WritingActivity` directly instantiates `LibraryPrefs(this)` in multiple scattered places
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/WritingActivity.kt:1979,2100,2119` (and `onCreate`)
* **Problem**: Rather than holding a single `LibraryPrefs` field (or injecting it), `WritingActivity` repeatedly constructs `LibraryPrefs(this)` inline across methods, scattering preference mutations (`lastOpened`, `launchOpenUnfinished`, `continueStamp`).

#### 3.4 `LibraryWalk` / `FolderListing` / `FileIndexDisk` duplicate file-filtering rules
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/library/LibraryWalk.kt`, `FolderListing.kt`, `FileIndexDisk.kt`, `FolderChooser.kt`
* **Problem**: Four separate places reimplement "is this a visible PDF or subfolder?", which directly caused the symlink divergence in **1.5**. A single `LibraryFiles.classify(file)` helper eliminates drift.

---

## 4. UX Visible in Code

### Medium

#### 4.1 Disabling "Show thumbnails" silently breaks writing marks and page counts in List view
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/LibraryActivity.kt:101,143,253,953-958`
* **Problem**: `ThumbnailLoader` is responsible for both rendering thumbnails AND loading `PdfFacts` (page count + has-writing dot). When the user turns off "Show thumbnails", `LibraryActivity` closes and nulls out `thumbnails`, which also stops loading `PdfFacts` — so page counts and writing marks silently vanish in List view too.
* **Fix**: Decouple `PdfFacts` loading from bitmap thumbnail rendering (or keep `ThumbnailLoader` running in facts-only mode when thumbnails are disabled).

#### 4.2 First save failure shows only a transient Toast; persistent "Not saved" banner waits for failure #2
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/WritingActivity.kt:2049-2056`
* **Problem**: If the first save fails (e.g., SD card write-protected, removed, or full), the user only gets a 3-second Toast; `setNotSaving(...)` is not called until `++failedSaves >= 2`, which won't happen for 30 seconds (`RETRY_DELAY_MS`). On e-ink tablets, a Toast can easily be missed while writing or looking away from the screen, and if the user leaves the document before the retry fires, they never see the persistent warning.
* **Fix**: Consider surfacing a subtle persistent indicator on the first failure (or immediately for deterministic failures like missing/read-only storage), while keeping the existing escalation message for repeated failures.

#### 4.3 `SelectionLayer` action bar clips off-screen on narrow windows
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/SelectionLayer.kt:264-273`
* **Problem**: Bar positioning computes `left = ((box.x0 + box.x1 - whole) / 2f).coerceIn(margin, viewWidth - margin - whole)`. If `viewWidth - margin - whole < margin` (e.g., split-screen or narrow portrait with large font scale), `coerceIn` throws `IllegalArgumentException("Cannot coerce value to an empty range")`!
* **Fix**: Clamp with `maxOf(margin, viewWidth - margin - whole)` (or fall back to `0f` and shrink/wrap buttons when `whole > viewWidth`).

#### 4.4 `PagePad` / `PageEntry` accepts digits beyond `pageCount` visually before clamping on Go
* **Files**: `notes/app/src/main/java/com/fzizzle/epapernotes/ui/PageEntry.kt`, `notes/app/src/test/java/com/fzizzle/epapernotes/ui/PageEntryTest.kt:38`
* **Problem**: For a 1-page document (or 30-page document), typing `7` (or `99`) displays `"7"` (or `"99"`) in the pad and only clamps to `1` (or `30`) upon pressing Go. That's minor, but disabling out-of-range digits or showing the clamped target makes the keypad feedback clearer.

---

## 5. Build Configuration & `probe/`

### High

#### 5.1 Plain-HTTP Maven repository (`http://repo.boox.com/...`) without group scoping
* **Files**: `notes/settings.gradle.kts:16-19`, `probe/settings.gradle.kts:15-18`
* **Problem**: Both builds declare:
  ```kotlin
  maven {
      url = uri("http://repo.boox.com/repository/maven-public/")
      isAllowInsecureProtocol = true
  }
  ```
  with **no** `content { includeGroup("com.onyx.android.sdk") }` restriction and no Gradle dependency verification (`verification-metadata.xml`). Any dependency lookup not satisfied by earlier repositories (or dynamic/snapshot checks, missing POMs, transitive artifacts) can fall back to unencrypted HTTP, exposing the build to MITM tampering.
* **Fix**: Scope the Boox repo strictly via `content { includeGroupByRegex("com\\.onyx\\..*") }` (or the exact Onyx artifact groups) and use HTTPS if the server supports it; enable Gradle dependency verification checksums for third-party artifacts.

### Medium

#### 5.2 Release build signed with debug key, R8/ProGuard disabled, and `arm64-v8a`-only ABI filter
* **Files**: `notes/app/build.gradle.kts:17-31`
* **Problems**:
  1. `signingConfig = signingConfigs.getByName("debug")` (`27`) on `release`: fine for personal sideloading, but risky if ever distributed or upgraded (different machines generate different debug keystores, breaking in-place APK upgrades and risking app-data loss if the user has to uninstall).
  2. `isMinifyEnabled = false` (`26`): ships unshrunk Onyx SDK + AndroidX + MuPDF Java wrappers, increasing APK size and startup classloading time on e-ink devices.
  3. `abiFilters += "arm64-v8a"` (`21`): applied unconditionally to all variants, which prevents running `androidTest` on x86_64 emulators in CI (unless guarded by a property or restricted to `release`).
* **Fix**: Keep a local release keystore path read from `local.properties` / environment variables; consider enabling R8 with explicit keep rules for Onyx/MuPDF JNI callbacks; gate `abiFilters` so debug/test builds can run on x86_64 emulators if desired.

#### 5.3 Deprecated `android.enableJetifier=true` and unused JitPack repo in `probe/`
* **Files**: `notes/gradle.properties:4`, `probe/gradle.properties:4`, `probe/settings.gradle.kts:13`
* **Problems**:
  1. `android.enableJetifier=true` is deprecated and scheduled for removal in AGP 9+. It is presumably needed for the legacy Onyx SDK (`onyxsdk-pen:1.4.11` / `onyxsdk-device:1.2.28`), which is a real upgrade blocker to document and isolate.
  2. `probe/settings.gradle.kts:13` declares `maven("https://jitpack.io")` even though `probe/app/build.gradle.kts` has no JitPack dependencies.
  3. `notes/` and `probe/` duplicate Gradle wrapper, AGP/Kotlin versions, and repository blocks rather than sharing a root multi-module build or version catalog (`libs.versions.toml`).

#### 5.4 Benchmark / manual device checks live inside `src/androidTest` with no runner filter
* **Files**: `notes/app/build.gradle.kts`, `notes/app/src/androidTest/java/com/fzizzle/epapernotes/check/*.kt`
* **Problem**: Five manual/device-specific benchmark classes (`FountainWidthCheck`, `SearchCheck`, `SpeedCheck`, `ThumbnailCheck`, `ZoomCheck`) sit in `src/androidTest` alongside real instrumentation tests. Running `./gradlew connectedDebugAndroidTest` executes all of them (and `MuPdfStressTest`'s 600+ forced-GC iterations).
* **Fix**: Tag manual/perf checks with a custom annotation (e.g., `@DeviceCheck` / `@LargeTest`) and exclude them by default via `testInstrumentationRunnerArguments`, or move them to a separate source set / flavor.

---

## 6. Test Suite Review (JVM `src/test` & Device `src/androidTest`)

Overall, the pure-Kotlin JVM test suite (`notes/app/src/test`, 51 files) is unusually thorough for domain invariants (`DocumentSessionTest`, `FileOpsTest`, `IndexCheckTest`, `ViewportTest`, `ContentBoundsTest`, `StrokeOutlineTest`, `NoRefcountTrapsTest`). However, a close line-by-line review uncovered **confirmed false-positive tests**, **flaky timing**, **cross-test pollution**, and **large untested areas**.

### 6.1 False-Positive / Broken Tests (Do NOT Verify What They Claim) — High Impact

1. **`PdfFileStrokesTest.erasedStrokeIsGoneFromFile` (`notes/app/src/androidTest/java/com/fzizzle/epapernotes/pdf/PdfFileStrokesTest.kt:511-518`)**
   * **Claim**: Checks that after erasing a stroke and saving, `"PNotesStroke"` no longer appears in the PDF file bytes (`!String(second.readBytes(), ISO_8859_1).contains("PNotesStroke")`).
   * **Why it's broken**: `PdfFile.saveTo()` saves with `"compress,garbage,objstms"`, which Flate-compresses annotation dictionaries into PDF object streams! Therefore `"PNotesStroke"` does **not** appear in the raw compressed bytes even **before** erasing. The test would pass even if erasing left the annotation completely intact in the PDF.
   * **Fix**: Assert using MuPDF object inspection (or decompress streams / inspect `page.annotations` and `pdf.countObjects()` / `pdf.loadObject(i)` for `/PNotesStroke`), AND first verify `"PNotesStroke"` IS found before erasing.

2. **`ChangeJournalTest.lineCutBetweenPointsIsIgnored` & `tornLastLineIsIgnored` (`notes/app/src/test/java/com/fzizzle/epapernotes/doc/ChangeJournalTest.kt:45,78-84`)**
   * **Claim**: Verifies that a truncated line without a trailing newline is ignored.
   * **Why it's broken**: The test writes a `pnj2` journal, then appends `"add 1 pn1 hl 1.00,2.00,1.000"` — which is a legacy `pnj1` line (missing the `id` field and `s:` prefix required by `pnj2`). Even *with* a trailing `\n`, `ChangeCodec` rejects that line as unparseable! Thus `lineCutBetweenPointsIsIgnored` passes even if the missing-newline guard in `ChangeJournal.read()` is completely deleted.
   * **Fix**: Append a valid `pnj2` line without its trailing `\n`, assert it is ignored, and also assert the exact same line *with* `\n` IS parsed.

3. **`FolderWatchTest.aFileArrivingDuringTheListingIsSeen` (`notes/app/src/androidTest/java/com/fzizzle/epapernotes/library/FolderWatchTest.kt:79-85`)**
   * **Claim**: Tests that a file arriving *during* listing is noticed by `FolderWatch`.
   * **Why it's broken**: It starts `watch`, calls `FolderListing.list(scratch)` synchronously, and then creates `new.pdf` *after* `list()` has already returned. That only tests normal post-listing file creation, not arrival during listing.

4. **`FolderWatchTest.watchingChildrenTwiceKeepsOneObserverEach` (`notes/app/src/androidTest/java/com/fzizzle/epapernotes/library/FolderWatchTest.kt:112-127`)**
   * **Claim**: Verifies that calling `watchChildren()` twice does not register duplicate `FileObserver`s.
   * **Why it's broken**: It creates one file and asserts `calls.get() == 1` after 1,000ms. Because `FolderWatch` debounces all events into a single callback after 400ms (`FolderWatch.kt:23,82`), two (or ten!) duplicate `FileObserver`s firing at the same time still coalesce to `calls.get() == 1`. The test cannot detect duplicate observers.
   * **Fix**: Expose an internal observer count for testing (or inject an observer factory) and assert the exact number of active observers.

5. **`ContinuePictureTest.aHalfWrittenPictureIsNeverLoaded` (`notes/app/src/androidTest/java/com/fzizzle/epapernotes/ui/ContinuePictureTest.kt:62-67`)**
   * **Claim**: Tests that a half-written Continue picture is never loaded.
   * **Why it's broken**: It manually creates a stray file `.continue-123.tmp` in `cacheDir` without ever calling `store()` or writing to `continue.png`. `load()` only looks at `continue.png`, so this passes trivially regardless of whether `store()` is atomic.

6. **`DocumentSessionTest.autosaveWaitsTenSeconds` (`notes/app/src/test/java/com/fzizzle/epapernotes/doc/DocumentSessionTest.kt:1018`)**
   * **Claim**: Verifies autosave waits 10 seconds.
   * **Why it's weak**: `FakeScheduler.postDelayed` completely ignores `delayMs` (`Fakes.kt`), so the test just asserts `assertEquals(10_000L, DocumentSession.AUTOSAVE_DELAY_MS)` — it doesn't verify `scheduleAutosave()` actually passes `AUTOSAVE_DELAY_MS` (or cancels/resets the timer on each stroke).

7. **`IndexCheckTest` + `MemoryIndexStore` masks the `ROOT_MISSING` transaction-commit bug (`IndexCheckTest.kt:161-184`, `MemoryIndexStore.kt:24`)**
   * **Why it's misleading**: `MemoryIndexStore.inBatch` is just `block()` (no rollback), and the test only verifies that *drops* didn't happen — it never mutates a folder earlier in the same batch to verify that `put()` writes are rolled back when `ROOT_MISSING` occurs (see **1.6**).

8. **`InkOverlayTest.strokesSurviveAChange` (`notes/app/src/test/java/com/fzizzle/epapernotes/ui/InkOverlayTest.kt:69-80`)**
   * **Why it's flawed**: Line `79` asserts `assertEquals(listOf(stroke(2)), ink.newerThan(0, 0))` *after* `ink.left(0)`, codifying the stroke memory leak described in **1.4**.

---

### 6.2 Flaky Timing, Wall-Clock Assertions & Device-Specific Hardcoding — High Impact

1. **Racy `ThumbnailLoaderTest` synchronization (`notes/app/src/androidTest/java/com/fzizzle/epapernotes/ui/ThumbnailLoaderTest.kt:258-305`)**:
   * `drawingAheadStopsWhenPaused` (`258-269`) polls every 50ms waiting for `draws >= 2`; on a fast device all 20 blank PDFs can finish in <50ms before the loop pauses, causing the test to fail.
   * `onScreenTilesGoFirst` (`279-286`) races `drawAhead()` against `show()` without a barrier.
   * `drawingAheadStopsWhenTheCardGoesOut` (`290-305`) increments `draws++` inside `render()` *before* `cache.store()` finishes; the test then asserts `pictures.size >= 3` right after `pause()`, racing against the final `store()`.
2. **Hardcoded wall-clock performance thresholds in `androidTest`**:
   * `ThumbnailRenderTest.kt:22` (`ms < 300`), `PageLookDeviceTest.kt:33,58` (`ms < 80`), `NameIndexDbTest.kt:164` (`median < 100`), `SpeedCheck.kt:144` (`late < 3 * early`). Any thermal throttle or background OS job causes spurious CI/device failures.
3. **Hardcoded personal tablet paths in `src/androidTest/check/`**:
   * `SearchCheck.kt` (`/storage/3331-3031`), `ThumbnailCheck.kt` (`/storage/3331-3031/Workbooks`), `ZoomCheck.kt` (`/sdcard/Documents/E-paper Notes test/PWT test copy.pdf`), and `FountainWidthCheck.kt` (has **zero assertions** — only logs).
4. **Heavy `Thread.sleep` usage**:
   * `FolderWatchTest.kt` sleeps 15+ seconds total across fixed `Thread.sleep(1000..1200)` calls.
   * `NameIndexTest.kt:205-265,294` uses `delayMs = 10/30` with `Thread.sleep` instead of latches/barriers.
   * `FileOpsTest.kt:235,313,439,517` uses four `Thread.sleep(300)` calls instead of coordinating via a latch when `awaitClosed` begins waiting.

---

### 6.3 Cross-Test State Pollution & Silent Skips — Medium Impact

1. **Leaked `DocumentSession.openSessions` across tests**:
   * `DocumentSessionDeviceTest.kt:53,81,121,195` intentionally abandons ("kills") sessions without calling `close()` and without passing a short `previousSessionWaitMs`. Each subsequent session on the same path waits out the full **15-second** `PREVIOUS_SESSION_WAIT_MS` timeout (wasting 45+ seconds per test run), and leaked entries stay in `DocumentSession.openSessions` for the rest of the instrumentation process — which even forced `NameIndexTest.kt:68` to pass `pause = { false }` to bypass `DocumentSession::anyOpen`!
   * Similarly, many tests in `DocumentSessionTest.kt` never call `session.close()`, leaving entries in the static `openSessions` map for the entire JVM test run.
2. **`LastPageStoreTest` mutates real app SharedPreferences (`notes/app/src/androidTest/java/com/fzizzle/epapernotes/ui/LastPageStoreTest.kt`)**:
   * Writes directly into the app's production `LastPageStore` preferences on the test device instead of using a test-isolated preference name, and never tests `pageCount` persistence or `move()` preserving `pageCount`.
3. **`NameIndexTest` deletes DB while worker thread is still shutting down (`NameIndexTest.kt:59-63`)**:
   * `tearDown` calls `index.close()` (which posts async shutdown) and immediately deletes the database file without awaiting thread termination.
4. **Silent skips under root**:
   * `DocumentSessionTest.kt:1407,1500,1686`, `FileOpsTest.kt:146`, `FileIndexDiskTest.kt:45`, `IndexCheckTest.kt:240` use `assumeFalse`/`assumeTrue` for `setReadable(false)`/`setWritable(false)`, silently skipping failure-path coverage on rooted Android devices or root CI containers.

---

### 6.4 Important Behaviour With No Test Coverage At All — High Impact

1. **Zero Activity / UI integration tests**: `WritingActivity` (2,258 lines), `LibraryActivity` (1,095 lines), `LibraryChanges`, `FolderChooser`, `PagePad`, `SearchPreviewView`, `SelectionLayer`, `StripButton`, `IslandRow`, `StripRow`, `CentredStrip`, and `PanelRefresh` have **no** instrumentation or Robolectric tests. Every confirmed bug in **1.2**, **1.7**, **1.11**, **1.12**, **1.14**, **1.15**, **1.21**, **1.22**, and **4.3** lives in this completely untested UI layer.
2. **`PageView` core mechanics untested**: `PageViewReleaseTest.kt` only tests `release()`. There are no tests for `PageView`'s touch/pinch/pan gesture handling, `setPages(width == 0)` deferral, `refitWhenDrawn` / `takeWaitingFit`, sharp-patch rendering, `BAKE_AFTER` quiet re-render, or `drawStroke` (which missed the highlighter `widthScale` bug in **1.12**).
3. **`PenInput` untested**: Only `PenButtonTest` exists for button mapping; `PenInput`'s scribble lifecycle, `showAlone()` delayed re-entry, `hoverErase` integration, and exclude-rect updates have no tests.
4. **Symlink tests missing for `FolderListing` and `LibraryWalk`**: `FileOpsTest` and `IndexCheckTest` explicitly test symlinks, but `FolderListingTest` and `LibraryWalkTest` do not (which let **1.5** slip through).
5. **Untested edge cases in tested modules**:
   * `FileOps`: filenames >240 UTF-8 bytes (case-only rename), FAT/exFAT illegal characters, `CopyNames.attempt` on extensionless files.
   * `PdfFile`: `ReplaceItems` moving strokes only (verifying annotation deletion persisted after reopen without extra changes).
   * `StrokeCodec` / `ItemCodec`: `NaN` / `Infinity` / `widthScale <= 0` inputs.
   * `Viewport`: degenerate zero-width/zero-height page or fit box.
   * `ThumbnailLoader`: concurrent `warm()` + `load()` stress test; Refresh clearing `.facts`.
   * `probe/`: `ProbeLog` and `ProbeSurface` have no tests (only `InputLabelsTest` and `ProbeEventTest` exist).

---

## 7. Speculative Ideas & Longer-Term Enhancements

*(Separated from confirmed bugs above — these are architectural and product improvements worth considering once confirmed issues are resolved.)*

1. **Incremental PDF saving (`incremental` MuPDF flag) for autosaves**:
   * `DocumentSession.kt:1308` notes that every autosave rewrites a full 14 MB lecture PDF (`compress,garbage,objstms`), which forced autosave delay up to 10s. Writing **incremental updates** on autosave (and compacting with `garbage,objstms` only on close or once incremental bloat exceeds a threshold) could cut autosave latency from hundreds of milliseconds to single-digit milliseconds and reduce flash wear. *(Requires careful testing with repaired PDFs and third-party PDF viewers.)*
2. **Adopt a Gradle multi-module layout + `libs.versions.toml`**:
   * Combining `notes/` and `probe/` under one root Gradle build with a shared version catalog removes duplicated wrapper/plugin/repo definitions and lets `probe/` reuse Onyx input wrappers directly.
3. **Add SQLite FTS5 (or trigram index) to `NameIndexDb` if library sizes grow past 5,000+ PDFs**:
   * Currently `LIKE '%word%'` does a full table scan on `pdfs`, which is fast for ~1,000–3,000 files (~10–30ms) but scales linearly. FTS5 with trigram tokenizer would make substring search sub-millisecond even at 50,000+ files.
4. **Add Robolectric or AndroidX Test UI tests for extracted controllers & Activities**:
   * Once `WritingActivity` and `LibraryActivity` are decomposed into controller/state-holder classes (**3.1**), fast JVM tests can cover fast-ink pause/resume/reload holds, `onPause` save-then-thumbnail ordering, trim-margins transitions, and library error/busy states without needing a physical tablet.
5. **Adaptive bitmap cache & RGB_565 option for non-zoomed e-ink page bitmaps**:
   * E-paper panels display 16 grayscale levels (or 4,096 Kaleido colors). Evaluating `RGB_565` for `PageView`'s full-page cache (just as `ThumbnailLoader` already does) could halve the 200 MB bitmap cache footprint and reduce memory bandwidth during page turns, provided visual quality on Kaleido/monochrome screens remains acceptable.
