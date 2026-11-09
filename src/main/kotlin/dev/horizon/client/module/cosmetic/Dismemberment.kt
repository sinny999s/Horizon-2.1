package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import dev.horizon.client.HorizonClient
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.mixin.cosmetic1.ModelPartAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.World3D
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.level.block.LiquidBlock
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * When a player leaves the level, its head, body, arms and legs fall apart as six
 * skinned pieces that tumble, bounce off the ground and fade out after [lifeTime] seconds.
 */
class Dismemberment : Module("dismemberment", Categories.COSMETIC) {
    val sound = bool("sound", true)
    val lifeTime = int("life-time", 10, 3..100)

    /** The model parts a piece is made of, and how high it sits above its position (flying, landed). */
    private enum class Part(val parts: (PlayerModel) -> List<ModelPart>, val flying: Double, val landed: Double) {
        HEAD({ listOf(it.head, it.hat) }, 0.5, 0.25),
        BODY({ listOf(it.body, it.jacket) }, 0.7, 0.2),
        LEFT_ARM({ listOf(it.leftArm, it.leftSleeve) }, 0.6, 0.2),
        RIGHT_ARM({ listOf(it.rightArm, it.rightSleeve) }, 0.6, 0.2),
        LEFT_LEG({ listOf(it.leftLeg, it.leftPants) }, 0.7, 0.2),
        RIGHT_LEG({ listOf(it.rightLeg, it.rightPants) }, 0.7, 0.2),
    }

    /** One piece. Velocities and spins are random (Math.random), rotations in degrees. */
    private inner class Piece(val skin: Identifier, val model: PlayerModel, val part: Part, var x: Double, var y: Double, var z: Double) {
        var vx = jitter()
        var vy = jitter()
        var vz = jitter()
        var rx = spin() * 5.0
        var ry = spin() * 5.0
        var rz = spin() * 5.0
        var prx = rx
        var pry = ry
        var prz = rz
        var drx = spin()
        var dry = spin()
        var drz = spin()
        var age = 0
        var px = x
        var py = y
        var pz = z

        private fun jitter() = Math.random() * 0.3 - 0.15
        private fun spin() = Math.random() * 5.0 - 2.5

        /** One tick; true once the piece has lived [lifeTime] seconds. */
        fun tick(): Boolean {
            val level = mc.level!!
            px = x
            py = y
            pz = z
            pry = ry
            prz = rz
            prx = rx
            vy -= 0.08
            val pos = BlockPos.containing(x, y - 0.3, z)
            val state = level.getBlockState(pos)
            vx *= 0.91
            vy *= 0.98
            vz *= 0.91
            drx *= 0.91
            dry *= 0.91
            drz *= 0.91
            if (state.block is LiquidBlock) {
                vy = 0.31
            } else if (!state.isAir) {
                vy = 0.0
                y = pos.y + 1.0
                vx *= 0.8
                vz *= 0.8
                rx += (-90.0f - rx % 360.0f) / 2
                drx = 0.0
                dry = 0.0
                drz = 0.0
            }
            for (e in level.entitiesForRendering().filter { it.position().distanceToSqr(x, y, z) - 0.1 < 0 }) {
                vx = (x - e.x) * 0.5
                vz = (z - e.z) * 0.5
            }
            x += vx
            y += vy
            z += vz
            rx += drx
            ry += dry
            rz += drz
            return age++ > lifeTime.value * 20
        }

        /** The part's cubes, unlit, depth-tested, fading out over the last 3 ticks. */
        fun render(view: Matrix4f) {
            val cam = mc.gameRenderer.mainCamera().position()
            val pt = World3D.partialTick.toDouble()
            val stack = PoseStack()
            stack.last().pose().set(view)
            val color = (((255f * Mth.clamp(lifeTime.value * 20 - age, 0, 3) / 3.0f).toInt()) shl 24) or 0xFFFFFF
            stack.pushPose()
            stack.translate(Mth.lerp(pt, px, x) - cam.x.toFloat(), Mth.lerp(pt, py, y) + (if (vy == 0.0) part.landed else part.flying) -
                cam.y.toFloat(), Mth.lerp(pt, pz, z) - cam.z.toFloat())
            stack.scale(0.0555f, 0.0555f, 0.0555f)
            stack.rotateDegrees(Axis.XP, (Mth.lerp(pt, prx, rx) + 180.0).toFloat())
            stack.rotateDegrees(Axis.YP, Mth.lerp(pt, pry, ry).toFloat())
            stack.rotateDegrees(Axis.ZP, Mth.lerp(pt, prz, rz).toFloat())
            val mat = stack.last().pose()
            val main = mc.gameRenderer.mainRenderTarget()
            World3D.draw(Pipelines.GHOST_DEPTH, main.colorTextureView!!, main.depthTextureView, Matrix4f(), Skins.texture(skin)) { vc ->
                for (p in part.parts(model)) cubes(p, vc, color, mat)
            }
            stack.popPose()
        }
    }

