package com.puzzlesolver.app.render

import android.opengl.GLES30
import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.grid.GridModel
import com.puzzlesolver.core.math.Vec3
import com.puzzlesolver.core.puzzle.OverlayModel
import com.puzzlesolver.core.surface.WallSurface

/**
 * Draws the solution onto the wall in AR.
 *
 * Geometry is built once, in world space, when the solution changes -- not per
 * frame. The overlay is pinned to the wall, so once the quads exist the per-frame
 * cost is one view-projection matrix and one draw call. That is what keeps the
 * overlay free even while the solver is grinding on a hard board.
 *
 * Each cell quad is subdivided when the wall is curved enough for a flat quad to
 * visibly lift off the surface. The test is in sagitta against cell size, so a
 * gentle curve costs nothing and a tight one stays glued down.
 */
class OverlayRenderer(
    private val spec: CanvasSpec,
    private val atlas: GlyphAtlas,
) {
    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var vertexCount = 0

    /** x, y, z, u, v, r, g, b, a per vertex. */
    private var vertices = FloatArray(INITIAL_VERTEX_CAPACITY * STRIDE_FLOATS)

    fun initialize() {
        program = GlUtil.program(VERTEX, FRAGMENT)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, vertices.size * 4, null, GLES30.GL_DYNAMIC_DRAW,
        )
        val stride = STRIDE_FLOATS * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 5 * 4)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Rebuilds the overlay geometry. Call only when the solution or the wall fit
     * changes.
     */
    fun build(overlay: OverlayModel, grid: GridModel, surface: WallSurface) {
        var out = 0
        val subdivisions = subdivisionsFor(grid, surface)

        for (h in overlay.highlights) {
            out = appendCellQuad(
                out, grid, surface, h.col.toFloat(), h.row.toFloat(), 1f, 1f,
                u0 = -1f, v0 = -1f, u1 = -1f, v1 = -1f, color = h.colorRgba, subdivisions,
            )
        }

        for (g in overlay.glyphs) {
            val uv = atlas.uvFor(g.text.firstOrNull() ?: continue) ?: continue
            val inset = (1f - g.scale) * 0.5f
            out = appendCellQuad(
                out, grid, surface,
                g.col + inset, g.row + inset, g.scale, g.scale,
                uv[0], uv[1], uv[2], uv[3], g.colorRgba, subdivisions,
            )
        }

        for (s in overlay.strokes) {
            out = appendStroke(out, grid, surface, s, subdivisions)
        }

        vertexCount = out
        if (vertexCount == 0) return

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val bytes = vertexCount * STRIDE_FLOATS * 4
        // Reallocate rather than glBufferSubData when the solution outgrew the buffer.
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bytes, GlUtil.floatBuffer(vertices), GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * How finely to tessellate a cell so it hugs the wall.
     *
     * A chord of length c on a circle of radius r stands off the surface by
     * c^2 / (8r) at its midpoint. Subdivide until that is under a tenth of a
     * millimetre, which is well below what anyone can see at arm's length.
     */
    private fun subdivisionsFor(grid: GridModel, surface: WallSurface): Int {
        if (surface !is WallSurface.Cylindrical) return 1
        val cellMetres = grid.pitchX * spec.metresPerTexel
        var n = 1
        while (n < MAX_SUBDIVISIONS) {
            val chord = cellMetres / n
            if (chord * chord / (8f * surface.radius) < 0.0001f) break
            n *= 2
        }
        return n
    }

    private fun appendCellQuad(
        offset: Int,
        grid: GridModel,
        surface: WallSurface,
        col: Float,
        row: Float,
        width: Float,
        height: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
        color: Int,
        subdivisions: Int,
    ): Int {
        var out = offset
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val a = ((color shr 24) and 0xFF) / 255f
        val textured = u0 >= 0f

        for (j in 0 until subdivisions) {
            for (i in 0 until subdivisions) {
                val fx0 = i.toFloat() / subdivisions
                val fx1 = (i + 1f) / subdivisions
                val fy0 = j.toFloat() / subdivisions
                val fy1 = (j + 1f) / subdivisions

                ensureCapacity(out + 6)
                // Two triangles, wound consistently.
                val corners = arrayOf(
                    floatArrayOf(fx0, fy0), floatArrayOf(fx1, fy0), floatArrayOf(fx0, fy1),
                    floatArrayOf(fx1, fy0), floatArrayOf(fx1, fy1), floatArrayOf(fx0, fy1),
                )
                for (c in corners) {
                    val world = cellPointToWorld(grid, surface, col + c[0] * width, row + c[1] * height)
                    val tu = if (textured) u0 + (u1 - u0) * c[0] else 0f
                    val tv = if (textured) v0 + (v1 - v0) * c[1] else 0f
                    out = writeVertex(out, world, tu, tv, r, g, b, a, textured)
                }
            }
        }
        return out
    }

    /** Builds a ribbon along a polyline given in fractional cell coordinates. */
    private fun appendStroke(
        offset: Int,
        grid: GridModel,
        surface: WallSurface,
        stroke: OverlayModel.Stroke,
        subdivisions: Int,
    ): Int {
        var out = offset
        val pts = stroke.points
        if (pts.size < 4) return out
        val r = ((stroke.colorRgba shr 16) and 0xFF) / 255f
        val g = ((stroke.colorRgba shr 8) and 0xFF) / 255f
        val b = (stroke.colorRgba and 0xFF) / 255f
        val a = ((stroke.colorRgba shr 24) and 0xFF) / 255f
        val half = stroke.widthCells * 0.5f

        var i = 0
        while (i + 3 < pts.size) {
            val ax = pts[i]; val ay = pts[i + 1]
            val bx = pts[i + 2]; val by = pts[i + 3]
            var dx = bx - ax
            var dy = by - ay
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len < 1e-6f) {
                i += 2
                continue
            }
            dx /= len
            dy /= len
            // Perpendicular in cell space; the surface unwrap keeps this isometric,
            // so a constant-width ribbon in cell space is constant-width on the wall.
            val nx = -dy * half
            val ny = dx * half

            ensureCapacity(out + 6)
            val quad = arrayOf(
                floatArrayOf(ax + nx, ay + ny), floatArrayOf(bx + nx, by + ny), floatArrayOf(ax - nx, ay - ny),
                floatArrayOf(bx + nx, by + ny), floatArrayOf(bx - nx, by - ny), floatArrayOf(ax - nx, ay - ny),
            )
            for (c in quad) {
                val world = cellPointToWorld(grid, surface, c[0], c[1])
                out = writeVertex(out, world, 0f, 0f, r, g, b, a, textured = false)
            }
            i += 2
        }
        return out
    }

    /** Cell coordinates -> canvas texels -> wall (u, v) metres -> world. */
    private fun cellPointToWorld(
        grid: GridModel,
        surface: WallSurface,
        col: Float,
        row: Float,
    ): Vec3 {
        val canvas = grid.cellToCanvas(col, row)
        val u = spec.texelToU(canvas[0])
        val v = spec.texelToV(canvas[1])
        val onWall = surface.uvToWorld(u, v)
        // Lift a hair off the wall so the overlay never z-fights with anything the
        // depth API places at the same plane.
        return onWall + surface.normalAt(u) * SURFACE_OFFSET
    }

    private fun writeVertex(
        offset: Int,
        p: Vec3,
        u: Float,
        v: Float,
        r: Float, g: Float, b: Float, a: Float,
        textured: Boolean,
    ): Int {
        val i = offset * STRIDE_FLOATS
        vertices[i] = p.x
        vertices[i + 1] = p.y
        vertices[i + 2] = p.z
        // Negative u flags an untextured vertex to the fragment shader.
        vertices[i + 3] = if (textured) u else -1f
        vertices[i + 4] = v
        vertices[i + 5] = r
        vertices[i + 6] = g
        vertices[i + 7] = b
        vertices[i + 8] = a
        return offset + 1
    }

    private fun ensureCapacity(vertexCount: Int) {
        if (vertices.size >= vertexCount * STRIDE_FLOATS) return
        var capacity = vertices.size / STRIDE_FLOATS
        while (capacity < vertexCount) capacity *= 2
        vertices = vertices.copyOf(capacity * STRIDE_FLOATS)
    }

    fun draw(viewProjection: FloatArray) {
        if (vertexCount == 0) return
        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        GLES30.glUniformMatrix4fv(
            GLES30.glGetUniformLocation(program, "uViewProjection"), 1, false, viewProjection, 0,
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas.texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uAtlas"), 0)

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
        GLES30.glBindVertexArray(0)

        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun clear() {
        vertexCount = 0
    }

    fun release() {
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }

    private companion object {
        const val STRIDE_FLOATS = 9
        const val INITIAL_VERTEX_CAPACITY = 4096
        const val MAX_SUBDIVISIONS = 8
        const val SURFACE_OFFSET = 0.003f

        val VERTEX = """
            #version 300 es
            precision highp float;
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            layout(location = 2) in vec4 aColor;
            uniform mat4 uViewProjection;
            out vec2 vTexCoord;
            out vec4 vColor;
            void main() {
                gl_Position = uViewProjection * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
                vColor = aColor;
            }
        """.trimIndent()

        val FRAGMENT = """
            #version 300 es
            precision mediump float;
            uniform sampler2D uAtlas;
            in vec2 vTexCoord;
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                if (vTexCoord.x < 0.0) {
                    fragColor = vColor;                 // flat fill: highlights, strokes
                } else {
                    // The atlas stores coverage in alpha, so the glyph takes the
                    // solver's colour rather than the colour it was rasterised in.
                    float coverage = texture(uAtlas, vTexCoord).a;
                    if (coverage < 0.02) discard;
                    fragColor = vec4(vColor.rgb, vColor.a * coverage);
                }
            }
        """.trimIndent()
    }
}
