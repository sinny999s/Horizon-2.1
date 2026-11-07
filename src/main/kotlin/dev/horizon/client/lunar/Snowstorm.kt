package dev.horizon.client.lunar

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Not in the original: Bedrock particle effects (`.particle.json`) as Blockbuster's Snowstorm runs them, which is what
 * Lunar's emote and cosmetic particles are. The parts Lunar's 74 effects use: emitter initialization, lifetime
 * (looping, once, expression), rate (steady, instant), shapes (point, sphere, disc, box; offsets, directions, surface
 * only), local space; particle lifetime, initial speed and spin, dynamic and parametric motion, simple collision with
 * blocks, camera-facing billboards with UVs and flipbooks, tinting (colours and gradients), lighting; curves (linear,
 * Catmull-Rom, Bézier). Molang by [Molang]. Positions in world blocks. Game-free: the caller says where the emitter
 * is, which blocks are solid, and draws [Emitter.particles].
 */
object Snowstorm {
    /** A vector of Molang nodes. */
    class V(val n: List<Molang.Node>) {
        fun x(s: Molang.Scope) = n.getOrNull(0)?.run(s) ?: 0.0
        fun y(s: Molang.Scope) = n.getOrNull(1)?.run(s) ?: 0.0
        fun z(s: Molang.Scope) = n.getOrNull(2)?.run(s) ?: 0.0
        fun w(s: Molang.Scope) = n.getOrNull(3)?.run(s) ?: 1.0
    }

    class Curve(val type: String, val nodes: List<Molang.Node>, val input: Molang.Node, val range: Molang.Node)

    class Flipbook(val base: V, val size: V, val step: V, val fps: Molang.Node, val maxFrame: Molang.Node, val stretch: Boolean, val loop: Boolean)

    /** A tint: a colour vector (r, g, b, a in 0..1), or a gradient over [interpolant] (keys sorted, colours ARGB). */
    class Tint(val color: V?, val interpolant: Molang.Node?, val keys: DoubleArray, val colors: IntArray)

    class Scheme(
        val texture: String, val blend: Boolean, val bone: String?,
        val curves: Map<String, Curve>,
        val creation: Molang.Node?, val perUpdate: Molang.Node?,
        val localPosition: Boolean, val localRotation: Boolean, val localDirection: Boolean,
        val rateSteady: Molang.Node?, val rateMax: Molang.Node?, val rateInstant: Molang.Node?,
        val loopActive: Molang.Node?, val loopSleep: Molang.Node?, val onceActive: Molang.Node?,
        val activation: Molang.Node?, val expiration: Molang.Node?,
        val shape: String, val offset: V?, val radius: Molang.Node?, val half: V?, val planeNormal: V?, val surfaceOnly: Boolean,
        val direction: Any?,
        val maxLifetime: Molang.Node?, val particleExpiration: Molang.Node?,
        val speed: Any?, val spin: Molang.Node?, val spinRate: Molang.Node?,
        val accel: V?, val drag: Molang.Node?, val rotAccel: Molang.Node?, val rotDrag: Molang.Node?,
        val relPos: V?, val relRot: Molang.Node?,
        val collision: Boolean, val restitution: Double, val collisionDrag: Double, val expireOnContact: Boolean,
        val size: V?, val facing: String, val texW: Double, val texH: Double, val uv: V?, val uvSize: V?, val flipbook: Flipbook?,
        val tint: Tint?, val lit: Boolean,
    )

    private fun node(e: JsonElement?): Molang.Node? = when {
        e == null || e.isJsonNull -> null
        e is JsonPrimitive && e.isNumber -> Molang.constOrCompile(e.asDouble)
        e is JsonPrimitive && e.isBoolean -> Molang.constOrCompile(if (e.asBoolean) 1 else 0)
        e is JsonPrimitive -> Molang.constOrCompile(e.asString)
        else -> null
    }

    private fun vec(e: JsonElement?): V? = (e as? JsonArray)?.let { a -> V(a.map { node(it) ?: Molang.constOrCompile(0) }) }

    private fun color(s: String): Int {
        val h = s.removePrefix("#")
        return when (h.length) {
            6 -> (0xFF shl 24) or h.toInt(16)
            8 -> h.toLong(16).toInt()
            else -> -1
        }
    }

