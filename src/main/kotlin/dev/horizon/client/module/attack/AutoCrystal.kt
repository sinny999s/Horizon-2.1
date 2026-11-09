package dev.horizon.client.module.attack

import dev.horizon.client.combat.CrystalTracker
import dev.horizon.client.combat.Explosions
import dev.horizon.client.combat.Found
import dev.horizon.client.combat.GrimRange
import dev.horizon.client.combat.IdPredict
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.PlacementLock
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Predictor
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.TargetFinder
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.config.Friends
import dev.horizon.client.event.EntitySpawnEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PostSyncEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.RenderEntityEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Esp
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.MoveFix
import dev.horizon.client.module.player.AutoMend
import dev.horizon.client.module.player.SpeedMine
import dev.horizon.client.module.protect.Offhand
import dev.horizon.client.module.rage.Blink
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import dev.horizon.client.render.WorldEsp
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.ItemTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.item.EndCrystalItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import dev.horizon.client.combat.DamageSync as Sync

/**
 * Each tick picks a target, finds the best base to put a crystal on and the best crystal
 * to break, and does both (or one) with the configured timing, switching and rotations; reacts to crystals spawning.
 */
class AutoCrystal : Module("auto-crystal", Categories.ATTACK) {
    enum class Timing { VANILLA, SEQUENTIAL }
    enum class OnCrystalSpawn { NO_REACTION, ATTACK, ACTION_X }
    enum class SpawnTiming { PACKET, ADD_TO_WORLD }
    enum class Predict { AUTO, CUSTOM, OFF }
    enum class FriendProtection { NONE, ONLY_LETHAL, FULL }
    enum class DamageSync { ALWAYS, CONDITIONS, OFF }
    enum class Switch { NONE, NORMAL, SILENT, INVENTORY }
    enum class TargetSort { DISTANCE, FOV, HEALTH }
    enum class RenderMode { SHADER, DEAFULT, TECHNO, OFF }

    val main = group("main")
    val timing = enum("timing", Timing.VANILLA, parent = main.value)
    val oneTickAction = bool("one-tick-action", true, parent = main.value)
    val onCrystalSpawn = enum("on-crystal-spawn", OnCrystalSpawn.ACTION_X, parent = main.value)
    val spawnTiming = enum("spawn-timing", SpawnTiming.ADD_TO_WORLD, parent = main.value)
    val rotate = bool("rotate", true, parent = main.value)
    val rayTrace = bool("ray-trace", false, parent = main.value)
    val crystalMultiPoint = bool("crystal-multi-point", false, parent = main.value)
    val asyncCalc = bool("async-calc", false, parent = main.value)
    val failNotify = bool("fail-notify", false, parent = main.value)
    val predict = enum("predict", Predict.AUTO, parent = main.value)
    val predictTicks = int("predict-ticks", 3, 1..10, parent = main.value) { predict.value == Predict.CUSTOM }
    val place = group("place")
    val n112 = bool("1.12", false, parent = place.value)
    val placeDelay = int("place-delay", 0, 0..10, parent = place.value)
    val placeRange = float("place-range", 5.0f, 1.0f..6.0f, parent = place.value)
    val limitPlace = bool("limit-place", true, parent = place.value)
    val removeFire = bool("remove-fire", true, parent = place.value)
    val removeLava = bool("remove-lava", true, parent = place.value)
    val breakSetting = group("break")
    val breakDelay = int("break-delay", 0, 0..10, parent = breakSetting.value)
    val grimBreakRange = bool("grim-break-range", false, parent = breakSetting.value)
    val breakRange = float("break-range", 5.0f, 1.0f..6.0f, parent = breakSetting.value) { !grimBreakRange.value }
    val limitBreak = bool("limit-break", true, parent = breakSetting.value)
    val randomizeDelay = bool("randomize-delay", false, parent = breakSetting.value)
    val pause = group("pause")
    val mining = bool("mining", false, parent = pause.value)
    val eating = bool("eating", false, parent = pause.value)
    val inventory = bool("inventory", false, parent = pause.value)
    val switchPause = int("switch-pause", 0, 0..10, parent = pause.value)
    val offHand = int("off-hand", 2, 0..5, parent = pause.value)
    val damages = group("damages")
    val assumeBestArmor = bool("assume-best-armor", false, parent = damages.value)
    val minDamage = float("min-damage", 6.0f, 2.0f..20.0f, parent = damages.value)
    val maxSelfDamage = float("max-self-damage", 10.0f, 2.0f..20.0f, parent = damages.value)
    val minDamageFactor = float("min-damage-factor", 0.1f, 0.1f..5.0f, parent = damages.value)
    val lethalMultiplier = int("lethal-multiplier", 1, 1..3, parent = damages.value)
    val overrideSelfDamage = bool("override-self-damage", true, parent = damages.value)
    val overrideMinDamage = bool("override-min-damage", true, parent = damages.value)
    val armorPercent = float("armor-percent", 5.0f, 0.0f..40.0f, parent = damages.value) { overrideMinDamage.value }
    val facePlaceHealth = float("face-place-health", 5.0f, 0.0f..20.0f, parent = damages.value) { overrideMinDamage.value }
    val antiEChestFarm = bool("anti-e-chest-farm", true, parent = damages.value) { overrideMinDamage.value }
    val antiPortal = bool("anti-portal", true, parent = damages.value) { overrideMinDamage.value }
    val antiQuiver = bool("anti-quiver", true, parent = damages.value) { overrideMinDamage.value }
    val destructibleTerrain = bool("destructible-terrain", true, parent = damages.value)
    val friendProtection = enum("friend-protection", FriendProtection.ONLY_LETHAL, parent = damages.value)
    val calcShields = bool("calc-shields", false, parent = damages.value)
    val damageSync = enum("damage-sync", DamageSync.OFF, parent = damages.value)
    val facePlaceSync = bool("face-place-sync", true, parent = damages.value) { damageSync.value == DamageSync.CONDITIONS }
    val lowCrystals = bool("low-crystals", true, parent = damages.value) { damageSync.value == DamageSync.CONDITIONS }
    val stacksLeft = float("stacks-left", 1.5f, 0.5f..5.0f, parent = damages.value) { damageSync.value == DamageSync.CONDITIONS && lowCrystals.value }
    val minimizeSelfDamage = bool("minimize-self-damage", true, parent = damages.value)
    val maxDamageReduction = float("max-damage-reduction", 1.5f, 0.01f..10.0f, parent = damages.value) { minimizeSelfDamage.value }
    val swap = group("swap")
    val gappleSwitch = bool("gapple-switch", true, parent = swap.value)
    val switch = enum("switch", Switch.NORMAL, parent = swap.value)
    val antiWeakness = enum("anti-weakness", Switch.SILENT, parent = swap.value)
    val idPredict = group("id-predict")
    val predict2 = bool("predict", false, parent = idPredict.value)
    val idOffset = int("id-offset", 1, 1..10, parent = idPredict.value)
    val idAttacks = int("id-attacks", 3, 1..10, parent = idPredict.value)
    val idDelay = int("id-delay", 25, 0..500, parent = idPredict.value)
    val predictOnlyInDuels = bool("predict-only-in-duels", false, parent = idPredict.value)
    val swingOnce = bool("swing-once", false, parent = idPredict.value)
    val onlyBreakingSurround = bool("only-breaking-surround", false, parent = idPredict.value)
    val targeting = group("targeting")
    val targetSort = enum("target-sort", TargetSort.DISTANCE, parent = targeting.value)
    val ignoreCreative = bool("ignore-creative", true, parent = targeting.value)
    val ignoreNaked = bool("ignore-naked", false, parent = targeting.value)
    val render = group("render")
    val fakeCrystalRemove = bool("fake-crystal-remove", false, parent = render.value)
    val removeDelay = int("remove-delay", 50, 0..200, parent = render.value) { fakeCrystalRemove.value }
    val highlightTarget = enum("highlight-target", Esp.COMETS, parent = render.value)
    val renderMode = enum("render-mode", RenderMode.SHADER, parent = render.value)
    val textPattern = string("text-pattern", "damage / self", parent = render.value)
    val alpha = float("alpha", 0.4f, 0.0f..1.0f, parent = render.value)

