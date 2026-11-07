package dev.horizon.client.module.esp

import dev.horizon.client.combat.GlassPlates
import dev.horizon.client.combat.Projection
import dev.horizon.client.combat.legacyText
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.config.Friends
import dev.horizon.client.event.MouseButtonEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.RenderNameTagEvent
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.lunar.LunarNameTag
import dev.horizon.client.mixin.esp1.BaseSpawnerAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.legit.Teams
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Icon
import dev.horizon.client.render.World3D
import dev.horizon.client.setting.GroupMode
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.numbers.StyledFormat
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.animal.equine.Horse
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.entity.SpawnerBlockEntity
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.ScoreHolder
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import net.minecraft.world.phys.Vec3
import org.joml.Vector4d
import java.awt.Color
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The original's "name-tags" (`e6`): instead of vanilla's player name tags, a glass plate over each player (farthest first)
 * with ping, gamemode, name, health, distance and totem pops; above it the armour and hand items (or their durability)
 * with enchant abbreviations, potion effects, a held shulker's contents, and health as hearts or dots. With chat open,
 * clicking a tag adds or removes that player as a friend. Spawners and pet/projectile owners can be tagged too.
 */
class NameTags : Module("name-tags", Categories.ESP) {
    enum class NameMode { DEFAULT, FULL, SEMI }
    @Suppress("EnumEntryName")
    enum class ArmorMode { None, Full, Durability }
    @Suppress("EnumEntryName")
    enum class Health { Number, Hearts, Dots }
    @Suppress("EnumEntryName")
    enum class HealthProvider { Entity, ScoreBoard, PlayerList }

    val nameMode = enum("name-mode", NameMode.FULL)
    val components = group("components", GroupMode.BOOLS)
    val gamemode = bool("gamemode", false, parent = components.value)
    val ping = bool("ping", false, parent = components.value)
    val hP = bool("h-p", true, parent = components.value)
    val distance = bool("distance", true, parent = components.value)
    val totemPops = bool("totem-pops", true, parent = components.value)
    val enchants = bool("enchants", true, parent = components.value)
    val onlyHandsEnchants = bool("only-hands-enchants", false, parent = components.value) { enchants.value }
    val potions = bool("potions", true, parent = components.value)
    val shulkers = bool("shulkers", true, parent = components.value)
    val selection = group("selection", GroupMode.BOOLS)
    val spawners = bool("spawners", false, parent = selection.value)
    val entityOwner = bool("entity-owner", false, parent = selection.value)
    val fill = color("fill", Color(0xFF969696.toInt(), true))
    val outline = color("outline", Color(0xFFFFFFFF.toInt(), true))
    val friendFill = color("friend-fill", Color(0xFF969696.toInt(), true))
    val friendOutline = color("friend-outline", Color(0xFF00FF00.toInt(), true))
    val armorMode = enum("armor-mode", ArmorMode.Full)
    val health = enum("health", Health.Number)
    val healthProvider = enum("health-provider", HealthProvider.Entity)

    private enum class HeartType(val full: Identifier, val half: Identifier) {
        CONTAINER(Identifier.withDefaultNamespace("hud/heart/container"), Identifier.withDefaultNamespace("hud/heart/container")),
        NORMAL(Identifier.withDefaultNamespace("hud/heart/full"), Identifier.withDefaultNamespace("hud/heart/half")),
        ABSORBING(Identifier.withDefaultNamespace("hud/heart/absorbing_full"), Identifier.withDefaultNamespace("hud/heart/absorbing_half")),
    }

    /** Enchant abbreviations; a HashMap as in the original, so their order on screen is the map's. */
    private val enchantNames = HashMap<ResourceKey<Enchantment>, String>().apply {
        put(Enchantments.BLAST_PROTECTION, "B")
        put(Enchantments.PROTECTION, "P")
        put(Enchantments.SHARPNESS, "S")
        put(Enchantments.EFFICIENCY, "E")
        put(Enchantments.UNBREAKING, "U")
        put(Enchantments.POWER, "PO")
        put(Enchantments.THORNS, "T")
    }