    fun parse(json: String): Scheme {
        val pe = JsonParser.parseString(json).asJsonObject.getAsJsonObject("particle_effect")
        val desc = pe.getAsJsonObject("description")
        val brp = desc.getAsJsonObject("basic_render_parameters")
        val c = pe.getAsJsonObject("components") ?: JsonObject()
        fun comp(k: String) = c.get("minecraft:$k") as? JsonObject
        val curves = HashMap<String, Curve>()
        for ((name, el) in pe.getAsJsonObject("curves")?.entrySet() ?: emptySet()) {
            val o = el as? JsonObject ?: continue
            val key = name.lowercase().removePrefix("variable.").removePrefix("v.")
            curves[key] = Curve(o.get("type")?.asString ?: "linear", (o.getAsJsonArray("nodes") ?: JsonArray()).mapNotNull { node(it) },
                node(o.get("input")) ?: Molang.constOrCompile(0), node(o.get("horizontal_range")) ?: Molang.constOrCompile(1))
        }
        val local = comp("emitter_local_space")
        val shapeKind = listOf("point", "sphere", "disc", "box", "entity_aabb").firstOrNull { comp("emitter_shape_$it") != null } ?: "point"
        val shape = comp("emitter_shape_$shapeKind") ?: JsonObject()
        val life = comp("particle_lifetime_expression")
        val dyn = comp("particle_motion_dynamic")
        val par = comp("particle_motion_parametric")
        val col = comp("particle_motion_collision")
        val bill = comp("particle_appearance_billboard")
        val uv = bill?.getAsJsonObject("uv")
        val fb = uv?.getAsJsonObject("flipbook")
        val tintEl = comp("particle_appearance_tinting")?.get("color")
        val tint = when (tintEl) {
            is JsonArray -> Tint(vec(tintEl), null, DoubleArray(0), IntArray(0))
            is JsonPrimitive -> if (tintEl.isString && tintEl.asString.startsWith("#")) Tint(null, null, doubleArrayOf(0.0), intArrayOf(color(tintEl.asString))) else null
            is JsonObject -> {
                val g = tintEl.get("gradient")
                val pairs = ArrayList<Pair<Double, Int>>()
                when (g) {
                    is JsonObject -> for ((k, v) in g.entrySet()) k.toDoubleOrNull()?.let { t -> pairs += t to colorOf(v) }
                    is JsonArray -> g.forEachIndexed { i, v -> pairs += (if (g.size() > 1) i.toDouble() / (g.size() - 1) else 0.0) to colorOf(v) }
                    else -> {}
                }
                pairs.sortBy { it.first }
                Tint(null, node(tintEl.get("interpolant")), pairs.map { it.first }.toDoubleArray(), pairs.map { it.second }.toIntArray())
            }
            else -> null
        }
        val init = comp("emitter_initialization")
        return Scheme(
            texture = brp?.get("texture")?.asString ?: "",
            blend = brp?.get("material")?.asString == "particles_blend",
            bone = brp?.get("bone")?.asString,
            curves = curves,
            creation = node(init?.get("creation_expression")), perUpdate = node(init?.get("per_update_expression")),
            localPosition = local?.get("position")?.asBoolean == true, localRotation = local?.get("rotation")?.asBoolean == true,
            localDirection = local?.get("direction")?.asBoolean == true,
            rateSteady = node(comp("emitter_rate_steady")?.get("spawn_rate")), rateMax = node(comp("emitter_rate_steady")?.get("max_particles")),
            rateInstant = node(comp("emitter_rate_instant")?.get("num_particles")),
            loopActive = node(comp("emitter_lifetime_looping")?.get("active_time")), loopSleep = node(comp("emitter_lifetime_looping")?.get("sleep_time")),
            onceActive = node(comp("emitter_lifetime_once")?.get("active_time")),
            activation = node(comp("emitter_lifetime_expression")?.get("activation_expression")),
            expiration = node(comp("emitter_lifetime_expression")?.get("expiration_expression")),
            shape = shapeKind, offset = vec(shape.get("offset")), radius = node(shape.get("radius")), half = vec(shape.get("half_dimensions")),
            planeNormal = vec(shape.get("plane_normal")) ?: (shape.get("plane_normal") as? JsonPrimitive)?.asString?.let {
                when (it) { "x" -> V(listOf(Molang.constOrCompile(1), Molang.constOrCompile(0), Molang.constOrCompile(0)))
                    "z" -> V(listOf(Molang.constOrCompile(0), Molang.constOrCompile(0), Molang.constOrCompile(1))); else -> null }
            },
            surfaceOnly = shape.get("surface_only")?.asBoolean == true,
            direction = shape.get("direction")?.let { if (it is JsonArray) vec(it) else it.asString },
            maxLifetime = node(life?.get("max_lifetime")), particleExpiration = node(life?.get("expiration_expression")),
            speed = c.get("minecraft:particle_initial_speed")?.let { if (it is JsonArray) vec(it) else node(it) },
            spin = node(comp("particle_initial_spin")?.get("rotation")), spinRate = node(comp("particle_initial_spin")?.get("rotation_rate")),
            accel = vec(dyn?.get("linear_acceleration")), drag = node(dyn?.get("linear_drag_coefficient")),
            rotAccel = node(dyn?.get("rotation_acceleration")), rotDrag = node(dyn?.get("rotation_drag_coefficient")),
            relPos = vec(par?.get("relative_position")), relRot = node(par?.get("rotation")),
            collision = col != null && col.get("enabled")?.let { !(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean) } ?: (col != null),
            restitution = col?.get("coefficient_of_restitution")?.asDouble ?: 0.0, collisionDrag = col?.get("collision_drag")?.asDouble ?: 0.0,
            expireOnContact = col?.get("expire_on_contact")?.asBoolean == true,
            size = vec(bill?.get("size")), facing = bill?.get("facing_camera_mode")?.asString ?: "rotate_xyz",
            texW = uv?.get("texture_width")?.asDouble ?: 1.0, texH = uv?.get("texture_height")?.asDouble ?: 1.0,
            uv = vec(uv?.get("uv")), uvSize = vec(uv?.get("uv_size")),
            flipbook = fb?.let {
                Flipbook(vec(it.get("base_UV")) ?: V(listOf(Molang.constOrCompile(0), Molang.constOrCompile(0))), vec(it.get("size_UV")) ?: V(emptyList()),
                    vec(it.get("step_UV")) ?: V(emptyList()), node(it.get("frames_per_second")) ?: Molang.constOrCompile(8),
                    node(it.get("max_frame")) ?: Molang.constOrCompile(1), it.get("stretch_to_lifetime")?.asBoolean == true, it.get("loop")?.asBoolean == true)
            },
            tint = tint, lit = c.has("minecraft:particle_appearance_lighting"),
        )
    }

