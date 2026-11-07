package dev.horizon.client.lunar

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Not in the original: a Lunar GeckoLib cosmetic's animations (`.anim.json`, Bedrock animations as GeckoLib 4 reads
 * them): per bone, rotation (degrees), position (pixels) and scale channels, each a constant vector or keyframes with
 * GeckoLib's easings; every number may be Molang ([Molang]). [sample] adds an animation's values at a time onto a
 * [GeoModel.Pose]. Game-free.
 */
object GeoAnimation {
    class Animation(val name: String, val loop: Loop, val length: Double, val bones: List<BoneChannels>)

    enum class Loop { ONCE, LOOP, HOLD }

    /** A bone's channels (any may be null). */
    class BoneChannels(val bone: String, val rotation: Channel?, val position: Channel?, val scale: Channel?)

    /** A value over time: a constant [Vec], or keyframes sorted by time. */
    class Channel(val constant: Vec?, val keyframes: List<Keyframe>)

    class Vec(val x: Molang.Node, val y: Molang.Node, val z: Molang.Node)

    /** A keyframe at [time] s: the value arriving ([pre]) and leaving ([post]), reached with [easing]. */
    class Keyframe(val time: Double, val pre: Vec, val post: Vec, val easing: String, val args: DoubleArray)

    /** The animations in an `.anim.json`, by name. */
    fun parse(json: String): Map<String, Animation> {
        val root = JsonParser.parseString(json).asJsonObject
        val out = HashMap<String, Animation>()
        for ((name, el) in root.getAsJsonObject("animations")?.entrySet() ?: emptySet()) {
            val a = el as? JsonObject ?: continue
            val loop = when (val l = a.get("loop")) {
                null -> Loop.ONCE
                is JsonPrimitive -> when {
                    l.isBoolean -> if (l.asBoolean) Loop.LOOP else Loop.ONCE
                    l.isString && l.asString == "hold_on_last_frame" -> Loop.HOLD
                    l.isString && l.asString == "true" -> Loop.LOOP
                    else -> Loop.ONCE
                }
                else -> Loop.ONCE
            }
            val bones = ArrayList<BoneChannels>()
            var last = 0.0
            for ((bone, bel) in a.getAsJsonObject("bones")?.entrySet() ?: emptySet()) {
                val b = bel as? JsonObject ?: continue
                val r = channel(b.get("rotation"))
                val p = channel(b.get("position"))
                val s = channel(b.get("scale"))
                for (c in listOfNotNull(r, p, s)) c.keyframes.lastOrNull()?.let { last = maxOf(last, it.time) }
                bones += BoneChannels(bone, r, p, s)
            }
            val length = a.get("animation_length")?.takeIf { it.isJsonPrimitive }?.asDouble ?: last
            out[name] = Animation(name, loop, length, bones)
        }
        return out
    }

    private fun vec(el: JsonElement?): Vec? = when (el) {
        null -> null
        is JsonArray -> Vec(node(el.getOrNull(0)), node(el.getOrNull(1)), node(el.getOrNull(2)))
        is JsonPrimitive -> node(el).let { Vec(it, it, it) }
        is JsonObject -> vec(el.get("vector"))
        else -> null
    }

    private fun JsonArray.getOrNull(i: Int) = if (i < size()) get(i) else null

    private fun node(el: JsonElement?): Molang.Node = when {
        el == null || !el.isJsonPrimitive -> Molang.constOrCompile(0)
        el.asJsonPrimitive.isNumber -> Molang.constOrCompile(el.asDouble)
        else -> Molang.constOrCompile(el.asString)
    }

    private fun channel(el: JsonElement?): Channel? {
        if (el == null) return null
        if (el !is JsonObject || el.has("vector")) return vec(el)?.let { Channel(it, emptyList()) }
        val keys = ArrayList<Keyframe>()
        for ((t, kel) in el.entrySet()) {
            val time = t.toDoubleOrNull() ?: continue
            when (kel) {
                is JsonObject -> {
                    val post = vec(kel.get("post")) ?: vec(kel.get("vector")) ?: vec(kel.get("pre")) ?: continue
                    val pre = vec(kel.get("pre")) ?: post
                    val easing = (kel.get("easing")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: kel.get("lerp_mode")?.takeIf { it.isJsonPrimitive }?.asString ?: "linear")
                    val args = (kel.get("easingArgs") as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asDouble }?.toDoubleArray() ?: DoubleArray(0)
                    keys += Keyframe(time, pre, post, easing, args)
                }
                else -> vec(kel)?.let { keys += Keyframe(time, it, it, "linear", DoubleArray(0)) }
            }
        }
        if (keys.isEmpty()) return null
        keys.sortBy { it.time }
        return Channel(null, keys)
    }

