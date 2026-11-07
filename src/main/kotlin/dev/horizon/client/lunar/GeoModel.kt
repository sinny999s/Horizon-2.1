package dev.horizon.client.lunar

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * Not in the original: a Lunar GeckoLib cosmetic's Bedrock geometry (`.geo.json`), baked into textured quads in the
 * player model's own space, one list per body part, so each part's quads can be drawn with that part's pose. In its
 * rest pose: the cosmetic's animations (Molang) aren't played. Game-free.
 *
 * Spaces: a Bedrock file is in pixels, y up, feet at 0, and mirrored in x; it's read as GeckoLib and Blockbench read it
 * (x negated: "W"), then put in the player model's space (Java, "J": x as in the file, y down from the neck at 24, so
 * `J = (x, 24 − y, z)`). A bone or cube rotation `[rx, ry, rz]` (degrees) becomes `Rz(rz)·Ry(ry)·Rx(rx)` in J about its
 * pivot. Faces are named as Blockbench names them in W: north −z, south +z, east +x, west −x, up +y, down −y.
 *
 * Which body part a bone rides on: its own or an ancestor's name (`bipedHead`, `armorRightArm`; plain `head`, `leftLeg`
 * only without an `attached_bone`, since a pet names its own head and legs); else the cosmetic's `attached_bone` (HEAD, RIGHT_ARM...; SHOULDER and anything else rides on the body); with none
 * (NONE), each top-level bone by where its cubes are (above the neck: head; at the torso's height: body or an arm by x;
 * lower: a leg by x).
 */
object GeoModel {
    enum class Limb { HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG }

    /**
     * Quads per body part, each [QUAD_FLOATS] floats: four vertices of (x, y, z in J pixels, u, v in 0..1), then the
     * face normal (x, y, z).
     */
    class Mesh(val parts: Map<Limb, FloatArray>) {
        val quadCount get() = parts.values.sumOf { it.size / QUAD_FLOATS }
    }

    const val QUAD_FLOATS = 23

    /**
     * One bone: its [parent] (index, −1 for none, always before it), body part, pivot (J pixels), rest rotation (file
     * degrees) and its cubes' quads in J pixels (the cubes' own rotations applied, the bones' not).
     */
    class BoneData(val name: String, val parent: Int, val limb: Limb, val pivot: Vector3f, val rest: Vector3f, val quads: FloatArray)

    /** A parsed model: bones parents first. [Pose] moves them; [bake] turns them into a [Mesh]. */
    class Model(val bones: List<BoneData>) {
        val index: Map<String, Int> = bones.withIndex().associate { (i, b) -> b.name to i }

        /** A mesh with room for every quad, to [bake] into again and again. */
        fun newMesh(): Mesh {
            val sizes = HashMap<Limb, Int>()
            for (b in bones) if (b.quads.isNotEmpty()) sizes[b.limb] = (sizes[b.limb] ?: 0) + b.quads.size
            return Mesh(sizes.mapValues { FloatArray(it.value) })
        }

        private val matrices = Array(bones.size) { Matrix4f() }
        private val tmp = Vector3f()
        private val nrm = Vector3f()

        /**
         * Bakes the bones, moved by [pose] (null: rest), into [into] (made by [newMesh]). [pre]: a transform of the whole
         * model in J pixels, applied first (Lunar's per-cosmetic transformations), or null.
         */
        fun bake(into: Mesh, pose: Pose?, pre: Matrix4f?) {
            val offsets = HashMap<Limb, Int>()
            for ((i, b) in bones.withIndex()) {
                val m = matrices[i]
                if (b.parent >= 0) m.set(matrices[b.parent]) else if (pre != null) m.set(pre) else m.identity()
                val p = b.pivot
                if (pose != null) {
                    val o = i * 3
                    // GeckoLib: position, then about the pivot rotation (rest + animation) and scale.
                    m.translate(pose.pos[o], -pose.pos[o + 1], pose.pos[o + 2])
                    m.translate(p)
                    rotate(m, b.rest.x + pose.rot[o], b.rest.y + pose.rot[o + 1], b.rest.z + pose.rot[o + 2])
                    m.scale(pose.scale[o], pose.scale[o + 1], pose.scale[o + 2])
                    m.translate(-p.x, -p.y, -p.z)
                } else if (b.rest.x != 0f || b.rest.y != 0f || b.rest.z != 0f) {
                    m.translate(p)
                    rotate(m, b.rest.x, b.rest.y, b.rest.z)
                    m.translate(-p.x, -p.y, -p.z)
                }
                if (b.quads.isEmpty()) continue
                val out = into.parts[b.limb] ?: continue
                var w = offsets[b.limb] ?: 0
                val q = b.quads
                var k = 0
                while (k < q.size) {
                    for (v in 0 until 4) {
                        val o = k + v * 5
                        m.transformPosition(q[o], q[o + 1], q[o + 2], tmp)
                        out[w++] = tmp.x; out[w++] = tmp.y; out[w++] = tmp.z; out[w++] = q[o + 3]; out[w++] = q[o + 4]
                    }
                    m.transformDirection(q[k + 20], q[k + 21], q[k + 22], nrm)
                    if (nrm.lengthSquared() > 0f) nrm.normalize()
                    out[w++] = nrm.x; out[w++] = nrm.y; out[w++] = nrm.z
                    k += QUAD_FLOATS
                }
                offsets[b.limb] = w
            }
        }

