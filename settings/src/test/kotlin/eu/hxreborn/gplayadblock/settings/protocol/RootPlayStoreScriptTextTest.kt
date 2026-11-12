package eu.hxreborn.gplayadblock.settings.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPlayStoreScriptTextTest {
    private fun lines(clearStreamCache: Boolean = true) =
        RootPlayStoreRestarter.stopScript(userId = 10, clearStreamCache = clearStreamCache).lines()

    private fun List<String>.stepIndex(fragment: String): Int =
        indexOfFirst { it.contains(fragment) }.also { index ->
            assertTrue("missing step: $fragment", index >= 0)
        }

    @Test
    fun `install and purchase checks precede force stop in the script`() {
        val lines = lines()
        val stop = lines.stepIndex("am force-stop --user 10 ")
        val sessions = lines.stepIndex("awk '${RootPlayStoreRestarter.BLOCKING_SESSION_FILTER}'")
        assertTrue(sessions < stop)
        assertTrue(lines[sessions].endsWith("|| exit ${RootPlayStoreRestarter.EXIT_BUSY}"))
        assertTrue(lines.stepIndex(RestartProtocol.PURCHASE_ACTIVITY_PACKAGE) < stop)
    }

    @Test
    fun `cleanup script steps name the stream cache and crash guard paths without wildcards`() {
        val lines = lines()
        val guard = lines[lines.stepIndex(RestartProtocol.CRASH_GUARD_PATH)]
        val cache = lines[lines.stepIndex("cache/${RestartProtocol.STREAM_DATA_STORE_DIR}")]
        listOf(guard, cache).forEach { step ->
            assertTrue(step.startsWith("nsenter"))
            assertTrue(step.contains("/data/user/10/"))
            assertFalse("no wildcard removal", step.contains("*"))
        }
        assertFalse(cache.contains(RestartProtocol.CRASH_GUARD_PATH))
    }

    @Test
    fun `the script omits stream cache removal when clearStreamCache is false`() {
        val script = RootPlayStoreRestarter.stopScript(10, clearStreamCache = false)
        val cleanup = script.lines().let { it[it.stepIndex("nsenter")] }
        assertFalse(script.contains(RestartProtocol.STREAM_DATA_STORE_DIR))
        assertTrue(cleanup.contains(RestartProtocol.CRASH_GUARD_PATH))
    }

    @Test
    fun `probe and force stop script steps exit with EXIT_FAILED when they fail`() {
        val lines = lines()
        val failed = "|| exit ${RootPlayStoreRestarter.EXIT_FAILED}"
        assertTrue(lines.stepIndex("am force-stop") < lines.stepIndex("nsenter"))
        assertTrue(lines[lines.stepIndex("am force-stop")].endsWith(failed))
        assertTrue(lines[lines.stepIndex("dumpsys activity")].endsWith(failed))
        assertTrue(lines[lines.stepIndex("dumpsys package")].endsWith(failed))
        assertTrue(lines[lines.stepIndex("ls /data/app")].endsWith(failed))
        assertTrue(lines.stepIndex("ls /data/app") < lines.stepIndex("dumpsys package"))
        val sessionsHeaderCheck = lines[lines.stepIndex("in 'Active install sessions:'")]
        assertTrue(sessionsHeaderCheck.contains("exit ${RootPlayStoreRestarter.EXIT_FAILED}"))
    }

    @Test
    fun `crash guard and stream cache script steps use different failure exit codes`() {
        val lines = lines()
        val guard = lines.stepIndex(RestartProtocol.CRASH_GUARD_PATH)
        val cache = lines.stepIndex("cache/${RestartProtocol.STREAM_DATA_STORE_DIR}")
        assertTrue(lines.stepIndex("am force-stop") < guard)
        assertTrue(guard < cache)
        assertTrue(lines[guard].endsWith("exit ${RootPlayStoreRestarter.EXIT_CRASH_GUARD_KEPT}"))
        assertTrue(lines[cache].endsWith("exit ${RootPlayStoreRestarter.EXIT_CACHE_PENDING}"))
    }
}
