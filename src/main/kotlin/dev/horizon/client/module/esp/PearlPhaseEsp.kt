package dev.horizon.client.module.esp

import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Colors
import dev.horizon.client.render.World3D
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import java.awt.Color

/**
 * Standing on the ground with free space at the feet, the bottom face of every block
 * collision at feet + 0.21 within [range] of the player's box is outlined and filled — [unsafe] when the block under that
 * block can be replaced (a phase would drop through), else [safe].
 */
class PearlPhaseEsp : Module("pearl-phase-esp", Categories.ESP) {
    val range = float("range", 1.0f, 0.01f..3.0f)
    val safe = color("safe", Color(0x3CFFFFFF.toInt(), true))
    val unsafe = color("unsafe", Color(0x3CFF6464.toInt(), true))

    init {
        on<Render3DEvent> {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (!p.onGround()) return@on
            if (!level.getBlockState(BlockPos.containing(p.position().add(0.0, 0.21, 0.0))).canBeReplaced()) return@on
            for ((box, color) in faces()) {
                World3D.fill(box, color)
                World3D.outline(box, Colors.withAlpha(color, 1f))
            }
        }
    }

    /** Each colliding shape's bottom face (a flat box) and its colour, in collision order. */
    private fun faces(): Map<AABB, Int> {
        val p = mc.player!!
        val level = mc.level!!
        val r = range.value.toDouble()
        val out = LinkedHashMap<AABB, Int>()
        val probe = p.boundingBox.inflate(r, 0.0, r).setMinY(p.y + 0.21).setMaxY(p.y + 0.21)
        for (shape in level.getBlockCollisions(p, probe)) {
            val b = shape.bounds()
            val below = BlockPos.containing(b.center).below()
            out[b.setMaxY(b.minY)] = (if (level.getBlockState(below).canBeReplaced()) unsafe.value else safe.value).rgb
        }
        return out
    }
}