    /** Small capitals in names are drawn as capitals. */
    private val smallCaps = mapOf('ᴀ' to 'A', 'ʙ' to 'B', 'ᴄ' to 'C', 'ᴅ' to 'D', 'ᴇ' to 'E', 'ꜰ' to 'F', 'ɢ' to 'G', 'ʜ' to 'H',
        'ɪ' to 'I', 'ᴊ' to 'J', 'ᴋ' to 'K', 'ʟ' to 'L', 'ᴍ' to 'M', 'ɴ' to 'N', 'ᴏ' to 'O', 'ᴘ' to 'P', 'ꞯ' to 'Q', 'ʀ' to 'R',
        'ꜱ' to 'S', 'ᴛ' to 'T', 'ᴜ' to 'U', 'ᴠ' to 'V', 'ᴡ' to 'W', 'ʏ' to 'Y', 'ᴢ' to 'Z')

    private val friendIcon = Icon("add-friend", 15f, 15f)
    private val iconFade = Animation(Easing.OUT_QUINT, 300)
    private val plates = GlassPlates()
    private val friendPlates = GlassPlates()

    /** Lunar's logo and badge for one of this frame's tags, at the text's [x], the plate's [top], [w] wide. */
    private class LunarTag(val marks: LunarNameTag.Marks, val x: Float, val top: Float, val w: Float)
    private val lunarTags = ArrayList<LunarTag>()

    /** The player whose tag the mouse is over while a screen is open (the original's `L`), "" if none. */
    private var hovered = ""

    init {
        on<RenderNameTagEvent> { e -> if (e.state is AvatarRenderState) e.cancel() }
        on<Render2DEvent> { render() }
        on<MouseButtonEvent> { e ->
            if (hovered.isBlank() || mc.gui.screen() !is ChatScreen || e.action != 1) return@on
            if (Friends.isFriend(hovered)) {
                Friends.remove(hovered)
                moduleMessage(name.trim(), ChatFormatting.RED.toString() + hovered + ChatFormatting.GRAY + " was removed from friends!")
                return@on
            }
            Friends.add(hovered)
            moduleMessage(name.trim(), ChatFormatting.GREEN.toString() + hovered + ChatFormatting.GRAY + " was added to friends!")
        }
    }

