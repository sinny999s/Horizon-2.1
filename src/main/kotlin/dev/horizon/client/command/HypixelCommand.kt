package dev.horizon.client.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.horizon.client.hud.widgets.HypixelStatsWidget
import net.minecraft.ChatFormatting
import net.minecraft.client.multiplayer.ClientSuggestionProvider

/**
 * Not in the original: the Hypixel Stats widget's API key and asking. `hypixel key <key>` sets the key (from
 * developer.hypixel.net) and asks at once, `hypixel key clear` forgets it, `hypixel refresh` asks now, and `hypixel`
 * says where things stand. The key is never shown.
 */
object HypixelCommand : ClientCommand("hypixel") {
    override fun build(builder: LiteralArgumentBuilder<ClientSuggestionProvider>) {
        builder.then(literal("key").then(argument("key", StringArgumentType.greedyString()).executes { c ->
            val k = c.getArgument("key", String::class.java).trim()
            if (k.equals("clear", ignoreCase = true)) {
                HypixelStatsWidget.key = null
                message("Hypixel API key forgotten")
            } else {
                HypixelStatsWidget.key = k
                message("Hypixel API key saved, asking for your stats...")
                ask()
            }
            1
        }))
        builder.then(literal("refresh").executes {
            if (HypixelStatsWidget.key == null) message(ChatFormatting.RED.toString() + "no API key: hypixel key <key> (from developer.hypixel.net)")
            else {
                message("asking Hypixel for your stats...")
                ask()
            }
            1
        })
        builder.executes {
            val s = HypixelStatsWidget.stats
            message(when {
                HypixelStatsWidget.key == null -> "no API key: hypixel key <key> (from developer.hypixel.net)"
                s == null -> "API key set; no stats yet" + (HypixelStatsWidget.problem?.let { " ($it)" } ?: "")
                else -> "stats for ${s.name}, ${(System.currentTimeMillis() - s.fetchedAt) / 60000} min old" +
                    (HypixelStatsWidget.problem?.let { "; last ask failed: $it" } ?: "")
            })
            if (!HypixelStatsWidget.enabled) message("turn the Hypixel Stats widget on in the HUD editor to see them")
            1
        }
    }

    private fun ask() = HypixelStatsWidget.refresh { failure ->
        val s = HypixelStatsWidget.stats
        message(if (failure != null) ChatFormatting.RED.toString() + failure
            else "got ${s?.name}'s stats: ${s?.bedwarsStars}✫, FKDR ${"%.2f".format(s?.fkdr ?: 0.0)}")
    }
}
