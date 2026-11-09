package dev.horizon.client.lunar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * Plays a Lunar GeckoLib cosmetic's animations, as Lunar does, for one wearer at a time
 * ([Instance]): every frame Lunar's `constants.molang` updates the wearer's `c.` values, each animation controller of
 * the cosmetic's state machine picks its state (the first whose `plays_when` holds; "single" machines always play
 * their one animation), switches cross-fade over the controller's transition ticks, and the animations are sampled
 * onto the bones and baked. Game-free: the caller supplies the `q.` values.
 */
class CosmeticAnimator(
    val model: GeoModel.Model,
    private val animations: Map<String, GeoAnimation.Animation>,
    private val controllers: List<Controller>,
    private val constants: Molang.Node?,
    private val functions: Map<String, Molang.Function>,
    /** The cosmetic's own transformations, in J pixels (or null). */
    private val pre: Matrix4f?,
    /** Its options' values (`option.<name>`): the wearer's choice isn't sent, so their defaults. */
    private val options: Map<String, Double> = emptyMap(),
) {
    class State(val anim: String, val playsWhen: Molang.Node, val transitionTicks: Double?)
    class Controller(val states: List<State>, val transitionTicks: Double)

    /** One wearer's animation state. */
    inner class Instance(private val created: Double) {
        val scope = Molang.Scope(functions = functions).also { it.options = options }
        private val pose = GeoModel.Pose(model.bones.size)
        val mesh = model.newMesh()
        private val current = IntArray(controllers.size) { -1 }
        private val started = DoubleArray(controllers.size)
        private val previous = IntArray(controllers.size) { -1 }
        private val previousStarted = DoubleArray(controllers.size)
        private val switched = DoubleArray(controllers.size)
        private var animTime = 0.0
        private var lifeTime = 0.0
        private var failed = false

        /** Poses and bakes the model at [now] (seconds); returns [mesh]. */
        fun update(now: Double, query: (String) -> Double): GeoModel.Mesh {
            lifeTime = now - created
            scope.query = { n ->
                when (n) {
                    "life_time" -> lifeTime
                    "anim_time" -> animTime
                    else -> query(n)
                }
            }
            pose.reset()
            if (!failed) {
                try {
                    constants?.run(scope)
                    for ((i, c) in controllers.withIndex()) play(i, c, now)
                } catch (e: Throwable) {
                    // A Molang loop gone wrong (or anything else) leaves the cosmetic still rather than breaking rendering.
                    failed = true
                    pose.reset()
                }
            }
            model.bake(mesh, pose, pre)
            return mesh
        }

        private fun play(i: Int, c: Controller, now: Double) {
            animTime = 0.0
            var pick = -1
            for ((k, st) in c.states.withIndex()) if (st.playsWhen.run(scope) != 0.0) { pick = k; break }
            if (pick != current[i]) {
                previous[i] = current[i]
                previousStarted[i] = started[i]
                switched[i] = now
                current[i] = pick
                started[i] = now
            }
            if (pick < 0) return
            val ticks = c.states[pick].transitionTicks ?: c.transitionTicks
            val w = if (ticks <= 0 || previous[i] < 0) 1.0 else ((now - switched[i]) / (ticks / 20.0)).coerceIn(0.0, 1.0)
            if (w < 1.0) sample(c.states[previous[i]].anim, now - previousStarted[i], (1 - w).toFloat())
            sample(c.states[pick].anim, now - started[i], w.toFloat())
        }

        private fun sample(name: String, elapsed: Double, weight: Float) {
            val a = animations[name] ?: return
            val t = GeoAnimation.time(a, elapsed)
            animTime = t
            GeoAnimation.sample(a, t, model, pose, scope, weight)
        }
    }

    companion object {
        private val ALWAYS = Molang.constOrCompile(1)

        /**
         * The controllers of a `.gek.json`'s `state_machine`: "single" (its `anim`), "molang" (its `controllers`), or
         * with neither, the cosmetic's `default_anim`, else its only animation.
         */
        fun controllers(gek: JsonObject, animations: Map<String, GeoAnimation.Animation>): List<Controller> {
            val sm = gek.getAsJsonObject("state_machine")
            when (sm?.get("name")?.asString) {
                "single" -> {
                    val anim = sm.get("anim")?.asString ?: return emptyList()
                    return listOf(Controller(listOf(State(anim, ALWAYS, null)), sm.get("transition_tick_length")?.asDouble ?: 0.0))
                }
                "molang" -> {
                    val out = ArrayList<Controller>()
                    for (el in sm.getAsJsonArray("controllers") ?: JsonArray()) {
                        val c = el.asJsonObject
                        val states = (c.getAsJsonArray("states") ?: JsonArray()).mapNotNull { s ->
                            val o = s.asJsonObject
                            val anim = o.get("anim")?.asString ?: return@mapNotNull null
                            State(anim, Molang.constOrCompile(o.get("plays_when")?.let { if (it.asJsonPrimitive.isNumber) it.asDouble else it.asString } ?: 1),
                                o.get("transition_ticks")?.asString?.toDoubleOrNull())
                        }
                        out += Controller(states, c.get("default_transition_ticks")?.asString?.toDoubleOrNull() ?: 0.0)
                    }
                    return out
                }
            }
            val anim = gek.get("default_anim")?.asString ?: animations.keys.singleOrNull() ?: return emptyList()
            return listOf(Controller(listOf(State(anim, ALWAYS, null)), 0.0))
        }

        /** A `.gek.json`'s `cosmetic_options`' defaults, by the name their `molang_query` gives (`option.size` → size). */
        fun options(gek: JsonObject): Map<String, Double> {
            val out = HashMap<String, Double>()
            for (el in gek.getAsJsonArray("cosmetic_options") ?: return out) {
                val o = el as? JsonObject ?: continue
                val q = o.get("molang_query")?.asString?.lowercase() ?: continue
                val d = o.get("default") ?: continue
                val v = when {
                    d.isJsonPrimitive && d.asJsonPrimitive.isNumber -> d.asDouble
                    d.isJsonPrimitive && d.asJsonPrimitive.isBoolean -> if (d.asBoolean) 1.0 else 0.0
                    else -> continue
                }
                out[q.removePrefix("option.").removePrefix("o.")] = v
            }
            return out
        }

        /**
         * A `.gek.json`'s `transformations` (Lunar applies them to the model in its own space, blocks, y up, from the
         * feet) as one transform in J pixels; null when it has none.
         */
        fun transformations(gek: JsonObject): Matrix4f? {
            val list = gek.getAsJsonArray("transformations") ?: return null
            val w = Matrix4f()
            for (el in list) {
                val t = el as? JsonObject ?: continue
                val v = LegacyModel.values(t.getAsJsonObject("values"), LegacyModel.Wearing.NONE).firstOrNull() ?: continue
                when (t.get("transformType")?.asString) {
                    "translate" -> w.translate(v.x * 16, v.y * 16, v.z * 16)
                    "scale" -> w.scale(v.x, v.y, v.z)
                    "rotate" -> if (v.angle != 0f) w.rotate(Math.toRadians(v.angle.toDouble()).toFloat(), Vector3f(v.x, v.y, v.z).normalize())
                }
            }
            // W (y up, x mirrored, from the feet) to J (y down from the neck): T(o)·S·W·S·T(−o), S = diag(−1, −1, 1).
            val s = Matrix4f().scale(-1f, -1f, 1f)
            return Matrix4f().translate(0f, 24f, 0f).mul(s).mul(w).mul(s).translate(0f, -24f, 0f)
        }
    }
}
