package com.fzizzle.epapernotes.library

/**
 * What the PDF tiles in one folder are called (UI review, decision 1): when every name starts
 * with the same text up to a dash or colon, that part is left out, so a topic's two PDFs read
 * "answer key" and "workbook" instead of the same long start cut off before the part that
 * differs. Numbered names ("Lecture 1", "Lecture 2") have no dash and stay whole.
 */
object TileNames {
    /** The places a shared start may end: just after one of these. */
    private val SEPARATORS = listOf(" — ", " – ", " - ", ": ")

    /** One label per name, in the same order. Either every name is shortened or none is. */
    fun labels(names: List<String>): List<String> {
        if (names.size < 2) return names
        val shared = names.reduce { a, b -> a.commonPrefixWith(b) }
        val cut = SEPARATORS.maxOf { sep -> shared.lastIndexOf(sep).let { if (it < 0) 0 else it + sep.length } }
        if (cut == 0) return names
        val labels = names.map { it.substring(cut) }
        return if (labels.any { it.isBlank() }) names else labels
    }
}
