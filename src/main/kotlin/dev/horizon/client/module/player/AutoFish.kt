package dev.horizon.client.module.player

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.mixin.player1.FishingHookAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.item.Items
import kotlin.random.Random

/**
 * Reels in when a fish bites (the hook's biting flag, or a splash sound near the hook in
 * 1.12.2 mode) and casts again; casts by itself when nothing happened for 45 s. Rod Save stops (or swaps rods) before
 * the rod breaks.
 */
class AutoFish : Module("auto-fish", Categories.PLAYER) {
    enum class DetectMode { V1_12_2, V1_17 }

    val detectMode = enum("detect-mode", DetectMode.V1_17)
    val rodSave = bool("rod-save", true)
    val changeRod = bool("change-rod", false)
    val legit = bool("legit", true)

    /** This bite was already reeled in. */
    private var reeled = false

    /** Since the last cast or reel; a cast is forced once it reaches 45 s. */
    private val castTimer = MsTimer()

    /** Since the last forced cast or rod swap; the tick does nothing for 1 s after one. */
    private val pauseTimer = MsTimer()

    override fun onDisable() {
        reeled = false
    }

    init {
        // On the network thread.
        on<ReceivePacketEvent> { e ->
            val p = e.packet as? ClientboundSoundPacket ?: return@on
            if (detectMode.value != DetectMode.V1_12_2) return@on
            if (p.sound.value() != SoundEvents.FISHING_BOBBER_SPLASH) return@on
            val hook = mc.player?.fishing ?: return@on
            if (hook.distanceToSqr(p.x, p.y, p.z) < 4.0) reel()
        }
        on<PlayerUpdateEvent>(-10) {
            val p = mc.player ?: return@on
            if (p.mainHandItem.item is FishingRodItem && p.mainHandItem.damageValue > 52 && rodSave.value) {
                val rod = if (changeRod.value) findRod() else -1
                if (rod != -1) {
                    moduleMessage(name.trim(), "Swapped to a new rod")
                    Inventory.selectHotbar(rod)
                    pauseTimer.reset()
                } else {
                    // Off, then the message; the rest of this tick still runs.
                    disable()
                    moduleMessage(name.trim(), "Saving the rod...")
                }
            }
            if (!pauseTimer.passed(1000)) return@on
            if (castTimer.passed(45000) && p.mainHandItem.item is FishingRodItem) {
                use()
                castTimer.reset()
                pauseTimer.reset()
            }
            val hook = p.fishing ?: return@on
            if (detectMode.value != DetectMode.V1_17) return@on
            val biting = hook.entityData.get(FishingHookAccessor.horizon_dataBiting())
            if (!reeled && biting) {
                reel()
                reeled = true
                return@on
            }
            if (biting) return@on
            reeled = false
        }
    }

    /**
     * Legit: reel in after 200..350 ms and cast again after twice that, each from a sleeping virtual thread handing the
     * use to the main thread; only the cast restarts the 45 s timer. Otherwise reel and cast at once.
     */
    private fun reel() {
        if (legit.value) {
            val delay = (Random.Default.nextDouble() * 150.0 + 200.0).toInt()
            Thread.ofVirtual().start {
                Thread.sleep(delay.toLong())
                mc.executeIfPossible { use() }
            }
            Thread.ofVirtual().start {
                Thread.sleep(delay * 2L)
                mc.executeIfPossible {
                    use()
                    castTimer.reset()
                }
            }
            return
        }
        use()
        use()
        castTimer.reset()
    }

    /**
     * `useItem(MAIN_HAND)` then `swing(MAIN_HAND)`. 26.3 has no swing packet (the server swings on a used item by
     * itself), so the swing is the local animation only.
     */
    private fun use() {
        val p = mc.player ?: return
        val stack = p.mainHandItem
        mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
        p.swing(InteractionHand.MAIN_HAND, stack.interactAnimation, false)
    }

    /** The first hotbar rod with damage below 52, else -1. */
    private fun findRod(): Int {
        val inv = mc.player?.inventory ?: return -1
        for (i in 0 until 9) {
            val s = inv.getItem(i)
            if (s.item == Items.FISHING_ROD && s.damageValue < 52) return i
        }
        return -1
    }
}
