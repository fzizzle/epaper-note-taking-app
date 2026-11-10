package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.fzizzle.epapernotes.doc.DocumentSession
import com.fzizzle.epapernotes.storage.SafeSave
import com.fzizzle.epapernotes.library.Entry
import com.fzizzle.epapernotes.library.FolderEntry
import com.fzizzle.epapernotes.library.FolderListing
import com.fzizzle.epapernotes.library.FolderWatch
import com.fzizzle.epapernotes.library.HookPath
import com.fzizzle.epapernotes.library.PdfEntry
import com.fzizzle.epapernotes.library.ThumbnailCache
import com.fzizzle.epapernotes.library.Volumes
import com.fzizzle.epapernotes.ui.StripButton.Glyph
import java.io.File
import java.util.concurrent.Executors

/**
 * The library (spec §6.1): the folders and PDFs in one folder of the owner's tree, as a grid,
 * with the breadcrumb, refresh, new folder and ⋯ (Settings) in a top bar like the writing strip.
 * Tap a folder to go in, a PDF to write on it; long-press either for Rename, Move, Delete.
 *
 * Everything that reads or changes the owner's folders runs on [disk], one background thread,
 * in order; the screen's thread only reads preferences. Thumbnails have a thread of their own
 * (ThumbnailLoader), and only while they're switched on.
 *
 * For checks over adb (`am start -S -n com.fzizzle.epapernotes/.ui.LibraryActivity …`):
 * `--es path <pdf>` opens a file straight away, and `--es root <folder>` sets the library
 * folder (and shows it) without the chooser. Both take `/sdcard/…`, `/storage/emulated/0/…`, or a
 * path on a memory card that's in (`/storage/3331-3031/…`).
 */
class LibraryActivity : Activity() {
    private lateinit var prefs: LibraryPrefs
    private val disk = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var thumbnailCache: ThumbnailCache
    private var thumbnails: ThumbnailLoader? = null

    private lateinit var library: View
    private lateinit var breadcrumb: BreadcrumbBar
    private lateinit var continueRow: TextView
    private lateinit var grid: GridView
    private lateinit var adapter: LibraryAdapter
    private lateinit var emptyNote: TextView

