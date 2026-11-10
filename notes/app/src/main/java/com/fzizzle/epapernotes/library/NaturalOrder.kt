package com.fzizzle.epapernotes.library

import java.math.BigInteger

/**
 * Orders names the way people expect: case-insensitive, and digit runs compare as numbers, so
 * "Lecture 2" comes before "Lecture 10". Ties fall back to the plain string so the order is stable.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                // BigInteger so an absurdly long number in a file name can't overflow.
                val byNumber = BigInteger(a.substring(i, endA)).compareTo(BigInteger(b.substring(j, endB)))
                if (byNumber != 0) return byNumber
                i = endA
                j = endB
            } else {
                val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        val byLength = (a.length - i).compareTo(b.length - j)
        return if (byLength != 0) byLength else a.compareTo(b)
    }

    private fun digitRunEnd(s: String, from: Int): Int {
        var end = from
        while (end < s.length && s[end].isDigit()) end++
        return end
    }
}
