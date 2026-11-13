package com.fzizzle.epapernotes.doc

import java.io.File

/** Things the writing screen should tell the owner about. They arrive on the worker thread. */
sealed interface SessionEvent {
    data object Saved : SessionEvent

    /** The save didn't work. The writing is still in the journal, and the next save tries again. */
    data class SaveFailed(val message: String) : SessionEvent

    /**
     * The journal couldn't be written (the tablet's own storage is full, most likely). The
     * change is kept in memory and saved to the PDF at once, but until the journal works again
     * a crash could lose what the PDF doesn't hold yet. Sent once, not at every stroke: again
     * only after the journal has worked in between.
     */
    data object JournalFailed : SessionEvent

    /**
     * Something else changed the file while it was open, and the app had no writing the new
     * version lacks: the new version has been opened in its place. Its pages may differ, and
     * the undo list is empty: lay all the pages out again.
     */
    data object Reloaded : SessionEvent

    /**
     * A change couldn't be written into the open PDF, which may now hold only part of it. The
     * app stops saving into the file; everything is kept in the journal and comes back on the
     * next open.
     */
    data object WritingStopped : SessionEvent

    /** MuPDF repaired the file to open it. Nothing is written until the owner chooses. */
    data object NeedsRepairDecision : SessionEvent

    /** [count] changes that never reached the PDF last time were found in the journal and put back. */
    data class Replayed(val count: Int) : SessionEvent

    /**
     * Changes have reached the PDF on these [pages]: draw them again. When [rotated], a page
     * turned, so its size on screen has changed too.
     */
    data class PageChanged(val pages: Set<Int>, val rotated: Boolean) : SessionEvent

    /** Strokes on [page] that an earlier version saved with a faulty drawing were drawn again: show it again. */
    data class InkRedrawn(val page: Int) : SessionEvent

    /**
     * A page was added or removed, and the file saved with it. Every page after it has a new
     * number, and the undo list is empty: lay all the pages out again and show [showPage].
     */
    data class PagesRenumbered(val showPage: Int) : SessionEvent

    /** A page wasn't added or removed, and nothing changed. [message] says why, for the owner. */
    data class PageNotChanged(val message: String) : SessionEvent

    /** For the undo and redo buttons. */
    data class HistoryChanged(val canUndo: Boolean, val canRedo: Boolean) : SessionEvent

    /**
     * Writing that couldn't be put into the file or a copy of it is set aside in [journal],
     * untouched: writing from an earlier session whose rescue into a copy failed, writing on a
     * page that's gone, or a journal whose page numbers can't be trusted after a page change.
     */
    data class EarlierWritingKept(val journal: File) : SessionEvent

    /**
     * The writing went into a copy, and the session now continues in it. [why] says what made
     * the copy. [notPlaced]: how many rescued changes were on pages the file no longer has;
     * they are set aside, not in the copy.
     */
    data class SavedToCopy(val copy: File, val why: CopyReason = CopyReason.DAMAGED, val notPlaced: Int = 0) : SessionEvent

    data class OpenFailed(val message: String) : SessionEvent
}

/** Why the app saved the writing into a "(my edits …)" copy rather than the file itself. */
enum class CopyReason {
    /** The file was damaged, and the owner chose a copy (spec §7.3). */
    DAMAGED,

    /** Something else changed the file while it was open with unsaved writing (spec §7.4). */
    CHANGED_WHILE_OPEN,

    /** Unsaved writing from an earlier session, found after the file had changed (spec §7.4). */
    RESCUED,
}
