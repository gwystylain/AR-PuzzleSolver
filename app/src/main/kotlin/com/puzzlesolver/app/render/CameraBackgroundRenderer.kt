package com.puzzlesolver.app.render

import android.opengl.GLES11Ext
import android.opengl.GLES30

/**
 * Draws the camera image as the screen background.
 *
 * Deliberately the dumbest thing in the render path: one quad, no depth, no state
 * beyond the texture binding. Everything interesting happens in
 * [CanvasAccumulator] and [OverlayRenderer]; this exists so the user can see what
 * the camera sees.
 */
class CameraBackgroundRenderer {

    private var program = 0
    private var texCoordBuffer = 0
    private var vao = 0

    /** Texture coordinates for the four NDC corners, refreshed each frame by ARCore. */
    private val texCoords = FloatArray(8)

    fun initialize() {
        program = GlUtil.program(VERTEX, FRAGMENT)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        texCoordBuffer = ids[0]
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, texCoordBuffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)
    }

    fun draw(textureId: Int, quadTexCoords: FloatArray) {
        System.arraycopy(quadTexCoords, 0, texCoords, 0, 8)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(program)

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, texCoordBuffer)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 8 * 4, GlUtil.floatBuffer(texCoords))

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCameraTex"), 0)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(texCoordBuffer), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }

    private companion object {
        val VERTEX = """
            #version 300 es
            precision highp float;
            layout(location = 0) in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                // Corner order matches ArCoreFrameSource.QUAD_NDC.
                vec2 ndc = vec2(
                    (gl_VertexID == 0 || gl_VertexID == 2) ? -1.0 : 1.0,
                    (gl_VertexID < 2) ? -1.0 : 1.0
                );
                gl_Position = vec4(ndc, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val FRAGMENT = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            uniform samplerExternalOES uCameraTex;
            in vec2 vTexCoord;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uCameraTex, vTexCoord);
            }
        """.trimIndent()
    }
}
