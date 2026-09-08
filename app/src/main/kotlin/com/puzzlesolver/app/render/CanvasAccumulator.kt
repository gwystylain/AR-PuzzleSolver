package com.puzzlesolver.app.render

import android.opengl.GLES30
import android.opengl.Matrix
import com.puzzlesolver.app.frame.FrameData
import com.puzzlesolver.core.canvas.CanvasSpec
import com.puzzlesolver.core.surface.WallSurface

/**
 * Builds the flattened puzzle image by projecting every camera frame onto the wall
 * surface and keeping, per canvas texel, the single best look we have ever had at it.
 *
 * Why best-sample rather than blending:
 *
 * Averaging frames is the obvious mosaic strategy and it is wrong here. Frames of a
 * wall taken while panning differ in scale, incidence angle and motion blur; the
 * mean of a sharp look and five smeared ones is a smeared look. Since the target is
 * static we can afford to be picky, so each texel keeps the highest-confidence
 * observation instead. Glyph strokes stay crisp, and crisp strokes are what the
 * classifier needs.
 *
 * The comparison is done by the depth unit rather than in the shader. Writing
 * `gl_FragDepth = 1 - confidence` with a GL_LESS depth test makes the hardware
 * perform a per-texel max-confidence reduction for free, with no read-modify-write
 * hazard and no second pass. Cost: early-Z is disabled for this draw, which is
 * irrelevant because the draw is fill-bound on a texture fetch anyway.
 */
