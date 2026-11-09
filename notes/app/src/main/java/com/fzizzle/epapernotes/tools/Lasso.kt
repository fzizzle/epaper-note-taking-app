package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item

/** Which items a lasso loop picks up. All in PDF space; the loop closes itself. */
object Lasso {
    fun selects(items: List<Item>, loop: List<Pt>): List<Item> =
        if (loop.size < 3) emptyList() else items.filter { selected(it, loop) }

    private fun selected(item: Item, loop: List<Pt>): Boolean = when (item) {
        is ImageItem -> inside((item.box.x0 + item.box.x1) / 2, (item.box.y0 + item.box.y1) / 2, loop)
        is InkItem -> item.stroke.points.count { inside(it.x, it.y, loop) } * 2 > item.stroke.points.size
    }

    /** Even-odd rule: a ray to the right crosses the loop an odd number of times when inside. */
    private fun inside(x: Float, y: Float, loop: List<Pt>): Boolean {
        var in_ = false
        var j = loop.lastIndex
        for (i in loop.indices) {
            val a = loop[i]
            val b = loop[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) in_ = !in_
            j = i
        }
        return in_
    }
}
