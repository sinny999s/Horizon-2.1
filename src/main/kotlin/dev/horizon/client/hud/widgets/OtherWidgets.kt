package dev.horizon.client.hud.widgets

import dev.horizon.client.HorizonClient
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.gui.modern.Ui
import dev.horizon.client.hud.HudType
import dev.horizon.client.hud.HudWidget
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Theme
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments

private const val BLACK = 0xFF000000.toInt()

/** Draws nothing; its `enabled` turns the notifications (and the module toggle sounds) on. */
object NotificationsWidget : HudWidget("notifications") {
    override fun render() {}
}

/**
 * Four arms around the screen centre (black outline, theme-gradient fill) spread by the attack
 * cooldown and the movement speed. Hides the vanilla crosshair; it has no size, so the editor can't grab it.
 */
object Crosshair : HudWidget("crosshair") {
    private var last = 0f

    override fun render() {
        val p = mc.player ?: return
        val col = Theme.gradient(0)
        val cx = screenW / 2f
        val cy = screenH / 2f
        val target = 3f - p.getAttackStrengthScale(0.5f) * 3f + (ServerInfo.horizontalSpeed * 10f).coerceIn(0f, 3f)
        val g = last + (target - last) * mc.deltaTracker.getGameTimeDeltaPartialTick(false) + 7f
        last = target
        Canvas.quad(cx - 1, cy - 6 + g, 2f, 4f, BLACK)
        Canvas.quad(cx - 0.5f, cy - 5.5f + g, 1f, 3f, col)
        Canvas.quad(cx - 1, cy + 2 - g, 2f, 4f, BLACK)
        Canvas.quad(cx - 0.5f, cy + 2.5f - g, 1f, 3f, col)
        Canvas.quad(cx - 6 + g, cy - 1, 4f, 2f, BLACK)
        Canvas.quad(cx - 5.5f + g, cy - 0.5f, 3f, 1f, col)
        Canvas.quad(cx + 2 - g, cy - 1, 4f, 2f, BLACK)
        Canvas.quad(cx + 2.5f - g, cy - 0.5f, 3f, 1f, col)
    }
}

/**
 * The skin head, a rainbow "Horizon" with the version, then the player name and the server address
 * ("singleplayer" without one).
 */
object WatermarkWidget : HudWidget("watermark-widget") {
    private val version by lazy {
        FabricLoader.getInstance().getModContainer(HorizonClient.ID).map { it.metadata.version.friendlyString }.orElse("")
    }

    override fun render() {
        val p = mc.player ?: return
        renderModern(p)
    }

    /**
     * The watermark recoloured: the same head, plates and places, with the plates and text in the
     * widgets' new look, the version grey and "Horizon" in the theme's flowing gradient (the mockup's .hgrad).
     */
    private fun renderModern(p: net.minecraft.client.player.AbstractClientPlayer) {
        val ver = "v$version"
        val name = p.gameProfile.name
        val ip = mc.currentServer?.ip ?: "singleplayer"
        val bw = tw("Horizon", HudType.bold8)
        val w1 = bw + tw(" ") + tw(ver)
        val w2 = tw(name)
        plate(left + 30, top, w1 + 7, 13f)
        plate(left + 30, top + 14, w2 + 6, 13f)
        plate(left + 37 + w2, top + 14, tw(ip) + 7, 13f)
        // background-size 300%, flowing 300% in 6 s: the visible third of a1 > a2 > a1 lightened > a1
        val f = (System.currentTimeMillis() % 3000L) / 3000f
        ctext("Horizon", left + 33f, top + 6.5f, flow(f), HudType.bold8, flow(f + 1f / 3f))
        ctext(ver, left + 33f + bw + tw(" "), top + 6.5f, Tok.T3)
        ctext(name, left + 33f, top + 20.5f, Tok.T1)
        ctext(ip, left + 40.5f + w2, top + 20.5f, Tok.T1)
        Skins.head(p, left + 3, top + 1, 24f, left + 1, top - 1, 28f)
        size(w1 + 40f, 28f)
    }

