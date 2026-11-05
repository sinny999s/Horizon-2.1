package dev.horizon.client.module.legit

import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.Aura

/**
 * Not in the original: one switch for playing on a server that watches (Hypixel). While it's on, the blatant modules
 * (all of Rage and Attack but Aura and Legacy Clicker, and the packet / silent-rotation ones elsewhere, [BLATANT]) are
 * turned off and can't be turned on, and Aura is held to what a player could do: its LEGIT rotations (the real camera,
 * turned like a mouse), no hits through walls, no back-track and vanilla's 3 block reach. Turning it off turns back on
 * what it turned off and gives Aura its own settings back. Its on/off is saved with the config, and the sidebar's
 * config menu has a switch for it.
 */
object GhostMode : Module("ghost-mode", Categories.LEGIT, listOf("ghost", "legit-mode")) {
    /** Blatant modules outside Rage and Attack. */
    private val BLATANT = setOf(
        "anti-knock-back", "no-push", "gui-move", "infinite-elytra", "hole-anchor",
        "air-place", "anti-aim", "portal-god-mode", "no-server-rotate", "trace-through", "speed-mine", "chorus-delay",
        "box-shrinker", "anti-attack",
        "ping-spoof", "fast-latency",
        "burrow", "surround", "self-trap", "blocker",
        "ec-exploit", "x-carry", "no-server-slot",
        "nuker", "chest-aura",
    )
    /** Attack modules that are fine: Aura is held to its legit settings, Legacy Clicker only clicks. */
    private val ALLOWED = setOf("aura", "legacy-clicker")

    /** What turning it on turned off, to turn back on. */
    private val suspended = LinkedHashSet<Module>()
    /** Aura's own settings from before, to give back. */
    private var auraBefore: AuraSettings? = null

    private class AuraSettings(val mode: Aura.RotationMode, val range: Float, val walls: Boolean, val backTrack: Boolean)

    fun isBlatant(m: Module): Boolean = m !== this && m.id !in ALLOWED &&
        (m.subCategory === Categories.RAGE || m.subCategory === Categories.ATTACK || m.id in BLATANT)

    /** Whether [m] may not be turned on now. */
    fun blocks(m: Module) = enabled && isBlatant(m)

    override fun onEnable() {
        suspended.clear()
        for (m in Modules.all) if (m.enabled && isBlatant(m)) {
            suspended += m
            m.disable()
        }
        snapshotAura()
        holdAura()
    }

    override fun onDisable() {
        val back = suspended.toList()
        suspended.clear()
        for (m in back) m.enable()
        auraBefore?.let { b ->
            val a = aura()
            a.rotationMode.value = b.mode
            a.range.value = b.range
            a.ignoreWalls.value = b.walls
            a.backTrack.value = b.backTrack
        }
        auraBefore = null
    }

    /** A config was just applied: its own Aura settings are what turning ghost mode off gives back. */
    fun configApplied() {
        suspended.clear()
        auraBefore = null
        if (enabled) snapshotAura()
    }

    /** After a config loads with ghost mode on: its blatant modules go off without a word, and Aura is held. */
    fun enforce() {
        if (!enabled) return
        for (m in Modules.all) if (m.enabled && isBlatant(m)) {
            m.enabledSetting.value = false
            m.unplug()
            m.onDisable()
        }
        holdAura()
    }

    private fun snapshotAura() {
        if (auraBefore != null) return
        val a = aura()
        auraBefore = AuraSettings(a.rotationMode.value, a.range.value, a.ignoreWalls.value, a.backTrack.value)
    }

    init {
        // A setting changed in the GUI while it's on goes straight back.
        on<TickEvent> { holdAura() }
    }

    private fun holdAura() {
        val a = aura()
        if (a.rotationMode.value != Aura.RotationMode.LEGIT) a.rotationMode.value = Aura.RotationMode.LEGIT
        if (a.range.value > 3.0f) a.range.value = 3.0f
        if (a.ignoreWalls.value) a.ignoreWalls.value = false
        if (a.backTrack.value) a.backTrack.value = false
    }

    private fun aura() = Modules.get<Aura>()
}
