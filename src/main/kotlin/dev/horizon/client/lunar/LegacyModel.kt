package dev.horizon.client.lunar

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * Lunar's older cosmetics (hats, bandannas, masks, glasses, ties, belts, backpacks...): a Wavefront
 * `.obj` per model (`cosmetics/models/<hats|bodywear>/<model>/<model>.obj`), one of several textures (the index's
 * path), and a definition in `indexes/hats.json` or `bodywear.json` saying which body part it rides on and how it's
 * placed. Placed as Lunar Client places them (from its code): the body part's pose, a quarter turn for headwear (hats,
 * bandannas, masks, glasses), the wearer's hat height offset (headwear), then each of the definition's
 * transformations in order. The model is in the player model's space, in blocks. Game-free.
 */
object LegacyModel {
    /** What the wearer has on that changes a transformation's values. */
    data class Wearing(val helmet: Boolean = false, val chestplate: Boolean = false, val skinLayer: Boolean = false) {
        companion object {
            val NONE = Wearing()
        }
    }

    /** One transformation's numbers: x, y, z (a rotation's axis) and a rotation's [angle] (degrees). */
    data class Values(val x: Float, val y: Float, val z: Float, val angle: Float = 0f)

    /** A definition from hats.json/bodywear.json. */
    class Definition(val name: String, val head: Boolean, val showWithArmor: Boolean, val transformations: List<Pair<String, JsonObject?>>)

    /** Triangles and quads, each as a quad of four vertices (a triangle repeats its last), [GeoModel.QUAD_FLOATS] each. */
    class Obj(val quads: FloatArray)

    /**
     * A transformation's values in the order Lunar applies them: plain `x`/`y`/`z` (and `angle`), or the `player` values
     * followed by the extra ones that apply to [w] (`skin_layer`, `helmet`, `chestplate`). The `gui` values are for
     * Lunar's menus only.
     */
    fun values(o: JsonObject?, w: Wearing): List<Values> {
        if (o == null) return emptyList()
        if (o.has("x") || o.has("angle")) return listOf(read(o))
        val out = ArrayList<Values>()
        o.getAsJsonObject("player")?.let { out += read(it) }
        if (w.skinLayer) o.getAsJsonObject("skin_layer")?.let { out += read(it) }
        if (w.helmet) o.getAsJsonObject("helmet")?.let { out += read(it) }
        if (w.chestplate) o.getAsJsonObject("chestplate")?.let { out += read(it) }
        return out
    }

    private fun read(o: JsonObject) = Values(f(o, "x"), f(o, "y"), f(o, "z"), f(o, "angle"))

    private fun f(o: JsonObject, k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f

    /** The definitions in hats.json or bodywear.json, by model name (lower case). */
    fun parseDefinitions(json: String): Map<String, Definition> {
        val out = HashMap<String, Definition>()
        for ((key, el) in JsonParser.parseString(json).asJsonObject.entrySet()) {
            val o = el as? JsonObject ?: continue
            val list = ArrayList<Pair<String, JsonObject?>>()
            for (t in o.getAsJsonArray("transformations") ?: continue) {
                val to = t as? JsonObject ?: continue
                list += (to.get("transformType")?.asString ?: continue) to to.getAsJsonObject("values")
            }
            val name = o.get("name")?.asString ?: key
            out[name.lowercase()] = Definition(name, o.get("bodyPart")?.asString == "HEAD",
                o.get("showWithArmor")?.asBoolean == true, list)
        }
        return out
    }

    /**
     * The placement after the body part's own pose, in blocks: for [headwear], a quarter turn and the wearer's
     * [hatHeight] (EquippedCosmetic.hat_height_offset, tenths of a block up), then [d]'s transformations for [w].
     */
    fun placement(d: Definition, headwear: Boolean, hatHeight: Float, w: Wearing): Matrix4f {
        val m = Matrix4f()
        if (headwear) {
            m.rotateY((Math.PI / 2).toFloat())
            m.translate(0f, -hatHeight * 0.1f, 0f)
        }
        for ((type, values) in d.transformations) {
            for (v in values(values, w)) when (type) {
                "translate" -> m.translate(v.x, v.y, v.z)
                "scale" -> m.scale(v.x, v.y, v.z)
                "rotate" -> if (v.angle != 0f && (v.x != 0f || v.y != 0f || v.z != 0f))
                    m.rotate(Math.toRadians(v.angle.toDouble()).toFloat(), Vector3f(v.x, v.y, v.z).normalize())
            }
        }
        return m
    }

    /** A Wavefront OBJ: its faces with texture coordinates (flipped to top-down) and normals. */
    fun parseObj(text: String): Obj {
        val v = ArrayList<FloatArray>()
        val vt = ArrayList<FloatArray>()
        val vn = ArrayList<FloatArray>()
        val out = ArrayList<Float>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val p = line.split(Regex("\\s+"))
            when (p[0]) {
                "v" -> v += floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
                "vt" -> vt += floatArrayOf(p[1].toFloat(), p.getOrNull(2)?.toFloat() ?: 0f)
                "vn" -> vn += floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
                "f" -> {
                    val corners = p.drop(1).map { c ->
                        val idx = c.split('/')
                        fun at(s: String?, size: Int) = s?.takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 0) size + it else it - 1 }
                        Triple(at(idx[0], v.size)!!, at(idx.getOrNull(1), vt.size), at(idx.getOrNull(2), vn.size))
                    }
                    // Fan the polygon into quads (a triangle repeats its last corner).
                    var k = 1
                    while (k + 1 < corners.size) {
                        val four = if (k + 2 < corners.size) listOf(corners[0], corners[k], corners[k + 1], corners[k + 2])
                        else listOf(corners[0], corners[k], corners[k + 1], corners[k + 1])
                        val pts = four.map { v[it.first] }
                        val e1 = Vector3f(pts[1][0] - pts[0][0], pts[1][1] - pts[0][1], pts[1][2] - pts[0][2])
                        val e2 = Vector3f(pts[2][0] - pts[0][0], pts[2][1] - pts[0][1], pts[2][2] - pts[0][2])
                        val n = four[0].third?.let { Vector3f(vn[it][0], vn[it][1], vn[it][2]) } ?: e1.cross(e2)
                        if (n.lengthSquared() > 0) n.normalize()
                        for ((i, c) in four.withIndex()) {
                            out += pts[i][0]; out += pts[i][1]; out += pts[i][2]
                            val t = c.second?.let { vt[it] }
                            out += t?.get(0) ?: 0f
                            out += 1f - (t?.get(1) ?: 0f)
                        }
                        out += n.x; out += n.y; out += n.z
                        k += 2
                    }
                }
            }
        }
        return Obj(out.toFloatArray())
    }
}