    /** `e6.x`. */
    private fun render() {
        val g = Canvas.graphics ?: return
        val pose = Canvas.pose
        val screen = mc.gui.screen()
        iconFade.update(if (screen is ChatScreen || screen === dev.horizon.client.gui.modern.ModernHudEditor) 1.0f else 0.0f)
        hovered = ""
        val self = mc.player!!
        val players = mc.level!!.players().sortedWith { a, b ->
            self.position().distanceToSqr(b.position()).compareTo(self.position().distanceToSqr(a.position()))
        }
        val window = mc.window
        val mouseX = mc.mouseHandler.getScaledXPos(window).toFloat()
        val mouseY = mc.mouseHandler.getScaledYPos(window).toFloat()
        for (p in players) {
            if (p == self && mc.options.cameraType.isFirstPerson) continue
            val v = above(p, if (health.value == Health.Number) 0.4f else 0.55f) ?: continue
            val text = tagText(p)
            // Not in the original: room for Lunar's logo and badge (Lunar Spoof), drawn after the plates.
            val lunar = LunarNameTag.marks(p.uuid)
            val lead = lunar?.lead ?: 0
            val w = Fonts.bold8.width(text) + lead + (lunar?.tail ?: 0)
            val x = (v.x + (v.z - v.x).toFloat() / 2.0f - w / 2.0f).toFloat()
            if (x.isNaN()) continue
            var offset = items(p, v.x, v.y)
            val top = (v.y - 13.0f).toFloat()
            val over = mouseX >= x - 2 && mouseX <= x - 2 + w + 4 && mouseY >= top && mouseY <= top + 12
            if (over && screen != null) hovered = p.gameProfile.name()
            (if (Friends.isFriend(p.gameProfile.name()) || Teams.isEspAlly(p)) friendPlates else plates).add(x - 2, top, w + 4, 12.0f, if (over && screen != null) 0.5f else 1.0f)
            Fonts.bold8.queue(text, x + lead, v.y.toFloat() - 13.0f, -1)
            if (lunar != null) lunarTags += LunarTag(lunar, x, top, w)
            if (health.value != Health.Number) {
                pose.pushMatrix()
                hearts(v.x, v.y, p, p.getAttributeValue(Attributes.MAX_HEALTH).toFloat(), Mth.ceil(p.health), Mth.ceil(p.absorptionAmount))
                pose.popMatrix()
            }
            if (potions.value) offset -= effects(v.x.toFloat(), (v.y + offset - 55).toFloat(), p)
            if (shulkers.value && screen == null) {
                val tx = v.x.toFloat() - 97.0f
                val ty = v.y.toFloat() + offset - 20.0f
                pose.translate(tx, ty)
                val main = p.mainHandItem
                val item = main.item
                shulker(if (item is BlockItem && item.block is ShulkerBoxBlock) main else p.offhandItem)
                pose.translate(-tx, -ty)
            }
            if (screen is ChatScreen || screen === dev.horizon.client.gui.modern.ModernHudEditor || iconFade.value != 0.0f) {
                friendIcon.draw((x - 18.0).toFloat(), (v.y - 16.0).toFloat(), alpha = iconFade.value)
            }
            pose.popMatrix()
        }
        if (spawners.value) spawners()
        if (entityOwner.value) owners()
        plates.flush(fill.value.rgb, outline.value.rgb)
        friendPlates.flush(friendFill.value.rgb, friendOutline.value.rgb)
        for (t in lunarTags) LunarNameTag.drawPlate(t.marks, t.x, t.top, t.w)
        lunarTags.clear()
        Fonts.bold8.flush()
        Fonts.medium75.flush()
    }

    /** The original's `sf.H`: the screen box ([Projection.box]) of the entity's interpolated eyes plus [up]. */
    private fun above(e: Entity, up: Float): Vector4d? {
        val pt = World3D.partialTick.toDouble()
        return Projection.box(Vec3(Mth.lerp(pt, e.xo, e.x), Mth.lerp(pt, e.yo, e.y) + e.getEyeHeight(e.pose) + up, Mth.lerp(pt, e.zo, e.z)))
    }

    /** `e6.w`: the tag's text. (the original's marks for the original users came from its server; there are none here.) */
    private fun tagText(p: Player): String {
        var s = ""
        if (ping.value) {
            val ms = mc.connection?.getPlayerInfo(p.uuid)?.latency ?: 0
            s += pingColor(ms) + ms + "ms " + ChatFormatting.WHITE
        }
        if (gamemode.value) s += gameModeText(mc.connection?.getPlayerInfo(p.uuid)?.gameMode) + " "
        // Not in the original: an Apollo server's own name tag lines replace the name.
        val apollo = dev.horizon.client.lunar.ApolloFeatures.nametag(p.uuid)
        val name = if (!apollo.isNullOrEmpty()) apollo.joinToString("  ") else when (nameMode.value) {
            NameMode.FULL -> legacyText(p.displayName)
            NameMode.SEMI -> p.displayName.string
            NameMode.DEFAULT -> p.gameProfile.name()
        }
        s += name.map { smallCaps[it] ?: it }.joinToString("") + " "
        if (hP.value && health.value == Health.Number) {
            val hp = healthOf(p)
            s += hpColor(hp) + round1(hp) + " " + ChatFormatting.WHITE
        }
        if (distance.value) s += String.format("%.1f", mc.player!!.distanceTo(p)) + "m "
        if (totemPops.value) {
            val pops = ServerInfo.pops(p)
            if (pops != 0) s += ChatFormatting.RESET.toString() + pops
        }
        return s
    }

    /** `e6.D(int)`: green when their ping is above yours, yellow within 15 ms of it, red otherwise. */
    private fun pingColor(ms: Int): String = when {
        ms > ServerInfo.ping -> ChatFormatting.GREEN.toString()
        Math.abs(ms - ServerInfo.ping) < 15 -> ChatFormatting.YELLOW.toString()
        else -> ChatFormatting.RED.toString()
    }

