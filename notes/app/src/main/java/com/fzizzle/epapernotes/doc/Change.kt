package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.Item

/**
 * One undoable step on the document. Each carries what it needs to be reversed, so undo is
 * just applying [inverse], even after a crash replay has rebuilt the changes from the journal.
 */
sealed interface Change {
    /** The pages this change touches. */
    val pages: Set<Int>

    fun inverse(): Change
}

data class AddItems(val page: Int, val items: List<Item>) : Change {
    override val pages get() = setOf(page)
    override fun inverse() = RemoveItems(page, items)
}

/** Carries the full items, not just ids, so its inverse can put them back. */
data class RemoveItems(val page: Int, val items: List<Item>) : Change {
    override val pages get() = setOf(page)
    override fun inverse() = AddItems(page, items)
}

/** Moving or resizing: the same ids in [before] and [after]. */
data class ReplaceItems(val page: Int, val before: List<Item>, val after: List<Item>) : Change {
    override val pages get() = setOf(page)
    override fun inverse() = ReplaceItems(page, after, before)
}

/** Page to rotation in degrees, for each page that turns. */
data class RotatePages(val before: Map<Int, Int>, val after: Map<Int, Int>) : Change {
    override val pages get() = before.keys + after.keys
    override fun inverse() = RotatePages(after, before)
}
