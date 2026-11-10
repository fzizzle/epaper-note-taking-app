package com.fzizzle.epapernotes.library

import java.io.File

/** The path shown in the library's top bar: the root, then each folder down to the one on show. */
object Breadcrumb {
    /**
     * Parts from [root] (named after its own folder) down to [folder]. A folder outside the root
     * gives just the root, since the library never goes above it. Paths are only normalized, not
     * canonicalized, so they stay the same strings the journals and last pages are keyed by.
     */
    fun parts(root: File, folder: File): List<Pair<String, File>> {
        val rootPath = root.absoluteFile.normalize()
        val rootPart = rootPath.name.ifEmpty { rootPath.path } to root
        val relative = runCatching { folder.absoluteFile.normalize().relativeTo(rootPath) }.getOrNull()
            ?: return listOf(rootPart)
        val names = relative.path.split(File.separatorChar).filter { it.isNotEmpty() }
        if (names.isEmpty() || names.first() == "..") return listOf(rootPart)
        var current = root
        return listOf(rootPart) + names.map { name ->
            current = File(current, name)
            name to current
        }
    }
}
