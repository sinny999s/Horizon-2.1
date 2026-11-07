package dev.horizon.client.module.attack

import dev.horizon.client.combat.Bedwars
import dev.horizon.client.combat.InstantRequest
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.Swings
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.compat.Via
import dev.horizon.client.event.CrosshairEvent
import dev.horizon.client.event.MouseTurnEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PostTasksEvent
import dev.horizon.client.event.PostTickEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.SprintEvent
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Esp
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.Sprint as SprintModule
import dev.horizon.client.module.legit.FireballDeflect
import dev.horizon.client.module.legit.PearlSave
import dev.horizon.client.module.rage.Blink
import dev.horizon.client.module.rage.ElytraTarget
import dev.horizon.client.module.rage.MaceElytraTarget
import dev.horizon.client.render.WorldEsp
import dev.horizon.client.setting.GroupMode
import dev.horizon.client.setting.ValueLabels
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.tags.ItemTags
import net.minecraft.util.Mth
import net.minecraft.util.profiling.Profiler
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemUseAnimation
import kotlin.random.Random

/**
 * The original's "aura" (`jv`): every tick picks a target, turns towards it with the chosen rotation mode, and hits it when
 * the attack timer, sprint handling and the ray (or line of sight) allow.
 *
 * Extended with ViaFabricPlus 1.8.x auto-detection ([combatMode]), millisecond-paced humanized 1.8 clicking, 1.8
 * swing-before-attack packet order, and tunable [LegitRotation] aim (a mouse spring, or human submovements: [HumanAim]).
 */
class Aura : Module("aura", Categories.ATTACK) {
    enum class Point { CENTER, EYES, CLOSEST, ROAMING, RUST }
    enum class PointJitter { NONE, RANDOM, ROAMING, SINE, DRUNK_SINE }
    enum class SpeedDistribution { FLAT, NORMAL }
    enum class YawSmoother { LINEAR, IN_QUAD, OUT_QUAD, IN_OUT_QUAD, IN_CUBIC, OUT_CUBIC, IN_QUART, OUT_QUART, IN_OUT_QUART, IN_QUINT, OUT_QUINT, IN_OUT_QUINT, IN_SINE, OUT_SINE, IN_OUT_SINE, IN_EXPO, OUT_EXPO, IN_OUT_EXPO, IN_CIRC, OUT_CIRC, IN_OUT_CIRC, SIGMOID, SIGMOID_2, SIGMOID_3, SIGMOID_4, SIGMOID_5, SIGMOID_6, SIGMOID_7, OUT_ELASTIC, IN_BACK, OUT_BACK, DECELERATE, IN_OUT_CUBIC }
    enum class HoldTiming { BEFORE_RANDOM, AFTER_RANDOM }
    enum class FilterLowDeltas { REMOVE, REPLACE, OFF }
    enum class RotationMode { SNAP, DEFAULT, NONE, VULCAN, HVH, POLAR_SEP_25, GRIM_SILENT, ADVANCED, CUSTOM, LEGIT }
    enum class LegitSpot { WANDER, CLOSEST, CHEST, HEAD }
    /** Not in the original: LEGIT's aim, a mouse spring (SPRING, the default) or human submovements ([HumanAim], HUMAN). */
    enum class AimModel { SPRING, HUMAN }
    enum class CombatMode { AUTO, COOLDOWN, LEGACY }
    /** BLOCK_HIT / HOLD: sword blocking around Aura's hits. LEGIT (not in the original): block-hits with the use key (see ShieldHook). */
    enum class AutoBlock { OFF, BLOCK_HIT, HOLD, LEGIT }
    enum class AutoWeapon { NORMAL, NONE, SILENT }
    enum class WhileShielding { PAUSE, IGNORE, UNPRESS, LEGIT }
    enum class RandomizeDelay { OFF, VULCAN, DEFAULT, LEGIT }
    enum class Sprint { LEGIT, LEGIT_EXTRA, NORMAL, HVH }
    enum class AntibotMode { AUTO, ARMOR, TAB_LIST }
    @Suppress("EnumEntryName")
    enum class Sort { Distance, Health, FOV }

