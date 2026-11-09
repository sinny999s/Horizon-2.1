package dev.horizon.client.module.legit

import kotlin.math.abs
import kotlin.math.max

/**
 * Game-free team-matching primitives across multi-server formats (legacy `§0`..`§f` and Bungee
 * `§x§R§R§G§G§B§B` hex codes, rank/level-prefixed display names where only the colour active on the username matters,
 * dyed leather armour RGB tolerance with undyed-leather filtering, and clan `[TAG]` prefixes).
 */
object TeamMatcher {
    const val WHITE_RGB = 0xFFFFFF
    const val DEFAULT_LEATHER_RGB = 0xA06540

    private val LEGACY_RGB = intArrayOf(
        0x000000, // 0 black
        0x0000AA, // 1 dark_blue
        0x00AA00, // 2 dark_green
        0x00AAAA, // 3 dark_aqua
        0xAA0000, // 4 dark_red
        0xAA00AA, // 5 dark_purple
        0xFFAA00, // 6 gold
        0xAAAAAA, // 7 gray
        0x555555, // 8 dark_gray
        0x5555FF, // 9 blue
        0x55FF55, // a green
        0x55FFFF, // b aqua
        0xFF5555, // c red
        0xFF55FF, // d light_purple
        0xFFFF55, // e yellow
        0xFFFFFF  // f white
    )

    /** A styled text span flattened from a Minecraft `Component` tree (`styleRgb` is `null` when uncoloured). */
    data class Span(val text: String, val styleRgb: Int?)

    /** Maps a legacy `0..9` / `a..f` formatting character to its 24-bit RGB colour, or `null` (`r` resets). */
    fun legacyCodeRgb(code: Char): Int? {
        val c = code.lowercaseChar()
        val idx = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> 10 + (c - 'a')
            else -> -1
        }
        return if (idx >= 0) LEGACY_RGB[idx] else null
    }

    /**
     * Resolves the effective 24-bit RGB colour active on [playerName] inside [spans] (or, if [playerName] doesn't appear,
     * the last colour seen in a prefix span). Handles servers that put level/star tags first, e.g. `§7[142✫] §cPlayer`.
     */
    fun extractNameColor(spans: List<Span>, playerName: String, ignoreWhite: Boolean = true): Int? {
        if (spans.isEmpty()) return null
        val plain = StringBuilder()
        val charColors = ArrayList<Int?>()

        for (span in spans) {
            var active: Int? = span.styleRgb?.and(0xFFFFFF)
            val s = span.text
            var i = 0
            while (i < s.length) {
                if (s[i] == '§' && i + 1 < s.length) {
                    val code = s[i + 1].lowercaseChar()
                    if (code == 'x' && i + 13 < s.length && isBungeeHex(s, i + 2)) {
                        active = parseBungeeHex(s, i + 2)
                        i += 14
                        continue
                    }
                    when (code) {
                        in '0'..'9', in 'a'..'f' -> active = legacyCodeRgb(code)
                        'r' -> active = span.styleRgb?.and(0xFFFFFF)
                        // k, l, m, n, o keep the current colour
                    }
                    i += 2
                    continue
                }
                plain.append(s[i])
                charColors.add(active)
                i++
            }
        }

        val full = plain.toString()
        val chosen: Int? = if (playerName.isNotEmpty()) {
            val idx = full.indexOf(playerName, ignoreCase = true)
            if (idx >= 0 && idx < charColors.size) {
                charColors[idx] ?: lastNonNullBefore(charColors, idx)
            } else {
                charColors.lastOrNull { it != null }
            }
        } else {
            charColors.lastOrNull { it != null }
        }

        val rgb = chosen?.and(0xFFFFFF) ?: return null
        if (ignoreWhite && rgb == WHITE_RGB) return null
        return rgb
    }

    /** Convenience overload for a single legacy-formatted string. */
    fun extractLegacyColor(text: String, playerName: String = "", ignoreWhite: Boolean = true): Int? =
        extractNameColor(listOf(Span(text, null)), playerName, ignoreWhite)

    /** True when two 24-bit RGB colours are within [tolerance] per channel (0..255). */
    fun colorsMatch(rgbA: Int?, rgbB: Int?, tolerance: Int = 0, ignoreWhite: Boolean = false): Boolean {
        if (rgbA == null || rgbB == null) return false
        val a = rgbA and 0xFFFFFF
        val b = rgbB and 0xFFFFFF
        if (ignoreWhite && (a == WHITE_RGB || b == WHITE_RGB)) return false
        if (tolerance <= 0) return a == b
        val dr = abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF))
        val dg = abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db)) <= tolerance
    }

    /** Filters undyed default leather (`0xA06540`) when [ignoreDefaultLeather] is on, returning 24-bit RGB or `null`. */
    fun normalizeArmorColor(rgb: Int?, ignoreDefaultLeather: Boolean = true): Int? {
        val c = rgb?.and(0xFFFFFF) ?: return null
        if (ignoreDefaultLeather && colorsMatch(c, DEFAULT_LEATHER_RGB, tolerance = 6)) return null
        return c
    }

    /**
     * Extracts a leading clan/team tag like `[RED]`, `[BLUE]`, `[HORIZON]` before [playerName] (stripping legacy `§`
     * codes, pure numeric level tags like `[120]`, and single-letter badges).
     */
    fun extractPrefixTag(rawText: String, playerName: String): String? {
        val clean = stripLegacy(rawText).trim()
        if (clean.isEmpty()) return null
        val before = if (playerName.isNotEmpty()) {
            val idx = clean.indexOf(playerName, ignoreCase = true)
            if (idx > 0) clean.substring(0, idx).trim() else clean
        } else {
            clean
        }
        val match = TAG_REGEX.findAll(before)
            .map { it.groupValues[1].trim() }
            .filter { tag -> tag.length in 2..14 && tag.any { it.isLetter() } }
            .firstOrNull()
        return match?.uppercase()
    }

    fun stripLegacy(text: String): String {
        if ('§' !in text) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '§' && i + 1 < text.length) {
                if (text[i + 1].lowercaseChar() == 'x' && i + 13 < text.length && isBungeeHex(text, i + 2)) {
                    i += 14
                    continue
                }
                i += 2
                continue
            }
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    private fun lastNonNullBefore(list: List<Int?>, index: Int): Int? {
        for (i in (index - 1) downTo 0) {
            val c = list[i]
            if (c != null) return c
        }
        return null
    }

    private fun isBungeeHex(s: String, start: Int): Boolean {
        for (k in 0 until 6) {
            if (s[start + k * 2] != '§') return false
            val h = s[start + k * 2 + 1].lowercaseChar()
            if (h !in '0'..'9' && h !in 'a'..'f') return false
        }
        return true
    }

    private fun parseBungeeHex(s: String, start: Int): Int {
        var out = 0
        for (k in 0 until 6) {
            out = (out shl 4) or s[start + k * 2 + 1].digitToInt(16)
        }
        return out
    }

    private val TAG_REGEX = Regex("""\[([A-Za-z0-9_-]{2,14})]""")
}
