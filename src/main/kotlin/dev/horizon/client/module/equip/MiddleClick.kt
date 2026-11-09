package dev.horizon.client.module.equip

import dev.horizon.client.combat.EquipUtil
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.InstantRequest
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.config.Friends
import dev.horizon.client.event.PickBlockEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.cosmetic.Trajectories
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult

/**
 * The pick-block key runs an action chosen by what is targeted (gliding, a player, a
 * block, anything else): throw a pearl (on press or release), use a firework, throw XP straight down while the armour
 * needs it, or add/remove a friend. Vanilla block picking is suppressed while it is on.
 */
class MiddleClick : Module("middle-click", Categories.EQUIP) {
    enum class OnBlock { FIREWORK, FRIEND, PEARL, XP, NONE }

    val onBlock = enum("on-block", OnBlock.XP)
    val onAir = enum("on-air", OnBlock.PEARL)
    val onEntity = enum("on-entity", OnBlock.FRIEND)
    val onElytra = enum("on-elytra", OnBlock.FIREWORK)
    val silent = bool("silent", true)
    val inventory = bool("inventory", true)
    val swapDelay = int("swap-delay", 100, 0..1000) { !silent.value }
    val antiWaste = bool("anti-waste", true)
    val stopOn = int("stop-on", 90, 0..100)
    val xpFactor = int("xp-factor", 1, 1..16)
    val pearlOnRelease = bool("pearl-on-release", true)

    /** The key was down on the last tick without a screen (kept across disable). */
    private var wasDown = false
    /** One cooldown shared by pearl (500 ms), firework (250 ms) and friend (800 ms), reset when it passes. */
    private val cooldown = MsTimer()

    init {
        on<PickBlockEvent> { it.cancel() }
        on<PlayerUpdateEvent>(-10) {
            val p = mc.player ?: return@on
            if (mc.gui.screen() != null) return@on
            if (mc.options.keyPickItem.isDown) {
                wasDown = true
                press(action(p))
            } else if (wasDown) {
                if (action(p) == OnBlock.PEARL && pearlOnRelease.value) pearl()
                wasDown = false
            }
        }
        on<Render3DEvent> {
            val p = mc.player ?: return@on
            if (mc.gui.screen() != null) return@on
            if (!mc.options.keyPickItem.isDown || !pearlOnRelease.value) return@on
            if (onBlock.value != OnBlock.PEARL && onAir.value != OnBlock.PEARL &&
                onEntity.value != OnBlock.PEARL && onElytra.value != OnBlock.PEARL
            ) return@on
            if (Inventory.find(Items.ENDER_PEARL) != null) {
                Modules.get<Trajectories>().path(p, Items.ENDER_PEARL, p.yRot)
            }
        }
    }

    /** The context order: gliding, a player, a non-air block, anything else (non-player entities too). */
    private fun action(p: Player): OnBlock {
        val hit = mc.hitResult
        return when {
            p.isFallFlying -> onElytra.value
            hit is EntityHitResult && hit.entity is Player -> onEntity.value
            hit is BlockHitResult && mc.level?.isEmptyBlock(hit.blockPos) == false -> onBlock.value
            else -> onAir.value
        }
    }

    private fun press(a: OnBlock) = when (a) {
        OnBlock.FIREWORK -> firework()
        OnBlock.FRIEND -> toggleFriend()
        OnBlock.PEARL -> if (!pearlOnRelease.value) pearl() else Unit
        OnBlock.XP -> xp()
        OnBlock.NONE -> Unit
    }

