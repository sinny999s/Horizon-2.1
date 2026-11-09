package dev.horizon.client.module.attack

import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.attackTarget
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.mixin.LocalPlayerAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.entity.boss.enderdragon.EndCrystal

/**
 * Before every attack packet (on anything but a crystal), position packets that tell the
 * server we're a hair above the ground and falling. Note: they go out in the same tick as the movement packet, and a
 * 26.3 server kicks for a second position packet in one tick; servers on older versions accept them.
 */
class Criticals : Module("criticals", Categories.ATTACK) {
    enum class Mode { NCP, CUSTOM }
    enum class NcpMode { NORMAL, STRICT, OLD, NEW }

    val mode = enum("mode", Mode.NCP)
    val ncpMode = enum("ncp-mode", NcpMode.NEW) { mode.value == Mode.NCP }
    val packet1 = group("packet-1") { mode.value == Mode.CUSTOM }
    val sendPacket1 = bool("send-packet-1", true, parent = packet1.value) { mode.value == Mode.CUSTOM }
    val ground1 = bool("ground-1", true, parent = packet1.value) { mode.value == Mode.CUSTOM }
    val full1 = bool("full-1", true, parent = packet1.value) { mode.value == Mode.CUSTOM }
    val offset1 = float("offset-1", 0.0f, 0.0f..1.5f, parent = packet1.value) { mode.value == Mode.CUSTOM }
    val packet2 = group("packet-2") { mode.value == Mode.CUSTOM }
    val sendPacket2 = bool("send-packet-2", true, parent = packet2.value) { mode.value == Mode.CUSTOM }
    val ground2 = bool("ground-2", false, parent = packet2.value) { mode.value == Mode.CUSTOM }
    val full2 = bool("full-2", true, parent = packet2.value) { mode.value == Mode.CUSTOM }
    val offset2 = float("offset-2", 0.0625f, 0.0f..1.5f, parent = packet2.value) { mode.value == Mode.CUSTOM }
    val packet3 = group("packet-3") { mode.value == Mode.CUSTOM }
    val sendPacket3 = bool("send-packet-3", true, parent = packet3.value) { mode.value == Mode.CUSTOM }
    val ground3 = bool("ground-3", false, parent = packet3.value) { mode.value == Mode.CUSTOM }
    val full3 = bool("full-3", true, parent = packet3.value) { mode.value == Mode.CUSTOM }
    val offset3 = float("offset-3", 0.04535f, 0.0f..1.5f, parent = packet3.value) { mode.value == Mode.CUSTOM }
    val packet4 = group("packet-4") { mode.value == Mode.CUSTOM }
    val sendPacket4 = bool("send-packet-4", false, parent = packet4.value) { mode.value == Mode.CUSTOM }
    val ground4 = bool("ground-4", false, parent = packet4.value) { mode.value == Mode.CUSTOM }
    val full4 = bool("full-4", false, parent = packet4.value) { mode.value == Mode.CUSTOM }
    val offset4 = float("offset-4", 0.0f, 0.0f..1.5f, parent = packet4.value) { mode.value == Mode.CUSTOM }
    val packet5 = group("packet-5") { mode.value == Mode.CUSTOM }
    val sendPacket5 = bool("send-packet-5", false, parent = packet5.value) { mode.value == Mode.CUSTOM }
    val ground5 = bool("ground-5", false, parent = packet5.value) { mode.value == Mode.CUSTOM }
    val full5 = bool("full-5", false, parent = packet5.value) { mode.value == Mode.CUSTOM }
    val offset5 = float("offset-5", 0.0f, 0.0f..1.5f, parent = packet5.value) { mode.value == Mode.CUSTOM }

    init {
        on<SendPacketEvent> { e ->
            val p = e.packet as? ServerboundAttackPacket ?: return@on
            val target = attackTarget(p) ?: return@on
            if (target is EndCrystal) return@on
            tryCrit()
        }
    }

    /** On the ground (or creative-flying), not in lava or under water. */
    private fun tryCrit() {
        val pl = mc.player ?: return
        if (!pl.onGround() && !pl.abilities.flying) return
        if (pl.isInLava) return
        if (pl.isUnderWater) return
        when (mode.value) {
            Mode.NCP -> ncp()
            Mode.CUSTOM -> custom()
        }
    }

    private fun ncp() {
        when (ncpMode.value) {
            NcpMode.NORMAL -> {
                send(0.0625, false, false)
                send(0.0, false, false)
            }
            NcpMode.STRICT -> {
                send(0.062600301692775, false, false)
                send(0.07260029960661, false, false)
                send(0.0, false, false)
                send(0.0, false, false)
            }
            NcpMode.OLD -> {
                send(1.058293536E-5, false, false)
                send(9.16580235E-6, false, false)
                send(1.0371854E-7, false, false)
            }
            NcpMode.NEW -> {
                send(2.71875E-7, false, false)
                send(0.0, false, false)
            }
        }
    }

    /** The enabled custom packets, 1 to 5. */
    private fun custom() {
        if (sendPacket1.value) send(offset1.value.toDouble(), full1.value, ground1.value)
        if (sendPacket2.value) send(offset2.value.toDouble(), full2.value, ground2.value)
        if (sendPacket3.value) send(offset3.value.toDouble(), full3.value, ground3.value)
        if (sendPacket4.value) send(offset4.value.toDouble(), full4.value, ground4.value)
        if (sendPacket5.value) send(offset5.value.toDouble(), full5.value, ground5.value)
    }

    /** Our position raised by [offset]; [full] adds the last rotation sent. The last-sent fields stay. */
    private fun send(offset: Double, full: Boolean, ground: Boolean) {
        val pl = mc.player ?: return
        val acc = pl as LocalPlayerAccessor
        Packets.send(
            if (full) ServerboundMovePlayerPacket.PosRot(pl.x, pl.y + offset, pl.z, acc.horizon_yRotLast(), acc.horizon_xRotLast(), ground, pl.horizontalCollision)
            else ServerboundMovePlayerPacket.Pos(pl.x, pl.y + offset, pl.z, ground, pl.horizontalCollision)
        )
    }
}
