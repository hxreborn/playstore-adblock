package eu.hxreborn.gplayadblock.settings.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class RootCommandResultTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun restarter(command: String) =
        RootPlayStoreRestarter(temporary.root, userId = 10) { _, output ->
            ProcessBuilder("/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
        }

    @Test
    fun `unexpected su exits report failed without reopening`() =
        runTest {
            listOf(1, 6, 126, 127, 255).forEach { exitCode ->
                var exited = false
                assertEquals(
                    RestartOutcome.Failed,
                    restarter("exit $exitCode").restart(false) {
                        exited =
                            true
                    },
                )
                assertFalse(exited)
            }
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun `permission denied before the shell starts reports root unavailable`() =
        runTest {
            assertEquals(
                RestartOutcome.RootUnavailable,
                restarter("printf 'Permission denied\\n'; exit 1").restart(false) {},
            )
        }

    @Test
    fun `a command failure after the shell starts reports failed`() =
        runTest {
            assertEquals(
                RestartOutcome.Failed,
                restarter(
                    "printf 'GPLAY_ADBLOCK_SHELL_STARTED\\nPermission denied\\n'; exit 1",
                ).restart(false) {},
            )
        }

    @Test
    fun `a missing or unexecutable su reports root unavailable`() =
        runTest {
            listOf(2, 13).forEach { errno ->
                val restarter =
                    RootPlayStoreRestarter(temporary.root, userId = 10) { _, _ ->
                        throw IOException("Cannot run program su: error=$errno, failed")
                    }
                assertEquals(RestartOutcome.RootUnavailable, restarter.restart(false) {})
            }
        }

    @Test
    fun `successful stop and start commands report restarted and remove their output files`() =
        runTest {
            var commands = 0
            var exits = 0
            val restarter =
                RootPlayStoreRestarter(temporary.root, userId = 10) { script, output ->
                    commands++
                    val marker = script.lineSequence().first()
                    ProcessBuilder("/bin/sh", "-c", marker)
                        .redirectErrorStream(true)
                        .redirectOutput(output)
                        .start()
                }
            assertEquals(RestartOutcome.Restarted, restarter.restart(false) { exits++ })
            assertEquals(2, commands)
            assertEquals(1, exits)
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun `a root command timeout reports failed and destroys the process`() =
        runTest {
            lateinit var process: Process
            val restarter =
                RootPlayStoreRestarter(
                    temporary.root,
                    userId = 10,
                    timeoutSeconds = 0,
                ) { _, output ->
                    ProcessBuilder("/bin/sh", "-c", "read line")
                        .redirectErrorStream(true)
                        .redirectOutput(output)
                        .start()
                        .also { process = it }
                }
            assertEquals(RestartOutcome.Failed, restarter.restart(false) {})
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(process.isAlive)
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun `other process creation failures report failed`() =
        runTest {
            val restarter =
                RootPlayStoreRestarter(temporary.root, userId = 10) { _, _ ->
                    throw IOException("Cannot run program su: error=24, too many open files")
                }
            assertEquals(RestartOutcome.Failed, restarter.restart(false) {})
        }
}