    /** `e6.m(GameType)`. "[S]" for survival is inferred (it is an encrypted constant); no tab entry reads as a bot. */
    private fun gameModeText(g: GameType?): String = when (g) {
        null -> "[BOT]"
        GameType.SURVIVAL -> "[S]"
        GameType.CREATIVE -> "[C]"
        GameType.SPECTATOR -> "[SP]"
        GameType.ADVENTURE -> "[A]"
    }

    /** `e6.O`. */
    private fun hpColor(hp: Float): String =
        if (hp > 15.0f) ChatFormatting.GREEN.toString() else if (hp > 7.0f) ChatFormatting.YELLOW.toString() else ChatFormatting.RED.toString()

    /** `e6.W`. */
    private fun hpColorOf(hp: Float): Int = if (hp > 15.0f) Color.GREEN.rgb else if (hp > 7.0f) Color.YELLOW.rgb else Color.RED.rgb

    /** `e6.m(float)`: to one decimal, half up (NaN shows 1.0). */
    private fun round1(f: Float): Float = when {
        f.isNaN() -> 1.0f
        f.isInfinite() -> f
        else -> BigDecimal(f.toDouble()).setScale(1, RoundingMode.HALF_UP).toFloat()
    }

    /** `e6.y`: the health the [healthProvider] reports. */
    private fun healthOf(p: Player): Float = when (healthProvider.value) {
        HealthProvider.ScoreBoard -> {
            val board = p.level().scoreboard
            var text = ""
            val objective = board.getDisplayObjective(DisplaySlot.BELOW_NAME)
            if (objective != null) {
                text = board.getPlayerScoreInfo(p, objective)?.formatValue(objective.numberFormatOrDefault(StyledFormat.NO_STYLE))?.string ?: ""
            }
            text.toFloatOrNull() ?: 0.0f
        }
        HealthProvider.PlayerList -> {
            val board = mc.level!!.scoreboard
            val objective = board.getDisplayObjective(DisplaySlot.LIST)
            if (objective?.renderType == ObjectiveCriteria.RenderType.HEARTS) {
                board.getPlayerScoreInfo(ScoreHolder.fromGameProfile(p.gameProfile), objective)?.value()?.toFloat() ?: 0.0f
            } else 0.0f
        }
        HealthProvider.Entity -> p.health + p.absorptionAmount
    }

    /**
     * `e6.H`: offhand, armour (head first) and main hand 18 apart above the tag (armour only with Durability), as items
     * or durability percentages, with enchant abbreviations stacked above each. Leaves a matrix pushed (popped after the
     * tag). Returns how far up the enchant columns reach (≤ 0).
     */
    private fun items(p: Player, x: Double, y: Double): Float {
        val g = Canvas.graphics!!
        val pose = Canvas.pose
        val list = ArrayList<ItemStack>()
        if (armorMode.value != ArmorMode.Durability) list += p.offhandItem
        val inv = p.inventory
        list += inv.getItem(39)
        list += inv.getItem(38)
        list += inv.getItem(37)
        list += inv.getItem(36)
        if (armorMode.value != ArmorMode.Durability) list += p.mainHandItem
        pose.pushMatrix()
        var top = 0.0f
        var dx = 0.0f
        if (armorMode.value == ArmorMode.None) return top
        val registry = mc.level!!.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
        for (stack in list) {
            if (!stack.isEmpty) {
                if (armorMode.value == ArmorMode.Full) {
                    pose.pushMatrix()
                    pose.translate(x.toFloat() - 55.0f + dx, y.toFloat() - 33.0f)
                    pose.scale(1.1f, 1.1f)
                    g.item(stack, 0, 0)
                    g.itemDecorations(mc.font, stack, 0, 0)
                    pose.popMatrix()
                } else {
                    val pct = ((stack.maxDamage - stack.damageValue).toFloat() / stack.maxDamage * 100.0f).toInt()
                    val c = if (pct < 33) Color.RED else if (pct in 34 until 66) Color.YELLOW else Color.GREEN
                    Fonts.medium75.queue("$pct%", x.toFloat() - 35.0f + dx, y.toFloat() - 22.0f, c.rgb)
                }
                if (enchants.value && (!onlyHandsEnchants.value || stack === p.offhandItem || stack === p.mainHandItem)) {
                    val ench = EnchantmentHelper.getEnchantmentsForCrafting(stack)
                    var dy = 0.0f
                    for ((key, abbr) in enchantNames) {
                        val holder = registry.get(key.identifier()).orElse(null) ?: continue
                        if (!ench.keySet().contains(holder)) continue
                        Fonts.medium75.queue(abbr + ench.getLevel(holder), x.toFloat() - 50.0f + dx, y.toFloat() - 45 + dy, -1)
                        dy -= 8.0f
                        if (top > dy) top = dy
                    }
                }
            }
            dx += 18.0f
        }
        return top
    }

