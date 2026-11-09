package dev.horizon.client.hud.widgets

import dev.horizon.client.gui.Fps
import dev.horizon.client.hud.HudWidget
import dev.horizon.client.hud.ServerInfo
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType

/**
 * "x  y  z" (two spaces). In the Nether the overworld coordinates follow in brackets; with
 * `only-nether` off the other dimension's always do. Grabbing it copies "x y z" to the clipboard.
 */
object CoordWidget : HudWidget("coord-widget") {
    private val secondary = bool("secondary", true)
    private val onlyNether = bool("only-nether", true)

    override fun render() {
        val p = mc.player ?: return
        val inNether = mc.level?.dimension()?.identifier()?.path == "the_nether"
        val f = if (!inNether) 0.125f else 8.0f
        val showOther = secondary.value && (!onlyNether.value || inNether)
        var main = "${p.x.toInt()}  ${p.y.toInt()}  ${p.z.toInt()}"
        var other = "[${(p.x * f).toInt()}  ${(p.z * f).toInt()}]"
        if (p.tickCount < 100) {
            main = "wait " + (100 - p.tickCount) / 20 + " seconds"
            other = ""
        }
        simple(main + if (showOther) " $other" else "")
    }

    override fun grabbed() {
        val p = mc.player ?: return
        mc.keyboardHandler.clipboard = "${p.x.toInt()} ${p.y.toInt()} ${p.z.toInt()}"
    }
}

/** The frame rate, sampled every 4 ticks, labelled "v-sync" or "fps". */
object FpsWidget : HudWidget("fps-widget") {
    private var shown = 0

    override fun tick() {
        if ((mc.player?.tickCount ?: return) % 4 == 0) shown = Fps.value
    }

    override fun render() = simple("$shown " + if (mc.options.enableVsync().get()) "v-sync" else "fps")
}

/** The local player's latency. */
object PingWidget : HudWidget("ping-widget") {
    override fun render() = simple("${ServerInfo.ping} ms")
}

/** "20.0 (19.98) t/s", the average reading "--.--" for the first 20 time packets. */
object TpsWidget : HudWidget("tps-widget") {
    override fun render() {
        val avg = if (ServerInfo.warmingUp()) "--.--" else ServerInfo.averageTps().toString()
        simple(ServerInfo.tps().toString() + " (" + avg + ") t/s")
    }
}

/** km/h, or blocks/s with `bps`; 3-D speed while gliding. `mph` shows miles/h. */
object SpeedWidget : HudWidget("speed-widget") {
    private val bps = bool("bps", false)
    private val mph = bool("mph", true)

    /** The tick-speed multiplier (1 without a Timer module). */
    private const val TIMER = 1f

    override fun render() {
        val p = mc.player ?: return
        val perTick = if (p.isFallFlying) ServerInfo.speed else ServerInfo.horizontalSpeed
        val text = if (bps.value) {
            String.format("%.1f", Math.rint((perTick * 200f * TIMER).toDouble()).toFloat() * 0.1) + " b/s"
        } else if (mph.value) {
            // blocks/tick × 20 = m/s; × 2.23694 = mph.
            String.format("%.1f", Math.rint((perTick * 447.388f * TIMER).toDouble()).toFloat() * 0.1) + " mph"
        } else {
            String.format("%.1f", Math.rint((perTick * 720f * TIMER).toDouble()).toFloat() * 0.1) + " km/h"
        }
        simple(text)
    }
}

/** Single and double chests in the loaded chunks around the player. */
object ChestCounterWidget : HudWidget("chest-counter-widget") {
    override fun render() {
        var single = 0
        var double = 0
        try {
            val level = mc.level
            if (level != null) {
                for (be in ServerInfo.blockEntities.filterIsInstance<ChestBlockEntity>()) {
                    if (level.getBlockState(be.blockPos).getValue(ChestBlock.TYPE) == ChestType.SINGLE) single++ else double++
                }
            }
        } catch (_: Exception) {
        }
        simple("chests: S:$single D:${double / 2}")
    }
}

/**
 * AutoCrystal's crystals per second, place-to-spawn ping, calculation time and damage efficiency.
 * This client has no AutoCrystal, so it reads as with AutoCrystal off.
 */
object CrystalSpeedWidget : HudWidget("crystal-speed-widget") {
    override fun render() {
        val dmg = 0f
        val self = 0f
        val ef = (dmg / self * 10).toInt() / 10.0f
        simple("0 c/s | 0 ping | 0 cpu | $ef ef.")
    }
}
