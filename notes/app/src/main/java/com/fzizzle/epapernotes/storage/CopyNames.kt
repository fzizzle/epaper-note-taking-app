package com.fzizzle.epapernotes.storage

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Names for the copy the app saves next to the original when it must not write into it: the
 * file changed on disk (spec §7.4), or it was damaged and the owner chose a copy (spec §7.3).
 * And for a fresh attempt, the copy without the owner's ink that the library makes on request.
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
        return candidates.map { File(folder, fitted(base, " ($it).pdf")) }.first { !it.exists() }
    }

    /**
     * [base] plus [tail], with whole characters cut from the end of [base] until the name fits
     * (and so its temporary name, which [SafeSave.tempFileFor] shortens itself, does too).
     */
    private fun fitted(base: String, tail: String): String {
        var cut = base
        while (cut.isNotEmpty() && !SafeSave.fitsName(cut + tail)) cut = cut.substring(0, cut.offsetByCodePoints(cut.length, -1))
        return cut + tail
    }

    /** From here an attempt's number isn't counted on, so counting never runs past what an Int holds. */
    private const val MAX_ATTEMPT = 1_000_000

    /** "W (attempt 3)": the name before the number, and the number. */
    private val ATTEMPT = Regex("""^(.*) \(attempt (\d+)\)$""")

    /**
     * `<name> (attempt 2).pdf`, beside the original, with its extension as it has it. An attempt
     * of an attempt counts on ("W (attempt 2)" gives "W (attempt 3)") rather than adding a second
     * bracket. A number [taken] already is skipped.
     */
    fun attempt(original: File, taken: (File) -> Boolean = File::exists): File {
        val folder = original.absoluteFile.parentFile
        val found = ATTEMPT.find(original.nameWithoutExtension)
        // A number too big to count on is just part of the name.
        val number = found?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it < MAX_ATTEMPT }
        val base = if (number != null) found.groupValues[1] else original.nameWithoutExtension
        val first = (number ?: 1) + 1
        return generateSequence(first) { it + 1 }
            .map { File(folder, fitted(base, " (attempt $it).${original.extension}")) }
            .first { !taken(it) }
    }
}