    /** `e6.I`: hearts (or dots) in one row of ten, containers first, absorption after the health. */
    private fun hearts(x: Double, y: Double, p: Player, maxHealth: Float, hp: Int, absorption: Int) {
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(x.toFloat() - 44.0f, y.toFloat() + 1.0f)
        pose.scale(1.1f, 1.1f)
        val maxHearts = Mth.ceil(maxHealth / 2.0)
        val absHearts = Mth.ceil(absorption / 2.0)
        val maxHp = maxHearts * 2
        var containers = 0
        for (i in maxHearts + absHearts - 1 downTo 0) {
            val hx = i % 10 * 8
            if (containers < 10) {
                heart(HeartType.CONTAINER, hx, false, p)
                containers++
            }
            val v = i * 2
            if (v < hp) heart(HeartType.NORMAL, hx, v + 1 == hp, p)
            if (i >= maxHearts && v - maxHp < absorption) {
                pose.pushMatrix()
                heart(HeartType.ABSORBING, hx, v - maxHp + 1 == absorption, p)
                pose.popMatrix()
            }
        }
        pose.popMatrix()
    }

    /** `e6.D`: one heart sprite, or with Dots a 7×3 bar (half: 3 in the health colour, 4 in the plate colour). */
    private fun heart(type: HeartType, x: Int, half: Boolean, p: Player) {
        if (health.value == Health.Dots) {
            val c = (if (Friends.isFriend(p.gameProfile.name())) friendFill else fill).value.rgb
            when (type) {
                HeartType.CONTAINER -> Canvas.quad(x.toFloat(), 0.0f, 7.0f, 3.0f, c)
                HeartType.NORMAL -> if (half) {
                    Canvas.quad(x.toFloat(), 0.0f, 3.0f, 3.0f, hpColorOf(p.health + p.absorptionAmount))
                    Canvas.quad(x + 3.0f, 0.0f, 4.0f, 3.0f, c)
                } else Canvas.quad(x.toFloat(), 0.0f, 7.0f, 3.0f, hpColorOf(p.health + p.absorptionAmount))
                HeartType.ABSORBING -> {}
            }
            return
        }
        Canvas.graphics!!.blitSprite(RenderPipelines.GUI_TEXTURED, if (half) type.half else type.full, x, 0, 9, 9)
    }

    /** `e6.Q`: the effects, strongest last, 25 apart and centred, each with its time and level. Returns 40 if any. */
    private fun effects(x: Float, y: Float, p: Player): Float {
        val list = ArrayList(p.activeEffects)
        if (list.isEmpty()) return 0.0f
        var ex = x + list.size * 12.5f
        val pose = Canvas.pose
        val g = Canvas.graphics!!
        for (e in list.sortedDescending()) {
            ex -= 25.0f
            val level = when (e.amplifier) {
                0 -> "I"; 1 -> "II"; 2 -> "III"; 3 -> "IV"; 4 -> "V"; else -> ""
            }
            pose.pushMatrix()
            pose.translate(ex, y)
            // The icon's own texture: 26.3 has no GUI sprite under the effect's id (that drew the missing texture).
            val id = e.effect.unwrapKey().orElseThrow().identifier()
            g.blit(RenderPipelines.GUI_TEXTURED, id.withPath { "textures/mob_effect/$it.png" }, 0, 0, 0f, 0f, 18, 18, 18, 18, -1)
            val time = duration(e)
            Fonts.medium75.queue(time, 9.0f - Fonts.medium75.width(time) / 2.0f, -8.0f, -1)
            Fonts.medium75.queue(level, 9.0f - Fonts.medium75.width(level) / 2.0f, -16.0f, -1)
            pose.popMatrix()
        }
        return 40.0f
    }

