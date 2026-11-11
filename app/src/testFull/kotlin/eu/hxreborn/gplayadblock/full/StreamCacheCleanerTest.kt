package eu.hxreborn.gplayadblock.full

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StreamCacheCleanerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `a symlinked user directory permits clearing the feed cache`() {
        val data = temporary.newFolder("data")
        val user = temporary.root.resolve("user")
        Files.createSymbolicLink(user.toPath(), data.toPath())
        val cache = user.resolve("com.android.vending/cache")
        val stream = cache.resolve("streamdatastore").apply { mkdirs() }
        stream.resolve("page").writeText("cached")
        cache.resolve("unrelated").writeText("keep")

        StreamCacheCleaner.clear(stream)

        assertTrue(stream.isDirectory)
        assertEquals(emptyList<String>(), stream.list()!!.toList())
        assertEquals("keep", cache.resolve("unrelated").readText())
    }

    @Test
    fun `a feed cache that is itself a symlink is refused`() {
        val outside = temporary.newFolder("outside")
        outside.resolve("page").writeText("keep")
        val stream = temporary.root.resolve("streamdatastore")
        Files.createSymbolicLink(stream.toPath(), outside.toPath())

        assertThrows(IOException::class.java) { StreamCacheCleaner.clear(stream) }

        assertEquals("keep", outside.resolve("page").readText())
        assertTrue(Files.isSymbolicLink(stream.toPath()))
    }

    @Test
    fun `links within the feed cache are removed without following them`() {
        val outside = temporary.newFolder("outside")
        outside.resolve("page").writeText("keep")
        val stream = temporary.newFolder("streamdatastore")
        Files.createSymbolicLink(stream.resolve("link").toPath(), outside.toPath())
        Files.createSymbolicLink(
            stream.resolve("dangling").toPath(),
            outside.resolve("missing").toPath(),
        )

        StreamCacheCleaner.clear(stream)

        assertFalse(stream.resolve("link").exists())
        assertEquals(emptyList<String>(), stream.list()!!.toList())
        assertEquals("keep", outside.resolve("page").readText())
    }

    @Test
    fun `a writer adding cache entries never removes adjacent data`() {
        val stream = temporary.newFolder("streamdatastore")
        val outside = temporary.newFile("keep").apply { writeText("keep") }
        val started = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val writer =
            Thread {
                var index = 0
                do {
                    stream.resolve("page-${index++}").writeText("cached")
                    started.countDown()
                } while (stop.count != 0L)
            }
        writer.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            try {
                StreamCacheCleaner.clear(stream)
            } catch (_: IOException) {
            }
        } finally {
            stop.countDown()
            writer.join(5000)
        }
        assertFalse(writer.isAlive)
        StreamCacheCleaner.clear(stream)
        assertEquals(emptyList<String>(), stream.list()!!.toList())
        assertEquals("keep", outside.readText())
    }
}
