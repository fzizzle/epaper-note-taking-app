package com.fzizzle.epapernotes.doc

import java.io.File

/** Things the writing screen should tell the owner about. They arrive on the worker thread. */
sealed interface SessionEvent {
    data object Saved : SessionEvent

    /** The save didn't work. The writing is still in the journal, and the next save tries again. */
    data class SaveFailed(val message: String) : SessionEvent

    /** Something else changed the file since it was opened. The app stops writing into it. */
    data object ChangedOnDisk : SessionEvent

    /** MuPDF repaired the file to open it. Nothing is written until the owner chooses. */
    data object NeedsRepairDecision : SessionEvent

    /** [count] changes that never reached the PDF last time were found in the journal and put back. */
    data class Replayed(val count: Int) : SessionEvent

    /**
     * Changes have reached the PDF on these [pages]: draw them again. When [rotated], a page
     * turned, so its size on screen has changed too.
     */
    data class PageChanged(val pages: Set<Int>, val rotated: Boolean) : SessionEvent

    /** For the undo and redo buttons. */
    data class HistoryChanged(val canUndo: Boolean, val canRedo: Boolean) : SessionEvent

    /**
     * The journal held writing from an earlier session, but the file has changed since. That
     * writing is set aside in [journal], untouched. Plan 5 turns it into a "(my edits …)" copy.
     */
    data class EarlierWritingKept(val journal: File) : SessionEvent

    /** The writing went into a copy, and the session now continues in it. */
    data class SavedToCopy(val copy: File) : SessionEvent

    data class OpenFailed(val message: String) : SessionEvent
}
