package dev.horizon.client.module.esp

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Rng
import dev.horizon.client.event.LightMapUpdateEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.XRayBlockEvent
import dev.horizon.client.event.XRayState
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.core.BlockPos
import net.minecraft.tags.ItemTags
import net.minecraft.world.phys.AABB

/**
 * Chunks are rebuilt showing only the chosen blocks, full bright and without cave culling
 * (the esp2 mixins read [XRayState]). The deobfuscator pokes every block around the player (start + abort mining, one
 * every `delay` ms, in random order) so an anti-x-ray server reveals what is really there; the player stands still
 * meanwhile.
 */
class XRay : Module("x-ray", Categories.ESP) {
    val blocksSetting = blocks("blocks", blockList("block.minecraft.diamond_ore", "block.minecraft.deepslate_diamond_ore", "block.minecraft.ancient_debris"))
    val deobfuscator = bool("deobfuscator", false)
    val fast = bool("fast", false) { deobfuscator.value }
    val delay = int("delay", 25, 1..100) { deobfuscator.value }
    val radius = int("radius", 5, 1..64) { deobfuscator.value }
    val radius2 = int("radius", 5, 1..32) { deobfuscator.value }
    val down = int("down", 5, 1..32) { deobfuscator.value }

    private val timer = MsTimer()

    /** Blocks the deobfuscator still has to poke, the last one poked, and the area it covers. */
    private val queue = ArrayList<BlockPos>()
    private var current: BlockPos? = null
    private var area = AABB(BlockPos.ZERO)

    /** The deobfuscator setting at the last player update. */
    private var wasDeobfuscating = false

    init {
        on<PlayerUpdateEvent> {
            XRayState.active = true
            if (deobfuscator.value && !wasDeobfuscating) {
                queue.clear()
                queue.addAll(positions())
                area = area()
            }
            wasDeobfuscating = deobfuscator.value
        }
        on<XRayBlockEvent> { e -> if (e.block in blocksSetting.value) e.cancel() }
        on<LightMapUpdateEvent> { it.cancel() }
        on<Render3DEvent> { deobfuscate() }
    }

    override fun onEnable() {
        if (mc.player == null || mc.level == null) return
        queue.clear()
        queue.addAll(positions())
        XRayState.active = true
        mc.levelExtractor.allChanged()
    }

    override fun onDisable() {
        // Turning it off outside a world leaves the render hooks on (on purpose).
        if (mc.player == null || mc.level == null) return
        mc.levelExtractor.allChanged()
        XRayState.active = false
    }

    private fun deobfuscate() {
        if (!deobfuscator.value) return
        val p = mc.player ?: return
        val cur = current
        if (cur != null && queue.isNotEmpty()) World3D.outline(AABB(cur), Theme.gradient(0))
        World3D.outline(area, Theme.gradient(0))
        if (queue.isEmpty()) return
        if (p.mainHandItem.`is`(ItemTags.PICKAXES)) Inventory.selectHotbar(p.inventory.selectedSlot % 8 + 1)
        if (!timer.passedAndReset(delay.value.toLong())) return
        val i = if (queue.size - 1 <= 1) 0 else Rng.int(0, queue.size - 1)
        val pos = queue.removeAt(i)
        current = pos
        val gm = mc.gameMode ?: return
        gm.startDestroyBlock(pos, p.direction)
        gm.stopDestroyBlock()
        // No swing packet: 26.3 has none.
    }

    /** The deobfuscator's area around the player. */
    private fun area(): AABB {
        val p = mc.player!!
        val r = radius.value.toDouble()
        return AABB(p.x - r, p.y - down.value, p.z - r, p.x + r, p.y + radius2.value, p.z + r)
    }

    /** Every non-air block in the area (x, then y, then z); with "fast" only those odd on all three axes. */
    private fun positions(): List<BlockPos> {
        val level = mc.level ?: return emptyList()
        val c = BlockPos.containing(mc.player!!.position())
        val list = ArrayList<BlockPos>()
        for (x in c.x - radius.value..c.x + radius.value) {
            for (y in c.y - down.value..c.y + radius2.value) {
                for (z in c.z - radius.value..c.z + radius.value) {
                    val pos = BlockPos(x, y, z)
                    if (level.isEmptyBlock(pos)) continue
                    if (!fast.value || (x % 2 != 0 && y % 2 != 0 && z % 2 != 0)) list += pos
                }
            }
        }
        return list
    }

    companion object {
        /** x-ray's MoveEvent handler: no horizontal movement while the deobfuscator has blocks left. */
        @JvmStatic
        fun freezesMovement(): Boolean {
            val x = Modules.get<XRay>()
            return x.enabled && x.deobfuscator.value && x.queue.isNotEmpty()
        }
    }
}
