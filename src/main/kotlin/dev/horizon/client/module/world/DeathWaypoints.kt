package dev.horizon.client.module.world

import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.config.WayPoint
import dev.horizon.client.config.Waypoints
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.entity.player.Player

/**
 * A waypoint where you die ("death-1", "death-2"...), with the server's other waypoints, so the
 * waypoint renderer and `^wp gps` can take you back; and the coordinates in chat with [message]. Only the server's
 * last [keep] deaths are kept.
 */
class DeathWaypoints : Module("death-waypoints", Categories.WORLD) {
    val keep = int("keep", 3, 1..20)
    val message = bool("message", true)

    /** Set while you're alive, so each death is marked once. */
    private var armed = false

    init {
        on<TickEvent> {
            val p = mc.player ?: return@on
            if (!p.isDeadOrDying) {
                armed = true
                return@on
            }
            if (!armed) return@on
            armed = false
            mark(p)
        }
    }

    private fun mark(p: Player) {
        val level = mc.level ?: return
        val server = mc.currentServer?.ip ?: "localhost"
        val pos = p.blockPosition()
        fun deaths() = Waypoints.list.filter { it.server == server && DEATH.matches(it.id) }
        val n = (deaths().maxOfOrNull { it.id.removePrefix("death-").toInt() } ?: 0) + 1
        val id = "death-$n"
        Waypoints.list += WayPoint(id, pos.x, pos.y, pos.z, server, Waypoints.dimension(level.dimension().identifier().path), true)
        for (old in deaths().dropLast(keep.value)) Waypoints.list.remove(old)
        Waypoints.save()
        if (message.value) moduleMessage(name.trim(), "You died at X: ${pos.x} Y: ${pos.y} Z: ${pos.z} (waypoint $id)")
    }

    private companion object {
        val DEATH = Regex("death-\\d+")
    }
}
