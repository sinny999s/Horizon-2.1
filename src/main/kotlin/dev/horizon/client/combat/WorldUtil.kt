package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Modules
import dev.horizon.client.module.Switch
import dev.horizon.client.module.basic.GuiMove
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.client.Minecraft
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/**
 * Switch-mode helpers ([Switch]): find an item the way a `switch` setting says, and run an action with it in hand.
 *
 * Also raises gui-move's busy flag around the action.
 */
object SwitchUtil {
    private val mc get() = Minecraft.getInstance()

    /**
     * NORMAL/SILENT: hotbar only (index 0..8); INVENTORY_*: index 36 down to 0, as a menu slot id; *_FULL: the hotbar
     * first, then the whole inventory. -1 when nothing matches.
     */
    fun find(mode: Switch, pred: (ItemStack) -> Boolean): Int = when (mode) {
        Switch.NORMAL, Switch.SILENT -> Inventory.hotbarFind(pred)?.slot ?: -1
        Switch.INVENTORY_NORMAL, Switch.INVENTORY_SILENT -> Inventory.find(pred)?.slot ?: -1
        Switch.NORMAL_FULL, Switch.SILENT_FULL -> (Inventory.hotbarFind(pred) ?: Inventory.find(pred))?.slot ?: -1
    }

    fun run(slot: Int, mode: Switch, action: () -> Unit) {
        val p = mc.player ?: return
        val guiMove = Modules.get<GuiMove>()
        guiMove.internalClick = true
        when (mode) {
            Switch.NORMAL -> {
                if (p.inventory.selectedSlot != slot) Inventory.selectHotbar(slot)
                action()
            }
            Switch.SILENT -> {
                val prev = p.inventory.selectedSlot
                if (Inventory.serverSlot != slot) Inventory.selectHotbar(slot)
                action()
                if (Inventory.serverSlot != prev) Inventory.selectHotbar(prev)
            }
            Switch.INVENTORY_NORMAL -> {
                swapSelected(slot)
                action()
            }
            Switch.INVENTORY_SILENT -> {
                swapSelected(slot)
                action()
                swapSelected(slot)
            }
            Switch.NORMAL_FULL -> run(slot, if (slot >= 9) Switch.INVENTORY_NORMAL else Switch.NORMAL, action)
            Switch.SILENT_FULL -> run(slot, if (slot >= 9) Switch.INVENTORY_SILENT else Switch.SILENT, action)
        }
        guiMove.internalClick = false
    }

    /** False when nothing matches. */
    fun run(mode: Switch, pred: (ItemStack) -> Boolean, action: () -> Unit): Boolean {
        val slot = find(mode, pred)
        if (slot == -1) return false
        run(slot, mode, action)
        return true
    }

    fun run(item: Item, mode: Switch, action: () -> Unit): Boolean = run(mode, { it.`is`(item) }, action)

    /** A number-key swap of menu slot [slot] with the selected hotbar slot, no close packet. */
    private fun swapSelected(slot: Int) {
        val p = mc.player ?: return
        mc.gameMode?.handleContainerInput(p.containerMenu.containerId, slot, p.inventory.selectedSlot, ContainerInput.SWAP, p)
    }
}

/**
 * The bits the farming modules (auto-bone-meal, auto-land, soil-ripper) share: the reach cube, the closest-first sort
 * and the "rotate, switch, click the top face" action.
 */
object FarmUtil {
    private val mc get() = Minecraft.getInstance()

    /**
     * Every position in the cube of ±ceil(block reach) around the block holding the eyes, x outermost,
     * then y, then z.
     */
    fun reachCube(): List<BlockPos> {
        val p = mc.player ?: return emptyList()
        val c = BlockPos.containing(p.eyePosition)
        val r = Math.ceil(p.blockInteractionRange()).toInt()
        val out = ArrayList<BlockPos>()
        for (x in c.x - r..c.x + r) for (y in c.y - r..c.y + r) for (z in c.z - r..c.z + r) out += BlockPos(x, y, z)
        return out
    }

    /**
     * The positions passing [pred], each as a click on its top face at its centre raised by [lift], closest to the
     * eyes first (a stable sort, as Kotlin's `sortedWith`).
     */
    fun topHits(lift: Double, pred: (BlockPos) -> Boolean): List<BlockHitResult> {
        val p = mc.player ?: return emptyList()
        return reachCube().filter(pred)
            .map { BlockHitResult(Vec3.atCenterOf(it).add(0.0, lift, 0.0), Direction.UP, it, false) }
            .sortedBy { p.eyePosition.distanceToSqr(it.location) }
    }

    /** Whether the top-face click point of [pos] raised by [lift] is closer to the eyes than the block reach. */
    fun inReach(pos: BlockPos, lift: Double): Boolean {
        val p = mc.player ?: return false
        return p.eyePosition.distanceTo(Vec3.atCenterOf(pos).add(0.0, lift, 0.0)) < p.blockInteractionRange()
    }

    /**
     * The farming modules' last step: find the item per [mode]; with a target and the item, queue an instant rotation
     * request at priority 0 towards the click point that, when it wins, switches per [mode] and uses the item on the
     * block (`useItemOn` + a local swing; 26.3 has no swing packet).
     */
    fun useOn(hits: List<BlockHitResult>, mode: Switch, item: (ItemStack) -> Boolean): Boolean {
        val slot = SwitchUtil.find(mode, item)
        if (hits.isEmpty() || slot == -1) return false
        val hit = hits.first()
        RotationManager.queue(InstantRequest(Rotation.lookAt(hit.location), 0) {
            SwitchUtil.run(slot, mode) {
                val p = mc.player ?: return@run
                mc.gameMode?.useItemOn(p, InteractionHand.MAIN_HAND, hit)
                p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
            }
        })
        return true
    }
}

