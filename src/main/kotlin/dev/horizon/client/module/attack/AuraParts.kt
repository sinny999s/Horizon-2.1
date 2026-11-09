package dev.horizon.client.module.attack

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Scheduler
import dev.horizon.client.combat.Rotation
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Ghost
import dev.horizon.client.module.protect.Offhand
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.network.protocol.game.VecDeltaCodec
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.EquipmentSlotGroup
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.Items
import net.minecraft.world.item.MaceItem
import net.minecraft.world.item.TridentItem
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

private val mc get() = Minecraft.getInstance()

/** Something Aura runs every tick, before each hit and after it. */
interface AuraHook {
    fun tick() {}

    fun pre() {}

    fun post(rotation: Rotation) {}
}

/**
 * `while-shielding`: lower a raised shield or 1.8 sword block for the hit; UNPRESS raises it again
 * straight after. Extended with 1.8 sword `auto-block` (`BLOCK_HIT` / `HOLD` / `LEGIT`).
 */
class ShieldHook(private val aura: Aura) : AuraHook {
    private var blocking = false
    private var blockingHand = InteractionHand.OFF_HAND
    var autoBlocking = false
        private set
    private var blockHoldTicks = 0
    private var blockHitCounter = 0

    // LEGIT: the use key held for a block-hit, the ticks it has been, and when it was let go (in this hook's ticks).
    private var legitHold = false
    private var legitHeld = 0
    private var legitSawHurt = false
    private var legitReleaseAt = 2
    private var legitPending = -1
    private var ticks = 0
    private var releasedAt = -10

    /**
     * `auto-block` LEGIT lets Aura hit only when your hand is free: never while the block is held
     * or about to be, and not in the tick the key is let go (vanilla sends the unblock at the start of the next tick, so
     * a hit there would ride on it).
     */
    fun allowsHit(): Boolean {
        if (aura.autoBlock.value != Aura.AutoBlock.LEGIT) return true
        return !legitHold && legitPending < 0 && ticks - releasedAt >= 2
    }

    private fun legitPress() {
        legitHold = true
        legitHeld = 0
        legitSawHurt = false
        // Let go when the target's hurt time is down to this: it can be hurt again about when it reaches 0.
        legitReleaseAt = 1 + Random.nextInt(3)
        autoBlocking = true
        mc.options.keyUse.isDown = true
    }

    private fun legitRelease() {
        legitHold = false
        autoBlocking = false
        releasedAt = ticks
        mc.options.keyUse.isDown = PlayerState.physical(mc.options.keyUse)
    }

    private fun canSwordBlock(p: Player): Boolean {
        if (!aura.isLegacyCombat()) return false
        val s = p.mainHandItem
        return s.`is`(ItemTags.SWORDS) && (s.useAnimation == ItemUseAnimation.BLOCK ||
            s.get(DataComponents.BLOCKS_ATTACKS) != null || s.get(DataComponents.CONSUMABLE) != null)
    }

    fun releaseAutoBlock() {
        legitPending = -1
        if (legitHold) return legitRelease()
        if (!autoBlocking) return
        autoBlocking = false
        blockHoldTicks = 0
        val p = mc.player ?: return
        if (p.isUsingItem && !PlayerState.physical(mc.options.keyUse)) mc.gameMode?.releaseUsingItem(p)
    }

    override fun tick() {
        ticks++
        val p = mc.player ?: return
        val ab = aura.autoBlock.value
        if (!aura.isLegacyCombat() || ab == Aura.AutoBlock.OFF || aura.target == null || !canSwordBlock(p)) {
            releaseAutoBlock()
            return
        }
        if (ab == Aura.AutoBlock.LEGIT) {
            // You right-clicking yourself: yours, not Aura's.
            if (PlayerState.physical(mc.options.keyUse)) {
                legitPending = -1
                if (legitHold) {
                    legitHold = false
                    autoBlocking = false
                    releasedAt = ticks
                }
                return
            }
            if (legitHold) {
                legitHeld++
                // Blocking while the target can't be hurt anyway (its hurt time, set when your hit lands), let go just
                // before it can; a hit that didn't land (no hurt time) lets go after 3 ticks, and 9 at the most.
                val hurt = (aura.target as? LivingEntity)?.hurtTime ?: 0
                if (hurt > legitReleaseAt + 1) legitSawHurt = true
                val done = if (legitSawHurt) hurt <= legitReleaseAt else legitHeld >= 3
                if (done || legitHeld >= 9) legitRelease()
            } else if (legitPending >= 0) {
                if (legitPending-- == 0) legitPress()
            }
            return
        }
        if (ab == Aura.AutoBlock.BLOCK_HIT && autoBlocking) {
            blockHoldTicks--
            if (blockHoldTicks <= 0) releaseAutoBlock()
        }
    }

