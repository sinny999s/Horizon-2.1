package dev.horizon.client.module.misc

import dev.horizon.client.HorizonClient
import dev.horizon.client.combat.Misc2Util
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.ServerWatch
import dev.horizon.client.config.Storage
import dev.horizon.client.event.EntityRemoveEvent
import dev.horizon.client.event.EntitySpawnEvent
import dev.horizon.client.event.PlayerDeathEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Icon
import net.minecraft.ChatFormatting
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
import net.minecraft.world.level.chunk.LevelChunk
import java.awt.Color
import java.io.BufferedWriter
import java.io.FileWriter
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Chat lines and notifications for pops, deaths, players entering and leaving, splash
 * potions landing on players, items picked up and eaten, effects running out, mentions and stashes, plus the flashing
 * top-centre warnings (air alarm, low armour, server not responding, lag-back). Sounds play only with Assistant on,
 * except the stash one. Pops and deaths come from the pop tracker ([dev.horizon.client.combat.PopTracker]).
 */
class Notifier : Module("notifier", Categories.MISC) {
    enum class PastEffects { OFF, CHAT, NOTIFICATION, BOTH }

    val totemPops = bool("totem-pops", false)
    val lowArmor = bool("low-armor", false)
    val rangeEnter = bool("range-enter", false)
    val rangeLeave = bool("range-leave", false)
    val lag = bool("lag", false)
    val rubberBand = bool("rubber-band", false)
    val stash = bool("stash", false)
    val minChests = int("min-chests", 5, 0..100) { stash.value }
    val minShulkers = int("min-shulkers", 0, 0..100) { stash.value }
    val mention = bool("mention", false)
    val pastEffects = enum("past-effects", PastEffects.BOTH)
    val splashBuff = enum("splash-buff", PastEffects.BOTH)
    val ftBypass = bool("ft-bypass", true) { splashBuff.value != PastEffects.OFF }
    val self = bool("self", false) { splashBuff.value != PastEffects.OFF }
    val pre121 = bool("pre-1.21", true) { splashBuff.value != PastEffects.OFF }
    val itemCollect = enum("item-collect", PastEffects.BOTH)
    val onlyDon = bool("only-don", false) { itemCollect.value != PastEffects.OFF }
    val useTracker = enum("use-tracker", PastEffects.BOTH)

    /** Offhand's on-mace sets this; the air-alarm warning shows while it's set and [airAlarmTimer] was reset < 3 s ago. */
    @Volatile
    var airAlarm = false
    val airAlarmTimer = MsTimer()

    private val armorIcon = Icon("armor", 30f, 30f)
    private val emptyArmorIcon = Icon("empty-armor", 30f, 30f)
    private val wifiIcon = Icon("wifi", 30f, 30f)
    private val wifiOffIcon = Icon("wifi-off", 30f, 30f)
    private val rewindIcon = Icon("rewind", 30f, 30f)
    private val loudspeakerIcon = Icon("loudspeaker", 30f, 30f)
    private val loudspeakerEmptyIcon = Icon("loudspeaker-empty", 30f, 30f)

    private val lagSoundTimer = MsTimer()
    private val lagBackSoundTimer = MsTimer()
    private val armorSoundTimer = MsTimer()
    private val mentionSoundTimer = MsTimer()
    private val popSoundTimer = MsTimer()
    private val chatSentTimer = MsTimer()
    private val rangeSoundTimer = MsTimer()
    private val stashSoundTimer = MsTimer()

    /** Chunks already reported as stashes. */
    private val stashes = ArrayList<LevelChunk>()

    /** The local player's effects last tick. */
    private val lastEffects = ArrayList<MobEffectInstance>()

    /** What a player started eating or drinking, by name. */
    private class Use(val itemName: String, val stack: ItemStack, val time: Long)

    private val uses = ConcurrentHashMap<String, Use>()