        /** The model at rest. */
        fun rest(): Mesh = newMesh().also { bake(it, null, null) }
    }

    /** Each bone's animated rotation (degrees, file convention), position (file pixels) and scale, three per bone. */
    class Pose(n: Int) {
        val rot = FloatArray(n * 3)
        val pos = FloatArray(n * 3)
        val scale = FloatArray(n * 3) { 1f }

        fun reset() {
            rot.fill(0f)
            pos.fill(0f)
            scale.fill(1f)
        }
    }

    private class Bone(val name: String, val parent: String?, val json: JsonObject) {
        var limb: Limb? = null
    }

    /** A Bedrock geometry file as a [Model]. Bones on body parts as described for [GeoModel]. */
    fun parse(json: String, attachedBone: String = "NONE"): Model {
        val root = JsonParser.parseString(json).asJsonObject
        val geo = root.getAsJsonArray("minecraft:geometry")?.firstOrNull()?.asJsonObject ?: error("no minecraft:geometry")
        val desc = geo.getAsJsonObject("description")
        val texW = desc?.get("texture_width")?.asFloat ?: 64f
        val texH = desc?.get("texture_height")?.asFloat ?: 64f
        val bones = LinkedHashMap<String, Bone>()
        for (el in geo.getAsJsonArray("bones") ?: JsonArray()) {
            val o = el.asJsonObject
            val name = o.get("name")?.asString ?: continue
            bones[name] = Bone(name, o.get("parent")?.asString, o)
        }
        assignLimbs(bones, attachedBone)
        // Parents first.
        val ordered = ArrayList<Bone>()
        val placed = HashSet<String>()
        fun place(b: Bone, depth: Int) {
            if (b.name in placed || depth > 64) return
            b.parent?.let { bones[it] }?.let { place(it, depth + 1) }
            placed += b.name
            ordered += b
        }
        for (b in bones.values) place(b, 0)
        val idx = HashMap<String, Int>()
        val out = ArrayList<BoneData>()
        for (b in ordered) {
            val list = ArrayList<Float>()
            for (c in b.json.getAsJsonArray("cubes") ?: JsonArray()) cube(c.asJsonObject, Matrix4f(), texW, texH, list)
            val pv = vec(b.json, "pivot")
            idx[b.name] = out.size
            out += BoneData(b.name, b.parent?.let { idx[it] } ?: -1, b.limb ?: Limb.BODY, toJ(pv.x, pv.y, pv.z), vec(b.json, "rotation"), list.toFloatArray())
        }
        return Model(out)
    }

    /** `Rz·Ry·Rx` (degrees) onto [m]. */
    fun rotate(m: Matrix4f, x: Float, y: Float, z: Float) {
        if (z != 0f) m.rotateZ(Math.toRadians(z.toDouble()).toFloat())
        if (y != 0f) m.rotateY(Math.toRadians(y.toDouble()).toFloat())
        if (x != 0f) m.rotateX(Math.toRadians(x.toDouble()).toFloat())
    }

    // ---- body parts ----

    /** The body part a bone's name says; with [plain] false only GeckoLib's armour names (`bipedHead`, `armorLeftLeg`). */
    private fun named(name: String, plain: Boolean): Limb? {
        val lower = name.lowercase()
        if (!plain && !lower.startsWith("biped") && !lower.startsWith("armor")) return null
        val n = lower.removePrefix("biped").removePrefix("armor").replace("_", "")
        return when (n) {
            "head", "helmet" -> Limb.HEAD
            "body", "chestplate", "torso" -> Limb.BODY
            "rightarm" -> Limb.RIGHT_ARM
            "leftarm" -> Limb.LEFT_ARM
            "rightleg", "rightboot", "rightlegging" -> Limb.RIGHT_LEG
            "leftleg", "leftboot", "leftlegging" -> Limb.LEFT_LEG
            else -> null
        }
    }

