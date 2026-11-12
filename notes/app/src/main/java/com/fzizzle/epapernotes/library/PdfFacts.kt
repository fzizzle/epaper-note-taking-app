package com.fzizzle.epapernotes.library

/**
 * What the library shows about a PDF besides its first page: how many pages it has, and
 * whether it holds any of the owner's writing. Read from the PDF itself when its thumbnail is
 * made, so it's right for a file that arrives from a laptop too, and kept beside the thumbnail
 * (ThumbnailCache), never in the owner's folders.
 */
data class PdfFacts(val pages: Int, val hasInk: Boolean) {
    /** As kept on disk: `30 1` for thirty pages with writing. */
    fun encode(): String = "$pages ${if (hasInk) 1 else 0}"

    companion object {
        /** Null for anything that isn't exactly what [encode] writes: then the PDF is looked at again. */
        fun decode(text: String): PdfFacts? {
            val parts = text.trim().split(" ")
            if (parts.size != 2) return null
            val pages = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val ink = when (parts[1]) { "1" -> true; "0" -> false; else -> return null }
            return PdfFacts(pages, ink)
        }
    }
}
