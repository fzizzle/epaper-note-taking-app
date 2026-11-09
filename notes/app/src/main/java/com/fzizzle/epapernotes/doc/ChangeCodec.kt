package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.ItemCodec

/**
 * A change as one line of text, so a power cut can only tear the last one:
 *
 *     add <page> <item> | <item> ...
 *     del <page> <item> | ...                  full items, so undo works after a crash replay
 *     put <page> <before items> || <after items>
 *     rot <page>=<deg>,... || <page>=<deg>,...
 *
 * Items never contain `|` (see ItemCodec), so ` || ` is split first, then ` | `.
 */
object ChangeCodec {
    private const val SIDES = " || "
    private const val ITEMS = " | "

    fun encode(c: Change): String = when (c) {
        is AddItems -> "add ${c.page} ${items(c.items)}"
        is RemoveItems -> "del ${c.page} ${items(c.items)}"
        is ReplaceItems -> "put ${c.page} ${items(c.before)}$SIDES${items(c.after)}"
        is RotatePages -> "rot ${rotations(c.before)}$SIDES${rotations(c.after)}"
    }

    /** Returns null for anything it doesn't recognise, or any line with a damaged part. */
    fun decode(line: String): Change? {
        val parts = line.split(' ', limit = 3)
        return when (parts[0]) {
            "add" -> pageAndItems(parts)?.let { (page, rest) -> decodeItems(rest)?.let { AddItems(page, it) } }
            "del" -> pageAndItems(parts)?.let { (page, rest) -> decodeItems(rest)?.let { RemoveItems(page, it) } }
            "put" -> pageAndItems(parts)?.let { (page, rest) -> decodePut(page, rest) }
            "rot" -> decodeRot(line.removePrefix("rot "))
            else -> null
        }
    }

    private fun items(list: List<Item>) = list.joinToString(ITEMS) { ItemCodec.encode(it) }

    private fun rotations(map: Map<Int, Int>) = map.entries.joinToString(",") { "${it.key}=${it.value}" }

    private fun pageAndItems(parts: List<String>): Pair<Int, String>? {
        if (parts.size != 3) return null
        val page = parts[1].toIntOrNull() ?: return null
        return page to parts[2]
    }

    private fun decodeItems(text: String): List<Item>? {
        if (text.isEmpty()) return emptyList()
        return text.split(ITEMS).map { ItemCodec.decode(it) ?: return null }
    }

    private fun decodePut(page: Int, rest: String): Change? {
        val at = rest.indexOf(SIDES)
        if (at < 0) return null
        val before = decodeItems(rest.substring(0, at)) ?: return null
        val after = decodeItems(rest.substring(at + SIDES.length)) ?: return null
        return ReplaceItems(page, before, after)
    }

    private fun decodeRot(rest: String): Change? {
        val at = rest.indexOf(SIDES)
        if (at < 0) return null
        val before = decodeRotations(rest.substring(0, at)) ?: return null
        val after = decodeRotations(rest.substring(at + SIDES.length)) ?: return null
        return RotatePages(before, after)
    }

    private fun decodeRotations(text: String): Map<Int, Int>? {
        if (text.isEmpty()) return emptyMap()
        val map = LinkedHashMap<Int, Int>()
        for (entry in text.split(',')) {
            val kv = entry.split('=')
            if (kv.size != 2) return null
            map[kv[0].toIntOrNull() ?: return null] = kv[1].toIntOrNull() ?: return null
        }
        return map
    }
}
