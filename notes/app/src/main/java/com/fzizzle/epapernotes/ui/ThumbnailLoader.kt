package com.fzizzle.epapernotes.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.fzizzle.epapernotes.library.PdfEntry
import com.fzizzle.epapernotes.library.PdfFacts
import com.fzizzle.epapernotes.library.THUMB_MAX_HEIGHT_PX
import com.fzizzle.epapernotes.library.ThumbnailCache
import com.fzizzle.epapernotes.library.thumbWidthPx
import com.fzizzle.epapernotes.pdf.PdfFile
import java.io.ByteArrayOutputStream

/**
 * Fills the library's PDF tiles with their first page (decision 8), on one background thread
 * of its own, one PDF at a time, so scrolling never waits for it.
 *
 * In order: tiles on screen first (from the disk cache if it has them, else rendered), then
 * every other PDF in the folder is rendered into the disk cache, so scrolling to it later is
 * quick. (After a refresh the others wait until they scroll into view: see [redraw].) Bitmaps
 * for the screen are kept in memory, about [MEMORY_BYTES], least recently used dropped first.
 * Tiles ask [bitmapFor], which looks only in memory.
 *
 * It exists only while Show thumbnails is on: with it off, the library makes none, so nothing
 * is rendered or read from the cache (decision 10). [onReady] runs on the main thread, with
 * null for a tile on screen whose PDF can't be drawn: its tile drops any old picture.
 */
