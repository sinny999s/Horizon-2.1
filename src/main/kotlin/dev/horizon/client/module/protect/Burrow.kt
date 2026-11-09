package dev.horizon.client.module.protect

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Tells the server we jumped (four fake rising positions, looking down), places obsidian (or
 * an ender chest) in our own feet block, and reports standing on top of it. Note: a 26.3 server kicks for more than one
 * position packet in a tick; servers on older versions accept the sequence.
 */
class Burrow : Module("burrow", Categories.PROTECT) {
    val once = bool("once", true)

    /** The feet block when enabled (repeat mode stops once we leave it). */
    private var start: BlockPos? = null
    private val cooldown = MsTimer()

    init {
        on<PlayerUpdateEvent> { tick() }
    }

    override fun onEnable() {
        val p = mc.player ?: return
        start = BlockPos.containing(p.position())
    }

    private fun tick() {
        val p = mc.player ?: return
        val feet = BlockPos.containing(p.position())
        if (!once.value && feet != start) {
            disable()
            return
        }
        val slot = Inventory.hotbarFind(Items.OBSIDIAN)?.slot ?: Inventory.hotbarFind(Items.ENDER_CHEST)?.slot ?: -1
        if (slot == -1 || mc.isLocalServer) {
            disable()
            return
        }
        if (!cooldown.passed(1000)) return
        val pitch = p.xRot
        val d = 90f - pitch
        fakeY(0.42, pitch + d * 0.25f)
        fakeY(0.75, pitch + d * 0.5f)
        fakeY(1.01, pitch + d * 0.75f)
        fakeY(1.16, pitch + d)
        Inventory.saveAndSelect(slot)
        val hit = BlockHitResult(Vec3.atCenterOf(feet).add(0.0, -0.5, 0.0), Direction.UP, feet.below(), false)
        Packets.sendSequenced { seq -> ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, seq) }
        // 26.3 has no swing packet to send here.
        Inventory.restore()
        Packets.send(ServerboundMovePlayerPacket.PosRot(p.x, p.y + 1.0f, p.z, p.yRot, 90.0f, true, p.horizontalCollision))
        cooldown.reset()
        if (once.value) disable()
    }

    /** A rising position packet, not on the ground. */
    private fun fakeY(dy: Double, pitch: Float) {
        val p = mc.player ?: return
        Packets.send(ServerboundMovePlayerPacket.PosRot(p.x, p.y + dy, p.z, p.yRot, pitch, false, p.horizontalCollision))
    }
}
