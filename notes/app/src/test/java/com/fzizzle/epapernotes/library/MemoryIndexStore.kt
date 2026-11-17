package com.fzizzle.epapernotes.library

/** An [IndexStore] held in maps, for laptop tests of the check. Paths as the check gives them ("" is the library folder). */
class MemoryIndexStore : IndexStore {
    private class Row(val time: Long, val readAt: Long, val pdfs: List<IndexedPdf>)

    private val rows = sortedMapOf<String, Row>()

    override var complete = false

    override fun folder(path: String): KnownFolder? {
        val row = rows[path] ?: return null
        return KnownFolder(row.time, row.readAt, rows.keys.filter { parentOf(it) == path && it != path }.map { it.substringAfterLast('/') })
    }

    override fun put(path: String, read: DiskFolder, readAt: Long) {
        rows[path] = Row(read.time, readAt, read.pdfs)
    }

    override fun drop(path: String) {
        rows.keys.removeAll { it == path || path.isEmpty() || it.startsWith("$path/") }
    }

    override fun <T> inBatch(block: () -> T): T = block()

    override fun search(words: List<String>, cap: Int): Matches {
        if (words.isEmpty()) return Matches(0, emptyList())
        val hits = rows.flatMap { (path, row) ->
            row.pdfs.filter { pdf -> NameMatch.key(pdf.fileName.dropLast(PDF_SUFFIX_LENGTH)).let { k -> words.all { k.contains(it) } } }
                .map { Hit(path, it.fileName) }
        }
        return Matches(hits.size, NameMatch.arrange(hits, cap))
    }

    /** Every folder path held, for tests that check what was dropped. */
    fun paths(): Set<String> = rows.keys.toSet()

    /** Every PDF held, in display order. */
    fun pdfs(): List<Hit> = NameMatch.arrange(rows.flatMap { (path, row) -> row.pdfs.map { Hit(path, it.fileName) } }, Int.MAX_VALUE)

    private fun parentOf(path: String) = if ('/' in path) path.substringBeforeLast('/') else ""

    private companion object {
        const val PDF_SUFFIX_LENGTH = 4 // ".pdf": only PDFs are indexed
    }
}
