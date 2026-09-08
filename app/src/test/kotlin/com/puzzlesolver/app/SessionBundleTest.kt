package com.puzzlesolver.app

import com.puzzlesolver.app.record.SessionBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * The on-phone half of collecting a run.
 *
 * What makes this worth pinning is that both of its exclusions fail silently and in
 * opposite directions. Letting `sessions/` in produces a zip that is hundreds of
 * megabytes and that the share sheet will accept and then fail to deliver, which looks
 * like a network problem. Letting `exports/` in makes each press include every previous
 * press, so the bundle doubles each time and nothing about the first one looks wrong.
 */
class SessionBundleTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(root: File, path: String, content: String = "x") {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private fun entries(zip: File): List<String> =
        ZipFile(zip).use { z -> z.entries().toList().map { it.name }.sorted() }

    @Test
    fun `gathers the run, keeping the paths a reader needs to make sense of it`() {
        val root = temp.newFolder("files")
        file(root, "canvas-dump.png")
        file(root, "gems/gem-0001.ppm.gz")
        file(root, "gems/gems-log.txt")
        file(root, "logs/run-0001.txt")

        val bundle = SessionBundle.write(root, File(temp.newFolder("out"), "b.zip"))

        assertEquals(4, bundle.entries)
        assertEquals(
            listOf("canvas-dump.png", "gems/gem-0001.ppm.gz", "gems/gems-log.txt", "logs/run-0001.txt"),
            entries(bundle.file),
        )
    }

    @Test
    fun `leaves AR recordings behind and says how many`() {
        val root = temp.newFolder("files")
        file(root, "gems/gem-0001.ppm.gz")
        file(root, "sessions/scan-0001.mp4")
        file(root, "sessions/scan-0002.mp4")

        val bundle = SessionBundle.write(root, File(temp.newFolder("out"), "b.zip"))

        assertEquals(1, bundle.entries)
        assertEquals("their absence has to be reported, not merely true", 2, bundle.skipped)
        assertTrue(bundle.describe().contains("2 AR recording"))
    }

    @Test
    fun `does not fold earlier exports into the next one`() {
        val root = temp.newFolder("files")
        file(root, "logs/run-0001.txt")
        file(root, "exports/puzzlesolver-0001.zip", "a previous bundle")

        val bundle = SessionBundle.write(root, File(root, "exports/puzzlesolver-0002.zip"))

        assertEquals(listOf("logs/run-0001.txt"), entries(bundle.file))
    }

    @Test
    fun `an empty run reports nothing rather than an empty zip that looks like a run`() {
        val root = temp.newFolder("files")

        val bundle = SessionBundle.write(root, File(temp.newFolder("out"), "b.zip"))

        assertEquals(0, bundle.entries)
    }

    @Test
    fun `each export takes the next number rather than overwriting the last`() {
        val exports = temp.newFolder("exports")

        val first = SessionBundle.nextDestination(exports)
        first.writeText("")
        val second = SessionBundle.nextDestination(exports)

        assertEquals("puzzlesolver-0001.zip", first.name)
        assertEquals("puzzlesolver-0002.zip", second.name)
    }
}
