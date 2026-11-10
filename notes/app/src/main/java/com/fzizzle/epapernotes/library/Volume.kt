package com.fzizzle.epapernotes.library

import java.io.File

/**
 * A place files can be: the tablet's own storage, or a memory card (or USB drive) that's in.
 * [directory] is the path Android gives it, `/storage/emulated/0` or `/storage/3331-3031`,
 * kept exactly as given and never canonicalized (Ruling L1, widened to memory cards): the
 * journals and last pages are keyed by those strings.
 */
data class Volume(val label: String, val directory: File, val isPrimary: Boolean)

/** What Android says about one storage volume, before it gets a label. */
data class VolumeInfo(val directory: File, val isPrimary: Boolean, val description: String?)

object Volumes {
    const val PRIMARY_LABEL = "Tablet storage"
    const val CARD_LABEL = "SD card"

    /**
     * The tablet's storage first, as "Tablet storage", then the others: "SD card" when there's
     * one (or its description if that mentions USB), or each its own description when there are several (a card and a USB drive).
     */
    fun labelled(found: List<VolumeInfo>): List<Volume> {
        val others = found.filter { !it.isPrimary }
        return found.filter { it.isPrimary }.map { Volume(PRIMARY_LABEL, it.directory, true) } +
            others.map {
                val description = it.description?.takeIf(String::isNotBlank)
                val label = if (others.size == 1) {
                    if (description != null && description.contains("USB", ignoreCase = true)) description else CARD_LABEL
                } else description ?: it.directory.name
                Volume(label, it.directory, false)
            }
    }

    /** The volume [file] is on, or null if it's on none that's in (say, a card taken out). */
    fun holding(volumes: List<Volume>, file: File): Volume? =
        volumes.filter { file.startsWith(it.directory) }.maxByOrNull { it.directory.path.length }

    /**
     * Where [folder] is, for Settings: `Documents › Notes`, or with a memory card in (or the
     * folder on one), `SD card › Workbooks`. A folder on a card that's out shows as its path.
     */
    fun whereIs(volumes: List<Volume>, folder: File): String {
        val volume = holding(volumes, folder) ?: return folder.path
        val parts = Breadcrumb.parts(volume.directory, folder).drop(1).map { it.first }
        val named = if (volumes.size > 1 || !volume.isPrimary) listOf(volume.label) + parts else parts
        return named.joinToString(" › ").ifEmpty { volume.label }
    }

    /** A library folder that isn't there because the card it's on is out, not because it was deleted. */
    fun isOnCardThatsOut(volumes: List<Volume>, folder: File): Boolean = holding(volumes, folder) == null
}
