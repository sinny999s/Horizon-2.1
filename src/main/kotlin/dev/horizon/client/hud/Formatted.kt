package dev.horizon.client.hud

/**
 * A number formatted with [pattern] (as String.format, in the default locale), formatted again only when it changes:
 * String.format parses its pattern on every call, and a widget draws its numbers every frame.
 */
class Formatted(private val pattern: String) {
    private var last: Any? = null
    private var text = ""

    fun of(value: Any): String {
        if (value != last) {
            last = value
            text = String.format(pattern, value)
        }
        return text
    }
}
