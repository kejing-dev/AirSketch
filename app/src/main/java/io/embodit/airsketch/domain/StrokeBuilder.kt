package io.embodit.airsketch.domain

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The draw zone (design spatialLayout.drawZone), in volume metres: a cylinder about the base axis. It is
 * symmetric about that axis, so the same test holds in the base-local ("art") frame.
 */
object Zone {
    const val R = 0.19f
    const val Y0 = 0.11f
    const val Y1 = 0.58f
    const val BASE_TOP = 0.10f
    const val BASE_R = 0.20f
    val NIB_HOME = Vec3(0f, 0.34f, 0.10f)

    /** [p] pulled back onto the zone, and whether it had to be. */
    fun clamp(p: Vec3): Pair<Vec3, Boolean> {
        var x = p.x
        var z = p.z
        val y = p.y.coerceIn(Y0, Y1)
        var hit = y != p.y
        val r = sqrt(x * x + z * z)
        if (r > R) {
            x *= R / r
            z *= R / r
            hit = true
        }
        return Vec3(x, y, z) to hit
    }

    fun inside(p: Vec3, eps: Float = 1e-4f): Boolean = p.y >= Y0 - eps && p.y <= Y1 + eps && p.x * p.x + p.z * p.z <= (R + eps) * (R + eps)

    /** Volume → art frame for a base turned by [yaw] radians about +Y. */
    fun toArt(v: Vec3, yaw: Float): Vec3 {
        val c = cos(yaw)
        val s = sin(yaw)
        // inverse of the yaw rotation
        return Vec3(c * v.x - s * v.z, v.y, s * v.x + c * v.z)
    }

    fun toVolume(a: Vec3, yaw: Float): Vec3 {
        val c = cos(yaw)
        val s = sin(yaw)
        return Vec3(c * a.x + s * a.z, a.y, -s * a.x + c * a.z)
    }
}

/**
 * Turns nib positions into stroke points (design strokeRules): a point every ≥ 3 mm, exponential smoothing
 * α = 0.5, nothing recorded while the nib sits on the zone boundary, Catmull-Rom resampling to 4 mm on release.
 * Sketch (taper) radius follows speed: slow = ×1.4, fast = ×0.5, both ends taper over 1 cm.
 */
