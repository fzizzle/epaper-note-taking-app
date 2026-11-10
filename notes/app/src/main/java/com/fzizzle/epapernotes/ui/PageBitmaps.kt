package com.fzizzle.epapernotes.ui

/**
 * Rendered pages kept in memory, so scrolling back doesn't redraw them. When the total
 * passes [maxBytes], the page used longest ago is dropped first.
 *
 * Generic so it can be tested without Android: the app stores Bitmaps, tests store strings.
 *
 * [onEvicted] is handed every picture that leaves: replaced by a newer one in its place,
 * pushed out to make room, or dropped by [invalidate]. The app frees the Bitmap's memory
 * there, at once: left to the garbage collector, pictures of 20 MB and more pile up through
 * a run of erases and undos. Whoever holds a picture got from here must therefore not keep it
 * past a [put] or [invalidate]: it may have been handed back.
 */
class PageBitmaps<B : Any>(
    private val maxBytes: Long,
    private val sizeOf: (B) -> Long,
    private val onEvicted: (B) -> Unit = {},
) {

    private data class Key(val page: Int, val widthPx: Int)

    // accessOrder = true: iteration goes from least to most recently used.
    private val entries = LinkedHashMap<Key, B>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(page: Int, widthPx: Int): B? = entries[Key(page, widthPx)]

    /** Any rendering of [page], at whatever width: something to show while the right one renders. */
    @Synchronized
    fun anyFor(page: Int): B? = entries.entries.lastOrNull { it.key.page == page }?.value

    @Synchronized
    fun put(page: Int, widthPx: Int, bitmap: B) {
        entries.put(Key(page, widthPx), bitmap)?.let {
            bytes -= sizeOf(it)
            // The very same picture put again in its own place is still in use: not handed back.
            if (it !== bitmap) onEvicted(it)
        }
        bytes += sizeOf(bitmap)
        val oldest = entries.entries.iterator()
        while (bytes > maxBytes && oldest.hasNext()) {
            val e = oldest.next()
            if (e.key == Key(page, widthPx)) continue // never drop the one just added
            bytes -= sizeOf(e.value)
            oldest.remove()
            onEvicted(e.value)
        }
    }

    /** Drops every rendering of [page], because its ink changed. */
    @Synchronized
    fun invalidate(page: Int) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.page == page) {
                bytes -= sizeOf(e.value)
                it.remove()
                onEvicted(e.value)
            }
        }
    }
}