    /** The pieces in flight. */
    private val pieces = ArrayList<Piece>()

    /** The players seen in the level. */
    private val seen = ArrayList<AbstractClientPlayer>()

    init {
        on<Render3DEvent> { e -> for (p in pieces) p.render(e.view) }
        on<PlayerUpdateEvent> {
            val level = mc.level ?: return@on
            for (p in level.players()) if (!seen.contains(p)) seen.add(p)
            seen.removeIf { p ->
                if (!level.players().contains(p)) {
                    spawn(p)
                    true
                } else false
            }
            pieces.removeIf { it.tick() }
        }
    }

    /** Six pieces at the player (none while another set with the same skin is still around). */
    private fun spawn(player: AbstractClientPlayer) {
        if (player is LocalPlayer) return
        val self = mc.player ?: return
        if (self.tickCount < 20) return
        val skin = player.skin.body().texturePath()
        val x = player.x
        val y = player.y + 1.45f
        val z = player.z
        @Suppress("UNCHECKED_CAST")
        val model = (mc.entityRenderDispatcher.getRenderer(self) as AvatarRenderer<LocalPlayer>).model
        if (pieces.any { it.skin == skin }) return
        pieces += Piece(skin, model, Part.HEAD, x, y, z)
        pieces += Piece(skin, model, Part.BODY, x, y, z)
        pieces += Piece(skin, model, Part.RIGHT_ARM, x + 0.35f, y - 0.15f, z)
        pieces += Piece(skin, model, Part.LEFT_ARM, x - 0.35f, y - 0.15f, z)
        pieces += Piece(skin, model, Part.RIGHT_LEG, x + 0.1f, y - 0.7f, z)
        pieces += Piece(skin, model, Part.LEFT_LEG, x - 0.1f, y - 0.7f, z)
        if (sound.value && self.distanceToSqr(x, y, z) - 49.0 < 0) {
            mc.executeIfPossible { mc.level?.playLocalSound(x, y, z, LEGO_BREAKING, SoundSource.PLAYERS, 1.0f, 1.0f, true) }
        }
    }

    /** Every face of the part's own cubes, positions through [mat] (children and the part's pose ignored). */
    private fun cubes(part: ModelPart, vc: VertexConsumer, color: Int, mat: Matrix4f) {
        val t = Vector3f()
        for (cube in (part as ModelPartAccessor).horizon_cubes()) {
            for (poly in cube.polygons) {
                for (i in 0..3) {
                    val v = poly.vertices()[i]
                    mat.transformPosition(v.x(), v.y(), v.z(), t)
                    vc.addVertex(t.x, t.y, t.z).setUv(v.u(), v.v()).setColor(color)
                }
            }
        }
    }

    private companion object {
        /** The "lego-breaking" sound (volume 1). */
        val LEGO_BREAKING: SoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(HorizonClient.ID, "lego-breaking"))
    }
}
