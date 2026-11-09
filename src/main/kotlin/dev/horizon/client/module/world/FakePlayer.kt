package dev.horizon.client.module.world

import com.mojang.authlib.GameProfile
import dev.horizon.client.combat.Explosions
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PreSyncEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.client.player.RemotePlayer
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * A [RemotePlayer] added to the client level, so everything that reads
 * `level.players()` (TargetFinder, Aura, AutoCrystal, ESPs) treats it as a real player. It takes crystal damage from
 * explosion packets, pops a totem (a synthetic entity event 35 on the receive-packet bus), regenerates, and can replay
 * a recorded path of the local player.
 */
class FakePlayer : Module("fake-player", Categories.WORLD) {
    /** Set while a recording plays back (hides Record). */
    var playing = false
    /** Set while recording (hides Play). */
    var recording = false

    val recordStop = action("record-/-stop", visible = { !playing }) { recording = !recording }
    val playStop = action("play-/-stop", visible = { !recording }) { playing = !playing }
    val copyInventory = bool("copy-inventory", true)
    val nameSetting = string("name", "OLD_GOD")

    /** The fake player while the module is on. */
    var player: RemotePlayer? = null

    /** One recorded player state. */
    private class Frame(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

    private val frames = ArrayList<Frame>()
    private var index = 0

    init {
        on<ReceivePacketEvent> { e -> receive(e) }
        on<PreSyncEvent> { preSync() }
    }

    override fun onEnable() {
        val self = mc.player ?: return
        val level = mc.level ?: return
        val fake = RemotePlayer(level, GameProfile(UUID.fromString("66123666-6666-6666-6666-613376666600"), nameSetting.value))
        player = fake
        fake.copyPosition(self)
        fake.id = 4204204
        if (copyInventory.value) {
            fake.setItemInHand(InteractionHand.MAIN_HAND, self.mainHandItem.copy())
            fake.setItemInHand(InteractionHand.OFF_HAND, self.offhandItem.copy())
            // Indices 35..38 on purpose: the last main slot, feet, legs and chest (not the helmet at 39).
            for (i in 0 until 4) fake.inventory.setItem(35 + i, self.inventory.getItem(35 + i).copy())
            fake.attributes.assignAllValues(self.attributes)
        }
        level.addEntity(fake)
        fake.addEffect(MobEffectInstance(MobEffects.REGENERATION, 9999, 2))
        fake.addEffect(MobEffectInstance(MobEffects.ABSORPTION, 9999, 4))
        fake.addEffect(MobEffectInstance(MobEffects.RESISTANCE, 9999, 1))
    }

    override fun onDisable() {
        val fake = player ?: return
        fake.setRemoved(Entity.RemovalReason.KILLED)
        fake.onClientRemoval()
        player = null
        frames.clear()
    }

    /** Network thread: an explosion hurts the fake player by the crystal damage formula. */
    private fun receive(e: ReceivePacketEvent) {
        val p = e.packet as? ClientboundExplodePacket ?: return
        val fake = player ?: return
        if (fake.hurtTime != 0) return
        val level = mc.level ?: return
        fake.handleDamageEvent(level.damageSources().generic())
        // Absorption is read but never lowered (on purpose).
        fake.health = fake.health + (fake.absorptionAmount - Explosions.damage(p.center(), fake, 0, false, emptyList(), emptyList()))
        if (fake.isDeadOrDying) {
            fake.health = 10f
            EventBus.post(ReceivePacketEvent(ClientboundEntityEventPacket(fake, 35.toByte())))
        }
    }

    private fun preSync() {
        val self = mc.player ?: return
        if (recording) {
            frames += Frame(self.x, self.y, self.z, self.yRot, self.xRot)
            return
        }
        val fake = player
        if (fake == null) {
            disable()
            return
        }
        if (fake.tickCount % 8 == 0) fake.health = fake.health + 0.5f
        if (fake.position().distanceToSqr(self.position()) - 400.0 > 0) {
            disable()
            return
        }
        if (playing && frames.isNotEmpty()) {
            index++
            if (index >= frames.size) {
                index = 0
                return
            }
            val f = frames[index]
            fake.yRot = f.yaw
            fake.xRot = f.pitch
            fake.yHeadRot = f.yaw
            fake.syncPacketPositionCodec(f.x, f.y, f.z)
            fake.moveOrInterpolateTo(Vec3(f.x, f.y, f.z), f.yaw, f.pitch)
        } else {
            index = 0
        }
        if (fake.offhandItem.item != Items.TOTEM_OF_UNDYING) fake.setItemInHand(InteractionHand.OFF_HAND, ItemStack(Items.TOTEM_OF_UNDYING))
    }
}
