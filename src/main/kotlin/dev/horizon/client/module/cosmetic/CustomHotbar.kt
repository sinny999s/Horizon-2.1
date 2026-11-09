package dev.horizon.client.module.cosmetic

import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/**
 * Modern sleek bottom HUD overhaul with a glassmorphism aesthetic.
 * Replaces vanilla hotbar, selection box, XP bar, health and hunger with a floating rounded glass dock,
 * smooth sliding animated selector, offhand dock, and modern status pills.
 */
class CustomHotbar : Module("custom-hotbar", Categories.COSMETIC) {
    companion object {
        var instance: CustomHotbar? = null

        @JvmStatic
        val isHotbarActive: Boolean
            get() = instance?.let { it.enabled && it.modernHotbar.value } == true

        @JvmStatic
        val isHealthActive: Boolean
            get() = instance?.let { it.enabled && it.modernHealth.value } == true

        @JvmStatic
        val isFoodActive: Boolean
            get() = instance?.let { it.enabled && it.modernFood.value } == true

        @JvmStatic
        val isXpActive: Boolean
            get() = instance?.let { it.enabled && it.modernXp.value } == true
    }

    val modernHotbar = bool("modern-hotbar", true)
    val modernHealth = bool("modern-health", true)
    val modernFood = bool("modern-food", true)
    val modernXp = bool("modern-xp", true)
    val offhand = bool("offhand", true)
    val syncTheme = bool("sync-theme", true)
    val accent = color("accent", Color(0xC88032FF.toInt(), true)) { !syncTheme.value }

    private val selectorAnim = Animation(Easing.OUT_QUINT, 140)

    init {
        instance = this
        on<Render2DEvent> { e ->
            val p = mc.player ?: return@on
            if (mc.gui.hud.isHidden) return@on
            if (mc.gui.screen() != null && mc.gui.screen()?.isInGameUi != true) return@on
            render(e.graphics)
        }
    }

    override fun onEnable() {
        mc.player?.let { selectorAnim.reset(it.inventory.selectedSlot.toFloat()) }
    }

