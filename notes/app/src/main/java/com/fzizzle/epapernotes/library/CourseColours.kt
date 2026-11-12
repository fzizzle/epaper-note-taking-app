package com.fzizzle.epapernotes.library

import java.io.File

/**
 * A colour for each course: each folder directly inside the library folder, by the first word
 * of its name (the course code, "BSA" of "BSA Building Stress Analysis"). On the colour
 * screen it tells the courses apart before their names are read. Worked out from the path
 * alone: nothing is kept, and nothing on disk is asked.
 */
object CourseColours {
    /**
     * Eight colours the Kaleido screen keeps apart, dark enough to show as a thin stripe.
     * Changing one changes a course's colour; changing how many there are changes them all.
     */
    val PALETTE: List<Int> = listOf(
        0xFFC0392B, 0xFFD68910, 0xFFB7950B, 0xFF1E8449,
        0xFF148F77, 0xFF1F618D, 0xFF6C3483, 0xFFAF4D98,
    ).map { it.toInt() }

    /**
     * The course [file] belongs to: the first word of the folder directly under [root] that
     * holds it (or is it). Null for [root] itself, for a PDF lying directly in it, and for
     * anything outside it.
     */
    fun courseOf(root: File, file: File): String? {
        if (file == root || !file.startsWith(root)) return null
        val under = file.relativeTo(root).invariantSeparatorsPath.split('/')
        val top = under.first()
        if (under.size == 1 && top.endsWith(".pdf", ignoreCase = true)) return null
        return top.trim().substringBefore(' ').ifEmpty { null }
    }

    /**
     * Which of [PALETTE] a course code gets: its letters' values added up, so the same code
     * gets the same colour on every run and in every version of the app.
     */
    fun indexFor(code: String): Int = code.uppercase().sumOf { it.code } % PALETTE.size

    /**
     * A colour for each of the library's courses ([codes], upper-cased), no two alike while
     * there are colours left. Each takes the colour its letters give ([indexFor]); when
     * that's taken, the next free one. Worked through in alphabetical order, so it comes out
     * the same however the folders were listed, and a new course only moves the ones it
     * bumps into. Past [PALETTE]'s size, courses share.
     */
    fun assign(codes: List<String>): Map<String, Int> {
        val given = LinkedHashMap<String, Int>()
        for (code in codes.map { it.uppercase() }.distinct().sorted()) {
            var index = indexFor(code)
            if (given.size < PALETTE.size) while (index in given.values) index = (index + 1) % PALETTE.size
            given[code] = index
        }
        return given
    }
}
