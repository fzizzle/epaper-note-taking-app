package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Affine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolStateTest {
    @Test fun buttonOneMeansLassoOnlyForThatStroke() {
        val s = ToolState()
        assertEquals(Tool.LASSO, s.strokeTool(PenButton.LASSO_BUTTON))
        assertEquals(Tool.PEN, s.chosen)
        assertEquals(Tool.PEN, s.strokeTool(PenButton.NONE))
    }

    @Test fun eraserEndMeansEraser() {
        assertEquals(Tool.ERASER, ToolState().strokeTool(PenButton.ERASER_END))
    }

    @Test fun selectionPausesFastInk() {
        val s = ToolState()
        assertTrue(s.fastInkOn())
        s.hasSelection = true
        assertFalse(s.fastInkOn())
    }

    @Test fun pasteArmedPausesFastInk() {
        val s = ToolState()
        s.pasteArmed = true
        assertFalse(s.fastInkOn())
    }

    @Test fun snipReturnsToThePreviousTool() {
        val s = ToolState()
        s.chosen = Tool.HIGHLIGHTER
        s.chosen = Tool.SNIP
        assertFalse(s.fastInkOn())
        s.snipDone()
        assertEquals(Tool.HIGHLIGHTER, s.chosen)
        assertTrue(s.fastInkOn())
    }

    @Test fun pasteShowsOnlyWithAClipboard() {
        val s = ToolState()
        assertFalse(s.showPaste)
        s.clipboard = Clipboard(emptyList(), Affine(1f, 0f, 0f, 1f, 0f, 0f))
        assertTrue(s.showPaste)
    }

    @Test fun eraserAndLassoDrawThinGrey() {
        assertEquals(LiveLine.THIN_GREY, liveLine(Tool.ERASER))
        assertEquals(LiveLine.THIN_GREY, liveLine(Tool.LASSO))
        assertEquals(LiveLine.PEN, liveLine(Tool.PEN))
        assertEquals(LiveLine.HIGHLIGHTER, liveLine(Tool.HIGHLIGHTER))
    }
}
