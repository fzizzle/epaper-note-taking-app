package com.fzizzle.epapernotes.check

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.ui.WritingActivity
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test of right and wrong: a way to drive what adb can't, two fingers. It opens the
 * owner's test copy, pinches in, turns pages with the volume key, and taps Fit, saving a
 * screenshot after each step to the app's `files/check/zoom-*.png` for a person to look at
 * beside the `Notes` log. Skipped by the normal run (`-e notPackage …check`), and when the
 * test copy isn't on the tablet. It writes nothing into the PDF.
 */
@RunWith(AndroidJUnit4::class)
class ZoomCheck {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val out = File(inst.targetContext.getExternalFilesDir(null), "check")

    @Test fun pinchTurnAndFit() {
        val pdf = File("/sdcard/Documents/E-paper Notes test/PWT test copy.pdf")
        assumeTrue("the test copy isn't on the tablet", pdf.isFile)
        out.mkdirs()
        val activity = inst.startActivitySync(
            Intent(inst.targetContext, WritingActivity::class.java).putExtra(WritingActivity.EXTRA_PATH, pdf.path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            pause(5000)
            shot("1-opened")
            pinch(930f, 900f, startGap = 150f, endGap = 450f)
            pause(3000)
            shot("2-pinched")
            repeat(2) {
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
                pause(2500)
                shot("3-turned-${it + 1}")
            }
            tap(viewCalled(activity, "Fit the whole page"))
            pause(2500)
            shot("4-fitted")
        } finally {
            activity.finish()
            pause(1500)
        }
    }

    private fun pause(ms: Long) = SystemClock.sleep(ms)

    private fun shot(name: String) {
        val bitmap = inst.uiAutomation.takeScreenshot()
        if (bitmap == null) {
            Log.w("ZoomCheck", "no screenshot for $name")
            return
        }
        File(out, "zoom-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        Log.i("ZoomCheck", "shot $name")
    }

    private fun viewCalled(activity: android.app.Activity, description: String): View {
        val found = ArrayList<View>()
        inst.runOnMainSync { activity.window.decorView.findViewsWithText(found, description, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION) }
        return found.first()
    }

    private fun tap(view: View) {
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        val x = at[0] + view.width / 2f
        val y = at[1] + view.height / 2f
        val down = SystemClock.uptimeMillis()
        send(down, down, MotionEvent.ACTION_DOWN, listOf(x to y))
        send(down, down + 60, MotionEvent.ACTION_UP, listOf(x to y))
    }

    /** Two fingers either side of ([cx], [cy]), moving apart along a slant from [startGap] to [endGap] pixels. */
    private fun pinch(cx: Float, cy: Float, startGap: Float, endGap: Float) {
        fun fingers(gap: Float) = listOf(cx - gap / 2 to cy - gap / 3, cx + gap / 2 to cy + gap / 3)
        val down = SystemClock.uptimeMillis()
        send(down, down, MotionEvent.ACTION_DOWN, fingers(startGap).take(1))
        send(down, down + 20, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), fingers(startGap))
        val steps = 30
        for (i in 1..steps) {
            SystemClock.sleep(20)
            send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, fingers(startGap + (endGap - startGap) * i / steps))
        }
        send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), fingers(endGap))
        send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, fingers(endGap).take(1))
    }

    private fun send(downTime: Long, time: Long, action: Int, at: List<Pair<Float, Float>>) {
        val props = at.indices.map { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = at.map { (x, y) -> MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, time, action, at.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        inst.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }
}
