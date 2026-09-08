package com.puzzlesolver.core.surface

import com.puzzlesolver.core.math.Vec2
import com.puzzlesolver.core.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The wall the puzzle is painted on, as a *developable* surface.
 *
 * This is the load-bearing idea of the whole app. A wall that curves only in the
 * horizontal direction -- a vertical extrusion of some horizontal profile curve --
 * has zero Gaussian curvature. Such a surface can be flattened onto a plane with
 * no stretching whatsoever. So the puzzle, which is intrinsically 2D, maps to a
 * flat canvas *exactly*: distances along the wall equal distances on the canvas.
 *
 * That buys us three things:
 *   1. The rectified canvas is metrically correct, so grid detection can assume
 *      straight lines and constant cell pitch.
 *   2. Frames captured from wildly different viewpoints compose into one mosaic
 *      without seams from projective mismatch.
 *   3. The solution overlay is computed once in canvas space and re-projected to
 *      whatever the camera pose happens to be, instead of being recomputed per frame.
 *
 * Coordinates: `u` is arc length along the wall (metres, increasing left-to-right
 * as seen from the room), `v` is height (metres, world Y). Both are world-anchored,
 * so they stay stable for the whole session.
 */
sealed interface WallSurface {

    /** World-space point for a canvas coordinate. */
    fun uvToWorld(u: Float, v: Float): Vec3

    /** Outward normal (pointing into the room, toward the viewer) at a canvas coordinate. */
    fun normalAt(u: Float): Vec3

    /** Canvas coordinate for a world point, by orthogonal projection onto the surface. */
    fun worldToUv(p: Vec3): Vec2

    /** Signed distance from the surface; positive on the room side. */
    fun signedDistance(p: Vec3): Float

    /**
     * Intersects a ray with the wall, choosing the hit whose normal faces the ray
     * origin. Returns canvas coordinates, or null if the ray misses or only hits
     * the back face.
     */
    fun rayIntersect(origin: Vec3, dir: Vec3): Vec2?

    /** Curvature magnitude, 1/metres. Zero for a flat wall. Drives mesh tessellation. */
    val curvature: Float

    // ---------------------------------------------------------------------

    /**
     * Degenerate case: a flat wall. Kept as its own implementation rather than a
     * cylinder with an enormous radius, because the enormous-radius cylinder loses
     * float precision badly -- u = R * theta with R around 1e6 is all cancellation.
     */
    data class Planar(
        /** Any point on the wall. */
        val origin: Vec3,
        /** Unit horizontal vector along the wall, direction of increasing u. */
        val tangent: Vec3,
        /** Unit outward normal, pointing into the room. */
        val normal: Vec3,
        val up: Vec3 = Vec3.UP,
    ) : WallSurface {

        override val curvature: Float get() = 0f

        override fun uvToWorld(u: Float, v: Float): Vec3 =
            origin + tangent * u + up * v

        override fun normalAt(u: Float): Vec3 = normal

        override fun worldToUv(p: Vec3): Vec2 {
            val d = p - origin
            return Vec2(d dot tangent, d dot up)
        }

        override fun signedDistance(p: Vec3): Float = (p - origin) dot normal

        override fun rayIntersect(origin: Vec3, dir: Vec3): Vec2? {
            val denom = dir dot normal
            if (denom > -1e-6f) return null            // parallel, or hitting the back face
            val t = ((this.origin - origin) dot normal) / denom
            if (t <= 0f) return null
            return worldToUv(origin + dir * t)
        }
    }

    /**
     * A vertical circular cylinder section -- the shape an ordinary curved wall
     * actually has.
     *
     * @param axis     a point the vertical axis passes through (its Y is ignored)
     * @param radius   always positive
     * @param concave  true when the room is *inside* the curve, the common case for
     *                 a curved feature wall you stand in front of; false for a
     *                 column or a convex bulge
     * @param thetaRef the angle mapped to u = 0, chosen so the scanned region sits
     *                 near the origin and keeps float precision
     */
    data class Cylindrical(
        val axis: Vec3,
        val radius: Float,
        val concave: Boolean,
        val thetaRef: Float,
        val up: Vec3 = Vec3.UP,
    ) : WallSurface {

        override val curvature: Float get() = 1f / radius

        private fun thetaOf(u: Float) = thetaRef + u / radius

        override fun uvToWorld(u: Float, v: Float): Vec3 {
            val t = thetaOf(u)
            return Vec3(axis.x + radius * cos(t), v, axis.z + radius * sin(t))
        }

        override fun normalAt(u: Float): Vec3 {
            val t = thetaOf(u)
            // Away from the axis for a convex wall; toward the axis -- which is toward
            // the room -- for a concave one.
            val s = if (concave) -1f else 1f
            return Vec3(s * cos(t), 0f, s * sin(t))
        }

        override fun worldToUv(p: Vec3): Vec2 {
            val dx = p.x - axis.x
            val dz = p.z - axis.z
            var dTheta = (atan2(dz, dx) - thetaRef).toDouble()
            // Keep the branch cut behind the wall rather than through it.
            while (dTheta > Math.PI) dTheta -= 2.0 * Math.PI
            while (dTheta < -Math.PI) dTheta += 2.0 * Math.PI
            return Vec2((radius * dTheta).toFloat(), p.y)
        }

        override fun signedDistance(p: Vec3): Float {
            val dx = p.x - axis.x
            val dz = p.z - axis.z
            val r = sqrt(dx * dx + dz * dz)
            return if (concave) radius - r else r - radius
        }

        override fun rayIntersect(origin: Vec3, dir: Vec3): Vec2? {
            // Intersect the infinite vertical cylinder: a quadratic in the XZ plane.
            val ox = origin.x - axis.x
            val oz = origin.z - axis.z
            val a = dir.x * dir.x + dir.z * dir.z
            if (a < 1e-12f) return null                // ray is vertical; never hits a wall
            val b = 2f * (ox * dir.x + oz * dir.z)
            val c = ox * ox + oz * oz - radius * radius
            val disc = b * b - 4f * a * c
            if (disc < 0f) return null
            val sq = sqrt(disc)
            val t0 = (-b - sq) / (2f * a)
            val t1 = (-b + sq) / (2f * a)

            // Take the nearest forward hit whose surface normal faces us. For a
            // concave wall seen from inside that is the far root; for a convex one
            // the near root. Testing the normal handles both without special-casing
            // where the camera happens to be standing.
            var best = Float.MAX_VALUE
            for (t in floatArrayOf(t0, t1)) {
                if (t <= 1e-4f || t >= best) continue
                val hit = origin + dir * t
                val u = worldToUv(hit).x
                if ((dir dot normalAt(u)) < 0f) best = t
            }
            if (best == Float.MAX_VALUE) return null
            return worldToUv(origin + dir * best)
        }
    }
}