    /** The colour of the last splash-potion particles, for [funTimePotion]. */
    @Volatile
    private var splashColor = 0

    init {
        Misc2Util.init()
        on<Render2DEvent> { warnings() }
        on<SendPacketEvent> { e ->
            if (mc.player != null && e.packet is ServerboundChatPacket) chatSentTimer.reset()
        }
        on<ReceivePacketEvent> { e ->
            Misc2Util.received(e) { p ->
                received(p)
                false
            }
        }
        on<EntitySpawnEvent> { e -> entered(e.entity) }
        on<EntityRemoveEvent> { e -> removed(e) }
        on<PlayerDeathEvent> { e ->
            if (!totemPops.value) return@on
            Misc2Util.chat(this, e.player.name.string + " " + String.format("died after popping %d totem(s)!", e.totemsPopped))
        }
        on<PlayerUpdateEvent> { tick() }
    }


    /** A player popped a totem ([count] so far); the pop sound at most every 3 s. */
    fun onPop(name: String, count: Int) {
        if (!totemPops.value) return
        Misc2Util.chat(this, name + " " + String.format("popped %d totem(s)!", count))

    }

    private fun warnings() {
        val p = mc.player ?: return
        if (!airAlarmTimer.passed(3000) && airAlarm) warning(loudspeakerIcon, loudspeakerEmptyIcon, "WARNING!", "Air alarm!")
        if (lowArmor.value) {
            for (s in listOf(p.inventory.getItem(39), p.inventory.getItem(38), p.inventory.getItem(37), p.inventory.getItem(36))) {
                if (s.isEmpty || durability(s) >= 20 || s.maxDamage == 0) continue
                warning(armorIcon, emptyArmorIcon, "Be careful!", "Your armor is almost broken")

                return
            }
        }
        if (lag.value && ServerWatch.sinceTimePacket() - 5000L > 0 && !mc.isLocalServer && p.tickCount > 60) {
            warning(wifiIcon, wifiOffIcon, "Server Lag", String.format("No response for %s", seconds(ServerWatch.sinceTimePacket())))

            return
        }
        if (!rubberBand.value) return
        if (ServerWatch.sinceLagBack() - 3000L >= 0) return
        if (p.tickCount > 60) {
            warning(rewindIcon, rewindIcon, "LagBack detected!", String.format("time elapsed: %s", seconds(ServerWatch.sinceLagBack())))

        }
    }

    private fun seconds(ms: Long) = String.format("%.1f", ms.toFloat() / 1000f)

    /** Durability left in percent. */
    private fun durability(s: ItemStack) = ((s.maxDamage - s.damageValue) / max(0.1, s.maxDamage.toDouble()) * 100.0).toInt()

    /**
     * [title] and [text] left-aligned below the top centre, pulsing between red and orange, the icon
     * to the right flipping between [a] and [b] with the pulse.
     */
    private fun warning(a: Icon, b: Icon, title: String, text: String) {
        val tw = max(Fonts.medium95.width(text), Fonts.semibold12.width(title))
        val x = mc.window.guiScaledWidth / 2f - tw / 2f - 17f
        val t = (System.currentTimeMillis() / 3.0 % 360).toInt()
        val tri = if (t < 180) t else 360 - t
        val col = Colors.lerp(Color.RED.rgb, Color.YELLOW.rgb, tri / 360f)
        Fonts.semibold12.draw(title, x, 8f, col)
        Fonts.medium95.draw(text, x, 20f, col)
        (if (tri > 90) a else b).draw(x + 5 + tw, 5f, primary = col)
    }

    /** Shows [msg] as a notification and/or in chat, as [mode] says. */
    private fun report(mode: PastEffects, msg: String, type: NotificationType, item: ItemStack?) {
        if (mode == PastEffects.NOTIFICATION || mode == PastEffects.BOTH) Notifications.post("", msg, 5f, type, item)
        if (mode == PastEffects.CHAT || mode == PastEffects.BOTH) Misc2Util.chat(this, msg)
    }

