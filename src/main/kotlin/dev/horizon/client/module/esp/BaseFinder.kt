package dev.horizon.client.module.esp

import dev.horizon.client.HorizonClient
import dev.horizon.client.combat.Esp2
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.config.Storage
import dev.horizon.client.config.WayPoint
import dev.horizon.client.config.Waypoints
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.BlockStateEvent
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.FindEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.World3D
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractBannerBlock
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.AnvilBlock
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds bases and stashes in the loaded chunks while you travel. Every chunk is scored by the blocks
 * players place and worldgen rarely does (shulker boxes, ender chests, signs, beacons, conduits, lodestones... and,
 * worth less, chests, beds and workstations; portals, obsidian and concrete count only up to a low cap, and vanilla
 * Trial Chambers, Ancient Cities and Villages are filtered out when [ignoreStructures] is on). A chunk scoring
 * [threshold] or more is a base (or a stash when it holds at least [stashShulkers] shulkers or [stashChests] chests),
 * together with the scored chunks touching it on the same vertical band. Also scans written signs ([readSigns]),
 * notable item frames ([readFrames]), entities players leave behind ([entityClues]: chested donkeys, mules and llamas,
 * armor stands wearing gear, tamed and name-tagged animals, a crowd of villagers in one chunk, as in a trading hall) and
 * pearl stasis chambers ([stasis]: an ender pearl held still for three seconds). Clues already in the clue log aren't
 * announced again. Every announcement posts a [FindEvent] for Elytra Autopilot.
 */
class BaseFinder : Module("base-finder", Categories.ESP) {
    val threshold = int("threshold", 12, 1..200)
    val stashShulkers = int("stash-shulkers", 4, 1..100)
    val stashChests = int("stash-chests", 24, 4..500)
    val ignoreStructures = bool("ignore-structures", true)
    val readSigns = bool("read-signs", true)
    val readFrames = bool("read-frames", true)
    val entityClues = bool("entity-clues", true)
    val stasis = bool("stasis", true)
    val spawnRadius = int("spawn-radius", 0, 0..100000)
    val notify = bool("notify", true)
    val sound = bool("sound", true)
    val save = bool("save", true)
    val waypoints = bool("waypoints", false)
    val color = color("color", Color(0x50FFB000, true))
    val stashColor = color("stash-color", Color(0x64FF2878, true))
    val fill = bool("fill", true)
    val outline = bool("outline", true)
    val tracers = bool("tracers", true)
    val labels = bool("labels", true)
    val range = int("range", 1000, 16..10000)
    val clear = action("clear") { reset() }

    /** A kind of block, worth [weight] each, at most [cap] for a chunk; [playerOnly] kinds never generate in structures. */
    private class Kind(val name: String, val weight: Float, val cap: Float = Float.MAX_VALUE, val playerOnly: Boolean = false)

    /** One chunk's score, what made it, and the bounds of those blocks. */
    private class Scored(val score: Float, val counts: Map<Kind, Int>, val box: AABB?)

    private class Base(
        val id: Int,
        val chunks: HashSet<Long>,
        var score: Float,
        var counts: Map<Kind, Int>,
        var what: String,
        var stash: Boolean,
        var box: AABB,
    )

    private val scored = HashMap<Long, Scored>()
    private val dirty = HashSet<Long>()
    private val bases = ArrayList<Base>()
    private val signs = HashMap<BlockPos, String>()
    private val frames = HashMap<BlockPos, String>()

    /** Chunks of bases already in the file, and positions of signs/frames already announced. */
    private val logged = HashSet<Long>()
    private val loggedSigns = HashSet<BlockPos>()
    private val loggedFrames = HashSet<BlockPos>()

    /** Entity and stasis clues already announced: by entity this session, and by kind and chunk from the clue log. */
    private val loggedEntities = HashSet<UUID>()
    private val loggedClues = HashSet<String>()
    private val stasisSpots = ArrayList<BlockPos>()

    /** Each ender pearl in view: where it was when it last moved, and for how many ticks it has stayed there. */
    private class PearlTrack(val pos: Vec3, var still: Int)

    private val pearls = HashMap<Int, PearlTrack>()
    private var changed = false
    private var level: ClientLevel? = null
    private var key: String? = null
    private var ticks = 0

