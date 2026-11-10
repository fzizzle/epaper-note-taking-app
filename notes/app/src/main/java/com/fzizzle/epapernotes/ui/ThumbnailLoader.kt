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
import com.fzizzle.epapernotes.library.THUMB_WIDTH_PX
import com.fzizzle.epapernotes.library.ThumbnailCache
import com.fzizzle.epapernotes.pdf.PdfFile
import java.io.ByteArrayOutputStream

/**
 * Fills the library's PDF tiles with their first page (decision 8), on one background thread
 * of its own, one PDF at a time, so scrolling never waits for it.
 *
 * In order: tiles on screen first (from the disk cache if it has them, else rendered), then
 * every other PDF in the folder is rendered into the disk cache, so scrolling to it later is
 * quick. Bitmaps for the screen are kept in memory, about [MEMORY_BYTES], least recently used
 * dropped first. Tiles ask [bitmapFor], which looks only in memory.
 *
 * It exists only while Show thumbnails is on: with it off, the library makes none, so nothing
 * is rendered or read from the cache (decision 10). [onReady] runs on the main thread.
 */
class ThumbnailLoader(private val cache: ThumbnailCache, private val onReady: (PdfEntry, Bitmap) -> Unit) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()

    // All guarded by lock.
    private var folder: List<PdfEntry> = emptyList()
    private var onScreen: List<PdfEntry> = emptyList()
    /** In the disk cache, as far as this session knows, or not renderable: nothing more to do. */
    private val done = HashSet<String>()
    private val failed = HashSet<String>()
    private val drops = ArrayList<PdfEntry>()
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

    /** Refresh: these are drawn again, after their cached thumbnails are deleted. */
    fun redraw(pdfs: List<PdfEntry>) = synchronized(lock) {
        pdfs.forEach { memory.remove(key(it)) }
        drops += pdfs
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
    private class Drop(val pdfs: List<PdfEntry>) : Job
    /** For a tile on screen: into memory, rendered if the cache hasn't got it. */
    private class Show(val pdf: PdfEntry) : Job
    /** For the rest of the folder: into the disk cache only. */
    private class Store(val pdf: PdfEntry) : Job

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
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
            when (job) {
                is Drop -> job.pdfs.forEach { pdf ->
                    cache.fileFor(pdf).delete()
                    memory.remove(key(pdf))
                    synchronized(lock) { done -= key(pdf); failed -= key(pdf) }
                }
                is Show -> load(job.pdf, intoMemory = true)
                is Store -> load(job.pdf, intoMemory = false)
            }
        }
    }

    /** Under lock. */
    private fun nextJob(): Job? {
        if (drops.isNotEmpty()) return Drop(drops.toList()).also { drops.clear() }
        onScreen.firstOrNull { memory.get(key(it)) == null && key(it) !in failed }?.let { return Show(it) }
        folder.firstOrNull { key(it) !in done }?.let { return Store(it) }
        return null
    }

    private fun load(pdf: PdfEntry, intoMemory: Boolean) {
        val key = key(pdf)
        val cached = cache.cached(pdf)
        if (cached != null && !intoMemory) return finished(key, ok = true)
        // A cached file can vanish before it's read, or be damaged: then it's rendered again.
        val decoded = cached?.let { BitmapFactory.decodeFile(it.path, SMALL) }
        val bitmap = decoded ?: render(pdf) ?: return finished(key, ok = false)
        finished(key, ok = true)
        if (!intoMemory) return
        memory.put(key, bitmap)
        main.post { if (!synchronized(lock) { stopped }) onReady(pdf, bitmap) }
    }

    private fun finished(key: String, ok: Boolean) = synchronized(lock) {
        done += key
        if (!ok) failed += key
    }

    /** Renders the first page, stores it in the cache, and returns a small copy for memory; null if it can't be read. */
    private fun render(pdf: PdfEntry): Bitmap? {
        val start = SystemClock.uptimeMillis()
        return try {
            val full = PdfFile.open(pdf.file).use { it.render(0, THUMB_WIDTH_PX) }
            val png = ByteArrayOutputStream().also { full.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            cache.store(pdf, png)
            val small = full.copy(Bitmap.Config.RGB_565, false)
            full.recycle()
            Log.i(TAG, "thumbnail ${pdf.name} ms=${SystemClock.uptimeMillis() - start}")
            small
        } catch (e: Exception) {
            // Damaged, password-protected, or gone: the tile keeps its grey page.
            Log.w(TAG, "thumbnail ${pdf.name} failed: ${e.message}")
            null
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
