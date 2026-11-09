package dev.horizon.client.module.player

import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.client.gui.screens.DeathScreen
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket

/**
 * Respawns from the death screen (once per death screen) and sends a command after
 * dying: when our death event arrives (Instant), or a second after respawning (Delayed).
 */
class AutoRespawn : Module("auto-respawn", Categories.PLAYER) {
    enum class DeathCommand { KIT, HOME, BACK, CUSTOM }
    enum class CommandTiming { INSTANT, DELAYED }

    val deathCoords = bool("death-coords", true)
    val deathCommand = enum("death-command", DeathCommand.KIT)
    val kitName = string("kit-name", "kitname") { deathCommand.value == DeathCommand.KIT }
    val command = string("command", "/ebal") { deathCommand.value == DeathCommand.CUSTOM }
    val commandTiming = enum("command-timing", CommandTiming.DELAYED)

    /** Set on every tick without a death screen, so each death screen respawns once. */
    private var armed = false

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            if (mc.gui.screen() !is DeathScreen) {
                armed = true
                return@on
            }
            if (!armed) return@on
            if (deathCoords.value) moduleMessage(name.trim(), "You died on the X: ${p.x.toInt()} Z: ${p.z.toInt()}")
            p.respawn()
            mc.gui.setScreen(null)
            // A sleeping task on a virtual thread, so the command goes from that thread.
            if (commandTiming.value == CommandTiming.DELAYED) Thread.ofVirtual().start {
                Thread.sleep(1000)
                runDeathCommand()
            }
            armed = false
        }
        // Our death (entity event 3) or death smoke (60), on the network thread.
        on<ReceivePacketEvent> { e ->
            val pk = e.packet as? ClientboundEntityEventPacket ?: return@on
            if (mc.player == null) return@on
            val level = mc.level ?: return@on
            if (pk.eventId.toInt() != 3 && pk.eventId.toInt() != 60) return@on
            if (pk.getEntity(level) != mc.player) return@on
            if (commandTiming.value != CommandTiming.INSTANT) return@on
            runDeathCommand()
        }
    }

    fun runDeathCommand() {
        val c = mc.player?.connection ?: return
        when (deathCommand.value) {
            DeathCommand.KIT -> c.sendCommand("kit " + kitName.value)
            DeathCommand.BACK -> c.sendCommand("back")
            DeathCommand.HOME -> c.sendCommand("home")
            DeathCommand.CUSTOM -> c.sendCommand(command.value)
        }
    }
}
