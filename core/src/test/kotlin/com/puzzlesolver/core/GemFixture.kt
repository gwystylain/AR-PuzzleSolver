package com.puzzlesolver.core

import com.puzzlesolver.core.image.CanvasView
import com.puzzlesolver.core.image.ChromaImage
import com.puzzlesolver.core.image.GrayImage

/**
 * The real gem wall, as pixels, shared by every test that wants it.
 *
 * A crop of `testVideos/Gems/VID20260814182431.mp4` — one of the two clips shot at a
 * reduced exposure, which are the only ones where the rings are visible at all.
 *
 * Loaded through luma plus BT.601 chroma rather than straight RGB on purpose: that round
 * trip is what the capture path does, and any hue the encoding loses would be lost on
 * device too. Testing against raw RGB would flatter the classifier by removing a step it
 * has to survive.
 */
object GemFixture {

    /** Lattice pitch measured in the source frame, in pixels. Unchanged by the crop. */
    const val PITCH = 165.2f

    /**
     * Gem centres in fixture coordinates with the three rings read by eye at 8x zoom,
     * outermost first. A null zone is one the labeller would not commit to.
     */
    val LABELLED = listOf(
        Labelled(85.7f, 92.0f, "red", "yellow", "red"),
        Labelled(264.5f, 102.7f, "purple", "blue", "blue"),
        Labelled(85.8f, 272.5f, "red", "yellow", null),
        Labelled(261.4f, 281.2f, "red", "green", "green"),
        Labelled(430.5f, 287.0f, "yellow", "blue", "blue"),
        Labelled(85.6f, 445.6f, "yellow", "blue", "blue"),
        Labelled(257.6f, 451.8f, "blue", "blue", null),
        Labelled(422.7f, 458.9f, "red", "green", "green"),
        Labelled(85.0f, 612.4f, "blue", "purple", "purple"),
        Labelled(253.9f, 617.4f, "blue", "yellow", "red"),
        Labelled(415.3f, 624.9f, "blue", "blue", "purple"),
    )

    /**
     * Every gem centre in the crop, including the ones whose rings were not labelled.
     *
     * Separate from [LABELLED] because the two answer different questions. That list is
     * ground truth for *colour* and only holds gems a human would commit to; this one is
     * ground truth for *position* and has to be complete, or a detector finding a real
     * gem would be marked down as inventing one.
     */
    val POSITIONS = floatArrayOf(
        85.7f, 92.0f,
        264.5f, 102.7f,
        437.7f, 108.2f,      // rings ambiguous by eye, so absent from LABELLED
        85.8f, 272.5f,
        261.4f, 281.2f,
        430.5f, 287.0f,
        85.6f, 445.6f,
        257.6f, 451.8f,
        422.7f, 458.9f,
        591.0f, 471.0f,      // near the right edge, rings not labelled
        85.0f, 612.4f,
        253.9f, 617.4f,
        415.3f, 624.9f,
        581.0f, 640.0f,      // near the right edge, rings not labelled
    )

    class Labelled(
        val x: Float,
        val y: Float,
        val outer: String,
        val middle: String,
        val centre: String?,
    )

    /**
     * The same wall at the exposure the app actually shipped to it, on 2026-08-20.
     *
     * A crop of the first frame of that run's capture burst, straight off the phone. It
     * is here because the run failed in a way nothing in the test suite could see: the
     * camera sat two stops brighter than the LED-wall preset, every gem's inner rings
     * bloomed into each other, and the reader named the blend -- red over green reads a
     * confident yellow, green over blue a confident cyan. Ninety-two percent of the
     * middle rings in that run came back one of those two, so the targets the user had
     * typed in could not match anywhere on the wall.
     *
     * What makes it worth keeping is the part that was invisible rather than the part
     * that was wrong. The old washed-out figure counted *desaturated* texels, and a
     * double-clipped texel is vividly saturated, so the frame scored 0.19 -- under every
     * threshold the app had -- while the HUD reported "no match in view" and the
     * auto-exposure loop called itself settled.
     */
    const val BLOWN_PITCH = 136.9f

    /**
     * Gem centres in the blown crop, as the scanner found them on the day.
     *
     * Positions only. There is no colour ground truth here and there cannot be: at this
     * exposure the inner rings are not in the image to be labelled, which is the whole
     * point of the fixture.
     */
    val BLOWN_POSITIONS = floatArrayOf(
        178.5f, 21.9f,
        321.7f, 28.0f,
        458.4f, 35.8f,
        180.3f, 168.6f,
        321.3f, 173.2f,
        456.7f, 179.0f,
        184.5f, 309.0f,
        321.4f, 312.7f,
        453.9f, 315.1f,
        40.1f, 447.2f,
        185.0f, 446.4f,
        324.0f, 446.6f,
        456.6f, 445.7f,
        463.0f, 575.5f,
        330.1f, 579.3f,
        187.9f, 583.6f,
        40.6f, 587.2f,
    )

    fun load(): CanvasView = load("gems-wall.ppm")

    /** The 2026-08-20 frame. See [BLOWN_PITCH]. */
    fun loadBlown(): CanvasView = load("gems-wall-blown.ppm")

    private fun load(resource: String): CanvasView {
        val stream = GemFixture::class.java.classLoader!!.getResourceAsStream(resource)
            ?: error("fixture missing: " + resource)
        stream.use { input ->
            fun token(): String {
                val sb = StringBuilder()
                var c = input.read()
                while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) c = input.read()
                if (c == '#'.code) {
                    while (c != '\n'.code) c = input.read()
                    return token()
                }
                while (c > 0 && c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) {
                    sb.append(c.toChar())
                    c = input.read()
                }
                return sb.toString()
            }
            require(token() == "P6")
            val w = token().toInt()
            val h = token().toInt()
            token()                                     // maxval
            val raw = ByteArray(w * h * 3)
            var read = 0
            while (read < raw.size) {
                val n = input.read(raw, read, raw.size - read)
                if (n <= 0) break
                read += n
            }
            require(read == raw.size) { "short fixture: $read of ${raw.size}" }

            val luma = GrayImage(w, h)
            val chroma = ChromaImage(w, h)
            for (i in 0 until w * h) {
                val r = (raw[i * 3].toInt() and 0xFF).toFloat()
                val g = (raw[i * 3 + 1].toInt() and 0xFF).toFloat()
                val b = (raw[i * 3 + 2].toInt() and 0xFF).toFloat()
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                val cb = -0.168736f * r - 0.331264f * g + 0.5f * b + 128f
                val cr = 0.5f * r - 0.418688f * g - 0.081312f * b + 128f
                luma.data[i] = y.toInt().coerceIn(0, 255).toByte()
                chroma.data[i * 2] = cb.toInt().coerceIn(0, 255).toByte()
                chroma.data[i * 2 + 1] = cr.toInt().coerceIn(0, 255).toByte()
            }
            return CanvasView(luma, chroma)
        }
    }
}
