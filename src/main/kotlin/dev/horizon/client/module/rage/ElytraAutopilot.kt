package dev.horizon.client.module.rage

import dev.horizon.client.combat.EquipUtil
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.config.Friends
import dev.horizon.client.config.Waypoints
import dev.horizon.client.event.FindEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.equip.ElytraReplace
import dev.horizon.client.module.esp.NewChunks
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Flies an elytra for you while you hunt bases. It takes off from the ground (a jump, then a glide),
 * fires a rocket when the speed drops below [minSpeed] or it's well under its cruise height (at most one every
 * [rocketDelay] seconds), and holds [height], climbing to keep [clearance] blocks above the terrain ahead. It steers, at
 * most [turnSpeed] degrees a tick, to a fixed heading (HEADING: the way you face, snapped to 45 degrees with [snap]),
 * coordinates (COORDS: [coords], "x z"), a waypoint by name (WAYPOINT), or along trails of old chunks from New Chunks
 * (TRAIL, see [TrailFinder]; at a trail's end it does [trailEnd]). When a finder announces a base, stash or pearl
 * stasis it can circle it for [circleTime] seconds ([onFind]); at its coordinates or waypoint it circles until you
 * take over.
 *
 * AFK safety: it disconnects (with a "[Horizon]" reason, which Auto Reconnect leaves alone) when a non-friend player
 * comes within [playerRange] or health falls to [leaveHp]; lands when it makes no progress for ten seconds
 * ([stuckStop]), when the rockets run out (warning at [lowRockets]) or when the elytra is a minute from breaking with
 * no spare (it puts a spare on itself when Elytra Replace is off). It refuses the nether below the roof (Baritone's
 * #elytra is made for that), and if it was on when you joined a world it turns itself off rather than launch you.
 */
class ElytraAutopilot : Module("elytra-autopilot", Categories.RAGE) {
    enum class Target { HEADING, COORDS, WAYPOINT, TRAIL }
    enum class OnFind { CONTINUE, CIRCLE }
    enum class TrailEnd { CIRCLE, CONTINUE, LAND }

    val target = enum("target", Target.HEADING)
    val snap = bool("snap", true) { target.value == Target.HEADING || target.value == Target.TRAIL }
    val coords = string("coords", "0 0") { target.value == Target.COORDS }
    val waypoint = string("waypoint", "") { target.value == Target.WAYPOINT }
    val height = int("height", 200, -64..320)
    val clearance = int("clearance", 24, 4..100)
    val minSpeed = float("min-speed", 1.3f, 0.3f..3.0f)
    val rocketDelay = float("rocket-delay", 2.0f, 0.5f..10.0f)
    val turnSpeed = float("turn-speed", 4.0f, 0.5f..20.0f)
    val rocketSlot = int("rocket-slot", 9, 1..9)
    val onFind = enum("on-find", OnFind.CONTINUE)
    val circleRadius = int("circle-radius", 48, 16..200)
    val circleTime = int("circle-time", 30, 5..600)
    val trailSpread = int("trail-spread", 60, 15..90) { target.value == Target.TRAIL }
    val trailMin = int("trail-min", 4, 2..20) { target.value == Target.TRAIL }
    val trailEnd = enum("trail-end", TrailEnd.CIRCLE) { target.value == Target.TRAIL }
    val leaveOnPlayer = bool("leave-on-player", true)
    val playerRange = int("player-range", 128, 16..256) { leaveOnPlayer.value }
    val leaveHp = float("leave-hp", 8.0f, 0.0f..20.0f)
    val stuckStop = bool("stuck-stop", true)
    val lowRockets = int("low-rockets", 32, 0..640)

    private enum class Phase { TAKEOFF, CRUISE, LANDING }

    private var phase = Phase.TAKEOFF

    /** Set up with a player in a world; [joinedOn] when it was turned on with no world (a saved config at start-up). */
    private var started = false
    private var joinedOn = false
    private var level: ClientLevel? = null
    private var heading = 0f
    private var goal: Vec3? = null
    private var circleAt: Vec3? = null
    private var circleUntil = 0L
    private var lastRocket = 0L
    private var lastGlide = -100
    private var ticks = 0
    private var progressFrom: Vec3? = null
    private var warnedRockets = false
    private var jumping = false
    private var following = false
    private var lostSamples = 0
    private var trailPoint: Vec3? = null

    init {
        on<TickEvent> { tick() }
        on<FindEvent> { e ->
            if (!started || phase != Phase.CRUISE || onFind.value != OnFind.CIRCLE || circleAt != null || e.kind !in CIRCLED) return@on
            circle(e.pos, circleTime.value)
            say("Circling the ${e.kind} at X: ${e.pos.x.toInt()} Z: ${e.pos.z.toInt()} for ${circleTime.value}s")
        }
    }

    override fun onEnable() {
        started = false
        joinedOn = mc.level == null
        circleAt = null
        following = false
        lostSamples = 0
        warnedRockets = false
        ticks = 0
        mc.player?.let { start(it) }
    }

    override fun onDisable() {
        if (jumping) mc.options.keyJump.setDown(false)
        jumping = false
        started = false
    }

    private fun say(text: String) = moduleMessage(name.trim(), text)

    private fun stop(text: String) {
        say(text)
        disable()
    }

    /** Checks it can fly at all, works out where to, and goes. */
    private fun start(p: LocalPlayer) {
        val lvl = mc.level ?: return
        if (!p.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) return stop("Put on an elytra first.")
        if (Inventory.count(Items.FIREWORK_ROCKET) == 0) return stop("You've got no rockets.")
        if (lvl.dimension() == Level.NETHER && p.y < NETHER_ROOF) {
            return stop("In the nether, fly on the roof (above Y $NETHER_ROOF), or use Baritone's #elytra under it.")
        }
        if (target.value == Target.TRAIL && !Modules.get<NewChunks>().enabled) {
            return stop("Trail mode needs New Chunks on (with palette, in the overworld).")
        }
        heading = if (snap.value) (p.yRot / 45f).roundToInt() * 45f else p.yRot
        goal = when (target.value) {
            Target.COORDS -> {
                val n = coords.value.trim().split(Regex("[ ,]+")).mapNotNull { it.toDoubleOrNull() }
                if (n.size < 2) return stop("Set coords to \"x z\".")
                Vec3(n.first() + 0.5, p.y, n.last() + 0.5)
            }
            Target.WAYPOINT -> {
                val server = mc.currentServer?.ip ?: "localhost"
                val dim = Waypoints.dimension(lvl.dimension().identifier().path)
                val wp = Waypoints.list.firstOrNull { it.id.equals(waypoint.value.trim(), true) && it.server == server && it.dimension == dim }
                    ?: return stop("No waypoint named \"${waypoint.value}\" here.")
                Vec3(wp.x + 0.5, wp.y.toDouble(), wp.z + 0.5)
            }
            else -> null
        }
        level = lvl
        phase = if (p.isFallFlying) Phase.CRUISE else Phase.TAKEOFF
        progressFrom = p.position()
        started = true
        val g = goal
        say(when {
            g != null -> "Flying to X: ${g.x.toInt()} Z: ${g.z.toInt()} (${hypot(g.x - p.x, g.z - p.z).toInt()}m)."
            target.value == Target.TRAIL -> "Flying ${heading.toInt()} degrees until it finds a trail of old chunks."
            else -> "Flying ${heading.toInt()} degrees."
        })
    }

    private fun tick() {
        val p = mc.player ?: return
        val lvl = mc.level ?: return
        if (!started) {
            if (joinedOn) stop("Turned off: it was on when you joined. Turn it on again to fly.") else start(p)
            return
        }
        // A new level mid-flight is a dimension change or a server handing you over (6b6t's workers): carry on.
        if (lvl !== level) level = lvl
        if (p.isDeadOrDying) return
        ticks++
        if (jumping) {
            mc.options.keyJump.setDown(false)
            jumping = false
        }
        if (safety(p, lvl)) return
        when (phase) {
            Phase.TAKEOFF -> takeoff(p)
            Phase.CRUISE -> cruise(p, lvl)
            Phase.LANDING -> land(p, lvl)
        }
    }

    /** True when it left or stopped. */
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
        if (stuckStop.value && phase == Phase.CRUISE && circleAt == null && ticks % STUCK_TICKS == 0) {
            val from = progressFrom
            if (from != null && hypot(p.x - from.x, p.z - from.z) < STUCK_BLOCKS) {
                phase = Phase.LANDING
                say("Stuck (under $STUCK_BLOCKS blocks in ${STUCK_TICKS / 20}s): landing.")
            }
            progressFrom = p.position()
        }
        return false
    }

    private fun leave(reason: String) {
        say("Leaving: $reason.")
        disable()
        mc.connection?.connection?.disconnect(Component.literal("[Horizon] Elytra Autopilot: $reason"))
    }

    /** Jump, start gliding on the way down, and the first rocket once gliding. */
    private fun takeoff(p: LocalPlayer) {
        if (p.isFallFlying) {
            phase = Phase.CRUISE
            progressFrom = p.position()
            rotate(p, heading, -35f, 90f)
            fireRocket(p)
            return
        }
        if (p.onGround()) {
            mc.options.keyJump.setDown(true)
            jumping = true
            return
        }
        if (p.deltaMovement.y < 0) glide(p)
    }

    /** Starts gliding (also after a server knocks you out of it), at most every 5 ticks. */
    private fun glide(p: LocalPlayer) {
        if (ticks - lastGlide < 5) return
        lastGlide = ticks
        if (p.tryToStartFallFlying()) Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
    }

    private fun cruise(p: LocalPlayer, lvl: ClientLevel) {
        if (!p.isFallFlying) {
            if (p.onGround()) {
                phase = Phase.TAKEOFF
                return
            }
            glide(p)
        }
        val now = System.currentTimeMillis()
        var yaw = steer(p, lvl)
        val c = circleAt
        if (c != null) {
            if (now < circleUntil) yaw = circleYaw(p, c)
            else {
                circleAt = null
                progressFrom = p.position()
                say("Done circling, carrying on.")
            }
        }
        val cruiseY = if (lvl.dimension() == Level.NETHER) max(height.value, NETHER_ROOF + 4) else height.value
        val targetY = max(cruiseY.toDouble(), groundAhead(p, lvl, yaw) + clearance.value)
        val err = targetY - p.y
        rotate(p, yaw, Mth.clamp(-err.toFloat() * 1.2f - 3f, -40f, 20f), turnSpeed.value)
        val speed = hypot(p.deltaMovement.x, p.deltaMovement.z)
        if (now - lastRocket >= rocketDelay.value * 1000 && (speed < minSpeed.value || err > 15)) fireRocket(p)
        supplies(p)
    }

    /** The heading for [target]; COORDS and WAYPOINT start circling on arrival. */
    private fun steer(p: LocalPlayer, lvl: ClientLevel): Float {
        when (target.value) {
            Target.HEADING -> {}
            Target.TRAIL -> trail(p, lvl)
            Target.COORDS, Target.WAYPOINT -> {
                val g = goal ?: return heading
                if (circleAt == null && hypot(g.x - p.x, g.z - p.z) < ARRIVE) {
                    circle(g, -1)
                    say("Arrived at X: ${g.x.toInt()} Z: ${g.z.toInt()}: circling until you take over.")
                }
                return yawOf(g.x - p.x, g.z - p.z)
            }
        }
        return heading
    }

    /** Every second: follow the trail ahead, or deal with its end (after three samples with none). */
    private fun trail(p: LocalPlayer, lvl: ClientLevel) {
        if (ticks % 20 != 0 || circleAt != null) return
        val nc = Modules.get<NewChunks>()
        if (!nc.enabled) return
        val reach = (mc.options.renderDistance().get() - 1).coerceIn(4, 12)
        val r = TrailFinder.steer(p.x, p.z, heading, { x, z -> nc.isOld(ChunkPos.pack(x, z)) }, { x, z -> nc.isKnown(ChunkPos.pack(x, z)) },
            trailSpread.value, reach, trailMin.value)
        when (r.state) {
            TrailFinder.State.FOLLOWING -> {
                if (!following) say("Following a trail of old chunks, heading ${r.yaw.toInt()} degrees.")
                following = true
                lostSamples = 0
                heading = r.yaw
                trailPoint = p.position()
            }
            TrailFinder.State.EXPLORED -> lostSamples = 0
            TrailFinder.State.LOST -> {
                if (!following || ++lostSamples < 3) return
                following = false
                val end = trailPoint ?: p.position()
                say("The trail ends near X: ${end.x.toInt()} Z: ${end.z.toInt()}.")
                when (trailEnd.value) {
                    TrailEnd.CIRCLE -> circle(end, circleTime.value)
                    TrailEnd.LAND -> phase = Phase.LANDING
                    TrailEnd.CONTINUE -> {}
                }
            }
        }
    }

    /** Circle [at] for [seconds] (negative: until you take over). */
    private fun circle(at: Vec3, seconds: Int) {
        circleAt = at
        circleUntil = if (seconds < 0) Long.MAX_VALUE else System.currentTimeMillis() + seconds * 1000L
    }

    /** Along the circle of [circleRadius] around [c], anticlockwise, steering back onto it when off. */
    private fun circleYaw(p: LocalPlayer, c: Vec3): Float {
        val rx = p.x - c.x
        val rz = p.z - c.z
        val d = hypot(rx, rz)
        if (d < 1) return p.yRot
        val ux = rx / d
        val uz = rz / d
        val k = Mth.clamp((d - circleRadius.value) / circleRadius.value, -1.0, 1.0)
        return yawOf(-uz - ux * k, ux - uz * k)
    }

    /**
     * The highest ground up to [LOOKAHEAD] blocks ahead along [yaw], in loaded chunks only: every block on the line
     * (a wall one block thick must not slip between samples) and every fourth two blocks to each side.
     */
    private fun groundAhead(p: LocalPlayer, lvl: ClientLevel, yaw: Float): Double {
        val r = Math.toRadians(yaw.toDouble())
        val dx = -sin(r)
        val dz = cos(r)
        var top = Int.MIN_VALUE
        fun at(x: Double, z: Double) {
            val bx = floor(x).toInt()
            val bz = floor(z).toInt()
            if (lvl.chunkSource.getChunkNow(bx shr 4, bz shr 4) != null) top = max(top, lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz))
        }
        for (d in 0..LOOKAHEAD) {
            val x = p.x + dx * d
            val z = p.z + dz * d
            at(x, z)
            if (d % 4 == 0) {
                at(x + dz * 2, z - dx * 2)
                at(x - dz * 2, z + dx * 2)
            }
        }
        return if (top == Int.MIN_VALUE) -1000.0 else top.toDouble()
    }

    /** Glide down, steeper while high, levelling out near the ground; off once down. */
    private fun land(p: LocalPlayer, lvl: ClientLevel) {
        val above = p.y - lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, p.blockX, p.blockZ)
        rotate(p, p.yRot, if (above > 20) 20f else if (above > 6) 8f else -5f, turnSpeed.value)
        if (p.onGround() || p.isInWater || !p.isFallFlying && above < 2) {
            stop("Landed at X: ${p.blockX} Y: ${p.blockY} Z: ${p.blockZ}.")
        }
    }

    /** Low-rocket warning; landing with none left; a spare elytra on, or landing, before the worn one breaks. */
    private fun supplies(p: LocalPlayer) {
        val rockets = Inventory.count(Items.FIREWORK_ROCKET)
        if (!warnedRockets && rockets <= lowRockets.value) {
            warnedRockets = true
            say("Only $rockets rockets left.")
        }
        if (rockets == 0) {
            phase = Phase.LANDING
            say("Out of rockets: landing.")
            return
        }
        val chest = p.getItemBySlot(EquipmentSlot.CHEST)
        if (!chest.has(DataComponents.GLIDER)) return
        val left = chest.maxDamage - chest.damageValue
        if (left > LAND_AT) return
        val spare = Inventory.find { it.has(DataComponents.GLIDER) && it.maxDamage - it.damageValue > LAND_AT }
        if (spare == null) {
            phase = Phase.LANDING
            say("The elytra has about ${left}s of flight left and there's no spare: landing.")
        } else if (left <= SWAP_AT && !Modules.get<ElytraReplace>().enabled) {
            EquipUtil.click(spare.slot)
            EquipUtil.click(CHEST_SLOT)
            EquipUtil.click(spare.slot, close = true)
            say("Put on a fresh elytra.")
        }
    }

    /** Uses a rocket from the offhand or the hotbar (moving one into [rocketSlot] from the inventory if needed). */
    private fun fireRocket(p: LocalPlayer) {
        if (!p.isFallFlying) return
        lastRocket = System.currentTimeMillis()
        if (p.offhandItem.`is`(Items.FIREWORK_ROCKET)) {
            mc.gameMode?.useItem(p, InteractionHand.OFF_HAND)
            return
        }
        var slot = Inventory.hotbarFind(Items.FIREWORK_ROCKET)?.slot
        if (slot == null) {
            val inv = Inventory.find(Items.FIREWORK_ROCKET) ?: return
            slot = rocketSlot.value - 1
            Inventory.click(inv.slot, 0, ContainerInput.PICKUP, false)
            Inventory.click(slot + 36, 0, ContainerInput.PICKUP, false)
            Inventory.click(inv.slot, 0, ContainerInput.PICKUP, true)
        }
        val prev = p.inventory.selectedSlot
        if (prev != slot) Inventory.selectHotbar(slot)
        mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
        if (prev != slot) Inventory.selectHotbar(prev)
    }

    /** Turns the player towards [yaw] and [pitch], at most [speed] degrees a tick across and twice that up and down. */
    private fun rotate(p: LocalPlayer, yaw: Float, pitch: Float, speed: Float) {
        p.yRot += Mth.clamp(Mth.wrapDegrees(yaw - p.yRot), -speed, speed)
        p.xRot += Mth.clamp(pitch - p.xRot, -speed * 2, speed * 2)
    }

    /** Minecraft's yaw for a direction (0 faces +Z, 90 faces -X). */
    private fun yawOf(dx: Double, dz: Double): Float = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()

    private companion object {
        /** The nether's bedrock roof; flying is only allowed above it. */
        const val NETHER_ROOF = 128

        /** Blocks of terrain looked at ahead, and how close counts as arrived. */
        const val LOOKAHEAD = 128
        const val ARRIVE = 24.0

        /** Stuck: under [STUCK_BLOCKS] blocks of progress in [STUCK_TICKS]. */
        const val STUCK_TICKS = 200
        const val STUCK_BLOCKS = 30

        /** Elytra uses left (a second of flight each) at which to land with no spare, and to put a spare on. */
        const val LAND_AT = 60
        const val SWAP_AT = 5

        /** The chest armour slot in the inventory menu. */
        const val CHEST_SLOT = 6

        /** What a finder's announcement can make it circle. */
        val CIRCLED = setOf("base", "stash", "stasis")
    }
}