class ThumbnailLoader(
    private val cache: ThumbnailCache,
    /** Whether the storage a PDF lies on is in (ThumbnailCache.pruneMissing): asked on this loader's thread. */
    private val storageIn: (java.io.File) -> Boolean = { true },
    /** A PDF's facts were found for [showFacts], with no picture: main thread. */
    private val onFacts: (PdfEntry) -> Unit = {},
    private val onReady: (PdfEntry, Bitmap?) -> Unit,
) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Each PDF's facts, for every thumbnail that went into [memory]. Kept for the loader's life: a few bytes each. */
    private val factsInMemory = java.util.concurrent.ConcurrentHashMap<String, PdfFacts>()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()

    // All guarded by lock.
    private var folder: List<PdfEntry> = emptyList()
    private var onScreen: List<PdfEntry> = emptyList()
    /** The PDFs whose facts are wanted without their pictures ([showFacts]). */
    private var factsWanted: List<PdfEntry> = emptyList()
    /**
     * Nothing more to do in the background: in the disk cache, as far as this session knows,
     * or not renderable, or left by a refresh until its tile comes on screen ([redraw]).
     * A tile on screen doesn't ask this: it's drawn whenever memory hasn't got it.
     */
    private val done = HashSet<String>()
    private val failed = HashSet<String>()
    private val drops = ArrayList<PdfEntry>()
    private val dropsForLater = ArrayList<PdfEntry>()
    private var paused = false
    private var stopped = false

    init {
        Thread(::run, "thumbnails").start()
    }

    /** Main thread. Memory only: never the disk. */
    fun bitmapFor(pdf: PdfEntry): Bitmap? = memory.get(key(pdf))

    /** Whether [pdf]'s thumbnail couldn't be made this session (its tile stays the grey page). */
    fun failedFor(pdf: PdfEntry): Boolean = synchronized(lock) { key(pdf) in failed }

    /** How many thumbnails this loader has drawn from a PDF (not read from the cache): for the log and for tests. */
    @Volatile internal var draws = 0
        private set

    /** Main thread. What's known of [pdf] (PdfFacts), from memory only; it comes with the thumbnail ([onReady]). */
    fun factsFor(pdf: PdfEntry): PdfFacts? = factsInMemory[key(pdf)]

    /** Main thread: the folder on show, and the PDFs whose tiles are on screen now. */
    fun show(folder: List<PdfEntry>, onScreen: List<PdfEntry>) = synchronized(lock) {
        this.folder = folder
        this.onScreen = onScreen
        this.factsWanted = emptyList()
        lock.notifyAll()
    }

    /**
     * Main thread, for the list view, which shows no pictures: finds the facts of [pdfs] (the
     * rows on screen) from the cache, or from the PDF itself without drawing it, and tells
     * [onFacts] for each. No pictures are made meanwhile.
     */
    fun showFacts(pdfs: List<PdfEntry>) = synchronized(lock) {
        folder = emptyList()
        onScreen = emptyList()
        factsWanted = pdfs
        lock.notifyAll()
    }

    /**
     * Refresh. The cached thumbnails of all of these are deleted, so none can be shown again
     * as it was. [onScreen] are then drawn again straight away. [rest], the folder's other
     * PDFs, are drawn again when their tiles scroll into view, and not before: drawing a
     * whole folder of several hundred again in the background took minutes.
     */
    fun redraw(onScreen: List<PdfEntry>, rest: List<PdfEntry>) = synchronized(lock) {
        (onScreen + rest).forEach { memory.remove(key(it)) }
        drops += onScreen
        dropsForLater += rest
        lock.notifyAll()
    }

    /** While the library is off screen (a document is open), it renders nothing. */
    fun pause() = synchronized(lock) { paused = true }

    fun resume() = synchronized(lock) {
        paused = false
        lock.notifyAll()
    }

    /** Ends the thread after the PDF in hand, if any. */
    fun stop() = synchronized(lock) {
        stopped = true
        lock.notifyAll()
    }

    private sealed interface Job
    /** [later]: deleted like [now], but not drawn again until they come on screen. */
    private class Drop(val now: List<PdfEntry>, val later: List<PdfEntry>) : Job
    /** For a tile on screen: into memory, rendered if the cache hasn't got it. */
    private class Show(val pdf: PdfEntry) : Job
    /** For the rest of the folder: into the disk cache only. */
    private class Store(val pdf: PdfEntry) : Job
    /** For a list row: its facts only, no picture. */
    private class Facts(val pdf: PdfEntry) : Job

    private fun run() {
        var priority = Process.THREAD_PRIORITY_BACKGROUND
        Process.setThreadPriority(priority)
        // Thumbnails of PDFs that are gone; it can trip over a file that vanishes as it reads.
        try {
            cache.pruneMissing(storageIn = storageIn)
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail prune failed", e)
        }
        while (true) {
            val job = synchronized(lock) {
                var next: Job? = null
                while (next == null) {
                    if (stopped) return
                    next = if (paused) null else nextJob()
                    if (next == null) lock.wait()
                }
                next
            }
            // A tile on screen is waiting for a Show: that runs at normal priority. At background
            // priority the tablet keeps the thread to its slow cores, and a folder's tiles filled
            // in one by one. The rest of the folder (Store) and the tidying stay in the background.
            val wanted = if (job is Show || job is Facts) Process.THREAD_PRIORITY_DEFAULT else Process.THREAD_PRIORITY_BACKGROUND
            if (wanted != priority) {
                priority = wanted
                Process.setThreadPriority(priority)
            }
            when (job) {
                is Drop -> {
                    job.now.forEach { pdf ->
                        cache.fileFor(pdf).delete()
                        memory.remove(key(pdf))
                        synchronized(lock) { done -= key(pdf); failed -= key(pdf) }
                    }
                    // Marked done so the background pass skips them. No longer failed, and not
                    // in memory or the cache, so each is drawn afresh once its tile is on screen.
                    job.later.forEach { pdf ->
                        cache.fileFor(pdf).delete()
                        memory.remove(key(pdf))
                        synchronized(lock) { done += key(pdf); failed -= key(pdf) }
                    }
                }
                is Facts -> loadFacts(job.pdf)
                is Show -> load(job.pdf, intoMemory = true)
                is Store -> load(job.pdf, intoMemory = false)
            }
        }
    }

    /** Under lock. */
    private fun nextJob(): Job? {
        if (drops.isNotEmpty() || dropsForLater.isNotEmpty()) {
            return Drop(drops.toList(), dropsForLater.toList()).also { drops.clear(); dropsForLater.clear() }
        }
        onScreen.firstOrNull { memory.get(key(it)) == null && key(it) !in failed }?.let { return Show(it) }
        factsWanted.firstOrNull { factsInMemory[key(it)] == null && key(it) !in failed }?.let { return Facts(it) }
        folder.firstOrNull { key(it) !in done }?.let { return Store(it) }
        return null
    }

    private fun load(pdf: PdfEntry, intoMemory: Boolean) {
        val key = key(pdf)
        // A thumbnail cached before facts were kept has none beside it: counted as not cached,
        // so the PDF is looked at again, once.
        val cachedFacts = cache.facts(pdf)
        val cached = if (cachedFacts != null) cache.cached(pdf) else null
        if (cached != null && !intoMemory) return finished(key, ok = true)
        // A cached file can vanish before it's read, or be damaged: then it's rendered again.
        // One cached before thumbnails had a height limit is far too tall: also rendered again.
        val decoded = cached?.let { BitmapFactory.decodeFile(it.path, SMALL) }?.takeIf { it.height <= THUMB_MAX_HEIGHT_PX }
        val bitmap = decoded ?: try {
            render(pdf, forScreen = intoMemory)
        } catch (e: Throwable) {
            // Damaged, password-protected, gone, or too big to draw (out of memory, which is
            // why this catches Throwable: it would end this thread and every later thumbnail
            // with it). A tile on screen goes to the grey page, rather than keep a picture of
            // what the file used to be.
            Log.w(TAG, "thumbnail ${pdf.name} failed: ${e.message}")
            finished(key, ok = false)
            if (intoMemory) tell(pdf, null)
            return
        }
        finished(key, ok = true)
        if (bitmap == null) return
        // A rendering put its facts in already; one read from the cache brings the cached ones.
        if (decoded != null && cachedFacts != null) factsInMemory[key] = cachedFacts
        memory.put(key, bitmap)
        tell(pdf, bitmap)
    }

    /** A list row's facts: from the cache, else from the PDF, opened but not drawn. Kept in the cache for next time. */
    private fun loadFacts(pdf: PdfEntry) {
        val key = key(pdf)
        val facts = cache.facts(pdf) ?: try {
            PdfFile.open(pdf.file).use { factsOf(it, pdf) }.also { cache.storeFacts(pdf, it) }
        } catch (e: Throwable) {
            Log.w(TAG, "facts ${pdf.name} failed: ${e.message}")
            synchronized(lock) { failed += key } // not asked again this session
            return
        }
        factsInMemory[key] = facts
        main.post { if (!synchronized(lock) { stopped }) onFacts(pdf) }
    }

    /**
     * The page count, and whether any page has the owner's writing: the look stops at the
     * first page that has, and at [INK_PAGES_LOOKED_AT]. A page that can't be read (a damaged
     * file) counts as having none: it must not cost the PDF its thumbnail.
     */
    private fun factsOf(file: PdfFile, pdf: PdfEntry): PdfFacts {
        val start = SystemClock.uptimeMillis()
        val pages = file.pageCount
        val hasInk = try {
            (0 until minOf(pages, INK_PAGES_LOOKED_AT)).any { file.hasOwnItems(it) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "looking for writing in ${pdf.name} failed: ${e.message}")
            false
        }
        Log.i(TAG, "facts ${pdf.name} pages=$pages ms=${SystemClock.uptimeMillis() - start}")
        return PdfFacts(pages, hasInk)
    }

    private fun tell(pdf: PdfEntry, bitmap: Bitmap?) {
        main.post { if (!synchronized(lock) { stopped }) onReady(pdf, bitmap) }
    }

    private fun finished(key: String, ok: Boolean) = synchronized(lock) {
        done += key
        if (!ok) failed += key
    }

    /**
     * Renders the first page and stores it in the cache. [forScreen]: also returns a small copy
     * for memory (else null). Throws if the PDF can't be read. A thumbnail the cache can't keep
     * (storage full, say) is still shown; it's just drawn again next time.
     */
    private fun render(pdf: PdfEntry, forScreen: Boolean): Bitmap? {
        val start = SystemClock.uptimeMillis()
        draws++
        var facts: PdfFacts? = null
        val full = PdfFile.open(pdf.file).use {
            val size = it.displaySize(0)
            // Narrower than usual for a very tall page, so no thumbnail is huge (THUMB_MAX_HEIGHT_PX).
            val picture = it.render(0, thumbWidthPx(size.width, size.height), smoothInk = true)
            facts = factsOf(it, pdf) // while the file is open
            picture
        }
        facts?.let { factsInMemory[key(pdf)] = it }
        var storeStart = 0L
        try {
            try {
                // Stored without a see-through channel (the page is solid anyway): Android reads
                // a PNG that has one at 32-bit whatever is asked, twice the memory of 16-bit.
                full.setHasAlpha(false)
                val png = ByteArrayOutputStream().also { full.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                storeStart = SystemClock.uptimeMillis()
                cache.store(pdf, png)
                facts?.let { cache.storeFacts(pdf, it) } // after the thumbnail: storing that sweeps the file's older versions
            } catch (e: Exception) {
                Log.w(TAG, "thumbnail ${pdf.name} not cached: ${e.message}")
            }
            Log.i(TAG, "thumbnail ${pdf.name} ms=${SystemClock.uptimeMillis() - start} store=${if (storeStart > 0) SystemClock.uptimeMillis() - storeStart else -1}")
            return if (forScreen) full.copy(Bitmap.Config.RGB_565, false) else null
        } finally {
            full.recycle()
        }
    }

    private fun key(pdf: PdfEntry) = "${pdf.file.path}|${pdf.size}|${pdf.modified}"

    private companion object {
        const val TAG = "Notes"
        const val MEMORY_BYTES = 40 * 1024 * 1024
        /** How many pages are looked at for the owner's writing: past this a file just counts as having none. */
        const val INK_PAGES_LOOKED_AT = 300
        /** 16-bit colour: half the memory, and at thumbnail size on e-ink it looks the same. */
        val SMALL = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    }
}
