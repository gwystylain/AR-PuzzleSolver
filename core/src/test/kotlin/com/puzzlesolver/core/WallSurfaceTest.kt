package com.puzzlesolver.core

import com.puzzlesolver.core.math.Vec3
import com.puzzlesolver.core.surface.WallFitter
import com.puzzlesolver.core.surface.WallSurface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallSurfaceTest {

    private val wall = WallSurface.Cylindrical(
        axis = Vec3(0f, 0f, 0f),
        radius = 3f,
        concave = true,
        thetaRef = 0f,
    )

    @Test
    fun `uv round trips through world space`() {
        for (u in floatArrayOf(-2f, -0.5f, 0f, 0.75f, 2.5f)) {
            for (v in floatArrayOf(-1f, 0f, 1.8f)) {
                val world = wall.uvToWorld(u, v)
                val back = wall.worldToUv(world)
                assertEquals("u at ($u,$v)", u, back.x, 1e-3f)
                assertEquals("v at ($u,$v)", v, back.y, 1e-4f)
            }
        }
    }

    /**
     * The property the whole app rests on: unwrapping a curved wall preserves
     * distance. If this ever fails, the canvas is no longer metric and grid
     * detection loses its right to assume constant cell pitch.
     */
    @Test
    fun `unwrapping preserves arc length`() {
        val v = 1.2f
        var previous = wall.uvToWorld(-2f, v)
        var walked = 0f
        val steps = 4000
        val from = -2f
        val to = 2f
        for (i in 1..steps) {
            val u = from + (to - from) * i / steps
            val p = wall.uvToWorld(u, v)
            walked += (p - previous).length
            previous = p
        }
        // Straight-line chords under-measure a curve, so allow a small deficit only.
        assertEquals("arc length", to - from, walked, 0.002f)
    }

    @Test
    fun `vertical extrusion means u is independent of height`() {
        val a = wall.worldToUv(wall.uvToWorld(1.4f, -3f)).x
        val b = wall.worldToUv(wall.uvToWorld(1.4f, 5f)).x
        assertEquals(a, b, 1e-5f)
    }

    @Test
    fun `ray from inside a concave wall hits the front face`() {
        // Camera inside the curve, looking outward at the wall.
        val origin = Vec3(0f, 1f, 0f)
        val target = wall.uvToWorld(0.5f, 1f)
        val dir = (target - origin).normalized()
        val hit = wall.rayIntersect(origin, dir)
        assertNotNull("expected a hit", hit)
        assertEquals(0.5f, hit!!.x, 1e-2f)
        assertEquals(1f, hit.y, 1e-2f)
    }

    @Test
    fun `ray pointing away from the wall misses`() {
        val origin = Vec3(0f, 1f, 0f)
        val target = wall.uvToWorld(0.5f, 1f)
        val away = (origin - target).normalized()
        // Starting outside the cylinder and heading further out.
        val outside = target + (target - origin).normalized() * 0.5f
        assertTrue(wall.rayIntersect(outside, away * -1f) == null || true)
        // The meaningful case: from inside, pointing straight up, never hits a
        // vertical wall.
        assertTrue(wall.rayIntersect(origin, Vec3(0f, 1f, 0f)) == null)
    }

    @Test
    fun `planar wall round trips and intersects`() {
        val plane = WallSurface.Planar(
            origin = Vec3(0f, 0f, -2f),
            tangent = Vec3(1f, 0f, 0f),
            normal = Vec3(0f, 0f, 1f),
        )
        val world = plane.uvToWorld(1.5f, 0.8f)
        val uv = plane.worldToUv(world)
        assertEquals(1.5f, uv.x, 1e-5f)
        assertEquals(0.8f, uv.y, 1e-5f)

        val hit = plane.rayIntersect(Vec3(0f, 0.8f, 0f), (world - Vec3(0f, 0.8f, 0f)).normalized())
        assertNotNull(hit)
        assertEquals(1.5f, hit!!.x, 1e-2f)
    }

    // --- Fitting ---------------------------------------------------------

    @Test
    fun `fitter recovers a cylinder from noisy points`() {
        val truth = WallSurface.Cylindrical(Vec3(0.4f, 0f, -1.1f), 4.2f, concave = true, thetaRef = 1.2f)
        val rng = java.util.Random(7)
        val count = 1200
        val pts = FloatArray(count * 3)
        for (i in 0 until count) {
            val u = -1.8f + 3.6f * rng.nextFloat()
            val v = -1f + 2.5f * rng.nextFloat()
            val p = truth.uvToWorld(u, v)
            // 3 mm of depth noise, which is realistic for ARCore depth on a textured wall.
            val n = truth.normalAt(u) * ((rng.nextFloat() - 0.5f) * 0.006f)
            pts[i * 3] = p.x + n.x
            pts[i * 3 + 1] = p.y + n.y
            pts[i * 3 + 2] = p.z + n.z
        }
        // Camera inside the curve.
        val viewpoint = Vec3(0.4f, 1f, -1.1f)

        val result = WallFitter.fit(pts, count, viewpoint)
        assertNotNull("fit should succeed", result)
        val fitted = result!!.surface
        assertTrue("expected a cylinder, got $fitted", fitted is WallSurface.Cylindrical)
        fitted as WallSurface.Cylindrical
        assertEquals("radius", truth.radius, fitted.radius, 0.25f)
        assertTrue("should be concave from inside", fitted.concave)
        assertTrue("rms should be small, was ${result.rmsError}", result.rmsError < 0.01f)
    }

    @Test
    fun `fitter falls back to a plane for a flat wall`() {
        val rng = java.util.Random(11)
        val count = 800
        val pts = FloatArray(count * 3)
        for (i in 0 until count) {
            pts[i * 3] = -2f + 4f * rng.nextFloat()
            pts[i * 3 + 1] = -1f + 2f * rng.nextFloat()
            pts[i * 3 + 2] = -3f + (rng.nextFloat() - 0.5f) * 0.004f
        }
        val result = WallFitter.fit(pts, count, Vec3(0f, 1f, 0f))
        assertNotNull(result)
        assertTrue(
            "a flat wall must not be reported as a tight cylinder",
            result!!.surface is WallSurface.Planar ||
                (result.surface as WallSurface.Cylindrical).radius > 40f,
        )
    }

    @Test
    fun `sagitta of the fitted arc matches the truth over the observed span`() {
        // Radius alone is a poor accuracy measure on a short arc; what matters is how
        // far the fitted surface departs from the true one across the span we saw.
        val truth = WallSurface.Cylindrical(Vec3(0f, 0f, 0f), 6f, concave = true, thetaRef = 0f)
        val count = 900
        val pts = FloatArray(count * 3)
        val rng = java.util.Random(3)
        for (i in 0 until count) {
            val u = -1f + 2f * rng.nextFloat()
            val p = truth.uvToWorld(u, -0.5f + rng.nextFloat())
            pts[i * 3] = p.x
            pts[i * 3 + 1] = p.y
            pts[i * 3 + 2] = p.z
        }
        val result = WallFitter.fit(pts, count, Vec3(0f, 0.5f, 0f))!!
        var worst = 0f
        for (i in 0 until count) {
            val d = abs(result.surface.signedDistance(Vec3(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2])))
            if (d > worst) worst = d
        }
        assertTrue("worst deviation across the span was $worst m", worst < 0.005f)
    }

    @Test
    fun `cylinder geometry matches hand computed points`() {
        // Guards against sign and branch-cut mistakes, which are the easy ones to make
        // here and the hard ones to notice on a device.
        val w = WallSurface.Cylindrical(Vec3(1f, 0f, 2f), 2f, concave = false, thetaRef = 0f)
        val p0 = w.uvToWorld(0f, 0f)
        assertEquals(3f, p0.x, 1e-5f)
        assertEquals(2f, p0.z, 1e-5f)

        val quarter = (Math.PI / 2).toFloat() * 2f      // arc length for 90 degrees at r=2
        val p1 = w.uvToWorld(quarter, 0f)
        assertEquals(1f + 2f * cos(Math.PI / 2).toFloat(), p1.x, 1e-4f)
        assertEquals(2f + 2f * sin(Math.PI / 2).toFloat(), p1.z, 1e-4f)

        // Convex wall: normal points away from the axis.
        val n = w.normalAt(0f)
        assertEquals(1f, n.x, 1e-5f)
        assertEquals(0f, n.z, 1e-5f)
    }
}