    private var root: File? = null
    /** The folder asked for; [shownFolder] is the one on screen, once it's been read. */
    private var folder: File? = null
    private var shownFolder: File? = null
    private var showingLibrary = false
    private var choosingRoot = false
    /** Between onStart and onStop. A stale watch event after stop is ignored. */
    private var started = false
    private val watch = FolderWatch { if (started) list() }
    /** Only the newest listing is shown. */
    private var listed = 0
    /** Since onResume, for the "library shown" log; 0 once logged. */
    private var resumedAt = 0L
    /** A double tap (easy on e-ink) must not open a file twice, or tap into the next folder. */
    private var lastTapAt = 0L
    private var opening = false
    private lateinit var changes: LibraryChanges

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LibraryPrefs(this)
        thumbnailCache = ThumbnailCache(File(cacheDir, "thumbnails"))
        adapter = LibraryAdapter { thumbnails?.bitmapFor(it) }
        changes = LibraryChanges(this, disk, prefs) { done -> list(then = done) }
        library = buildLibrary()
    }

    override fun onStart() {
        super.onStart()
        started = true
        shownFolder?.let(watch::watch)
        thumbnails?.resume()
    }

    override fun onResume() {
        super.onResume()
        val backFromDocument = opening
        opening = false
        resumedAt = SystemClock.uptimeMillis()
        logFrameDrawn(resumedAt)
        if (!Environment.isExternalStorageManager()) {
            showingLibrary = false
            return setContentViewInsideBars(fileAccessPrompt(this))
        }
        if (!showingLibrary) setContentViewInsideBars(library)
        showingLibrary = true
        // Started only now: its daily tidy-up, without file access, would think every PDF gone.
        if (prefs.showThumbnails && thumbnails == null) thumbnails = newThumbnails()

        intent.getStringExtra(EXTRA_ROOT)?.let { raw ->
            intent.removeExtra(EXTRA_ROOT)
            val chosen = hookPath(raw) ?: return@let
            prefs.root = chosen
            prefs.folderOnShow = chosen
            this.root = null // the folder on show starts again at this root, even if it's the root already
        }
        intent.getStringExtra(WritingActivity.EXTRA_PATH)?.let { raw ->
            intent.removeExtra(WritingActivity.EXTRA_PATH)
            hookPath(raw)?.let(::open)
        }
        val root = prefs.root ?: return chooseFirstRoot()
        if (root != this.root) {
            this.root = root
            folder = prefs.folderOnShow?.takeIf { it.startsWith(root) } ?: root
            shownFolder = null
        }
        // Back from a document, its last save is still landing (spec §7.2): list once it has,
        // so the file's new size and date redraw its tile now, not when the folder watch
        // notices 0.4 s later. The folder as it was stays on screen meanwhile.
        list(afterSaveOf = if (backFromDocument && shownFolder != null) prefs.lastOpened else null)
    }

    /** A check hook's path, as the library writes paths (Ruling L1); one outside every volume that's in is refused. */
    private fun hookPath(raw: String): File? =
        HookPath.under(StorageVolumes.mounted(this).map { it.directory }, raw)
            ?: null.also { toast("Not on the tablet's storage or a memory card that's in: $raw") }

    override fun onStop() {
        super.onStop()
        started = false
        watch.stop()
        thumbnails?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        watch.stop()
        thumbnails?.stop()
        disk.shutdown() // a rename or move in hand still finishes
    }

    /** First launch (decision 1). Leaving the chooser leaves the app: there's nothing to show yet. */
    private fun chooseFirstRoot() {
        if (choosingRoot) return
        choosingRoot = true
        chooseLibraryFolder(this, disk, start = null, onCancel = { choosingRoot = false; finish() }) {
            choosingRoot = false
            useRoot(it)
        }
    }

    /** Turning the tablet only changes how many tiles fit across. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        grid.numColumns = columnsFor(newConfig)
    }

    @Deprecated("Back inside a folder goes up one level; at the library folder it leaves the app.")
    override fun onBackPressed() {
        val root = root
        val here = folder
        if (root != null && here != null && here != root && here.startsWith(root)) goTo(here.parentFile!!)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun buildLibrary(): View {
        val stripHeight = (maxOf(resources.displayMetrics.heightPixels, resources.displayMetrics.widthPixels) * 0.05f).toInt()
        val gap = stripHeight / 8
        val strip = StripRow(this, side = stripHeight - 2 * gap, gap = gap).apply { setBackgroundColor(Color.WHITE) }
        breadcrumb = BreadcrumbBar(this) { if (tapAllowed()) goTo(it) }
        strip.addView(breadcrumb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = gap })
        fun button(glyph: Glyph, description: String, gaps: Int = 1, onClick: () -> Unit) =
            strip.addSquare(StripButton(this, glyph, description).apply { setOnClickListener { onClick() } }, gaps)
        button(Glyph.REFRESH, "Refresh") { refresh() }
        button(Glyph.NEW_FOLDER, "New folder") { root?.let { changes.askForNewFolder(it, shownFolder ?: it) } }
        button(Glyph.MENU, "Settings", gaps = 0) { showSettings() }

        continueRow = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = LibraryLook.pressedFeedback()
            visibility = View.GONE
            setOnClickListener { prefs.lastOpened?.let(::open) }
        }
        grid = GridView(this).apply {
            numColumns = columnsFor(resources.configuration)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(4)
            verticalSpacing = dp(8)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT) // tiles show their own pressed state, without a fade
            overScrollMode = View.OVER_SCROLL_NEVER // no glow at the ends: it's an animation
            isScrollbarFadingEnabled = false // nor a fading scrollbar
            adapter = this@LibraryActivity.adapter
            setOnItemClickListener { _, _, position, _ -> tapped(this@LibraryActivity.adapter.entries[position]) }
            setOnItemLongClickListener { _, view, position, _ ->
                root?.let { changes.showMenu(it, shownFolder ?: it, view, this@LibraryActivity.adapter.entries[position]) }
                true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
                override fun onScroll(view: AbsListView, first: Int, count: Int, total: Int) = tilesOnScreenChanged()
            })
        }
        emptyNote = TextView(this).apply {
            textSize = 18f
            setTextColor(LibraryLook.GRAPHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
            // Only clickable while it says the library folder is missing: then it leads to Settings (or lists again if the folder is back).
            setOnClickListener {
                if (!tapAllowed()) return@setOnClickListener
                // The card may be back since the last listing: look again before sending anyone to Settings.
                if (root?.isDirectory == true) list() else showSettings()
            }
            isClickable = false
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, stripHeight))
            addView(View(this@LibraryActivity).apply { setBackgroundColor(LibraryLook.RULE) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2))
            addView(continueRow)
            addView(FrameLayout(this@LibraryActivity).apply {
                addView(grid)
                addView(emptyNote)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /** About 5 tiles across in portrait, 7 in landscape (decision 2). */
    private fun columnsFor(config: Configuration) = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) 7 else 5

    /**
     * Reads the folder on show on [disk], then shows it. Unchanged contents leave the screen
     * alone (no e-ink refresh). [then] runs on the main thread afterwards, even if a newer
     * listing took this one's place.
     */
    private fun list(redrawTiles: Boolean = false, afterSaveOf: File? = null, then: () -> Unit = {}) {
        val root = root ?: return then()
        val wanted = folder ?: root
        val ask = ++listed
        val last = prefs.lastOpened
        // Watched before it's read (review 2.10): a file landing while it's read still re-lists.
        if (started && wanted != watch.watching) watch.watch(wanted)
        disk.execute {
            afterSaveOf?.let { DocumentSession.awaitClosed(it, LEFT_SAVE_WAIT_MS) }
            val start = SystemClock.uptimeMillis()
            // The folder went away (deleted or moved on a laptop): show the nearest one left.
            var shown = wanted
            while (shown != root && !shown.isDirectory) shown = shown.parentFile ?: root
            val entries = FolderListing.list(shown)
            // Hidden copies a crashed save left behind (spec §7.3), never while a document in
            // this folder is open or still saving: its save may be writing one right now.
            if (!DocumentSession.anyOpenIn(shown)) {
                val removed = SafeSave.cleanStale(shown)
                if (removed > 0) Log.i(TAG, "removed $removed stale temporary file(s) in ${shown.path}")
            }
            val rootMissing = !root.isDirectory
            val resume = if (shown == root) last?.takeIf { it.isFile } else null
            Log.i(TAG, "library listed ${shown.path} n=${entries.size} ms=${SystemClock.uptimeMillis() - start}")
            main.post {
                if (ask == listed && !isDestroyed) showListing(shown, entries, resume, rootMissing, redrawTiles)
                then()
            }
        }
    }

    private fun showListing(shown: File, entries: List<Entry>, resume: File?, rootMissing: Boolean, redrawTiles: Boolean) {
        folder = shown
        // Another folder than was asked for (that one went away), or this one was deleted and
        // perhaps recreated by a sync app since it was watched: watched afresh.
        val rewatch = watch.needsRewatch
        if (started && (shown != watch.watching || rewatch)) watch.watch(shown)
        if (shown != shownFolder) {
            shownFolder = shown
            prefs.folderOnShow = shown
            breadcrumb.show(root ?: shown, shown)
            grid.setSelection(0)
        }
        // Texts are only set when they differ: setting one, even to the same words, draws its
        // row again, and a listing comes after every change the folder watch sees.
        val continueText = resume?.let { "▶  Continue: ${it.nameWithoutExtension}" }.orEmpty()
        if (continueRow.text.toString() != continueText) continueRow.text = continueText
        continueRow.visibility = if (resume != null) View.VISIBLE else View.GONE
        val changed = redrawTiles || entries != adapter.entries || adapter.thumbnails != (thumbnails != null)
        if (changed) adapter.show(entries, thumbnails = thumbnails != null, dropPictures = redrawTiles)
        val emptyText = when {
            !rootMissing -> "No folders or PDFs here."
            Volumes.isOnCardThatsOut(StorageVolumes.mounted(this), root ?: shown) ->
                "The library folder is on the memory card or USB drive, which isn't in. Put it back, or tap here to choose another folder in Settings (⋯)."
            else -> "The library folder isn't there. Tap here to choose another in Settings (⋯)."
        }
        if (emptyNote.text.toString() != emptyText) emptyNote.text = emptyText
        if (emptyNote.isClickable != rootMissing) {
            emptyNote.isClickable = rootMissing
            emptyNote.background = if (rootMissing) LibraryLook.pressedFeedback() else null
        }
        emptyNote.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        tilesOnScreenChanged()
        logShown(waitForDraw = changed)
    }

    /** "library shown": from onResume to the grid on screen (Task 6 times leaving a document by it). */
    private fun logShown(waitForDraw: Boolean) {
        val since = resumedAt.takeIf { it != 0L } ?: return
        resumedAt = 0L
        val log = { Log.i(TAG, "library shown ms=${SystemClock.uptimeMillis() - since}"); Unit }
        if (!waitForDraw) return log()
        grid.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                grid.viewTreeObserver.removeOnPreDrawListener(this)
                log()
                return true
            }
        })
    }

    /** "library frame": from onResume to the first frame handed to the screen (Task 6, with "library shown"). */
    private fun logFrameDrawn(since: Long) {
        window.decorView.viewTreeObserver.registerFrameCommitCallback {
            Log.i(TAG, "library frame ms=${SystemClock.uptimeMillis() - since}")
        }
    }

    private fun goTo(target: File) {
        folder = target
        list()
    }

    private fun showSettings() =
        showLibrarySettings(this, prefs, disk, onRootChosen = ::useRoot, onThumbnailsChanged = ::switchThumbnails)

    /**
     * The tiles on screen get their thumbnails first, and the folders among them are watched,
     * so a PDF arriving inside one updates its count (spec §6.1).
     */
    private fun tilesOnScreenChanged() {
        if (started) watch.watchChildren(onScreen().filterIsInstance<FolderEntry>().map { it.file })
        val loader = thumbnails ?: return
        loader.show(adapter.entries.filterIsInstance<PdfEntry>(), onScreen().filterIsInstance<PdfEntry>())
    }

    /** The entries whose tiles are on screen now. */
    private fun onScreen(): List<Entry> {
        val entries = adapter.entries
        val first = grid.firstVisiblePosition.coerceIn(0, entries.size)
        val end = (first + grid.childCount).coerceIn(first, entries.size)
        return entries.subList(first, end)
    }

    private fun newThumbnails() = ThumbnailLoader(thumbnailCache) { pdf, bitmap -> thumbnailReady(pdf, bitmap) }

    private fun thumbnailReady(pdf: PdfEntry, bitmap: Bitmap?) {
        for (i in 0 until grid.childCount) {
            val tile = grid.getChildAt(i) as? TileView ?: continue
            if (tile.entry == pdf) tile.showBitmap(bitmap)
        }
    }

    private fun switchThumbnails(on: Boolean) {
        thumbnails?.stop()
        thumbnails = if (on) newThumbnails() else null
        adapter.show(adapter.entries, thumbnails = on)
        tilesOnScreenChanged()
    }

    /** Settings or the first launch chose the library folder. */
    private fun useRoot(chosen: File) {
        prefs.root = chosen
        prefs.folderOnShow = chosen
        root = chosen
        folder = chosen
        shownFolder = null // the breadcrumb starts somewhere new
        list()
    }

    /**
     * Every thumbnail in the folder is thrown away, so no old picture comes back. The ones on
     * screen are drawn again now; the rest when they scroll into view, rather than the whole
     * folder at once, which in a folder of several hundred PDFs took minutes.
     */
    private fun refresh() {
        thumbnails?.let { loader ->
            val showing = onScreen().filterIsInstance<PdfEntry>().toSet()
            val (onScreen, rest) = adapter.entries.filterIsInstance<PdfEntry>().partition { it in showing }
            loader.redraw(onScreen, rest)
        }
        list(redrawTiles = true)
    }

    private fun tapAllowed(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt < ViewConfiguration.getDoubleTapTimeout()) return false
        lastTapAt = now
        return true
    }

    private fun tapped(entry: Entry) {
        if (!tapAllowed()) return
        when (entry) {
            is FolderEntry -> goTo(entry.file)
            is PdfEntry -> open(entry.file)
        }
    }

    private fun open(pdf: File) {
        if (opening) return
        if (changes.busy) return toast("One moment: still finishing the last change.")
        opening = true
        prefs.lastOpened = pdf
        startActivity(Intent(this, WritingActivity::class.java).putExtra(WritingActivity.EXTRA_PATH, pdf.path))
    }

    /** Android 15+ draws apps under the status bar. Keep everything below it, so the top row can be tapped. */
    private fun setContentViewInsideBars(view: View) {
        (view.parent as? FrameLayout)?.removeView(view)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(view)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
    }

    private fun dp(v: Int) = LibraryLook.dp(this, v)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "Notes"
        /** Check hook, like `--es path`: the library folder, set without the chooser. */
        const val EXTRA_ROOT = "root"
        /** The longest the first listing waits for a document's last save; it's usually well under a second. */
        const val LEFT_SAVE_WAIT_MS = 3000L
    }
}
