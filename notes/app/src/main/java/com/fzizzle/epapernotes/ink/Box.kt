package com.fzizzle.epapernotes.ink

/** A rectangle in PDF user space: x0,y0 is the lower-left corner, x1,y1 the upper-right. */
data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float)
