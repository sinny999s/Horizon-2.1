package dev.horizon.client.module.esp

import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.World3D
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color

/** Marks the holes in the bedrock floor (bottom-layer blocks that aren't bedrock). */
class VoidEsp : Module("void-esp", Categories.ESP) {
    enum class Mode { FLAT, BOX, WIRE_FRAME, CROSS }

    val mode = enum("mode", Mode.CROSS)
    val colorSetting = color("color", Color(0xFFFFFFFF.toInt(), true))
    val range = int("range", 10, 1..64)

    private var holes: List<BlockPos> = ArrayList()

    init {
        on<PlayerUpdateEvent> { holes = scan() }
        on<Render3DEvent> {
            val c = colorSetting.value.rgb
            val solid = PlaceBoxes.withAlpha(c, 1f)
            for (pos in holes) {
                val box = AABB(pos)
                when (mode.value) {
                    Mode.FLAT -> {
                        val flat = box.setMaxY(pos.y.toDouble())
                        World3D.fill(flat, c)
                        World3D.outline(flat, solid)
                    }
                    Mode.BOX -> {
                        World3D.fill(box, c)
                        World3D.outline(box, solid)
                    }
                    Mode.WIRE_FRAME -> World3D.outline(box, solid)
                    Mode.CROSS -> {
                        World3D.line(Vec3(box.minX, box.minY, box.maxZ), Vec3(box.maxX, box.minY, box.minZ), c, noCull = true)
                        World3D.line(Vec3(box.maxX, box.minY, box.maxZ), Vec3(box.minX, box.minY, box.minZ), c, noCull = true)
                    }
                }
            }
        }
    }

    /** The bottom layer within [range] of the player (x and z truncated, end exclusive) minus bedrock. */
    private fun scan(): List<BlockPos> {
        val p = mc.player ?: return emptyList()
        val level = mc.level ?: return emptyList()
        val list = ArrayList<BlockPos>()
        val r = range.value
        for (x in (p.x - r).toInt() until (p.x + r).toInt()) {
            for (z in (p.z - r).toInt() until (p.z + r).toInt()) {
                val pos = BlockPos.containing(x.toDouble(), level.minY.toDouble(), z.toDouble())
                if (level.getBlockState(pos).block != Blocks.BEDROCK) list += pos
            }
        }
        return list
    }
}
