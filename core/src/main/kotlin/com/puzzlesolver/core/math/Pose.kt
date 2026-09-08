package com.puzzlesolver.core.math

import kotlin.math.sqrt

/**
 * Rigid transform: rotation (unit quaternion) + translation. Mirrors the layout
 * of `com.google.ar.core.Pose` so the ARCore bridge is a straight copy, but lives
 * in :core so replay/tests never touch Android.
 */
class Pose(
    @JvmField val tx: Float, @JvmField val ty: Float, @JvmField val tz: Float,
    @JvmField val qx: Float, @JvmField val qy: Float, @JvmField val qz: Float, @JvmField val qw: Float,
) {
    val translation: Vec3 get() = Vec3(tx, ty, tz)

    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2 * q_vec x (q_vec x v + q_w * v)
        val ux = qy * v.z - qz * v.y + qw * v.x
        val uy = qz * v.x - qx * v.z + qw * v.y
        val uz = qx * v.y - qy * v.x + qw * v.z
        return Vec3(
            v.x + 2f * (qy * uz - qz * uy),
            v.y + 2f * (qz * ux - qx * uz),
            v.z + 2f * (qx * uy - qy * ux),
        )
    }

    fun transform(v: Vec3): Vec3 {
        val r = rotate(v)
        return Vec3(r.x + tx, r.y + ty, r.z + tz)
    }

    fun inverseRotate(v: Vec3): Vec3 = Pose(0f, 0f, 0f, -qx, -qy, -qz, qw).rotate(v)

    fun inverseTransform(v: Vec3): Vec3 =
        inverseRotate(Vec3(v.x - tx, v.y - ty, v.z - tz))

    /** Camera basis in world space, using the OpenGL camera convention (-Z forward). */
    val forward: Vec3 get() = rotate(Vec3(0f, 0f, -1f))
    val right: Vec3 get() = rotate(Vec3(1f, 0f, 0f))
    val up: Vec3 get() = rotate(Vec3(0f, 1f, 0f))

    companion object {
        val IDENTITY = Pose(0f, 0f, 0f, 0f, 0f, 0f, 1f)

        fun fromArrays(t: FloatArray, q: FloatArray) =
            Pose(t[0], t[1], t[2], q[0], q[1], q[2], q[3])

        /** Normalizes on construction; ARCore poses drift slightly over long sessions. */
        fun normalized(tx: Float, ty: Float, tz: Float, qx: Float, qy: Float, qz: Float, qw: Float): Pose {
            val n = sqrt(qx * qx + qy * qy + qz * qz + qw * qw).takeIf { it > 1e-9f } ?: 1f
            return Pose(tx, ty, tz, qx / n, qy / n, qz / n, qw / n)
        }
    }
}

/**
 * Pinhole intrinsics for the frame we are actually processing (i.e. already
 * scaled to the CPU image size, not the sensor's native size).
 */
data class Intrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
) {
    /** Unprojects a pixel to a ray direction in camera space (-Z forward). */
    fun rayCamera(px: Float, py: Float): Vec3 =
        Vec3((px - cx) / fx, -(py - cy) / fy, -1f)

    /** Projects a camera-space point to pixel coordinates. Returns null if behind the camera. */
    fun project(p: Vec3): Vec2? {
        if (p.z >= -1e-4f) return null
        val invZ = -1f / p.z
        return Vec2(cx + fx * p.x * invZ, cy - fy * p.y * invZ)
    }

    fun scaled(sx: Float, sy: Float) = Intrinsics(
        fx * sx, fy * sy, cx * sx, cy * sy,
        (width * sx).toInt(), (height * sy).toInt(),
    )
}
