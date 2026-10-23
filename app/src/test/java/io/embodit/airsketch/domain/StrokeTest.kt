package io.embodit.airsketch.domain

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeTest {
    private fun builder(brush: Brush = Brush.INK, mirrored: Boolean = false, budget: Int = Int.MAX_VALUE) =
        StrokeBuilder(brush, 1, Palette.SKY, mirrored, 0f, budget)

    private fun drawLine(b: StrokeBuilder, from: Vec3, to: Vec3, steps: Int, msPerStep: Long = 16) {
        b.start(from, 0)
        for (i in 1..steps) b.feed(from.lerp(to, i / steps.toFloat()), i * msPerStep, false)
    }

    @Test
    fun shortDragIsNotKept() {
        val b = builder()
        drawLine(b, Vec3(0f, 0.3f, 0f), Vec3(0.005f, 0.3f, 0f), 5)
        assertNull("a < 9 mm drag is not a stroke (design minStroke)", b.finish())
    }

    @Test
    fun pointsAreAtLeastThreeMillimetresApartAndResampledToFour() {
        val b = builder()
        drawLine(b, Vec3(-0.1f, 0.3f, 0f), Vec3(0.1f, 0.3f, 0f), 200)
        for (i in 1 until b.count) assertTrue(b.point(i).distanceTo(b.point(i - 1)) >= StrokeBuilder.STEP - 1e-5f)
        val s = assertNotNullAnd(b.finish())
        for (i in 1 until s.count - 1) assertEquals(0.004f, s.point(i).distanceTo(s.point(i - 1)), 0.0006f)
    }

    @Test
    fun boundaryEventsAreNotRecorded() {
        val b = builder()
        b.start(Vec3(0f, 0.3f, 0f), 0)
        b.feed(Vec3(0.01f, 0.3f, 0f), 16, false)
        val n = b.count
        b.feed(Vec3(0.05f, 0.3f, 0f), 32, onBoundary = true)
        assertEquals(n, b.count)
        assertTrue(b.paused)
    }

    @Test
    fun budgetStopsTheStrokeAndMirroredPointsCountTwice() {
        val b = builder(mirrored = true, budget = 20)
        drawLine(b, Vec3(-0.1f, 0.3f, 0f), Vec3(0.1f, 0.3f, 0f), 200)
        assertTrue(b.full)
        assertEquals(10, b.count)
        val s = assertNotNullAnd(b.finish())
        assertTrue(s.cost <= 20)
    }

    @Test
    fun taperIsThickerWhenSlowAndPointedAtBothEnds() {
        val slow = builder(Brush.TAPER).also { drawLine(it, Vec3(-0.05f, 0.3f, 0f), Vec3(0.05f, 0.3f, 0f), 100, msPerStep = 40) }
        val fast = builder(Brush.TAPER).also { drawLine(it, Vec3(-0.05f, 0.3f, 0f), Vec3(0.05f, 0.3f, 0f), 20, msPerStep = 4) }
        val mid = { b: StrokeBuilder -> b.radius(b.count / 2) }
        assertTrue("slow ${mid(slow)} > fast ${mid(fast)}", mid(slow) > mid(fast))
        val s = assertNotNullAnd(slow.finish())
        assertTrue(s.radius(0) < s.radius(s.count / 2))
        assertTrue(s.radius(s.count - 1) < s.radius(s.count / 2))
    }

    @Test
    fun zoneClampsToTheCylinder() {
        val (p, hit) = Zone.clamp(Vec3(0.5f, 0.9f, 0f))
        assertTrue(hit)
        assertEquals(Zone.R, p.x, 1e-5f)
        assertEquals(Zone.Y1, p.y, 1e-5f)
        assertFalse(Zone.clamp(Zone.NIB_HOME).second)
    }

    @Test
    fun artFrameRoundTrips() {
        val v = Vec3(0.1f, 0.3f, -0.05f)
        for (deg in listOf(0f, 37f, 90f, 215f)) {
            val yaw = deg * PI.toFloat() / 180f
            val back = Zone.toVolume(Zone.toArt(v, yaw), yaw)
            assertTrue(back.distanceTo(v) < 1e-5f)
        }
        // a quarter turn about +Y brings the front (+Z) of the base to +X
        val front = Zone.toVolume(Vec3(0f, 0f, 1f), PI.toFloat() / 2)
        assertEquals(1f, front.x, 1e-5f)
    }

    @Test
    fun meshCostsMatchTheBuilder() {
        val pts = FloatArray(40) { if (it % 4 == 3) 0.003f else (it / 4) * 0.004f }
        for (brush in Brush.entries) for (mirror in listOf(false, true)) {
            val s = StrokeData(brush, 1, 3, mirror, 0f, pts)
            val m = StrokeMesher.mesh(s)
            assertEquals("${brush.id} mirror=$mirror", StrokeMesher.vertexCost(s), m.vertexCount)
            assertEquals(0, m.indices.size % 3)
            assertTrue(m.indices.all { it in 0 until m.vertexCount })
            assertTrue("uv picks colour 3", m.uvs.filterIndexed { i, _ -> i % 2 == 0 }.all { abs(it - Palette.u(3)) < 1e-6f })
        }
    }

    @Test
    fun workJsonRoundTripsInMillimetres() {
        val s = StrokeData(Brush.RIBBON, 2, 9, true, 90f, floatArrayOf(0.0101f, 0.2f, -0.03f, 0.006f, 0.02f, 0.21f, -0.03f, 0.006f))
        val w = Work("abc", 3, 1L, 2L, yawDeg = 45f, mirror = true, brush = 2, size = 2, color = 9, display = true, strokes = mutableListOf(s))
        val back = Work.parse(w.toJson())
        assertEquals("abc", back.id)
        assertEquals(45f, back.yawDeg, 1e-4f)
        assertTrue(back.display)
        val t = back.strokes.single()
        assertEquals(Brush.RIBBON, t.brush)
        assertTrue(t.mirrored)
        assertEquals(0.010f, t.pts[0], 1e-6f)
        assertEquals(0.006f, t.radius(1), 1e-6f)
    }

    @Test
    fun samplesStayInsideTheZone() {
        for (id in Samples.IDS) for (s in Samples.work(id).strokes) for (i in 0 until s.count) {
            val p = s.point(i)
            assertTrue("$id $i $p", Zone.inside(p, 0.002f) && Zone.inside(Vec3(-p.x, p.y, p.z), 0.002f))
        }
    }

    @Test
    fun debugPathsStayInsideTheZone() {
        for (name in listOf("helix", "zigzag", "circle", "line")) for (p in Samples.path(name)) assertTrue("$name $p", Zone.inside(p))
    }

    @Test
    fun fullAtThreeHundredStrokesOrSixteenThousandPoints() {
        val w = Work("w", 1, 0, 0)
        val small = StrokeData(Brush.INK, 0, 0, false, 0f, FloatArray(8))
        repeat(Limits.MAX_STROKES - 1) { w.strokes += small }
        assertFalse(Limits.full(w))
        w.strokes += small
        assertTrue(Limits.full(w))
    }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