    private fun flow(t: Float): Int {
        val stops = intArrayOf(Tok.a1, Tok.a2, Tok.light(Tok.a1, 0.4f), Tok.a1)
        val f = (t % 1f) * 3f
        val i = f.toInt().coerceIn(0, 2)
        return Colors.lerp(stops[i], stops[i + 1], f - i)
    }
}

/**
 * While gliding (or in the editor) a panel with a compass arrow, a pitch bar and flight stats
 * (fireworks, elytra durability and range, speeds).
 */
object FlightWidget : HudWidget("flight-widget") {
    private val speeds = ArrayDeque<Float>(400)
    private val show = Animation(Easing.OUT_BACK, 300)

    override fun tick() {
        if (speeds.size >= 400) speeds.removeFirst()
        speeds.add(ServerInfo.horizontalSpeed * 20f)
    }

    override fun render() {
        val p = mc.player ?: return
        show.update(if (p.isFallFlying || editorMode) 1f else 0f)
        if (show.value <= 0f) return
        val s = show.value.coerceIn(0f, 2f)
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(left + 60f, top + 28.5f)
        pose.scale(s, s)
        pose.translate(-(left + 60f), -(top + 28.5f))
        renderModern(p.yRot, p.xRot, stats(p))
        pose.popMatrix()
        size(120f, 55f)
    }

    /** The stats, quirks and all (on purpose). */
    private fun stats(p: net.minecraft.world.entity.player.Player): List<String> {
        var durability = 0
        var res = 0f
        val chest = p.getItemBySlot(EquipmentSlot.CHEST)
        if (chest.`is`(Items.ELYTRA) && speeds.isNotEmpty()) {
            val unbreaking = mc.level?.registryAccess()?.lookupOrThrow(Registries.ENCHANTMENT)?.get(Enchantments.UNBREAKING)?.orElse(null)
            val unb = if (unbreaking != null) EnchantmentHelper.getItemEnchantmentLevel(unbreaking, chest) else 0
            durability = chest.maxDamage - chest.damageValue
            res = (durability * speeds.average()).toFloat()
            res = (res * 10).toInt() / 10f
            if (unb > 0) res *= unb - 1
        }
        return listOf("Fireworks: ${ServerInfo.count(Items.FIREWORK_ROCKET)}", "Durability: $durability", "Res (km): $res",
            "Elytras: ${ServerInfo.count(Items.ELYTRA)}", "XZ Speed: ${(ServerInfo.horizontalSpeed * 72f * 10f).toInt() / 10f}",
            "Y Speed: ${((p.y - p.yo) * 72f * 10f).toInt() / 10f}")
    }

    /**
     * The new look (hud.css .flight): a compass ring with a needle along your heading (letters: X up, Z right), a slim
     * pitch track, and the stats.
     */
    private fun renderModern(yaw: Float, pitch: Float, lines: List<String>) {
        plate(left, top, 120f, 55f, batched = false)
        val cx = left + 4.5f + 23f
        val cy = top + 27.5f
        Ui.box(cx - 23f, cy - 23f, 46f, 46f, 23f, 0, 0.5f, 0x26FFFFFF)
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.rotateAbout(Math.toRadians((yaw + 90f).toDouble()).toFloat(), cx, cy)
        Canvas.box(cx - 0.75f, cy - 19f, 1.5f, 19f, 0.75f, Tok.a1, Tok.a1, Tok.a1 and 0xFFFFFF, Tok.a1 and 0xFFFFFF)
        pose.popMatrix()
        val l = HudType.letter
        ctext("X", cx - l.width("X") / 2f, cy - 23f + 4.75f, Tok.T3, l)
        ctext("x", cx - l.width("x") / 2f, cy + 23f - 4.75f, Tok.T3, l)
        ctext("Z", cx + 23f - 3f - l.width("Z"), cy, Tok.T3, l)
        ctext("z", cx - 23f + 3f, cy, Tok.T3, l)
        // pitch: -90 (up) at the top of the track, 90 at the bottom
        val px = left + 54.5f
        Ui.box(px, cy - 23f, 2f, 46f, 1f, 0x14FFFFFF)
        Ui.box(px, cy - 23f + (pitch / 90f * 0.5f + 0.5f) * 42f, 2f, 4f, 1f, Tok.a1)
        var ly = cy - (lines.size - 1) * 4.5f
        for (s in lines) {
            ctext(s, left + 60.5f, ly, Tok.T2, HudType.small)
            ly += 9f
        }
    }
}