    /** `e6.g`: "m:ss", or "*:*" for an infinite effect. */
    private fun duration(e: MobEffectInstance): String =
        if (e.isInfiniteDuration) "*:*" else (e.duration / 1200).toString() + ":" + String.format("%02d", e.duration % 1200 / 20)

    /** `e6.v`: a held shulker's contents on a plate, nine to a row. */
    private fun shulker(stack: ItemStack) {
        val contents = stack.get(DataComponents.CONTAINER) ?: return
        val item = stack.item
        if (item !is BlockItem || item.block !is ShulkerBoxBlock) return
        val list = contents.itemCopies().toList()
        if (list.isEmpty()) return
        plates.add(12.0f, -79.0f, 169.0f, 60.0f)
        val g = Canvas.graphics!!
        var row = 0
        var col = 0
        for (s in list) {
            g.item(s, 16 + col * 18, -75 + row * 18)
            g.itemDecorations(mc.font, s, 16 + col * 18, -75 + row * 18)
            col++
            if (col >= 9) {
                col = 0
                row++
            }
        }
    }

    /** `e6.Z`: each loaded spawner: its mob and seconds to the next spawn ("loot!" when idle with a second left). */
    private fun spawners() {
        val level = mc.level!!
        for (be in ServerInfo.blockEntities) {
            if (be !is SpawnerBlockEntity) continue
            val pos = be.blockPos
            val v = Projection.box(Vec3(pos.x + 0.5, pos.y.toDouble() + 1.5f, pos.z + 0.5)) ?: continue
            val spawner = be.spawner
            val mob = spawner.getOrCreateDisplayEntity(level, pos) ?: continue
            val delay = (spawner as BaseSpawnerAccessor).horizon_getSpawnDelay()
            var text = mob.name.string + " " + String.format("%.1f", delay / 20.0f) + "s"
            if (spawner.spin == spawner.oSpin && spawner.spin == 0.0 && delay / 20.0f - 1.0f == 0.0f) text = mob.name.string + " loot!"
            val w = Fonts.bold8.width(text)
            val x = (v.x + (v.z - v.x).toFloat() / 2.0f - w / 2).toFloat()
            plates.add(x - 2, (v.y - 13.0f).toFloat(), w + 4, 11.0f)
            Fonts.bold8.queue(text, x, v.y.toFloat() - 12.5f, -1)
        }
    }

    /** `e6.G`: "by <owner>" over projectiles, horses (owner's UUID) and tamed animals. */
    private fun owners() {
        for (e in mc.level!!.entitiesForRendering()) {
            val owner = when (e) {
                is Projectile -> e.owner?.displayName?.string ?: ""
                is Horse -> (e as OwnableEntity).owner?.uuid?.toString() ?: ""
                is TamableAnimal -> if (e.isTame) e.owner?.displayName?.string ?: "" else ""
                else -> ""
            }
            if (owner.isEmpty()) continue
            val v = above(e, 0.4f) ?: continue
            val text = ChatFormatting.GRAY.toString() + "by " + ChatFormatting.RESET + " " + owner
            val w = Fonts.bold8.width(text)
            val x = ((v.x + (v.z - v.x).toFloat() / 2 - w / 2) * 1).toFloat()
            plates.add(x - 2, (v.y - 13.0f).toFloat(), w + 4, 12.0f)
            Fonts.bold8.queue(text, x, v.y.toFloat() - 12.0f, -1)
        }
    }
}
