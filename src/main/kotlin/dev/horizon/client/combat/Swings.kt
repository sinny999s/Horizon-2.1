package dev.horizon.client.combat

import dev.horizon.client.event.AfterSendPacketEvent
import dev.horizon.client.event.EventBus
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundPunchPacket

/**
 * The swing that goes with every attack. In 26.3 the arm swing is its own packet (Punch), sent by
 * vanilla's left click right after the attack, and servers (and anticheats: Vulcan's "No Swing") expect the two
 * together. So every attack the client sends itself (Aura, AutoCrystal, Crystal Bind...) is followed by a Punch, as a
 * click would be; your own clicks already send theirs ([inVanillaAttack], set by StartAttackMixin).
 */
object Swings {
    /** Vanilla's own left click is running (it sends its Punch itself). */
    @JvmField
    var inVanillaAttack = false

    fun init() {
        EventBus.on<AfterSendPacketEvent>(this) { e ->
            if (e.packet is ServerboundAttackPacket && !inVanillaAttack) punch()
        }
    }

    /** The swing packet, as a left click sends it (also after an attack with no attack packet, or a miss). */
    fun punch() {
        Minecraft.getInstance().connection?.send(ServerboundPunchPacket.INSTANCE)
    }
}
