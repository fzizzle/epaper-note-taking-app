package com.fzizzle.epapernotes.library

import java.text.Normalizer
import java.util.Locale

/**
 * How a typed query is matched against file names, and how results are ordered. Capitals and
 * accents are ignored; every word of the query must be somewhere in the name, in any order.
 */
object NameMatch {
    /** The most results shown; the rest are only counted. */
    const val SEARCH_LIMIT = 200

    private val combiningMarks = Regex("\\p{Mn}+")

    /** The match key of a name without ".pdf": accents dropped, then lower case. */
    fun key(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase(Locale.ROOT)

    /** The query split on whitespace, each word through [key]; empty words dropped. */
    fun words(query: String): List<String> =
        query.split(Regex("\\s+")).map { key(it) }.filter { it.isNotEmpty() }

    /** True when every word of [query] is in [name]. An empty query matches nothing. */
    fun matches(name: String, query: String): Boolean {
        val words = words(query)
        if (words.isEmpty()) return false
        val k = key(name)
        return words.all { k.contains(it) }
    }

    /** A pattern for `LIKE ? ESCAPE '\'` that finds [word] anywhere, taking `\`, `%` and `_` literally. */
    fun likePattern(word: String): String {
        val escaped = word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    /**
     * Natural order of the folder path (name by name, so "Week 2/" comes before "Week 10/"),
     * then of the file name; the first [limit] of them.
     */
    fun arrange(hits: List<Hit>, limit: Int): List<Hit> =
        hits.sortedWith(byFolderThenName).take(limit)

    /** A folder path as shown to the reader: "ML › Metrics". "" for the library folder. */
    fun folderShown(folder: String): String = folder.split('/').filter { it.isNotEmpty() }.joinToString(" › ")

    private val byFolderThenName = Comparator<Hit> { a, b ->
        val byFolder = compareFolders(a.folder, b.folder)
        if (byFolder != 0) byFolder else NaturalOrder.compare(a.fileName, b.fileName)
    }

    private fun compareFolders(a: String, b: String): Int {
        val pa = a.split('/').filter { it.isNotEmpty() }
        val pb = b.split('/').filter { it.isNotEmpty() }
        for (i in 0 until minOf(pa.size, pb.size)) {
            val c = NaturalOrder.compare(pa[i], pb[i])
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }
}
