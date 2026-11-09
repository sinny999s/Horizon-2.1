package dev.horizon.client.lunar

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.abs

/**
 * Blockbuster's BOBJ format, which Lunar's emotes use (they began as McHorse's Emoticons): an OBJ
 * with an armature (`arm_bone name parent tail.xyz matrix[16]`, row-major, rest pose), meshes bound to it (`o`, `o_arm`,
 * `v` then its `vw bone weight` lines, `vt`, `vn`, `f`), and actions (`an name`, `ao bone`, `ag location|rotation|scale
 * index`, `kf frame value CONSTANT|LINEAR|BEZIER [left.x left.y right.x right.y]`, frames in ticks). Skinned as Emoticons
 * does: each bone's pose is its rest matrix relative to its parent's, then the action's translation, rotation (Z·Y·X,
 * radians) and scale, under its parent's pose; a vertex moves by its weights' bones' pose × inverse rest. Units are
 * blocks, y up, the feet at the origin. Game-free.
 */
object Bobj {
    class Bone(val name: String, val parent: String?, val rest: Matrix4f) {
        var parentIndex = -1
        val relative = Matrix4f()
        val inverse = Matrix4f()
    }

    /** A mesh: per vertex its position, up to [MAX_WEIGHTS] (bone index, weight), and its triangles' corners. */
    class Mesh(val name: String, val positions: FloatArray, val boneIdx: IntArray, val weights: FloatArray, val corners: IntArray,
               val uvs: FloatArray, val normals: FloatArray) {
        /** Each triangle corner: vertex index, uv (u, v), normal (x, y, z): see [corners]. */
        val triangles get() = corners.size / 3
    }

    /** One channel: keyframes as (frame, value, interpolation, left x, left y, right x, right y). */
    class Channel(val frames: FloatArray, val values: FloatArray, val interp: ByteArray, val handles: FloatArray) {
        fun at(frame: Float): Float {
            val n = frames.size
            if (n == 0) return 0f
            if (frame <= frames[0]) return values[0]
            if (frame >= frames[n - 1]) return values[n - 1]
            var i = 1
            while (frames[i] < frame) i++
            val a = i - 1
            return when (interp[a].toInt()) {
                CONSTANT -> values[a]
                LINEAR -> values[a] + (values[i] - values[a]) * (frame - frames[a]) / (frames[i] - frames[a])
                else -> bezier(frames[a], values[a], handles[a * 4 + 2], handles[a * 4 + 3], handles[i * 4], handles[i * 4 + 1], frames[i], values[i], frame)
            }
        }
    }

    /** An action: per bone name, its nine channels (location, rotation, scale × x, y, z; null when not keyed). */
    class Action(val name: String, val bones: Map<String, Array<Channel?>>) {
        val length: Float = bones.values.maxOfOrNull { cs -> cs.maxOfOrNull { c -> c?.frames?.lastOrNull() ?: 0f } ?: 0f } ?: 0f
    }

    class File(val bones: List<Bone>, val meshes: List<Mesh>, val actions: Map<String, Action>)

    const val MAX_WEIGHTS = 4
    const val CONSTANT = 0
    const val LINEAR = 1
    const val BEZIER = 2

