package com.puzzlesolver.core.surface

import com.puzzlesolver.core.math.Vec3
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fits a [WallSurface] to a point cloud -- ARCore depth points or feature points
 * that we believe lie on the wall.
 *
 * Because the wall is a vertical extrusion, we can collapse every point onto the
 * horizontal XZ plane and fit a *2D circle* there. A circle fit is closed form
 * (Kasa), so RANSAC over it costs microseconds and can keep re-running to refine
 * the estimate while the user is still panning.
 */
object WallFitter {

    data class Config(
        /** Inlier band half-width, metres. Painted walls are flat to a few mm. */
        val inlierTolerance: Float = 0.02f,
        val ransacIterations: Int = 400,
        val minInliers: Int = 40,
        /**
         * Above this radius we stop believing the curvature and call the wall flat.
         * A 60 m radius over a 3 m span sags by 1.9 cm, at the edge of our tolerance,
         * and fitting it invites numerical nonsense.
         */
        val maxMeaningfulRadius: Float = 60f,
        val seed: Long = 0x5EED,
    )

    data class Result(
        val surface: WallSurface,
        val inlierCount: Int,
        val rmsError: Float,
    )

    /**
     * @param points    flat xyz triples
     * @param count     number of points (points.size must be at least 3 * count)
     * @param viewpoint a point known to be in the room, normally the camera. Decides
     *                  whether the wall is concave or convex and which way the
     *                  normal faces.
     */
    fun fit(points: FloatArray, count: Int, viewpoint: Vec3, cfg: Config = Config()): Result? {
        if (count < cfg.minInliers) return null
        val rng = Random(cfg.seed)

        var bestInliers = 0
        var bestCx = 0f
        var bestCz = 0f
        var bestR = 0f
        var bestIsLine = false
        var bestNx = 0f
        var bestNz = 0f
        var bestD = 0f

        repeat(cfg.ransacIterations) {
            val i0 = rng.nextInt(count)
            val i1 = rng.nextInt(count)
            val i2 = rng.nextInt(count)
            if (i0 == i1 || i1 == i2 || i0 == i2) return@repeat

            val ax = points[i0 * 3]
            val az = points[i0 * 3 + 2]
            val bx = points[i1 * 3]
            val bz = points[i1 * 3 + 2]
            val cx0 = points[i2 * 3]
            val cz0 = points[i2 * 3 + 2]

            val circ = circleThrough(ax, az, bx, bz, cx0, cz0)
            if (circ == null || circ[2] > cfg.maxMeaningfulRadius) {
                // Nearly collinear sample -- score it as a straight-wall hypothesis.
                var nx = -(cz0 - az)
                var nz = (cx0 - ax)
                val n = sqrt(nx * nx + nz * nz)
                if (n < 1e-6f) return@repeat
                nx /= n
                nz /= n
                val d = nx * ax + nz * az
                var inl = 0
                for (i in 0 until count) {
                    if (abs(nx * points[i * 3] + nz * points[i * 3 + 2] - d) <= cfg.inlierTolerance) inl++
                }
                if (inl > bestInliers) {
                    bestInliers = inl
                    bestIsLine = true
                    bestNx = nx
                    bestNz = nz
                    bestD = d
                }
            } else {
                val ccx = circ[0]
                val ccz = circ[1]
                val r = circ[2]
                var inl = 0
                for (i in 0 until count) {
                    val dx = points[i * 3] - ccx
                    val dz = points[i * 3 + 2] - ccz
                    if (abs(sqrt(dx * dx + dz * dz) - r) <= cfg.inlierTolerance) inl++
                }
                if (inl > bestInliers) {
                    bestInliers = inl
                    bestIsLine = false
                    bestCx = ccx
                    bestCz = ccz
                    bestR = r
                }
            }
        }

        if (bestInliers < cfg.minInliers) return null

        return if (bestIsLine) {
            refinePlane(points, count, bestNx, bestNz, bestD, cfg, viewpoint)
        } else {
            refineCylinder(points, count, bestCx, bestCz, bestR, cfg, viewpoint)
        }
    }

    /** Circumcircle of three 2D points as [cx, cz, r], or null when they are collinear. */
    private fun circleThrough(
        ax: Float, az: Float,
        bx: Float, bz: Float,
        cx: Float, cz: Float,
    ): FloatArray? {
        val d = 2f * (ax * (bz - cz) + bx * (cz - az) + cx * (az - bz))
        if (abs(d) < 1e-9f) return null
        val a2 = ax * ax + az * az
        val b2 = bx * bx + bz * bz
        val c2 = cx * cx + cz * cz
        val ux = (a2 * (bz - cz) + b2 * (cz - az) + c2 * (az - bz)) / d
        val uz = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d
        val r = sqrt((ax - ux) * (ax - ux) + (az - uz) * (az - uz))
        if (!r.isFinite() || r < 1e-4f) return null
        return floatArrayOf(ux, uz, r)
    }