/**
 * A top-down outline of the block a hitbox corner is inside, turned with the player's yaw, with the
 * hitbox in it (red when a corner is free). In the editor it shows the block you stand in.
 * The new look (hud.css .phase): a plate with the block as a 32-unit outline and your hitbox in the
 * theme colour.
 */
object PhaseWidget : HudWidget("phase-widget") {
    private var block: BlockPos? = null
    private var free = false

    override fun tick() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        val bb = p.boundingBox
        val eps = 1.0E-5f
        free = false
        block = if (editorMode) BlockPos.containing(p.position()) else null
        val corners = listOf(
            BlockPos.containing(bb.minX, bb.minY + 0.3, bb.minZ),
            BlockPos.containing(bb.minX, bb.minY + 0.3, bb.maxZ - eps),
            BlockPos.containing(bb.maxX - eps, bb.minY + 0.3, bb.minZ),
            BlockPos.containing(bb.maxX - eps, bb.minY + 0.3, bb.maxZ - eps),
        )
        for (c in corners) {
            if (!level.getBlockState(c).canBeReplaced()) block = c else free = true
        }
    }

    override fun render() {
        val f = block ?: return
        val p = mc.player ?: return
        plate(left, top, 50f, 50f, batched = false)
        val b = 32f
        val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
        val px = p.xo + (p.x - p.xo) * pt
        val pz = p.zo + (p.z - p.zo) * pt
        val bx = left + 9f
        val by = top + 9f
        val dx = (px.toFloat() - (f.x + 0.5f) + 0.2f) * b
        val dz = (pz.toFloat() - (f.z + 0.5f) + 0.2f) * b
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.rotateAbout(Math.toRadians(-p.yRot + 180.0).toFloat(), left + 25f, top + 25f)
        Ui.box(bx, by, b, b, 1.5f, 0, 0.75f, 0x99FFFFFF.toInt())
        val c = if (free) 0xFFFF5D5D.toInt() else Tok.a1
        Ui.glow(bx + dx, by + dz, b * 0.6f, b * 0.6f, 0f, 5f, Colors.withAlpha(c, 0.45f))
        Ui.box(bx + dx, by + dz, b * 0.6f, b * 0.6f, 0f, 0, 0.75f, c)
        pose.popMatrix()
        size(50f, 50f)
    }
}

/**
 * The media playing on the computer. Reading it needs a native library; without one
 * nothing plays, so only the placeholder shows, in chat and the Hud editor.
 */
object MediaPlayerWidget : HudWidget("media-player-widget") {
    private val show = Animation(Easing.OUT_BACK, 250)

    override fun render() {
        show.update(if (editorMode) 1f else 0f)
        size(120f, 43f)
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(left + 60, top + 21.5f)
        val s = show.value.coerceIn(0f, 2f)
        pose.scale(s, s)
        pose.translate(-(left + 60), -(top + 21.5f))
        if (editorMode) {
            // The new look (hud.css .media): an art tile and what's playing
            plate(left, top, 120f, 43f, 1f, batched = false)
            Ui.dbox(left + 5.5f, top + 5.5f, 32f, 32f, 4f, 0x14FFFFFF, 0x05FFFFFF)
            Ui.icon("sound", left + 14.5f, top + 14.5f, 14f, Tok.T4)
            ctext("Nothing playing", left + 43.5f, top + 14.5f, Tok.T1, HudType.media)
            ctext("Media from your computer", left + 43.5f, top + 23f, Tok.T3, HudType.small)
            ctext("shows here", left + 43.5f, top + 30f, Tok.T3, HudType.small)
        }
        pose.popMatrix()
    }
}
