package dev.horizon.client.module.basic

import dev.horizon.client.combat.LagBack
import dev.horizon.client.combat.Motion
import dev.horizon.client.combat.Packets
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.mixin.LocalPlayerAccessor
import dev.horizon.client.mixin.deps.ClientboundExplodePacketAccessor
import dev.horizon.client.mixin.deps.ClientboundSetEntityMotionPacketAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.level.block.AirBlock
import net.minecraft.world.phys.Vec3
import java.util.Optional

/**
 * Removes or scales velocity and explosion knockback; OldGrim also drops the next
 * pings, GrimNew answers with a position + STOP_DESTROY_BLOCK pair, not-moving freezes movement packets.
 * `pause-on-flag` is read by nothing.
 */
class AntiKnockBack : Module("anti-knock-back", Categories.BASIC) {
    @Suppress("EnumEntryName")
    enum class Mode { Cancel, Custom, OldGrim, GrimNew }

    val mode = enum("mode", Mode.GrimNew)
    val oldBreak = bool("old-break", false) { mode.value == Mode.GrimNew }
    val pauseInLiquids = bool("pause-in-liquids", false)
    val explosions = bool("explosions", true)
    val pauseOnFlag = bool("pause-on-flag", false)
    val onlyWalls = bool("only-walls", false)
    val onlyGround = bool("only-ground", false) { onlyWalls.value }
    val notMoving = bool("not-moving", false)
    val ignoreRotations = bool("ignore-rotations", false) { notMoving.value }
    val vertical = float("vertical", 0.0f, 0.0f..100.0f) { mode.value == Mode.Custom }
    val horizontal = float("horizontal", 0.0f, 0.0f..100.0f) { mode.value == Mode.Custom }

    /** Every movement packet is dropped after knockback taken standing still (not-moving). */
    @Volatile
    var freeze = false

    /** GrimNew cancelled a knockback; the packet pair goes out at the next PlayerUpdate. */
    @Volatile
    private var grimPending = false

    /** OldGrim, how many more incoming pings to drop. */
    @Volatile
    private var pingSkips = 0

    /** On every rotation queued (even with the module off): ignore-rotations lets the packet go. */
    fun onRotationQueued() {
        if (ignoreRotations.value) freeze = false
    }

    init {
        LagBack.start()

        on<ReceivePacketEvent> { e ->
            val p = mc.player ?: return@on
            if (mc.level == null) return@on
            val pk = e.packet
            if (pk is ClientboundSetEntityMotionPacket && pk.id() == p.id) {
                if (!shouldApply()) return@on
                when (mode.value) {
                    Mode.Custom -> (pk as ClientboundSetEntityMotionPacketAccessor).horizon_setMovement(
                        Vec3(pk.movement().x * horizontal.value / 100.0, pk.movement().y * vertical.value / 100.0,
                            pk.movement().z * horizontal.value / 100.0))
                    Mode.Cancel -> e.cancel()
                    Mode.OldGrim -> {
                        e.cancel()
                        pingSkips = 6
                    }
                    Mode.GrimNew -> if (LagBack.msSince() - 200 > 0) {
                        e.cancel()
                        grimPending = true
                    }
                }
            }
            if (pk is ClientboundExplodePacket && explosions.value && !Modules.get<WindHop>().enabled) {
                if (!shouldApply()) return@on
                val kb = pk.playerKnockback().orElse(null) ?: return@on
                if (kb.x == 0.0 && kb.y == 0.0 && kb.z == 0.0) return@on
                val acc = pk as ClientboundExplodePacketAccessor
                when (mode.value) {
                    Mode.Cancel -> acc.horizon_setPlayerKnockback(Optional.of(Vec3.ZERO))
                    // y and z swapped (on purpose)
                    Mode.Custom -> acc.horizon_setPlayerKnockback(Optional.of(Vec3(kb.x * horizontal.value / 100.0f,
                        kb.z * vertical.value / 100.0f, kb.y * horizontal.value / 100.0f)))
                    Mode.GrimNew -> if (LagBack.msSince() - 200 > 0) {
                        acc.horizon_setPlayerKnockback(Optional.of(Vec3.ZERO))
                        grimPending = true
                    }
                    Mode.OldGrim -> {}
                }
            }
            if (mode.value == Mode.OldGrim && pk is ClientboundPingPacket && pingSkips > 0) {
                e.cancel()
                pingSkips--
            }
            if (pk is ClientboundPlayerPositionPacket) freeze = false
        }

        on<PlayerUpdateEvent>(-10) {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            if ((p.isInWater || p.isUnderWater) && pauseInLiquids.value) return@on
            if (Motion.isMoving()) freeze = false
            if (mode.value == Mode.GrimNew && grimPending) {
                val pos = if (p.isVisuallyCrawling || oldBreak.value) BlockPos.containing(p.position()) else p.blockPosition().above()
                if (level.isEmptyBlock(pos)) {
                    // A 26.3 server kicks for this PosRot when vanilla's sendPosition also sends a position this tick;
                    // servers on older versions accept it.
                    val acc = p as LocalPlayerAccessor
                    Packets.send(ServerboundMovePlayerPacket.PosRot(p.x, p.y, p.z, acc.horizon_yRotLast(), acc.horizon_xRotLast(),
                        p.onGround(), p.horizontalCollision))
                    Packets.sendSequenced { seq ->
                        val at = if (!p.isVisuallyCrawling && !oldBreak.value) p.blockPosition().above() else BlockPos.containing(p.position())
                        ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, at, Direction.DOWN, seq)
                    }
                }
                grimPending = false
            }
            if (pingSkips > 0) pingSkips--
        }

        on<SendPacketEvent> { e ->
            if (!notMoving.value) return@on
            if (!freeze) return@on
            if (e.packet is ServerboundMovePlayerPacket) e.cancel()
        }
    }

    override fun onEnable() {
        pingSkips = 0
    }

    /** Whether this knockback is to be handled (may start the not-moving freeze). */
    private fun shouldApply(): Boolean {
        val p = mc.player ?: return false
        val level = mc.level ?: return false
        if (level.getBlockState(BlockPos.containing(p.position())).block !is AirBlock) {
            if (!onlyGround.value) return true
            if (p.onGround()) return true
        }
        if (notMoving.value && !Motion.isMoving()) {
            freeze = true
            return true
        }
        if ((p.isInWater || p.isUnderWater || p.isInLava) && pauseInLiquids.value) return false
        if (notMoving.value) return false
        return !onlyWalls.value
    }
}
