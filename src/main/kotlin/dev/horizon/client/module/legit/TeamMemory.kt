package dev.horizon.client.module.legit

/**
 * Who [Teams] last saw as a teammate, for the moments a server shows nothing to tell a player's
 * team by. A Bedwars teammate who respawns reappears a moment before his dyed armour and team colour do, and Aura would
 * swing at him in that gap. A teammate stays one while every check has nothing to compare ([Signal.UNKNOWN]). A check
 * that sees two different teams ([Signal.MISMATCH]) forgets him at once, so a waiting room where everyone's name is the
 * same grey doesn't carry into the match. [Teams] also forgets everyone when the world changes or a Bedwars match
 * starts or ends.
 */
class TeamMemory<K> {
    enum class Signal { MATCH, MISMATCH, UNKNOWN }

    private val teammates = HashSet<K>()

    /** Whether [key] counts as a teammate, given what the checks say now. With [remember] off it's the plain check. */
    fun verdict(key: K, signal: Signal, remember: Boolean): Boolean = when (signal) {
        Signal.MATCH -> {
            teammates += key
            true
        }
        Signal.MISMATCH -> {
            teammates -= key
            false
        }
        Signal.UNKNOWN -> remember && key in teammates
    }

    fun clear() = teammates.clear()
}