    private fun colorOf(v: JsonElement): Int = when {
        v is JsonPrimitive && v.isString -> color(v.asString)
        v is JsonArray -> {
            fun ch(i: Int) = (if (i < v.size()) v.get(i).asDouble.coerceIn(0.0, 1.0) * 255 else 255.0).toInt()
            (ch(3) shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
        else -> -1
    }

    /** One particle. Positions are world blocks, or relative to the emitter in local space. */
    class Particle(val r: DoubleArray) {
        var x = 0.0; var y = 0.0; var z = 0.0
        var vx = 0.0; var vy = 0.0; var vz = 0.0
        var ox = 0.0; var oy = 0.0; var oz = 0.0
        var age = 0.0
        var life = 1.0
        var rot = 0.0
        var rotRate = 0.0
        var dead = false

        // What to draw this frame, filled by the emitter: half sizes, rotation, uv rectangle, colour.
        var w = 0.0; var h = 0.0
        var u0 = 0f; var v0 = 0f; var u1 = 1f; var v1 = 1f
        var argb = -1
    }

    /** An emitter of [scheme], where its owner puts it every frame ([update]). */
    class Emitter(val scheme: Scheme, private val random: Random = Random()) {
        val scope = Molang.Scope(random = random)
        val particles = ArrayList<Particle>()
        var x = 0.0; var y = 0.0; var z = 0.0
        /** The owner's facing (degrees), for local rotation and direction. */
        var yaw = 0.0
        private var age = 0.0
        private var loopStart = 0.0
        private var spawnAcc = 0.0
        private var started = false
        private var instantDone = false
        /** No longer emitting (its particles may still live). */
        var finished = false
            private set

        val done get() = finished && particles.isEmpty()

        private val vars get() = scope.variables

        init {
            vars["emitter_random_1"] = random.nextDouble()
            vars["emitter_random_2"] = random.nextDouble()
            vars["emitter_random_3"] = random.nextDouble()
            vars["emitter_random_4"] = random.nextDouble()
        }

        /** Stops emitting; the particles live out their lives. */
        fun stop() {
            finished = true
        }

        /**
         * Moves the emitter on by [dt] seconds; [solid] says whether a block position is solid (for collisions).
         */
        fun update(dt: Double, solid: (Int, Int, Int) -> Boolean) {
            val s = scheme
            if (!started) {
                started = true
                s.creation?.run(scope)
            }
            age += dt
            vars["emitter_age"] = age - loopStart
            s.perUpdate?.run(scope)
            var active = !finished
            if (active) {
                when {
                    s.loopActive != null -> {
                        val a = s.loopActive.run(scope)
                        val sleep = s.loopSleep?.run(scope) ?: 0.0
                        vars["emitter_lifetime"] = a
                        val t = age - loopStart
                        if (t >= a + sleep && a + sleep > 0) {
                            loopStart = age
                            instantDone = false
                            vars["emitter_age"] = 0.0
                            vars["emitter_random_1"] = random.nextDouble()
                            vars["emitter_random_2"] = random.nextDouble()
                            vars["emitter_random_3"] = random.nextDouble()
                            vars["emitter_random_4"] = random.nextDouble()
                        }
                        active = (age - loopStart) < a
                    }
                    s.onceActive != null -> {
                        val a = s.onceActive.run(scope)
                        vars["emitter_lifetime"] = a
                        if (age >= a) {
                            active = false
                            finished = true
                        }
                    }
                    s.activation != null -> {
                        if (s.expiration != null && s.expiration.run(scope) != 0.0) {
                            active = false
                            finished = true
                        } else active = s.activation.run(scope) != 0.0
                    }
                }
            }
            if (active) {
                if (s.rateInstant != null && !instantDone) {
                    instantDone = true
                    repeat(s.rateInstant.run(scope).toInt().coerceIn(0, 2000)) { spawn() }
                }
                if (s.rateSteady != null) {
                    val max = s.rateMax?.run(scope)?.toInt() ?: 100
                    spawnAcc += s.rateSteady.run(scope).coerceAtLeast(0.0) * dt
                    while (spawnAcc >= 1.0) {
                        spawnAcc -= 1.0
                        if (particles.size < max) spawn()
                    }
                }
            }
            val it = particles.iterator()
            while (it.hasNext()) {
                val p = it.next()
                p.age += dt
                bind(p)
                if (p.age >= p.life || (s.particleExpiration?.run(scope) ?: 0.0) != 0.0 || p.dead) {
                    it.remove()
                    continue
                }
                move(p, dt, solid)
                look(p)
            }
        }

        private fun bind(p: Particle) {
            vars["particle_age"] = p.age
            vars["particle_lifetime"] = p.life
            vars["particle_random_1"] = p.r[0]
            vars["particle_random_2"] = p.r[1]
            vars["particle_random_3"] = p.r[2]
            vars["particle_random_4"] = p.r[3]
            for ((name, c) in scheme.curves) vars[name] = curve(c)
        }

        private fun curve(c: Curve): Double {
            val n = c.nodes.size
            if (n == 0) return 0.0
            val range = c.range.run(scope).takeIf { it != 0.0 } ?: 1.0
            val t = (c.input.run(scope) / range).coerceIn(0.0, 1.0)
            fun at(i: Int) = c.nodes[i.coerceIn(0, n - 1)].run(scope)
            return when (c.type) {
                "catmull_rom" -> {
                    if (n < 4) return at(0)
                    val segs = n - 3
                    val f = t * segs
                    val i = minOf(f.toInt(), segs - 1)
                    val u = f - i
                    val p0 = at(i); val p1 = at(i + 1); val p2 = at(i + 2); val p3 = at(i + 3)
                    0.5 * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u + (-p0 + 3 * p1 - 3 * p2 + p3) * u * u * u)
                }
                "bezier" -> {
                    if (n < 4) return at(0)
                    val u = 1 - t
                    u * u * u * at(0) + 3 * u * u * t * at(1) + 3 * u * t * t * at(2) + t * t * t * at(3)
                }
                else -> {
                    if (n == 1) return at(0)
                    val f = t * (n - 1)
                    val i = minOf(f.toInt(), n - 2)
                    at(i) + (at(i + 1) - at(i)) * (f - i)
                }
            }
        }

        private fun spawn() {
            val s = scheme
            val p = Particle(DoubleArray(4) { random.nextDouble() })
            bind(p)
            p.life = s.maxLifetime?.run(scope)?.takeIf { it > 0 } ?: 1.0
            vars["particle_lifetime"] = p.life
            // Position in the shape (emitter-relative), and the direction it leaves in.
            val ox = s.offset?.x(scope) ?: 0.0
            val oy = s.offset?.y(scope) ?: 0.0
            val oz = s.offset?.z(scope) ?: 0.0
            var lx = 0.0; var ly = 0.0; var lz = 0.0
            when (s.shape) {
                "sphere" -> {
                    val r = s.radius?.run(scope) ?: 1.0
                    var dx: Double; var dy: Double; var dz: Double
                    do { dx = random.nextDouble() * 2 - 1; dy = random.nextDouble() * 2 - 1; dz = random.nextDouble() * 2 - 1 } while (dx * dx + dy * dy + dz * dz > 1 || dx * dx + dy * dy + dz * dz == 0.0)
                    val len = sqrt(dx * dx + dy * dy + dz * dz)
                    val k = if (s.surfaceOnly) r / len else r
                    lx = dx * k; ly = dy * k; lz = dz * k
                }
                "disc" -> {
                    val r = s.radius?.run(scope) ?: 1.0
                    val a = random.nextDouble() * Math.PI * 2
                    val d = if (s.surfaceOnly) r else r * sqrt(random.nextDouble())
                    // A disc in the plane its normal says (y up by default).
                    val nx = s.planeNormal?.x(scope) ?: 0.0
                    val ny = s.planeNormal?.y(scope) ?: 1.0
                    val nz = s.planeNormal?.z(scope) ?: 0.0
                    val px = cos(a) * d
                    val pz = sin(a) * d
                    when {
                        Math.abs(nx) > 0.5 -> { ly = px; lz = pz }
                        Math.abs(nz) > 0.5 -> { lx = px; ly = pz }
                        else -> { lx = px; lz = pz }
                    }
                }
                "box" -> {
                    val hx = s.half?.x(scope) ?: 0.0
                    val hy = s.half?.y(scope) ?: 0.0
                    val hz = s.half?.z(scope) ?: 0.0
                    lx = (random.nextDouble() * 2 - 1) * hx
                    ly = (random.nextDouble() * 2 - 1) * hy
                    lz = (random.nextDouble() * 2 - 1) * hz
                    if (s.surfaceOnly) when (random.nextInt(3)) {
                        0 -> lx = if (random.nextBoolean()) hx else -hx
                        1 -> ly = if (random.nextBoolean()) hy else -hy
                        else -> lz = if (random.nextBoolean()) hz else -hz
                    }
                }
            }
            var dx: Double; var dy: Double; var dz: Double
            when (val d = s.direction) {
                is V -> { dx = d.x(scope); dy = d.y(scope); dz = d.z(scope) }
                "inwards" -> { dx = -lx; dy = -ly; dz = -lz }
                else -> { dx = lx; dy = ly; dz = lz }
            }
            if (s.direction !is V && dx == 0.0 && dy == 0.0 && dz == 0.0) {
                dx = random.nextDouble() * 2 - 1; dy = random.nextDouble() * 2 - 1; dz = random.nextDouble() * 2 - 1
            }
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len > 0) { dx /= len; dy /= len; dz /= len }
            lx += ox; ly += oy; lz += oz
            if (s.localDirection || s.localRotation) {
                val r = rotate(dx, dz); dx = r.first; dz = r.second
            }
            if (s.localRotation) {
                val r = rotate(lx, lz); lx = r.first; lz = r.second
            }
            if (s.localPosition) { p.x = lx; p.y = ly; p.z = lz } else { p.x = x + lx; p.y = y + ly; p.z = z + lz }
            p.ox = p.x; p.oy = p.y; p.oz = p.z
            when (val sp = s.speed) {
                is V -> { p.vx = sp.x(scope); p.vy = sp.y(scope); p.vz = sp.z(scope) }
                is Molang.Node -> { val v = sp.run(scope); p.vx = dx * v; p.vy = dy * v; p.vz = dz * v }
            }
            p.rot = s.spin?.run(scope) ?: 0.0
            p.rotRate = s.spinRate?.run(scope) ?: 0.0
            particles += p
            look(p)
        }

        /** Turns emitter-local x, z by the owner's facing. */
        private fun rotate(a: Double, b: Double): Pair<Double, Double> {
            val r = Math.toRadians(yaw)
            val c = cos(r)
            val s = sin(r)
            return (a * c - b * s) to (a * s + b * c)
        }

        private fun move(p: Particle, dt: Double, solid: (Int, Int, Int) -> Boolean) {
            val s = scheme
            if (s.relPos != null) {
                p.x = p.ox + s.relPos.x(scope); p.y = p.oy + s.relPos.y(scope); p.z = p.oz + s.relPos.z(scope)
                s.relRot?.let { p.rot = it.run(scope) }
                return
            }
            s.accel?.let { a -> p.vx += a.x(scope) * dt; p.vy += a.y(scope) * dt; p.vz += a.z(scope) * dt }
            val drag = s.drag?.run(scope) ?: 0.0
            if (drag != 0.0) {
                val k = maxOf(0.0, 1 - drag * dt)
                p.vx *= k; p.vy *= k; p.vz *= k
            }
            p.rotRate += (s.rotAccel?.run(scope) ?: 0.0) * dt
            val rd = s.rotDrag?.run(scope) ?: 0.0
            if (rd != 0.0) p.rotRate *= maxOf(0.0, 1 - rd * dt)
            p.rot += p.rotRate * dt
            val nx = p.x + p.vx * dt
            val ny = p.y + p.vy * dt
            val nz = p.z + p.vz * dt
            if (s.collision) {
                val wx = if (s.localPosition) x + nx else nx
                val wy = if (s.localPosition) y + ny else ny
                val wz = if (s.localPosition) z + nz else nz
                if (solid(Math.floor(wx).toInt(), Math.floor(wy).toInt(), Math.floor(wz).toInt())) {
                    if (s.expireOnContact) { p.dead = true; return }
                    p.vy = -p.vy * s.restitution
                    val k = maxOf(0.0, 1 - s.collisionDrag * dt)
                    p.vx *= k; p.vz *= k
                    return
                }
            }
            p.x = nx; p.y = ny; p.z = nz
        }

        /** Size, uv and colour for drawing [p] (its variables bound). */
        private fun look(p: Particle) {
            val s = scheme
            p.w = s.size?.x(scope) ?: 0.1
            p.h = s.size?.y(scope) ?: p.w
            val fb = s.flipbook
            var u: Double; var v: Double; var uw: Double; var vh: Double
            if (fb != null) {
                val max = maxOf(1, fb.maxFrame.run(scope).toInt())
                val frame = if (fb.stretch) minOf(max - 1, (p.age / p.life * max).toInt())
                else (p.age * fb.fps.run(scope)).toInt().let { if (fb.loop) it % max else minOf(it, max - 1) }
                u = fb.base.x(scope) + fb.step.x(scope) * frame
                v = fb.base.y(scope) + fb.step.y(scope) * frame
                uw = fb.size.x(scope)
                vh = fb.size.y(scope)
            } else {
                u = s.uv?.x(scope) ?: 0.0
                v = s.uv?.y(scope) ?: 0.0
                uw = s.uvSize?.x(scope) ?: s.texW
                vh = s.uvSize?.y(scope) ?: s.texH
            }
            p.u0 = (u / s.texW).toFloat(); p.v0 = (v / s.texH).toFloat()
            p.u1 = ((u + uw) / s.texW).toFloat(); p.v1 = ((v + vh) / s.texH).toFloat()
            val t = s.tint
            p.argb = when {
                t == null -> -1
                t.color != null -> {
                    fun ch(d: Double) = (d.coerceIn(0.0, 1.0) * 255).toInt()
                    (ch(t.color.w(scope)) shl 24) or (ch(t.color.x(scope)) shl 16) or (ch(t.color.y(scope)) shl 8) or ch(t.color.z(scope))
                }
                t.keys.isEmpty() -> -1
                t.interpolant == null || t.keys.size == 1 -> t.colors[0]
                else -> gradient(t, t.interpolant.run(scope))
            }
        }

        private fun gradient(t: Tint, x: Double): Int {
            val k = t.keys
            if (x <= k[0]) return t.colors[0]
            if (x >= k.last()) return t.colors.last()
            var i = 1
            while (k[i] < x) i++
            val f = (x - k[i - 1]) / (k[i] - k[i - 1])
            val a = t.colors[i - 1]
            val b = t.colors[i]
            fun mix(sh: Int) = (((a ushr sh) and 255) + ((((b ushr sh) and 255) - ((a ushr sh) and 255)) * f)).toInt() and 255
            return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
    }
}
