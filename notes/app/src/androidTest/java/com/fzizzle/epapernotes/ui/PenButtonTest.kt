package com.fzizzle.epapernotes.ui

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fzizzle.epapernotes.tools.PenButton
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Which pen button an event carries (probe results §1), on real MotionEvents. */
@RunWith(AndroidJUnit4::class)
class PenButtonTest {

    private fun event(tool: Int, buttons: Int, action: Int = MotionEvent.ACTION_HOVER_MOVE): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
        val coords = MotionEvent.PointerCoords().apply { x = 100f; y = 100f }
        return MotionEvent.obtain(0, 0, action, 1, arrayOf(props), arrayOf(coords), 0, buttons, 1f, 1f, 0, 0, 0, 0)
    }

    @Test fun noButton() =
        assertEquals(PenButton.NONE, PageView.penButtonOf(event(MotionEvent.TOOL_TYPE_STYLUS, 0)))

    @Test fun buttonOneIsLasso() =
        assertEquals(PenButton.LASSO_BUTTON, PageView.penButtonOf(event(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY)))

    /** Button 2 pressed while hovering: still a stylus, with the secondary flag. */
    @Test fun buttonTwoWhileHoveringIsEraser() =
        assertEquals(PenButton.ERASER_END, PageView.penButtonOf(event(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_SECONDARY)))

    @Test fun buttonTwoOnTouchDownIsEraser() =
        assertEquals(
            PenButton.ERASER_END,
            PageView.penButtonOf(event(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_SECONDARY, MotionEvent.ACTION_DOWN)),
        )

    /** Button 2 held as the pen comes into range: the pen reports as an eraser. */
    @Test fun eraserToolIsEraser() =
        assertEquals(PenButton.ERASER_END, PageView.penButtonOf(event(MotionEvent.TOOL_TYPE_ERASER, 0)))

    @Test fun bothButtonsEraserWins() =
        assertEquals(
            PenButton.ERASER_END,
            PageView.penButtonOf(event(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)),
        )
}