class StrokeBuilder(
    val brush: Brush,
    val size: Int,
    val color: Int,
    val mirrored: Boolean,
    val faceYawDeg: Float,
    /** Points this stroke may still use before the sketch is full. */
    private val budget: Int = Int.MAX_VALUE,
) {
    companion object {
        const val STEP = 0.003f
        const val RESAMPLE = 0.004f
        const val ALPHA = 0.5f
        const val MIN_POINTS = 3
        const val SLOW = 0.05f
        const val FAST = 0.50f
        const val TAPER_END = 0.01f
    }

    private var pts = FloatArray(256)
    var count = 0
        private set
    private var smooth: Vec3? = null
    private var lastAt = 0L
    private var speedK = 1f
    var paused = false
        private set
    var full = false
        private set

    private val base = Sizes.radius(size)
    private val pointCost = if (mirrored) 2 else 1

    fun point(i: Int) = Vec3(pts[i * 4], pts[i * 4 + 1], pts[i * 4 + 2])

    fun radius(i: Int) = pts[i * 4 + 3]

    fun last(): Vec3? = if (count == 0) null else point(count - 1)

    fun raw(): FloatArray = pts.copyOf(count * 4)

    fun start(p: Vec3, atMs: Long) {
        smooth = p
        lastAt = atMs
        push(p, radiusFor(1f))
    }

    /**
     * Feeds the nib position [p] (art frame). [onBoundary]: the nib is held on the zone edge, so this event is
     * not recorded (the stroke pauses). Returns true when a point was added.
     */
    fun feed(p: Vec3, atMs: Long, onBoundary: Boolean): Boolean {
        if (full) return false
        paused = onBoundary
        if (onBoundary) return false
        val s = smooth?.let { it + (p - it) * ALPHA } ?: p
        smooth = s
        val prev = last() ?: run {
            push(s, radiusFor(1f))
            return true
        }
        val d = s.distanceTo(prev)
        if (d < STEP) return false
        val dt = max(1L, atMs - lastAt) / 1000f
        lastAt = atMs
        val v = d / dt
        val t = ((v - SLOW) / (FAST - SLOW)).coerceIn(0f, 1f)
        val k = 1.4f + (0.5f - 1.4f) * t
        speedK += (k - speedK) * 0.5f
        push(s, radiusFor(speedK))
        return true
    }

    private fun radiusFor(k: Float): Float = if (brush == Brush.TAPER) base * k else base

    private fun push(p: Vec3, r: Float) {
        if ((count + 1) * pointCost > budget) {
            full = true
            return
        }
        if ((count + 1) * 4 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[count * 4] = p.x
        pts[count * 4 + 1] = p.y
        pts[count * 4 + 2] = p.z
        pts[count * 4 + 3] = r
        count++
    }

    /** The finished stroke, or null when it is too short to keep (fewer than 3 points, design minStroke). */
    fun finish(): StrokeData? {
        if (count < MIN_POINTS) return null
        var out = resample(raw(), RESAMPLE)
        if ((out.size / 4) * pointCost > budget) out = raw()
        if (brush == Brush.TAPER) taperEnds(out, TAPER_END)
        return StrokeData(brush, size, color, mirrored, faceYawDeg, out)
    }
}

/** Catmull-Rom through the points of [src] (x, y, z, r), re-spaced every [step] metres along the curve. */
fun resample(src: FloatArray, step: Float): FloatArray {
    val n = src.size / 4
    if (n < 2) return src.copyOf()
    fun p(i: Int, k: Int) = src[i.coerceIn(0, n - 1) * 4 + k]
    // dense polyline
    val sub = 8
    val dense = ArrayList<FloatArray>(n * sub)
    for (i in 0 until n - 1) {
        for (j in 0 until sub) {
            val t = j / sub.toFloat()
            val t2 = t * t
            val t3 = t2 * t
            val v = FloatArray(4)
            for (k in 0 until 3) {
                val p0 = p(i - 1, k); val p1 = p(i, k); val p2 = p(i + 1, k); val p3 = p(i + 2, k)
                v[k] = 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
            }
            v[3] = p(i, 3) + (p(i + 1, 3) - p(i, 3)) * t
            dense += v
        }
    }
    dense += floatArrayOf(p(n - 1, 0), p(n - 1, 1), p(n - 1, 2), p(n - 1, 3))
    val out = ArrayList<Float>()
    fun put(v: FloatArray) { out += v[0]; out += v[1]; out += v[2]; out += v[3] }
    put(dense[0])
    var carry = 0f
    for (i in 1 until dense.size) {
        val a = dense[i - 1]
        val b = dense[i]
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len <= 0f) continue
        var s = step - carry
        while (s <= len) {
            val t = s / len
            put(floatArrayOf(a[0] + dx * t, a[1] + dy * t, a[2] + dz * t, a[3] + (b[3] - a[3]) * t))
            s += step
        }
        carry = len - (s - step)
    }
    // always end exactly at the last point
    val last = dense.last()
    val m = out.size / 4
    val lx = out[(m - 1) * 4]; val ly = out[(m - 1) * 4 + 1]; val lz = out[(m - 1) * 4 + 2]
    val gap = sqrt((last[0] - lx) * (last[0] - lx) + (last[1] - ly) * (last[1] - ly) + (last[2] - lz) * (last[2] - lz))
    if (gap > step * 0.25f) put(last) else { out[(m - 1) * 4] = last[0]; out[(m - 1) * 4 + 1] = last[1]; out[(m - 1) * 4 + 2] = last[2] }
    return out.toFloatArray()
}

/** Shrinks the radius to a point over [len] metres at both ends (in place). */
fun taperEnds(pts: FloatArray, len: Float) {
    val n = pts.size / 4
    if (n < 2) return
    val acc = FloatArray(n)
    for (i in 1 until n) {
        val dx = pts[i * 4] - pts[(i - 1) * 4]; val dy = pts[i * 4 + 1] - pts[(i - 1) * 4 + 1]; val dz = pts[i * 4 + 2] - pts[(i - 1) * 4 + 2]
        acc[i] = acc[i - 1] + sqrt(dx * dx + dy * dy + dz * dz)
    }
    val total = acc[n - 1]
    for (i in 0 until n) {
        val e = min(acc[i], total - acc[i])
        val k = (e / len).coerceIn(0.15f, 1f)
        pts[i * 4 + 3] *= k
    }
}