    /** The time into [a] after [elapsed] seconds of playing it. */
    fun time(a: Animation, elapsed: Double): Double = when {
        a.length <= 0.0 -> elapsed
        a.loop == Loop.LOOP -> elapsed % a.length
        else -> minOf(elapsed, a.length)
    }

    /**
     * Adds [a] at [t] seconds onto [pose] for [model]'s bones, times [weight] (rotation and position add, scale moves
     * from 1 toward the animation's). Molang runs in [scope], whose `q.anim_time` the caller sets to [t].
     */
    fun sample(a: Animation, t: Double, model: GeoModel.Model, pose: GeoModel.Pose, scope: Molang.Scope, weight: Float = 1f) {
        for (b in a.bones) {
            val i = model.index[b.bone] ?: continue
            val o = i * 3
            b.rotation?.let { c ->
                value(c, t, scope) { x, y, z -> pose.rot[o] += x * weight; pose.rot[o + 1] += y * weight; pose.rot[o + 2] += z * weight }
            }
            b.position?.let { c ->
                value(c, t, scope) { x, y, z -> pose.pos[o] += x * weight; pose.pos[o + 1] += y * weight; pose.pos[o + 2] += z * weight }
            }
            b.scale?.let { c ->
                value(c, t, scope) { x, y, z ->
                    pose.scale[o] *= 1 + (x - 1) * weight; pose.scale[o + 1] *= 1 + (y - 1) * weight; pose.scale[o + 2] *= 1 + (z - 1) * weight
                }
            }
        }
    }

    private inline fun value(c: Channel, t: Double, s: Molang.Scope, out: (Float, Float, Float) -> Unit) {
        c.constant?.let {
            out(finite(it.x.run(s)), finite(it.y.run(s)), finite(it.z.run(s)))
            return
        }
        val k = c.keyframes
        if (t <= k[0].time || k.size == 1) {
            val v = k[0].pre
            out(finite(v.x.run(s)), finite(v.y.run(s)), finite(v.z.run(s)))
            return
        }
        var i = 1
        while (i < k.size && k[i].time < t) i++
        if (i >= k.size) {
            val v = k.last().post
            out(finite(v.x.run(s)), finite(v.y.run(s)), finite(v.z.run(s)))
            return
        }
        val a = k[i - 1]
        val b = k[i]
        val span = b.time - a.time
        val f = if (span <= 0) 1.0 else ease(b.easing, (t - a.time) / span, b.args)
        out(mix(a.post.x, b.pre.x, f, s), mix(a.post.y, b.pre.y, f, s), mix(a.post.z, b.pre.z, f, s))
    }

    private fun mix(x: Molang.Node, y: Molang.Node, f: Double, s: Molang.Scope): Float {
        val p = x.run(s)
        val q = y.run(s)
        return finite(p + (q - p) * f)
    }

    /** A value as a float, 0 when it isn't a finite number (a Molang domain error must not hide a cosmetic). */
    private fun finite(v: Double): Float = if (v.isFinite()) v.toFloat() else 0f

