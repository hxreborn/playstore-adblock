package eu.hxreborn.gplayadblock.settings.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class RootPlayStoreScriptExecutionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun stub(
        name: String,
        script: String,
    ) {
        temporary.root.resolve(name).apply {
            writeText(
                "#!/bin/sh\nprintf 'HOST_STUB $name %s\\n' \"\$*\" >> \"\$TEST_LOG\"\n$script\n",
            )
            setExecutable(true)
        }
    }

    private fun execute(script: String): Pair<Int, String> {
        val log = temporary.root.resolve("calls")
        val process =
            ProcessBuilder("/bin/sh", "-c", script)
                .apply {
                    environment()["PATH"] =
                        temporary.root.path + File.pathSeparator + System.getenv("PATH")
                    environment()["TEST_LOG"] = log.path
                    redirectErrorStream(true)
                    redirectOutput(temporary.root.resolve("output"))
                }.start()
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            return process.exitValue() to log.readText()
        } finally {
            process.destroyForcibly()
        }
    }

    private fun prepare(
        sessions: String = "Active install sessions:",
        activity: String = "no activities",
    ) {
        stub("ls", "exit 0")
        stub(
            "dumpsys",
            "case \"\$1\" in package) printf '%s\\n' '$sessions';; activity) printf '%s\\n' '$activity';; esac",
        )
        stub("am", "exit 0")
        stub("nsenter", "exit 0")
    }

    @Test
    fun `an active install refuses stop and cleanup`() {
        prepare("Active install sessions:\n  installerPackageName=com.android.vending active=true")
        val (code, log) = execute(RootPlayStoreRestarter.stopScript(10, true))
        assertEquals(RootPlayStoreRestarter.EXIT_BUSY, code)
        assertFalse(log.contains("HOST_STUB am"))
        assertFalse(log.contains("HOST_STUB nsenter"))
    }

    @Test
    fun `a live purchase refuses stop and cleanup`() {
        prepare(activity = "com.android.vending/com.google.android.finsky.billing.PurchaseActivity")
        val (code, log) = execute(RootPlayStoreRestarter.stopScript(10, true))
        assertEquals(RootPlayStoreRestarter.EXIT_BUSY, code)
        assertFalse(log.contains("HOST_STUB am"))
        assertFalse(log.contains("HOST_STUB nsenter"))
    }

    @Test
    fun `the cleanup script checks cache and stream cache paths for links before removal`() {
        val script = RootPlayStoreRestarter.stopScript(10, true)
        val cache = "/data/user/10/com.android.vending/cache"
        assertTrue(script.contains("[ ! -L $cache ] && [ ! -L $cache/streamdatastore ] && rm -rf"))
    }

    @Test
    fun `a failed stream cache removal after force stop exits with EXIT_CACHE_PENDING`() {
        prepare()
        stub("nsenter", "case \"\$*\" in *'rm -rf'*) exit 1;; esac")
        val (code, log) = execute(RootPlayStoreRestarter.stopScript(10, true))
        assertEquals(RootPlayStoreRestarter.EXIT_CACHE_PENDING, code)
        assertTrue(log.contains("HOST_STUB am force-stop --user 10 com.android.vending"))
        assertTrue(log.contains("rm -f $CRASH_GUARD && [ ! -e $CRASH_GUARD ]"))
    }

    @Test
    fun `a failed crash guard removal exits before stream cache cleanup`() {
        prepare()
        stub("nsenter", "case \"\$*\" in *'rm -f '*) exit 1;; esac")
        val (code, log) = execute(RootPlayStoreRestarter.stopScript(10, true))
        assertEquals(RootPlayStoreRestarter.EXIT_CRASH_GUARD_KEPT, code)
        assertFalse(log.contains("rm -rf"))
    }

    @Test
    fun `without a cache clear the script requests crash guard removal only`() {
        prepare()
        val (code, log) = execute(RootPlayStoreRestarter.stopScript(10, false))
        assertEquals(0, code)
        assertTrue(log.contains("rm -f $CRASH_GUARD && [ ! -e $CRASH_GUARD ]"))
        assertFalse(log.contains("rm -rf"))
    }

    @Test
    fun `a zero exit from am with an error message is a failed reopen`() {
        stub("am", "printf 'Error: activity not found\\n'; exit 0")
        stub("pidof", "printf '1234\\n'")
        val (code, log) = execute(RootPlayStoreRestarter.startScript(10))
        assertEquals(RootPlayStoreRestarter.EXIT_FAILED, code)
        assertFalse(log.contains("HOST_STUB pidof"))
    }

    private companion object {
        const val CRASH_GUARD =
            "/data/user/10/com.android.vending/${RestartProtocol.CRASH_GUARD_PATH}"
    }

    private fun prepareOwners() {
        stub(
            "stat",
            "case \"\$3\" in /data/user/10/*) echo 1010140;; /proc/1234) echo 10140;; " +
                "/proc/5678) echo 1010140;; *) exit 1;; esac",
        )
    }

    @Test
    fun `a successful launch and a process owned by the target user exit with zero`() {
        prepareOwners()
        stub("am", "printf 'Status: ok\\n'")
        stub("pidof", "printf '1234 5678\\n'")
        val (code, log) = execute(RootPlayStoreRestarter.startScript(10))
        assertEquals(0, code)
        assertTrue(log.contains("HOST_STUB pidof com.android.vending"))
        assertTrue(log.contains("HOST_STUB stat -c %u /proc/5678"))
    }

    @Test
    fun `a Play Store process of another user does not confirm reopen`() {
        prepareOwners()
        stub("am", "printf 'Status: ok\\n'")
        stub("pidof", "printf '1234\\n'")
        val (code, _) = execute(RootPlayStoreRestarter.startScript(10))
        assertEquals(RootPlayStoreRestarter.EXIT_FAILED, code)
    }
}
