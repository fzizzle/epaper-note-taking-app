package com.fzizzle.epapernotes.library

import java.io.File

/**
 * A path given to the library's adb check hooks (`--es root`, `--es path`), written the way the
 * library hands paths out (Ruling L1): absolute under the tablet's storage,
 * `/storage/emulated/0/…`, so a check finds the same journals and last pages the owner's taps do.
 */
object HookPath {
    private const val SDCARD = "/sdcard"

    /** [raw] under [storage], with a leading `/sdcard` written as [storage]; null if it's anywhere else. */
    fun under(storage: File, raw: String): File? {
        val path = when {
            raw == SDCARD -> storage.path
            raw.startsWith("$SDCARD/") -> storage.path + raw.removePrefix(SDCARD)
            else -> raw
        }
        // ".." could climb out of the storage while the path still starts with it.
        if (path.split('/').any { it == ".." || it == "." }) return null
        val file = File(path)
        return file.takeIf { it.isAbsolute && it.startsWith(storage) }
    }
}
