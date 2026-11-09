package dev.horizon.client.combat

import dev.horizon.client.module.Animation
import dev.horizon.client.module.Render
import dev.horizon.client.render.World3D
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import java.util.concurrent.ConcurrentHashMap

/**
 * A 300 ms box on every block a placer module placed. Entries are equal when everything matches, so
 * placing the same block again with the same look restarts its box.
 */
object PlaceBoxes {
    data class Box(val pos: BlockPos, val line: Int, val fill: Int, val animation: Animation, val mode: Render)

    const val LIFETIME = 300L

    val boxes = ConcurrentHashMap<Box, Long>()

    fun add(pos: BlockPos, line: Int, fill: Int, animation: Animation, mode: Render) {
        if (mode == Render.NONE) return
        boxes[Box(pos, line, fill, animation, mode)] = System.currentTimeMillis()
    }

    /** First thing in the world flush: drop boxes older than 300 ms, queue the rest. */
    fun render() {
        val now = System.currentTimeMillis()
        val it = boxes.entries.iterator()
        while (it.hasNext()) {
            val (b, t0) = it.next()
            if ((now - t0).toFloat() > 300f) {
                it.remove()
                continue
            }
            draw(b, now - t0)
        }
    }

    private fun draw(b: Box, t: Long) {
        val f = factor(b.animation, t)
        val p = b.pos
        val box = when (b.animation) {
            Animation.Static, Animation.Fade -> AABB(p)
            Animation.Grow -> AABB(p.x.toDouble(), p.y + f.toDouble(), p.z.toDouble(), p.x + 1.0, p.y.toDouble(), p.z + 1.0)
            else -> cube(p, f)
        }
        val line = withAlpha(b.line, (b.line ushr 24) / 255f * f)
        val fill = withAlpha(b.fill, (b.fill ushr 24) / 255f * f)
        if (b.mode == Render.BOTH || b.mode == Render.LINE) World3D.outline(box, line)
        if (b.mode == Render.BOTH || b.mode == Render.FILL) World3D.fill(box, fill)
        if (b.mode == Render.SHADER) World3D.shaderBox(box, fill)
    }

    /** `[b + 0.5 − f/2, b + 0.5 + f/2]` on each axis. */
    private fun cube(p: BlockPos, f: Float): AABB {
        val h = f / 2.0
        return AABB(p.x + 0.5 - h, p.y + 0.5 - h, p.z + 0.5 - h, p.x + 0.5 + h, p.y + 0.5 + h, p.z + 0.5 + h)
    }

    /** The colour with alpha `(int)(255 · clamp(a, 0, 1))`. */
    fun withAlpha(argb: Int, a: Float): Int = ((255f * Mth.clamp(a, 0f, 1f)).toInt() shl 24) or (argb and 0xFFFFFF)

    /** The animation factor `f(t)` for a box [t] ms old: it scales the alphas and, for most animations, the cube. */
    fun factor(a: Animation, t: Long): Float = when (a) {
        Animation.Static -> 1f
        Animation.Decrease, Animation.Fade -> 1f - t / 300f
        Animation.FILL, Animation.Grow -> t / 300f
        Animation.Flash -> if (t <= 100) t / 100f else 1f - (t - 100) / 400f
        Animation.TNT -> if (t < 200) 1f else 1f + (t - 200) / 400f
        Animation.Pull -> if (t < 200) 1.5f - (t / 200f) * 0.5f else 1f
        Animation.Hover -> 1f + t / 1500f
    }
}
