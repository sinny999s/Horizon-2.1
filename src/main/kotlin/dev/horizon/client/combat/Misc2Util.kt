package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerDeathEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.event.StopUsingItemEvent
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.misc.Notifier
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.level.chunk.LevelChunk
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

private val mc get() = Minecraft.getInstance()

/** Helpers the misc modules share: chat output, text flattening, and a packet-event gate. */
object Misc2Util {
    /** Plugs the always-on trackers below; called once when the Notifier module is created (at client start). */
    fun init() {
        ServerWatch.init()
        PopTracker.init()
    }

    /**
     * One legacy-formatted line, "§a> §7[§6Name§7] text" (so the text is gray
     * unless it has its own codes), shown on the game thread.
     */
    fun chat(module: Module, text: String) {
        val line = "${ChatFormatting.GREEN}> ${ChatFormatting.GRAY}[${ChatFormatting.GOLD}${module.name.trim()}${ChatFormatting.GRAY}] $text"
        val c = Component.nullToEmpty(line)
        if (mc.isSameThread) mc.player?.sendSystemMessage(c) else mc.executeIfPossible { mc.player?.sendSystemMessage(c) }
    }

    /**
     * ReceivePacket, fired only with a player and once per packet of a bundle. [fn] returns
     * whether to drop the packet; dropping any packet of a bundle drops the bundle.
     */
    inline fun received(e: ReceivePacketEvent, fn: (Packet<*>) -> Boolean) {
        if (Minecraft.getInstance().player == null) return
        val p = e.packet
        if (p is ClientboundBundlePacket) {
            for (sub in p.subPackets()) if (fn(sub)) e.cancel()
        } else if (fn(p)) {
            e.cancel()
        }
    }

    /** A component as a legacy string, a colour code wherever the colour changes, then "§r". */
    fun legacy(c: Component): String {
        val sb = StringBuilder()
        var last = Int.MIN_VALUE
        c.visit(FormattedText.StyledContentConsumer<Unit> { style, s ->
            if (s.isNotEmpty()) {
                val color = style.color?.let { it.value and 0xFFFFFF } ?: -1
                if (color != last) {
                    if (color == -1) sb.append("§r") else sb.append(colorCode(color))
                    last = color
                }
                sb.append(s)
            }
            Optional.empty()
        }, Style.EMPTY)
        return sb.append("§r").toString()
    }

    /** "§c" for a legacy colour, else "§x§r§r§g§g§b§b". */
    private fun colorCode(rgb: Int): String {
        val legacy = when (rgb) {
            0 -> '0'; 170 -> '1'; 43520 -> '2'; 43690 -> '3'; 11141120 -> '4'; 11141290 -> '5'; 16755200 -> '6'
            11184810 -> '7'; 5592405 -> '8'; 5592575 -> '9'; 5635925 -> 'a'; 5636095 -> 'b'; 16733525 -> 'c'
            16733695 -> 'd'; 16777045 -> 'e'; 16777215 -> 'f'
            else -> null
        }
        if (legacy != null) return "§$legacy"
        val sb = StringBuilder("§x")
        for (ch in String.format(Locale.ROOT, "%06x", rgb and 0xFFFFFF)) sb.append('§').append(ch)
        return sb.toString()
    }

    /** A sound at the world origin on the voice channel, heard everywhere because the files are stereo. */
    fun playVoice(event: SoundEvent) {
        mc.execute {
            mc.soundManager.play(SimpleSoundInstance(event, SoundSource.VOICE, 1f, 1f, SoundInstance.createUnseededRandom(), 0.0, 0.0, 0.0))
        }
    }

    /** The loaded chunks in the render-distance square (truncating division, x outer, z inner). */
    fun loadedChunks(): List<LevelChunk> {
        val list = ArrayList<LevelChunk>()
        val p = mc.player ?: return list
        val level = mc.level ?: return list
        val rd = mc.options.renderDistance().get()
        for (i in -rd..rd) for (j in -rd..rd) level.chunkSource.getChunkNow(p.x.toInt() / 16 + i, p.z.toInt() / 16 + j)?.let { list += it }
        return list
    }

}

