package io.embodit.airsketch.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Plain mesh arrays (positions xyz, normals xyz, uv0, triangle indices); converted to an SDK MeshModel in the scene. */
class MeshData(val positions: FloatArray, val normals: FloatArray, val uvs: FloatArray, val indices: IntArray) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
}

/** Growable mesh arrays. */
class MeshBuilder(cap: Int = 1024) {
    private var pos = FloatArray(cap * 3)
    private var nor = FloatArray(cap * 3)
    private var uv = FloatArray(cap * 2)
    private var idx = IntArray(cap * 3)
    var vertices = 0
        private set
    var indexCount = 0
        private set

    fun vertex(p: Vec3, n: Vec3, u: Float, v: Float): Int {
        if ((vertices + 1) * 3 > pos.size) {
            pos = pos.copyOf(pos.size * 2)
            nor = nor.copyOf(nor.size * 2)
            uv = uv.copyOf(uv.size * 2)
        }
        val i = vertices
        pos[i * 3] = p.x; pos[i * 3 + 1] = p.y; pos[i * 3 + 2] = p.z
        nor[i * 3] = n.x; nor[i * 3 + 1] = n.y; nor[i * 3 + 2] = n.z
        uv[i * 2] = u; uv[i * 2 + 1] = v
        vertices++
        return i
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[indexCount++] = a
        idx[indexCount++] = b
        idx[indexCount++] = c
    }

    fun build(): MeshData = MeshData(pos.copyOf(vertices * 3), nor.copyOf(vertices * 3), uv.copyOf(vertices * 2), idx.copyOf(indexCount))
}

/**
 * Stroke geometry (design brushes[]): a 6-sided tube for ink / neon / sketch, a flat double-sided band for ribbon
 * (width = diameter × 2.5, facing the volume front at the moment the stroke began). Colour is a uv0 column of the
 * brush's 12 × 1 palette texture, so every stroke of one brush can share one batched mesh.
 */
object StrokeMesher {
    const val SIDES = 6
    private const val RIBBON_WIDTH = 2.5f

    /** Vertices [s] adds to a batch (both copies when mirrored). */
    fun vertexCost(s: StrokeData): Int = vertexCost(s.brush, s.count) * if (s.mirrored) 2 else 1

    fun vertexCost(brush: Brush, n: Int): Int = if (brush == Brush.RIBBON) n * 2 else n * SIDES + 2

    fun append(out: MeshBuilder, s: StrokeData) {
        append(out, s.pts, 0, s.count, s.brush, s.color, s.faceYawDeg, false, true)
        if (s.mirrored) append(out, s.pts, 0, s.count, s.brush, s.color, s.faceYawDeg, true, true)
    }

    fun mesh(s: StrokeData): MeshData = MeshBuilder(vertexCost(s) + 8).also { append(it, s) }.build()

    /**
     * Appends points [from, to) of [pts] (x, y, z, r). [mirror] negates x. [caps] closes the tube ends
     * (the live tail leaves its start open where it joins the body).
     */
    fun append(out: MeshBuilder, pts: FloatArray, from: Int, to: Int, brush: Brush, color: Int, faceYawDeg: Float, mirror: Boolean, caps: Boolean) {
        val n = to - from
        if (n < 2) return
        val sx = if (mirror) -1f else 1f
        fun p(i: Int) = Vec3(pts[(from + i) * 4] * sx, pts[(from + i) * 4 + 1], pts[(from + i) * 4 + 2])
        fun r(i: Int) = pts[(from + i) * 4 + 3]
        val u = Palette.u(color)
        val tangents = Array(n) { i ->
            val a = p(maxOf(0, i - 1))
            val b = p(minOf(n - 1, i + 1))
            (b - a).normalized()
        }
        if (brush == Brush.RIBBON) {
            val yaw = faceYawDeg * (PI.toFloat() / 180f)
            val face = Vec3(-sin(yaw) * sx, 0f, cos(yaw))
            var side = Vec3(1f, 0f, 0f)
            val base = out.vertices
            for (i in 0 until n) {
                val t = tangents[i]
                val c = t.cross(face)
                if (c.length > 0.15f) side = c.normalized()
                val nrm = side.cross(t).normalized().let { if (it.length < 0.5f) face else it }
                val half = r(i) * RIBBON_WIDTH
                out.vertex(p(i) + side * half, nrm, u, 0.5f)
                out.vertex(p(i) - side * half, nrm, u, 0.5f)
            }
            for (i in 0 until n - 1) {
                val a = base + i * 2
                out.tri(a, a + 2, a + 1)
                out.tri(a + 1, a + 2, a + 3)
            }
            return
        }
        // tube with parallel-transported frames
        var normal = anyPerpendicular(tangents[0])
        val base = out.vertices
        for (i in 0 until n) {
            val t = tangents[i]
            val proj = normal - t * normal.dot(t)
            normal = if (proj.length > 1e-4f) proj.normalized() else anyPerpendicular(t)
            val bin = t.cross(normal)
            val c = p(i)
            val rad = r(i)
            for (k in 0 until SIDES) {
                val a = 2 * PI.toFloat() * k / SIDES
                val dir = normal * cos(a) + bin * sin(a)
                out.vertex(c + dir * rad, dir, u, 0.5f)
            }
        }
        for (i in 0 until n - 1) {
            for (k in 0 until SIDES) {
                val a = base + i * SIDES + k
                val b = base + i * SIDES + (k + 1) % SIDES
                val c = a + SIDES
                val d = b + SIDES
                if (mirror) {
                    out.tri(a, b, c); out.tri(b, d, c)
                } else {
                    out.tri(a, c, b); out.tri(b, c, d)
                }
            }
        }
        if (caps) {
            val s0 = out.vertex(p(0), -tangents[0], u, 0.5f)
            val s1 = out.vertex(p(n - 1), tangents[n - 1], u, 0.5f)
            val last = base + (n - 1) * SIDES
            for (k in 0 until SIDES) {
                out.tri(s0, base + (k + 1) % SIDES, base + k)
                out.tri(s1, last + k, last + (k + 1) % SIDES)
            }
        }
    }

    private fun anyPerpendicular(t: Vec3): Vec3 {
        val h = if (abs(t.y) < 0.9f) Vec3.UP else Vec3.RIGHT
        return t.cross(h).normalized()
    }
}

/** Axis-aligned bounds of a mesh, centre and half extent (for the SDK BoundingBox). */
fun boundsOf(m: MeshData): Pair<Vec3, Vec3> {
    if (m.vertexCount == 0) return Vec3.ZERO to Vec3(0.001f, 0.001f, 0.001f)
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    val p = m.positions
    for (i in 0 until m.vertexCount) {
        val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
        if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
        if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
    }
    val c = Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2)
    val h = Vec3(maxOf(0.001f, (maxX - minX) / 2), maxOf(0.001f, (maxY - minY) / 2), maxOf(0.001f, (maxZ - minZ) / 2))
    return c to h
}

/** Length of a polyline of (x, y, z, r) points. */
fun polylineLength(pts: FloatArray): Float {
    var s = 0f
    for (i in 1 until pts.size / 4) {
        val dx = pts[i * 4] - pts[(i - 1) * 4]; val dy = pts[i * 4 + 1] - pts[(i - 1) * 4 + 1]; val dz = pts[i * 4 + 2] - pts[(i - 1) * 4 + 2]
        s += sqrt(dx * dx + dy * dy + dz * dz)
    }
    return s
}
