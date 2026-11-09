package com.fzizzle.epapernotes.geometry

/** A point. Which space it's in (PDF points, display points, screen pixels) depends on where it comes from. */
data class Pt(val x: Float, val y: Float)

/**
 * A 2D transform: scale, rotate, move. It has the same six numbers and meaning as a PDF matrix:
 *
 *     x' = a·x + c·y + e
 *     y' = b·x + d·y + f
 *
 * The app uses these to move between PDF space (where strokes are stored), display points
 * (the page as shown, rotated, origin top-left) and screen pixels (zoomed and scrolled).
 */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {

    fun map(p: Pt): Pt = Pt(a * p.x + c * p.y + e, b * p.x + d * p.y + f)

    /** The transform that undoes this one. */
    fun inverse(): Affine {
        val det = a * d - b * c
        require(det != 0f) { "transform squashes everything flat and can't be undone" }
        return Affine(
            a = d / det,
            b = -b / det,
            c = -c / det,
            d = a / det,
            e = (c * f - d * e) / det,
            f = (b * e - a * f) / det,
        )
    }

    /** This transform first, then [next]. */
    fun then(next: Affine): Affine = Affine(
        a = a * next.a + b * next.c,
        b = a * next.b + b * next.d,
        c = c * next.a + d * next.c,
        d = c * next.b + d * next.d,
        e = e * next.a + f * next.c + next.e,
        f = e * next.b + f * next.d + next.f,
    )

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)

        fun scale(s: Float) = Affine(s, 0f, 0f, s, 0f, 0f)

        fun translate(dx: Float, dy: Float) = Affine(1f, 0f, 0f, 1f, dx, dy)
    }
}