    init {
        on<TickEvent> { tick() }
        on<BlockStateEvent> { e ->
            if (e.state.block in kinds || e.previous?.block in kinds) dirty += ChunkPos.pack(e.pos.x shr 4, e.pos.z shr 4)
        }
        on<AfterReceivePacketEvent> { e ->
            val p = e.packet as? ClientboundLevelChunkWithLightPacket ?: return@on
            dirty += ChunkPos.pack(p.x, p.z)
        }
        on<Render3DEvent> { draw() }
        on<Render2DEvent> { if (labels.value) labels() }
    }

    override fun onEnable() = reset()

    private fun reset() {
        scored.clear()
        dirty.clear()
        bases.clear()
        signs.clear()
        frames.clear()
        logged.clear()
        loggedSigns.clear()
        loggedFrames.clear()
        loggedEntities.clear()
        loggedClues.clear()
        stasisSpots.clear()
        pearls.clear()
        level = null
    }

    private fun tick() {
        val lvl = mc.level ?: return
        val p = mc.player ?: return
        if (lvl !== level) {
            // A new world or dimension: start over, with what the file already knows of this one.
            reset()
            level = lvl
            key = currentKey(lvl)
            if (save.value) readLog()
        }
        if (++ticks % 20 == 0) {
            if (scored.keys.removeIf { lvl.chunkSource.getChunkNow(ChunkPos.getX(it), ChunkPos.getZ(it)) == null }) changed = true
            signs.keys.removeIf { lvl.chunkSource.getChunkNow(it.x shr 4, it.z shr 4) == null }
        }
        if (ticks % 10 == 0) {
            if (readSigns.value) scanSigns(lvl)
            if (readFrames.value) scanFrames(lvl)
            if (entityClues.value || stasis.value) scanEntities(lvl)
        }
        for (k in dirty) {
            if (k !in scored) continue
            val chunk = lvl.chunkSource.getChunkNow(ChunkPos.getX(k), ChunkPos.getZ(k))
            if (chunk == null) scored.remove(k) else scored[k] = score(lvl, chunk)
            changed = true
        }
        dirty.clear()
        // New chunks, nearest first, a few at a time (most are skipped on their palettes alone).
        val cx = p.blockX shr 4
        val cz = p.blockZ shr 4
        var budget = SCANS_PER_TICK
        rings@ for (r in 0..mc.options.renderDistance().get() + 1) {
            for (dx in -r..r) for (dz in -r..r) {
                if (max(abs(dx), abs(dz)) != r) continue
                val k = ChunkPos.pack(cx + dx, cz + dz)
                if (k in scored) continue
                val chunk = lvl.chunkSource.getChunkNow(cx + dx, cz + dz) ?: continue
                scored[k] = score(lvl, chunk)
                changed = true
                if (--budget == 0) break@rings
            }
        }
        if (changed) {
            changed = false
            update(lvl)
        }
    }

    /**
     * The chunk's score: the strongest vertical band of counted blocks (each kind up to its cap), split whenever there is
     * more than [MAX_Y_GAP] blocks of empty vertical space so a stray underground chest doesn't stretch a surface box.
     */
    private fun score(lvl: ClientLevel, chunk: LevelChunk): Scored {
        var counts = HashMap<Kind, Int>()
        val x0 = chunk.pos.x() shl 4
        val z0 = chunk.pos.z() shl 4
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        var best = EMPTY
        val filterStruct = ignoreStructures.value
        var villageChunk = false
        if (filterStruct) {
            for (s in chunk.sections) {
                if (!s.hasOnlyAir() && s.states.maybeHas { it.block in VILLAGE_BLOCKS }) {
                    villageChunk = true
                    break
                }
            }
        }
        fun finishBand() {
            if (counts.isEmpty()) return
            var playerScore = 0f
            var worldgenScore = 0f
            for ((kind, n) in counts) {
                val pts = min(kind.weight * n, kind.cap)
                if (kind.playerOnly) playerScore += pts else worldgenScore += pts
            }
            if (villageChunk) worldgenScore = min(worldgenScore, 6f)
            val s = playerScore + worldgenScore
            if (s > best.score) {
                best = Scored(s, counts, AABB(minX.toDouble(), minY.toDouble(), minZ.toDouble(), maxX + 1.0, maxY + 1.0, maxZ + 1.0))
            }
            counts = HashMap()
            minX = Int.MAX_VALUE; minY = Int.MAX_VALUE; minZ = Int.MAX_VALUE
            maxX = Int.MIN_VALUE; maxY = Int.MIN_VALUE; maxZ = Int.MIN_VALUE
        }
        val sections = chunk.sections
        for (i in sections.indices) {
            val s = sections[i]
            if (s.hasOnlyAir() || !s.states.maybeHas { it.block in kinds }) continue
            val structSection = filterStruct && s.states.maybeHas { it.block in STRUCTURE_BLOCKS }
            val y0 = lvl.getSectionYFromSectionIndex(i) shl 4
            for (y in 0..15) {
                val wy = y0 + y
                if (counts.isNotEmpty() && wy - maxY > MAX_Y_GAP) finishBand()
                for (z in 0..15) for (x in 0..15) {
                    val kind = kinds[s.getBlockState(x, y, z).block] ?: continue
                    if (structSection && !kind.playerOnly) continue
                    counts.merge(kind, 1, Int::plus)
                    minX = min(minX, x0 + x); minY = min(minY, wy); minZ = min(minZ, z0 + z)
                    maxX = max(maxX, x0 + x); maxY = max(maxY, wy); maxZ = max(maxZ, z0 + z)
                }
            }
        }
        finishBand()
        return best
    }

