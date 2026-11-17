package dev.horizon.client.hud.widgets

import dev.horizon.client.combat.FightGear
import dev.horizon.client.combat.FightOdds
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.gui.modern.Ui
import dev.horizon.client.hud.HudType
import dev.horizon.client.hud.HudWidget
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.Aura
import dev.horizon.client.module.attack.AutoCrystal
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The aura's (or auto-crystal's) target with armour, head, hands, totem pops and health, red sparks
 * on hits, drops at low health and "EZZZ…" after a kill. In chat and the Hud editor the target is you.
 *
 * `win-loss` adds whether you are winning the fight ([FightOdds]: each side's time to kill the other, from health,
 * gear and the damage dealt and taken lately). `attach-to-target` draws the panel beside the target in the world,
 * following it until the aura lets go, instead of at its place on the screen (where it still is in chat and the editor).
 */
object TargetInfo : HudWidget("target-info") {
    private val winLoss = bool("win-loss", true)
    private val attach = bool("attach-to-target", false)

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val hurt: Boolean) {
        var age = 0
        var px = x
        var py = y
    }

    /** In the panel's own units, from its top-left corner. */
    private val particles = ArrayList<Particle>()
    private var shown: LivingEntity? = null
    private val show = Animation(Easing.OUT_BACK, 300)
    private val health = Animation(Easing.OUT_QUINT, 300)

    /** Ticks since the shown player started dying. */
    private var dying = 0

    private val odds = FightOdds()
    private var oddsFor: LivingEntity? = null
    private var lastHurt = 0

    /** Where the panel is drawn this frame: its top-left and scale, and false while it has nowhere to go. */
    private var ox = 0f
    private var oy = 0f
    private var os = 1f
    private var placed = false

    private const val W = 120f
    private const val H = 55f
    private val WIN = 0xFF4ADE80.toInt()
    private val LOSE = 0xFFFF5D5D.toInt()

    /** The aura's target, else the auto-crystal's while it's on. */
    private fun target(): Entity? = Modules.get<Aura>().target ?: Modules.get<AutoCrystal>().takeIf { it.enabled }?.target

    private val pt get() = mc.deltaTracker.getGameTimeDeltaPartialTick(false)

    private fun armor(p: Player): List<ItemStack> = listOf(p.inventory.getItem(39), p.inventory.getItem(38), p.inventory.getItem(37), p.inventory.getItem(36))

    override fun render() {
        val target = if (editorMode) mc.player else target()
        if (target is LivingEntity) shown = target
        val visible = if (target != null && target.isAlive) true else dying in 1 until 20
        show.update(if (visible) 1f else 0f)
        if (show.value <= 0f) shown = null
        placed = place()
        draw(false)
    }

    override fun postRender() = draw(true)

    /**
     * Where the panel goes this frame: its place on the screen, or attached, beside the target's body on the camera's
     * right (its left when that runs off the screen), at chest height, smaller further away. False while an attached
     * target is behind the camera.
     */
    private fun place(): Boolean {
        val u = shown
        if (!attach.value || editorMode || u == null || u === mc.player) {
            ox = left
            oy = top
            os = 1f
            return true
        }
        val pos = u.getPosition(pt)
        val y = pos.y + u.bbHeight * 0.55
        val chest = Vec3(pos.x, y, pos.z)
        val centre = World3D.project(chest) ?: return false
        val cam = mc.gameRenderer.mainCamera()
        val yaw = Math.toRadians(cam.yRot().toDouble())
        val side = u.bbWidth / 2.0 + 0.35
        // The camera's right, flat.
        val rx = -cos(yaw) * side
        val rz = -sin(yaw) * side
        os = (4f / max(cam.position().distanceTo(chest).toFloat(), 0.1f)).coerceIn(0.55f, 1f)
        val w = W * os
        val h = H * os
        val sw = screenW.toFloat()
        val sh = screenH.toFloat()
        var x = World3D.project(Vec3(pos.x + rx, y, pos.z + rz))?.get(0) ?: (centre[0] + 20f)
        if (x + w > sw - 5f) x = (World3D.project(Vec3(pos.x - rx, y, pos.z - rz))?.get(0) ?: (centre[0] - 20f)) - w
        ox = x.coerceIn(5f, max(5f, sw - w - 5f))
        oy = (centre[1] - h / 2f).coerceIn(5f, max(5f, sh - h - 5f))
        return true
    }

    /**
     * The new look (hud.css .ti): the rounded head, the name, a red-to-orange health bar, the health
     * and totem pops, and the armour and main hand in a row with Winning / Even / Losing at its end (in the editor,
     * where the target is you, "you, in the editor" there instead). Drawn from (0, 0) at the panel's place.
     */
    private fun draw(post: Boolean) {
        val u = shown ?: return
        if (!post) size(W, H)
        if (!placed) return
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(ox, oy)
        pose.scale(os, os)
        val s = show.value.coerceIn(0f, 2f)
        pose.translate(W / 2f, H / 2f)
        pose.scale(s, s)
        pose.translate(-W / 2f, -H / 2f)
        if (!post) {
            plate(0f, 0f, W, H)
            if (dying > 0) plate(0f, 0f, W, H, ((dying + 5f + pt) / 20f).coerceIn(0f, 1f))
            if (u is Player && dying == 0) {
                val items = armor(u) + u.mainHandItem
                for ((i, stack) in items.withIndex()) {
                    if (stack.isEmpty) continue
                    pose.pushMatrix()
                    pose.translate(4.5f + i * 13f, 36.5f)
                    pose.scale(0.6875f, 0.6875f)
                    Canvas.graphics?.item(stack, 0, 0)
                    pose.popMatrix()
                }
            }
        } else {
            drawParticles()
            if (u is AbstractClientPlayer) {
                pose.pushMatrix()
                val shake = (10f - health.value.coerceIn(0f, 10f)) / 2f
                val sx = shake * sin(u.tickCount + pt)
                val k = 1f - u.hurtTime / 100f
                pose.rotateAbout(Math.toRadians(sx.toDouble()).toFloat(), 18.5f, 18.5f)
                pose.translate(18.5f + shake * sx, 18.5f - shake * cos(u.tickCount + pt))
                pose.scale(k, k)
                pose.translate(-18.5f, -18.5f)
                Skins.roundedHead(u, 4.5f, 4.5f, 28f, 4.5f)
                Ui.box(4.5f, 4.5f, 28f, 28f, 4.5f, 0, 0.5f, 0x24FFFFFF)
                pose.popMatrix()
            }
            health.update(u.health + u.absorptionAmount)
            val frac = (health.value / 20f).coerceIn(0f, 1f)
            ctext(Ui.fit(HudType.semi, u.name.string, 78f), 37.5f, 10.33f, Tok.T1, HudType.semi)
            Ui.box(37.5f, 17.5f, 78f, 4f, 2f, 0x14FFFFFF)
            if (frac > 0f) {
                Ui.glow(37.5f, 17.5f, 78f * frac, 4f, 2f, 4f, 0x66FF5D5D)
                Ui.hbox(37.5f, 17.5f, 78f * frac, 4f, 2f, 0xFFFF5D5D.toInt(), Colors.lerp(0xFFFF5D5D.toInt(), 0xFFFFB35D.toInt(), frac))
            }
            val hp = ((health.value * 10.0).roundToInt() / 10.0).toString()
            val pops = if (u is Player) ServerInfo.pops(u) else 0
            ctext("$hp hp · $pops pops", 37.5f, 28.33f, Tok.T3, HudType.small)
            if (u === mc.player && editorMode) {
                val t = "you, in the editor"
                ctext(t, 115.5f - tw(t, HudType.small), 42f, Tok.T2, HudType.small)
            } else if (winLoss.value && dying == 0 && u === oddsFor) {
                verdict()
            }
            Fonts.bold12.drawGradientCentered("EZZZZZZZZZZ".dropLast(11 - dying.coerceIn(0, 11)), 59f, 19f, 10)
        }
        pose.popMatrix()
    }

    /** Winning (green), Even (grey) or Losing (red) in a tinted pill at the end of the armour row. */
    private fun verdict() {
        val (label, color) = when (odds.verdict) {
            FightOdds.Verdict.WINNING -> "Winning" to WIN
            FightOdds.Verdict.LOSING -> "Losing" to LOSE
            FightOdds.Verdict.EVEN -> "Even" to Tok.T2
        }
        val bw = tw(label, HudType.semi) + 9f
        val bx = 115.5f - bw
        Ui.box(bx, 36.5f, bw, 11f, 3f, Colors.withAlpha(color, 0.14f), 0.5f, Colors.withAlpha(color, 0.35f))
        ctext(label, bx + 4.5f, 42f, color, HudType.semi)
    }

    private fun drawParticles() {
        for (p in particles.filter { it.hurt }) {
            Skins.sprite(Skins.FIREFLY_ALT, p.px + (p.x - p.px) * pt, p.py + (p.y - p.py) * pt, 10f, 10f,
                Colors.withAlpha(Theme.primary, (1f - (p.age + pt) / 10f) * 0.8f))
        }
        for (p in particles.filter { !it.hurt }) {
            Skins.sprite(Skins.DROP, p.px + (p.x - p.px) * pt, p.py + (p.y - p.py) * pt, 6f, 6f,
                Colors.withAlpha(-1, 1f - (p.age + pt) / 60f))
        }
    }

    override fun tick() {
        val u = shown
        if (u != null && u.isDeadOrDying && u is Player && u != mc.player) dying++ else dying = 0
        val t = target()
        // Where the new look's head and health bar sit.
        if (t is Player && t.health < 10f && t.tickCount % 6 == 0) {
            particles += Particle(4.5f + 28 * Math.random().toFloat(), 4.5f,
                Math.random().toFloat() * 2 - 1, Math.random().toFloat() * 2, false)
        }
        val hpFrac = (health.value / 20f).coerceIn(0f, 1f)
        if (t is LivingEntity && t.hurtTime in 5..10) {
            repeat(6) {
                val sx = 32.5f + 78 * hpFrac
                particles += Particle(sx, 14.5f + Math.random().toFloat() * 2 - 1,
                    Math.random().toFloat() * 2, Math.random().toFloat() * 1 - 0.5f, true)
            }
        }
        particles.removeIf { step(it) }
        if (winLoss.value) fight(t)
    }

    /** One tick of the fight with the target (or the one dying in the panel) for [odds]; a new target starts over. */
    private fun fight(t: Entity?) {
        val p = mc.player ?: return
        val e = t as? LivingEntity ?: shown?.takeIf { it.isDeadOrDying } ?: return
        if (e === p) return
        if (e !== oddsFor) {
            odds.reset()
            oddsFor = e
            lastHurt = e.hurtTime
        }
        // It started flashing red: hit (by you, most likely).
        val landed = e.hurtTime > lastHurt
        lastHurt = e.hurtTime
        odds.update(FightOdds.Sample(
            myHp = p.health + p.absorptionAmount, theirHp = e.health + e.absorptionAmount,
            myTaken = FightGear.taken(p), theirTaken = FightGear.taken(e),
            myHit = FightGear.hit(p), theirHit = FightGear.hit(e),
            landed = landed, theirMax = e.maxHealth + e.absorptionAmount,
        ))
    }

    private fun step(p: Particle): Boolean {
        p.px = p.x
        p.py = p.y
        p.x += p.vx
        p.y += p.vy
        p.vx *= 0.95f
        p.vy *= 0.95f
        val hx = 4.5
        val hy = 4.5
        val inHead = hx <= p.x + 4 && p.x + 4 <= hx + 28 && hy <= p.y + 4 && p.y + 4 <= hy + 28
        if (!inHead && !p.hurt) {
            p.vy += 0.2f
            p.vx *= 0.95f
        }
        val old = p.age
        p.age = old + 1
        return old > (if (p.hurt) 10 else 60)
    }
}