    private fun refineCylinder(
        points: FloatArray,
        count: Int,
        cx0: Float,
        cz0: Float,
        r0: Float,
        cfg: Config,
        viewpoint: Vec3,
    ): Result {
        // Kasa least-squares circle fit over the inliers. Linear in (A, B, C) for
        // x^2 + z^2 + A x + B z + C = 0, so one pass with no iteration.
        var sx = 0.0
        var sz = 0.0
        var sxx = 0.0
        var szz = 0.0
        var sxz = 0.0
        var sxr = 0.0
        var szr = 0.0
        var sr = 0.0
        var n = 0
        for (i in 0 until count) {
            val x = points[i * 3].toDouble()
            val z = points[i * 3 + 2].toDouble()
            val dx = x - cx0
            val dz = z - cz0
            if (abs(sqrt(dx * dx + dz * dz) - r0) > cfg.inlierTolerance) continue
            val rr = x * x + z * z
            sx += x; sz += z
            sxx += x * x; szz += z * z; sxz += x * z
            sxr += x * rr; szr += z * rr; sr += rr
            n++
        }
        if (n < 3) return Result(cylinderFrom(cx0, cz0, r0, viewpoint), n, Float.NaN)

        val m = arrayOf(
            doubleArrayOf(sxx, sxz, sx, -sxr),
            doubleArrayOf(sxz, szz, sz, -szr),
            doubleArrayOf(sx, sz, n.toDouble(), -sr),
        )
        val sol = solve3x3(m) ?: return Result(cylinderFrom(cx0, cz0, r0, viewpoint), n, Float.NaN)
        val cx = (-sol[0] / 2.0).toFloat()
        val cz = (-sol[1] / 2.0).toFloat()
        val rSq = cx * cx + cz * cz - sol[2].toFloat()
        val r = if (rSq > 0f) sqrt(rSq) else r0

        if (!r.isFinite() || r > cfg.maxMeaningfulRadius) {
            // Curvature washed out under refinement. Fall back to a plane through the
            // same inliers so we keep the precision advantages of the flat case.
            val toCamera = atan2(viewpoint.z - cz, viewpoint.x - cx)
            val nx = cos(toCamera)
            val nz = sin(toCamera)
            return refinePlane(points, count, nx, nz, nx * cx + nz * cz - r, cfg, viewpoint)
        }

        var err = 0.0
        for (i in 0 until count) {
            val dx = points[i * 3] - cx
            val dz = points[i * 3 + 2] - cz
            val e = (sqrt(dx * dx + dz * dz) - r).toDouble()
            if (abs(e) <= cfg.inlierTolerance) err += e * e
        }
        return Result(cylinderFrom(cx, cz, r, viewpoint), n, sqrt(err / n).toFloat())
    }

    private fun cylinderFrom(cx: Float, cz: Float, r: Float, viewpoint: Vec3): WallSurface.Cylindrical {
        val dx = viewpoint.x - cx
        val dz = viewpoint.z - cz
        val distToAxis = sqrt(dx * dx + dz * dz)
        // Camera nearer the axis than the wall is => we are standing inside the curve.
        val concave = distToAxis < r
        // Anchor u = 0 at the wall point nearest the camera.
        val thetaRef = atan2(dz, dx)
        return WallSurface.Cylindrical(Vec3(cx, 0f, cz), r, concave, thetaRef)
    }

    private fun refinePlane(
        points: FloatArray,
        count: Int,
        nx0: Float,
        nz0: Float,
        d0: Float,
        cfg: Config,
        viewpoint: Vec3,
    ): Result {
        // Total least squares: the wall direction is the principal axis of the XZ
        // scatter, the normal is perpendicular to it.
        var sx = 0.0
        var sz = 0.0
        var n = 0
        for (i in 0 until count) {
            val x = points[i * 3]
            val z = points[i * 3 + 2]
            if (abs(nx0 * x + nz0 * z - d0) > cfg.inlierTolerance) continue
            sx += x; sz += z; n++
        }
        if (n < 2) return Result(
            WallSurface.Planar(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f)),
            n,
            Float.NaN,
        )
        val mx = sx / n
        val mz = sz / n
        var cxx = 0.0
        var czz = 0.0
        var cxz = 0.0
        for (i in 0 until count) {
            val x = points[i * 3]
            val z = points[i * 3 + 2]
            if (abs(nx0 * x + nz0 * z - d0) > cfg.inlierTolerance) continue
            val dx = x - mx
            val dz = z - mz
            cxx += dx * dx; czz += dz * dz; cxz += dx * dz
        }
        // Principal direction of a symmetric 2x2 matrix, closed form.
        val theta = 0.5 * atan2(2.0 * cxz, cxx - czz)
        var tx = cos(theta).toFloat()
        var tz = sin(theta).toFloat()
        var nx = -tz
        var nz = tx
        // Point the normal into the room.
        if ((viewpoint.x - mx) * nx + (viewpoint.z - mz) * nz < 0) {
            nx = -nx
            nz = -nz
        }
        // Keep (tangent, up, normal) right-handed so u increases consistently.
        if (tx * nz - tz * nx < 0f) {
            tx = -tx
            tz = -tz
        }

        var err = 0.0
        for (i in 0 until count) {
            val e = (nx * (points[i * 3] - mx) + nz * (points[i * 3 + 2] - mz)).toDouble()
            if (abs(e) <= cfg.inlierTolerance) err += e * e
        }
        val origin = Vec3(mx.toFloat(), 0f, mz.toFloat())
        return Result(
            WallSurface.Planar(origin, Vec3(tx, 0f, tz), Vec3(nx, 0f, nz)),
            n,
            sqrt(err / n).toFloat(),
        )
    }

    /** Gaussian elimination on a 3x4 augmented matrix. */
    private fun solve3x3(m: Array<DoubleArray>): DoubleArray? {
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val tmp = m[col]
            m[col] = m[piv]
            m[piv] = tmp
            for (r in 0 until 3) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                for (c in col until 4) m[r][c] -= f * m[col][c]
            }
        }
        return doubleArrayOf(m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2])
    }
}
