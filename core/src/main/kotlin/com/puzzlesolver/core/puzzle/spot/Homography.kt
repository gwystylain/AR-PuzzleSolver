package com.puzzlesolver.core.puzzle.spot

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A plane-to-plane projective map, as a row-major 3x3 matrix.
 *
 * Spot needs two: one that straightens a photographed screen into a rectangle, and the
 * small correction on top of it that lines one photo's rectangle up with the others'. A
 * photo of a flat screen from anywhere is exactly a homography of the screen, so this is
 * the whole of the geometry -- no pose, no intrinsics, no lens model.
 */
class Homography(@JvmField val m: DoubleArray) {

    init {
        require(m.size == 9)
    }

    /** Maps (x, y) into [out] as x then y. */
    fun map(x: Double, y: Double, out: DoubleArray) {
        val w = m[6] * x + m[7] * y + m[8]
        out[0] = (m[0] * x + m[1] * y + m[2]) / w
        out[1] = (m[3] * x + m[4] * y + m[5]) / w
    }

    /** `this * other`: apply [other] first, then this. */
    operator fun times(other: Homography): Homography {
        val a = m
        val b = other.m
        val c = DoubleArray(9)
        for (r in 0 until 3) {
            for (k in 0 until 3) {
                c[r * 3 + k] = a[r * 3] * b[k] + a[r * 3 + 1] * b[3 + k] + a[r * 3 + 2] * b[6 + k]
            }
        }
        return Homography(c).normalised()
    }

    private fun normalised(): Homography {
        val s = m[8]
        if (abs(s) < 1e-12) return this
        return Homography(DoubleArray(9) { m[it] / s })
    }

    companion object {
        val IDENTITY = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun scale(sx: Double, sy: Double) = Homography(doubleArrayOf(sx, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 1.0))

        /**
         * The homography taking each `src` point to its `dst` point, in the least-squares
         * sense when there are more than four.
         *
         * Points are flat x, y pairs. The usual conditioning applies -- each set is moved
         * to its centroid and scaled to a mean distance of root two before solving -- which
         * is the difference between a fit that holds to a hundredth of a pixel and one that
         * wanders by several on a 600-pixel image. The last element is fixed at one, which
         * only fails for a map that sends the centroid to infinity; a photographed screen
         * never does.
         *
         * @return null when the points are degenerate: fewer than four, or all on a line.
         */
        fun fit(src: DoubleArray, dst: DoubleArray, count: Int = src.size / 2): Homography? {
            if (count < 4) return null
            val ns = conditioning(src, count) ?: return null
            val nd = conditioning(dst, count) ?: return null
            // Normal equations of the 2N x 8 system, accumulated directly.
            val ata = DoubleArray(64)
            val atb = DoubleArray(8)
            val row = DoubleArray(8)
            val p = DoubleArray(2)
            val q = DoubleArray(2)
            for (i in 0 until count) {
                ns.map(src[i * 2], src[i * 2 + 1], p)
                nd.map(dst[i * 2], dst[i * 2 + 1], q)
                val x = p[0]
                val y = p[1]
                for (eq in 0 until 2) {
                    val t = q[eq]
                    row.fill(0.0)
                    if (eq == 0) {
                        row[0] = x; row[1] = y; row[2] = 1.0
                    } else {
                        row[3] = x; row[4] = y; row[5] = 1.0
                    }
                    row[6] = -t * x
                    row[7] = -t * y
                    for (r in 0 until 8) {
                        val v = row[r]
                        if (v == 0.0) continue
                        for (c in 0 until 8) ata[r * 8 + c] += v * row[c]
                        atb[r] += v * t
                    }
                }
            }
            val h = solve(ata, atb, 8) ?: return null
            val hn = Homography(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0))
            return (invert(nd) ?: return null) * hn * ns
        }

        fun invert(h: Homography): Homography? {
            val a = h.m
            val c00 = a[4] * a[8] - a[5] * a[7]
            val c01 = a[5] * a[6] - a[3] * a[8]
            val c02 = a[3] * a[7] - a[4] * a[6]
            val det = a[0] * c00 + a[1] * c01 + a[2] * c02
            if (abs(det) < 1e-15) return null
            val inv = doubleArrayOf(
                c00, a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
                c01, a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
                c02, a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
            )
            return Homography(DoubleArray(9) { inv[it] / det }).normalised()
        }

        /** Similarity taking the points to their centroid at a mean distance of root two. */
        private fun conditioning(pts: DoubleArray, count: Int): Homography? {
            var cx = 0.0
            var cy = 0.0
            for (i in 0 until count) {
                cx += pts[i * 2]
                cy += pts[i * 2 + 1]
            }
            cx /= count
            cy /= count
            var d = 0.0
            for (i in 0 until count) {
                val dx = pts[i * 2] - cx
                val dy = pts[i * 2 + 1] - cy
                d += sqrt(dx * dx + dy * dy)
            }
            d /= count
            if (d < 1e-9) return null
            val s = sqrt(2.0) / d
            return Homography(doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0))
        }

        /** Gaussian elimination with partial pivoting; null when singular. */
        private fun solve(a: DoubleArray, b: DoubleArray, n: Int): DoubleArray? {
            val m = a.copyOf()
            val v = b.copyOf()
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) if (abs(m[r * n + col]) > abs(m[pivot * n + col])) pivot = r
                if (abs(m[pivot * n + col]) < 1e-12) return null
                if (pivot != col) {
                    for (c in 0 until n) {
                        val t = m[col * n + c]; m[col * n + c] = m[pivot * n + c]; m[pivot * n + c] = t
                    }
                    val t = v[col]; v[col] = v[pivot]; v[pivot] = t
                }
                for (r in col + 1 until n) {
                    val f = m[r * n + col] / m[col * n + col]
                    if (f == 0.0) continue
                    for (c in col until n) m[r * n + c] -= f * m[col * n + c]
                    v[r] -= f * v[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = v[r]
                for (c in r + 1 until n) s -= m[r * n + c] * x[c]
                x[r] = s / m[r * n + r]
            }
            return x
        }
    }
}
