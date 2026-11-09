package dev.horizon.client.module.cosmetic

import dev.horizon.client.combat.Esp2
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.event.FreecamStateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.legit.BowMinecart
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import dev.horizon.client.setting.Bind
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.EggItem
import net.minecraft.world.item.EnderpearlItem
import net.minecraft.world.item.ExperienceBottleItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.item.LingeringPotionItem
import net.minecraft.world.item.SnowballItem
import net.minecraft.world.item.SplashPotionItem
import net.minecraft.world.item.TridentItem
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The flight path of whatever bow, crossbow or throwable every player holds, up to 300
 * ticks, with a ring where it lands. Holding "preview" moves the camera to where your pearl would land.
 */
class Trajectories : Module("trajectories", Categories.COSMETIC) {
    val preview = bind("preview", Bind())
    val handOffset = bool("hand-offset", false)

    /** Where the local player's pearl lands, from the last frame. */
    private var landing: Vec3? = null

    /** The camera's slide towards [landing] while "preview" is held. */
    private val slide = Animation(Easing.OUT_QUINT, 300)

    /** The local player's motion at the start of the tick. */
    private var motion: Vec3 = Vec3.ZERO

    init {
        on<FreecamStateEvent> { e -> previewCamera(e) }
        on<TickEvent> { mc.player?.let { motion = it.deltaMovement } }
        on<Render3DEvent> { render() }
    }

    /** Is the preview bind's key or mouse button held. */
    private fun previewHeld(): Boolean {
        val b = preview.value
        if (b.key == -1) return false
        return if (b.mouse) b.key in Keys.heldMouse else Keys.down(b.key)
    }

    private fun previewCamera(e: FreecamStateEvent) {
        val at = landing ?: return
        if (preview.value.key == -1 || !previewHeld()) return
        val cam = mc.cameraEntity ?: return
        val p = mc.player ?: return
        e.cancel()
        val to = at.add(0.0, p.getEyeHeight(p.pose).toDouble(), 0.0)
        val from = p.eyePosition
        val t = slide.value.toDouble()
        e.x = Mth.lerp(t, from.x, to.x)
        e.y = Mth.lerp(t, from.y, to.y)
        e.z = Mth.lerp(t, from.z, to.z)
        e.yaw = cam.yRot
        e.pitch = 0f
    }

    private fun render() {
        val level = mc.level ?: return
        slide.update(if (previewHeld()) 1f else 0f)
        landing = null
        val bob = mc.options.bobView().get()
        mc.options.bobView().set(false)
        for (p in level.players()) {
            val main = p.mainHandItem
            val off = p.offhandItem
            val hand = when {
                main.item is BowItem || main.item is CrossbowItem || throwable(main.item) -> InteractionHand.MAIN_HAND
                off.item is BowItem || off.item is CrossbowItem || throwable(off.item) -> InteractionHand.OFF_HAND
                else -> continue
            }
            val item = if (hand == InteractionHand.OFF_HAND) off.item else main.item
            val multiOff = Esp2.hasEnchant(off, Enchantments.MULTISHOT)
            val multiMain = Esp2.hasEnchant(main, Enchantments.MULTISHOT)
            val yaw = Mth.lerp(World3D.partialTick, p.yRotO, p.yRot)
            if (off.item is CrossbowItem && multiOff || main.item is CrossbowItem && multiMain) {
                path(p, item, yaw - 10)
                path(p, item, yaw)
                path(p, item, yaw + 10)
            } else {
                path(p, item, yaw)
            }
        }
        mc.options.bobView().set(bob)
    }

    private fun throwable(item: Item) = item is EnderpearlItem || item is TridentItem || item is ExperienceBottleItem ||
        item is SnowballItem || item is EggItem || item is SplashPotionItem || item is LingeringPotionItem

    /** The aim vector's length before normalising. */
    private fun aimScale(item: Item) = if (item is BowItem) 1.0f else 0.4f

    /** Launch speed. */
    private fun speed(item: Item) = when (item) {
        is SplashPotionItem, is LingeringPotionItem -> 0.39f
        is ExperienceBottleItem -> 0.59f
        is TridentItem -> 2.0f
        else -> 1.5f
    }

    /** Thrown up by 20 degrees. */
    private fun pitchOffset(item: Item) = if (item is SplashPotionItem || item is LingeringPotionItem || item is ExperienceBottleItem) 20 else 0

    /** The landing ring's radius. */
    private fun ringRadius(item: Item) = if (item is SplashPotionItem || item is LingeringPotionItem || item is ExperienceBottleItem) 4.0f else 0.5f

    /** Bow-minecart is on and you hold a Flame bow: mark the block a flaming arrow would ignite. */
    private fun bowMinecartAims(): Boolean {
        val p = mc.player ?: return false
        if (!Modules.get<BowMinecart>().enabled) return false
        val bow = p.mainHandItem
        return bow.item == Items.BOW && Esp2.hasEnchant(bow, Enchantments.FLAME)
    }

