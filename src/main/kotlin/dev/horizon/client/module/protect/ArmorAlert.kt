package dev.horizon.client.module.protect

import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Monitors the durability of all worn armor pieces (helmet, elytra/chestplate, leggings, boots)
 * and warns when any piece drops below a critical threshold, preventing catastrophic gear destruction in PvP and travel.
 */
class ArmorAlert : Module("armor-alert", Categories.PROTECT) {
    enum class Output { CHAT, NOTIFICATION, BOTH }

    val threshold = int("threshold-%", 15, 1..50)
    val output = enum("output", Output.BOTH)
    val sound = bool("sound", true)
    val cooldownSeconds = int("cooldown-s", 6, 2..30)

    private val alertCooldowns = ConcurrentHashMap<Int, Long>()

    init {
        on<PlayerUpdateEvent> {
            val player = mc.player ?: return@on
            val now = System.currentTimeMillis()
            val cdMs = cooldownSeconds.value * 1000L

            val slots = listOf(
                39 to "Helmet",
                38 to if (player.inventory.getItem(38).`is`(net.minecraft.world.item.Items.ELYTRA)) "Elytra" else "Chestplate",
                37 to "Leggings",
                36 to "Boots"
            )

            for ((slot, name) in slots) {
                val stack = player.inventory.getItem(slot)
                if (stack.isEmpty || !stack.isDamageableItem) continue

                val maxDmg = stack.maxDamage
                if (maxDmg <= 0) continue

                val curDmg = stack.damageValue
                val remainingPct = ((maxDmg - curDmg).toFloat() / maxDmg * 100f).roundToInt()

                if (remainingPct <= threshold.value) {
                    val last = alertCooldowns[slot] ?: 0L
                    if (now - last > cdMs) {
                        alertCooldowns[slot] = now

                        val msg = "$name durability is low ($remainingPct% remaining)!"
                        if (output.value == Output.CHAT || output.value == Output.BOTH) {
                            moduleMessage("ArmorAlert", msg)
                        }
                        if (output.value == Output.NOTIFICATION || output.value == Output.BOTH) {
                            Notifications.post("Armor Alert", msg, 3f, NotificationType.WARNING)
                        }
                        if (sound.value) {
                            player.playSound(SoundEvents.ANVIL_LAND, 0.7f, 1.2f)
                        }
                    }
                }
            }
        }
    }

    override fun onDisable() {
        alertCooldowns.clear()
    }
}