/**
 * The server timing kept for the notifier: when the last packet and the last time packet
 * came, and when the server last moved us (a lag-back) other than after a chorus fruit or an ender pearl.
 */
object ServerWatch {
    private var lastPacket = 0L
    private var lastTimePacket = 0L
    @Volatile
    private var lagBack = -1L
    @Volatile
    private var teleport = -1L

    /** The last packet's time minus the last time packet's. */
    fun sinceTimePacket(): Long = lastPacket - lastTimePacket

    /** Ms since the last lag-back. */
    fun sinceLagBack(): Long = System.currentTimeMillis() - lagBack

    fun init() {
        EventBus.on<ReceivePacketEvent>(this) { e ->
            Misc2Util.received(e) { p ->
                packet(p)
                false
            }
        }
        EventBus.on<SendPacketEvent>(this) { e ->
            val p = mc.player ?: return@on
            if (e.packet is ServerboundUseItemPacket && p.useItem.`is`(Items.CHORUS_FRUIT)) teleport = System.currentTimeMillis()
        }
        EventBus.on<StopUsingItemEvent>(this) {
            if (mc.player?.useItem?.`is`(Items.CHORUS_FRUIT) == true) teleport = System.currentTimeMillis()
        }
    }

    private fun packet(p: Packet<*>) {
        val now = System.currentTimeMillis()
        // A time packet in the player's first 20 ticks (after the first one ever) updates neither time.
        if (p is ClientboundSetTimePacket) {
            if (lastTimePacket != 0L && (mc.player?.tickCount ?: 20) < 20) return
            lastTimePacket = now
        }
        lastPacket = now
        if (p is ClientboundPlayerPositionPacket && now - teleport - 10000L > 0 && (mc.player?.tickCount ?: 0) > 20) lagBack = now
        if (p is ClientboundSoundPacket && p.sound.value() == SoundEvents.ENDER_PEARL_THROW) teleport = now
    }
}

/**
 * Totem pops per player name (counted as the packet arrives, the local player's never), and deaths of
 * other players seen in the level, which post [PlayerDeathEvent] and reset that player's count.
 */
object PopTracker {
    private val pops = ConcurrentHashMap<String, Int>()
    private val recentlyDead = LinkedHashMap<String, Long>()

    fun pops(name: String): Int = pops.getOrDefault(name, 0)

    fun init() {
        EventBus.on<ReceivePacketEvent>(this, 10) { e ->
            Misc2Util.received(e) { p ->
                if (p is ClientboundEntityEventPacket && p.eventId.toInt() == 35) pop(p)
                false
            }
        }
        EventBus.on<PlayerUpdateEvent>(this, 10) { deaths() }
    }

    private fun pop(p: ClientboundEntityEventPacket) {
        val e = p.getEntity(mc.level ?: return) as? Player ?: return
        if (e === mc.player) return
        val name = e.name.string
        pops[name] = pops.getOrDefault(name, 0) + 1
        val notifier = Modules.get<Notifier>()
        if (!notifier.enabled) return
        notifier.onPop(name, pops.getOrDefault(name, 0))
    }

    private fun deaths() {
        val level = mc.level ?: return
        val dead = level.players().filter { p ->
            !(p.health > 0f && p.isAlive && !p.isDeadOrDying) && p !== mc.player && p.name.string !in recentlyDead
        }
        for (p in dead) {
            val name = p.name.string
            EventBus.post(PlayerDeathEvent(p, pops.getOrDefault(name, 0)))
            recentlyDead[name] = System.currentTimeMillis()
            pops.remove(name)
        }
        recentlyDead.entries.removeIf { System.currentTimeMillis() - it.value > 1000L }
    }
}