    /** One path from [player]'s eyes at [yaw]. */
    fun path(player: Player, item: Item, yaw: Float) {
        val level = mc.level ?: return
        landing = null
        val pt = World3D.partialTick
        var x = Mth.lerp(pt.toDouble(), player.xo, player.x)
        var y = Mth.lerp(pt.toDouble(), player.yo, player.y)
        var z = Mth.lerp(pt.toDouble(), player.zo, player.z)
        y += player.getEyeHeight(player.pose)
        if (handOffset.value) {
            y -= 0.1
            if (player == mc.player) {
                val ox = (cos(yaw / 180.0f * Math.PI.toFloat()) * 0.16f).toDouble()
                val oz = (sin(yaw / 180.0f * Math.PI.toFloat()) * 0.16f).toDouble()
                if (item == player.offhandItem.item) {
                    x += ox
                    z += oz
                } else {
                    x -= ox
                    z -= oz
                }
            }
        }
        val pitch = Mth.lerp(pt, player.xRotO, player.xRot)
        val m = aimScale(item)
        var vx = (-sin(yaw / 180.0f * Math.PI.toFloat()) * cos(pitch / 180.0f * Math.PI.toFloat()) * m).toDouble()
        var vy = (-sin((pitch - pitchOffset(item)) / 180.0f * 3.141593f) * m).toDouble()
        var vz = (cos(yaw / 180.0f * Math.PI.toFloat()) * cos(pitch / 180.0f * Math.PI.toFloat()) * m).toDouble()
        var power = Mth.lerp(pt, max(0.0f, player.ticksUsingItem - 1.0f), player.ticksUsingItem.toFloat()) / 20.0f
        power = (power * power + power * 2.0f) / 3.0f
        if (power > 1.0f || power == 0.0f) power = 1.0f
        val len = Mth.sqrt((vx * vx + vy * vy + vz * vz).toFloat())
        vx /= len
        vy /= len
        vz /= len
        val v = (if (item is BowItem) power * 2.0f else if (item is CrossbowItem) 2.2f else 1.0f) * speed(item)
        vx *= v
        vy *= v
        vz *= v
        if (player == mc.player) {
            vx += Mth.lerp(pt.toDouble(), motion.x, player.deltaMovement.x)
            vy += Mth.lerp(pt.toDouble(), motion.y, player.deltaMovement.y)
            vz += Mth.lerp(pt.toDouble(), motion.z, player.deltaMovement.z)
        }
        val gravity = if (item is BowItem) 0.05f.toDouble() else if (player.mainHandItem.item is CrossbowItem) 0.05f.toDouble() else 0.03
        for (i in 0 until 300) {
            val prev = Vec3(x, y, z)
            x += vx
            y += vy
            z += vz
            if (level.getBlockState(BlockPos.containing(x, y, z)).block == Blocks.WATER) {
                vx *= 0.8
                vy *= 0.8
                vz *= 0.8
            } else {
                vx *= 0.99
                vy *= 0.99
                vz *= 0.99
            }
            vy -= gravity
            val cur = Vec3(x, y, z)
            val hit = level.clip(ClipContext(prev, cur, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            if (hit.type == HitResult.Type.BLOCK) {
                if (bowMinecartAims()) {
                    val above = hit.blockPos.above()
                    val box = AABB(above).setMaxY(above.y + 0.125)
                    val me = mc.player!!
                    val range = me.blockInteractionRange().toFloat()
                    val dist = me.eyePosition.distanceTo(Vec3.atCenterOf(hit.blockPos))
                    val c = if (2.3f - dist <= 0 && dist <= range) Theme.gradient(0) else Color.RED.rgb
                    World3D.outline(box, c)
                    World3D.fill(box, PlaceBoxes.withAlpha(c, (c ushr 24) / 255f / 4.0f))
                } else {
                    ring(hit.location, ringRadius(item), hit.direction, Theme.gradient(i * 10))
                }
                World3D.line(prev, hit.location, Theme.gradient(i))
                if (item is EnderpearlItem && player == mc.player) landing = hit.location
                break
            }
            var hitEntity = false
            val near = level.entitiesForRendering().filter {
                it !is Projectile && it != mc.player && (it.distanceToSqr(cur) < 25.0 || it.distanceToSqr(prev) < 25.0)
            }
            for (e in near) {
                val at = e.boundingBox.clip(prev, cur)
                if (at.isPresent && !hitEntity) {
                    World3D.line(prev, at.get(), Theme.gradient(i))
                    hitEntity = true
                    if (item is EnderpearlItem && player == mc.player) landing = e.boundingBox.center
                }
            }
            if (hitEntity) break
            if (y <= -65.0) break
            if (!(vx == 0.0 && vy == 0.0 && vz == 0.0)) World3D.line(prev, cur, Theme.gradient(i))
        }
    }

    /** A ring of 37 points (every 10 degrees) facing [side], drawn as lines. */
    private fun ring(at: Vec3, r: Float, side: Direction, color: Int) {
        val points = ArrayList<Vec3>()
        for (deg in 0..360 step 10) {
            val a = deg * Math.PI / 180.0
            val (dx, dy, dz) = when (side) {
                Direction.NORTH, Direction.SOUTH -> Triple(sin(a) * r, cos(a) * r, 0.0)
                Direction.EAST, Direction.WEST -> Triple(0.0, sin(a) * r, -cos(a) * r)
                Direction.UP, Direction.DOWN -> Triple(cos(a) * r, 0.0, sin(a) * r)
            }
            points += Vec3(at.x + dx, at.y + dy, at.z + dz)
        }
        for (i in 0 until points.size - 1) World3D.line(points[i], points[i + 1], color)
    }
}