    /** Scans loaded chunks' block entities for player-written signs. */
    private fun scanSigns(lvl: ClientLevel) {
        val p = mc.player ?: return
        val rd = mc.options.renderDistance().get()
        val cx = p.blockX shr 4
        val cz = p.blockZ shr 4
        val r = spawnRadius.value
        for (dx in -rd..rd) for (dz in -rd..rd) {
            val chunk = lvl.chunkSource.getChunkNow(cx + dx, cz + dz) ?: continue
            for (be in chunk.blockEntities.values) {
                val sign = be as? SignBlockEntity ?: continue
                val pos = sign.blockPos
                if (r > 0 && abs(pos.x) <= r && abs(pos.z) <= r) continue
                val lines = (sign.getText(SignTextSlot.FRONT).getMessages(false) + sign.getText(SignTextSlot.BACK).getMessages(false))
                    .map { it.string.trim().trim('"').trim() }
                    .filter { it.isNotEmpty() }
                if (lines.isEmpty()) continue
                val text = lines.joinToString(" / ")
                signs[pos] = text
                if (loggedSigns.add(pos)) announceClue("Sign", pos, "\"$text\"")
            }
        }
    }

    /** Scans loaded item frames for map art, shulkers, or custom-named items. */
    private fun scanFrames(lvl: ClientLevel) {
        val r = spawnRadius.value
        frames.clear()
        for (e in lvl.entitiesForRendering()) {
            val frame = e as? ItemFrame ?: continue
            val stack = frame.item
            if (stack.isEmpty) continue
            val pos = frame.blockPosition()
            if (r > 0 && abs(pos.x) <= r && abs(pos.z) <= r) continue
            val isMap = stack.`is`(Items.FILLED_MAP)
            val isShulker = (stack.item as? BlockItem)?.block is ShulkerBoxBlock
            val custom = stack.customName?.string
            if (!isMap && !isShulker && custom == null) continue
            val desc = when {
                custom != null -> "${stack.itemName.string} \"$custom\""
                isMap -> "Filled Map"
                else -> stack.hoverName.string
            }
            frames[pos] = desc
            if (loggedFrames.add(pos)) announceClue("Item frame", pos, desc)
        }
    }

