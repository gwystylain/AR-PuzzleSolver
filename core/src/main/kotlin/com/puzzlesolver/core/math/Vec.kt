package com.puzzlesolver.core.math

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Value-class-free plain data vectors. These are allocated freely in setup and
 * calibration code, but every per-pixel / per-frame hot loop in this project
 * works on raw FloatArrays instead -- see [com.puzzlesolver.core.surface.WallSurface]
 * for the batch APIs that avoid allocating one of these per sample.
 */
data class Vec3(@JvmField val x: Float, @JvmField val y: Float, @JvmField val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    val length: Float get() = sqrt(x * x + y * y + z * z)
    val lengthSq: Float get() = x * x + y * y + z * z

    fun normalized(): Vec3 {
        val l = length
        return if (l < 1e-9f) ZERO else Vec3(x / l, y / l, z / l)
    }

    /** Component of this vector perpendicular to [axis] (which must be unit length). */
    fun rejectFrom(axis: Vec3): Vec3 = this - axis * (this dot axis)

    fun toFloatArray() = floatArrayOf(x, y, z)

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
        fun of(a: FloatArray, off: Int = 0) = Vec3(a[off], a[off + 1], a[off + 2])
    }
}

data class Vec2(@JvmField val x: Float, @JvmField val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    infix fun dot(o: Vec2): Float = x * o.x + y * o.y
    /** 2D cross product magnitude; sign tells you which side [o] is on. */
    infix fun cross(o: Vec2): Float = x * o.y - y * o.x
    val length: Float get() = sqrt(x * x + y * y)

    fun normalized(): Vec2 {
        val l = length
        return if (l < 1e-9f) Vec2(0f, 0f) else Vec2(x / l, y / l)
    }
}

fun approxEquals(a: Float, b: Float, eps: Float = 1e-5f) = abs(a - b) <= eps
