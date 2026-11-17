package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import com.fzizzle.epapernotes.library.CourseColours
import com.fzizzle.epapernotes.library.Entry
import com.fzizzle.epapernotes.library.FolderEntry
import com.fzizzle.epapernotes.library.FolderListing
import com.fzizzle.epapernotes.library.FolderWatch
import com.fzizzle.epapernotes.library.Hit
import com.fzizzle.epapernotes.library.HookPath
import com.fzizzle.epapernotes.library.LaunchOpen
import com.fzizzle.epapernotes.library.LibraryWalk
import com.fzizzle.epapernotes.library.NameMatch
import com.fzizzle.epapernotes.library.PdfEntry
import com.fzizzle.epapernotes.library.ThumbnailCache
import com.fzizzle.epapernotes.library.TileMarks
import com.fzizzle.epapernotes.library.progressOf
import com.fzizzle.epapernotes.library.Volumes
import com.fzizzle.epapernotes.ui.StripButton.Glyph
import java.io.File
import java.util.concurrent.Executors

/**
 * The library (spec §6.1): the folders and PDFs in one folder of the owner's tree, as a grid,
 * with the breadcrumb, search, refresh, new folder and ⋯ (Settings) in a top bar like the writing
 * strip. Tap a folder to go in, a PDF to write on it; long-press either for Rename, Move, Delete.
 * Search shows the whole library's PDFs whose names match, as rows, in place of the folder
 * (design §6), until Back or the breadcrumb.
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
    private lateinit var continueRow: LinearLayout
    private lateinit var continuePictureView: android.widget.ImageView
    private lateinit var continueName: TextView
    private lateinit var continueDetail: TextView
    private lateinit var continuePicture: ContinuePicture

    /** What the Continue row shows: the file, its page and page count, and its picture's date ([ContinuePicture.stamp]). */
    private data class ContinueKey(val file: File, val page: Int, val pageCount: Int, val pictureStamp: Long)

    /** Read by the disk thread, which only reads the picture again when this would change. */
    @Volatile private var shownContinue: ContinueKey? = null
    private lateinit var grid: GridView
    private lateinit var adapter: LibraryAdapter
    private lateinit var emptyNote: TextView

    private lateinit var lastPages: LastPageStore
    private lateinit var courseRule: View
    /** The colour [courseRule] has now: only set when it changes, so an unchanged listing redraws nothing. */
    private var courseRuleColour = LibraryLook.RULE
    /** How far the owner is into each PDF on show that they've opened (progressOf), read with the listing. */
    private var progress: Map<File, Float> = emptyMap()

    /** For the list view: "12 / 30" for each PDF on show that's been opened, read with the listing like [progress]. */
    private var pageTexts: Map<File, String> = emptyMap()

    /** A list row's right-hand words: where the owner is in a PDF, or its pages if it's unopened; what a folder holds. */
    private fun detailFor(entry: Entry): String = when (entry) {
        is FolderEntry -> folderCountText(entry)
        is PdfEntry -> resultFolders[entry.file] ?: pageTexts[entry.file] ?: thumbnails?.factsFor(entry)?.let { if (it.pages == 1) "1 page" else "${it.pages} pages" }.orEmpty()
    }

    private lateinit var viewButton: StripButton

    /** Tiles to list or back (decision 6), kept for next time. One redraw of the grid. */
    private fun switchView() {
        prefs.listView = !prefs.listView
        viewButton.glyph = if (prefs.listView) Glyph.TILES else Glyph.LIST
        // From results: back to the folder, which comes in the new view, in one redraw.
        if (search != null) return leaveSearch()
        grid.numColumns = columnsFor(resources.configuration)
        adapter.show(adapter.entries, thumbnails = adapter.thumbnails, listView = prefs.listView)
        tilesOnScreenChanged()
    }

    /** Marks [file]'s tile or row as the one just come back from, or with null takes the mark off. Only the views on screen are touched: no rebinding, so a tap in hand isn't lost. */
    private fun markCameBackFrom(file: File?) {
        if (adapter.markedFile == file) return
        adapter.markedFile = file
        showMarks()
    }

    /** Marks [file]'s tile or row while its long-press menu is open, or with null takes that mark off. */
    private fun markMenuFor(file: File?) {
        if (adapter.menuFile == file) return
        adapter.menuFile = file
        showMarks()
    }

    private fun showMarks() {
        for (i in 0 until grid.childCount) (grid.getChildAt(i) as? LibraryItemView)?.let { it.marked = adapter.isMarked(it.entry?.file) }
    }

    /** A tile's marks, from memory only: its progress from the listing, its writing mark from the thumbnail's facts. */
    private fun marksFor(entry: Entry): TileMarks {
        // Results carry no marks: progress was read for the folder on show, not for them, and a
        // writing mark arriving later with a thumbnail's facts would redraw a row by itself.
        if (resultsOnScreen != null) return TileMarks.NONE
        // At the library folder each course's folder carries its colour; inside a course the line under the strip does.
        val pdf = entry as? PdfEntry
            ?: return TileMarks(stripe = if (root == shownFolder) courseColourOf(entry.file) else null)
        return TileMarks(progress = progress[pdf.file], hasInk = thumbnails?.factsFor(pdf)?.hasInk == true)
    }

    /** Each course's colour, by its code in capitals (CourseColours.assign), read with the listing. */
    private var courseColours: Map<String, Int> = emptyMap()

    private fun courseColourOf(file: File): Int? =
        root?.let { CourseColours.courseOf(it, file) }?.let { courseColours[it.uppercase()] }

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
    /** The name index of [root]; kept in step from here (checks, listings, changes). */
    private var index: NameIndex? = null

    /** A search under way or on screen: its query, and its ask number ([searched]) so older answers are dropped. */
    private data class SearchState(val query: String, val asked: Int)

    /** Non-null while the library is in a search: then results, not the folder, are what the grid is for. */
    private var search: SearchState? = null
    /** The last query searched for, filled in the next time; memory only. */
    private var lastQuery = ""
    /** Only the newest search's answers are shown, as with [listed]. */
    private var searched = 0

    /** What the results on screen were made from, or null while the grid shows a folder. */
    private data class ShownResults(val hits: List<Hit>, val rootMissing: Boolean)
    private var resultsOnScreen: ShownResults? = null
    /** Each result's folder path from the library folder, its row's right-hand words. */
    private var resultFolders: Map<File, String> = emptyMap()
    /** Where the folder was scrolled to when results took its place, to go back to. */
    private var folderScroll = 0
    /** The line between the strip and the grid while results show: a check still running, or how many were left out. */
    private lateinit var noteLine: TextView
    /** [noteLine] above the grid: redrawn as one when both change. */
    private lateinit var resultsArea: View
    /** A tapped result's file is being looked for on [disk]: further taps wait for it. */
    private var checkingResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LibraryPrefs(this)
        thumbnailCache = ThumbnailCache(File(cacheDir, "thumbnails"))
        lastPages = LastPageStore(this)
        continuePicture = ContinuePicture(cacheDir, prefs)
        adapter = LibraryAdapter({ thumbnails?.bitmapFor(it) }, ::marksFor, ::detailFor)
        changes = LibraryChanges(this, disk, prefs, { done -> list(then = done) }) { index?.reread(it) }
        library = buildLibrary()
        // Started from nothing, from the launcher: the one moment "Start in the last document" acts.
        freshStart = savedInstanceState == null && intent?.action == Intent.ACTION_MAIN && intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
    }

    /** True until the first [onResume] with file access has decided where to start (LaunchOpen). */
    private var freshStart = false

    /** "Start in the last document": at a fresh start, opens it, unless the last try at that never finished. */
    private fun startWhereTheSettingSays() {
        val fresh = freshStart
        freshStart = false
        val last = prefs.lastOpened
        when (LaunchOpen.decide(fresh, prefs.openLastAtLaunch, last?.isFile == true, prefs.launchOpenUnfinished)) {
            LaunchOpen.Start.LIBRARY -> Unit
            LaunchOpen.Start.LAST_DOCUMENT -> {
                prefs.launchOpenUnfinished = true // cleared by the writing screen once the document shows
                open(last!!)
            }
            LaunchOpen.Start.LIBRARY_AFTER_FAILED_OPEN -> {
                prefs.launchOpenUnfinished = false
                toast("Didn't reopen ${last?.nameWithoutExtension ?: "the last document"}: it didn't finish opening last time.")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        shownFolder?.let(watch::watch)
        // The subfolders on screen too, before the listing onResume asks for: a PDF landing in
        // one meanwhile still updates its count. The grid still holds the last entries.
        if (shownFolder != null && resultsOnScreen == null) watch.watchChildren(watchedChildren())
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
        // Paused when a document was asked for (open); taken up again here even if the document
        // closed before the library ever stopped (one that couldn't be opened, say).
        thumbnails?.resume()

        // The adb shortcuts (--es root, --es path), for checks: only in a debug build. This
        // screen is the launcher's, so any app could send them otherwise (decision 4).
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
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
        }
        val root = prefs.root ?: return chooseFirstRoot()
        startWhereTheSettingSays()
        val newIndex = NameIndex.forRoot(this, root)
        // The old index was closed, so a search waiting on it never answers: leave it, as Back does.
        if (newIndex !== index) endSearch()
        index = newIndex
        // Not while "Start in the last document" opens one now: the full re-read would start under
        // its opening. Coming back from it, the next onResume takes it.
        if (!opening && NameIndex.firstCheckDue()) newIndex.check(full = true)
        if (root != this.root) {
            endSearch()
            this.root = root
            folder = prefs.folderOnShow?.takeIf { it.startsWith(root) } ?: root
            shownFolder = null
        }
        // Back to results (from a document opened from them, say): searched again, not listed.
        search?.let {
            return runSearch(
                it.query, fresh = false,
                afterSaveOf = if (backFromDocument) prefs.lastOpened else null,
                cameBackFrom = if (backFromDocument) prefs.lastOpened else null,
            )
        }
        // Back from a document, its last save is still landing (spec §7.2): list once it has,
        // so the file's new size and date redraw its tile now, not when the folder watch
        // notices 0.4 s later. The folder as it was stays on screen meanwhile.
        list(
            afterSaveOf = if (backFromDocument && shownFolder != null) prefs.lastOpened else null,
            cameBackFrom = if (backFromDocument) prefs.lastOpened else null,
        )
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
        // Put on their tiles now, not dropped: a tile only gets its picture here or when it's
        // bound again, and coming back to an unchanged folder binds nothing, so a picture
        // dropped here would leave its tile grey.
        main.removeCallbacks(showPendingThumbnails)
        showPendingThumbnails.run()
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

    @Deprecated("Back from search results goes to the folder; inside a folder it goes up one level; at the library folder it leaves the app.")
    override fun onBackPressed() {
        if (search != null) return leaveSearch()
        val root = root
        val here = folder
        if (root != null && here != null && here != root && here.startsWith(root)) goTo(here.parentFile!!)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun buildLibrary(): View {
        val stripHeight = (maxOf(resources.displayMetrics.heightPixels, resources.displayMetrics.widthPixels) * 0.05f).toInt()
        val gap = stripHeight / 8
        val strip = StripRow(this, side = stripHeight - 2 * gap, gap = gap).apply { setBackgroundColor(Color.WHITE) }
        breadcrumb = BreadcrumbBar(this) {
            if (!tapAllowed()) return@BreadcrumbBar
            if (search != null) leaveSearch() else goTo(it)
        }
        strip.addView(breadcrumb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = gap })
        fun button(glyph: Glyph, description: String, gaps: Int = 1, onClick: () -> Unit) =
            strip.addSquare(StripButton(this, glyph, description).apply { setOnClickListener { onClick() } }, gaps)
        // Shows the view it switches to.
        viewButton = StripButton(this, if (prefs.listView) Glyph.TILES else Glyph.LIST, "List or tiles").apply { setOnClickListener { switchView() } }
        strip.addSquare(viewButton)
        button(Glyph.SEARCH, "Search") { askToSearch() }
        button(Glyph.REFRESH, "Refresh") { refresh() }
        button(Glyph.NEW_FOLDER, "New folder") {
            root?.let {
                if (search != null) leaveSearch()
                changes.askForNewFolder(it, shownFolder ?: it)
            }
        }
        button(Glyph.MENU, "Settings", gaps = 0) { showSettings() }

        // Continue: a picture of the page that was left, with its ink, then the file's name and where in it.
        continuePictureView = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            // A thin frame, as the tiles' pictures have.
            foreground = android.graphics.drawable.GradientDrawable().apply { setStroke(2, LibraryLook.RULE) }
            visibility = View.GONE
        }
        continueName = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        continueDetail = TextView(this).apply {
            textSize = 16f
            setTextColor(LibraryLook.GRAPHITE)
        }
        continueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = LibraryLook.pressedFeedback()
            visibility = View.GONE
            addView(continuePictureView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(CONTINUE_PICTURE_DP)).apply { rightMargin = dp(16) })
            addView(LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(continueName)
                addView(continueDetail)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
                // Nothing in results (design §6): taken, so no tap follows either.
                if (search != null) return@setOnItemLongClickListener true
                root?.let { changes.showMenu(it, shownFolder ?: it, view, this@LibraryActivity.adapter.entries[position], mark = ::markMenuFor, openFolder = ::goTo) }
                true
            }
            // The mark on the document just come back from goes at the next touch. Not taken: the touch does what it would.
            setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) markCameBackFrom(null)
                false
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
                override fun onScroll(view: AbsListView, first: Int, count: Int, total: Int) = tilesOnScreenChanged(fromScroll = true)
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
                val search = search
                when {
                    root?.isDirectory != true -> showSettings()
                    search != null -> runSearch(search.query, fresh = false)
                    else -> list()
                }
            }
            isClickable = false
        }
        noteLine = TextView(this).apply {
            textSize = 16f
            setTextColor(LibraryLook.GRAPHITE)
            // One line, always: a new text never changes its height, so the grid never moves.
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(20), dp(8), dp(20), dp(4))
            visibility = View.GONE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, stripHeight))
            // The line under the strip: grey, or inside a course its colour (showListing).
            courseRule = View(this@LibraryActivity).apply { setBackgroundColor(LibraryLook.RULE) }
            addView(courseRule, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, RULE_PX))
            addView(continueRow)
            // The note line and the grid in one view, so a single panel update covers both.
            resultsArea = LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(noteLine)
                addView(FrameLayout(this@LibraryActivity).apply {
                    addView(grid)
                    addView(emptyNote)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            addView(resultsArea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /** About 5 tiles across in portrait, 7 in landscape (decision 2). */
    private fun columnsFor(config: Configuration): Int {
        val sideways = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        // The list view: one column upright, two sideways. Results are always rows.
        return if (prefs.listView || resultsOnScreen != null) (if (sideways) 2 else 1) else (if (sideways) 7 else 5)
    }

    /**
     * Reads the folder on show on [disk], then shows it. Unchanged contents leave the screen
     * alone (no e-ink refresh). [then] runs on the main thread afterwards, even if a newer
     * listing took this one's place.
     */
    private fun list(redrawTiles: Boolean = false, afterSaveOf: File? = null, cameBackFrom: File? = null, then: () -> Unit = {}) {
        val root = root ?: return then()
        // Results on screen are never replaced by the folder (the folder watch, a change's
        // re-list): leaving the search clears [search] first, then lists.
        if (search != null) return then()
        val wanted = folder ?: root
        // A visit (another folder, or back from a document), not a re-read after a change on disk: timed for the log.
        if (wanted != shownFolder || cameBackFrom != null) startVisit()
        // Where the screen will be in the folder, so the thumbnails read ahead are the ones it shows.
        val firstShown = if (wanted != shownFolder) 0 else if (resultsOnScreen != null) folderScroll else grid.firstVisiblePosition
        val loader = thumbnails
        val ask = ++listed
        // Read here, on the main thread: the disk thread must not read the var.
        val index = index
        val last = prefs.lastOpened
        // Watched before it's read (review 2.10): a file landing while it's read still re-lists.
        if (started && wanted != watch.watching) watch.watch(wanted)
        disk.execute {
            afterSaveOf?.let { DocumentSession.awaitClosed(it, LEFT_SAVE_WAIT_MS) }
            val start = SystemClock.uptimeMillis()
            // The folder went away (deleted or moved on a laptop): show the nearest one left.
            var shown = wanted
            while (shown != root && !shown.isDirectory) shown = shown.parentFile ?: root
            val entries = FolderListing.list(shown, if (prefs.openOutSmallFolders) FolderListing.OPEN_OUT_UP_TO else 0)
            // Hidden copies a crashed save left behind (spec §7.3), never while a document in
            // this folder is open or still saving: its save may be writing one right now.
            if (!DocumentSession.anyOpenIn(shown)) {
                val removed = SafeSave.cleanStale(shown)
                if (removed > 0) Log.i(TAG, "removed $removed stale temporary file(s) in ${shown.path}")
            }
            // Read here, with the listing, not as tiles are drawn.
            val progress = HashMap<File, Float>()
            val pageTexts = HashMap<File, String>()
            for (pdf in entries.filterIsInstance<PdfEntry>()) {
                val path = pdf.file.path
                val page = lastPages.get(path)
                val count = lastPages.pageCount(path)
                progressOf(lastPages.has(path), page, count)?.let {
                    progress[pdf.file] = it
                    pageTexts[pdf.file] = "${minOf(page + 1, count)} / $count"
                }
            }
            // The courses are the library folder's own folders, whichever folder is on show.
            val courses = root.listFiles().orEmpty()
                .filter { !it.name.startsWith(".") && it.isDirectory }
                .mapNotNull { CourseColours.courseOf(root, it) }
            val courseColours = CourseColours.assign(courses).mapValues { CourseColours.PALETTE[it.value] }
            val rootMissing = !root.isDirectory
            val resume = (if (shown == root) last?.takeIf { it.isFile } else null)?.let {
                ContinueKey(it, lastPages.get(it.path), lastPages.pageCount(it.path), continuePicture.stamp)
            }
            // The picture is only read when the row will change.
            val resumePicture = if (resume != null && resume != shownContinue) continuePicture.load(resume.file) else null
            // Re-read by the index on its own thread, so this folder's names are current for a search.
            index?.reread(listOf(shown))
            Log.i(TAG, "library listed ${shown.path} n=${entries.size} ms=${SystemClock.uptimeMillis() - start}")
            // The thumbnails of the tiles about to be shown, from the cache (a couple of
            // milliseconds each), so the folder is drawn once, with its pictures, rather than
            // grey and then again. Not in the list view, which shows none.
            // Not after Refresh: its point is that every picture is made again.
            if (loader != null && !prefs.listView && !redrawTiles) {
                val from = cameBackFrom?.let { f -> entries.indexOfFirst { it.file == f } }?.takeIf { it >= 0 }?.let { (it - FIRST_SCREEN_TILES / 2).coerceAtLeast(0) }
                    ?: firstShown.coerceIn(0, entries.size)
                val upcoming = entries.drop(from).take(FIRST_SCREEN_TILES).filterIsInstance<PdfEntry>()
                val read = loader.warm(upcoming, WARM_BUDGET_MS)
                if (read > 0) Log.i(TAG, "thumbnails read ahead n=$read")
            }
            main.post {
                // A search begun meanwhile keeps the grid.
                if (ask == listed && !isDestroyed && search == null) showListing(shown, entries, progress, pageTexts, courseColours, resume, resumePicture, rootMissing, redrawTiles, cameBackFrom)
                then()
            }
        }
    }

    private fun showListing(
        shown: File, entries: List<Entry>, progress: Map<File, Float>, pageTexts: Map<File, String>, courseColours: Map<String, Int>,
        resume: ContinueKey?, resumePicture: Bitmap?, rootMissing: Boolean, redrawTiles: Boolean, cameBackFrom: File?,
    ) {
        val coloursChanged = courseColours != this.courseColours
        this.courseColours = courseColours
        folder = shown
        // Another folder than was asked for (that one went away), or this one was deleted and
        // perhaps recreated by a sync app since it was watched: watched afresh.
        val rewatch = watch.needsRewatch
        if (started && (shown != watch.watching || rewatch)) {
            watch.watch(shown)
            // A recreated folder was read before this watch began, so a file landing in
            // between would go unseen: read once more. If nothing changed, nothing is redrawn.
            if (rewatch) main.post { list() }
        }
        // Back from results: the path, the folder's rows or tiles, and where it was scrolled to,
        // all in this one redraw.
        val fromResults = resultsOnScreen != null
        val sameFolder = shown == shownFolder
        resultsOnScreen = null
        resultFolders = emptyMap()
        if (noteLine.visibility != View.GONE) noteLine.visibility = View.GONE
        // From results, or the view switched while a search had no answer yet: the folder's columns.
        if (fromResults || adapter.listView != prefs.listView) grid.numColumns = columnsFor(resources.configuration)
        if (shown != shownFolder) {
            shownFolder = shown
            prefs.folderOnShow = shown
            breadcrumb.show(root ?: shown, shown)
            grid.setSelection(0)
        } else if (breadcrumb.showingText) {
            breadcrumb.show(root ?: shown, shown)
        }
        val ruleColour = courseColourOf(shown) ?: LibraryLook.RULE
        if (ruleColour != courseRuleColour) {
            courseRuleColour = ruleColour
            courseRule.setBackgroundColor(ruleColour)
            // Thicker in colour: two pixels of it are too little to see on the colour screen.
            courseRule.layoutParams = courseRule.layoutParams.apply { height = if (ruleColour == LibraryLook.RULE) RULE_PX else COURSE_RULE_PX }
        }
        // Texts are only set when they differ: setting one, even to the same words, draws its
        // row again, and a listing comes after every change the folder watch sees.
        // Only when it changed (ContinueKey): the picture read again or a text set draws the row again.
        if (resume != shownContinue) {
            shownContinue = resume
            if (resume != null) {
                continueName.text = resume.file.nameWithoutExtension
                continueDetail.text = if (resume.pageCount > 0) "Continue · page ${minOf(resume.page + 1, resume.pageCount)} of ${resume.pageCount}" else "Continue"
                continuePictureView.setImageBitmap(resumePicture)
                continuePictureView.visibility = if (resumePicture != null) View.VISIBLE else View.GONE
            }
        }
        continueRow.visibility = if (resume != null) View.VISIBLE else View.GONE
        // A document just left may only have moved to another page: the file is the same, its bar isn't.
        if (visitStartedAt != 0L) visitGoes++ // this listing draws the grid
        val changed = redrawTiles || fromResults || entries != adapter.entries || adapter.thumbnails != (thumbnails != null) || progress != this.progress || coloursChanged
        this.progress = progress
        this.pageTexts = pageTexts
        if (changed || adapter.listView != prefs.listView) {
            adapter.show(entries, thumbnails = thumbnails != null, dropPictures = redrawTiles, listView = prefs.listView)
            if (fromResults && sameFolder) grid.setSelection(folderScroll)
            // From results the note line goes too: one update over both.
            PanelRefresh.gentle(if (fromResults) resultsArea else grid)
        }
        // Back from a document (decision 7): its tile is brought into view if it isn't, and
        // marked until the next touch, so the eye finds its place again.
        if (cameBackFrom != null) {
            val at = entries.indexOfFirst { it.file == cameBackFrom }
            if (at >= 0) {
                if (at < grid.firstVisiblePosition || at > grid.lastVisiblePosition) grid.setSelection(at)
                markCameBackFrom(cameBackFrom)
            }
        }
        // An empty folder says how to fill it (decision 8).
        val emptyText = if (!rootMissing) "Nothing here yet.\n\nCopy PDFs into\n${Volumes.whereIs(StorageVolumes.mounted(this), shown)}\nfrom a computer or a sync app, and they show up here by themselves."
        else rootMissingText(shown)
        showEmptyNote(emptyText, rootMissing, entries.isEmpty())
        tilesOnScreenChanged()
        startDrawingAhead()
        logShown(waitForDraw = changed)
        // The grid lays its tiles out on the next frame: look then.
        grid.post { logFolderReadyIfSo() }
    }

    /** Where the grid is empty because the library folder isn't there: what it says, tapped for Settings. */
    private fun rootMissingText(shown: File): String =
        if (Volumes.isOnCardThatsOut(StorageVolumes.mounted(this), root ?: shown)) {
            "The library folder is on the memory card or USB drive, which isn't in. Put it back, or tap here to choose another folder in Settings (⋯)."
        } else {
            "The library folder isn't there. Tap here to choose another in Settings (⋯)."
        }

    /** The note over an empty grid. Only what changed is set: setting a text, even the same one, redraws it. */
    private fun showEmptyNote(text: String, rootMissing: Boolean, visible: Boolean) {
        if (emptyNote.text.toString() != text) emptyNote.text = text
        if (emptyNote.isClickable != rootMissing) {
            emptyNote.isClickable = rootMissing
            emptyNote.background = if (rootMissing) LibraryLook.pressedFeedback() else null
        }
        emptyNote.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * The search button: asks for the words, the last ones filled in and selected, with the
     * first few matches previewed under the box as they're typed.
     */
    private fun askToSearch() {
        val root = root ?: return
        val preview = { field: android.widget.EditText, close: () -> Unit ->
            SearchPreviewView(this, field, root, ask = { query, onAnswer -> index?.preview(query, onAnswer) }) { pdf, query ->
                if (!tapAllowed()) return@SearchPreviewView
                close()
                lastQuery = query
                openResult(pdf, fromPreview = true)
            }
        }
        changes.askForText("Search", lastQuery, "Search", below = preview) { typed ->
            // Nothing but spaces matches nothing: nothing happens.
            if (NameMatch.words(typed).isEmpty()) return@askForText
            lastQuery = typed
            runSearch(typed, fresh = true)
        }
    }

    /**
     * Asks the index for [query]'s matches; the answers come to [showAnswer]. [fresh]: a new
     * query, whose results start at the top. [afterSaveOf]: back from that document, whose last
     * save is still landing; the index's check waits while a document is open, so the search
     * waits for the save first, or it would come back "Checking for changes…" and redraw twice.
     */
    private fun runSearch(query: String, fresh: Boolean, afterSaveOf: File? = null, cameBackFrom: File? = null) {
        val index = index ?: return
        val ask = ++searched
        search = SearchState(query, ask)
        val startedAt = SystemClock.uptimeMillis()
        val go = {
            var first = true
            index.search(query) { answer ->
                showAnswer(ask, answer, startedAt, scrollToTop = fresh && first, cameBackFrom)
                first = false
            }
        }
        if (afterSaveOf == null) return go()
        disk.execute {
            DocumentSession.awaitClosed(afterSaveOf, LEFT_SAVE_WAIT_MS)
            main.post { if (search?.asked == ask && !isDestroyed) go() }
        }
    }

    /**
     * Puts one answer of the newest search on screen: the rows, the note line and the breadcrumb,
     * together. A later answer with the same results changes only the note line (a small update
     * of that line alone); with other results the grid is redrawn once.
     */
    private fun showAnswer(ask: Int, answer: NameIndex.SearchAnswer, startedAt: Long, scrollToTop: Boolean, cameBackFrom: File?) {
        if (search?.asked != ask || isDestroyed) return
        val root = root ?: return
        Log.i(TAG, "search ms=${SystemClock.uptimeMillis() - startedAt} found=${answer.matches.total} note=${answer.note}")
        breadcrumb.showText("Search: \"${answer.query}\"")
        val hits = answer.matches.hits
        val results = ShownResults(hits, answer.rootMissing)
        val redraw = results != resultsOnScreen
        if (redraw) {
            // The folder's place, kept from the first results on: a second search replaces results, not the folder.
            if (resultsOnScreen == null) folderScroll = grid.firstVisiblePosition
            resultsOnScreen = results
            resultFolders = hits.associate { File(root, it.folder + "/" + it.fileName) to NameMatch.folderShown(it.folder) }
            val entries = hits.map { hit ->
                // The index holds only names ending ".pdf" (any case): the name shown is without it.
                PdfEntry(File(root, hit.folder + "/" + hit.fileName), hit.fileName.dropLast(PDF_SUFFIX_LENGTH), 0, 0)
            }
            grid.numColumns = columnsFor(resources.configuration)
            // The mark on a tile just come back from goes with the folder (cameBackFrom marks a row below).
            adapter.markedFile = null
            adapter.show(entries, thumbnails = false, listView = true, shortNames = false)
            if (scrollToTop) grid.setSelection(0)
            showEmptyNote(if (answer.rootMissing) rootMissingText(shownFolder ?: root) else "Nothing found.", answer.rootMissing, hits.isEmpty())
        }
        // Kept up, even empty, while results show: changing its words then never moves the grid.
        if (noteLine.visibility != View.VISIBLE) noteLine.visibility = View.VISIBLE
        val note = SearchNote.text(answer.note, hits.size, answer.matches.total)
        val noteChanged = noteLine.text.toString() != note
        if (noteChanged) noteLine.text = note
        // One panel update: the list with the note line over it (one view holds both), or the line alone.
        if (redraw) PanelRefresh.gentle(resultsArea) else if (noteChanged) PanelRefresh.line(noteLine)
        // Back from a document opened from the results: its row is marked, as a folder's tile is.
        if (cameBackFrom != null) {
            val at = adapter.entries.indexOfFirst { it.file == cameBackFrom }
            if (at >= 0) {
                if (at < grid.firstVisiblePosition || at > grid.lastVisiblePosition) grid.setSelection(at)
                markCameBackFrom(cameBackFrom)
            }
        }
        logShown(waitForDraw = redraw)
    }

    /** Leaves the search: nothing more of it is shown. The screen goes back to the folder with the next listing. */
    private fun endSearch() {
        search = null
    }

    /** Back, or the breadcrumb, from results: the folder that was on show, re-listed. */
    private fun leaveSearch() {
        endSearch()
        // Unmarked without a redraw of its own: the folder's listing redraws every row.
        if (resultsOnScreen != null) adapter.markedFile = null
        list()
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
        markCameBackFrom(null)
        folder = target
        list()
    }

    private fun showSettings() =
        showLibrarySettings(this, prefs, disk, onRootChosen = ::useRoot, onThumbnailsChanged = ::switchThumbnails, onListingChanged = { list() })

    /**
     * The tiles on screen get their thumbnails first, and the folders among them are watched,
     * so a PDF arriving inside one updates its count (spec §6.1).
     */
    private fun tilesOnScreenChanged(fromScroll: Boolean = false) {
        // The grid's scroll callback comes many times for one swipe, and again with nothing
        // moved. Unless the tiles on screen or the folder's contents changed since the last
        // call, there's nothing new to tell anyone. Every other caller is always acted on.
        // The grid calls its scroll listener once while it's being set up, before `grid` holds it.
        if (!::grid.isInitialized) return
        // The folder watch and the thumbnails are left as they are while results show (design §6).
        if (resultsOnScreen != null) return
        val now = listOf(grid.firstVisiblePosition, grid.childCount, System.identityHashCode(adapter.entries), System.identityHashCode(thumbnails), started)
        if (fromScroll && now == tilesLastReported) return
        tilesLastReported = now
        // The folders on screen, and the small ones shown opened out (their PDFs' group): a
        // PDF arriving in one of those must show up, or close it back into a folder.
        if (started) watch.watchChildren(watchedChildren())
        val loader = thumbnails ?: return
        // The list view shows no pictures, so none are fetched for it.
        // But the rows' writing marks and page counts come from the PDFs' facts all the same.
        if (prefs.listView) return loader.showFacts(onScreen().filterIsInstance<PdfEntry>())
        loader.show(adapter.entries.filterIsInstance<PdfEntry>(), onScreen().filterIsInstance<PdfEntry>())
    }

    private fun watchedChildren(): List<File> =
        onScreen().mapNotNull { (it as? FolderEntry)?.file ?: (it as? PdfEntry)?.group }.distinct()

    /** What [tilesOnScreenChanged] last acted on. */
    private var tilesLastReported: List<Any> = emptyList()

    /** The entries whose tiles are on screen now. */
    private fun onScreen(): List<Entry> {
        val entries = adapter.entries
        val first = grid.firstVisiblePosition.coerceIn(0, entries.size)
        val end = (first + grid.childCount).coerceIn(first, entries.size)
        return entries.subList(first, end)
    }

    private fun newThumbnails() = ThumbnailLoader(
        thumbnailCache,
        // Asked afresh each time: a card can come and go while the app runs.
        storageIn = { Volumes.holding(StorageVolumes.mounted(this), it) != null },
        onFacts = { factsReady() },
    ) { pdf, bitmap -> thumbnailReady(pdf, bitmap) }

    /** Facts arrived for list rows, one PDF at a time: the rows are bound again together, as thumbnails are shown together. A row only redraws if what it shows changed. */
    private val showArrivedFacts = Runnable { adapter.notifyDataSetChanged() }

    private fun factsReady() {
        main.removeCallbacks(showArrivedFacts)
        main.postDelayed(showArrivedFacts, FACTS_BATCH_MS)
    }

    /**
     * Thumbnails that have arrived and aren't on their tiles yet. They come one at a time, a
     * few milliseconds apart, and each tile drawn by itself is a panel update of its own: a
     * folder filled in tile by tile. They go on together once all the tiles on screen have
     * theirs, or [MISSING_WAIT_MS] after the first came at the latest.
     */
    private val pendingThumbnails = HashMap<PdfEntry, Bitmap?>()
    private val showPendingThumbnails = Runnable {
        var onTiles = 0
        for (i in 0 until grid.childCount) {
            val tile = grid.getChildAt(i) as? TileView ?: continue
            val entry = tile.entry as? PdfEntry ?: continue
            if (pendingThumbnails.containsKey(entry)) {
                tile.showBitmap(pendingThumbnails[entry])
                tile.showMarks(marksFor(entry)) // its facts came with it
                onTiles++
            }
        }
        Log.i(TAG, "thumbnails shown n=$onTiles of ${pendingThumbnails.size}")
        // Only when a tile on screen changed: a picture that came for a folder since left (or
        // for a tile scrolled away) is kept in memory, and costs no refresh of the panel.
        if (onTiles > 0) {
            PanelRefresh.gentle(grid)
            if (visitStartedAt != 0L) visitGoes++
        }
        pendingThumbnails.clear()
        logFolderReadyIfSo()
    }

    /** The loader and library folder the drawing ahead was last started for: once each. */
    private var aheadFor: Pair<ThumbnailLoader, File>? = null

    /**
     * Once the library is on screen, the thumbnails of every PDF in it that has none are drawn
     * into the cache, quietly (lighter thumbnails plan, decision 2), so a folder visited for
     * the first time comes on at once. The loader does it only when nothing on screen waits,
     * and stops while a document is open. Again for a new library folder or a new loader
     * (thumbnails switched off and on).
     */
    private fun startDrawingAhead() {
        val loader = thumbnails ?: return
        val root = root ?: return
        if (aheadFor == loader to root) return
        aheadFor = loader to root
        loader.drawAhead(LibraryWalk.pdfsUnder(root))
    }

    // A folder visit, for the "folder ready" log line: when it was asked for, how many times
    // the grid has been drawn with pictures since, and the loader's draw count then.
    private var visitStartedAt = 0L
    private var visitGoes = 0
    private var visitDrawsAtStart = 0

    private fun startVisit() {
        visitStartedAt = SystemClock.uptimeMillis()
        visitGoes = 0
        visitDrawsAtStart = thumbnails?.draws ?: 0
    }

    /**
     * Once every PDF tile on screen has its picture (or can't have one), logs how long the
     * visit took, how many times the grid was drawn on the way, and how many thumbnails had to
     * be drawn from their PDFs rather than read from the cache. Once per visit.
     */
    private fun logFolderReadyIfSo() {
        if (visitStartedAt == 0L) return
        val loader = thumbnails
        val pdfs = onScreen().filterIsInstance<PdfEntry>()
        if (loader != null && pdfs.any { loader.bitmapFor(it) == null && !loader.failedFor(it) }) return
        val drawn = (loader?.draws ?: 0) - visitDrawsAtStart
        Log.i(TAG, "folder ready ms=${SystemClock.uptimeMillis() - visitStartedAt} goes=$visitGoes drawn=$drawn cached=${(pdfs.size - drawn).coerceAtLeast(0)}")
        visitStartedAt = 0L
    }

    private fun thumbnailReady(pdf: PdfEntry, bitmap: Bitmap?) {
        val waiting = pendingThumbnails.isNotEmpty()
        pendingThumbnails[pdf] = bitmap
        // On together once every tile on screen has its picture (or can't have one), or at the
        // latest MISSING_WAIT_MS after the first arrived: one refresh, not one per few tiles.
        if (thumbnails?.waitingOnScreen() != true) {
            main.removeCallbacks(showPendingThumbnails)
            showPendingThumbnails.run()
        } else if (!waiting) {
            main.postDelayed(showPendingThumbnails, MISSING_WAIT_MS)
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
        index = NameIndex.forRoot(this, chosen)
        // A search waiting on the old index never answers (it was closed): leave it, as Back does.
        endSearch()
        list()
    }

    /**
     * Every thumbnail in the folder is thrown away, so no old picture comes back. The ones on
     * screen are drawn again now; the rest when they scroll into view, rather than the whole
     * folder at once, which in a folder of several hundred PDFs took minutes.
     */
    private fun refresh() {
        // In results: the whole library checked, then searched again. The thumbnails are left alone.
        search?.let {
            index?.check(full = true)
            return runSearch(it.query, fresh = false)
        }
        thumbnails?.let { loader ->
            val showing = onScreen().filterIsInstance<PdfEntry>().toSet()
            val (onScreen, rest) = adapter.entries.filterIsInstance<PdfEntry>().partition { it in showing }
            loader.redraw(onScreen, rest)
        }
        index?.check(full = true)
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
            // A search with no answer yet: the folder is still on show, but the screen is the search's (Back leaves it).
            is FolderEntry -> if (search == null) goTo(entry.file)
            is PdfEntry -> if (search != null) openResult(entry.file) else open(entry.file)
        }
    }

    /**
     * A result is opened only if its file is still there (the index may be behind the card).
     * Looked for on [disk], never the screen's thread; a second tap meanwhile does nothing.
     * [fromPreview]: tapped under the search box, with no results on screen to search again.
     */
    private fun openResult(pdf: File, fromPreview: Boolean = false) {
        if (opening || checkingResult) return
        val root = root ?: return
        checkingResult = true
        disk.execute {
            val rootThere = root.isDirectory
            val there = pdf.isFile
            main.post {
                checkingResult = false
                if (isDestroyed) return@post
                when {
                    there -> open(pdf)
                    // From the preview: the folder's listing says the card is out.
                    !rootThere && fromPreview -> list()
                    // The card is out, not the file gone: searched again, which answers with the card-out note.
                    !rootThere -> search?.let { runSearch(it.query, fresh = false) }
                    else -> resultGone(pdf)
                }
            }
        }
    }

    /** A result whose file has gone since the index last saw its folder: that folder is read again, then the search runs again. */
    private fun resultGone(pdf: File) {
        toast("That file isn't there any more.")
        index?.reread(listOfNotNull(pdf.parentFile))
        search?.let { runSearch(it.query, fresh = false) }
    }

    private fun open(pdf: File) {
        if (opening) return
        if (changes.busy) return toast("One moment: still finishing the last change.")
        opening = true
        prefs.lastOpened = pdf
        // No thumbnail is drawn while the document opens: waiting for the library to stop
        // left a few drawn alongside the document's first page. onResume takes it up again.
        thumbnails?.pause()
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
        /** How long thumbnails are gathered before their tiles are drawn together (see pendingThumbnails). */
        /** List rows whose facts arrive within this long of each other are bound again together. */
        const val FACTS_BATCH_MS = 120L
        /** The longest a folder waits for its visible thumbnails to be read from the cache before it is shown. */
        const val WARM_BUDGET_MS = 300L
        /** How many entries from where the screen will be are read ahead: seven rows of five, more than a screen. */
        const val FIRST_SCREEN_TILES = 35
        /** The longest missing thumbnails are held back so they come on together. */
        const val MISSING_WAIT_MS = 1500L
        /** The line under the top strip, and the same line inside a course, in the course's colour. */
        /** How tall the Continue row's picture is. */
        const val CONTINUE_PICTURE_DP = 96
        const val RULE_PX = 2
        const val COURSE_RULE_PX = 4
        /** ".pdf", taken off a result's file name. */
        const val PDF_SUFFIX_LENGTH = 4
    }
}
