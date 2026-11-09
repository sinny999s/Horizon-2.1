package dev.horizon.client.module.esp

import com.mojang.blaze3d.vertex.VertexConsumer
import dev.horizon.client.combat.Esp2
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.WorldParticle
import dev.horizon.client.combat.WorldParticles
import dev.horizon.client.event.EntityRemoveEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.widgets.Skins
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Ghost
import dev.horizon.client.render.Pipelines
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import dev.horizon.client.setting.GroupMode
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.arrow.ThrownTrident
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import kotlin.math.min

/**
 * Simulates every flying projectile (and item) of the chosen kinds up to 300
 * ticks and draws its path (gradient lines, or glowing dots), a circle where it lands, and for pearls a ghost of the
 * thrower at the landing spot that fills up as the pearl gets there.
 */
class ProjectilePredict : Module("projectile-predict", Categories.ESP) {
    enum class LineType { DOT, DEFAULT }

    val selection = group("selection", GroupMode.BOOLS)
    val pearls = bool("pearls", true, parent = selection.value)
    val arrows = bool("arrows", true, parent = selection.value)
    val tridents = bool("tridents", true, parent = selection.value)
    val eggs = bool("eggs", true, parent = selection.value)
    val windcharges = bool("windcharges", true, parent = selection.value)
    val snowballs = bool("snowballs", true, parent = selection.value)
    val potions = bool("potions", true, parent = selection.value)
    val drawCircle = bool("draw-circle", true, parent = selection.value)
    val itemsSetting = bool("items", true, parent = selection.value)
    val drawPlayer = bool("draw-player", true) { pearls.value }
    val drawHit = bool("draw-hit", true) { pearls.value }
    val lineType = enum("line-type", LineType.DOT)

    init {
        on<Render3DEvent> { render() }
        on<EntityRemoveEvent> { e -> burst(e.entity) }
    }

    /** Which entities are simulated (airborne ones of an enabled kind). */
    private fun wanted(e: Entity): Boolean = (pearls.value && e is ThrownEnderpearl ||
        arrows.value && e is AbstractArrow && e.isPushedByFluid ||
        tridents.value && e is ThrownTrident && e.isPushedByFluid ||
        windcharges.value && e is WindCharge ||
        eggs.value && e is ThrownEgg ||
        snowballs.value && e is Snowball ||
        potions.value && e is AbstractThrownPotion ||
        itemsSetting.value && e is ItemEntity) && !e.onGround()

    private fun render() {
        val level = mc.level ?: return
        val list = level.entitiesForRendering().filter { wanted(it) }
        val dots = if (lineType.value == LineType.DEFAULT) null else Esp2.Quads()
        for (e in list) simulate(e, dots)
        if (lineType.value == LineType.DOT && dots != null) dots.draw(Pipelines.COLORED_TEXTURE_ADDITIVE, false)
    }

    /** One entity's path. */
    private fun simulate(e: Entity, dots: Esp2.Quads?) {
        val level = mc.level ?: return
        var vel = e.deltaMovement
        var pos = e.position()
        var steps = 0
        for (i in 0 until 300) {
            val prev = pos
            val inWater = level.getBlockState(BlockPos.containing(pos)).block == Blocks.WATER
            val drag = if (inWater) (if (e is ThrownTrident) 0.99 else if (e is ThrownEnderpearl) 0.8 else 0.6) else 0.99
            vel = vel.add(0.0, -e.gravity, 0.0).scale(drag)
            pos = pos.add(vel)
            val hit = level.clip(ClipContext(prev, pos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player!!))
            if (hit.type == HitResult.Type.BLOCK || hit.type == HitResult.Type.ENTITY) {
                if (lineType.value == LineType.DOT) {
                    if (drawCircle.value && !e.onGround()) {
                        Esp2.drawBillboard(Skins.atlas(), hit.location.add(0.0, 0.01, 0.0), 0f, 90f, 1f, Theme.gradient(0), true,
                            Esp2.CIRCLE)
                    }
                } else {
                    World3D.line(prev, hit.location.add(0.0, 0.01, 0.0), Theme.gradient(i * 10))
                }
                if (drawPlayer.value && e is ThrownEnderpearl) {
                    val owner = e.owner
                    if (owner is Player) {
                        landing(hit.location, owner, (e.tickCount + World3D.partialTick) / (e.tickCount + steps))
                    }
                }
                return
            }
            if (pos.y <= -65.0) return
            val dm = e.deltaMovement
            if (dm.x == 0.0 && dm.y == 0.0 && dm.z == 0.0) continue
            steps++
            val a1 = min(i / 8f, 1f).coerceAtLeast(0f)
            val a2 = min((i + 1) / 8f, 1f).coerceAtLeast(0f)
            val c = Theme.gradient(i * 10)
            if (lineType.value == LineType.DOT) {
                Esp2.billboardInto(dots!!, pos, 0f, 0f, 0.3f, PlaceBoxes.withAlpha(c, a1), Esp2.FIREFLY)
            } else {
                World3D.line(prev, pos, PlaceBoxes.withAlpha(c, a1), PlaceBoxes.withAlpha(Theme.gradient(i * 10), a2))
            }
        }
    }

