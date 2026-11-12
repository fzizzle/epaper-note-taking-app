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
class ThumbnailLoader(private val cache: ThumbnailCache, private val onReady: (PdfEntry, Bitmap?) -> Unit) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()

    // All guarded by lock.
    private var folder: List<PdfEntry> = emptyList()
    private var onScreen: List<PdfEntry> = emptyList()
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

    /** Main thread: the folder on show, and the PDFs whose tiles are on screen now. */
    fun show(folder: List<PdfEntry>, onScreen: List<PdfEntry>) = synchronized(lock) {
        this.folder = folder
        this.onScreen = onScreen
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

    private fun run() {
        var priority = Process.THREAD_PRIORITY_BACKGROUND
        Process.setThreadPriority(priority)
        // Thumbnails of PDFs that are gone; it can trip over a file that vanishes as it reads.
        try {
            cache.pruneMissing()
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
            val wanted = if (job is Show) Process.THREAD_PRIORITY_DEFAULT else Process.THREAD_PRIORITY_BACKGROUND
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
        folder.firstOrNull { key(it) !in done }?.let { return Store(it) }
        return null
    }

    private fun load(pdf: PdfEntry, intoMemory: Boolean) {
        val key = key(pdf)
        val cached = cache.cached(pdf)
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
        memory.put(key, bitmap)
        tell(pdf, bitmap)
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
        val full = PdfFile.open(pdf.file).use {
            val size = it.displaySize(0)
            // Narrower than usual for a very tall page, so no thumbnail is huge (THUMB_MAX_HEIGHT_PX).
            it.render(0, thumbWidthPx(size.width, size.height), smoothInk = true)
        }
        try {
            try {
                val png = ByteArrayOutputStream().also { full.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                cache.store(pdf, png)
            } catch (e: Exception) {
                Log.w(TAG, "thumbnail ${pdf.name} not cached: ${e.message}")
            }
            Log.i(TAG, "thumbnail ${pdf.name} ms=${SystemClock.uptimeMillis() - start}")
            return if (forScreen) full.copy(Bitmap.Config.RGB_565, false) else null
        } finally {
            full.recycle()
        }
    }

    private fun key(pdf: PdfEntry) = "${pdf.file.path}|${pdf.size}|${pdf.modified}"

    private companion object {
        const val TAG = "Notes"
        const val MEMORY_BYTES = 40 * 1024 * 1024
        /** 16-bit colour: half the memory, and at thumbnail size on e-ink it looks the same. */
        val SMALL = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    }
}