    /** What a crystal there does to the target and to us. */
    data class DamageResult(val damage: Float, val self: Float, val override: Boolean, val lethal: Boolean)

    /** A placement (a [BlockHitResult]) or a crystal, with its damage. */
    data class Scored<T>(val data: T, val res: DamageResult)

    class BreakTarget(val crystal: EndCrystal, val forced: Boolean)

    /** The base last placed on, for the render. */
    class RenderRecord(val pos: BlockPos, val side: Direction, val damage: Float, val self: Float)

    /** What one calculation sees (the entity list and the target are taken once). */
    private class Calc(val entities: List<Entity>, val target: Player)

    @Volatile
    var target: Player? = null
        private set
    val placeResult = AtomicReference<Scored<BlockHitResult>?>(null)
    private val breakResult = AtomicReference<BreakTarget?>(null)

    /** A crystal was brought in with an inventory swap while Speed Mine works (so its hotbar slot isn't touched). */
    val altSwapActive = AtomicBoolean(false)
    private val breakTimer = TickTimer()
    private val placeTimer = TickTimer()
    private val switchTimer = TickTimer()
    private val deathPause = MsTimer()
    private val rotationTimer = MsTimer()
    private val rotationTarget = AtomicReference<Vec3?>(null)

    @Volatile
    var renderRecord: RenderRecord? = null
        private set
    private var deferredBreak: (() -> Unit)? = null
    private var deferredPlace: (() -> Unit)? = null

    @Volatile
    private var rotatedThisTick = false
    private var recursionGuard = false

    /** The last 10 bases placed on. */
    val recentPlacements = LinkedList<BlockPos>()

    /** Blocks Speed Mine just broke, with when. */
    val minedMap = ConcurrentHashMap<BlockPos, Long>()

    /** The last place-to-spawn time ("ping" in the HUD widget) and calculation time ("cpu"). */
    var placeToSpawnMs = 0L
        private set
    var calcTimeMs = 0L
        private set
    var breakCount = 0
        private set

    /** Our own coming SetCarriedItem packets, which mustn't count as the player switching. */
    @Volatile
    private var ignoreSlotPackets = 0

    /** A crystal that blocked a placement; the next break search tries it first. */
    @Volatile
    private var forcedNext: EndCrystal? = null

    /** The render fade (OUT_BACK over 1 s), restarted from 1 on each place. */
    val fade = Fade()

    /** TECHNO's ripples: a counter from 8 per place, ticked down while TECHNO is the render mode. */
    val ripples = ArrayList<IntArray>()

    init {
        Explosions.assumeBestArmor = { enabled && assumeBestArmor.value }
        CrystalTracker.notify = { failNotify.value }
        on<PlayerUpdateEvent> { onTick() }
        on<PostSyncEvent> {
            deferredBreak?.invoke()
            deferredBreak = null
            deferredPlace?.invoke()
            deferredPlace = null
        }
        on<EntitySpawnEvent> { e ->
            val c = e.entity
            if (c is EndCrystal && spawnTiming.value == SpawnTiming.ADD_TO_WORLD) onCrystalSpawn(c.id, c.position())
        }
        on<ReceivePacketEvent> { e -> receive(e) }
        on<SendPacketEvent> { e ->
            if (e.packet is ServerboundSetCarriedItemPacket) {
                if (ignoreSlotPackets > 0) ignoreSlotPackets-- else switchTimer.reset()
            }
        }
        on<Render3DEvent> { render() }
        // fake-crystal-remove: an attacked crystal (and the items near it) stops showing between remove-delay and 200 ms.
        on<RenderEntityEvent> { e ->
            if (!fakeCrystalRemove.value || target == null) return@on
            val elapsed = System.currentTimeMillis() - CrystalTracker.attackTime(e.entity.id)
            if (removeDelay.value <= elapsed && elapsed < 201) e.cancel()
        }
    }

    override fun onEnable() = reset()

    override fun onDisable() = reset()

    private fun reset() {
        CrystalTracker.clear()
        recentPlacements.clear()
        breakTimer.reset()
        placeTimer.reset()
        breakResult.set(null)
        placeResult.set(null)
        renderRecord = null
        target = null
        placeToSpawnMs = 0
        ignoreSlotPackets = 0
        breakCount = 0
        deferredPlace = null
        deferredBreak = null
    }

