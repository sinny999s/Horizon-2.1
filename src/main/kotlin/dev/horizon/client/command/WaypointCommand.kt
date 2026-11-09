package dev.horizon.client.command

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.horizon.client.config.WayPoint
import dev.horizon.client.config.Waypoints
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientSuggestionProvider
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import java.util.concurrent.CompletableFuture

/**
 * The waypoints from chat, like the Waypoints window. `waypoint add <name> [x y z]` (where you stand,
 * or there, on this server and in this dimension), `remove <name>`, `show <name>`, `hide <name>`, `gps <name>` (the GPS
 * arrow points at it) and `list` (this server's). Also typed as `wp`.
 */
object WaypointCommand : ClientCommand("waypoint") {
    override fun build(builder: LiteralArgumentBuilder<ClientSuggestionProvider>) {
        builder.then(literal("add").then(argument("name", StringArgumentType.string())
            .executes { c -> add(c.getArgument("name", String::class.java), null); 1 }
            .then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer())
                .then(argument("z", IntegerArgumentType.integer()).executes { c ->
                    add(c.getArgument("name", String::class.java),
                        BlockPos(c.getArgument("x", Int::class.java), c.getArgument("y", Int::class.java), c.getArgument("z", Int::class.java)))
                    1
                })))))
        builder.then(literal("remove").then(argument("name", WaypointArgument).executes { c ->
            val wp = find(c.getArgument("name", String::class.java))
            Waypoints.list.remove(wp)
            Waypoints.save()
            message("Waypoint ${wp.id} is removed")
            1
        }))
        builder.then(literal("show").then(argument("name", WaypointArgument).executes { c -> visible(c, true); 1 }))
        builder.then(literal("hide").then(argument("name", WaypointArgument).executes { c -> visible(c, false); 1 }))
        builder.then(literal("gps").then(argument("name", WaypointArgument).executes { c ->
            val wp = find(c.getArgument("name", String::class.java))
            Commands.gps = BlockPos(wp.x, 0, wp.z)
            message("GPS points at ${wp.id} (${wp.x}, ${wp.z})")
            1
        }))
        builder.then(literal("list").executes {
            val here = Waypoints.list.filter { it.server == server() || it.server == "localhost" }
            if (here.isEmpty()) {
                message("No waypoints on this server")
            } else {
                message("Waypoints:")
                for (wp in here) {
                    val dim = when (wp.dimension) { 0 -> "overworld"; 1 -> "nether"; else -> "end" }
                    message(" ${wp.id}: ${wp.x}, ${wp.y}, ${wp.z} ($dim${if (wp.visible) "" else ", hidden"})")
                }
            }
            1
        })
        builder.executes { message("waypoint <add / remove / show / hide / gps / list>"); 1 }
    }

    /** The server the waypoints are kept under: its address, or "localhost" in singleplayer. */
    private fun server(): String = Minecraft.getInstance().currentServer?.ip ?: "localhost"

    private fun add(name: String, at: BlockPos?) {
        val p = mc.player ?: return
        val level = mc.level ?: return
        val pos = at ?: p.blockPosition()
        Waypoints.list += WayPoint(name, pos.x, pos.y, pos.z, server(), Waypoints.dimension(level.dimension().identifier().path), true)
        Waypoints.save()
        message("Waypoint $name is added at ${pos.x}, ${pos.y}, ${pos.z}")
    }

    private fun visible(c: CommandContext<ClientSuggestionProvider>, on: Boolean) {
        val wp = find(c.getArgument("name", String::class.java))
        wp.visible = on
        Waypoints.save()
        message("Waypoint ${wp.id} is ${if (on) "shown" else "hidden"}")
    }

    /** The waypoint called [name], this server's first ([WaypointArgument] has checked there is one). */
    private fun find(name: String): WayPoint =
        Waypoints.list.filter { it.id == name }.let { all -> all.firstOrNull { it.server == server() } ?: all.first() }
}

/** `wp`: the same as `waypoint`. */
object WpCommand : ClientCommand("wp") {
    override fun build(builder: LiteralArgumentBuilder<ClientSuggestionProvider>) = WaypointCommand.build(builder)
}

/** The name of a waypoint (in quotes if it has spaces); suggests this server's first. */
object WaypointArgument : ArgumentType<String> {
    override fun parse(reader: StringReader): String {
        val name = reader.readString()
        if (Waypoints.list.none { it.id == name }) {
            throw com.mojang.brigadier.exceptions.DynamicCommandExceptionType {
                net.minecraft.network.chat.Component.literal("Waypoint with name %s does not exist".format(it))
            }.create(name)
        }
        return name
    }

    override fun <S> listSuggestions(context: CommandContext<S>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val server = Minecraft.getInstance().currentServer?.ip ?: "localhost"
        val names = Waypoints.list.sortedBy { it.server != server }.map { it.id }.distinct()
        return SharedSuggestionProvider.suggest(names.map { if (it.any { ch -> ch == ' ' || ch == '"' }) "\"" + it.replace("\"", "\\\"") + "\"" else it }, builder)
    }
}
