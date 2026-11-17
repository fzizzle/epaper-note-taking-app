package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.ui.NameIndex.Note
import org.junit.Assert.assertEquals
import org.junit.Test

/** The search's note line, and a result's folder path shortened to fit its row. */
class SearchTextsTest {
    @Test fun nothingToSay() = assertEquals("", SearchNote.text(Note.NONE, 12, 12))

    @Test fun twoHundredIsNotMany() = assertEquals("", SearchNote.text(Note.NONE, 200, 200))

    @Test fun manyAreCountedWithGrouping() =
        assertEquals("Showing 200 of 1,234. Type more to narrow it.", SearchNote.text(Note.NONE, 200, 1234))

    @Test fun aCheckStillRunning() = assertEquals("Checking for changes…", SearchNote.text(Note.CHECKING, 3, 3))

    @Test fun theFirstBuild() = assertEquals("Still reading the library…", SearchNote.text(Note.STILL_READING, 3, 3))

    @Test fun aCheckAndMany() =
        assertEquals("Checking for changes…   Showing 200 of 5,000. Type more to narrow it.", SearchNote.text(Note.CHECKING, 200, 5000))

    /** One unit per letter, so room is counted in letters. */
    private val letters: (String) -> Float = { it.length.toFloat() }

    @Test fun aPathThatFitsIsLeftAlone() = assertEquals("ML › Metrics", shortenedFromStart("ML › Metrics", 12f, letters))

    @Test fun wholeFoldersGoFromTheStart() =
        assertEquals("… › Topic", shortenedFromStart("ML Machine Learning › Metrics › Topic", 10f, letters))

    @Test fun asManyFoldersAsFitStay() =
        assertEquals("… › Metrics › Topic", shortenedFromStart("ML Machine Learning › Metrics › Topic", 20f, letters))

    @Test fun aLastFolderTooWideLosesLetters() =
        assertEquals("…Topic", shortenedFromStart("Year › A Long Topic", 6f, letters))

    @Test fun noRoomAtAll() = assertEquals("…", shortenedFromStart("Topic", 0.5f, letters))
}
