package dev.horizon.client.module.player

import dev.horizon.client.config.Friends
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.player.Player

/**
 * Drops attack packets aimed at friends, villagers, or zombified piglins that aren't
 * already after us.
 */
class AntiAttack : Module("anti-attack", Categories.PLAYER) {
    val friends = bool("friends", true)
    val villagers = bool("villagers", true)
    val zoglins = bool("zoglins", true)

    init {
        // The target comes from the level by the packet's id.
        on<SendPacketEvent> { e ->
            val p = e.packet as? ServerboundAttackPacket ?: return@on
            try {
                val target = mc.level?.getEntity(p.entityId())
                val drop = friends.value && target is Player && Friends.isFriend(target.gameProfile.name) ||
                    villagers.value && target is Villager ||
                    zoglins.value && target is ZombifiedPiglin && target.target != mc.player
                if (drop) e.cancel()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }
}