    private fun receive(e: ReceivePacketEvent) {
        try {
            when (val pk = e.packet) {
                is ClientboundAddEntityPacket -> {
                    IdPredict.observe(pk.id)
                    if (pk.type == EntityTypes.END_CRYSTAL && target != null && spawnTiming.value == SpawnTiming.PACKET) {
                        val pos = Vec3(pk.x, pk.y, pk.z)
                        if (asyncCalc.value) Thread { onCrystalSpawn(pk.id, pos) }.start() else onCrystalSpawn(pk.id, pos)
                    }
                }
                is ClientboundExplodePacket -> {
                    val level = mc.level ?: return
                    val now = System.currentTimeMillis()
                    for (c in level.entitiesForRendering()) {
                        if (c is EndCrystal && c.distanceToSqr(pk.center()) <= 144) CrystalTracker.attacked.putIfAbsent(c.id, now)
                    }
                }
                is ClientboundEntityEventPacket -> {
                    val t = target
                    if (pk.eventId.toInt() == 3 && t != null && pk.getEntity(mc.level ?: return) === t) deathPause.reset()
                }
                is ClientboundSoundPacket -> {
                    val s = pk.sound.value()
                    if (s == SoundEvents.ANVIL_PLACE || s == SoundEvents.ANVIL_LAND || s == SoundEvents.ANVIL_FALL) Sync.anvilTimer.reset()
                }
            }
        } catch (ex: Exception) {
            mc.player?.sendSystemMessage(Component.literal("AUTO CRYSTAL PACKET HANDLE ERROR"))
            ex.printStackTrace()
        }
    }

    /** Prediction ticks for the target. */
    private fun targetTicks(): Int = when (predict.value) {
        Predict.AUTO -> min(floor(PlayerState.ping * 1.5 / 50.0).toInt(), 10)
        Predict.CUSTOM -> predictTicks.value
        Predict.OFF -> 0
    }

    /** Prediction ticks for ourselves. */
    private fun selfTicks(self: Boolean): Int = when (predict.value) {
        Predict.AUTO -> if (self) min(floor(PlayerState.ping / 50.0).toInt(), 10) else 0
        Predict.CUSTOM -> if (self) predictTicks.value else 0
        Predict.OFF -> 0
    }

    private fun onTick() {
        val player = mc.player ?: return
        if (renderMode.value == RenderMode.TECHNO) ripples.removeIf { r -> (r[0]--) < 0 }
        val searchRange = max(placeRange.value, breakRange.value) + 12.0
        val now = System.currentTimeMillis()
        for ((pos, t) in minedMap.entries.toList()) if ((now - t) - max(100, PlayerState.ping) * 2.0 > 0) minedMap.remove(pos)
        val old = target
        if (old != null && old.isDeadOrDying) {
            target = null
            return
        }
        val t = TargetFinder.find(searchRange, TargetFinder.Sort.valueOf(targetSort.value.name), ignoreNaked.value, ignoreCreative.value)
        target = t
        if (t == null) {
            placeResult.set(null)
            Sync.reset()
            return
        }
        val calc = calc(t)
        if (asyncCalc.value) Thread { calculate(calc) }.start() else calculate(calc)
        rotatedThisTick = false
        if (!player.offhandItem.`is`(Items.END_CRYSTAL) && gappleSwitch.value && mc.options.keyUse.isDown && player.mainHandItem.`is`(Items.END_CRYSTAL)) {
            Inventory.hotbarFind(Items.ENCHANTED_GOLDEN_APPLE)?.let { Inventory.selectHotbar(it.slot) }
        }
        doActions()
        if (!rotate.value) return
        val v = rotationTarget.get() ?: return
        RotationManager.queue(RotationRequest(Rotation.lookAt(v), 140) {})
        if (rotationTimer.passed(500)) {
            rotationTarget.set(null)
            renderRecord = null
        }
    }

    private fun calc(t: Player) = Calc(ArrayList(mc.level!!.entitiesForRendering().toList()), t)

    private fun calculate(c: Calc) {
        placeResult.set(bestPlace(c))
        breakResult.set(bestBreak(c))
    }

    /** Every base within place range, best by [select]. */
    private fun bestPlace(c: Calc): Scored<BlockHitResult>? {
        if (mc.player == null) return null
        val t0 = System.currentTimeMillis()
        val best = select(search(c, false, false), c)
        calcTimeMs = System.currentTimeMillis() - t0
        return best
    }

    /** [evaluatePlacement] over the place-range cube around the eye block. */
    private fun search(c: Calc, support: Boolean, allowWater: Boolean): List<Scored<BlockHitResult>> {
        val player = mc.player ?: return emptyList()
        val center = BlockPos.containing(player.eyePosition)
        val r = ceil(placeRange.value).toInt()
        val feet = Predictor.feet(player, 1)
        val cands = ArrayList<Scored<BlockHitResult>>()
        for (x in center.x - r..center.x + r) for (y in center.y - r..center.y + r) for (z in center.z - r..center.z + r) {
            evaluatePlacement(BlockPos(x, y, z), feet, c, support, allowWater)?.let { cands += it }
        }
        return cands
    }

    /**
     * For auto-base-place (on its worker thread): the best base that isn't one yet ([support]: Auto
     * Base Place can place obsidian there), if it beats our current placement and, by more than 1, every real base.
     */
    fun supportSearch(onlyNoPositions: Boolean, allowWater: Boolean): BlockHitResult? {
        val c = calc(target ?: throw IllegalStateException("no target"))
        // Float comparisons as sign tests: NaN counts as "greater".
        fun sign(f: Float) = if (f == 0.0f) 0 else if (f < 0.0f) -1 else 1
        val nd = select(search(c, false, allowWater), c)?.res?.damage ?: 0.0f
        if (onlyNoPositions && sign(nd) != 0) return null
        val sup = select(search(c, true, allowWater), c)
        val sd = sup?.res?.damage ?: 0.0f
        val cur = placeResult.get()?.res?.damage ?: 0.0f
        if (sign(sd - cur) <= 0) return null
        if (sign(nd - sd) >= 0) return null
        return if (sign(abs(sd - nd) - 1.0f) > 0) sup?.data else null
    }

