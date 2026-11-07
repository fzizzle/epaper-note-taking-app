package com.fzizzle.epapernotes.probe

/**
 * Small pure helpers that turn raw input details into log labels. Kept free of Android
 * types so they can be unit-tested; the constants are Android's values copied here.
 */

/** Onyx passes a "shortcut" flag on stroke begin/end: true when a pen button, not the tip, started it. */
fun onyxAction(phase: String, shortcut: Boolean): String = "$phase shortcut=$shortcut"

/**
 * Key events carry more than a key code. If Boox maps both pen buttons to the same (or an
 * unknown) key code, the hardware scan code may still tell them apart.
 */
fun keyAction(action: Int, scanCode: Int, deviceId: Int, source: Int, repeat: Int): String {
    val name = when (action) {
        0 -> "KEY_DOWN"
        1 -> "KEY_UP"
        2 -> "KEY_MULTIPLE"
        else -> "KEY_ACTION($action)"
    }
    return "$name scan=$scanCode dev=$deviceId src=0x${Integer.toHexString(source)} rep=$repeat"
}

private const val SOURCE_STYLUS = 0x4002
private const val KEYCODE_BACK = 4

/**
 * True when a key event should be logged but not passed on. A pen button mapped to Back
 * would otherwise close the probe in the middle of a test.
 */
fun shouldSwallowKey(source: Int, keyCode: Int): Boolean =
    source and SOURCE_STYLUS == SOURCE_STYLUS && keyCode == KEYCODE_BACK