class CanvasAccumulator(
    private val spec: CanvasSpec,
    /** Vertices per side of the surface mesh. 129 gives ~4 cm facets on a 6 m wall. */
    private val meshResolution: Int = 129,
) {
    private var program = 0
    private var fbo = 0
    private var colorTex = 0
    private var depthRb = 0

    private var coverageProgram = 0
    private var coverageFbo = 0
    private var coverageTex = 0

    /**
     * Single-channel staging target for tile readback.
     *
     * The canvas itself is RGBA8, and `glReadPixels` on an RGBA8 framebuffer only
     * accepts GL_RGBA (plus whatever the driver advertises as its preferred format) --
     * asking for GL_RED raises GL_INVALID_OPERATION and returns nothing. Blitting the
     * tile into an R8 target first keeps the transfer at one byte per texel instead of
     * four, which matters when this runs several times per frame.
     */
    private var tileProgram = 0
    private var tileFbo = 0
    private var tileTex = 0

    /**
     * Two-channel staging target for the chroma half of a tile.
     *
     * Separate from the luma path on purpose. Luma feeds grid detection and glyph
     * classification for every existing puzzle; chroma feeds one adapter that only runs
     * when that puzzle is on screen. Reading them together would put a permanent 3x on
     * the readback bandwidth to serve the rarer case.
     */
    private var chromaProgram = 0
    private var chromaFbo = 0
    private var chromaTex = 0

    private var vao = 0
    private var vbo = 0
    private var ibo = 0
    private var indexCount = 0

    /** Coverage grid dimensions, mirroring CoverageMap's stride. */
    val coverageCols = (spec.widthTexels + COVERAGE_STRIDE - 1) / COVERAGE_STRIDE
    val coverageRows = (spec.heightTexels + COVERAGE_STRIDE - 1) / COVERAGE_STRIDE

    private var coverageReadback: AsyncReadback? = null
    private var tileReadback: AsyncReadback? = null
    private var chromaReadback: AsyncReadback? = null

    private val viewProjection = FloatArray(16)
    private val ndcToTexture = FloatArray(6)

    val canvasTexture: Int get() = colorTex

    fun initialize() {
        program = GlUtil.program(VERTEX_SHADER, FRAGMENT_SHADER)
        coverageProgram = GlUtil.program(FULLSCREEN_VERTEX, COVERAGE_FRAGMENT)

        colorTex = GlUtil.createTexture(
            GLES30.GL_TEXTURE_2D, spec.widthTexels, spec.heightTexels, GLES30.GL_RGBA8,
        )

        val rb = IntArray(1)
        GLES30.glGenRenderbuffers(1, rb, 0)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb[0])
        GLES30.glRenderbufferStorage(
            GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, spec.widthTexels, spec.heightTexels,
        )
        depthRb = rb[0]

        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, colorTex, 0,
        )
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depthRb,
        )
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "canvas framebuffer incomplete"
        }

        tileProgram = GlUtil.program(FULLSCREEN_VERTEX, TILE_FRAGMENT)
        tileTex = GlUtil.createTexture(GLES30.GL_TEXTURE_2D, TILE_SIZE, TILE_SIZE, GLES30.GL_R8)
        GLES30.glGenFramebuffers(1, f, 0)
        tileFbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tileFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tileTex, 0,
        )
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "tile framebuffer incomplete"
        }

        coverageTex = GlUtil.createTexture(
            GLES30.GL_TEXTURE_2D, coverageCols, coverageRows, GLES30.GL_R8,
        )
        GLES30.glGenFramebuffers(1, f, 0)
        coverageFbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, coverageFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, coverageTex, 0,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        buildMesh()

        chromaProgram = GlUtil.program(FULLSCREEN_VERTEX, CHROMA_FRAGMENT)
        chromaTex = GlUtil.createTexture(GLES30.GL_TEXTURE_2D, TILE_SIZE, TILE_SIZE, GLES30.GL_RG8)
        GLES30.glGenFramebuffers(1, f, 0)
        chromaFbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, chromaFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, chromaTex, 0,
        )
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "chroma framebuffer incomplete"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        coverageReadback = AsyncReadback(coverageCols, coverageRows, GLES30.GL_RED, 1)
        tileReadback = AsyncReadback(TILE_SIZE, TILE_SIZE, GLES30.GL_RED, 1)
        chromaReadback = AsyncReadback(TILE_SIZE, TILE_SIZE, GLES30.GL_RG, 2)

        clear()
        GlUtil.checkError("CanvasAccumulator.initialize")
    }

    /**
     * Resets the mosaic. Called when tracking is lost badly enough that the world
     * anchor is suspect, or when the user restarts the scan.
     */
    fun clear() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClearDepthf(1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * A regular grid over canvas (u, v) in [0,1]^2. The vertex shader turns those
     * into world positions on whatever surface we have fitted, so the same mesh
     * serves a flat wall and a curved one and nothing has to be rebuilt when the
     * fit is refined.
     */
    private fun buildMesh() {
        val n = meshResolution
        val verts = FloatArray(n * n * 2)
        var k = 0
        for (j in 0 until n) {
            for (i in 0 until n) {
                verts[k++] = i.toFloat() / (n - 1)
                verts[k++] = j.toFloat() / (n - 1)
            }
        }
        val indices = ShortArray((n - 1) * (n - 1) * 6)
        var m = 0
        for (j in 0 until n - 1) {
            for (i in 0 until n - 1) {
                val a = (j * n + i).toShort()
                val b = (j * n + i + 1).toShort()
                val c = ((j + 1) * n + i).toShort()
                val d = ((j + 1) * n + i + 1).toShort()
                indices[m++] = a; indices[m++] = b; indices[m++] = c
                indices[m++] = b; indices[m++] = d; indices[m++] = c
            }
        }
        indexCount = indices.size

        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        ibo = ids[1]

        val vaoIds = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoIds, 0)
        vao = vaoIds[0]
        GLES30.glBindVertexArray(vao)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val vb = GlUtil.floatBuffer(verts)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)

        val ib = java.nio.ByteBuffer.allocateDirect(indices.size * 2)
            .order(java.nio.ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(indices)
                position(0)
            }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, ib, GLES30.GL_STATIC_DRAW)

        GLES30.glBindVertexArray(0)
    }

    /**
     * Projects one frame onto the canvas.
     *
     * @param projection ARCore's projection matrix for this frame
     * @param view       ARCore's view matrix for this frame
     */
    fun accumulate(
        frame: FrameData,
        surface: WallSurface,
        projection: FloatArray,
        view: FloatArray,
    ) {
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        computeNdcToTexture(frame.textureTransform)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, spec.widthTexels, spec.heightTexels)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(frame.textureTarget, frame.textureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCameraTex"), 0)

        GLES30.glUniformMatrix4fv(
            GLES30.glGetUniformLocation(program, "uViewProjection"), 1, false, viewProjection, 0,
        )
        GLES30.glUniform2fv(
            GLES30.glGetUniformLocation(program, "uNdcToTex"), 3, ndcToTexture, 0,
        )
        GLES30.glUniform4f(
            GLES30.glGetUniformLocation(program, "uCanvasExtent"),
            spec.uMin, spec.vMin, spec.widthMetres, spec.heightMetres,
        )

        val cam = frame.pose!!.translation
        GLES30.glUniform3f(GLES30.glGetUniformLocation(program, "uCameraPos"), cam.x, cam.y, cam.z)

        uploadSurface(surface)

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GlUtil.checkError("accumulate")
    }

    private fun uploadSurface(surface: WallSurface) {
        when (surface) {
            is WallSurface.Planar -> {
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uSurfaceType"), 0)
                GLES30.glUniform3f(
                    GLES30.glGetUniformLocation(program, "uOrigin"),
                    surface.origin.x, surface.origin.y, surface.origin.z,
                )
                GLES30.glUniform3f(
                    GLES30.glGetUniformLocation(program, "uTangent"),
                    surface.tangent.x, surface.tangent.y, surface.tangent.z,
                )
                GLES30.glUniform3f(
                    GLES30.glGetUniformLocation(program, "uNormal"),
                    surface.normal.x, surface.normal.y, surface.normal.z,
                )
                // The plane's second axis is not always world up: a camera-facing plane
                // uses the camera's up. Hardcoding (0,1,0) here silently skews the
                // canvas for any such surface.
                GLES30.glUniform3f(
                    GLES30.glGetUniformLocation(program, "uUp"),
                    surface.up.x, surface.up.y, surface.up.z,
                )
            }
            is WallSurface.Cylindrical -> {
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uSurfaceType"), 1)
                GLES30.glUniform3f(
                    GLES30.glGetUniformLocation(program, "uOrigin"),
                    surface.axis.x, 0f, surface.axis.z,
                )
                GLES30.glUniform3f(
                    GLES30.glGetUniformLocation(program, "uCylinder"),
                    surface.radius, surface.thetaRef, if (surface.concave) -1f else 1f,
                )
            }
        }
    }

    /**
     * ARCore hands us the NDC-to-texture mapping as four corner pairs. It is affine,
     * so three corners determine it; solving once per frame on the CPU is cheaper
     * than shipping all four to the shader and interpolating.
     */
    private fun computeNdcToTexture(quad: FloatArray) {
        // quad holds tex coords for NDC corners (-1,-1) (1,-1) (-1,1) (1,1).
        val t00x = quad[0]; val t00y = quad[1]
        val t10x = quad[2]; val t10y = quad[3]
        val t01x = quad[4]; val t01y = quad[5]
        // tex = a * ndc.x + b * ndc.y + c, solved from the three corners.
        ndcToTexture[0] = (t10x - t00x) * 0.5f
        ndcToTexture[1] = (t10y - t00y) * 0.5f
        ndcToTexture[2] = (t01x - t00x) * 0.5f
        ndcToTexture[3] = (t01y - t00y) * 0.5f
        ndcToTexture[4] = t00x + ndcToTexture[0] + ndcToTexture[2]
        ndcToTexture[5] = t00y + ndcToTexture[1] + ndcToTexture[3]
    }

    // --- Readback --------------------------------------------------------

    /** Queues the coverage downsample and its readback. Call once per accumulated frame. */
    fun requestCoverage() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, coverageFbo)
        GLES30.glViewport(0, 0, coverageCols, coverageRows)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(coverageProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(coverageProgram, "uCanvas"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(coverageProgram, "uStride"), COVERAGE_STRIDE)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        coverageReadback?.request(0, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GlUtil.checkError("requestCoverage")
    }

    fun collectCoverage(dst: ByteArray): Boolean =
        coverageReadback?.collect(dst) != null

    /**
     * Queues a readback of one canvas tile.
     *
     * The full canvas is 16 MB; pulling it across every detection pass would cost
     * more than everything else put together. Instead the pipeline nominates the
     * tiles whose coverage has changed and we stream those, a few per frame, into
     * the CPU-side mirror. Detection then runs against a canvas that is a fraction
     * of a second stale, which for a puzzle painted on a wall is no staleness at all.
     */
    fun requestTile(tileX: Int, tileY: Int): Boolean {
        // Blit the tile's luma channel into the R8 staging target, then read that. See
        // the field comment for why we cannot read GL_RED straight off the canvas.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tileFbo)
        GLES30.glViewport(0, 0, TILE_SIZE, TILE_SIZE)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(tileProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(tileProgram, "uCanvas"), 0)
        GLES30.glUniform2i(
            GLES30.glGetUniformLocation(tileProgram, "uOrigin"),
            tileX * TILE_SIZE, tileY * TILE_SIZE,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // Read from the staging target's origin, but tag it with where it came from on
        // the canvas -- otherwise every tile lands on top of the same corner.
        val ok = tileReadback?.request(0, 0, tileX * TILE_SIZE, tileY * TILE_SIZE) ?: false
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GlUtil.checkError("requestTile")
        return ok
    }

    /** @return the tile origin in canvas texels, or null when nothing is ready. */
    fun collectTile(dst: ByteArray): IntArray? = tileReadback?.collect(dst)

    /**
     * Queues a readback of one tile's chroma, two bytes per texel.
     *
     * Only issued while a colour-hungry adapter is active, so the common case pays
     * nothing for it.
     */
    fun requestChromaTile(tileX: Int, tileY: Int): Boolean {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, chromaFbo)
        GLES30.glViewport(0, 0, TILE_SIZE, TILE_SIZE)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(chromaProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(chromaProgram, "uCanvas"), 0)
        GLES30.glUniform2i(
            GLES30.glGetUniformLocation(chromaProgram, "uOrigin"),
            tileX * TILE_SIZE, tileY * TILE_SIZE,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val ok = chromaReadback?.request(0, 0, tileX * TILE_SIZE, tileY * TILE_SIZE) ?: false
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GlUtil.checkError("requestChromaTile")
        return ok
    }

    fun collectChromaTile(dst: ByteArray): IntArray? = chromaReadback?.collect(dst)

    fun release() {
        coverageReadback?.release()
        tileReadback?.release()
        chromaReadback?.release()
        GLES30.glDeleteFramebuffers(4, intArrayOf(fbo, coverageFbo, tileFbo, chromaFbo), 0)
        GLES30.glDeleteTextures(4, intArrayOf(colorTex, coverageTex, tileTex, chromaTex), 0)
        GLES30.glDeleteRenderbuffers(1, intArrayOf(depthRb), 0)
        GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteProgram(coverageProgram)
        GLES30.glDeleteProgram(tileProgram)
    }

    companion object {
        const val COVERAGE_STRIDE = 16
        const val TILE_SIZE = 256

        private val VERTEX_SHADER = """
            #version 300 es
            precision highp float;

            layout(location = 0) in vec2 aCanvasUv;      // [0,1]^2 over the canvas

            uniform mat4 uViewProjection;
            uniform vec2 uNdcToTex[3];                   // affine NDC -> camera texture
            uniform vec4 uCanvasExtent;                  // uMin, vMin, uSize, vSize
            uniform vec3 uCameraPos;

            uniform int  uSurfaceType;                   // 0 = planar, 1 = cylindrical
            uniform vec3 uOrigin;                        // plane origin, or cylinder axis
            uniform vec3 uTangent;                       // planar only
            uniform vec3 uUp;                            // planar only
            uniform vec3 uNormal;                        // planar only
            uniform vec3 uCylinder;                      // radius, thetaRef, concaveSign

            out vec2 vTexCoord;
            out float vConfidence;

            void main() {
                float u = uCanvasExtent.x + aCanvasUv.x * uCanvasExtent.z;
                float v = uCanvasExtent.y + aCanvasUv.y * uCanvasExtent.w;

                vec3 world;
                vec3 normal;
                if (uSurfaceType == 0) {
                    world  = uOrigin + uTangent * u + uUp * v;
                    normal = uNormal;
                } else {
                    float radius = uCylinder.x;
                    float theta  = uCylinder.y + u / radius;
                    world  = vec3(uOrigin.x + radius * cos(theta), v, uOrigin.z + radius * sin(theta));
                    normal = uCylinder.z * vec3(cos(theta), 0.0, sin(theta));
                }

                // Canvas position is a straight remap of the mesh parameter; the
                // whole point of the developable-surface model is that this stays
                // linear no matter how the wall curves.
                gl_Position = vec4(aCanvasUv * 2.0 - 1.0, 0.0, 1.0);

                vec4 clip = uViewProjection * vec4(world, 1.0);
                vec2 ndc  = clip.xy / max(clip.w, 1e-6);
                vTexCoord = uNdcToTex[0] * ndc.x + uNdcToTex[1] * ndc.y + uNdcToTex[2];

                vec3 toCamera = uCameraPos - world;
                float distance = length(toCamera);
                vec3 viewDir = toCamera / max(distance, 1e-6);

                // Confidence, in order of how much each factor actually matters:
                //  - incidence: a texel seen at 70 degrees is smeared across a third
                //    of the pixels it deserves, and its glyph edges go with it
                //  - distance: sampling density falls off as 1/d, so a far look
                //    carries less real information even when perfectly square-on
                //  - behind the camera: reject outright
                float incidence = dot(normal, viewDir);
                float distanceTerm = clamp(1.5 / max(distance, 0.3), 0.0, 1.0);
                float behind = clip.w > 0.0 ? 1.0 : 0.0;
                vConfidence = clamp(incidence, 0.0, 1.0) * distanceTerm * behind;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;

            uniform samplerExternalOES uCameraTex;

            in vec2 vTexCoord;
            in float vConfidence;

            layout(location = 0) out vec4 fragColor;

            void main() {
                // Outside the frame there is nothing to learn from this texel.
                if (vTexCoord.x < 0.0 || vTexCoord.x > 1.0 ||
                    vTexCoord.y < 0.0 || vTexCoord.y > 1.0 || vConfidence <= 0.01) {
                    discard;
                }

                // Fade the last few percent at the frame edge. Rolling shutter and
                // lens distortion are both worst there, and a hard cut leaves a
                // visible seam in the mosaic exactly where glyphs get misread.
                vec2 edge = min(vTexCoord, 1.0 - vTexCoord);
                float border = smoothstep(0.0, 0.04, min(edge.x, edge.y));
                float confidence = vConfidence * border;
                if (confidence <= 0.01) discard;

                vec3 rgb = texture(uCameraTex, vTexCoord).rgb;
                float luma = dot(rgb, vec3(0.299, 0.587, 0.114));

                // Blue and alpha were spare, and one puzzle needs colour. BT.601
                // chroma, biased to the middle of the range so both signs survive an
                // unsigned 8-bit channel. Luma stays in red and confidence in green,
                // untouched, so every existing reader of this texture is unaffected.
                float cb = 0.5 + (rgb.b - luma) / 1.772;
                float cr = 0.5 + (rgb.r - luma) / 1.402;

                // The depth unit does the max-confidence reduction for us.
                gl_FragDepth = 1.0 - confidence;
                fragColor = vec4(luma, confidence, cb, cr);
            }
        """.trimIndent()

        private val FULLSCREEN_VERTEX = """
            #version 300 es
            precision highp float;
            out vec2 vUv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()

        private val CHROMA_FRAGMENT = """
            #version 300 es
            precision highp float;
            uniform sampler2D uCanvas;
            uniform ivec2 uOrigin;
            layout(location = 0) out vec2 fragChroma;
            void main() {
                // Cb and Cr, straight out of the two channels the accumulator parks
                // them in. Exact texel copy for the same reason the luma path is: any
                // filtering here bleeds one button's glow into its neighbour, and the
                // glow is the entire colour signal on that wall.
                vec4 c = texelFetch(uCanvas, uOrigin + ivec2(gl_FragCoord.xy), 0);
                fragChroma = vec2(c.b, c.a);
            }
        """.trimIndent()

        private val TILE_FRAGMENT = """
            #version 300 es
            precision highp float;
            uniform sampler2D uCanvas;
            uniform ivec2 uOrigin;
            layout(location = 0) out float fragLuma;
            void main() {
                // Exact texel copy, no filtering: this feeds glyph classification, and
                // any interpolation here would soften strokes the accumulator worked to
                // keep sharp.
                fragLuma = texelFetch(uCanvas, uOrigin + ivec2(gl_FragCoord.xy), 0).r;
            }
        """.trimIndent()

        private val COVERAGE_FRAGMENT = """
            #version 300 es
            precision highp float;
            uniform sampler2D uCanvas;
            uniform int uStride;
            in vec2 vUv;
            layout(location = 0) out float fragCoverage;
            void main() {
                // Max, not mean: a cell counts as observed if any part of it was seen
                // well, and the grid detector wants generous bounds rather than a
                // conservative interior.
                ivec2 base = ivec2(gl_FragCoord.xy) * uStride;
                float best = 0.0;
                for (int j = 0; j < 16; ++j) {
                    for (int i = 0; i < 16; ++i) {
                        best = max(best, texelFetch(uCanvas, base + ivec2(i, j), 0).g);
                    }
                }
                fragCoverage = best;
            }
        """.trimIndent()
    }
}
