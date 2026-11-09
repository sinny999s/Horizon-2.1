package dev.horizon.client.combat

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.horizon.client.HorizonClient
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.module.Mode
import dev.horizon.client.module.Modules
import dev.horizon.client.module.cosmetic.SwingAnimations
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Quaternionfc
import org.joml.Vector3f

/** The sprites on the atlas (`horizon:textures/atlas.png`): u0, v0, u1, v1. */
enum class AtlasSprite(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    SMOKE(0.0f, 0.0f, 0.3906f, 0.3906f),
    CIRCLE(0.3906f, 0.0f, 0.6406f, 0.25f),
    CRACK(0.6406f, 0.0f, 0.8906f, 0.25f),
    CLOUD(0.8906f, 0.0f, 0.9531f, 0.0625f),
    DOLLAR(0.8906f, 0.0625f, 0.9531f, 0.125f),
    DROP(0.8906f, 0.125f, 0.9531f, 0.1875f),
    FIREFLY(0.8906f, 0.1875f, 0.9531f, 0.25f),
    FIREFLY_ALT(0.3906f, 0.25f, 0.4531f, 0.3125f),
    HEART(0.4531f, 0.25f, 0.5156f, 0.3125f),
    SNOWFLAKE(0.5156f, 0.25f, 0.5781f, 0.3125f),
    STAR(0.5781f, 0.25f, 0.6406f, 0.3125f),
    TRIANGLE1(0.6406f, 0.25f, 0.7031f, 0.3125f),
    TRIANGLE2(0.7031f, 0.25f, 0.7656f, 0.3125f),
    TRIANGLE3(0.7656f, 0.25f, 0.8281f, 0.3125f),
    TRIANGLE4(0.8281f, 0.25f, 0.8906f, 0.3125f),
    HALO(0.8906f, 0.25f, 0.9531f, 0.3096f),
    TRIANGLE5(0.3906f, 0.3125f, 0.4531f, 0.3701f),
}

/** A particle mode -> its sprite. */
fun Mode.sprite(): AtlasSprite = when (this) {
    Mode.STAR -> AtlasSprite.STAR
    Mode.FIRE_FLY -> AtlasSprite.FIREFLY
    Mode.DOLLAR -> AtlasSprite.DOLLAR
    Mode.HEART -> AtlasSprite.HEART
    Mode.SNOW_FLAKE -> AtlasSprite.SNOWFLAKE
    Mode.DROP -> AtlasSprite.DROP
    Mode.CLOUD -> AtlasSprite.CLOUD
}

/**
 * One textured quad: corners (0, [size]) (size, size) (size, 0) (0, 0) of [m]'s plane get uv (u1, v0) (u0, v0)
 * (u0, v1) (u1, v1), as the particles and the rain/splash sprites emit them.
 */
fun VertexConsumer.sprite(m: Matrix4f, size: Float, s: AtlasSprite, color: Int, tmp: Vector3f = Vector3f()) {
    fun v(x: Float, y: Float, u: Float, vv: Float) {
        m.transformPosition(x, y, 0f, tmp)
        addVertex(tmp.x, tmp.y, tmp.z).setUv(u, vv).setColor(color)
    }
    v(0f, size, s.u1, s.v0)
    v(size, size, s.u0, s.v0)
    v(size, 0f, s.u0, s.v1)
    v(0f, 0f, s.u1, s.v1)
}

/** Opaque black, which a particle colour uses to mean "the theme gradient". */
const val THEME_COLOR = 0xFF000000.toInt()

/**
 * A camera-facing sprite moving by [velocity] every tick, slowed by [friction] and pulled by
 * [gravity], living [life] ticks; with [fade] it shrinks and fades out. [color] [THEME_COLOR] = the theme gradient.
 */