    /** Entities that give a base away, and ender pearls held in stasis; each announced once. */
    private fun scanEntities(lvl: ClientLevel) {
        val r = spawnRadius.value
        val villagers = HashMap<Long, Int>()
        val alive = HashSet<Int>()
        for (e in lvl.entitiesForRendering()) {
            if (r > 0 && abs(e.x) <= r && abs(e.z) <= r) continue
            if (e is ThrownEnderpearl) {
                if (stasis.value) {
                    alive += e.id
                    trackPearl(e)
                }
                continue
            }
            if (!entityClues.value) continue
            val type = e.type.description.string.lowercase()
            val named = e.customName?.string?.let { "\"$it\"" }
            when {
                e is AbstractChestedHorse && e.hasChest() -> entityClue(e, "Chested $type", named ?: "carrying a chest")
                e is ArmorStand -> {
                    val gear = GEAR.map { e.getItemBySlot(it) }.filter { !it.isEmpty }
                    if (gear.isNotEmpty()) entityClue(e, "Armor stand", gear.joinToString(", ") { it.hoverName.string })
                }
                e is TamableAnimal && e.isTame -> entityClue(e, "Tamed $type", named ?: "no name")
                e is Mob && named != null -> entityClue(e, "Named $type", named)
                e is Villager -> villagers.merge(ChunkPos.pack(e.blockX shr 4, e.blockZ shr 4), 1, Int::plus)
            }
        }
        pearls.keys.retainAll(alive)
        for ((k, n) in villagers) {
            if (n < VILLAGERS) continue
            val chunk = lvl.chunkSource.getChunkNow(ChunkPos.getX(k), ChunkPos.getZ(k)) ?: continue
            // A village's own villagers stand around its bell and hay; a trading hall has neither.
            if (chunk.sections.any { !it.hasOnlyAir() && it.states.maybeHas { st -> st.block in VILLAGE_BLOCKS } }) continue
            val pos = BlockPos((ChunkPos.getX(k) shl 4) + 8, mc.player?.blockY ?: 64, (ChunkPos.getZ(k) shl 4) + 8)
            if (loggedClues.add(clueKey("Villagers", pos))) announceClue("Villagers", pos, "$n villagers in one chunk")
        }
    }

    private fun entityClue(e: Entity, kind: String, detail: String) {
        if (!loggedEntities.add(e.uuid)) return
        if (!loggedClues.add(clueKey(kind, e.blockPosition()))) return
        announceClue(kind, e.blockPosition(), detail)
    }

    /** A pearl that stays within [STASIS_MOVE] blocks for [STASIS_TICKS] ticks is held in a stasis chamber. */
    private fun trackPearl(e: ThrownEnderpearl) {
        val t = pearls[e.id]
        if (t == null || e.position().distanceTo(t.pos) > STASIS_MOVE) {
            pearls[e.id] = PearlTrack(e.position(), 0)
            return
        }
        t.still += 10
        if (t.still < STASIS_TICKS) return
        val pos = e.blockPosition()
        if (stasisSpots.any { it.distSqr(pos) <= 16.0 }) return
        stasisSpots += pos
        if (!loggedClues.add(clueKey("Pearl stasis", pos))) return
        val owner = e.owner?.name?.string
        announceClue("Pearl stasis", pos, if (owner != null) "a pearl thrown by $owner" else "an ender pearl held in place")
    }

    private fun clueKey(kind: String, pos: BlockPos) = "$kind ${pos.x shr 4} ${pos.z shr 4}"

    private fun announceClue(kind: String, pos: BlockPos, detail: String) {
        EventBus.post(FindEvent(if (kind == "Pearl stasis") "stasis" else "clue", Vec3.atCenterOf(pos)))
        val p = mc.player ?: return
        val dist = p.position().distanceTo(Vec3.atCenterOf(pos)).toInt()
        if (notify.value) {
            moduleMessage(name.trim(), "$kind at X: ${pos.x} Y: ${pos.y} Z: ${pos.z} (${dist}m): $detail")
        }
        if (save.value) {
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val line = "$time | $kind | X ${pos.x} Y ${pos.y} Z ${pos.z} | $detail\n"
            try {
                Files.writeString(cluesFile(), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Couldn't write {}", cluesFile(), e)
            }
        }
    }

    private fun isStash(counts: Map<Kind, Int>): Boolean =
        (counts[shulkerKind] ?: 0) >= stashShulkers.value || (counts[chestKind] ?: 0) >= stashChests.value

    /**
     * Every chunk at [threshold] or over, with the scored chunks touching it on the same vertical band, is one base; one
     * that overlaps a base already found grows it, any other is new.
     */
    private fun update(lvl: ClientLevel) {
        val seen = HashSet<Long>()
        for ((start, s) in scored) {
            if (s.score < threshold.value || start in seen) continue
            val sBox = s.box ?: continue
            var bandMinY = sBox.minY
            var bandMaxY = sBox.maxY
            val cluster = HashSet<Long>()
            val queue = ArrayDeque<Long>()
            seen += start
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val k = queue.removeFirst()
                cluster += k
                val x = ChunkPos.getX(k)
                val z = ChunkPos.getZ(k)
                for (dx in -1..1) for (dz in -1..1) {
                    val n = ChunkPos.pack(x + dx, z + dz)
                    val nb = scored[n] ?: continue
                    val nBox = nb.box ?: continue
                    if (nb.score >= PART && nBox.minY <= bandMaxY + MAX_Y_GAP && nBox.maxY >= bandMinY - MAX_Y_GAP && seen.add(n)) {
                        bandMinY = min(bandMinY, nBox.minY)
                        bandMaxY = max(bandMaxY, nBox.maxY)
                        queue.addLast(n)
                    }
                }
            }
            var score = 0f
            val counts = HashMap<Kind, Int>()
            var box: AABB? = null
            for (k in cluster) {
                val c = scored[k] ?: continue
                score += c.score
                for ((kind, n) in c.counts) counts.merge(kind, n, Int::plus)
                if (c.box != null) box = box?.minmax(c.box) ?: c.box
            }
            if (box == null) continue
            val r = spawnRadius.value.toDouble()
            if (r > 0 && abs(box.center.x) <= r && abs(box.center.z) <= r) continue
            val what = summary(counts)
            val stash = isStash(counts)
            val old = bases.firstOrNull { b -> cluster.any { it in b.chunks } }
            if (old != null) {
                old.chunks += cluster
                if (stash && !old.stash) {
                    old.stash = true
                    if (sound.value) playAlert(true)
                }
                if (score >= old.score) {
                    old.score = score
                    old.counts = counts
                    old.what = what
                    old.box = box
                }
                continue
            }
            val base = Base(bases.size + 1, cluster, score, counts, what, stash, box)
            bases += base
            if (cluster.any { it in logged }) continue
            announce(lvl, base)
        }
    }