    private fun render(g: GuiGraphicsExtractor) {
        val p = mc.player ?: return
        val sw = mc.window.guiScaledWidth.toFloat()
        val sh = mc.window.guiScaledHeight.toFloat()
        val barW = 186f
        val barH = 22f
        val barX = (sw - barW) / 2f
        val barY = sh - 26f
        val accentColor = if (syncTheme.value) Theme.gradient(0) else accent.value.rgb
        // As vanilla: no hotbar in spectator, health and food only where you can be hurt, XP only where it counts.
        val gameMode = mc.gameMode ?: return
        val hurtable = gameMode.canHurtPlayer()

        if (modernHotbar.value && !p.isSpectator) {
            // Main Glass Dock
            Canvas.glass(barX, barY, barW, barH, 4f, 1.2f, 0f, 1f, Theme.widgetBlur, Theme.outline)

            // Slot Dividers
            for (i in 1..8) {
                Canvas.quad(barX + 3f + i * 20f - 0.5f, barY + 3f, 1f, 16f, 0x18FFFFFF)
            }

            // Smooth Animated Selector Box
            selectorAnim.update(p.inventory.selectedSlot.toFloat())
            val selX = barX + 3f + selectorAnim.value * 20f
            Canvas.glass(selX, barY + 1.5f, 20f, 19f, 3.5f, 1.5f, 0f, 1f, 0, accentColor)
            Canvas.quad(selX + 1.5f, barY + 3f, 17f, 16f, PlaceBoxes.withAlpha(accentColor, 0.22f))

            // Offhand Dock
            if (offhand.value) {
                val offX = barX - 26f
                val offY = barY
                Canvas.glass(offX, offY, 22f, 22f, 4f, 1.2f, 0f, 1f, Theme.widgetBlur, Theme.outline)
                val offStack = p.offhandItem
                if (!offStack.isEmpty) {
                    g.item(offStack, (offX + 3f).toInt(), (offY + 3f).toInt())
                    g.itemDecorations(mc.font, offStack, (offX + 3f).toInt(), (offY + 3f).toInt())
                }
            }

            // Hotbar Items
            for (i in 0..8) {
                val sx = barX + 3f + i * 20f + 2f
                val sy = barY + 3f
                val stack = p.inventory.getItem(i)
                if (!stack.isEmpty) {
                    g.item(stack, sx.toInt(), sy.toInt())
                    g.itemDecorations(mc.font, stack, sx.toInt(), sy.toInt())
                }
            }
        }

        // Modern XP Bar
        if (modernXp.value && gameMode.hasExperience()) {
            val xpY = barY - 4.5f
            val xpH = 2.5f
            Canvas.quad(barX, xpY, barW, xpH, 0x60000000)
            val xpFrac = p.experienceProgress.coerceIn(0f, 1f)
            val xpCol = if (syncTheme.value) Theme.gradient(0) else 0xFF55FF55.toInt()
            if (xpFrac > 0f) {
                Canvas.quad(barX, xpY, barW * xpFrac, xpH, xpCol)
            }
        }

        // Status Pills (Health & Food)
        val pillW = 87f
        val pillH = 11f
        val statusY = barY - 18f

        if (modernHealth.value && hurtable) {
            val hpX = barX
            Canvas.glass(hpX, statusY, pillW, pillH, 3f, 1f, 0f, 1f, Theme.widgetBlur, Theme.outline)
            val hp = p.health.coerceAtLeast(0f)
            val maxHp = p.maxHealth.coerceAtLeast(1f)
            val abs = p.absorptionAmount.coerceAtLeast(0f)
            val totalCap = maxOf(maxHp, hp + abs)
            val innerW = pillW - 3f
            val hpW = innerW * (hp / totalCap).coerceIn(0f, 1f)
            val absW = innerW * (abs / totalCap).coerceIn(0f, 1f)

            if (hpW > 0f) {
                Canvas.quad(hpX + 1.5f, statusY + 1.5f, hpW, pillH - 3f, 0xFFE02A4A.toInt())
            }
            if (absW > 0f) {
                Canvas.quad(hpX + 1.5f + hpW, statusY + 1.5f, absW, pillH - 3f, 0xFFFFA500.toInt())
            }

            val hpStr = "${(hp + abs).toInt()} HP"
            Fonts.medium65.draw(hpStr, hpX + 4f, statusY + 2.5f, 0xFFFFFFFF.toInt())
            if (p.armorValue > 0) {
                val armorStr = "${p.armorValue} Armor"
                Fonts.medium65.draw(armorStr, hpX + pillW - Fonts.medium65.width(armorStr) - 4f, statusY + 2.5f, 0xFF99BBEE.toInt())
            }
            Fonts.medium65.flush()
        }

        if (modernFood.value && hurtable) {
            val foodX = barX + barW - pillW
            Canvas.glass(foodX, statusY, pillW, pillH, 3f, 1f, 0f, 1f, Theme.widgetBlur, Theme.outline)
            val food = p.foodData.foodLevel.coerceAtLeast(0)
            val innerW = pillW - 3f
            val foodW = innerW * (food / 20f).coerceIn(0f, 1f)

            if (foodW > 0f) {
                Canvas.quad(foodX + 1.5f, statusY + 1.5f, foodW, pillH - 3f, 0xFFE07820.toInt())
            }

            val foodStr = "$food Food"
            Fonts.medium65.draw(foodStr, foodX + 4f, statusY + 2.5f, 0xFFFFFFFF.toInt())
            if (p.airSupply < p.maxAirSupply) {
                val airPct = "${(p.airSupply * 100 / p.maxAirSupply).coerceAtLeast(0)}% Air"
                Fonts.medium65.draw(airPct, foodX + pillW - Fonts.medium65.width(airPct) - 4f, statusY + 2.5f, 0xFF66CCFF.toInt())
            }
            Fonts.medium65.flush()
        }

        // Centered Experience Level badge between pills
        if (modernXp.value && gameMode.hasExperience() && p.experienceLevel > 0) {
            val lvlStr = p.experienceLevel.toString()
            Fonts.bold8.drawCentered(lvlStr, barX + barW / 2f, statusY + 2f, 0xFF55FF55.toInt())
            Fonts.bold8.flush()
        }
    }
}
