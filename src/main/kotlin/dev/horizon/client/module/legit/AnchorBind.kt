package dev.horizon.client.module.legit

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.Scheduler
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.combat.bindHeld
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.setting.Bind
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult

/**
 * While the bind is held, every `delay * 5 + 10` ticks (2 with delay 0) it right-clicks
 * a chain of hotbar slots: anchor, glowstone, detonate — or glowstone, detonate when looking at an anchor. Steps are
 * `delay − 2` (± 1) ticks apart, or one frame apart with delay 0.
 */
class AnchorBind : Module("anchor-bind", Categories.LEGIT) {
    val delay = int("delay", 1, 0..5)
    val randomizeDelay = bool("randomize-delay", false)
    val anchorBind = bind("anchor-bind", Bind())

    /** The repeat gate. */
    private val gate = TickTimer()

    init {
        on<PlayerUpdateEvent> {
            if (!bindHeld(anchorBind.value)) return@on
            val d = delay.value
            if (!gate.passedAndReset(if (d > 0) d * 5 + 10 else 2)) return@on
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            val glow = Inventory.hotbarFind(Items.GLOWSTONE)
            val holdingGlow = p.inventory.getItem(p.inventory.selectedSlot).`is`(Items.GLOWSTONE)
            val anchor = Inventory.hotbarFind(Items.RESPAWN_ANCHOR)
            val sel = p.inventory.selectedSlot
            val hit = mc.hitResult
            // A MISS result is a BlockHitResult too; its position gets checked all the same.
            val lookingAtAnchor = hit is BlockHitResult && level.getBlockState(hit.blockPos).`is`(Blocks.RESPAWN_ANCHOR)
            if (glow == null || anchor == null) return@on
            if (lookingAtAnchor) {
                chain(intArrayOf(glow.slot, sel))
                return@on
            }
            chain(intArrayOf(anchor.slot, glow.slot, if (holdingGlow) anchor.slot else sel))
        }
    }

    /** Select → right-click → select → … one scheduled step at a time. */
    private fun chain(slots: IntArray) {
        var f: () -> Unit = {}
        for (s in slots.reversed()) {
            val next = f
            f = { select(s, next) }
        }
        f()
    }

    private fun select(slot: Int, next: () -> Unit) {
        Inventory.selectHotbar(slot)
        schedule { use(next) }
    }

    private fun use(next: () -> Unit) {
        (mc as MinecraftAccessor).horizon_startUseItem()
        schedule(next)
    }

    /** `delay − 2` (± 1), counted in −10 tick passes, or in ms on the frame pass with delay 0 (re-read each step). */
    private fun schedule(task: () -> Unit) {
        val n = delay.value - 2 + if (randomizeDelay.value) Rng.int(-1, 1) else 0
        Scheduler.schedule(n, -10, delay.value > 0, task)
    }
}