    fun parse(text: String, bonesFrom: List<Bone>? = null): File {
        val bones = ArrayList<Bone>()
        val meshes = ArrayList<Mesh>()
        val actions = HashMap<String, Action>()
        // OBJ indices run on across a file's objects.
        val v = ArrayList<Float>()
        val vw = ArrayList<MutableList<Pair<String, Float>>>()
        val vt = ArrayList<Float>()
        val vn = ArrayList<Float>()
        var mesh: String? = null
        var faces = ArrayList<IntArray>()
        val meshFaces = LinkedHashMap<String, ArrayList<IntArray>>()
        var action: HashMap<String, Array<Channel?>>? = null
        var actionName = ""
        var group: Array<Channel?>? = null
        var channelSlot = -1
        val kf = ArrayList<FloatArray>()
        val kfInterp = ArrayList<Byte>()
        fun closeChannel() {
            val g = group ?: return
            if (channelSlot < 0 || kf.isEmpty()) return
            g[channelSlot] = Channel(FloatArray(kf.size) { kf[it][0] }, FloatArray(kf.size) { kf[it][1] }, kfInterp.toByteArray(),
                FloatArray(kf.size * 4) { kf[it / 4][2 + it % 4] })
            kf.clear()
            kfInterp.clear()
            channelSlot = -1
        }
        fun closeAction() {
            closeChannel()
            action?.let { actions[actionName] = Action(actionName, it) }
            action = null
            group = null
        }
        for (raw in text.lineSequence()) {
            if (raw.isEmpty() || raw[0] == '#') continue
            val p = raw.trim().split(' ')
            when (p[0]) {
                "arm_bone" -> {
                    // "arm_bone name parent tail.x tail.y tail.z m00 m01 ... m33": a root has an empty parent.
                    val rest = raw.trim().substring(9)
                    val name = rest.substringBefore(' ')
                    var tail = rest.substring(name.length + 1)
                    val parent: String?
                    if (tail.startsWith(' ')) {
                        parent = null
                        tail = tail.substring(1)
                    } else {
                        parent = tail.substringBefore(' ')
                        tail = tail.substring(parent.length + 1)
                    }
                    val n = tail.trim().split(' ').map { it.toFloat() }
                    val m = Matrix4f()
                    // Row-major after the tail's three numbers.
                    m.set(n[3], n[7], n[11], n[15], n[4], n[8], n[12], n[16], n[5], n[9], n[13], n[17], n[6], n[10], n[14], n[18])
                    bones += Bone(name, parent, m)
                }
                "o" -> {
                    mesh = p[1]
                    faces = meshFaces.getOrPut(p[1]) { ArrayList() }
                }
                "v" -> {
                    v += p[1].toFloat(); v += p[2].toFloat(); v += p[3].toFloat()
                    vw.add(ArrayList())
                }
                "vw" -> vw.lastOrNull()?.add(p[1] to p[2].toFloat())
                "vt" -> { vt += p[1].toFloat(); vt += p.getOrNull(2)?.toFloat() ?: 0f }
                "vn" -> { vn += p[1].toFloat(); vn += p[2].toFloat(); vn += p[3].toFloat() }
                "f" -> if (mesh != null) {
                    val c = p.drop(1).map { s -> s.split('/').map { it.toIntOrNull()?.minus(1) ?: -1 } }
                    // Fan into triangles: (v, vt, vn) per corner.
                    for (k in 1 until c.size - 1) for (corner in listOf(c[0], c[k], c[k + 1]))
                        faces += intArrayOf(corner[0], corner.getOrElse(1) { -1 }, corner.getOrElse(2) { -1 })
                }
                "an" -> {
                    closeAction()
                    actionName = p[1]
                    action = HashMap()
                }
                "ao" -> {
                    closeChannel()
                    group = arrayOfNulls<Channel>(9).also { action?.put(p[1], it) }
                }
                "ag" -> {
                    closeChannel()
                    val base = when (p[1]) {
                        "location" -> 0
                        "rotation" -> 3
                        "scale" -> 6
                        else -> -1
                    }
                    val idx = p.getOrNull(2)?.toIntOrNull() ?: 0
                    channelSlot = if (base < 0 || idx !in 0..2) -1 else base + idx
                }
                "kf" -> if (channelSlot >= 0) {
                    kf += floatArrayOf(p[1].toFloat(), p[2].toFloat(), p.getOrNull(4)?.toFloatOrNull() ?: 0f, p.getOrNull(5)?.toFloatOrNull() ?: 0f,
                        p.getOrNull(6)?.toFloatOrNull() ?: 0f, p.getOrNull(7)?.toFloatOrNull() ?: 0f)
                    kfInterp += when (p.getOrNull(3)) {
                        "CONSTANT" -> CONSTANT
                        "LINEAR" -> LINEAR
                        else -> BEZIER
                    }.toByte()
                }
            }
        }
        closeAction()
        val skeleton = bonesFrom ?: bones
        val index = skeleton.withIndex().associate { (i, b) -> b.name to i }
        for ((name, fs) in meshFaces) {
            if (fs.isEmpty()) continue
            // Each mesh gets its own vertex list (the vertices its faces use).
            val remap = HashMap<Int, Int>()
            val pos = ArrayList<Float>()
            val bi = ArrayList<Int>()
            val bw = ArrayList<Float>()
            val corners = IntArray(fs.size)
            val uvs = FloatArray(fs.size * 2)
            val normals = FloatArray(fs.size * 3)
            for ((k, f) in fs.withIndex()) {
                val local = remap.getOrPut(f[0]) {
                    val i = pos.size / 3
                    pos += v[f[0] * 3]; pos += v[f[0] * 3 + 1]; pos += v[f[0] * 3 + 2]
                    val w = vw[f[0]].filter { it.second > 0f && it.first in index }.sortedByDescending { it.second }.take(MAX_WEIGHTS)
                    val sum = w.sumOf { it.second.toDouble() }.toFloat()
                    for (j in 0 until MAX_WEIGHTS) {
                        val e = w.getOrNull(j)
                        bi += e?.let { index[it.first] } ?: 0
                        bw += if (e != null && sum > 0f) e.second / sum else 0f
                    }
                    i
                }
                corners[k] = local
                if (f[1] >= 0) {
                    uvs[k * 2] = vt[f[1] * 2]
                    uvs[k * 2 + 1] = 1f - vt[f[1] * 2 + 1]
                }
                if (f[2] >= 0) {
                    normals[k * 3] = vn[f[2] * 3]; normals[k * 3 + 1] = vn[f[2] * 3 + 1]; normals[k * 3 + 2] = vn[f[2] * 3 + 2]
                }
            }
            meshes += Mesh(name, pos.toFloatArray(), bi.toIntArray(), bw.toFloatArray(), corners, uvs, normals)
        }
        if (bonesFrom == null) setUp(bones)
        return File(bones, meshes, actions)
    }

