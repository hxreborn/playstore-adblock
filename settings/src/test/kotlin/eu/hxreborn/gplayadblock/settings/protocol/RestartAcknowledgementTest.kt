package eu.hxreborn.gplayadblock.settings.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestartAcknowledgementTest {
    @Test
    fun `an absent receiver never counts as a successful restart`() =
        runTest {
            val result =
                withTimeoutOrNull(100) {
                    awaitRestartAcknowledgement(
                        CompletableDeferred(RestartProtocol.RESULT_NO_RECEIVER),
                        CompletableDeferred(),
                        CompletableDeferred(),
                    )
                }
            assertNull(result)
        }

    @Test
    fun `an acknowledgement without process completion never counts as success`() =
        runTest {
            val result =
                withTimeoutOrNull(100) {
                    awaitRestartAcknowledgement(
                        CompletableDeferred(),
                        CompletableDeferred(RestartProtocol.RESULT_READY),
                        CompletableDeferred(),
                    )
                }
            assertNull(result)
        }

    @Test
    fun `acknowledged cleanup failure still confirms the process restart`() =
        runTest {
            assertEquals(
                RestartProtocol.RESULT_CACHE_PENDING,
                awaitRestartAcknowledgement(
                    CompletableDeferred(RestartProtocol.RESULT_NO_RECEIVER),
                    CompletableDeferred(RestartProtocol.RESULT_CACHE_PENDING),
                    CompletableDeferred(),
                ),
            )
        }

    @Test
    fun `a Play Store started after the request counts as a restart without a reply`() =
        runTest {
            assertEquals(
                RestartProtocol.RESULT_RESTARTED_WITHOUT_REPLY,
                awaitRestartAcknowledgement(
                    CompletableDeferred(RestartProtocol.RESULT_NO_RECEIVER),
                    CompletableDeferred(),
                    CompletableDeferred(Unit),
                ),
            )
        }

    @Test
    fun `a reply wins over a fresh start`() =
        runTest {
            assertEquals(
                RestartProtocol.RESULT_READY,
                awaitRestartAcknowledgement(
                    CompletableDeferred(RestartProtocol.RESULT_NO_RECEIVER),
                    CompletableDeferred(RestartProtocol.RESULT_READY),
                    CompletableDeferred(Unit),
                ),
            )
        }

    @Test
    fun `a busy receiver returns without a success acknowledgement`() =
        runTest {
            assertEquals(
                RestartProtocol.RESULT_BUSY,
                awaitRestartAcknowledgement(
                    CompletableDeferred(RestartProtocol.RESULT_BUSY),
                    CompletableDeferred(),
                    CompletableDeferred(),
                ),
            )
        }
}