    /** GeckoLib's easings (names as in its EasingType), on progress [x] in 0..1. */
    fun ease(name: String, x: Double, args: DoubleArray = DoubleArray(0)): Double {
        val arg = args.firstOrNull()
        return when (name) {
            "linear", "catmullrom" -> x
            "step" -> if (x >= 1.0) 1.0 else 0.0
            "easeInSine" -> 1 - cos(x * PI / 2)
            "easeOutSine" -> sin(x * PI / 2)
            "easeInOutSine" -> -(cos(PI * x) - 1) / 2
            "easeInQuad" -> x * x
            "easeOutQuad" -> 1 - (1 - x) * (1 - x)
            "easeInOutQuad" -> if (x < 0.5) 2 * x * x else 1 - (-2 * x + 2).pow(2) / 2
            "easeInCubic" -> x * x * x
            "easeOutCubic" -> 1 - (1 - x).pow(3)
            "easeInOutCubic" -> if (x < 0.5) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
            "easeInQuart" -> x.pow(4)
            "easeOutQuart" -> 1 - (1 - x).pow(4)
            "easeInOutQuart" -> if (x < 0.5) 8 * x.pow(4) else 1 - (-2 * x + 2).pow(4) / 2
            "easeInQuint" -> x.pow(5)
            "easeOutQuint" -> 1 - (1 - x).pow(5)
            "easeInOutQuint" -> if (x < 0.5) 16 * x.pow(5) else 1 - (-2 * x + 2).pow(5) / 2
            "easeInExpo" -> if (x == 0.0) 0.0 else 2.0.pow(10 * x - 10)
            "easeOutExpo" -> if (x == 1.0) 1.0 else 1 - 2.0.pow(-10 * x)
            "easeInOutExpo" -> when {
                x == 0.0 || x == 1.0 -> x
                x < 0.5 -> 2.0.pow(20 * x - 10) / 2
                else -> (2 - 2.0.pow(-20 * x + 10)) / 2
            }
            "easeInCirc" -> 1 - sqrt(maxOf(0.0, 1 - x * x))
            "easeOutCirc" -> sqrt(maxOf(0.0, 1 - (x - 1) * (x - 1)))
            "easeInOutCirc" -> if (x < 0.5) (1 - sqrt(maxOf(0.0, 1 - (2 * x).pow(2)))) / 2 else (sqrt(maxOf(0.0, 1 - (-2 * x + 2).pow(2))) + 1) / 2
            "easeInBack" -> { val c1 = arg ?: 1.70158; (c1 + 1) * x * x * x - c1 * x * x }
            "easeOutBack" -> { val c1 = arg ?: 1.70158; 1 + (c1 + 1) * (x - 1).pow(3) + c1 * (x - 1).pow(2) }
            "easeInOutBack" -> {
                val c2 = (arg ?: 1.70158) * 1.525
                if (x < 0.5) ((2 * x).pow(2) * ((c2 + 1) * 2 * x - c2)) / 2 else ((2 * x - 2).pow(2) * ((c2 + 1) * (x * 2 - 2) + c2) + 2) / 2
            }
            "easeInElastic" -> { val p = elastic(arg); if (x == 0.0 || x == 1.0) x else -(2.0.pow(10 * x - 10)) * sin((x * 10 - 10.75) * p) }
            "easeOutElastic" -> { val p = elastic(arg); if (x == 0.0 || x == 1.0) x else 2.0.pow(-10 * x) * sin((x * 10 - 0.75) * p) + 1 }
            "easeInOutElastic" -> {
                val p = elastic(arg) / 1.5
                when {
                    x == 0.0 || x == 1.0 -> x
                    x < 0.5 -> -(2.0.pow(20 * x - 10) * sin((20 * x - 11.125) * p)) / 2
                    else -> 2.0.pow(-20 * x + 10) * sin((20 * x - 11.125) * p) / 2 + 1
                }
            }
            "easeOutBounce" -> bounceOut(x)
            "easeInBounce" -> 1 - bounceOut(1 - x)
            "easeInOutBounce" -> if (x < 0.5) (1 - bounceOut(1 - 2 * x)) / 2 else (1 + bounceOut(2 * x - 1)) / 2
            else -> x
        }
    }

    private fun elastic(arg: Double?) = (2 * PI) / 3 * (arg?.takeIf { it > 0 }?.let { 1.0 / it } ?: 1.0)

    private fun bounceOut(x0: Double): Double {
        var x = x0
        val n1 = 7.5625
        val d1 = 2.75
        return when {
            x < 1 / d1 -> n1 * x * x
            x < 2 / d1 -> { x -= 1.5 / d1; n1 * x * x + 0.75 }
            x < 2.5 / d1 -> { x -= 2.25 / d1; n1 * x * x + 0.9375 }
            else -> { x -= 2.625 / d1; n1 * x * x + 0.984375 }
        }
    }
}
