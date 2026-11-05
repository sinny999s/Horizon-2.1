package dev.horizon.client.combat

/**
 * Not in the original: what a line of Hypixel's chat says happened, for Session Stats. Hypixel announces every death
 * in chat ("Victim was knocked into the void by Killer.", "FINAL KILL!" on the end of a final one) and every bed
 * ("BED DESTRUCTION > Red Bed was destroyed by Killer!"). Kill messages come in many themes, so the shape is read, not
 * the words: one sentence opening with the victim's name, without a "name: message" colon. Kept free of the game so
 * it can be tested on its own.
 */
object HypixelChat {
    enum class Kind { NONE, KILL, FINAL_KILL, DEATH, FINAL_DEATH, BED }

    /** What [raw] (`§` codes or not) means for the player called [me]. */
    fun classify(raw: String, me: String): Kind {
        val text = raw.replace(FORMAT, "").trim()
        if (text.startsWith("BED DESTRUCTION")) return if (named(text, me)) Kind.BED else Kind.NONE
        val final = text.endsWith("FINAL KILL!")
        val body = text.removeSuffix("FINAL KILL!").trim()
        if (!body.endsWith(".") || ':' in body || body.count { it == ' ' } > 16) return Kind.NONE
        val victim = body.substringBefore(' ')
        if (victim == body || !NAME.matches(victim) || NOT_DEATH.containsMatchIn(body)) return Kind.NONE
        return when {
            victim == me -> if (final) Kind.FINAL_DEATH else Kind.DEATH
            named(body.substringAfter(' '), me) -> if (final) Kind.FINAL_KILL else Kind.KILL
            else -> Kind.NONE
        }
    }

    private fun named(text: String, name: String) =
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])").containsMatchIn(text)

    private val FORMAT = Regex("§.")
    private val NAME = Regex("[A-Za-z0-9_]{1,16}")
    /** Lines shaped like a death that aren't one: someone (re)connecting, leaving or joining. */
    private val NOT_DEATH = Regex("^\\S+ (reconnected|disconnected|left|joined|has )")
}
