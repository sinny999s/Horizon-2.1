package dev.horizon.client.compat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Hypixel Stats widget's API answers, levelling and colours. */
class HypixelPlayerApiTest {
    private val answer = """
        {"success":true,"player":{"displayname":"BrownieIsNice","networkExp":79342431,
         "achievements":{"bedwars_level":312},
         "stats":{"Bedwars":{"final_kills_bedwars":1500,"final_deaths_bedwars":600,"wins_bedwars":400,"losses_bedwars":350,
           "beds_broken_bedwars":900,"beds_lost_bedwars":0,"kills_bedwars":3000,"deaths_bedwars":2500,"coins_bedwars":5}}}}
    """.trimIndent()

    @Test
    fun parsesAPlayer() {
        val s = assertIs<HypixelPlayerApi.Result.Ok>(HypixelPlayerApi.parse(200, answer, 42L)).stats
        assertEquals("BrownieIsNice", s.name)
        assertEquals(312, s.bedwarsStars)
        assertEquals(2.5, s.fkdr)
        assertEquals(400.0 / 350, s.wlr)
        assertEquals(900.0, s.bblr) // no beds lost: divided by 1
        assertEquals(1500, s.finalKills)
        assertNull(s.winstreak) // hidden
        assertEquals(42L, s.fetchedAt)
        // Hypixel's own example: 79342431 XP is level 249.46...
        assertEquals(249.46, s.networkLevel, 0.01)
    }

    @Test
    fun newPlayersAndFailures() {
        // Joined, never played Bedwars.
        val fresh = assertIs<HypixelPlayerApi.Result.Ok>(HypixelPlayerApi.parse(200, """{"success":true,"player":{"displayname":"x"}}""", 0)).stats
        assertEquals(0, fresh.bedwarsStars)
        assertEquals(1.0, fresh.networkLevel)
        assertEquals(0.0, fresh.fkdr)
        // Never joined.
        assertTrue("never joined" in assertIs<HypixelPlayerApi.Result.Failed>(HypixelPlayerApi.parse(200, """{"success":true,"player":null}""", 0)).reason)
        // A bad key, a rate limit, a broken answer.
        assertTrue("API key" in assertIs<HypixelPlayerApi.Result.Failed>(HypixelPlayerApi.parse(403, """{"success":false,"cause":"Invalid API key"}""", 0)).reason)
        assertTrue("too many" in assertIs<HypixelPlayerApi.Result.Failed>(HypixelPlayerApi.parse(429, """{"success":false,"cause":"Key throttle"}""", 0)).reason)
        assertEquals("Malformed UUID", assertIs<HypixelPlayerApi.Result.Failed>(HypixelPlayerApi.parse(422, """{"success":false,"cause":"Malformed UUID"}""", 0)).reason)
        assertIs<HypixelPlayerApi.Result.Failed>(HypixelPlayerApi.parse(502, "<html>bad gateway</html>", 0))
    }

    @Test
    fun levels() {
        assertEquals(1.0, HypixelPlayerApi.networkLevel(0.0))
        assertEquals(2.0, HypixelPlayerApi.networkLevel(10_000.0), 1e-9) // 10000 XP from 1 to 2
        assertEquals(1.5, HypixelPlayerApi.networkLevel(5_000.0), 1e-9)
    }

    @Test
    fun prestigeColours() {
        assertEquals(0xFFAAAAAA.toInt(), HypixelPlayerApi.prestigeColor(42)) // stone
        assertEquals(0xFF55FFFF.toInt(), HypixelPlayerApi.prestigeColor(312)) // diamond
        assertEquals(0xFFAA00AA.toInt(), HypixelPlayerApi.prestigeColor(999)) // amethyst
        assertEquals(0xFFFFAA00.toInt(), HypixelPlayerApi.prestigeColor(1450))
    }
}