    /** Parent indices, rest matrices relative to the parent, and inverse rest matrices. */
    fun setUp(bones: List<Bone>) {
        val index = bones.withIndex().associate { (i, b) -> b.name to i }
        for (b in bones) {
            b.parentIndex = b.parent?.let { index[it] } ?: -1
            b.inverse.set(b.rest).invert()
            if (b.parentIndex >= 0) b.relative.set(bones[b.parentIndex].rest).invert().mul(b.rest) else b.relative.set(b.rest)
        }
    }

    /** Bone poses for [action] at [frame] (null: rest) into [skin] (pose × inverse rest per bone); [world] is scratch. */
    fun pose(bones: List<Bone>, action: Action?, frame: Float, world: Array<Matrix4f>, skin: Array<Matrix4f>) {
        for ((i, b) in bones.withIndex()) {
            val m = world[i]
            if (b.parentIndex >= 0) m.set(world[b.parentIndex]).mul(b.relative) else m.set(b.relative)
            val c = action?.bones?.get(b.name)
            if (c != null) {
                m.translate(c[0]?.at(frame) ?: 0f, c[1]?.at(frame) ?: 0f, c[2]?.at(frame) ?: 0f)
                c[5]?.at(frame)?.let { if (it != 0f) m.rotateZ(it) }
                c[4]?.at(frame)?.let { if (it != 0f) m.rotateY(it) }
                c[3]?.at(frame)?.let { if (it != 0f) m.rotateX(it) }
                m.scale(c[6]?.at(frame) ?: 1f, c[7]?.at(frame) ?: 1f, c[8]?.at(frame) ?: 1f)
            }
            skin[i].set(m).mul(b.inverse)
        }
    }

    /**
     * [mesh] skinned by [skin] as quads (a triangle repeats its last corner), [GeoModel.QUAD_FLOATS] each, into [out]
     * (resized as needed); returns the floats written.
     */
    fun skin(mesh: Mesh, skin: Array<Matrix4f>, out: FloatArray): Int {
        val n = mesh.positions.size / 3
        val moved = FloatArray(n * 3)
        val t = Vector3f()
        for (i in 0 until n) {
            var x = 0f
            var y = 0f
            var z = 0f
            var total = 0f
            for (j in 0 until MAX_WEIGHTS) {
                val w = mesh.weights[i * MAX_WEIGHTS + j]
                if (w <= 0f) continue
                skin[mesh.boneIdx[i * MAX_WEIGHTS + j]].transformPosition(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2], t)
                x += t.x * w; y += t.y * w; z += t.z * w
                total += w
            }
            if (total <= 0f) {
                x = mesh.positions[i * 3]; y = mesh.positions[i * 3 + 1]; z = mesh.positions[i * 3 + 2]
            }
            moved[i * 3] = x; moved[i * 3 + 1] = y; moved[i * 3 + 2] = z
        }
        var w = 0
        val a = Vector3f()
        val b = Vector3f()
        for (tri in 0 until mesh.triangles) {
            val k = tri * 3
            val i0 = mesh.corners[k]; val i1 = mesh.corners[k + 1]; val i2 = mesh.corners[k + 2]
            a.set(moved[i1 * 3] - moved[i0 * 3], moved[i1 * 3 + 1] - moved[i0 * 3 + 1], moved[i1 * 3 + 2] - moved[i0 * 3 + 2])
            b.set(moved[i2 * 3] - moved[i0 * 3], moved[i2 * 3 + 1] - moved[i0 * 3 + 1], moved[i2 * 3 + 2] - moved[i0 * 3 + 2])
            a.cross(b)
            if (a.lengthSquared() > 0f) a.normalize()
            for (c in intArrayOf(0, 1, 2, 2)) {
                val vi = mesh.corners[k + c]
                out[w++] = moved[vi * 3]; out[w++] = moved[vi * 3 + 1]; out[w++] = moved[vi * 3 + 2]
                out[w++] = mesh.uvs[(k + c) * 2]; out[w++] = mesh.uvs[(k + c) * 2 + 1]
            }
            out[w++] = a.x; out[w++] = a.y; out[w++] = a.z
        }
        return w
    }

    /** Floats [skin] writes for [mesh]. */
    fun size(mesh: Mesh) = mesh.triangles * GeoModel.QUAD_FLOATS

    /** A Blender F-curve Bézier segment's value at [x]: P0 (x0, y0), handles P1, P2, P3 (x3, y3). */
    fun bezier(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x: Float): Float {
        // Clamp the handles inside the segment (as Blender does) so x(t) is monotonic, then find t by bisection.
        val h1 = x1.coerceIn(x0, x3)
        val h2 = x2.coerceIn(x0, x3)
        var lo = 0f
        var hi = 1f
        var t = 0.5f
        repeat(24) {
            t = (lo + hi) / 2
            val xt = cubic(x0, h1, h2, x3, t)
            if (abs(xt - x) < 1e-4f) return cubic(y0, y1, y2, y3, t)
            if (xt < x) lo = t else hi = t
        }
        return cubic(y0, y1, y2, y3, t)
    }

    private fun cubic(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val u = 1 - t
        return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d
    }
}