    override fun pre() {
        val p = mc.player ?: return
        if (autoBlocking) {
            releaseAutoBlock()
            blocking = false
            return
        }
        blocking = p.isUsingItem && (p.useItem.useAnimation == ItemUseAnimation.BLOCK || p.useItem.get(DataComponents.BLOCKS_ATTACKS) != null)
        blockingHand = p.usedItemHand
        val mode = aura.whileShielding.value
        if (blocking && (mode == Aura.WhileShielding.UNPRESS || mode == Aura.WhileShielding.LEGIT)) mc.gameMode?.releaseUsingItem(p)
    }

    override fun post(rotation: Rotation) {
        val p = mc.player ?: return
        if (blocking && aura.whileShielding.value == Aura.WhileShielding.UNPRESS) {
            mc.gameMode?.useItem(p, blockingHand)
            return
        }
        if (!canSwordBlock(p)) return
        when (aura.autoBlock.value) {
            Aura.AutoBlock.OFF -> {}
            Aura.AutoBlock.HOLD -> {
                mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
                autoBlocking = true
            }
            // Block from the next tick or the one after (most of the time; a person misses some).
            Aura.AutoBlock.LEGIT -> if (!legitHold && legitPending < 0 && Random.nextFloat() < 0.85f) legitPending = Random.nextInt(2)
            Aura.AutoBlock.BLOCK_HIT -> {
                blockHitCounter++
                if (blockHitCounter >= 2) {
                    blockHitCounter = 0
                    mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
                    autoBlocking = true
                    blockHoldTicks = 1 + ((System.nanoTime() ushr 4) and 1L).toInt()
                }
            }
        }
    }
}

/** `auto-weapon` and `only-weapon`. */
class WeaponHook(private val aura: Aura) : AuraHook {
    private var saved = -1

    /** The only-weapon gate. */
    fun gate(): Boolean {
        if (!aura.onlyWeapon.value) return true
        val p = mc.player ?: return false
        if (aura.autoWeapon.value == Aura.AutoWeapon.NONE) return isWeapon(p.mainHandItem)
        return Inventory.hotbarFind { it.`is`(ItemTags.SWORDS) } != null || Inventory.hotbarFind { it.`is`(ItemTags.AXES) } != null
    }

    private fun isWeapon(s: ItemStack) =
        s.`is`(ItemTags.SWORDS) || s.`is`(ItemTags.AXES) || s.item is TridentItem || s.item is MaceItem

    /** The best sword in the hotbar (durability + Sharpness), else the best axe. */
    fun selectBest() {
        val slot = best(ItemTags.SWORDS) ?: best(ItemTags.AXES) ?: return
        Inventory.selectHotbar(slot)
    }

    private fun best(tag: net.minecraft.tags.TagKey<net.minecraft.world.item.Item>): Int? {
        val p = mc.player ?: return null
        val level = mc.level ?: return null
        val sharpness = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS)
        var best: Int? = null
        var bestScore = 1.0f
        for (i in 0..8) {
            val s = p.inventory.getItem(i)
            if (!s.`is`(tag)) continue
            val score = (s.get(DataComponents.MAX_DAMAGE) ?: 0) + EnchantmentHelper.getItemEnchantmentLevel(sharpness, s).toFloat()
            if (score > bestScore) {
                best = i
                bestScore = score
            }
        }
        return best
    }

    override fun tick() = restore()

    override fun pre() {
        if (aura.autoWeapon.value != Aura.AutoWeapon.SILENT) return
        saved = Inventory.selected
        selectBest()
    }

    override fun post(rotation: Rotation) = restore()

    private fun restore() {
        if (saved == -1) return
        Inventory.selectHotbar(saved)
        saved = -1
    }
}