    private fun attached(bone: String): Limb? = when (bone.uppercase()) {
        "HEAD" -> Limb.HEAD
        "RIGHT_ARM" -> Limb.RIGHT_ARM
        "LEFT_ARM" -> Limb.LEFT_ARM
        "RIGHT_LEG" -> Limb.RIGHT_LEG
        "LEFT_LEG" -> Limb.LEFT_LEG
        "NONE", "" -> null
        else -> Limb.BODY
    }

    private fun assignLimbs(bones: Map<String, Bone>, attachedBone: String) {
        val fixed = attached(attachedBone)
        // A pet or hat names its own head and legs: only armour names count there.
        val plain = fixed == null
        fun own(b: Bone): Limb? {
            b.limb?.let { return it }
            val l = named(b.name, plain) ?: b.parent?.let { bones[it] }?.let { own(it) }
            b.limb = l
            return l
        }
        for (b in bones.values) own(b)
        if (fixed != null) {
            // A head/arm/leg cosmetic: everything rides on that part unless a bone names another.
            for (b in bones.values) if (b.limb == null) b.limb = fixed
            return
        }
        // Each top-level bone without a part, with everything under it, goes where its cubes are.
        val children = bones.values.groupBy { it.parent }
        fun subtree(b: Bone, into: MutableList<Bone>) {
            into += b
            for (c in children[b.name].orEmpty()) subtree(c, into)
        }
        for (top in bones.values.filter { it.parent == null || it.parent !in bones }) {
            val all = ArrayList<Bone>().also { subtree(top, it) }.filter { it.limb == null }
            if (all.isEmpty()) continue
            val centre = centroid(all) ?: continue
            val limb = byPosition(centre.x, centre.y)
            for (b in all) b.limb = limb
        }
    }

    /** Where a point (file pixels: x, y up) is on a player standing at the origin. */
    fun byPosition(x: Float, y: Float): Limb = when {
        y >= 24f -> Limb.HEAD
        y >= 12f -> when {
            x < -4f -> Limb.RIGHT_ARM
            x > 4f -> Limb.LEFT_ARM
            else -> Limb.BODY
        }
        else -> if (x < 0f) Limb.RIGHT_LEG else Limb.LEFT_LEG
    }

    /** The middle of [bones]' cubes in file pixels (volume-weighted, flat cubes counted too), or null with none. */
    private fun centroid(bones: List<Bone>): Vector3f? {
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var sw = 0.0
        for (b in bones) for (c in b.json.getAsJsonArray("cubes") ?: continue) {
            val o = c.asJsonObject
            val origin = vec(o, "origin")
            val size = vec(o, "size")
            val w = maxOf(0.01, (maxOf(size.x, 0.1f) * maxOf(size.y, 0.1f) * maxOf(size.z, 0.1f)).toDouble())
            sx += (origin.x + size.x / 2) * w
            sy += (origin.y + size.y / 2) * w
            sz += (origin.z + size.z / 2) * w
            sw += w
        }
        if (sw == 0.0) return null
        return Vector3f((sx / sw).toFloat(), (sy / sw).toFloat(), (sz / sw).toFloat())
    }

    // ---- geometry ----

    private fun vec(o: JsonObject, key: String): Vector3f {
        val a = o.get(key) as? JsonArray ?: return Vector3f()
        return Vector3f(a[0].asFloat, a[1].asFloat, a[2].asFloat)
    }

    /** A file point (pixels, y up, x mirrored) in the player model's space. */
    private fun toJ(x: Float, y: Float, z: Float) = Vector3f(x, 24f - y, z)

    /** `T(p)·Rz·Ry·Rx·T(−p)` in J for a file pivot [pivot] and rotation [rot] (degrees). */
    private fun rotationAbout(pivot: Vector3f, rot: Vector3f): Matrix4f {
        if (rot.x == 0f && rot.y == 0f && rot.z == 0f) return Matrix4f()
        val p = toJ(pivot.x, pivot.y, pivot.z)
        return Matrix4f().translate(p)
            .rotateZ(Math.toRadians(rot.z.toDouble()).toFloat())
            .rotateY(Math.toRadians(rot.y.toDouble()).toFloat())
            .rotateX(Math.toRadians(rot.x.toDouble()).toFloat())
            .translate(-p.x, -p.y, -p.z)
    }

    private val FACES = listOf("north", "south", "east", "west", "up", "down")

