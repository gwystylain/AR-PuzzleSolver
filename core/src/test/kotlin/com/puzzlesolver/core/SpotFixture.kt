package com.puzzlesolver.core

import com.puzzlesolver.core.image.RgbImage
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * Reads photos into [RgbImage]s, from the test resources or a directory.
 *
 * The Spot fixtures are JPEGs rather than the PPMs the other colour fixtures are, because
 * they are photographs of a picture and nothing in them is a measured constant: twelve
 * hundred-kilobyte JPEGs carry everything the test needs, where the same frames as PPMs
 * would be twenty-five megabytes.
 */
object SpotFixture {

    /** `spot/set1/1.jpg`, `2.jpg`, ... for as many as there are. */
    fun set(name: String): List<RgbImage> {
        val loader = SpotFixture::class.java.classLoader!!
        val out = ArrayList<RgbImage>()
        var i = 1
        while (true) {
            val stream = loader.getResourceAsStream("spot/$name/$i.jpg") ?: break
            out.add(read(stream))
            i++
        }
        require(out.isNotEmpty()) { "fixture set $name is missing from the test resources" }
        return out
    }

    fun read(file: File): RgbImage = read(file.inputStream())

    fun read(stream: InputStream): RgbImage {
        val image = stream.use { ImageIO.read(it) } ?: error("not an image ImageIO can read")
        val out = RgbImage(image.width, image.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val p = image.getRGB(x, y)
                out.set(x, y, (p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            }
        }
        return out
    }

    fun toBufferedImage(image: RgbImage): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        out.setRGB(0, 0, image.width, image.height, image.toArgb(), 0, image.width)
        return out
    }
}
