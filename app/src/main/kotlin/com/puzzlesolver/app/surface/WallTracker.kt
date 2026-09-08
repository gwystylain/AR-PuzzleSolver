package com.puzzlesolver.app.surface

import android.util.Log
import com.puzzlesolver.app.frame.FrameData
import com.puzzlesolver.app.frame.TrackingState
import com.puzzlesolver.core.math.Vec3
import com.puzzlesolver.core.surface.WallFitter
import com.puzzlesolver.core.surface.WallSurface
import kotlin.math.abs

/**
 * Maintains the wall estimate across a whole scan.
 *
 * A single frame sees a narrow slice of the wall, and a circle fitted to a narrow
 * arc is badly conditioned -- the radius can be out by a factor of several while
 * the residuals stay tiny. So points are pooled across frames in world space and
 * the fit is redone as the pool widens. The estimate typically starts as "flat",
 * becomes a large radius after the first metre of panning, and converges once the
 * user has swept enough arc to constrain it.
 *
 * The pool is voxel-decimated rather than capped by age. Old points from the far
 * end of the wall are the most valuable ones for pinning down curvature, so
 * throwing them away for being old would defeat the purpose.
 */
class WallTracker(
    private val config: Config = Config(),
) {
    data class Config(
        /** Voxel edge for pool decimation, metres. */
        val voxelSize: Float = 0.04f,
        val maxPoints: Int = 20_000,
        /** Refit once this many new voxels have been added. */
        val refitEveryNewVoxels: Int = 60,
        /**
         * Points further than this from the current estimate are treated as furniture,
         * people, or the floor, and excluded from later fits.
         */
        val outlierRejectDistance: Float = 0.12f,
        /**
         * The wall must be roughly vertical: reject points whose local neighbourhood
         * is dominated by a horizontal surface. Cheap proxy -- reject points below
         * this height relative to the camera, which removes the floor.
         */
        val minHeightBelowCamera: Float = -1.2f,
        /**
         * Inliers needed before the fit is trusted.
         *
         * Calibrated for *feature* points, not a depth point cloud. Devices without a
         * hardware depth sensor hand us tens of points per frame rather than thousands,
         * and the pool only grows as the user pans; demanding a depth-sensor-sized
         * number here means such a device never locks at all.
         */
        val minInliersForLock: Int = 60,
        /**
         * Fraction of pooled points that must lie on the fitted surface.
         *
         * This is the discriminating test, and the one that matters most. A count alone
         * cannot tell a wall from a cluttered desk: a circle can always be threaded
         * through scattered geometry with a small residual over the subset it happens to
         * pass near. Requiring most of what we have seen to lie on one surface is what
         * distinguishes "this is a wall" from "this is a room full of furniture".
         */
        val minInlierFraction: Float = 0.55f,
        /**
         * Minimum arc of wall observed before the fit is trusted.
         *
         * A circle fitted to a narrow arc is badly conditioned -- radius can be wrong by
         * a factor of several while residuals stay tiny -- so a good-looking fit over
         * 10 cm means nothing.
         */
        val minSpanMetres: Float = 0.35f,
    )

    /** How much of the wall the pooled points span, in metres of arc. */
    var observedSpanMetres: Float = 0f
        private set

    var surface: WallSurface? = null
        private set

    var lastFitRms: Float = Float.NaN
        private set

    var lastFitInliers: Int = 0
        private set

    /**
     * True once the fit is trustworthy enough to anchor the canvas. Until then the
     * accumulator stays idle, because warping frames onto a wrong surface produces
     * a mosaic that has to be thrown away.
     */
    val isConverged: Boolean
        get() = convergenceIssue == null

    /** Points currently in the decimated pool. */
    val pooledPoints: Int get() = poolCount

    /**
     * Why the fit is not yet trusted, or null when it is.
     *
     * Exists because "nothing is happening" is the least actionable failure a scanning
     * app can present. Every gate below is a real precondition, and each fails for a
     * different reason the user can actually do something about -- so say which.
     */
    val convergenceIssue: String?
        get() {
            if (forcedSurface != null) return null
            if (surface == null) return "no surface fitted yet"
            if (lastFitRms.isNaN()) return "fit produced no residual"
            if (lastFitInliers < config.minInliersForLock) {
                return "only $lastFitInliers inliers, need ${config.minInliersForLock} -- pan more"
            }
            val fraction = if (poolCount > 0) lastFitInliers.toFloat() / poolCount else 0f
            if (fraction < config.minInlierFraction) {
                return "only ${(fraction * 100).toInt()}% of points on one surface -- " +
                    "this looks like a cluttered scene, not a wall"
            }
            if (observedSpanMetres < config.minSpanMetres) {
                return "only ${"%.2f".format(observedSpanMetres)}m of wall seen, " +
                    "need ${config.minSpanMetres}m -- pan wider"
            }
            if (lastFitRms > config.outlierRejectDistance * 0.5f) {
                return "fit rms ${"%.3f".format(lastFitRms)}m too high -- surface is not smooth"
            }
            return null
        }

    /**
     * Debug override: when set, the fit is bypassed and this surface is used as-is.
     *
     * Exists because the geometry stage is the only part of the pipeline that needs a
     * physical wall, and it therefore blocks testing everything downstream of it. With
     * a synthetic wall asserted, the accumulator, grid detector, cell reader and solver
     * can all be exercised against a puzzle taped to any flat thing at hand.
     */
    private var forcedSurface: WallSurface? = null

    /** Flat xyz triples of the decimated pool. */
    private var pool = FloatArray(config.maxPoints * 3)
    private var poolCount = 0

    /** Occupied voxel keys, so we decimate without a per-point distance search. */
    private val voxels = HashSet<Long>()
    private var newVoxelsSinceFit = 0

    private var minU = Float.MAX_VALUE
    private var maxU = -Float.MAX_VALUE

    /**
     * Asserts a flat vertical wall [distanceMetres] ahead of the camera and stops
     * fitting. Pass a null pose to return to real fitting.
     */
    fun forcePlanarWall(pose: com.puzzlesolver.core.math.Pose?, distanceMetres: Float): Boolean {
        if (pose == null) {
            forcedSurface = null
            surface = null
            return true
        }
        // Face the camera squarely rather than assuming a vertical wall.
        //
        // Assuming vertical is what a real wall is, but this is an override used to test
        // the stages downstream of geometry -- and if the phone is tilted at all, a
        // vertical plane foreshortens the target and the canvas comes out anisotropically
        // squashed. A plane normal to the view axis, with axes taken from the camera's
        // own right and up, reproduces a head-on flat target undistorted at any tilt,
        // which is the whole point of the override.
        //
        // (tangent, up, normal) stays right-handed: for an OpenGL camera basis,
        // right x up = -forward = normal.
        val forward = pose.forward
        val origin = pose.translation + forward * distanceMetres
        val normal = -forward
        val plane = WallSurface.Planar(origin, pose.right, normal, pose.up)
        forcedSurface = plane
        surface = plane
        lastFitInliers = config.minInliersForLock
        lastFitRms = 0f
        observedSpanMetres = distanceMetres
        Log.i(TAG, "forced flat wall at ${distanceMetres}m: $plane")
        return true
    }

    val isForced: Boolean get() = forcedSurface != null

    fun reset() {
        forcedSurface = null
        poolCount = 0
        voxels.clear()
        newVoxelsSinceFit = 0
        surface = null
        lastFitRms = Float.NaN
        lastFitInliers = 0
        observedSpanMetres = 0f
        minU = Float.MAX_VALUE
        maxU = -Float.MAX_VALUE
    }

    /**
     * Folds a frame's points into the pool and refits when enough has changed.
     *
     * @return true when the surface estimate changed, which the accumulator uses to
     *         decide whether the existing mosaic is still valid.
     */
    fun update(frame: FrameData): Boolean {
        // A forced wall is fixed by definition; folding points in would only let the
        // real fit fight the override.
        if (forcedSurface != null) return false
        if (frame.trackingState != TrackingState.TRACKING) return false
        val pose = frame.pose ?: return false
        val camera = pose.translation

        val current = surface
        var added = 0
        for (i in 0 until frame.pointCount) {
            val x = frame.points[i * 3]
            val y = frame.points[i * 3 + 1]
            val z = frame.points[i * 3 + 2]

            // Cheap floor rejection.
            if (y - camera.y < config.minHeightBelowCamera) continue

            // Once we have an estimate, use it to keep the pool clean.
            if (current != null &&
                abs(current.signedDistance(Vec3(x, y, z))) > config.outlierRejectDistance
            ) {
                continue
            }

            if (poolCount >= config.maxPoints) {
                // Pool is full. Keep it, but stop growing -- at 20k points spread over
                // 4 cm voxels we already have far more than the fit needs.
                //
                // Tested before the occupancy set is touched, not after. Adding the key
                // first and breaking second left the set growing by an entry per frame
                // for the life of the session: a slow leak on the one structure whose
                // entire job is to keep this bounded, and invisible from outside because
                // the pool count it guards sits reassuringly at its cap throughout.
                break
            }
            val key = voxelKey(x, y, z)
            if (!voxels.add(key)) continue
            pool[poolCount * 3] = x
            pool[poolCount * 3 + 1] = y
            pool[poolCount * 3 + 2] = z
            poolCount++
            added++
            newVoxelsSinceFit++
        }

        if (poolCount < MIN_POINTS_TO_FIT) return false
        if (newVoxelsSinceFit < config.refitEveryNewVoxels && current != null) return false
        newVoxelsSinceFit = 0

        val result = WallFitter.fit(
            pool, poolCount, camera,
            WallFitter.Config(inlierTolerance = config.outlierRejectDistance * 0.4f),
        ) ?: return false

        val previous = surface
        surface = result.surface
        lastFitRms = result.rmsError
        lastFitInliers = result.inlierCount
        updateSpan(result.surface)

        val changed = previous == null || !isEquivalent(previous, result.surface)
        if (changed) {
            Log.i(
                TAG,
                "wall refit: ${describe(result.surface)} inliers=${result.inlierCount} " +
                    "rms=${"%.4f".format(result.rmsError)} span=${"%.2f".format(observedSpanMetres)}m",
            )
        }
        return changed
    }

    /**
     * How much wall we have actually seen, measured over inliers only.
     *
     * Outliers must be excluded or the number is nonsense. `worldToUv` wraps the
     * cylinder angle to +/-pi, so points scattered around the room map to u values
     * spanning the entire circumference: a garbage 20 m-radius fit will cheerfully
     * report 120 m of "wall", which then satisfies any span gate we put on it.
     */
    private fun updateSpan(s: WallSurface) {
        minU = Float.MAX_VALUE
        maxU = -Float.MAX_VALUE
        for (i in 0 until poolCount) {
            val p = Vec3(pool[i * 3], pool[i * 3 + 1], pool[i * 3 + 2])
            if (abs(s.signedDistance(p)) > config.outlierRejectDistance) continue
            val u = s.worldToUv(p).x
            if (u < minU) minU = u
            if (u > maxU) maxU = u
        }
        observedSpanMetres = if (maxU > minU) maxU - minU else 0f
    }

    /**
     * Whether two estimates are close enough that the accumulated mosaic remains
     * valid.
     *
     * The threshold is in *canvas texels of displacement*, not in metres of radius:
     * a 10% radius change on a gentle curve moves the surface by less than a texel
     * and must not trigger a rebuild, while the same 10% on a tight curve moves it
     * far enough to smear glyphs and must.
     */
    private fun isEquivalent(a: WallSurface, b: WallSurface): Boolean {
        if (a is WallSurface.Planar && b is WallSurface.Planar) {
            return abs(a.signedDistance(b.origin)) < EQUIVALENCE_METRES &&
                (a.normal dot b.normal) > 0.999f
        }
        if (a is WallSurface.Cylindrical && b is WallSurface.Cylindrical) {
            if (a.concave != b.concave) return false
            // Sagitta difference over the span we have actually observed.
            val span = observedSpanMetres.coerceAtLeast(0.5f)
            val sagA = sagitta(a.radius, span)
            val sagB = sagitta(b.radius, span)
            return abs(sagA - sagB) < EQUIVALENCE_METRES &&
                (a.axis - b.axis).length < EQUIVALENCE_METRES * 10f
        }
        return false
    }

    private fun sagitta(radius: Float, chord: Float): Float {
        val half = chord * 0.5f
        val inner = radius * radius - half * half
        return if (inner <= 0f) radius else radius - kotlin.math.sqrt(inner)
    }

    private fun voxelKey(x: Float, y: Float, z: Float): Long {
        val s = 1f / config.voxelSize
        val ix = Math.round(x * s).toLong() and 0x1FFFFF
        val iy = Math.round(y * s).toLong() and 0x1FFFFF
        val iz = Math.round(z * s).toLong() and 0x1FFFFF
        return (ix shl 42) or (iy shl 21) or iz
    }

    fun describe(): String =
        surface?.let { (if (isForced) "forced " else "") + describe(it) } ?: "looking for the wall"

    fun describe(s: WallSurface): String = when (s) {
        is WallSurface.Planar -> "flat wall"
        is WallSurface.Cylindrical ->
            "${if (s.concave) "concave" else "convex"} wall, r=${"%.2f".format(s.radius)}m"
    }

    private companion object {
        const val TAG = "WallTracker"
        const val MIN_POINTS_TO_FIT = 120
        const val EQUIVALENCE_METRES = 0.004f
    }
}
