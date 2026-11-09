package dev.horizon.client.module.legit

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.Scheduler
import dev.horizon.client.combat.bindHeld
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PreEntitySpawnEvent
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.setting.Bind
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3

/**
 * While the bind is held it hits the crystal under the crosshair, or places obsidian
 * (unless looking at obsidian/bedrock) and a crystal with `delay` ms frame steps, then hits that crystal the moment it
 * spawns within 2 blocks of where the placing click aimed.
 */
class CrystalBind : Module("crystal-bind", Categories.LEGIT) {
    val delay = int("delay", 0, 0..250)
    val crystalBind = bind("crystal-bind", Bind())
    val changePitch = bool("change-pitch", false)
    val optimizer = bool("optimizer", false)
    val switchBack = bool("switch-back", false)

    /** Where the last crystal-placing click aimed; ZERO = nothing pending. Survives failed placements. */
    private var aimed: Vec3 = Vec3.ZERO

    /** The repeat gate. */
    private val gate = MsTimer()

    init {
        on<PlayerUpdateEvent> {
            val hit = mc.hitResult
            val level = mc.level ?: return@on
            val lookCrystal = hit is EntityHitResult && hit.entity is EndCrystal
            val lookBase = hit is BlockHitResult && level.getBlockState(hit.blockPos).let { it.`is`(Blocks.OBSIDIAN) || it.`is`(Blocks.BEDROCK) }
            if (!bindHeld(crystalBind.value)) return@on
            val mult = if (lookCrystal) 1 else if (lookBase) 2 else 4
            if (!gate.passedAndReset(delay.value.toLong() * mult)) return@on
            val crystal = Inventory.hotbarFind(Items.END_CRYSTAL)
            val obs = Inventory.hotbarFind(Items.OBSIDIAN)
            if (obs == null && !lookBase && !lookCrystal) return@on
            if (crystal == null) return@on
            val p = mc.player ?: return@on
            if (lookCrystal) {
                mc.gameMode?.attack(p, (hit as EntityHitResult).entity)
                p.swing(InteractionHand.MAIN_HAND, p.mainHandItem.attackAnimation, false)
                return@on
            }
            val orig = p.inventory.selectedSlot
            if (!lookBase) {
                // obs is non-null here: without obsidian and not looking at a base it returned above.
                if (p.inventory.selectedSlot != obs!!.slot) Inventory.selectHotbar(obs.slot)
                (mc as MinecraftAccessor).horizon_startUseItem()
                schedule { selectThenPlace(crystal.slot, orig) }
                return@on
            }
            Inventory.selectHotbar(crystal.slot)
            schedule { placeCrystal(orig) }
        }
        on<PreEntitySpawnEvent> { e -> onSpawn(e.entity) }
    }

    /** A frame task `delay` ms from now (delay re-read each time). */
    private fun schedule(task: () -> Unit) = Scheduler.ms(delay.value, task)

    private fun selectThenPlace(crystal: Int, orig: Int) {
        Inventory.selectHotbar(crystal)
        schedule { placeCrystal(orig) }
    }

    private fun placeCrystal(orig: Int) {
        (mc as MinecraftAccessor).horizon_startUseItem()
        aimed = mc.hitResult?.location ?: Vec3.ZERO
        if (switchBack.value) schedule { Inventory.selectHotbar(orig) }
    }

    /**
     * Runs at `ClientLevel.addEntity` HEAD. The crystal isn't in the level yet there, so `optimizer`'s
     * removal finds nothing and does nothing (on purpose).
     */
    private fun onSpawn(e: Entity) {
        if (e !is EndCrystal) return
        if (aimed == Vec3.ZERO) return
        if (e.distanceToSqr(aimed) >= 4.0) return
        aimed = Vec3.ZERO
        val p = mc.player ?: return
        if (changePitch.value) p.xRot = Rotation.lookAt(e.position().add(0.0, 0.15, 0.0)).gcdFix().pitch
        mc.gameMode?.attack(p, e)
        p.swing(InteractionHand.MAIN_HAND, p.mainHandItem.attackAnimation, false)
        if (optimizer.value) mc.level?.removeEntity(e.id, Entity.RemovalReason.KILLED)
    }
}
