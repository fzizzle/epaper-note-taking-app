package com.fzizzle.epapernotes.probe

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the probe's record. Every line goes straight to [file] so nothing is lost if the
 * app is killed mid-test, and the last [KEEP] lines stay in memory for the on-screen view.
 */
class ProbeLog(private val file: File) {
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(event: ProbeEvent) = write(event.describe())

    fun note(text: String) = write(text)

    @Synchronized
    fun recent(n: Int): List<String> = lines.toList().takeLast(n)

    @Synchronized
    fun clearScreen() = lines.clear()

    @Synchronized
    private fun write(text: String) {
        val line = "${time.format(Date())} $text"
        // Synchronous append on purpose: a few hundred events per second is fine for a probe.
        file.appendText(line + "\n")
        lines.addLast(line)
        while (lines.size > KEEP) lines.removeFirst()
    }

    private companion object {
        const val KEEP = 200
    }
}