    /** A pearl along the current look, used when the rotation manager picks the request. */
    private fun pearl() {
        val p = mc.player ?: return
        if (!cooldown.passedAndReset(500)) return
        val hotbar = Inventory.hotbarFind(Items.ENDER_PEARL)?.slot ?: -1
        val inv = Inventory.find(Items.ENDER_PEARL)?.slot ?: -1
        if (hotbar == -1 && inv == -1) return
        RotationManager.queue(InstantRequest(Rotation(p.yRot, p.xRot), 200) {
            if (silent.value) {
                if (inventory.value && hotbar == -1) EquipUtil.useViaSwap(Inventory.find(Items.ENDER_PEARL)?.slot ?: -1)
                else EquipUtil.useInstant(hotbar, xpFactor.value)
            } else if (!inventory.value || hotbar != -1) {
                if (hotbar != -1) swapThread(hotbar, false)
            } else if (inv != -1) {
                swapThread(inv, true)
            }
        })
    }

    /** A firework, no rotation, every 250 ms while held. */
    private fun firework() {
        if (!cooldown.passedAndReset(250)) return
        val hotbar = Inventory.hotbarFind(Items.FIREWORK_ROCKET)?.slot ?: -1
        if (silent.value) {
            if (inventory.value && hotbar == -1) EquipUtil.useViaSwap(Inventory.find(Items.FIREWORK_ROCKET)?.slot ?: -1)
            else EquipUtil.useInstant(hotbar, xpFactor.value)
        } else if (inventory.value && hotbar == -1) {
            val inv = Inventory.find(Items.FIREWORK_ROCKET)?.slot ?: -1
            if (inv != -1) swapThread(inv, true)
        } else if (hotbar != -1) {
            swapThread(hotbar, false)
        }
    }

    /** XP straight down (no cooldown), thrown after the movement packet carried pitch 90. */
    private fun xp() {
        val p = mc.player ?: return
        if (!needsXp()) return
        RotationManager.queue(RotationRequest(Rotation(p.yRot, 90f), 200) {
            EquipUtil.useInstant(Inventory.hotbarFind(Items.EXPERIENCE_BOTTLE)?.slot ?: -1, xpFactor.value)
        })
    }

    /** XP in the hotbar and a worn piece under [stopOn] percent, or always without [antiWaste]. */
    private fun needsXp(): Boolean {
        val inv = mc.player?.inventory ?: return false
        if (Inventory.hotbarFind(Items.EXPERIENCE_BOTTLE) == null) return false
        if (intArrayOf(39, 38, 37, 36).any { EquipUtil.durabilityPercent(inv.getItem(it)) < stopOn.value }) return true
        return !antiWaste.value
    }

    /** Add or remove the targeted player's profile name. */
    private fun toggleFriend() {
        if (!cooldown.passedAndReset(800)) return
        val hit = mc.hitResult as? EntityHitResult ?: return
        val e = hit.entity as? Player ?: return
        val profile = e.gameProfile.name
        if (Friends.isFriend(profile)) {
            Friends.remove(profile)
            moduleMessage(name.trim(), "§c" + e.name.string + "§7 was removed from friends!")
        } else {
            Friends.add(profile)
            moduleMessage(name.trim(), "§a" + e.name.string + "§7 was added to friends!")
        }
    }

    /**
     * A swap thread: switch to [slot] (hotbar) or swap it into the selected slot (inventory), wait [swapDelay], queue the
     * use, wait again, switch or swap back. The thread only waits; the inventory and the rotation queue are touched on the
     * client thread.
     */
    private fun swapThread(slot: Int, viaInventory: Boolean) {
        val p = mc.player ?: return
        val original = p.inventory.selectedSlot
        val delay = swapDelay.value.toLong()
        fun swap(to: Int) = if (!viaInventory) Inventory.selectHotbar(to) else EquipUtil.click(slot, original, ContainerInput.SWAP)
        swap(slot)
        Thread {
            Thread.sleep(delay)
            mc.execute {
                val pl = mc.player ?: return@execute
                // No swing packet after the use; 26.3 has none.
                RotationManager.queue(InstantRequest(Rotation(pl.yRot, pl.xRot), 200) { EquipUtil.use() })
            }
            Thread.sleep(delay)
            mc.execute { swap(original) }
        }.start()
    }
}
