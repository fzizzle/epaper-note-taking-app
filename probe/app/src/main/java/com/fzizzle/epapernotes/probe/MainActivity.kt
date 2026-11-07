package com.fzizzle.epapernotes.probe

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * The probe screen. Wires the drawing surface, Onyx fast-ink and the log together.
 * Nothing here interprets results; it records them for the owner and for the write-up.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var log: ProbeLog
    private lateinit var surface: ProbeSurface
    private lateinit var onyx: OnyxInput
    private lateinit var toggle: Button
    private lateinit var buttonState: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val file = File(getExternalFilesDir(null), "probe-log.txt")
        log = ProbeLog(file)
        log.note("==== Pen Probe start: model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} log=${file.absolutePath}")

        surface = findViewById(R.id.surface)
        toggle = findViewById(R.id.fastInkToggle)
        buttonState = findViewById(R.id.buttonState)
        logView = findViewById(R.id.logView)
        onyx = OnyxInput(surface, log)

        surface.onEvent = { e ->
            log.add(e)
            // The screen can't refresh during fast-ink, so only update the view when it's off.
            if (!onyx.isOn) {
                buttonState.text = "Buttons: ${buttonNames(e.buttonState)}"
                refreshLogSoon()
            }
        }

        toggle.setOnClickListener { setFastInk(!onyx.isOn) }
        findViewById<Button>(R.id.showLog).setOnClickListener {
            setFastInk(false)
            refreshLog()
        }
        findViewById<Button>(R.id.clear).setOnClickListener {
            log.note("---- CLEAR ----")
            log.clearScreen()
            surface.clearInk()
            refreshLog()
        }
        val sideErase = findViewById<Button>(R.id.sideErase)
        sideErase.setOnClickListener {
            // Takes effect the next time fast-ink turns on.
            onyx.sideButtonErase = !onyx.sideButtonErase
            setFastInk(false)
            sideErase.text = if (onyx.sideButtonErase) "Side-erase: ON" else "Side-erase: OFF"
            log.note("sideButtonErase set to ${onyx.sideButtonErase}")
            refreshLog()
        }
        findViewById<Button>(R.id.mark).setOnClickListener {
            log.note("---- MARK ----")
            refreshLog()
        }
        refreshLog()
    }

    private fun setFastInk(on: Boolean) {
        if (on) onyx.turnOn() else onyx.turnOff()
        surface.fastInk = onyx.isOn
        toggle.text = if (onyx.isOn) "Fast-ink: ON" else "Fast-ink: OFF"
    }

    // Some pens send their buttons as key presses rather than motion-event bits.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        log.add(
            ProbeEvent(
                source = EventSource.KEY,
                fastInk = onyx.isOn,
                toolType = -1,
                action = keyAction(event.action, event.scanCode, event.deviceId, event.source, event.repeatCount),
                buttonState = -1,
                actionButton = -1,
                keyCode = event.keyCode,
                x = -1f,
                y = -1f,
                pressure = 0f,
            ),
        )
        if (!onyx.isOn) refreshLogSoon()
        if (shouldSwallowKey(event.source, event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        // Leaving fast-ink on in the background can leave the panel in a stuck state.
        setFastInk(false)
        super.onPause()
    }

    // E-ink redraws are slow; batch log updates instead of redrawing on every event.
    private var refreshPending = false

    private fun refreshLogSoon() {
        if (refreshPending) return
        refreshPending = true
        logView.postDelayed({
            refreshPending = false
            refreshLog()
        }, 250)
    }

    private fun refreshLog() {
        logView.text = log.recent(25).joinToString("\n")
    }
}
