package com.fzizzle.epapernotes.storage

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Names for the copy the app saves next to the original when it must not write into it: the
 * file changed on disk (spec §7.4), or it was damaged and the owner chose a copy (spec §7.3).
 */
object CopyNames {
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH-mm")

    /**
     * `<name> (my edits YYYY-MM-DD).pdf`. If that's taken, the time is added, and if that's
     * taken too, a counter. An existing file is never chosen.
     */
    fun myEdits(original: File, now: LocalDateTime): File {
        val folder = original.absoluteFile.parentFile
        val base = original.nameWithoutExtension
        val stamp = "my edits ${now.format(DATE)}"
        val withTime = "$stamp ${now.format(TIME)}"

        val candidates = sequenceOf(stamp, withTime) + generateSequence(2) { it + 1 }.map { "$withTime $it" }
        return candidates.map { File(folder, "$base ($it).pdf") }.first { !it.exists() }
    }
}
