package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.discovery.HookGroup
import eu.hxreborn.gplayadblock.discovery.ReleaseFixtures
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class HookInputLayoutTest {
    @Before
    fun requireReleaseFixtures() {
        val unavailable = ReleaseFixtures.unavailableReason()
        assumeTrue(unavailable, unavailable == null)
    }

    @Test
    fun `every release fixture places arguments where the filters read them`() {
        val mismatches =
            ReleaseFixtures.all().flatMap { fixture ->
                val targets =
                    fixture.resolveTargets() as? ResolvedTargets.Resolved
                        ?: return@flatMap emptyList()
                HookGroup.inputMismatches(targets).flatMap { (group, reasons) ->
                    reasons.map { reason -> "${fixture.release} [${group.label}]: $reason" }
                }
            }
        assertTrue(
            "hook filters read arguments by index, and these releases resolve to methods whose " +
                "arguments are not where the filter expects them:\n" +
                mismatches.joinToString("\n"),
            mismatches.isEmpty(),
        )
    }
}
