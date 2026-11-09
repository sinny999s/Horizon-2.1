package dev.horizon.client.module.misc

import com.mojang.blaze3d.Blaze3D
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.PopCounter
import dev.horizon.client.combat.moduleChat
import dev.horizon.client.combat.teamChatBypass
import dev.horizon.client.config.Storage
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.AutoCrystal
import dev.horizon.client.module.attack.Aura
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import java.io.File
import kotlin.random.Random

/**
 * When Aura's or AutoCrystal's target dies (entity events 3/60, or seen dead on a tick) a
 * random line of autoez.txt goes out, and when it pops a totem (event 35) one of autoez_totem.txt; at most one line per
 * 450 ms. "%name%" is the player, "%count%" their pops; a line starting with "/" is sent as a command.
 */
class AutoEz : Module("auto-ez", Categories.MISC) {
    enum class Mode { GLOBAL, LOCAL, WHISPERS, SILENT }

    // These live in the horizon folder with the rest of the data.
    private val deathFile get() = Storage.root.resolve("autoez.txt").toFile()
    private val popFile get() = Storage.root.resolve("autoez_totem.txt").toFile()

    val notationSetting = notation("notation", "%name% will be replaced with player name")
    val openDeath = action("open-death") { open(deathFile) }
    val openTotemPop = action("open-totem-pop") { open(popFile) }
    val mode = enum("mode", Mode.GLOBAL)
    val prefix = string("prefix", "msg") { mode.value == Mode.WHISPERS }

    private val timer = MsTimer()
    private var deathModified = 0L
    private var popModified = 0L
    private var deathLines: List<String> = emptyList()
    private var popLines: List<String> = emptyList()

    init {
        PopCounter.start()
        on<TickEvent> {
            val player = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (player.tickCount % 60 == 0 && deathFile.exists() && deathFile.lastModified() > deathModified) {
                deathLines = read(deathFile)
                deathModified = deathFile.lastModified()
            }
            if (player.tickCount % 60 == 0 && popFile.exists() && popFile.lastModified() > popModified) {
                popLines = read(popFile)
                popModified = popFile.lastModified()
            }
            for (p in level.players()) {
                if (isTarget(p) && (p.isDeadOrDying || p.health <= 0f)) say(p.gameProfile.name, deathLines)
            }
        }
        on<ReceivePacketEvent> { e ->
            val packet = e.packet as? ClientboundEntityEventPacket ?: return@on
            if (mc.player == null || mc.level == null) return@on
            val id = packet.eventId.toInt()
            if (id == 3 || id == 60) {
                val entity = packet.getEntity(mc.level ?: return@on)
                if (entity != null && isTarget(entity) && entity is Player) say(entity.name.tryCollapseToString().toString(), deathLines)
            }
            if (packet.eventId.toInt() == 35) {
                val entity = packet.getEntity(mc.level ?: return@on)
                if (entity != null && isTarget(entity)) say(entity.name.tryCollapseToString().toString(), popLines)
            }
        }
    }

    override fun onEnable() {
        deathLines = read(deathFile)
        popLines = read(popFile)
    }

    private fun isTarget(e: Entity) = e == Modules.get<AutoCrystal>().target || e == Modules.get<Aura>().target

    private fun say(name: String, lines: List<String>) {
        teamChatBypass.set(true)
        if (timer.passed(450) && lines.isNotEmpty()) {
            val line = lines.random(Random.Default).replace("%name%", name).replace("%count%", PopCounter.pops(name).toString())
            val connection = mc.connection
            if (line.startsWith("/")) {
                connection?.sendCommand(line.removeRange(0, 1))
            } else {
                when (mode.value) {
                    Mode.GLOBAL -> connection?.sendChat("!$line")
                    Mode.LOCAL -> connection?.sendChat(line)
                    Mode.WHISPERS -> connection?.sendCommand(prefix.value + " " + name + " " + line)
                    Mode.SILENT -> moduleChat(this, line)
                }
            }
            timer.reset()
        } else if (lines.isEmpty()) {
            // The raw translation key (on purpose): it has no English line.
            moduleChat(this, "modules.misc.auto-ez.empty")
        }
        teamChatBypass.set(false)
    }

    /** The trimmed non-blank lines; a missing file is created and counts as empty. */
    private fun read(file: File): List<String> {
        file.parentFile?.mkdirs()
        if (file.createNewFile()) return emptyList()
        return file.readLines().map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun open(file: File) {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        Blaze3D.openPath(file.toPath())
    }
}