    /** The ReceivePacket handler (network thread). */
    private fun received(packet: Packet<*>) {
        try {
            val level by lazy(LazyThreadSafetyMode.NONE) { mc.level!! }
            val player = mc.player!!
            if (packet is ClientboundSetEntityDataPacket) {
                val e = level.getEntity(packet.id()) ?: return
                if (e !is Player || e == player) return
                for (dv in packet.packedItems()) {
                    // The living-entity flags: 1 = using the main hand, 3 = using the off-hand, anything else = stopped.
                    if (dv.id().toString() != "8") continue
                    val v = dv.value().toString()
                    if (v == "1" || v == "3") {
                        val s = e.getItemInHand(if (v == "3") InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND)
                        if (s.components.has(DataComponents.FOOD) || s.components.has(DataComponents.POTION_CONTENTS)) {
                            uses[Misc2Util.legacy(e.name)] = Use(Misc2Util.legacy(s.copy().hoverName), s.copy(), System.currentTimeMillis())
                        }
                        continue
                    }
                    val use = uses.remove(Misc2Util.legacy(e.name)) ?: return
                    if (System.currentTimeMillis() - use.time >= 1000L) {
                        report(useTracker.value, String.format("%s used %s", Misc2Util.legacy(e.name), use.itemName), NotificationType.INFO, use.stack)
                    }
                    val names = level.players().map { Misc2Util.legacy(it.name) }
                    uses.entries.removeIf { it.key !in names }
                }
            }
            if (packet is ClientboundLevelEventPacket && (packet.type == 2007 || packet.type == 2002)) {
                val d = packet.data
                val c = Color((d shr 16 and 0xFF) / 255f, (d shr 8 and 0xFF) / 255f, (d and 0xFF) / 255f)
                if (c.rgb == -13083194) return
                splashColor = c.rgb
            }
            if (mention.value && packet is ClientboundSystemChatPacket && chatSentTimer.passed(500)) {
                if (packet.content.string.contains(player.gameProfile.name)) {
                    Notifications.post(name, "you were mentioned in the chat!", 3f, NotificationType.WARNING)

                }
            }
            if (itemCollect.value != PastEffects.OFF && packet is ClientboundTakeItemEntityPacket) {
                val item = level.getEntity(packet.itemId)
                val collector = level.getEntity(packet.playerId)
                if (item !is ItemEntity || collector == null) return
                val s = item.item.copy()
                if (s.isEmpty) return
                if (onlyDon.value && !s.hoverName.string.lowercase(Locale.ROOT).contains("круш") && !s.hoverName.string.contains("★")) return
                val msg = if (collector.id == player.id) String.format("collected item: %s x%d", Misc2Util.legacy(s.hoverName), s.count)
                else String.format("%s collected item: %s x%d", Misc2Util.legacy(collector.name), Misc2Util.legacy(s.hoverName), s.count)
                report(itemCollect.value, msg, NotificationType.INFO, s)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun entered(e: net.minecraft.world.entity.Entity) {
        if (!rangeEnter.value || e !is Player || e == mc.player) return
        Misc2Util.chat(this, String.format("%s has entered your visual range!", e.name.string))

    }

    private fun removed(ev: EntityRemoveEvent) {
        val player = mc.player ?: return
        val e = ev.entity
        if (splashBuff.value != PastEffects.OFF && e is ThrownSplashPotion) {
            val level = mc.level ?: return
            for (target in level.players().filter { it != player || self.value }) {
                val d = if (pre121.value) e.distanceToSqr(target)
                else e.boundingBox.distanceToSqr(target.boundingBox.inflate(ProjectileUtil.computeMargin(e).toDouble()))
                if (d >= 16.0) continue
                val stack = if (ftBypass.value && Misc2Util.legacy(e.item.hoverName).lowercase(Locale.ROOT).contains("несоздаваемое"))
                    funTimePotion(splashColor) else e.item
                val ratio = 1.0 - sqrt(d) / 4.0
                val percent = (100 * ratio).toInt()
                val color = if (percent > 80) ChatFormatting.GREEN else if (percent > 50) ChatFormatting.YELLOW else ChatFormatting.RED
                val msg = String.format("%s got effects from %s %s%d", Misc2Util.legacy(target.name), Misc2Util.legacy(stack.hoverName), color.toString(), percent) + "%"
                val mode = splashBuff.value
                if (mode == PastEffects.NOTIFICATION || mode == PastEffects.BOTH) Notifications.post("", msg, 5f, NotificationType.WARNING, stack)
                if (mode != PastEffects.CHAT && mode != PastEffects.BOTH) continue
                Misc2Util.chat(this, msg)
                val contents = stack.get(DataComponents.POTION_CONTENTS) ?: return
                for (effect in contents.allEffects) {
                    val level0 = when (val n = effect.amplifier + 1) {
                        1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"; 6 -> "VI"; 7 -> "VII"; 8 -> "VIII"; 9 -> "IX"; 10 -> "X"
                        else -> n.toString()
                    }
                    val ticks = (effect.duration * ratio).toInt()
                    val time = if (effect.isInfiniteDuration) "*:*" else (ticks / 1200).toString() + ":" + String.format("%02d", ticks % 1200 / 20)
                    val harmful: Any = if (effect.effect.value().category == MobEffectCategory.HARMFUL) ChatFormatting.RED else ""
                    player.sendSystemMessage(Component.nullToEmpty("•" + harmful + " " + I18n.get(effect.effect.value().descriptionId) + " " + level0 + " " + ChatFormatting.RESET + time))
                }
            }
        }
        if (!rangeLeave.value || e !is Player || e == player) return
        Misc2Util.chat(this, String.format("%s has left your visual range!", e.name.string))

    }

    private fun tick() {
        val player = mc.player ?: return
        if (stash.value) {
            for (chunk in Misc2Util.loadedChunks()) {
                if (chunk in stashes) continue
                var chests = 0
                var shulkers = 0
                for (be in chunk.blockEntities.values.stream().toList()) {
                    if (be is ChestBlockEntity) chests++
                    if (be is ShulkerBoxBlockEntity) shulkers++
                }
                if (chests < minChests.value || shulkers < minShulkers.value) continue
                stashes += chunk
                val msg = "Stash: X: " + chunk.pos.middleBlockX + " Z: " + chunk.pos.middleBlockZ + " Chests: " + chests + " Shulkers: " + shulkers
                Notifications.post("Stash Logger", msg, 5f, NotificationType.SUCCESS)
                Misc2Util.chat(this, msg)
                if (stashSoundTimer.passedAndReset(5000)) Misc2Util.playVoice(FOUND_STASH)
                val server = mc.connection?.serverData?.ip?.replace(':', '_') ?: "unknown_server"
                try {
                    BufferedWriter(FileWriter(Storage.dir("stashes").resolve("$server.txt").toFile(), true)).use { it.append("\n" + msg) }
                } catch (_: Exception) {
                }
            }
        }
        if (pastEffects.value == PastEffects.OFF) return
        val now = player.activeEffects.map { it.descriptionId }.toSet()
        for (id in lastEffects.map { it.descriptionId } - now) {
            val msg = String.format("You've run out of %s", I18n.get(id))
            val mode = pastEffects.value
            if (mode == PastEffects.NOTIFICATION || mode == PastEffects.BOTH) Notifications.post(name, msg, 2f, NotificationType.WARNING, Items.POTION.defaultInstance)
            if (mode == PastEffects.CHAT || mode == PastEffects.BOTH) Misc2Util.chat(this, msg)
        }
        lastEffects.clear()
        lastEffects.addAll(player.activeEffects)
    }

    /** FunTime's custom splash potions, told apart by their particle colour. */
    private fun funTimePotion(color: Int): ItemStack {
        val (effects, name) = when (color) {
            -16711936 -> listOf(MobEffectInstance(MobEffects.HEALTH_BOOST, 3600, 1), MobEffectInstance(MobEffects.INVISIBILITY, 18000, 0),
                MobEffectInstance(MobEffects.REGENERATION, 1200, 1), MobEffectInstance(MobEffects.RESISTANCE, 1200, 0)) to "§2§l[★]§r§a§l Зелье Победителя§r"
            -16711681 -> listOf(MobEffectInstance(MobEffects.JUMP_BOOST, 3600, 0), MobEffectInstance(MobEffects.SPEED, 3600, 2)) to
                "§3§l[★]§r§b§l Моча Флеша§r"
            -6684877 -> listOf(MobEffectInstance(MobEffects.POISON, 1000, 1), MobEffectInstance(MobEffects.SLOWNESS, 1800, 3),
                MobEffectInstance(MobEffects.WEAKNESS, 1800, 2), MobEffectInstance(MobEffects.WITHER, 600, 4)) to "§2§l[★]§r§a§l Серная кислота§r"
            -3407872 -> listOf(MobEffectInstance(MobEffects.RESISTANCE, 3600, 0), MobEffectInstance(MobEffects.STRENGTH, 1800, 3)) to
                "§4§l[★]§r§c§l Зелье Киллера§r"
            -65281 -> listOf(MobEffectInstance(MobEffects.HEALTH_BOOST, 900, 2), MobEffectInstance(MobEffects.REGENERATION, 900, 2)) to
                "§5§l[★]§r§d§l Зелье Медика§r"
            -39424 -> listOf(MobEffectInstance(MobEffects.BLINDNESS, 200, 0), MobEffectInstance(MobEffects.GLOWING, 3600, 0),
                MobEffectInstance(MobEffects.HUNGER, 1800, 9), MobEffectInstance(MobEffects.SLOWNESS, 3600, 2),
                MobEffectInstance(MobEffects.WITHER, 600, 4)) to "§c§l[★]§r§6§l Зелье Отрыжки§r"
            -256 -> listOf(MobEffectInstance(MobEffects.FIRE_RESISTANCE, 18000, 0), MobEffectInstance(MobEffects.HASTE, 3600, 0),
                MobEffectInstance(MobEffects.INVISIBILITY, 18000, 0), MobEffectInstance(MobEffects.SPEED, 18000, 2),
                MobEffectInstance(MobEffects.STRENGTH, 6000, 2)) to "§6§l[★]§r§e§l Зелье агента§r"
            -1 -> listOf(MobEffectInstance(MobEffects.BLINDNESS, 400, 0), MobEffectInstance(MobEffects.GLOWING, 4800, 0)) to
                "§6§l[★]§r§e§l Вспышка§r"
            else -> return ItemStack(Items.SPLASH_POTION).apply { set(DataComponents.CUSTOM_NAME, Component.literal("§7Неизвестное зелье")) }
        }
        return ItemStack(Items.SPLASH_POTION).apply {
            set(DataComponents.POTION_CONTENTS, PotionContents(Optional.empty(), Optional.empty(), effects, Optional.empty()))
            set(DataComponents.CUSTOM_NAME, Component.literal(name))
        }
    }

    private companion object {
        fun sound(name: String): SoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(HorizonClient.ID, name))

        val TOTEM_POP = sound("totem-pop")
        val ARMOR_ALERT = sound("armor-alert")
        val LAG = sound("lag")
        val LAG_BACK = sound("lag-back")
        val CHAT_MENTION = sound("chat-mention")
        val RANGE_IN = sound("range-in")
        val RANGE_OUT = sound("range-out")
        val FOUND_STASH = sound("found-stash")
    }
}