/** `offhand-hit-swap`: the best off-hand attack-damage item goes to the off-hand for the hit. */
class OffhandSwapHook(private val aura: Aura) : AuraHook {
    private var swapIn: (() -> Unit)? = null
    private var swapBack: (() -> Unit)? = null

    /** The inventory index with the strongest off-hand attack-damage modifier, or −1. */
    fun find(): Int {
        val p = mc.player ?: return -1
        var best = -1
        var bestAmount = 0.0
        for (i in 0..36) {
            val s = if (i == 36) p.offhandItem else p.inventory.getItem(i)
            val mods = s.get(DataComponents.ATTRIBUTE_MODIFIERS) ?: continue
            for (e in mods.modifiers()) {
                if (e.slot() != EquipmentSlotGroup.OFFHAND || !e.attribute().value().descriptionId.contains("attack_damage")) continue
                if (e.modifier().amount() > bestAmount) {
                    bestAmount = e.modifier().amount()
                    best = i
                }
            }
        }
        return if (best == 36) -1 else best
    }

    /** Line up the swap in (end of this tick) and the swap back (after the hit). */
    fun schedule() {
        if (aura.isLegacyCombat() || !aura.offhandHitSwap.value) return
        val slot = find()
        if (slot == -1 || mc.gui.screen() is net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen) return
        val notContainer = mc.gui.screen() !is net.minecraft.client.gui.screens.inventory.ContainerScreen
        val slotId = if (slot < 9) slot + 36 else slot
        PlayerState.releaseMovementKeys()
        Scheduler.ticks(2) { PlayerState.resyncMovementKeys() }
        val swap = {
            val p = mc.player
            if (p != null) {
                if (!p.containerMenu.carried.isEmpty) mc.gameMode?.handleContainerInput(p.containerMenu.containerId, -999, 0, ContainerInput.PICKUP, p)
                Inventory.swap(slotId, 40, notContainer)
                Modules.get<Offhand>().swapCooldown = 15
            }
        }
        swapIn = swap
        swapBack = swap
    }

    /** At the end of the client tick. */
    fun postTick() {
        swapIn?.invoke()
        swapIn = null
    }

    override fun post(rotation: Rotation) {
        swapBack?.invoke()
        swapBack = null
        Modules.get<Offhand>().swapCooldown = 1
        PlayerState.resyncMovementKeys()
    }
}

/**
 * `back-track`: while the target is a player, hold incoming packets back so it stays where it was,
 * as long as that is farther from us than where it is now; let them go after `move-delay` ms.
 */
class BackTrack(private val aura: Aura) {
    var player: Player? = null
        private set

    /** The latest server position, and the one before (for the render). */
    var latest: Vec3? = null
        private set
    var previous: Vec3? = null
        private set
    var lastMove = 0L
        private set

    private class Delayed(val packet: Packet<*>, val time: Long)

    private val delayed = ConcurrentLinkedQueue<Delayed>()
    private val ready = ConcurrentLinkedQueue<Packet<*>>()

    /** Each Aura tick, before the target is chosen. */
    fun update() {
        val t = if (aura.enabled) aura.target as? Player else null
        if (t == null) {
            flush()
            return
        }
        if (t !== player) {
            flush()
            latest = t.position()
        }
        player = t
    }

    fun receive(e: ReceivePacketEvent) {
        val p = player
        if (delayed.isEmpty() && ready.isEmpty() && (p == null || !p.isAlive)) return
        val pk = e.packet
        if (pk is ClientboundSystemChatPacket || pk is ClientboundSetTimePacket || pk is ClientboundSoundPacket ||
            pk is ClientboundStopSoundPacket || pk is ClientboundSetActionBarTextPacket || pk is ClientboundSetHealthPacket ||
            pk is ClientboundBossEventPacket) return
        if (pk is ClientboundPlayerPositionPacket || pk is ClientboundDisconnectPacket) {
            flush()
            return
        }
        var newPos: Vec3? = null
        if (pk is ClientboundMoveEntityPacket) {
            val level = mc.level ?: return
            if (pk.getEntity(level) !== p) return
            val codec = VecDeltaCodec()
            latest?.let { codec.setBase(it) }
            newPos = pk.positionDelta.decode(codec).endPosition()
            lastMove = System.currentTimeMillis()
        }
        if (pk is ClientboundTeleportEntityPacket) {
            if (p == null || pk.id() != p.id) return
            newPos = pk.change().position()
        }
        if (pk is ClientboundEntityPositionSyncPacket) {
            if (p == null || pk.id() != p.id) return
            newPos = pk.position().endPosition()
        }
        if (newPos != null) {
            previous = latest
            latest = newPos
            val self = mc.player
            if (p != null && self != null && self.position().distanceToSqr(newPos) <= self.position().distanceToSqr(p.position())) {
                release(true)
                return
            }
        }
        delayed += Delayed(pk, System.currentTimeMillis())
        e.cancel()
    }