    /** How far targets are looked for and tracked; hits only land within [reach]. */
    val range = float("range", 3.0f, 3.0f..6.0f)
    /**
     * Not in the original (whose `range` was also its reach): how far a hit may land, vanilla's 3 by default, so a long
     * `range` only finds and follows targets from further away. Ghost Mode holds it at 3.
     */
    val reach = float("reach", 3.0f, 3.0f..6.0f)
    val ignoreWalls = bool("ignore-walls", true)
    val fov = int("fov", 180, 1..180).also { ValueLabels.register("fov", 1, "trigger") }
    /** Not in the original: no attacks in a Bedwars lobby or waiting room, back to it when the match starts ([Bedwars]). */
    val lobbyOff = bool("lobby-off", true)
    val advanced = group("advanced") { rotationMode.value == RotationMode.ADVANCED }
    val point = enum("point", Point.CENTER, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pointJitter = enum("point-jitter", PointJitter.NONE, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pointVRange = float("point-v-range", 0.1f, 0.01f..1.8f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pointHRange = float("point-h-range", 0.1f, 0.01f..0.3f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pointHFreq = float("point-h-freq", 20.0f, 1.0f..30.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pointVFreq = float("point-v-freq", 10.0f, 1.0f..30.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val speedDistribution = enum("speed-distribution", SpeedDistribution.FLAT, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val minYawSpeed = float("min-yaw-speed", 30.0f, 0.1f..180.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val maxYawSpeed = float("max-yaw-speed", 60.0f, 0.1f..180.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val minPitchSpeed = float("min-pitch-speed", 10.0f, 0.1f..180.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val maxPitchSpeed = float("max-pitch-speed", 20.0f, 0.1f..180.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val jitterDistribution = enum("jitter-distribution", SpeedDistribution.NORMAL, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val yawJitter = enum("yaw-jitter", PointJitter.NONE, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pitchJitter = enum("pitch-jitter", PointJitter.NONE, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val yawAmplMin = float("yaw-ampl-min", 5.0f, 1.0f..30.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pitchAmplMin = float("pitch-ampl-min", 3.0f, 1.0f..30.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val yawAmplMax = float("yaw-ampl-max", 10.0f, 1.0f..30.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pitchAmplMax = float("pitch-ampl-max", 5.0f, 1.0f..30.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val yawFreqMin = float("yaw-freq-min", 20.0f, 1.0f..30.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pitchFreqMin = float("pitch-freq-min", 10.0f, 1.0f..30.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val yawFreqMax = float("yaw-freq-max", 40.0f, 1.0f..30.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pitchFreqMax = float("pitch-freq-max", 20.0f, 1.0f..30.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val smoothBoxExpand = float("smooth-box-expand", 0.0f, 0.1f..5.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val yawAccel = float("yaw-accel", 1.0f, 0.1f..1.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pitchAccel = float("pitch-accel", 1.0f, 0.1f..1.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val yawDecel = float("yaw-decel", 0.0f, 0.0f..1.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pitchDecel = float("pitch-decel", 0.0f, 0.0f..1.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val yawSmoother = enum("yaw-smoother", YawSmoother.LINEAR, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pitchSmoother = enum("pitch-smoother", YawSmoother.LINEAR, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val minFactorYaw = float("min-factor-yaw", 0.5f, 0.0f..1.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val minFactorPitch = float("min-factor-pitch", 0.5f, 0.0f..1.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val clientLook = bool("client-look", false, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val overShoot = bool("over-shoot", false, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val osTrigger = float("os-trigger", 45.0f, 20.0f..180.0f, parent = advanced.value) { overShoot.value && rotationMode.value == RotationMode.ADVANCED }
    val osStreak = int("os-streak", 3, 1..10, parent = advanced.value) { overShoot.value && rotationMode.value == RotationMode.ADVANCED }
    val osValue = float("os-value", 25.0f, 20.0f..180.0f, parent = advanced.value) { overShoot.value && rotationMode.value == RotationMode.ADVANCED }
    val osMinTicks = int("os-min-ticks", 2, 0..10, parent = advanced.value) { overShoot.value && rotationMode.value == RotationMode.ADVANCED }
    val osMaxTicks = int("os-max-ticks", 2, 0..10, parent = advanced.value) { overShoot.value && rotationMode.value == RotationMode.ADVANCED }
    val headUp = bool("head-up", false, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val huIntervalMin = int("hu-interval-min", 165, 5..400, parent = advanced.value) { headUp.value && rotationMode.value == RotationMode.ADVANCED }
    val huIntervalMax = int("hu-interval-max", 180, 5..400, parent = advanced.value) { headUp.value && rotationMode.value == RotationMode.ADVANCED }
    val huTicksMin = int("hu-ticks-min", 3, 1..20, parent = advanced.value) { headUp.value && rotationMode.value == RotationMode.ADVANCED }
    val huTicksMax = int("hu-ticks-max", 6, 1..20, parent = advanced.value) { headUp.value && rotationMode.value == RotationMode.ADVANCED }
    val huSpeedMin = float("hu-speed-min", 5.0f, 1.0f..90.0f, parent = advanced.value) { headUp.value && rotationMode.value == RotationMode.ADVANCED }
    val huSpeedMax = float("hu-speed-max", 36.0f, 1.0f..90.0f, parent = advanced.value) { headUp.value && rotationMode.value == RotationMode.ADVANCED }
    val clickJitter = bool("click-jitter", false, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val do360 = bool("do-360", false, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val do360Chance = int("do-360-chance", 20, 1..100, parent = advanced.value) { do360.value && rotationMode.value == RotationMode.ADVANCED }
    val holdAfterHit = bool("hold-after-hit", false, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val holdMin = int("hold-min", 5, 0..15, parent = advanced.value) { holdAfterHit.value && rotationMode.value == RotationMode.ADVANCED }
    val holdMax = int("hold-max", 7, 0..15, parent = advanced.value) { holdAfterHit.value && rotationMode.value == RotationMode.ADVANCED }
    val holdTiming = enum("hold-timing", HoldTiming.AFTER_RANDOM, parent = advanced.value) { holdAfterHit.value && rotationMode.value == RotationMode.ADVANCED }
    val holdChance = int("hold-chance", 100, 1..100, parent = advanced.value) { holdAfterHit.value && rotationMode.value == RotationMode.ADVANCED }
    val pitchMin = float("pitch-min", -90.0f, -90.0f..90.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pitchMax = float("pitch-max", 90.0f, -90.0f..90.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pitchInTargetMin = float("pitch-in-target-min", -90.0f, -90.0f..90.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val pitchInTargetMax = float("pitch-in-target-max", 90.0f, -90.0f..90.0f, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val filterLowDeltas = enum("filter-low-deltas", FilterLowDeltas.OFF, parent = advanced.value) { rotationMode.value == RotationMode.ADVANCED }
    val lowDeltaThreshold = float("low-delta-threshold", 1.0f, 0.0f..20.0f, parent = advanced.value) { filterLowDeltas.value != FilterLowDeltas.OFF && rotationMode.value == RotationMode.ADVANCED }
    val replaceMin = float("replace-min", 3.0f, 0.0f..45.0f, parent = advanced.value) { filterLowDeltas.value == FilterLowDeltas.REPLACE && rotationMode.value == RotationMode.ADVANCED }
    val replaceMax = float("replace-max", 5.0f, 0.0f..45.0f, parent = advanced.value) { filterLowDeltas.value == FilterLowDeltas.REPLACE && rotationMode.value == RotationMode.ADVANCED }
    val rotationMode = enum("rotation-mode", RotationMode.DEFAULT)
    val priorityManual = bool("priority-manual", true) { rotationMode.value == RotationMode.SNAP }
    val legit = group("legit") { rotationMode.value == RotationMode.LEGIT }.also { it.value.opened = true }
    val aimModel = enum("aim-model", AimModel.SPRING, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    val aimSpot = enum("aim-spot", LegitSpot.WANDER, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    val minAimSpeed = float("min-aim-speed", 240.0f, 20.0f..720.0f, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    val maxAimSpeed = float("max-aim-speed", 420.0f, 20.0f..720.0f, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    val smoothness = float("smoothness", 0.45f, 0.05f..1.0f, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    val pitchAssist = int("pitch-assist", 65, 0..100, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    val hitboxRelax = int("hitbox-relax", 70, 0..100, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    val reactionMin = int("reaction-min", 35, 0..300, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    val reactionMax = int("reaction-max", 85, 0..300, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    val tremor = float("tremor", 0.35f, 0.0f..2.0f, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    val requireClick = bool("require-click", false, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    /**
     * Not in the original: the same hand turns a look only the server gets (sent like the other silent modes) and
     * leaves your camera alone. Losing the target, it turns back to your camera rather than snapping. Use it with
     * Move Fix so you walk the way the server sees you face.
     */
    val silent = bool("silent", false, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    // HUMAN only (see HumanAim): how late you see the target move, how often a flick lands past it, how far off it
    // lands, how much a path bows. `tracking` (both models): how well you keep up with a strafing target.
    val perceptionDelay = int("perception-delay", 100, 0..300, parent = legit.value) { isHumanAim() }
    val overshoot = int("overshoot", 20, 0..100, parent = legit.value) { isHumanAim() }
    val aimError = int("aim-error", 6, 0..25, parent = legit.value) { isHumanAim() }
    val curve = int("curve", 6, 0..30, parent = legit.value) { isHumanAim() }
    val tracking = int("tracking", 80, 0..100, parent = legit.value) { rotationMode.value == RotationMode.LEGIT }
    val autoWeapon = enum("auto-weapon", AutoWeapon.NONE)
    val onlyWeapon = bool("only-weapon", false)
    val combatMode = enum("combat-mode", CombatMode.AUTO)
    val minCps = int("min-cps", 10, 1..20) { isLegacyCombat() }
    val maxCps = int("max-cps", 14, 1..20) { isLegacyCombat() }
    val clickPattern = enum("click-pattern", LegacyClicker.Pattern.HUMAN) { isLegacyCombat() }
    val swingRange = float("swing-range", 0.5f, 0.0f..2.5f) { isLegacyCombat() }
    val autoBlock = enum("auto-block", AutoBlock.OFF) { isLegacyCombat() }
    val shieldBreaker = bool("shield-breaker", true) { !isLegacyCombat() }
    val splashWhileOnGround = bool("splash-while-on-ground", false) { !isLegacyCombat() }
    val backTrack = bool("back-track", false)
    val moveDelay = int("move-delay", 250, 0..1000) { backTrack.value }
    val attackChance = int("attack-chance", 100, 50..100)
    val pauseWhileEating = bool("pause-while-eating", false)
    val pauseInInventory = bool("pause-in-inventory", true)
    val whileShielding = enum("while-shielding", WhileShielding.UNPRESS)
    val disableOnDeath = bool("disable-on-death", true)
    val overrideCrosshair = bool("override-crosshair", true)
    val rotateOnDisable = bool("rotate-on-disable", false)
    val offhandHitSwap = bool("offhand-hit-swap", false) { !isLegacyCombat() }
    val randomizeDelay = enum("randomize-delay", RandomizeDelay.OFF) { !isLegacyCombat() }
    val sprint = enum("sprint", Sprint.NORMAL)
    val esp = enum("esp", Esp.COMETS)
    val sort = enum("sort", Sort.Distance)
    /** Not in the original: let go of the held target for whoever is under the crosshair or the mouse turns towards. */
    val dynamicSwitch = bool("dynamic-switch", false) { sort.value != Sort.FOV }
    val targets = group("targets", GroupMode.BOOLS)
    val antibot = bool("antibot", true, parent = targets.value)
    val players = bool("players", true, parent = targets.value)
    val mobs = bool("mobs", true, parent = targets.value)
    val animals = bool("animals", true, parent = targets.value)
    val villagers = bool("villagers", true, parent = targets.value)
    val hostiles = bool("hostiles", true, parent = targets.value)
    val onlyAngryHostiles = bool("only-angry-hostiles", true, parent = targets.value) { hostiles.value }
    val projectiles = bool("projectiles", true, parent = targets.value)
    val ignoreInvisibleEntities = bool("ignore-invisible-entities", false, parent = targets.value)
    val ignoreNamed = bool("ignore-named", false, parent = targets.value)
    val ignoreTeam = bool("ignore-team", false, parent = targets.value)
    val ignoreCreative = bool("ignore-creative", true, parent = targets.value)
    val ignoreNaked = bool("ignore-naked", false, parent = targets.value)
    /**
     * Not in the original: how [antibot] tells a bot. ARMOR is the original's check (full, fresh, unenchanted armour
     * with leather or iron in it), which in Bedwars is every player in the starting kit. TAB_LIST counts a player
     * missing from the tab list as a bot (Hypixel's bots and NPCs aren't in it; real players are), or one with an
     * NPC's UUID (version 2). AUTO is TAB_LIST in Bedwars ([Bedwars]) or on Hypixel ([HypixelApi]), ARMOR elsewhere.
     */
    val antibotMode = enum("antibot-mode", AntibotMode.AUTO) { antibot.value }

    /** 1.8.x spam-click combat is active (auto-detected through ViaFabricPlus, or forced to [CombatMode.LEGACY]). */
    fun isLegacyCombat(): Boolean = when (combatMode.value) {
        CombatMode.AUTO -> Via.isLegacyCombat()
        CombatMode.LEGACY -> true
        CombatMode.COOLDOWN -> false
    }

    /** The entity Aura is fighting (the original's `G`). */
    @Volatile
    var target: Entity? = null

    /** Aura's last rotation (the original's `d`); the rotation manager snaps it in place when it sends it. */
    var rotation = Rotation(0f, 0f)
        private set

    /** A rotation callback ran since the last rotate-on-disable (the original's `aJ`). */
    private var rotated = false

    val timer = AttackTimer(this)
    val offhandSwap = OffhandSwapHook(this)
    val sprintHook = SprintHook(this, timer, offhandSwap)
    private val shield = ShieldHook(this)
    val weapon = WeaponHook(this)

    /** The original's hook list, in its order: attack timing, offhand-hit-swap, sprint, while-shielding, auto-weapon. */
    private val hooks: List<AuraHook> = listOf(timer, offhandSwap, sprintHook, shield, weapon)
    val backTracker = BackTrack(this)
    private val finder = AuraTargets(this)

    init {
        on<PlayerUpdateEvent> { tick() }
        on<SprintEvent> { e -> if (sprintHook.resetting || Modules.get<SprintModule>().skipOnce) e.sprinting = false }
        on<PostTickEvent> { offhandSwap.postTick() }
        on<ReceivePacketEvent> { e -> receive(e) }
        on<PostTasksEvent> { if (backTrack.value) backTracker.postTasks() }
        on<CrosshairEvent> { e -> overrideCrosshair(e) }
        on<Render3DEvent> { render() }
        on<MouseTurnEvent> {
            // Not in the original: a fireball deflect or a pearl throw has the camera meanwhile.
            if (effectiveRotation() === LegitRotation && !Modules.get<FireballDeflect>().busy && !Modules.get<PearlSave>().busy) {
                val t = target
                if (dynamicSwitch.value && t != null && sort.value != Sort.FOV) finder.turnedTo(t)?.let { target = it }
                LegitRotation.frame(this)
            }
        }
    }

    /** The original's `jv.Y`: the target ESP, then (with back-track, whatever the ESP) the back-tracked ghost. */
    private fun render() {
        val p = mc.player ?: return
        val t = target ?: return
        if (!weapon.gate()) return
        val alpha = 1f - Mth.clamp(p.distanceTo(t) / range.value, 0f, 1f)
        when (esp.value) {
            Esp.COMETS -> WorldEsp.comets(t, alpha)
            Esp.BLOOM_CIRCLE -> WorldEsp.bloomCircle(t, alpha)
            // SELECTION draws nothing in the original (its composite is an empty method).
            Esp.SELECTION, Esp.OFF -> {}
        }
        if (backTrack.value) backTracker.render()
    }

    /** Said so in chat for this lobby. */
    private var lobbyTold = false

    override fun onEnable() = reset()

    override fun onDisable() {
        val p = mc.player
        if (rotateOnDisable.value && p != null) p.forceSetRotation(rotation.yaw, false, rotation.pitch, false)
        reset()
    }

    private fun reset() {
        target = null
        backTracker.flush()
        timer.reset()
        shield.releaseAutoBlock()
        LegitRotation.reset()
        val p = mc.player ?: return
        rotation = Rotation(p.yRot, p.xRot)
        sprintHook.resetting = false
    }

    fun isHumanAim() = rotationMode.value == RotationMode.LEGIT && aimModel.value == AimModel.HUMAN

    /** The original's `jv.O()`: Aura is fighting someone with a weapon. */
    fun isAttacking(): Boolean = enabled && target != null && weapon.gate()

    /** The original's `jv.n()`: DEFAULT while elytra-targeting (gliding) or mace-elytra-targeting, else the setting. */
    fun effectiveRotation(): AuraRotation {
        val p = mc.player
        val elytra = Modules.get<ElytraTarget>().enabled && p != null && p.isFallFlying
        return if (elytra || Modules.get<MaceElytraTarget>().enabled) DefaultRotation else AuraRotation.of(rotationMode.value)
    }

    /** The original's `jv.Ps`. */
    private fun shouldPause(): Boolean {
        val p = mc.player ?: return true
        // Not in the original: nothing to fight in a lobby or a game's waiting room.
        if (lobbyOff.value && Bedwars.inLobby) {
            if (!lobbyTold) moduleMessage(name.trim(), "held off in the lobby until the match starts")
            lobbyTold = true
            return true
        }
        lobbyTold = false
        if (effectiveRotation() === LegitRotation && requireClick.value && !PlayerState.physical(mc.options.keyAttack)) return true
        if (PlayerState.containerOpen && pauseInInventory.value) return true
        val blocker = p.useItem.get(DataComponents.BLOCKS_ATTACKS) != null || p.useItem.useAnimation == ItemUseAnimation.BLOCK
        if (p.isUsingItem && pauseWhileEating.value && !blocker) return true
        if (p.isUsingItem && !shield.autoBlocking && whileShielding.value == WhileShielding.PAUSE && blocker) return true
        if (!weapon.gate()) return true
        val blink = Modules.get<Blink>()
        return blink.enabled && blink.attackAction.value == Blink.AttackAction.NONE && !Modules.get<ElytraTarget>().enabled
    }

    private fun lookBack(p: Player) {
        if (rotateOnDisable.value && rotated) {
            p.forceSetRotation(rotation.yaw, false, rotation.pitch, false)
            rotated = false
        }
        rotation = Rotation(p.yRot, p.xRot)
    }

    /** Not in the original: with no target, `silent`'s hidden look is still sent while it turns back to the camera. */
    private fun silentReturn() {
        if (effectiveRotation() !== LegitRotation || !LegitRotation.silentActive) return
        rotation = Rotation(LegitRotation.silentYaw, LegitRotation.silentPitch)
        RotationManager.rotate(rotation, 130)
    }

    /** The original's `jv.S`. */
    private fun tick() {
        val p = mc.player ?: return
        if (shouldPause()) {
            target = null
            shield.releaseAutoBlock()
            lookBack(p)
            silentReturn()
            for (h in hooks) h.tick()
            return
        }
        if (backTrack.value) backTracker.update()
        Modules.get<ElytraTarget>().resolve { selectAndAim(p) }
    }

    /** The original's `jv.a`: pick the target, aim, and queue the hit with the rotation. */
    private fun selectAndAim(p: Player) {
        val t = finder.select(target)
        target = t
        for (h in hooks) h.tick()
        if (t == null) {
            shield.releaseAutoBlock()
            lookBack(p)
            silentReturn()
            return
        }
        if (autoWeapon.value == AutoWeapon.NORMAL) weapon.selectBest()
        val attackReady = sprintHook.gate(timer.ready(0))
        val mode = effectiveRotation()
        if (mode === LegitRotation && LegitRotation.silentActive) {
            // Not in the original: `silent`. The hand turned the hidden look; it is sent like the other silent modes,
            // and the same check as below applies to it (the last look sent and this one both on the target).
            rotation = Rotation(LegitRotation.silentYaw, LegitRotation.silentPitch)
            val sentOnTarget = rayHitsTarget(t, Rotation(Rotation.lastYaw, Rotation.lastPitch), reach.value, true)
            val canHit = sentOnTarget && rayHitsTarget(t, rotation, reach.value, true)
            RotationManager.queue(InstantRequest(rotation, 130) { onRotated(attackReady, canHit) })
            return
        }
        if (mode === LegitRotation) {
            // The camera itself turns (LegitRotation, every frame): nothing silent is sent, and a hit lands only where
            // the crosshair is, never through a wall.
            rotation = Rotation(p.yRot, p.xRot)
            // The hit reaches the server before this tick's look does, so it is judged by the last look sent: hit only
            // when that one is on the target too (turning hard up close, it can still point past it: NCP's direction).
            val sentOnTarget = rayHitsTarget(t, Rotation(Rotation.lastYaw, Rotation.lastPitch), reach.value, true)
            onRotated(attackReady, sentOnTarget && rayHitsTarget(t, rotation, reach.value, true))
            return
        }
        rotation = if (fov.value == 1) Rotation(p.yRot, p.xRot) else mode.rotate(this, t, rotation, attackReady)
        val inHitRange = PlayerState.eye.distanceTo(mode.aimPoint(this, t)) <= reach.value
        val canHit = if (mode.needsRaycast(this)) rayHitsTarget(t, rotation, reach.value, !ignoreWalls.value)
        else inHitRange && (p.hasLineOfSight(t) || ignoreWalls.value)
        RotationManager.queue(InstantRequest(rotation, 130) { onRotated(attackReady, canHit) })
    }

    /** Runs when Aura's request wins (PlayerUpdate −20), before the movement packet. */
    private fun onRotated(attackReady: Boolean, canHit: Boolean) {
        rotated = true
        val t = target ?: return
        if (!attackReady) return
        // auto-block LEGIT: no hit (nor swing) while the hand is blocking, as in 1.8.
        if (!shield.allowsHit()) return
        if (!canHit) {
            if (isLegacyCombat() && swingRange.value > 0f && inPreSwingRange(t)) preSwing()
            return
        }
        if (!isLegacyCombat() && breakShield()) return
        attack(t, rotation)
    }

    private fun inPreSwingRange(t: Entity): Boolean {
        val p = mc.player ?: return false
        val mode = effectiveRotation()
        val sight = p.hasLineOfSight(t) || (ignoreWalls.value && mode !== LegitRotation)
        return sight && PlayerState.eye.distanceTo(mode.aimPoint(this, t)) <= reach.value + swingRange.value
    }

    /** 1.8 pre-swing / miss-swing while approaching or mid-flick, without sending an out-of-range attack packet. */
    private fun preSwing() {
        val p = mc.player ?: return
        shield.pre()
        p.swing(InteractionHand.MAIN_HAND, p.mainHandItem.attackAnimation, false)
        Swings.punch()
        timer.post(rotation)
        shield.post(rotation)
        LegitRotation.onSwing()
    }

    /** The original's `jv.s` (with 1.8.x swing-before-attack packet ordering when [isLegacyCombat] is active). */
    private fun attack(t: Entity, rot: Rotation) {
        val p = mc.player ?: return
        val gm = mc.gameMode ?: return
        val legacy = isLegacyCombat()
        if (attackChance.value == 100 || Random.nextFloat() * 100 < attackChance.value) {
            for (h in hooks) h.pre()
            val center = Rotation.lookAt(t.boundingBox.center)
            val grim = rotationMode.value == RotationMode.GRIM_SILENT
            if (grim) Packets.sendRotation(center.yaw, center.pitch)
            val stack = p.mainHandItem
            if (legacy) p.swing(InteractionHand.MAIN_HAND, stack.attackAnimation, false)
            val piercing = if (legacy) null else stack.get(DataComponents.PIERCING_WEAPON)
            if (piercing != null && !gm.isSpectator) {
                gm.piercingAttack(stack.attackAnimation, piercing)
                Swings.punch()
            } else {
                gm.attack(p, t)
            }
            if (!legacy) p.swing(InteractionHand.MAIN_HAND, stack.attackAnimation, false)
            if (grim) Packets.sendRotation(p.yRot, p.xRot)
            for (h in hooks) h.post(rot)
        } else {
            shield.pre()
            p.swing(InteractionHand.MAIN_HAND, p.mainHandItem.attackAnimation, false)
            Swings.punch()
            timer.post(rot)
            shield.post(rot)
        }
        p.resetOnlyAttackStrengthTicker()
        AdvancedRotation.onAttack(this)
        LegitRotation.onSwing()
    }

    /** The original's `jv.PZ`: an axe hit (swapped in through the inventory) on a player holding a shield up. */
    private fun breakShield(): Boolean {
        if (!shieldBreaker.value) return false
        val t = target as? Player ?: return false
        if (!t.holdingShieldUp()) return false
        val p = mc.player ?: return false
        val axe = Inventory.find { it.`is`(ItemTags.AXES) } ?: return false
        // Inventory.find gives container ids, so the hotbar branch of the original's code can't happen.
        Inventory.swap(axe.slot, p.inventory.selectedSlot, true)
        mc.gameMode?.attack(p, t)
        p.swing(InteractionHand.MAIN_HAND, p.mainHandItem.attackAnimation, false)
        Inventory.swap(axe.slot, p.inventory.selectedSlot, true)
        timer.countdown = 10
        return true
    }

    private fun receive(e: ReceivePacketEvent) {
        val pk = e.packet
        if (pk is ClientboundEntityEventPacket) {
            val level = mc.level
            val p = mc.player
            if (level != null) {
                val ent = pk.getEntity(level)
                val t = target
                if (pk.eventId.toInt() == 48 && t != null && ent === t) {
                    Notifications.post("Aura", "Shield disabled for " + t.name.string, 2f, NotificationType.SUCCESS)
                }
                if (pk.eventId.toInt() == 3 && p != null && ent === p && disableOnDeath.value) {
                    mc.execute {
                        disable()
                        moduleMessage(name.trim(), "Disabling due to death!")
                    }
                }
            }
        }
        if (backTrack.value) backTracker.receive(e)
    }

    /** The original's `jv.T`: vanilla's crosshair pick, along Aura's rotation and out to its reach (the original's range). */
    private fun overrideCrosshair(e: CrosshairEvent) {
        if (target == null || !overrideCrosshair.value) return
        Profiler.get().pop()
        e.cancel()
        val p = mc.player ?: return
        if (mc.cameraEntity == null || mc.level == null) return
        val hit = pickAlong(p.blockInteractionRange(), reach.value.toDouble(), rotation.yaw, rotation.pitch)
        mc.hitResult = hit
        mc.crosshairPickEntity = hit.pickedEntity()
    }
}