    private fun playAlert(stash: Boolean) {
        val evt = if (stash) SoundEvents.UI_TOAST_CHALLENGE_COMPLETE else SoundEvents.EXPERIENCE_ORB_PICKUP
        mc.soundManager.play(SimpleSoundInstance.forUI(evt, 1f))
    }

    private fun announce(lvl: ClientLevel, base: Base) {
        val p = mc.player ?: return
        val c = base.box.center
        val x = c.x.toInt()
        val y = c.y.toInt()
        val z = c.z.toInt()
        val other = when (lvl.dimension()) {
            Level.NETHER -> " (overworld X: ${x * 8} Z: ${z * 8})"
            Level.OVERWORLD -> " (nether X: ${Math.floorDiv(x, 8)} Z: ${Math.floorDiv(z, 8)})"
            else -> ""
        }
        val tag = if (base.stash) "Stash" else "Base"
        val what = base.what
        EventBus.post(FindEvent(if (base.stash) "stash" else "base", c))
        if (sound.value) playAlert(base.stash)
        if (notify.value) {
            val prefix = if (base.stash) "§d[STASH]§r " else ""
            moduleMessage(name.trim(), "${prefix}$tag #${base.id} at X: $x Y: $y Z: $z, ${p.position().distanceTo(c).toInt()}m away: $what " +
                "(score ${base.score.toInt()})$other")
        }
        if (save.value) {
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val chunks = base.chunks.joinToString(" ") { "${ChunkPos.getX(it)},${ChunkPos.getZ(it)}" }
            val line = "$time | ${tag.uppercase()} | X $x Y $y Z $z | score ${base.score.toInt()} | $what$other | chunks $chunks\n"
            try {
                Files.writeString(file(), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                logged += base.chunks
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Couldn't write {}", file(), e)
            }
        }
        if (waypoints.value) {
            val server = mc.currentServer?.ip ?: "localhost"
            val wpPrefix = if (base.stash) "stash-" else "base-"
            val n = (Waypoints.list.filter { it.server == server && WAYPOINT.matches(it.id) }
                .maxOfOrNull { it.id.substringAfter('-').toIntOrNull() ?: 0 } ?: 0) + 1
            Waypoints.list += WayPoint("$wpPrefix$n", x, y, z, server, Waypoints.dimension(lvl.dimension().identifier().path), true)
            Waypoints.save()
        }
    }

    /** The biggest few kinds, "14 shulkers, 22 chests, 6 portal blocks". */
    private fun summary(counts: Map<Kind, Int>) =
        counts.entries.sortedByDescending { (kind, n) -> min(kind.weight * n, kind.cap) }.take(4).joinToString(", ") { (kind, n) -> "$n ${kind.name}" }

    private fun draw() {
        if (bases.isEmpty()) return
        val cam = World3D.camera
        for (base in bases) {
            if (!near(base, cam)) continue
            val c = (if (base.stash) stashColor.value else color.value).rgb
            val line = PlaceBoxes.withAlpha(c, 1f)
            if (fill.value) World3D.fill(base.box, c)
            if (outline.value) World3D.outline(base.box, line)
            if (tracers.value) World3D.line(Esp2.tracerStart(), base.box.center, line, noCull = true)
        }
    }

    private fun labels() {
        val cam = World3D.camera
        for (base in bases) {
            if (!near(base, cam)) continue
            val top = Vec3(base.box.center.x, base.box.maxY + 1.0, base.box.center.z)
            val s = World3D.project(top) ?: continue
            val tag = if (base.stash) "Stash" else "Base"
            val titleColor = if (base.stash) 0xFFFF66A8.toInt() else -1
            Fonts.bold8.drawCentered("$tag #${base.id} ${cam.distanceTo(base.box.center).toInt()}m", s[0], s[1] - 4f, titleColor)
            Fonts.medium65.drawCentered(base.what, s[0], s[1] + 6f, 0xFFD0D0D0.toInt())
        }
        val clueRangeSqr = min(range.value.toDouble(), 128.0).let { it * it }
        if (readSigns.value) {
            for ((pos, text) in signs) {
                val p = Vec3(pos.x + 0.5, pos.y + 1.25, pos.z + 0.5)
                if (cam.distanceToSqr(p) > clueRangeSqr) continue
                val s = World3D.project(p) ?: continue
                Fonts.medium65.drawCentered("Sign: $text", s[0], s[1], 0xFFFFE070.toInt())
            }
        }
        if (readFrames.value) {
            for ((pos, desc) in frames) {
                val p = Vec3(pos.x + 0.5, pos.y + 0.85, pos.z + 0.5)
                if (cam.distanceToSqr(p) > clueRangeSqr) continue
                val s = World3D.project(p) ?: continue
                Fonts.medium65.drawCentered(desc, s[0], s[1], 0xFF80E8FF.toInt())
            }
        }
    }

    private fun near(base: Base, cam: Vec3): Boolean {
        val dx = base.box.center.x - cam.x
        val dz = base.box.center.z - cam.z
        return dx * dx + dz * dz <= range.value.toDouble() * range.value
    }

    /** The server's address (or the singleplayer world's name) and the dimension, as a file name. */
    private fun currentKey(lvl: ClientLevel): String {
        val server = mc.singleplayerServer?.worldData?.levelName?.let { "singleplayer-$it" } ?: mc.currentServer?.ip ?: "unknown"
        return (server + "_" + lvl.dimension().identifier().path).replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun file() = Storage.dir("basefinder").resolve("$key.txt")

    private fun cluesFile() = Storage.dir("basefinder").resolve("${key}_clues.txt")

    /**
     * The chunks of every base in the file ("chunks x,z x,z" at the end of each line), and every clue in the clue log
     * ("time | kind | X x Y y Z z | detail"), so nothing already reported is announced again.
     */
    private fun readLog() {
        val c = cluesFile()
        if (Files.exists(c)) {
            try {
                for (line in Files.readAllLines(c)) {
                    val parts = line.split(" | ")
                    if (parts.size < 3) continue
                    val xyz = parts[2].split(' ')
                    val pos = BlockPos(xyz.getOrNull(1)?.toIntOrNull() ?: continue, xyz.getOrNull(3)?.toIntOrNull() ?: continue,
                        xyz.getOrNull(5)?.toIntOrNull() ?: continue)
                    when (parts[1]) {
                        "Sign" -> loggedSigns += pos
                        "Item frame" -> loggedFrames += pos
                        else -> loggedClues += clueKey(parts[1], pos)
                    }
                }
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Couldn't read {}", c, e)
            }
        }
        val f = file()
        if (!Files.exists(f)) return
        try {
            for (line in Files.readAllLines(f)) {
                val at = line.lastIndexOf("chunks ")
                if (at < 0) continue
                for (part in line.substring(at + 7).split(' ')) {
                    val xz = part.split(',')
                    if (xz.size == 2) logged += ChunkPos.pack(xz[0].trim().toIntOrNull() ?: continue, xz[1].trim().toIntOrNull() ?: continue)
                }
            }
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Couldn't read {}", f, e)
        }
    }

    private companion object {
        const val SCANS_PER_TICK = 32

        /** A chunk around a base counts as part of it from this score. */
        const val PART = 2f

        /** Vertical gap (in blocks) beyond which counted blocks belong to a separate band rather than one box. */
        const val MAX_Y_GAP = 16

        val EMPTY = Scored(0f, emptyMap(), null)
        val WAYPOINT = Regex("(base|stash)-\\d+")

        /** Blocks that identify Trial Chambers and Ancient Cities, where worldgen containers/redstone are ignored. */
        val STRUCTURE_BLOCKS = hashSetOf(
            Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.TUFF_BRICKS, Blocks.CHISELED_TUFF_BRICKS,
            Blocks.REINFORCED_DEEPSLATE, Blocks.SCULK_SHRIEKER,
        )

        /** Villagers in one chunk (away from a village's bell and hay) that make a trading hall. */
        const val VILLAGERS = 8

        /** How far a pearl may drift, and for how many ticks it must stay, to be held in stasis. */
        const val STASIS_MOVE = 0.5
        const val STASIS_TICKS = 60

        val GEAR = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND)

        /** Blocks that identify Villages, where worldgen beds/workstations/barrels are capped below [threshold]. */
        val VILLAGE_BLOCKS = hashSetOf(Blocks.BELL, Blocks.HAY_BLOCK)

        val shulkerKind = Kind("shulkers", 5f, playerOnly = true)
        val chestKind = Kind("chests", 1f)

        /** What's counted, and for how much. */
        val kinds: Map<Block, Kind> by lazy {
            val m = HashMap<Block, Kind>()
            fun add(kind: Kind, vararg blocks: Block) = blocks.forEach { m[it] = kind }
            val signs = Kind("signs", 2f, playerOnly = true)
            val beds = Kind("beds", 1f, 3f)
            val anvils = Kind("anvils", 2f, playerOnly = true)
            val concrete = Kind("concrete", 0.25f, 4f, playerOnly = true)
            val heads = Kind("heads", 3f, 6f)
            val banners = Kind("banners", 1.5f, 4.5f, playerOnly = true)
            for (b in BuiltInRegistries.BLOCK) {
                when {
                    b is ShulkerBoxBlock -> m[b] = shulkerKind
                    b is SignBlock -> m[b] = signs
                    b is BedBlock -> m[b] = beds
                    b is AnvilBlock -> m[b] = anvils
                    b is AbstractSkullBlock -> m[b] = heads
                    b is AbstractBannerBlock -> m[b] = banners
                    BuiltInRegistries.BLOCK.getKey(b).path.endsWith("_concrete") -> m[b] = concrete
                }
            }
            add(Kind("ender chests", 2f, 4f, playerOnly = true), Blocks.ENDER_CHEST)
            add(Kind("beacons", 5f, playerOnly = true), Blocks.BEACON)
            add(Kind("conduits", 6f, playerOnly = true), Blocks.CONDUIT)
            add(Kind("lodestones", 4f, playerOnly = true), Blocks.LODESTONE)
            add(Kind("respawn anchors", 4f, playerOnly = true), Blocks.RESPAWN_ANCHOR)
            add(Kind("enchanting tables", 3f, playerOnly = true), Blocks.ENCHANTING_TABLE)
            add(Kind("jukeboxes", 3f, playerOnly = true), Blocks.JUKEBOX)
            add(Kind("portal blocks", 0.5f, 2f, playerOnly = true), Blocks.NETHER_PORTAL)
            add(Kind("hoppers", 2f), Blocks.HOPPER)
            add(Kind("ore blocks", 3f, playerOnly = true), Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK, Blocks.EMERALD_BLOCK)
            add(Kind("pistons", 1f, 6f), Blocks.PISTON, Blocks.STICKY_PISTON)
            add(Kind("redstone parts", 1f), Blocks.OBSERVER, Blocks.COMPARATOR, Blocks.CRAFTER, Blocks.DAYLIGHT_DETECTOR)
            add(chestKind, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL)
            add(Kind("dispensers", 1f), Blocks.DISPENSER, Blocks.DROPPER)
            add(Kind("brewing stands", 1f, 2f), Blocks.BREWING_STAND)
            add(Kind("workstations", 0.5f, 2f), Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER)
            add(Kind("obsidian", 0.2f, 2f), Blocks.OBSIDIAN)
            m
        }
    }
}
