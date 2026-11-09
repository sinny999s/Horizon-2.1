package dev.horizon.client.module.rage

import dev.horizon.client.HorizonClient
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.compat.Baritone
import dev.horizon.client.config.Friends
import dev.horizon.client.config.WayPoint
import dev.horizon.client.config.Waypoints
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.esp.NewChunks
import dev.horizon.client.module.world.HuntMap
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Follows a trail of old chunks under the nether roof, where Elytra Autopilot can't fly. New Chunks
 * finds the trail (with [TrailFinder], as Elytra Autopilot's trail mode does) and Baritone's elytra does the flying: it
 * gets a goal [LEAD] blocks ahead along the trail, and a new one when the trail bends or the goal gets close (only now
 * and then, and with open air below, since each new goal leaves the flight unsteered while Baritone replans). Until
 * there's a trail it flies the way you faced (snapped to 45 degrees with [snap]). When no trail has been ahead for
 * [endTime] seconds it says where the trail ended (the farthest old chunk it saw along it), can add a waypoint there
 * ([waypoint]), and has Baritone land there ([atEnd] LAND) or flies on looking for another (CONTINUE). From the ground
 * it jumps and starts gliding itself, since Baritone only takes off on its own by walking to a ledge (elytraAutoJump).
 *
 * With [predict] on and a nether seed known (Hunt Map's seeds, or singleplayer's), Baritone plans with that seed past
 * the loaded chunks (elytraNetherSeed and elytraPredictTerrain) while it runs; your Baritone settings come back when it
 * stops. Turned off mid-flight, it has Baritone land where you are (#stop takes over at once instead). Like Elytra
 * Autopilot it disconnects when a non-friend player comes within [playerRange] or health falls to [leaveHp], and if it
 * was on when you joined a world it turns itself off rather than launch you.
 *
 * Shelved, not registered: in the dev client Baritone 1.20.0's elytra flew into the nether's lava sea within seconds on
 * every test, also on its own (#goal, #elytra) and over ground generated in advance (tools/examples/trail-follower*.txt).
 * It also needs [NewChunks.isOld] to count unflagged nether chunks as old (palette on): fresh nether ground comes out
 * almost solidly new, while settled lava marks only about one old chunk in ten (6b6t data, 2026-10-29).
 */
class TrailFollower : Module("trail-follower", Categories.RAGE) {
    enum class AtEnd { LAND, CONTINUE }

    val snap = bool("snap", true)
    val trailSpread = int("trail-spread", 60, 15..90)
    val trailMin = int("trail-min", 4, 2..20)
    val endTime = int("end-time", 3, 1..20)
    val atEnd = enum("at-end", AtEnd.LAND)
    val waypoint = bool("waypoint", true)
    val predict = bool("predict", true)
    val leaveOnPlayer = bool("leave-on-player", true)
    val playerRange = int("player-range", 128, 16..256) { leaveOnPlayer.value }
    val leaveHp = float("leave-hp", 8.0f, 0.0f..20.0f)

    /** Set up with a player in a world; [joinedOn] when it was turned on with no world (a saved config at start-up). */
    private var started = false
    private var joinedOn = false

    /** Turned off by itself (it stopped, landed or left): nothing more to tell Baritone. */
    private var quiet = false
    private var level: ClientLevel? = null
    private var ticks = 0
    private var heading = 0f
    private var following = false
    private var lostSamples = 0

    /** Where the trail was last seen to go. */
    private var trailEnd: Vec3? = null

    /** The goal Baritone has, the heading it was set for, and when. */
    private var hasGoal = false
    private var goalX = 0
    private var goalZ = 0
    private var goalYaw = 0f
    private var lastAim = 0

    /** Flying to land at the trail's end; [landPending] until Baritone has that goal, from [endedAt]. */
    private var landing = false
    private var landPending = false
    private var endedAt = 0

    /** It has glided since it was turned on, and ticks on the ground since it last did. */
    private var airborne = false
    private var grounded = 0
    private var jumping = false
    private var lastGlide = -100

    /** Ticks Baritone has had nothing to do. */
    private var idle = 0

    /** A new level (6b6t's workers hand you over): Baritone may have dropped the flight, so set it again. */
    private var resume = false

    /** Baritone settings changed for the flight, with their values before. */
    private val saved = LinkedHashMap<String, Any>()

    init {
        on<TickEvent> { tick() }
    }

    override fun onEnable() {
        started = false
        quiet = false
        joinedOn = mc.level == null
        mc.player?.let { start(it) }
    }

    override fun onDisable() {
        if (jumping) mc.options.keyJump.setDown(false)
        jumping = false
        if (started && !quiet) {
            val p = mc.player
            if (p != null && p.isFallFlying && Baritone.elytraActive) {
                Baritone.elytraTo(p.blockX, p.blockY, p.blockZ)
                say("Baritone is landing where you are (#stop to take over now).")
            } else Baritone.stop()
        }
        for ((k, v) in saved) Baritone.set(k, v)
        saved.clear()
        started = false
    }

    private fun say(text: String) = moduleMessage(name.trim(), text)

    private fun stop(text: String) {
        say(text)
        quiet = true
        disable()
    }

    /** Checks it can fly at all, and sets off the way you face. */
    private fun start(p: LocalPlayer) {
        val lvl = mc.level ?: return
        if (!Baritone.present) return stop("This needs Baritone, which isn't installed.")
        if (!Baritone.elytraSupported) return stop("Baritone's elytra can't run on this computer (see #elytra supported).")
        if (lvl.dimension() != Level.NETHER) return stop("Baritone's elytra only flies in the nether. Elsewhere, use Elytra Autopilot's trail mode.")
        if (p.y >= NETHER_ROOF) return stop("You're on the nether roof: use Elytra Autopilot's trail mode up there.")
        if (!p.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) return stop("Put on an elytra first.")
        if (Inventory.hotbarFind(Items.FIREWORK_ROCKET) == null) return stop("Put rockets in your hotbar (Baritone uses them from there).")
        val nc = Modules.get<NewChunks>()
        if (!nc.enabled || !nc.palette.value) return stop("This needs New Chunks on, with palette.")
        heading = if (snap.value) (p.yRot / 45f).roundToInt() * 45f else p.yRot
        level = lvl
        ticks = 0
        following = false
        lostSamples = 0
        trailEnd = null
        hasGoal = false
        lastAim = -REAIM_GAP
        landing = false
        landPending = false
        airborne = p.isFallFlying
        grounded = 0
        idle = 0
        resume = false
        var note = ""
        val seed = if (predict.value) Modules.get<HuntMap>().seed() else null
        if (seed != null) {
            use("elytraNetherSeed", seed)
            use("elytraPredictTerrain", true)
            note = " Baritone plans with this world's nether seed."
        }
        started = true
        aim(p)
        if (started) say("Flying ${Mth.wrapDegrees(heading).toInt()} degrees until it finds a trail of old chunks.$note")
    }

    /** Sets a Baritone setting for the flight, keeping the value it had. */
    private fun use(setting: String, value: Any) {
        if (setting !in saved) Baritone.setting(setting)?.let { saved[setting] = it }
        Baritone.set(setting, value)
    }

    private fun tick() {
        val p = mc.player ?: return
        val lvl = mc.level ?: return
        if (!started) {
            if (joinedOn) stop("Turned off: it was on when you joined. Turn it on again to fly.") else start(p)
            return
        }
        if (jumping) {
            mc.options.keyJump.setDown(false)
            jumping = false
        }
        if (lvl !== level) {
            level = lvl
            if (lvl.dimension() != Level.NETHER) return stop("Left the nether: stopped.")
            resume = true
        }
        if (p.isDeadOrDying) return stop("Died: stopped.")
        ticks++
        if (safety(p, lvl)) return
        if (!Baritone.elytraActive) {
            if (resume) {
                resume = false
                idle = 0
                if (landing) landPending = true else {
                    hasGoal = false
                    aim(p, now = true)
                }
            } else if (++idle >= IDLE_TICKS) {
                return stop(if (landing) "Landed near the trail's end, at X: ${p.blockX} Y: ${p.blockY} Z: ${p.blockZ}."
                else "Baritone stopped flying (it landed, or #stop): stopped.")
            }
            return
        }
        idle = 0
        resume = false
        // Off the ground at the start, and again after a second down (a glide that hit a low ceiling).
        when {
            p.isFallFlying -> {
                airborne = true
                grounded = 0
            }
            landing -> {}
            !airborne -> takeoff(p)
            p.onGround() -> if (++grounded >= REGLIDE_TICKS) takeoff(p)
            grounded >= REGLIDE_TICKS -> takeoff(p)
        }
        if (landPending) land(p) else if (ticks % 20 == 0 && !landing) sample(p, lvl)
    }

    /** Baritone waits for you to start gliding (unless elytraAutoJump is on): jump, and glide on the way down. */
    private fun takeoff(p: LocalPlayer) {
        if (Baritone.setting("elytraAutoJump") == true) return
        if (p.onGround()) {
            mc.options.keyJump.setDown(true)
            jumping = true
        } else if (p.deltaMovement.y < 0 && ticks - lastGlide >= 5) {
            lastGlide = ticks
            if (p.tryToStartFallFlying()) Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
        }
    }

    /** True when it left. */
    private fun safety(p: LocalPlayer, lvl: ClientLevel): Boolean {
        if (leaveOnPlayer.value) {
            val other = lvl.players().firstOrNull {
                it !== p && !Friends.isFriend(it.gameProfile.name) && it.distanceTo(p) <= playerRange.value
            }
            if (other != null) {
                leave("${other.gameProfile.name} came within ${other.distanceTo(p).toInt()}m")
                return true
            }
        }
        if (leaveHp.value > 0f && p.health <= leaveHp.value) {
            leave("health fell to ${p.health.toInt()}")
            return true
        }
        return false
    }

    private fun leave(reason: String) {
        Baritone.stop()
        stop("Leaving: $reason.")
        mc.connection?.connection?.disconnect(Component.literal("[Horizon] Trail Follower: $reason"))
    }

    /** Every second: follow the trail ahead, or deal with its end (after [endTime] samples with none). */
    private fun sample(p: LocalPlayer, lvl: ClientLevel) {
        val nc = Modules.get<NewChunks>()
        if (!nc.enabled) return
        val old = { x: Int, z: Int -> nc.isOld(ChunkPos.pack(x, z)) }
        val reach = (mc.options.renderDistance().get() - 1).coerceIn(4, 12)
        val r = TrailFinder.steer(p.x, p.z, heading, old, { x, z -> nc.isKnown(ChunkPos.pack(x, z)) },
            trailSpread.value, reach, trailMin.value)
        when (r.state) {
            TrailFinder.State.FOLLOWING -> {
                if (!following) say("Following a trail of old chunks, heading ${r.yaw.toInt()} degrees.")
                following = true
                lostSamples = 0
                heading = r.yaw
                val far = TrailFinder.farthestOld(p.x, p.z, r.yaw, old, reach)
                val rad = Math.toRadians(r.yaw.toDouble())
                trailEnd = Vec3(p.x - sin(rad) * far, p.y, p.z + cos(rad) * far)
            }
            TrailFinder.State.EXPLORED -> lostSamples = 0
            TrailFinder.State.LOST -> if (following && ++lostSamples >= endTime.value) return ended(p, lvl)
        }
        aim(p)
    }

    private fun ended(p: LocalPlayer, lvl: ClientLevel) {
        following = false
        lostSamples = 0
        val end = trailEnd ?: p.position()
        val x = floor(end.x).toInt()
        val z = floor(end.z).toInt()
        say("The trail ends near X: $x Z: $z.")
        if (waypoint.value) {
            val server = mc.currentServer?.ip ?: "localhost"
            val dim = Waypoints.dimension(lvl.dimension().identifier().path)
            Waypoints.list += WayPoint("Trail end ($x, $z)", x, p.blockY, z, server, dim, true)
            Waypoints.save()
        }
        when (atEnd.value) {
            AtEnd.LAND -> {
                landing = true
                landPending = true
                endedAt = ticks
            }
            AtEnd.CONTINUE -> aim(p)
        }
    }

    /** Gives Baritone the trail's end to land at, at a safe moment (or after [LAND_WAIT] ticks of none). */
    private fun land(p: LocalPlayer) {
        if (ticks - lastAim < REAIM_GAP || !safeToReplan(p) && ticks - endedAt < LAND_WAIT) return
        val end = trailEnd ?: p.position()
        landPending = false
        lastAim = ticks
        if (Baritone.elytraTo(floor(end.x).toInt(), floor(end.z).toInt())) say("Landing there.") else stop("Baritone wouldn't fly there: stopped.")
    }

    /**
     * A new goal is a new flight plan: Baritone drops the one it's flying, hands every loaded chunk to its pathfinder
     * again and plans from scratch, steering nothing meanwhile. Low over lava that's enough to fall in, so it's only
     * done while gliding with [SAFE_DROP] blocks of open air below (or not gliding at all).
     */
    private fun safeToReplan(p: LocalPlayer): Boolean {
        if (!p.isFallFlying) return true
        val lvl = p.level()
        val pos = p.blockPosition().mutable()
        repeat(SAFE_DROP) {
            pos.move(0, -1, 0)
            if (!lvl.getBlockState(pos).isAir) return false
        }
        return true
    }

    /**
     * Gives Baritone a goal [LEAD] blocks ahead along the heading (inside the world border): when it has none yet, and
     * (at most every [REAIM_GAP] ticks, at a safe moment: see [safeToReplan]) when the heading has turned
     * [REAIM_DEGREES] from the goal's or the goal is under [REAIM_DISTANCE] away. [now] skips the wait.
     */
    private fun aim(p: LocalPlayer, now: Boolean = false) {
        if (hasGoal) {
            if (abs(Mth.wrapDegrees(heading - goalYaw)) < REAIM_DEGREES && hypot(goalX + 0.5 - p.x, goalZ + 0.5 - p.z) > REAIM_DISTANCE) return
            if (!now && (ticks - lastAim < REAIM_GAP || !safeToReplan(p))) return
        }
        val border = p.level().worldBorder
        val rad = Math.toRadians(heading.toDouble())
        val x = floor(Mth.clamp(p.x - sin(rad) * LEAD, border.minX + EDGE, border.maxX - EDGE)).toInt()
        val z = floor(Mth.clamp(p.z + cos(rad) * LEAD, border.minZ + EDGE, border.maxZ - EDGE)).toInt()
        if (!Baritone.elytraTo(x, z)) return stop("Baritone wouldn't fly there: stopped.")
        hasGoal = true
        goalX = x
        goalZ = z
        goalYaw = heading
        lastAim = ticks
        HorizonClient.LOG.info("Trail Follower: goal {} {} (heading {}) from {} {} {}", x, z, heading.toInt(), p.blockX, p.blockY, p.blockZ)
    }

    private companion object {
        /** The nether's bedrock roof; Baritone's elytra flies under it. */
        const val NETHER_ROOF = 128

        /** How far ahead the goal is, and when it gets a new one. */
        const val LEAD = 640.0
        const val REAIM_DISTANCE = 240.0
        const val REAIM_DEGREES = 20f
        const val REAIM_GAP = 120

        /** Open air below needed to change the plan mid-flight; ticks the trail's end waits for that at most. */
        const val SAFE_DROP = 6
        const val LAND_WAIT = 100

        /** Blocks kept from the world border. */
        const val EDGE = 32.0

        /** Ticks of Baritone doing nothing that mean it has stopped. */
        const val IDLE_TICKS = 40

        /** Ticks on the ground mid-flight before it jumps and glides again. */
        const val REGLIDE_TICKS = 20
    }
}
