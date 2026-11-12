package eu.hxreborn.gplayadblock.settings.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestartResultTest {
    @Test
    fun `an unset broadcast result waits for a reply or a fresh start`() =
        runTest {
            val result =
                withTimeoutOrNull(100) {
                    awaitRestartResult(
                        CompletableDeferred(RestartProtocol.RESULT_UNSET),
                        CompletableDeferred(),
                        CompletableDeferred(),
                    )
                }
            assertNull(result)
        }

    @Test
    fun `a callback reply waits for the broadcast to finish`() =
        runTest {
            val result =
                withTimeoutOrNull(100) {
                    awaitRestartResult(
                        CompletableDeferred(),
                        CompletableDeferred(RestartProtocol.RESULT_READY),
                        CompletableDeferred(),
                    )
                }
            assertNull(result)
        }

    @Test
    fun `an unset broadcast result returns the cache pending callback reply`() =
        runTest {
            assertEquals(
                RestartProtocol.RESULT_CACHE_PENDING,
                awaitRestartResult(
                    CompletableDeferred(RestartProtocol.RESULT_UNSET),
                    CompletableDeferred(RestartProtocol.RESULT_CACHE_PENDING),
                    CompletableDeferred(),
                ),
            )
        }

    @Test
    fun `an unset broadcast result returns restarted without reply when freshStart completes`() =
        runTest {
            assertEquals(
                RestartProtocol.RESULT_RESTARTED_WITHOUT_REPLY,
                awaitRestartResult(
                    CompletableDeferred(RestartProtocol.RESULT_UNSET),
                    CompletableDeferred(),
                    CompletableDeferred(Unit),
                ),
            )
        }

    @Test
    fun `a ready callback reply is selected before an already completed freshStart`() =
        runTest {
            assertEquals(
                RestartProtocol.RESULT_READY,
                awaitRestartResult(
                    CompletableDeferred(RestartProtocol.RESULT_UNSET),
                    CompletableDeferred(RestartProtocol.RESULT_READY),
                    CompletableDeferred(Unit),
                ),
            )
        }

    @Test
    fun `a Busy broadcast result returns without waiting for a callback reply`() =
        runTest {
            assertEquals(
                RestartProtocol.RESULT_BUSY,
                awaitRestartResult(
                    CompletableDeferred(RestartProtocol.RESULT_BUSY),
                    CompletableDeferred(),
                    CompletableDeferred(),
                ),
            )
        }
}
