package dev.horizon.client.config

import dev.horizon.client.setting.BindSetting
import dev.horizon.client.setting.BoolSetting
import dev.horizon.client.setting.ColorSetting
import dev.horizon.client.setting.EnumSetting
import dev.horizon.client.setting.FloatSetting
import dev.horizon.client.setting.IntSetting
import dev.horizon.client.setting.Setting
import dev.horizon.client.setting.StringSetting
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicConfigDataTest {
    enum class Mode { LEGIT, BLATANT }

    private class Holder(override val id: String, override val settings: List<Setting<*>>) : SettingsHolder

    private fun aura() = Holder("aura", listOf(
        BoolSetting("enabled", false), BindSetting("bind"), BoolSetting("hidden", false), FloatSetting("range", 3f, 1f..6f),
        IntSetting("cps", 10, 1..20), EnumSetting("mode", Mode.LEGIT), ColorSetting("color", Color.WHITE),
        StringSetting("note", "hi"),
    ))

    private fun spammer() = Holder("spammer", listOf(BoolSetting("enabled", false), StringSetting("message", "hello")))

    private fun e(id: String, vararg s: Pair<String, String>) = HolderEntry(id, s.map { SettingEntry(it.first, it.second) })

    @Test
    fun keepsOnlyWhatAPublicConfigMayHold() {
        val got = PublicConfigData.clean(listOf(
            e("aura", "enabled" to "true", "bind" to """{"key":14}""", "hidden" to "true", "range" to "3.5", "cps" to "12",
                "mode" to "BLATANT", "color" to "-1", "note" to "/msg everyone hi", "unknown" to "1"),
            e("spammer", "enabled" to "true", "message" to "buy my stuff"),
            e("not-in-this-client", "enabled" to "true"),
        ), listOf(aura(), spammer()))
        assertEquals(listOf(e("aura", "enabled" to "true", "range" to "3.5", "cps" to "12", "mode" to "BLATANT", "color" to "-1")), got)
    }

    @Test
    fun bringsNumbersIntoRangeAndDropsJunk() {
        val got = PublicConfigData.clean(listOf(
            e("aura", "range" to "999", "cps" to "-5", "enabled" to "yes", "mode" to "NOPE", "color" to "red"),
        ), listOf(aura()))
        assertEquals(listOf(e("aura", "range" to "6.0", "cps" to "1")), got)
        assertEquals(emptyList(), PublicConfigData.clean(listOf(e("aura", "range" to "NaN", "cps" to "1e9")), listOf(aura())))
        assertNull(PublicConfigData.shareable(FloatSetting("x", 1f, 0f..2f), "Infinity"))
    }

    @Test
    fun aSettingTwiceCountsOnce() {
        val got = PublicConfigData.clean(listOf(e("aura", "cps" to "12", "cps" to "20")), listOf(aura()))
        assertEquals(listOf(e("aura", "cps" to "12")), got)
    }

    @Test
    fun adoptingKeepsYourPersonalSettings() {
        val a = aura()
        val s = spammer()
        (a.settings[1] as BindSetting).value = dev.horizon.client.setting.Bind(14)
        (a.settings[7] as StringSetting).value = "mine"
        (s.settings[0] as BoolSetting).value = false
        (s.settings[1] as StringSetting).value = "my message"
        val got = PublicConfigData.adopt(listOf(
            e("aura", "enabled" to "true", "bind" to """{"key":99}""", "cps" to "15", "note" to "theirs"),
            e("spammer", "enabled" to "true", "message" to "theirs"),
        ), listOf(a, s))
        val aura = got.first { it.id == "aura" }.settings.associate { it.id to it.value }
        assertEquals("true", aura["enabled"])
        assertEquals("15", aura["cps"])
        assertTrue(aura["bind"]!!.contains("14"), aura["bind"])
        assertEquals("mine", aura["note"])
        assertEquals("3.0", aura["range"], "what it doesn't set stays yours")
        val spam = got.first { it.id == "spammer" }.settings.associate { it.id to it.value }
        assertEquals(mapOf("enabled" to "false", "message" to "my message"), spam)
    }

    @Test
    fun publishingLeavesThePersonalOut() {
        val a = aura()
        (a.settings[7] as StringSetting).value = "https://discord.com/api/webhooks/secret"
        val up = PublicConfigData.live(listOf(a, spammer()))
        assertEquals(listOf("aura"), up.map { it.id })
        assertEquals(listOf("enabled", "range", "cps", "mode", "color"), up[0].settings.map { it.id })
    }

    @Test
    fun countsChanges() {
        assertEquals(2, PublicConfigData.changes(listOf(e("aura", "enabled" to "true", "cps" to "12", "range" to "3.0")), listOf(aura())))
    }

    @Test
    fun localIds() {
        assertEquals("bedwars-legit", PublicConfigData.localId("Bedwars  Legit!", "Ab3dEf9h", emptySet()) { false })
        assertEquals("bedwars-legit-ab3def9h", PublicConfigData.localId("Bedwars Legit", "Ab3dEf9h", setOf("bedwars-legit")) { false })
        assertEquals("bedwars-legit", PublicConfigData.localId("Bedwars Legit", "Ab3dEf9h", setOf("bedwars-legit")) { true })
        assertEquals("public", PublicConfigData.localId("!!!", "Ab3dEf9h", emptySet()) { false })
    }
}