    private fun cube(o: JsonObject, bone: Matrix4f, texW: Float, texH: Float, out: MutableList<Float>) {
        val origin = vec(o, "origin")
        val size = vec(o, "size")
        val inflate = o.get("inflate")?.asFloat ?: 0f
        val m = Matrix4f(bone).mul(rotationAbout(if (o.has("pivot")) vec(o, "pivot") else Vector3f(), vec(o, "rotation")))
        // W space (x negated), inflated.
        val x1 = -(origin.x + size.x) - inflate
        val x2 = -origin.x + inflate
        val y1 = origin.y - inflate
        val y2 = origin.y + size.y + inflate
        val z1 = origin.z - inflate
        val z2 = origin.z + size.z + inflate
        val uv = faceUvs(o.get("uv"), size, o.get("mirror")?.asBoolean == true) ?: return
        for (face in FACES) {
            val f = uv[face] ?: continue
            if (f[2] == 0f && f[3] == 0f) continue
            // Corners top-left, top-right, bottom-right, bottom-left as the face is seen from outside.
            val corners = when (face) {
                "north" -> arrayOf(floatArrayOf(x2, y2, z1), floatArrayOf(x1, y2, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x2, y1, z1))
                "south" -> arrayOf(floatArrayOf(x1, y2, z2), floatArrayOf(x2, y2, z2), floatArrayOf(x2, y1, z2), floatArrayOf(x1, y1, z2))
                "east" -> arrayOf(floatArrayOf(x2, y2, z2), floatArrayOf(x2, y2, z1), floatArrayOf(x2, y1, z1), floatArrayOf(x2, y1, z2))
                "west" -> arrayOf(floatArrayOf(x1, y2, z1), floatArrayOf(x1, y2, z2), floatArrayOf(x1, y1, z2), floatArrayOf(x1, y1, z1))
                "up" -> arrayOf(floatArrayOf(x1, y2, z1), floatArrayOf(x2, y2, z1), floatArrayOf(x2, y2, z2), floatArrayOf(x1, y2, z2))
                else -> arrayOf(floatArrayOf(x1, y1, z2), floatArrayOf(x2, y1, z2), floatArrayOf(x2, y1, z1), floatArrayOf(x1, y1, z1))
            }
            val u0 = f[0] / texW
            val v0 = f[1] / texH
            val u1 = (f[0] + f[2]) / texW
            val v1 = (f[1] + f[3]) / texH
            val us = floatArrayOf(u0, u1, u1, u0)
            val vs = floatArrayOf(v0, v0, v1, v1)
            val pts = Array(4) { i ->
                val c = corners[i]
                // W -> J: (−x, 24 − y, z); then the bone's and cube's rotations (in J).
                m.transformPosition(Vector3f(-c[0], 24f - c[1], c[2]))
            }
            val normal = Vector3f(pts[1]).sub(pts[0]).cross(Vector3f(pts[3]).sub(pts[0]))
            // The corners go clockwise seen from outside (W -> J is a turn, not a mirror), so this points inward.
            if (normal.lengthSquared() > 0f) normal.normalize().negate() else normal.set(0f, -1f, 0f)
            for (i in 0 until 4) {
                out += pts[i].x; out += pts[i].y; out += pts[i].z; out += us[i]; out += vs[i]
            }
            out += normal.x; out += normal.y; out += normal.z
        }
    }

    /**
     * Each face's `[u, v, width, height]` in texture pixels: per-face UVs as written, or Bedrock box UV (the Java skin
     * layout) from `[u, v]` and the cube's size. Null when the cube has no UV.
     */
    private fun faceUvs(el: JsonElement?, size: Vector3f, mirror: Boolean): Map<String, FloatArray>? {
        if (el == null) return null
        if (el is JsonObject) {
            val out = HashMap<String, FloatArray>()
            for (face in FACES) {
                val f = el.getAsJsonObject(face) ?: continue
                val uv = f.getAsJsonArray("uv") ?: continue
                val s = f.getAsJsonArray("uv_size")
                out[face] = floatArrayOf(uv[0].asFloat, uv[1].asFloat, s?.get(0)?.asFloat ?: 0f, s?.get(1)?.asFloat ?: 0f)
            }
            return out
        }
        val a = el as? JsonArray ?: return null
        val u = a[0].asFloat
        val v = a[1].asFloat
        val w = size.x
        val h = size.y
        val d = size.z
        val faces = hashMapOf(
            "east" to floatArrayOf(u, v + d, d, h),
            "north" to floatArrayOf(u + d, v + d, w, h),
            "west" to floatArrayOf(u + d + w, v + d, d, h),
            "south" to floatArrayOf(u + 2 * d + w, v + d, w, h),
            "up" to floatArrayOf(u + d + w, v + d, -w, -d),
            "down" to floatArrayOf(u + d + 2 * w, v, -w, d),
        )
        if (mirror) {
            // Mirrored box UV: left and right swap, and every face is flipped across.
            val east = faces["east"]!!
            faces["east"] = faces["west"]!!
            faces["west"] = east
            for (f in faces.values) {
                f[0] += f[2]
                f[2] = -f[2]
            }
        }
        return faces
    }
}