    /** [support] and [allowWater] only come from [supportSearch]. */
    private fun evaluatePlacement(pos: BlockPos, predictedFeet: Vec3, c: Calc, support: Boolean = false,
                                  allowWater: Boolean = false): Scored<BlockHitResult>? {
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        if (CrystalTracker.placeAttemptFailed(pos)) return null
        if (calcShields.value && shieldsFrom(c.target, pos)) return null
        val top = Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0)
        if (c.target.position().distanceTo(top) > 12.0) return null
        val baseState = level.getBlockState(pos)
        if (support) {
            // A base to be: an open cell Auto Base Place can put obsidian in (so an existing obsidian base doesn't count).
            if (!baseState.canBeReplaced()) return null
            if (!Modules.get<AutoBasePlace>().canPlace(pos)) return null
        } else if (baseState.block != Blocks.OBSIDIAN && baseState.block != Blocks.BEDROCK) {
            return null
        }
        val above = level.getBlockState(pos.above())
        var canClear = false
        if (removeLava.value && above.block in CLEARABLE) {
            canClear = Inventory.hotbarFind(Items.STRING) != null || Inventory.hotbarFind(Items.REDSTONE) != null ||
                Inventory.hotbarFind(Items.TORCH) != null
        }
        if (!(above.isAir || (above.block == Blocks.FIRE && removeFire.value) || (allowWater && above.block == Blocks.WATER) || canClear)) return null
        if (n112.value && !level.isEmptyBlock(pos.above(2))) return null
        if (placementBlocked(pos, true, c)) return null
        val dmg = evaluateDamage(pos, c, force = false, usePrediction = true, support = support) ?: return null
        val hit = placementHit(pos, support) ?: return null
        val eyeP = predictedFeet.add(0.0, player.getEyeHeight(player.pose).toDouble(), 0.0)
        val breakable = when {
            rayTrace.value -> visiblePoint(top, eyeP) != null
            grimBreakRange.value -> GrimRange.check(top, hit.location)
            else -> eyeP.distanceTo(hit.location) <= breakRange.value
        }
        if (!breakable) return null
        return Scored(hit, dmg)
    }

    /** The target holds up a shield facing the crystal. */
    private fun shieldsFrom(t: Player, pos: BlockPos): Boolean {
        if (!t.isUsingItem || !(t.offhandItem.`is`(Items.SHIELD) || t.mainHandItem.`is`(Items.SHIELD))) return false
        // Vanilla's calculateViewVector(0, yHeadRot): level, along the head's yaw.
        val g = (-t.yHeadRot * (Math.PI.toFloat() / 180f)).toDouble()
        val view = Vec3((Mth.sin(g) * Mth.cos(0.0)).toDouble(), (-Mth.sin(0.0)).toDouble(), (Mth.cos(g) * Mth.cos(0.0)).toDouble())
        val d = Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0).vectorTo(t.position())
        return Vec3(d.x, 0.0, d.z).normalize().dot(view) < 0
    }

    private fun dist(p: Vec3) = p.distanceTo(PlayerState.eye)

    private fun dist(x: Double, y: Double, z: Double) = Vec3(x, y, z).distanceTo(PlayerState.eye)

    /** Where to click the base ([support]: the planned base counts as a full block for the ray). */
    private fun placementHit(pos: BlockPos, support: Boolean = false): BlockHitResult? {
        val level = mc.level ?: return null
        val eye = PlayerState.eye
        val range = placeRange.value
        if (rayTrace.value) {
            // A TreeMap by distance on purpose: offsets the same distance away collapse into the first.
            val sorted = java.util.TreeMap<Double, Vec3>()
            for (off in RAY_OFFSETS) sorted.putIfAbsent(dist(pos.x + off.x, pos.y + off.y, pos.z + off.z), off)
            for (off in sorted.values) {
                val p = Vec3(pos.x + off.x, pos.y + off.y - 0.01, pos.z + off.z)
                if (dist(p) > range) continue
                val h = Explosions.raycast(eye, p, false, emptyList(), if (support) listOf(pos) else emptyList())
                if (h != null && h.blockPos == pos) return h
            }
            return null
        }
        val refY = if (Modules.get<MoveFix>().enabled) pos.y + 1 else pos.y
        if (eye.y > refY) {
            val off = TOP_OFFSETS.minBy { dist(pos.x + it.x, pos.y + it.y + 1, pos.z + it.z) }
            val p = Vec3(pos.x + off.x, pos.y + 1.0, pos.z + off.z)
            if (p.distanceToSqr(eye) <= range * range) return BlockHitResult(p, Direction.UP, pos, false)
        }
        if (eye.y < pos.y && level.isEmptyBlock(pos.below())) {
            val off = TOP_OFFSETS.minBy { dist(pos.x + it.x, pos.y + it.y, pos.z + it.z) }
            val p = Vec3(pos.x + off.x, pos.y.toDouble(), pos.z + off.z)
            if (p.distanceToSqr(eye) <= range * range) return BlockHitResult(p, Direction.DOWN, pos, false)
        }
        var best: Direction? = null
        var bestD = range.toDouble()
        for (d in Placer.visibleFaces(pos)) {
            if (d == Direction.UP || d == Direction.DOWN) continue
            val p = Vec3(pos.x + 0.5 + d.stepX * 0.5, pos.y + 0.99, pos.z + 0.5 + d.stepZ * 0.5)
            if (level.getBlockState(pos.relative(d)).canBeReplaced() && bestD > dist(p)) {
                best = d
                bestD = dist(p)
            }
        }
        val b = best ?: return null
        return BlockHitResult(Vec3.atCenterOf(pos).add(b.stepX * 0.5, b.stepY * 0.5, b.stepZ * 0.5), b, pos, false)
    }

    /** Something is (or will be) in the crystal's space. */
    private fun placementBlocked(base: BlockPos, ignoreBreakable: Boolean, c: Calc): Boolean {
        val box = if (mc.currentServer?.ip?.contains("crystalpvp.cc") == true) AABB(base.above()) else AABB(base.above()).inflate(0.0, 1.0, 0.0)
        val pr = placeResult.get()
        val adjacent = Direction.entries.any { pr != null && pr.data.blockPos == base.relative(it) } ||
            Direction.entries.any { base.relative(it) in recentPlacements }
        if (minedMap.containsKey(base.above()) && !adjacent) return true
        val center = Vec3.atCenterOf(base)
        for (e in c.entities) {
            if (!e.isAlive || e.distanceToSqr(center) > 16) continue
            val hit = if (e is Player) {
                for (i in 0..targetTicks()) if (Predictor.box(e, i).intersects(box)) return true
                e.boundingBox.intersects(box)
            } else {
                e.boundingBox.intersects(box)
            }
            if (!hit) continue
            if (e is ExperienceOrb) continue
            if (e is ItemEntity) {
                if (e.isOnFire || CrystalTracker.isAttacked(e.id) || minedMap.containsKey(base.above()) || e.item.`is`(Items.STRING)) continue
                return true
            }
            if (e !is EndCrystal) return true
            if (CrystalTracker.isAttacked(e.id)) continue
            if (CrystalTracker.attackFailed(e.id)) return true
            if (ignoreBreakable) {
                if (evaluateBreak(e.position(), c, true, e.tickCount) != null) continue
                return true
            }
            if (e.position().distanceToSqr(box.center) > 0.3) forcedNext = e
            return true
        }
        return false
    }

    /** The most damage, unless another one saves enough self-damage; avoids bases about to be mined. */
    private fun <T> select(list: List<Scored<T>>, c: Calc): Scored<T>? {
        if (list.isEmpty()) return null
        var best = list[0]
        for (s in list) if (s.res.damage > best.res.damage) best = s
        if (minimizeSelfDamage.value && list.size > 1) {
            val red = maxDamageReduction.value
            for (s in list) {
                if (s == best) continue
                if (!(best.res.self > s.res.self)) continue
                if (best.res.self - s.res.self <= red) continue
                if (best.res.damage - s.res.damage > red && !s.res.lethal) continue
                if (best.res.lethal && !s.res.lethal) continue
                best = s
                break
            }
        }
        val data = best.data
        if (data is BlockHitResult && list.size > 1) {
            val mined = Modules.get<SpeedMine>().aboutToBreak(true)
            if (data.blockPos in mined) {
                val tp = c.target.blockPosition()
                if (data.blockPos.x != tp.x || data.blockPos.z != tp.z) {
                    var alt: Scored<T>? = null
                    for (s in list) {
                        if ((s.data as BlockHitResult).blockPos in mined) continue
                        if (alt == null || s.res.damage > alt.res.damage) alt = s
                    }
                    if (alt != null) return alt
                }
            }
        }
        return best
    }

    private fun bestBreak(c: Calc): BreakTarget? {
        val player = mc.player ?: return null
        val forced = forcedNext
        if (forced != null) {
            forcedNext = null
            if (evaluateBreak(forced.position(), c, true, forced.tickCount) != null) return BreakTarget(forced, true)
        }
        val cands = ArrayList<Scored<EndCrystal>>()
        for (e in c.entities) {
            if (e !is EndCrystal) continue
            if (e.isRemoved || CrystalTracker.attackFailed(e.id) || CrystalTracker.isAttacked(e.id)) continue
            if (player.position().distanceToSqr(e.position()) > 49) continue
            evaluateBreak(e.position(), c, false, e.tickCount)?.let { cands += Scored(e, it) }
        }
        return select(cands, c)?.let { BreakTarget(it.data, false) }
    }

    private fun evaluateBreak(crystal: Vec3, c: Calc, force: Boolean, tickCount: Int): DamageResult? {
        val base = BlockPos.containing(crystal.subtract(0.0, 0.1, 0.0))
        if (randomizeDelay.value) {
            val need = if (breakCount % 4 == 0) 2 else Rng.int(0, 1)
            if (tickCount < need) return null
        }
        if (rayTrace.value) {
            if (visiblePoint(crystal, PlayerState.eye) == null) return null
            return evaluateDamage(base, c, force, false)
        }
        val refY = if (Modules.get<MoveFix>().enabled) base.y + 1 else base.y
        var p = crystal
        if (PlayerState.eye.y > refY) {
            val off = TOP_OFFSETS.minBy { dist(base.x + it.x, base.y + it.y + 1, base.z + it.z) }
            p = Vec3(base.x + off.x, base.y + 1.0, base.z + off.z)
        }
        if (grimBreakRange.value) {
            if (!GrimRange.check(crystal, p)) return null
            return evaluateDamage(base, c, force, false)
        }
        if (p.distanceToSqr(PlayerState.eye) > placeRange.value * placeRange.value) return null
        return evaluateDamage(base, c, force, false)
    }

    /** A point of the crystal's box to aim at (with ray-trace, one no block is in front of). */
    private fun visiblePoint(v: Vec3, eyes: Vec3): Vec3? {
        if (rayTrace.value) {
            val pts = ArrayList<Vec3>(27)
            for (i in 0..2) for (j in 0..2) for (k in 0..2) {
                val p = Vec3(v.x - 1 + i, v.y + j, v.z - 1 + k)
                val ok = if (grimBreakRange.value) GrimRange.check(v, p) else eyes.distanceTo(p) <= breakRange.value
                if (ok) pts += p
            }
            pts.sortBy { eyes.distanceTo(it) }
            return pts.firstOrNull { Explosions.raycast(eyes, it, false, emptyList(), emptyList()) == null }
        }
        val e = mc.player?.eyePosition ?: return null
        return Vec3(Mth.clamp(e.x, v.x - 1, v.x + 1), Mth.clamp(e.y, v.y, v.y + 2), Mth.clamp(e.z, v.z - 1, v.z + 1))
    }

    /** [support]: the base isn't there yet, so it counts as a full block, and nothing as mined. */
    private fun evaluateDamage(pos: BlockPos, c: Calc, force: Boolean, usePrediction: Boolean, support: Boolean = false): DamageResult? {
        val player = mc.player ?: return null
        val center = Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0)
        val mined = if (usePrediction) Modules.get<SpeedMine>().aboutToBreak(true) else emptyList()
        val ghost = if (support) emptyList() else mined
        val solid = if (support) listOf(pos) else emptyList()
        val tTicks = if (usePrediction) targetTicks() else 0
        val terrain = destructibleTerrain.value
        val t = c.target
        val dmg = Explosions.damage(center, t, tTicks, terrain, ghost, solid)
        val passesMin = overrideMin(dmg, c) || dmg > minDamage.value
        if (!passesMin && !force) return null
        var self = Explosions.damage(center, player, selfTicks(usePrediction), terrain, ghost, solid)
        val overrideSelf = overrideSelfDamage.value && self > maxSelfDamage.value &&
            dmg * lethalMultiplier.value > t.health + t.absorptionAmount && self < player.health + player.absorptionAmount
        if (friendProtection.value != FriendProtection.NONE) {
            for (f in c.entities) {
                if (f !is Player || !f.isAlive || !Friends.isFriend(f.gameProfile.name)) continue
                val fd = Explosions.damage(center, f, tTicks, terrain, ghost, solid)
                if (friendProtection.value == FriendProtection.FULL) self = max(self, fd)
                else if (fd >= f.health + f.absorptionAmount) return null
            }
        }
        if (!overrideSelf) {
            if (self > player.health + player.absorptionAmount) return null
            if (!(dmg / self >= minDamageFactor.value)) return null
        }
        if (self > maxSelfDamage.value && !overrideSelf) return null
        return DamageResult(dmg, self, overrideSelf, dmg >= t.health + t.absorptionAmount)
    }

    /** Face-place, worn-armour and lethal reasons to go under min-damage. */
    private fun overrideMin(dmg: Float, c: Calc): Boolean {
        if (dmg < 0.6f || !overrideMinDamage.value) return false
        val t = c.target
        val aura = Modules.get<Aura>()
        val hp = t.health + t.absorptionAmount
        if (dmg * lethalMultiplier.value > hp) {
            if (dmg > 4.0f) return true
            if (!aura.isAttacking()) return true
        }
        for (i in intArrayOf(39, 38, 37, 36)) {
            val s = t.inventory.getItem(i)
            if (s.isEmpty) continue
            if ((s.maxDamage - s.damageValue).toFloat() / s.maxDamage * 100f < armorPercent.value) return true
        }
        val level = mc.level ?: return false
        if (antiPortal.value && level.getBlockState(BlockPos.containing(t.position())).`is`(Blocks.NETHER_PORTAL)) return true
        if (antiEChestFarm.value && eChestFarm()) return true
        if (antiQuiver.value && t.isHolding(Items.BOW)) {
            for (a in c.entities) {
                if (a is Arrow && a.tickCount < 5 && a.position().y > t.y && t.eyePosition.distanceToSqr(a.position()) < 9.0) return true
            }
        }
        if (hp > facePlaceHealth.value) return false
        return !aura.isAttacking()
    }

    /** A stack of 8+ obsidian lies at the target's feet (they're farming ender chests). */
    fun eChestFarm(): Boolean {
        val t = target ?: return false
        val level = mc.level ?: return false
        val bb = t.boundingBox
        val zone = bb.setMinY(bb.center.y).inflate(0.5, 0.0, 0.5)
        return level.entitiesForRendering().any {
            it is ItemEntity && it.item.`is`(Items.OBSIDIAN) && it.item.count >= 8 && it.isAlive && it.boundingBox.intersects(zone)
        }
    }

    private fun doActions() {
        val p = placeResult.get()
        val b = breakResult.get()
        if (placeRayBlocked() || !oneTickAction.value) {
            if (b != null && breakTimer.ticks >= breakDelay.value) {
                attack(b.crystal.id, b.crystal.position(), false)
                return
            }
            if (p != null && placeTimer.ticks >= placeDelay.value) place(p.data, false)
            return
        }
        if (b != null && breakTimer.ticks >= breakDelay.value) attack(b.crystal.id, b.crystal.position(), false)
        if (p != null && placeTimer.ticks >= placeDelay.value) place(p.data, false)
    }

    /** With ray-trace, the click wouldn't land where it was planned. */
    private fun placeRayBlocked(): Boolean {
        if (!rayTrace.value) return false
        val p = placeResult.get() ?: return false
        val eye = PlayerState.eye
        val hv = p.data.location
        val base = p.data.blockPos
        val c = Vec3.atCenterOf(base).add(0.0, 0.5, 0.0)
        val box = AABB(c.x - 1, c.y, c.z - 1, c.x + 1, c.y + 2, c.z + 1)
        if (box.clip(eye, hv).isEmpty && !box.contains(eye) && !box.contains(hv)) return true
        val h = Explosions.raycast(eye, hv, false, emptyList(), emptyList()) ?: return false
        return !(h.blockPos == base && h.direction == p.data.direction)
    }

    private fun requestRotation(v: Vec3) {
        rotationTarget.set(v)
        rotationTimer.reset()
    }

    private fun attack(id: Int, pos: Vec3, fromSpawn: Boolean) {
        val player = mc.player ?: return
        if (CrystalTracker.isAttacked(id) && limitBreak.value) return
        if (paused(false) || target == null) return
        if (rotate.value && !fromSpawn) {
            val box = AABB(pos.x - 1, pos.y, pos.z - 1, pos.x + 1, pos.y + 2, pos.z + 1)
            val eye = player.eyePosition
            val aim = if (crystalMultiPoint.value && !box.contains(eye))
                Vec3(Mth.clamp(eye.x, pos.x - 1, pos.x + 1), Mth.clamp(eye.y, pos.y, pos.y + 2), Mth.clamp(eye.z, pos.z - 1, pos.z + 1))
            else pos.add(0.0, 0.5, 0.0)
            requestRotation(aim)
            rotatedThisTick = true
        }
        deferredBreak = { doAttack(id, pos) }
        if (timing.value != Timing.VANILLA && !fromSpawn) return
        deferredBreak?.invoke()
        deferredBreak = null
    }

    private fun doAttack(id: Int, pos: Vec3) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        var prev = -1
        val weakness = player.getEffect(MobEffects.WEAKNESS)
        val strength = player.getEffect(MobEffects.STRENGTH)
        if (antiWeakness.value != Switch.NONE && weakness != null && !(strength != null && strength.amplifier >= weakness.amplifier)) {
            prev = weaponSwap(hotbarWeapon(), Inventory.find(::isWeapon), antiWeakness.value)
        }
        Packets.send(ServerboundAttackPacket(id))
        CrystalTracker.markAttacked(id, pos)
        val now = System.currentTimeMillis()
        for (e in level.entitiesForRendering()) {
            if (e is ItemEntity && pos.distanceToSqr(e.position()) < 2.25) CrystalTracker.attacked.putIfAbsent(e.id, now)
        }
        // A swing packet belongs here; 26.3 has none.
        breakTimer.reset()
        breakCount++
        if (prev != -1) when (antiWeakness.value) {
            Switch.INVENTORY -> Inventory.swap(prev, player.inventory.selectedSlot, true)
            Switch.SILENT -> {
                ignoreSlotPackets++
                Inventory.selectHotbar(prev)
            }
            Switch.NORMAL -> Inventory.selectHotbar(prev)
            Switch.NONE -> {}
        }
    }

    private fun isWeapon(s: ItemStack) = s.`is`(ItemTags.SWORDS) || s.`is`(ItemTags.PICKAXES) || s.`is`(ItemTags.AXES) || s.`is`(ItemTags.SHOVELS)

    /** The main hand if it's a weapon, else the first hotbar weapon. */
    private fun hotbarWeapon(): Found? {
        val p = mc.player ?: return null
        if (isWeapon(p.mainHandItem)) return Found(p.inventory.selectedSlot, p.mainHandItem)
        return Inventory.hotbarFind(::isWeapon)
    }

    /** Switch to a weapon; returns the slot to switch back to. */
    private fun weaponSwap(hotbar: Found?, inv: Found?, mode: Switch): Int {
        val p = mc.player ?: return -1
        if (mode == Switch.INVENTORY) {
            if (inv == null) return p.inventory.selectedSlot
            Inventory.swap(inv.slot, p.inventory.selectedSlot, true)
            return inv.slot
        }
        if (mode == Switch.SILENT) ignoreSlotPackets++
        val prev = p.inventory.selectedSlot
        if (hotbar != null) Inventory.selectHotbar(hotbar.slot)
        return prev
    }

    private fun place(hit: BlockHitResult, fromSpawn: Boolean) {
        if (paused(true)) return
        if (CrystalTracker.recentBoxConflict(hit.blockPos, limitPlace.value)) return
        val snapshot = placeResult.get()
        val res = snapshot?.res
        val extra = randomizeDelay.value && breakCount % 5 == 0
        if (!Sync.allow(res?.damage, res?.lethal ?: false, extra, Sync.Mode.valueOf(damageSync.value.name), facePlaceSync.value,
                lowCrystals.value, stacksLeft.value, minDamage.value, Modules.get<SpeedMine>().aboutToBreak(true).isNotEmpty())) return
        val t = target ?: return
        if (placementBlocked(hit.blockPos, false, calc(t))) return
        if (rotate.value && !rotatedThisTick) requestRotation(hit.location)
        deferredPlace = { doPlace(hit, snapshot) }
        if (timing.value != Timing.VANILLA && !fromSpawn) return
        deferredPlace?.invoke()
        deferredPlace = null
    }

    private fun doPlace(hit: BlockHitResult, data: Scored<BlockHitResult>?) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val pos = hit.blockPos
        val above = level.getBlockState(pos.above()).block
        val offCrystal = player.offhandItem.item is EndCrystalItem
        val holding = player.mainHandItem.item is EndCrystalItem || offCrystal
        var slot = Inventory.hotbarFind(Items.END_CRYSTAL)?.slot ?: -1
        var invSwap = switch.value == Switch.INVENTORY
        var prevSlot = -1
        var swapped = false
        var alternative = false
        if (removeFire.value && above == Blocks.FIRE) {
            Packets.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos.above(), Direction.UP))
        }
        if (removeLava.value && removeFire.value && above == Blocks.TRIPWIRE) {
            Packets.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos.above(), Direction.UP))
            mc.gameMode?.destroyBlock(pos.above())
        }
        if (removeLava.value && above == Blocks.LAVA) clearLava(pos)
        val speedMine = Modules.get<SpeedMine>()
        if (!holding && speedMine.aboutToBreak(true).isNotEmpty() && speedMine.resetOnSwitch.value &&
            speedMine.switchMode.value == SpeedMine.SwitchMode.Alternative) {
            alternative = true
            invSwap = true
        }
        if (invSwap) {
            if (!player.containerMenu.carried.isEmpty) return
            if (slot == -1) slot = Inventory.find(Items.END_CRYSTAL)?.slot ?: -1
        }
        if (switch.value != Switch.NONE && !holding && slot != -1) {
            prevSlot = player.inventory.selectedSlot
            if (invSwap) {
                Inventory.swap(if (slot < 9) slot + 36 else slot, player.inventory.selectedSlot, true)
                swapped = true
                altSwapActive.set(alternative)
            } else {
                if (switch.value == Switch.SILENT) ignoreSlotPackets++
                Inventory.selectHotbar(slot)
            }
        }
        if ((slot == -1 || switch.value == Switch.NONE) && !holding) return
        if (player.mainHandItem.item !is EndCrystalItem && !offCrystal && switch.value != Switch.SILENT) return
        CrystalTracker.recordPlace(pos)
        val hand = if (offCrystal) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
        Packets.sendSequenced { seq -> ServerboundUseItemOnPacket(hand, hit, seq) }
        renderRecord = RenderRecord(pos, hit.direction, data?.res?.damage ?: 1f, data?.res?.self ?: 1f)
        player.swing(hand, SwingAnimation.DEFAULT, false)
        PlacementLock.lock(pos)
        for (d in Direction.entries) PlacementLock.lock(pos.relative(d))
        if (renderMode.value == RenderMode.TECHNO) ripples += intArrayOf(8)
        if (predict2.value && (!predictOnlyInDuels.value || level.worldBorder.size == 100.0) &&
            (!onlyBreakingSurround.value || speedMine.aboutToBreak(false).isNotEmpty())) {
            IdPredict.burst(idOffset.value, idAttacks.value, idDelay.value)
            if (!recursionGuard) {
                recursionGuard = true
                place(hit, true)
            }
            recursionGuard = false
        }
        placeTimer.reset()
        Sync.onPlaced(data?.res?.damage ?: 0f)
        recentPlacements.add(pos)
        while (recentPlacements.size > 10) recentPlacements.poll()
        fade.restart(1f)
        if (switch.value != Switch.SILENT && !swapped) return
        if (prevSlot == -1) return
        if (swapped) {
            Inventory.swap(if (slot < 9) slot + 36 else slot, player.inventory.selectedSlot, true)
            return
        }
        if (switch.value == Switch.SILENT) {
            ignoreSlotPackets++
            Inventory.selectHotbar(prevSlot)
        }
    }

    /** Put string (or redstone, or a torch) on the lava, then break it. */
    private fun clearLava(pos: BlockPos) {
        val player = mc.player ?: return
        val item = Inventory.hotbarFind(Items.STRING) ?: Inventory.hotbarFind(Items.REDSTONE) ?: Inventory.hotbarFind(Items.TORCH) ?: return
        Inventory.saveAndSelect(item.slot)
        mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, BlockHitResult(Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0), Direction.UP, pos, false))
        Packets.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos.above(), Direction.UP))
        mc.gameMode?.destroyBlock(pos.above())
        Inventory.restore()
    }

    private fun paused(isPlace: Boolean): Boolean {
        val player = mc.player ?: return true
        if (!deathPause.passed(500)) return true
        if (Modules.get<AutoMend>().mending) return true
        if (Modules.get<Blink>().enabled) return true
        val speedMine = Modules.get<SpeedMine>()
        if (speedMine.toolSwapInFlight.get()) return true
        val offCrystal = player.offhandItem.`is`(Items.END_CRYSTAL)
        if (mc.gameMode?.isDestroying == true && !offCrystal && mining.value) return true
        if (!isPlace && switchPause.value > 0 && switchTimer.ticks < switchPause.value) return true
        if (offHand.value > 0 && (5 - Modules.get<Offhand>().swapCooldown) < offHand.value) return true
        if (isPlace) {
            when (switch.value) {
                Switch.NONE -> if (!player.mainHandItem.`is`(Items.END_CRYSTAL) && !offCrystal) return true
                Switch.SILENT, Switch.NORMAL -> if (Inventory.hotbarFind(Items.END_CRYSTAL) == null && !offCrystal) return true
                Switch.INVENTORY -> if (Inventory.find(Items.END_CRYSTAL) == null && !offCrystal) return true
            }
            if (speedMine.aboutToBreak(true).isEmpty()) altSwapActive.set(false)
            if (altSwapActive.get()) return true
        }
        if (player.isUsingItem && eating.value) return true
        if (inventory.value && PlayerState.containerOpen) return true
        val main = player.mainHandItem
        return !offCrystal && gappleSwitch.value && mc.options.keyUse.isDown &&
            (main.`is`(Items.ENCHANTED_GOLDEN_APPLE) || main.`is`(Items.GOLDEN_APPLE)) && switch.value != Switch.SILENT && switch.value != Switch.INVENTORY
    }

    /** A crystal appeared; react without waiting for the next tick. */
    private fun onCrystalSpawn(id: Int, pos: Vec3) {
        val rec = CrystalTracker.matchSpawn(pos) ?: return
        placeToSpawnMs = System.currentTimeMillis() - rec.time
        val t = target ?: return
        if (breakTimer.ticks < breakDelay.value) return
        if (onCrystalSpawn.value == OnCrystalSpawn.NO_REACTION) return
        if (placeRayBlocked() || !oneTickAction.value || randomizeDelay.value) return
        val c = calc(t)
        if (rotate.value) requestRotation(pos)
        if (evaluateBreak(pos, c, false, 0) != null) attack(id, pos, true)
        if (onCrystalSpawn.value == OnCrystalSpawn.ACTION_X) {
            if (placeTimer.ticks < placeDelay.value) return
            placeResult.get()?.let { place(it.data, true) }
        }
    }

    private fun render() {
        CrystalTracker.cleanup()
        val p = mc.player ?: return
        val t = target
        if (t != null && highlightTarget.value == Esp.COMETS) {
            WorldEsp.comets(t, 1f - Mth.clamp(p.distanceTo(t) / 12f, 0f, 1f))
        }
        val rec = renderRecord ?: return
        if (renderMode.value == RenderMode.OFF) return
        fade.update(0f)
        val box = AABB(rec.pos)
        val text = textPattern.value.replace("damage", fmt(rec.damage).toString()).replace("self", fmt(rec.self).toString())
        when (renderMode.value) {
            RenderMode.SHADER -> World3D.shaderBox(box, PlaceBoxes.withAlpha(Theme.primary, alpha.value * fade.value))
            RenderMode.DEAFULT -> {
                World3D.fill(box, PlaceBoxes.withAlpha(Theme.primary, alpha.value * fade.value))
                World3D.outline(box, PlaceBoxes.withAlpha(Theme.primary, fade.value))
            }
            RenderMode.TECHNO -> WorldEsp.techno(box, ripples)
            RenderMode.OFF -> {}
        }
        World3D.label(text, box.center, PlaceBoxes.withAlpha(Theme.text, fade.value))
    }

    /** One decimal, HALF_UP (1.0 for NaN or infinity). */
    private fun fmt(f: Float): Float =
        if (f.isNaN() || f.isInfinite()) 1.0f else java.math.BigDecimal(f.toDouble()).setScale(1, java.math.RoundingMode.HALF_UP).toFloat()

    /** A value easing (OUT_BACK, 1 s) from where it was towards a target. */
    class Fade {
        private var start = 0f
        private var target = 0f
        private var time = 0L
        var value = 0f
            private set

        fun restart(from: Float) {
            start = from
            value = from
            time = System.currentTimeMillis()
        }

        fun update(to: Float) {
            if (to != target) {
                start = value
                target = to
                time = System.currentTimeMillis()
            }
            val e = System.currentTimeMillis() - time
            value = if (e > 1000) target else start + (target - start) * Easing.OUT_BACK.apply(e / 1000.0).toFloat()
        }
    }

    private companion object {
        val CLEARABLE = setOf(Blocks.LAVA, Blocks.TRIPWIRE, Blocks.REDSTONE_WIRE, Blocks.TORCH)

        /** Face centres, then corners inset by 0.05. */
        val RAY_OFFSETS = listOf(
            Vec3(0.5, 1.0, 0.5), Vec3(0.5, 0.0, 0.5), Vec3(0.5, 0.5, 0.0), Vec3(0.5, 0.5, 1.0), Vec3(0.0, 0.5, 0.5), Vec3(1.0, 0.5, 0.5),
            Vec3(0.05, 0.05, 0.05), Vec3(0.05, 0.05, 0.95), Vec3(0.05, 0.95, 0.05), Vec3(0.05, 0.95, 0.95),
            Vec3(0.95, 0.05, 0.05), Vec3(0.95, 0.05, 0.95), Vec3(0.95, 0.95, 0.05), Vec3(0.95, 0.95, 0.95),
        )

        /** Points on a top face; the order matters (the first closest wins). */
        val TOP_OFFSETS = listOf(
            Vec3(0.51, 0.0, 0.49), Vec3(0.05, 0.0, 0.05), Vec3(0.05, 0.0, 0.95), Vec3(0.95, 0.0, 0.05), Vec3(0.95, 0.0, 0.95),
            Vec3(0.475, 0.0, 0.95), Vec3(0.475, 0.0, 0.05), Vec3(0.95, 0.0, 0.025), Vec3(0.05, 0.0, 0.475),
        )
    }
}
