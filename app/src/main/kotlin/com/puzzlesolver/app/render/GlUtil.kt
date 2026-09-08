package com.puzzlesolver.app.render

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Thin GL ES 3.0 helpers. Everything here is called from the GL thread only. */
object GlUtil {

    private const val TAG = "GlUtil"

    fun compile(type: Int, source: String): Int {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, source)
        GLES30.glCompileShader(id)
        val status = IntArray(1)
        GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(id)
            GLES30.glDeleteShader(id)
            throw RuntimeException("shader compile failed: $log")
        }
        return id
    }

    fun program(vertexSource: String, fragmentSource: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            GLES30.glDeleteProgram(p)
            throw RuntimeException("program link failed: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return p
    }

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }

    fun checkError(tag: String) {
        var e = GLES30.glGetError()
        while (e != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "$tag: GL error 0x${Integer.toHexString(e)}")
            e = GLES30.glGetError()
        }
    }

    fun createTexture(target: Int, width: Int, height: Int, internalFormat: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(target, ids[0])
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (width > 0 && height > 0) {
            GLES30.glTexStorage2D(target, 1, internalFormat, width, height)
        }
        GLES30.glBindTexture(target, 0)
        return ids[0]
    }
}

/**
 * Asynchronous readback of a GL texture region via pixel buffer objects.
 *
 * Synchronous glReadPixels stalls the GPU pipeline until every queued command
 * drains -- on a mobile tiler that is routinely 10-20 ms, which would eat the
 * entire frame budget. Instead we issue the read into a PBO, insert a fence, and
 * collect the result one or two frames later. The CPU pipeline is then always
 * working on data that is a couple of frames stale, which is irrelevant here: the
 * puzzle is not going anywhere.
 */
class AsyncReadback(
    private val width: Int,
    private val height: Int,
    private val format: Int = GLES30.GL_RED,
    private val bytesPerPixel: Int = 1,
    slots: Int = 3,
) {
    private val pbos = IntArray(slots)
    private val fences = arrayOfNulls<Long>(slots)
    /** Origin of the region each in-flight read covers, so the consumer knows where it goes. */
    private val origins = Array(slots) { IntArray(2) }
    private var writeSlot = 0
    private var pending = 0

    private val byteCount = width * height * bytesPerPixel

    init {
        GLES30.glGenBuffers(slots, pbos, 0)
        for (id in pbos) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, id)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, byteCount, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    /**
     * Queues a read of a [width] x [height] region of the bound framebuffer whose
     * lower-left corner is ([x], [y]). Never blocks.
     *
     * @return false when every slot is still in flight, meaning the consumer is
     *         behind and this read was skipped.
     */
    fun request(x: Int = 0, y: Int = 0, tagX: Int = x, tagY: Int = y): Boolean {
        if (pending >= pbos.size) return false
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[writeSlot])
        GLES30.glReadPixels(x, y, width, height, format, GLES30.GL_UNSIGNED_BYTE, 0)
        fences[writeSlot] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        // The tag is where the data *belongs*, which is not always where it was read
        // from: tile reads are blitted into a staging target first and so are always
        // read from its origin, while the consumer needs the canvas coordinate.
        origins[writeSlot][0] = tagX
        origins[writeSlot][1] = tagY
        writeSlot = (writeSlot + 1) % pbos.size
        pending++
        return true
    }

    /**
     * Collects the oldest completed read into [dst].
     *
     * @return the [x, y] tag the data belongs to, or null when nothing is ready. Null
     *         is the normal case on most frames, not an error.
     */
    fun collect(dst: ByteArray): IntArray? {
        if (pending == 0) return null
        val slot = ((writeSlot - pending) % pbos.size + pbos.size) % pbos.size
        val fence = fences[slot] ?: return null
        val status = GLES30.glClientWaitSync(fence, 0, 0)
        if (status != GLES30.GL_ALREADY_SIGNALED && status != GLES30.GL_CONDITION_SATISFIED) {
            return null
        }
        GLES30.glDeleteSync(fence)
        fences[slot] = null

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[slot])
        val mapped = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER, 0, byteCount, GLES30.GL_MAP_READ_BIT,
        ) as? ByteBuffer
        if (mapped != null) {
            mapped.order(ByteOrder.nativeOrder())
            mapped.get(dst, 0, minOf(dst.size, byteCount))
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        pending--
        return if (mapped != null) origins[slot] else null
    }

    fun release() {
        GLES30.glDeleteBuffers(pbos.size, pbos, 0)
        for (i in fences.indices) fences[i]?.let { GLES30.glDeleteSync(it) }
    }
}
