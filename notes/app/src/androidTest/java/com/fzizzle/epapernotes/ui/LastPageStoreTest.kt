package com.fzizzle.epapernotes.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The real preferences: a renamed or moved file keeps its page, and only its own. */
@RunWith(AndroidJUnit4::class)
class LastPageStoreTest {
    private val store = LastPageStore(InstrumentationRegistry.getInstrumentation().targetContext)
    // Paths no real file has, removed afterwards.
    private val a = "/storage/emulated/0/.pnotes-test/A.pdf"
    private val b = "/storage/emulated/0/.pnotes-test/B.pdf"

    @After fun tearDown() {
        store.remove(a)
        store.remove(b)
    }

    @Test fun moveCarriesThePage() {
        store.set(a, 4)
        store.move(a, b)
        assertEquals(4, store.get(b))
        assertEquals(0, store.get(a))
    }

    /** Review: a file with no page, moved onto a name with a page left over, starts at the first page. */
    @Test fun moveWithNoPageDropsAStaleOne() {
        store.set(b, 9)
        store.move(a, b)
        assertEquals(0, store.get(b))
    }
}
