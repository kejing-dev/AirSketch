package io.embodit.airsketch.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The three sample works (design samples[]), generated in code with the same stroke format a user stroke is saved
 * in (the design's "generator script" lives here, so the samples always match the current mesher). Ported point
 * for point from the sample page's mkSamples().
 */
object Samples {
    val IDS = listOf("spring", "tree", "orbit")
    private const val PI_F = PI.toFloat()

    fun work(id: String): Work {
        val strokes = when (id) {
            "tree" -> tree()
            "orbit" -> orbit()
            else -> spring()
        }
        return Work(id = "sample:$id", nameIndex = 0, createdAt = 0L, updatedAt = 0L, strokes = strokes.toMutableList(), sample = id)
    }

    private class P(val x: Float, val y: Float, val z: Float, val r: Float)

    private fun stroke(brush: Brush, color: Int, mirrored: Boolean, faceRad: Float, pts: List<P>): StrokeData {
        val raw = FloatArray(pts.size * 4)
        pts.forEachIndexed { i, p -> raw[i * 4] = p.x; raw[i * 4 + 1] = p.y; raw[i * 4 + 2] = p.z; raw[i * 4 + 3] = p.r }
        val size = when {
            pts[0].r <= 0.002f -> 0
            pts[0].r <= 0.0035f -> 1
            else -> 2
        }
        return StrokeData(brush, size, color, mirrored, faceRad * 180f / PI_F, resample(raw, StrokeBuilder.RESAMPLE))
    }

    private fun spring(): List<StrokeData> {
        val pts = (0..220).map { i ->
            val t = i / 220f
            val a = t * PI_F * 6
            P(cos(a) * .09f, .13f + t * .42f, sin(a) * .09f, .003f)
        }
        return listOf(stroke(Brush.NEON, 6, false, 0f, pts))
    }

    private fun tree(): List<StrokeData> {
        val st = ArrayList<StrokeData>()
        st += stroke(Brush.TAPER, 11, false, 0f, (0..40).map { i -> val t = i / 40f; P(sin(t * 2.2f) * .012f, .11f + t * .24f, 0f, .006f * (1.25f - t * .6f)) })
        for (b in listOf(floatArrayOf(.20f, .07f, .30f, .02f), floatArrayOf(.25f, .06f, .37f, -.03f), floatArrayOf(.30f, .05f, .43f, .02f))) {
            st += stroke(Brush.TAPER, 11, true, 0f, (0..16).map { i -> val t = i / 16f; P(t * b[1], b[0] + t * (b[2] - b[0]), t * b[3], .004f * (1 - t * .5f)) })
        }
        for (c in listOf(floatArrayOf(.07f, .31f, .02f, 5f), floatArrayOf(.06f, .38f, -.03f, 10f), floatArrayOf(.05f, .44f, .02f, 5f), floatArrayOf(0f, .47f, 0f, 5f), floatArrayOf(.03f, .50f, .03f, 10f))) {
            val pts = (0..30).map { i -> val a = i / 30f * PI_F * 2; P(c[0] + cos(a) * .028f, c[1] + sin(a) * .028f, c[2] + sin(a * 2) * .01f, .003f) }
            st += stroke(Brush.INK, if (c[3] == 5f) 5 else 4, c[0] != 0f, 0f, pts)
        }
        return st
    }

    private fun orbit(): List<StrokeData> {
        val st = ArrayList<StrokeData>()
        for ((rot, color) in listOf(0f to 8, PI_F / 3 to 9, 2 * PI_F / 3 to 10)) {
            val pts = (0..72).map { i ->
                val a = i / 72f * PI_F * 2
                val x = cos(a) * .13f
                val y = sin(a) * .13f * .5f
                val z = sin(a) * .13f * .866f
                P(x * cos(rot) + z * sin(rot), .34f + y, -x * sin(rot) + z * cos(rot), .004f)
            }
            st += stroke(Brush.RIBBON, color, false, rot, pts)
        }
        st += stroke(Brush.NEON, 4, false, 0f, (0..10).map { i -> val a = i / 10f * PI_F * 2 + PI_F / 2; val rr = if (i % 2 == 1) .014f else .035f; P(cos(a) * rr, .34f + sin(a) * rr, 0f, .003f) })
        return st
    }

    /** Debug trajectories for `stroke helix|zigzag|circle` (volume metres, every 2 mm), fed through the drag pipeline. */
    fun path(name: String): List<Vec3> {
        val out = ArrayList<Vec3>()
        when (name) {
            "zigzag" -> {
                val corners = (0..6).map { i -> Vec3(-0.14f + i * 0.28f / 6, if (i % 2 == 0) 0.26f else 0.40f, 0.05f) }
                for (i in 1 until corners.size) dense(out, corners[i - 1], corners[i])
            }
            "circle" -> for (i in 0..315) { val a = i / 315f * 2 * PI_F; out += Vec3(sin(a) * 0.10f, 0.34f + cos(a) * 0.10f, 0.06f) }
            "line" -> dense(out, Vec3(-0.12f, 0.30f, 0.06f), Vec3(0.12f, 0.30f, 0.06f))
            else -> for (i in 0..520) { val t = i / 520f; val a = t * 4 * PI_F; out += Vec3(cos(a) * 0.08f, 0.20f + t * 0.26f, sin(a) * 0.08f) }
        }
        return out
    }

    private fun dense(out: MutableList<Vec3>, a: Vec3, b: Vec3) {
        val n = maxOf(1, (a.distanceTo(b) / 0.002f).toInt())
        for (i in 0..n) out += a.lerp(b, i / n.toFloat())
    }
}
