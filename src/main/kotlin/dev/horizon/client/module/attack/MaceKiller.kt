package dev.horizon.client.module.attack

import dev.horizon.client.combat.Explosions
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.attackTarget
import dev.horizon.client.event.AfterSendPacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.ceil

/**
 * On `ServerboundAttackPacket` against a non-crystal, non-blocking target when a mace
 * is in the hotbar, selects the mace and sends upward + downward `Pos(onGround=false)` packets up to the highest
 * unobstructed point within [height] blocks.
 */
class MaceKiller : Module("mace-killer", Categories.ATTACK) {
    val height = int("height", 20, 0..200)

    private var prevSlot = -1

    init {
        on<SendPacketEvent> { e ->
            val pk = e.packet as? ServerboundAttackPacket ?: return@on
            val pl = mc.player ?: return@on
            val target = attackTarget(pk) ?: return@on
            if (target is EndCrystal) return@on
            if (target is Player && target.isBlocking) return@on
            val mace = Inventory.hotbarFind(Items.MACE) ?: return@on
            if (pl.inventory.selectedSlot != mace.slot) {
                if (mace.slot < 9) Inventory.selectHotbar(mace.slot)
                // selectedSlot is read after the mace slot has already been selected (on purpose).
                prevSlot = pl.inventory.selectedSlot
            }
            val topPos = findTopPos()
            val dist = pl.position().distanceTo(topPos)
            if (dist > 10.0) {
                val count = ceil(abs(dist / 10.0)).toInt()
                repeat(count) {
                    Packets.send(ServerboundMovePlayerPacket.StatusOnly(false, pl.horizontalCollision))
                }
            }
            Packets.send(ServerboundMovePlayerPacket.Pos(topPos.x, topPos.y, topPos.z, false, pl.horizontalCollision))
            Packets.send(ServerboundMovePlayerPacket.Pos(pl.x, pl.y, pl.z, false, pl.horizontalCollision))
        }

        on<AfterSendPacketEvent> { e ->
            if (e.packet !is ServerboundAttackPacket) return@on
            if (prevSlot == -1) return@on
            Inventory.selectHotbar(prevSlot)
            prevSlot = -1
        }
    }

    /** Highest unobstructed step (`1.8` blocks per step, `i in 2..height/2`). */
    private fun findTopPos(): Vec3 {
        val pl = mc.player ?: return Vec3.ZERO
        var best = pl.position()
        val maxSteps = height.value / 2
        if (2 <= maxSteps) {
            for (i in 2..maxSteps) {
                val from = pl.position()
                val to = from.add(0.0, i * 1.8, 0.0)
                if (Explosions.raycast(from, to, false, emptyList(), emptyList()) == null) {
                    best = from.add(0.0, (i - 1) * 1.8, 0.0)
                } else {
                    break
                }
            }
        }
        return best
    }
}
