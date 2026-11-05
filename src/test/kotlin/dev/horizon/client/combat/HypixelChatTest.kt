package dev.horizon.client.combat

import dev.horizon.client.combat.HypixelChat.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Hypixel Bedwars chat lines, as Session Stats reads them, for the player "Me_1". */
class HypixelChatTest {
    private fun k(line: String) = HypixelChat.classify(line, "Me_1")

    @Test
    fun kills() {
        assertEquals(Kind.KILL, k("Steve was killed by Me_1."))
        assertEquals(Kind.KILL, k("Steve was knocked into the void by Me_1."))
        assertEquals(Kind.KILL, k("§cSteve §7was shot by §9Me_1§7."))
        assertEquals(Kind.KILL, k("Steve was killed by Me_1's Iron Golem."))
        assertEquals(Kind.FINAL_KILL, k("Steve was spooked by Me_1. FINAL KILL!"))
    }

    @Test
    fun deaths() {
        assertEquals(Kind.DEATH, k("Me_1 was killed by Steve."))
        assertEquals(Kind.DEATH, k("Me_1 fell into the void."))
        assertEquals(Kind.FINAL_DEATH, k("Me_1 was knocked into the void by Steve. FINAL KILL!"))
    }

    @Test
    fun beds() {
        assertEquals(Kind.BED, k("BED DESTRUCTION > Red Bed was destroyed by Me_1!"))
        assertEquals(Kind.NONE, k("BED DESTRUCTION > Your Bed was destroyed by Steve!"))
    }

    @Test
    fun notKillsOrDeaths() {
        // Someone else's fight, a name that only contains yours, player chat, joins and reconnects, sentences of
        // yours that aren't deaths.
        assertEquals(Kind.NONE, k("Steve was killed by Alex."))
        assertEquals(Kind.NONE, k("Steve was killed by Me_12."))
        assertEquals(Kind.NONE, k("Steve: I was killed by Me_1."))
        assertEquals(Kind.NONE, k("[MVP+] Steve: gg Me_1."))
        assertEquals(Kind.NONE, k("Me_1 reconnected."))
        assertEquals(Kind.NONE, k("Me_1 disconnected."))
        assertEquals(Kind.NONE, k("Me_1 has joined (3/8)!"))
        assertEquals(Kind.NONE, k("Me_1 joined the party."))
        assertEquals(Kind.NONE, k("You will respawn in 5 seconds!"))
        assertEquals(Kind.NONE, k("Protect your bed and destroy the enemy beds."))
        assertEquals(Kind.NONE, k("TEAM ELIMINATED > Red Team has been eliminated!"))
    }
}
