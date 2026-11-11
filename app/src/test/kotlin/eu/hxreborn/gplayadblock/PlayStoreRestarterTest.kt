package eu.hxreborn.gplayadblock

import org.junit.Assert.assertTrue
import org.junit.Test

class PlayStoreRestarterTest {
    private val lines = PlayStoreRestarter.rootScript(userId = 10).lines()

    private fun indexOf(fragment: String): Int =
        lines.indexOfFirst { it.contains(fragment) }.also { index ->
            assertTrue("missing step: $fragment", index >= 0)
        }

    @Test
    fun `root restart checks installs and purchases before stopping Play Store`() {
        val stop = indexOf("am force-stop --user 10 ")
        assertTrue(indexOf("/data/app/vmdl") < stop)
        assertTrue(indexOf("/mnt/expand/*/app/vmdl") < stop)
        assertTrue(indexOf("/data/app-staging/session_") < stop)
        assertTrue(indexOf(RestartProtocol.PURCHASE_ACTIVITY_PACKAGE) < stop)
    }

    @Test
    fun `root restart removes only the feed cache and the crash guard`() {
        val cleanup = lines[indexOf("nsenter")]
        assertTrue(cleanup.contains("/data/user/10/"))
        assertTrue(cleanup.contains("cache/${RestartProtocol.STREAM_DATA_STORE_DIR}"))
        assertTrue(cleanup.contains(RestartProtocol.CRASH_GUARD_PATH))
        assertTrue("no wildcard removal", !cleanup.contains("*"))
    }

    @Test
    fun `every root step stops the script when it fails`() {
        assertTrue(indexOf("am force-stop") < indexOf("nsenter"))
        assertTrue(lines[indexOf("am force-stop")].endsWith("|| exit 1"))
        assertTrue(lines[indexOf("dumpsys activity")].endsWith("|| exit 1"))
        assertTrue(lines[indexOf("ls /data/app")].endsWith("|| exit 1"))
        assertTrue(indexOf("ls /data/app") < indexOf("/data/app/vmdl"))
        assertTrue(lines.last().startsWith("am start") && lines.last().endsWith("|| exit 1"))
    }

    @Test
    fun `a failed cleanup starts Play Store again and still reports failure`() {
        val cleanup = lines[indexOf("nsenter")]
        assertTrue(cleanup.startsWith("if ! nsenter"))
        assertTrue(cleanup.contains("then am start"))
        assertTrue(cleanup.endsWith("exit 1; fi"))
    }
}
