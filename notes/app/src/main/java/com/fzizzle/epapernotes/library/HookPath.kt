package com.fzizzle.epapernotes.library

import java.io.File

/**
 * A path given to the library's adb check hooks (`--es root`, `--es path`), written the way the
 * library hands paths out (Ruling L1): absolute under a volume that's in, `/storage/emulated/0/…`
 * or `/storage/3331-3031/…`, so a check finds the same journals and last pages the owner's taps do.
 */
object HookPath {
    private const val SDCARD = "/sdcard"

    /**
     * [raw] under one of [volumes], with a leading `/sdcard` written as the first, the tablet's
     * own storage; null if it's anywhere else.
     */
    fun under(volumes: List<File>, raw: String): File? {
        val storage = volumes.firstOrNull() ?: return null
        val path = when {
            raw == SDCARD -> storage.path
            raw.startsWith("$SDCARD/") -> storage.path + raw.removePrefix(SDCARD)
            else -> raw
        }
        // ".." could climb out of the volume while the path still starts with it.
        if (path.split('/').any { it == ".." || it == "." }) return null
        val file = File(path)
        return file.takeIf { f -> f.isAbsolute && volumes.any { f.startsWith(it) } }
    }
}
