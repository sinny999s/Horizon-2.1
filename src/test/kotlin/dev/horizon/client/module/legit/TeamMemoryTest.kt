package dev.horizon.client.module.legit

import dev.horizon.client.module.legit.TeamMemory.Signal.MATCH
import dev.horizon.client.module.legit.TeamMemory.Signal.MISMATCH
import dev.horizon.client.module.legit.TeamMemory.Signal.UNKNOWN
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [TeamMemory] through a Bedwars teammate's respawn, a grey waiting room and a match ending. */
class TeamMemoryTest {
    @Test
    fun `a respawning teammate stays one until his team shows`() {
        val m = TeamMemory<String>()
        assertTrue(m.verdict("mate", MATCH, remember = true))
        // Respawned: no dyed armour, no team colour yet.
        repeat(5) { assertTrue(m.verdict("mate", UNKNOWN, remember = true)) }
        assertTrue(m.verdict("mate", MATCH, remember = true))
    }

    @Test
    fun `without remember the gap is not covered`() {
        val m = TeamMemory<String>()
        assertTrue(m.verdict("mate", MATCH, remember = false))
        assertFalse(m.verdict("mate", UNKNOWN, remember = false))
    }

    @Test
    fun `a player never seen as a teammate is not one`() {
        val m = TeamMemory<String>()
        assertFalse(m.verdict("enemy", UNKNOWN, remember = true))
        assertFalse(m.verdict("enemy", MISMATCH, remember = true))
        assertFalse(m.verdict("enemy", UNKNOWN, remember = true))
    }

    @Test
    fun `another team's colour forgets at once`() {
        // Everyone grey in the waiting room matches; the match colours them, and the enemy's red tells.
        val m = TeamMemory<String>()
        assertTrue(m.verdict("enemy", MATCH, remember = true))
        assertFalse(m.verdict("enemy", MISMATCH, remember = true))
        assertFalse(m.verdict("enemy", UNKNOWN, remember = true))
    }

    @Test
    fun `clear forgets everyone`() {
        val m = TeamMemory<String>()
        m.verdict("a", MATCH, remember = true)
        m.verdict("b", MATCH, remember = true)
        m.clear()
        assertFalse(m.verdict("a", UNKNOWN, remember = true))
        assertFalse(m.verdict("b", UNKNOWN, remember = true))
    }

    @Test
    fun `one player's verdict doesn't touch another's`() {
        val m = TeamMemory<String>()
        m.verdict("mate", MATCH, remember = true)
        m.verdict("enemy", MISMATCH, remember = true)
        assertTrue(m.verdict("mate", UNKNOWN, remember = true))
        assertFalse(m.verdict("enemy", UNKNOWN, remember = true))
    }
}
