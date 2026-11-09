package dev.horizon.client.module.esp

import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.mixin.esp1.DestroyProgressAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Colors
import dev.horizon.client.render.World3D
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The block the player is mining, and (with [external]) every block another player started
 * breaking within 12 blocks, as a box growing with the progress, coloured from [primary] to [secondary], with the percentage.
 * Other players' progress isn't known: it's counted up by 0.025 a tick from the first destruction packet.
 */
class BreakEsp : Module("break-esp", Categories.ESP) {
    enum class Mode { DEFAULT, GROW, SHRINK, FILL, EMPTY }

    val external = bool("external", true)
    val mode = enum("mode", Mode.SHRINK)
    val primary = color("primary", Color(0x64FFFFFF.toInt(), true))
    val secondary = color("secondary", Color(0x64FFFFFF.toInt(), true))
    val text = color("text", Color(0xFFFFFFFF.toInt(), true))

    /** A block someone else is breaking, its progress now ([cur]) and a tick ago ([prev]). */
    private class Breaking(val pos: BlockPos) {
        var cur = 0f
        var prev = 0f

        /** Every tick: true once the block is gone or the count passed 2. */
        fun tick(): Boolean {
            prev = cur
            cur += 0.025f
            if (Minecraft.getInstance().level!!.isEmptyBlock(pos)) return true
            return cur - 2.0f > 0
        }
    }

    private val others = CopyOnWriteArrayList<Breaking>()
    private var progress = 0f
    private var prevProgress = 0f

    init {
        on<ReceivePacketEvent> { e ->
            if (!external.value) return@on
            val packet = e.packet as? ClientboundBlockDestructionPacket ?: return@on
            val p = mc.player ?: return@on
            if (Vec3.atCenterOf(packet.pos).distanceToSqr(p.position()) - 144.0 > 0) return@on
            if (others.none { it.pos == packet.pos }) others += Breaking(packet.pos)
        }
        on<PlayerUpdateEvent> {
            others.removeIf { it.tick() }
            prevProgress = progress
            progress = (mc.gameMode as DestroyProgressAccessor).horizon_getDestroyProgress()
        }
        on<Render3DEvent> { e ->
            val hit = mc.hitResult
            if (hit is BlockHitResult) {
                val pos = hit.blockPos
                if (mc.gameMode!!.isDestroying && !mc.level!!.isEmptyBlock(pos)) {
                    draw(pos, Mth.clamp(Mth.lerp(e.partialTick, prevProgress, progress), 0f, 1f))
                }
            }
            for (b in others) draw(b.pos, Mth.clamp(Mth.lerp(e.partialTick, b.prev, b.cur), 0f, 1f))
        }
    }

    private fun draw(pos: BlockPos, p: Float) {
        val box = box(pos, p)
        val c = Colors.lerp(primary.value.rgb, secondary.value.rgb, p)
        World3D.fill(box, c)
        World3D.outline(box, Colors.withAlpha(c, 1f))
        World3D.label("${(p * 100).toInt()}%", box.center, text.value.rgb)
    }

    private fun box(pos: BlockPos, p: Float): AABB = when (mode.value) {
        Mode.DEFAULT -> AABB(pos)
        Mode.GROW -> {
            val f = 1f - p
            AABB(pos).contract(f.toDouble(), f.toDouble(), f.toDouble()).move(f / 2.0, f / 2.0, f / 2.0)
        }
        Mode.SHRINK -> AABB(pos).contract(p.toDouble(), p.toDouble(), p.toDouble()).move(p / 2.0, p / 2.0, p / 2.0)
        Mode.FILL -> AABB(pos).setMaxY(pos.y + p.toDouble())
        Mode.EMPTY -> AABB(pos).setMaxY(pos.y + (1f - p).toDouble())
    }
}