    private fun release(force: Boolean) {
        val cutoff = System.currentTimeMillis() - aura.moveDelay.value
        val it = delayed.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (force || d.time <= cutoff) {
                ready += d.packet
                it.remove()
            }
        }
    }

    /** Once per frame, after the client ran its tasks. */
    fun postTasks() {
        if (mc.level == null || mc.player == null) {
            delayed.clear()
            player = null
            latest = null
            return
        }
        val p = player
        if (p != null && p.isAlive) release(false) else flush()
        while (true) handle(ready.poll() ?: break)
    }

    fun flush() {
        while (true) handle(delayed.poll()?.packet ?: break)
        player = null
        latest = null
    }

    /** A pulsing ghost where the server has the target, once that is a block or more from where it shows. */
    fun render() {
        val p = player ?: return
        val z = latest ?: return
        val h = previous ?: return
        if (p.position().distanceToSqr(z) - 1.0 < 0) return
        var a = (System.currentTimeMillis() / 2.0 % 360).toInt()
        if (a >= 180) a = 360 - a
        val k = Mth.clamp((System.currentTimeMillis() - lastMove) / 100f, 0f, 1f).toDouble()
        val pos = Vec3(Mth.lerp(k, h.x, z.x), Mth.lerp(k, h.y, z.y), Mth.lerp(k, h.z, z.z))
        Ghost.draw(pos, p, a / 360f, true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun handle(p: Packet<*>) {
        val listener = mc.connection ?: return
        try {
            (p as Packet<ClientGamePacketListener>).handle(listener)
        } catch (_: Exception) {
        }
    }
}

/**
 * Vanilla's crosshair pick, but looking along [yaw]/[pitch] and reaching [entityRange] for entities.
 */
fun pickAlong(blockRange: Double, entityRange: Double, yaw: Float, pitch: Float): HitResult? {
    val p = mc.player ?: return null
    val level = mc.level ?: return null
    var maxR = max(blockRange, entityRange)
    var maxSq = maxR * maxR
    val eye = p.eyePosition
    val dir = Vec3.directionFromRotation(pitch, yaw)
    val blockHit = level.clip(ClipContext(eye, eye.add(dir.scale(blockRange)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p))
    val blockDistSq = blockHit.location.distanceToSqr(eye)
    if (blockHit.type != HitResult.Type.MISS) {
        maxSq = blockDistSq
        maxR = sqrt(blockDistSq)
    }
    val end = eye.add(dir.scale(maxR))
    val box = p.boundingBox.expandTowards(dir.scale(maxR)).inflate(1.0)
    val eHit = ProjectileUtil.getEntityHitResult(p, eye, end, box, EntitySelector.CAN_BE_PICKED, maxSq)
    fun filter(hit: HitResult, r: Double): HitResult =
        if (hit.location.closerThan(eye, r)) hit
        else BlockHitResult.miss(hit.location, Direction.getApproximateNearest(hit.location.subtract(eye)), BlockPos.containing(hit.location))
    return if (eHit != null && eHit.location.distanceToSqr(eye) < blockDistSq) filter(eHit, entityRange) else filter(blockHit, blockRange)
}

/** For callers that want the entity a pick found. */
fun HitResult?.pickedEntity() = (this as? EntityHitResult)?.entity

/** The shield check Aura's shield breaker uses: the target is using an item with a shield in a hand. */
fun Player.holdingShieldUp() = isUsingItem && (offhandItem.`is`(Items.SHIELD) || mainHandItem.`is`(Items.SHIELD))
