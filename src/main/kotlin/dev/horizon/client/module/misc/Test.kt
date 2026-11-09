package dev.horizon.client.module.misc

import dev.horizon.client.combat.Explosions
import dev.horizon.client.combat.Misc2Util
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.max

/**
 * A developer benchmark: every frame, the crystal damage to us from every block within 6 of
 * our eyes, once without and once with the explosion block cache; prints any mismatch and the times to chat.
 */
class Test : Module("test", Categories.MISC) {
    init {
        on<Render3DEvent> { bench() }
    }

    private fun bench() {
        val p = mc.player ?: return
        val c = BlockPos.containing(p.eyePosition)
        var plain = 0L
        var cached = 0L
        for (x in c.x - 6..c.x + 6) for (y in c.y - 6..c.y + 6) for (z in c.z - 6..c.z + 6) {
            val pos = BlockPos(x, y, z)
            Explosions.useCache = false
            var t = System.currentTimeMillis()
            val a = Explosions.damage(Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0), p, 0, true, emptyList(), emptyList())
            plain += System.currentTimeMillis() - t
            Explosions.useCache = true
            t = System.currentTimeMillis()
            val b = Explosions.damage(Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0), p, 0, true, emptyList(), emptyList())
            cached += System.currentTimeMillis() - t
            if (a != b) Misc2Util.chat(this, "$pos $a != $b")
        }
        val saved = ((plain - cached).toFloat() / max(plain, 1L).toFloat() * 100f).toInt()
        Misc2Util.chat(this, "time: $plain; optimized: $cached, saved: $saved%")
    }
}
