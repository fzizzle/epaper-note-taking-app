package com.fzizzle.epapernotes.probe

import java.util.Locale

/** Where an input event came from. Comparing these is the whole point of the probe. */
enum class EventSource { TOUCH, HOVER, KEY, ONYX_DRAW, ONYX_ERASE }

/**
 * One input event, flattened to plain values so it can be logged and unit-tested
 * without an Android runtime. Ints are raw Android values; -1 means "doesn't apply".
 */
data class ProbeEvent(
    val source: EventSource,
    val fastInk: Boolean,
    val toolType: Int,
    val action: String,
    val buttonState: Int,
    val actionButton: Int,
    val keyCode: Int,
    val x: Float,
    val y: Float,
    val pressure: Float,
)

// Android's MotionEvent.BUTTON_* bit values, copied so this file has no Android dependency.
private val BUTTON_BITS = listOf(
    1 to "PRIMARY",
    2 to "SECONDARY",
    4 to "TERTIARY",
    8 to "BACK",
    16 to "FORWARD",
    32 to "STYLUS_PRIMARY",
    64 to "STYLUS_SECONDARY",
)

/** Decodes a MotionEvent button bitmask into readable names, e.g. "STYLUS_PRIMARY+STYLUS_SECONDARY". */
fun buttonNames(buttonState: Int): String {
    if (buttonState == 0) return "none"
    val names = BUTTON_BITS.filter { (bit, _) -> buttonState and bit != 0 }.map { it.second }.toMutableList()
    val known = BUTTON_BITS.fold(0) { acc, (bit, _) -> acc or bit }
    val unknown = buttonState and known.inv()
    // Boox firmware might use non-standard bits; show them rather than hide them.
    if (unknown != 0) names += "0x" + Integer.toHexString(unknown)
    return names.joinToString("+")
}

/** Maps MotionEvent.TOOL_TYPE_* to a name. An eraser end of a pen reports ERASER. */
fun toolName(toolType: Int): String = when (toolType) {
    -1 -> "-"
    1 -> "FINGER"
    2 -> "STYLUS"
    3 -> "MOUSE"
    4 -> "ERASER"
    else -> "UNKNOWN($toolType)"
}

/** One log line describing the event. */
fun ProbeEvent.describe(): String {
    val buttons = if (buttonState == -1) "-" else buttonNames(buttonState)
    val actionBtn = if (actionButton == -1) "-" else buttonNames(actionButton)
    val key = if (keyCode == -1) "-" else keyCode.toString()
    val ink = if (fastInk) "on" else "off"
    val p = String.format(Locale.US, "%.2f", pressure)
    return "$source fastInk=$ink tool=${toolName(toolType)} action=$action buttons=$buttons " +
        "actionButton=$actionBtn key=$key x=${x.toInt()} y=${y.toInt()} p=$p"
}
