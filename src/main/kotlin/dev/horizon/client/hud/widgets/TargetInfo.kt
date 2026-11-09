package dev.horizon.client.hud.widgets

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
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The aura's (or auto-crystal's) target with armour, head, hands, totem pops and health, red sparks
 * on hits, drops at low health and "EZZZ…" after a kill. In chat and the Hud editor the target is you.
 */
object TargetInfo : HudWidget("target-info") {
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val hurt: Boolean) {
        var age = 0
        var px = x
        var py = y
    }

    private val particles = ArrayList<Particle>()
    private var shown: LivingEntity? = null
    private val show = Animation(Easing.OUT_BACK, 300)
    private val health = Animation(Easing.OUT_QUINT, 300)

    /** Ticks since the shown player started dying. */
    private var dying = 0

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
        draw(false)
    }

    override fun postRender() = draw(true)

    /**
     * The new look (hud.css .ti): the rounded head, the name, a red-to-orange health bar, the health
     * and totem pops, and the armour and main hand in a row (in the editor, where the target is you, "you, in the
     * editor" beside them).
     */
    private fun draw(post: Boolean) {
        val pose = Canvas.pose
        pose.pushMatrix()
        val s = show.value.coerceIn(0f, 2f)
        pose.translate(left + 60f, top + 27.5f)
        pose.scale(s, s)
        pose.translate(-(left + 60f), -(top + 27.5f))
        val u = shown
        if (u != null) {
            if (!post) {
                plate(left, top, 120f, 55f)
                if (dying > 0) plate(left, top, 120f, 55f, ((dying + 5f + pt) / 20f).coerceIn(0f, 1f))
                if (u is Player && dying == 0) {
                    val items = armor(u) + u.mainHandItem
                    for ((i, stack) in items.withIndex()) {
                        if (stack.isEmpty) continue
                        pose.pushMatrix()
                        pose.translate(left + 4.5f + i * 13f, top + 36.5f)
                        pose.scale(0.6875f, 0.6875f)
                        Canvas.graphics?.item(stack, 0, 0)
                        pose.popMatrix()
                    }
                }
                size(120f, 55f)
            } else {
                drawParticles()
                if (u is AbstractClientPlayer) {
                    pose.pushMatrix()
                    val shake = (10f - health.value.coerceIn(0f, 10f)) / 2f
                    val sx = shake * sin(u.tickCount + pt)
                    val k = 1f - u.hurtTime / 100f
                    pose.rotateAbout(Math.toRadians(sx.toDouble()).toFloat(), left + 18.5f, top + 18.5f)
                    pose.translate(left + 18.5f + shake * sx, top + 18.5f - shake * cos(u.tickCount + pt))
                    pose.scale(k, k)
                    pose.translate(-(left + 18.5f), -(top + 18.5f))
                    Skins.roundedHead(u, left + 4.5f, top + 4.5f, 28f, 4.5f)
                    Ui.box(left + 4.5f, top + 4.5f, 28f, 28f, 4.5f, 0, 0.5f, 0x24FFFFFF)
                    pose.popMatrix()
                }
                health.update(u.health + u.absorptionAmount)
                val frac = (health.value / 20f).coerceIn(0f, 1f)
                ctext(Ui.fit(HudType.semi, u.name.string, 78f), left + 37.5f, top + 10.33f, Tok.T1, HudType.semi)
                Ui.box(left + 37.5f, top + 17.5f, 78f, 4f, 2f, 0x14FFFFFF)
                if (frac > 0f) {
                    Ui.glow(left + 37.5f, top + 17.5f, 78f * frac, 4f, 2f, 4f, 0x66FF5D5D)
                    Ui.hbox(left + 37.5f, top + 17.5f, 78f * frac, 4f, 2f, 0xFFFF5D5D.toInt(), Colors.lerp(0xFFFF5D5D.toInt(), 0xFFFFB35D.toInt(), frac))
                }
                val hp = ((health.value * 10.0).roundToInt() / 10.0).toString()
                val pops = if (u is Player) ServerInfo.pops(u) else 0
                ctext("$hp hp \u00b7 $pops pops", left + 37.5f, top + 28.33f, Tok.T3, HudType.small)
                if (u === mc.player && editorMode) {
                    val t = "you, in the editor"
                    ctext(t, left + 115.5f - tw(t, HudType.small), top + 42f, Tok.T2, HudType.small)
                }
                Fonts.bold12.drawGradientCentered("EZZZZZZZZZZ".dropLast(11 - dying.coerceIn(0, 11)), left + 59f, top + 19f, 10)
            }
        }
        pose.popMatrix()
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
            particles += Particle(left + 4.5f + 28 * Math.random().toFloat(), top + 4.5f,
                Math.random().toFloat() * 2 - 1, Math.random().toFloat() * 2, false)
        }
        val hpFrac = (health.value / 20f).coerceIn(0f, 1f)
        if (t is LivingEntity && t.hurtTime in 5..10) {
            repeat(6) {
                val sx = left + 32.5f + 78 * hpFrac
                particles += Particle(sx, top + 14.5f + Math.random().toFloat() * 2 - 1,
                    Math.random().toFloat() * 2, Math.random().toFloat() * 1 - 0.5f, true)
            }
        }
        particles.removeIf { step(it) }
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
        val inHead = left + hx <= p.x + 4 && p.x + 4 <= left + hx + 28 && top + hy <= p.y + 4 && p.y + 4 <= top + hy + 28
        if (!inHead && !p.hurt) {
            p.vy += 0.2f
            p.vx *= 0.95f
        }
        val old = p.age
        p.age = old + 1
        return old > (if (p.hurt) 10 else 60)
    }
}