    /**
     * The thrower at the pearl's landing spot, a faint through-walls ghost plus a solid one that shows only
     * below [fill] of its height (an invisible depth-writing box hides the rest).
     */
    private fun landing(pos: Vec3, owner: Player, fill: Float) {
        val me = mc.player ?: return
        if (me.distanceToSqr(pos) < 1.0) return
        Ghost.draw(pos, owner, 0.45f, false)
        val h = 1.8f
        val box = AABB(pos.x - 0.6f, pos.y + fill * h, pos.z - 0.6f, pos.x + 0.6f, pos.y + h + 0.5f, pos.z + 0.6f)
        val main = mc.gameRenderer.mainRenderTarget()
        World3D.draw(Esp2.DEPTH_BOX, main.colorTextureView!!, main.depthTextureView, Matrix4f(World3D.view)) { vc ->
            blackBox(vc, box)
        }
        Ghost.draw(pos, owner, 1f, true)
    }

    /** The 6 faces with black on every corner, skipped when off-screen. */
    private fun blackBox(vc: VertexConsumer, box: AABB) {
        if (!World3D.isVisible(box)) return
        val cam = World3D.camera
        val x0 = (box.minX - cam.x).toFloat(); val y0 = (box.minY - cam.y).toFloat(); val z0 = (box.minZ - cam.z).toFloat()
        val x1 = (box.maxX - cam.x).toFloat(); val y1 = (box.maxY - cam.y).toFloat(); val z1 = (box.maxZ - cam.z).toFloat()
        fun v(x: Float, y: Float, z: Float) = vc.addVertex(x, y, z).setColor(0, 0, 0, 255)
        v(x0, y0, z0); v(x0, y1, z0); v(x1, y1, z0); v(x1, y0, z0)
        v(x1, y0, z0); v(x1, y1, z0); v(x1, y1, z1); v(x1, y0, z1)
        v(x0, y0, z1); v(x1, y0, z1); v(x1, y1, z1); v(x0, y1, z1)
        v(x0, y0, z0); v(x0, y0, z1); v(x0, y1, z1); v(x0, y1, z0)
        v(x0, y1, z0); v(x0, y1, z1); v(x1, y1, z1); v(x1, y1, z0)
        v(x0, y0, z0); v(x0, y0, z1); v(x1, y0, z1); v(x1, y0, z0)
    }

    /** 25 glowing particles where a pearl disappears. */
    private fun burst(e: Entity?) {
        if (!drawHit.value || e !is ThrownEnderpearl) return
        for (i in 0 until 25) {
            val vel = Vec3(Math.random() / 2.0f - 0.25f, Math.random() / 4.0f, Math.random() / 2.0f - 0.25f)
            WorldParticles.add(WorldParticle(Esp2.FIREFLY, e.position(), 0f, 0.25f, 12, vel, Theme.gradient(i * 10)))
        }
    }
}