class Particle3D(
    private val sprite: AtlasSprite,
    pos: Vec3,
    private val rotSpeed: Float,
    private val size: Float,
    private var life: Int,
    private var velocity: Vec3,
    private val color: Int = THEME_COLOR,
    private val gravity: Float = 0.03f,
    private val friction: Float = 0.99f,
    private val fade: Boolean = true,
) {
    var pos: Vec3 = pos
        private set
    private val maxLife = life
    private var prevPos = pos
    private var rot = 0f
    private var prevRot = 0f

    init {
        // A random 0..3 drawn (and never used) for every particle; kept for the shared RNG's sequence.
        Rng.int(0, 3)
    }

    /** One tick; true once it is dead. */
    fun tick(): Boolean {
        life--
        if (life < 0) return true
        prevPos = pos
        pos = pos.add(velocity)
        velocity = velocity.scale(friction.toDouble())
        velocity = velocity.add(0.0, -gravity.toDouble(), 0.0)
        prevRot = rot
        rot += rotSpeed
        return false
    }

    /** The sprite at its interpolated position, turned by [base]. */
    fun build(vc: VertexConsumer, ps: PoseStack, base: Quaternionfc, pt: Float, cam: Vec3, tmp: Vector3f) {
        ps.pushPose()
        val lifeLerp = Mth.lerp(pt, life + 1f, life.toFloat())
        val k = if (fade) lifeLerp / maxLife else 1f
        val s = size * k
        val c = if (color == THEME_COLOR) Theme.gradient(life * 2) else color
        ps.translate(Mth.lerp(pt.toDouble(), prevPos.x, pos.x) - cam.x, Mth.lerp(pt.toDouble(), prevPos.y, pos.y) - cam.y,
            Mth.lerp(pt.toDouble(), prevPos.z, pos.z) - cam.z)
        ps.rotate(base)
        if (rot != 0f && prevRot != 0f) ps.rotate(Axis.ZP.rotationDegrees(Mth.lerp(pt, prevRot, rot)))
        val m = ps.last().pose()
        ps.translate(-s / 2.0, -s / 2.0, -0.01)
        vc.sprite(m, s, sprite, if (!fade) c else Colors.withAlpha(c, k), tmp)
        ps.popPose()
    }
}

/**
 * The particle system: every particle ticks on PlayerUpdate (dead ones dropped) and all are drawn in one
 * additive, depth-tested batch on Render3D. Registered at client start, before any module.
 */
object Particles3D {
    private val mc get() = Minecraft.getInstance()
    private val list = ArrayList<Particle3D>()

    fun init() {
        EventBus.on<Render3DEvent>(this) { render() }
        EventBus.on<PlayerUpdateEvent>(this) { list.removeIf { it.tick() } }
    }

    fun add(p: Particle3D) {
        list += p
    }

    private fun render() {
        if (list.isEmpty()) return
        val cam = mc.gameRenderer.mainCamera()
        val back = Math.toRadians((cam.yRot() + 180f).toDouble()).toFloat()
        val pitch = Math.toRadians(cam.xRot().toDouble()).toFloat()
        val yaw = Math.toRadians(cam.yRot().toDouble()).toFloat()
        val modelView = Matrix4f(World3D.view).rotate(Axis.YP.rotation(-back)).rotate(Axis.XP.rotation(-pitch))
        val ps = PoseStack()
        ps.rotate(Axis.XP.rotation(pitch))
        ps.rotate(Axis.YP.rotation(back))
        val base = Quaternionf().rotateYXZ(-yaw, pitch, 0f)
        val frustum = cam.cullFrustum
        val camPos = cam.position()
        val pt = World3D.partialTick
        val main = mc.gameRenderer.mainRenderTarget()
        World3D.draw(Pipelines.COLORED_TEXTURE_ADDITIVE_DEPTH, main.colorTextureView!!, main.depthTextureView, modelView, Skins.atlas()) { vc ->
            val tmp = Vector3f()
            for (p in list) if (frustum.pointInFrustum(p.pos.x, p.pos.y, p.pos.z)) p.build(vc, ps, base, pt, camPos, tmp)
        }
    }
}

/** Pipelines that [Pipelines] lacks, built on first use like its post pipelines. */
object Cosmetic2Pipelines {
    /** `pos_color_depth`: translucent quads, depth-tested (reversed-Z GEQUAL), no depth write. */
    val POS_COLOR_DEPTH: RenderPipeline = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(HorizonClient.ID, "pipeline/pos_color_depth"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withVertexShader(Identifier.fromNamespaceAndPath(HorizonClient.ID, "core/pos_color"))
        .withFragmentShader(Identifier.fromNamespaceAndPath(HorizonClient.ID, "core/pos_color"))
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withCull(false)
        .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .build()
}

/**
 * An immediate draw in GUI units (ortho over the scaled screen, -100..100, model-view identity),
 * through World3D's draw with its projection swapped for the call.
 */
fun drawGui(pipeline: RenderPipeline, texture: GpuTextureView?, build: (VertexConsumer) -> Unit) {
    val mc = Minecraft.getInstance()
    val window = mc.window
    val ortho = Projection()
    ortho.setupOrtho(-100f, 100f, window.guiScaledWidth.toFloat(), window.guiScaledHeight.toFloat(), true)
    val saved = Matrix4f(World3D.projection)
    World3D.projection.set(ortho.getMatrix(Matrix4f()))
    World3D.draw(pipeline, mc.gameRenderer.mainRenderTarget().colorTextureView!!, null, Matrix4f(), texture, build = build)
    World3D.projection.set(saved)
}

/** Static entry points for this area's mixins. */
object Cosmetic2Hooks {
    private val swingAnimations by lazy { Modules.get<SwingAnimations>() }

    /** Swing Animations needs vanilla 26.2's per-frame `swingArm` call (26.3 only calls it while the hand swings). */
    @JvmStatic
    fun swingEveryFrame(): Boolean = swingAnimations.enabled
}
