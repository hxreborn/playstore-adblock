package eu.hxreborn.gplayadblock.discovery

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class ResolverFingerprintTest {
    @Before
    fun requireReleaseFixtures() {
        val unavailable = ReleaseFixtures.unavailableReason()
        assumeTrue(unavailable, unavailable == null)
    }

    @Test
    fun `every release fixture resolves to its recorded target set`() {
        val drifted =
            ReleaseFixtures.all().mapNotNull { fixture ->
                val recorded = fixture.recordedFingerprint() ?: return@mapNotNull null
                TargetFingerprints
                    .firstDifference(recorded, fixture.fingerprint())
                    ?.let { difference -> "${fixture.release}: $difference" }
            }
        assertTrue(
            "the resolver selects different targets than recorded on:\n" +
                drifted.joinToString("\n") +
                "\n\nRegenerate the affected fingerprints only after reviewing what moved.",
            drifted.isEmpty(),
        )
    }

    @Test
    fun `every release fixture has a recorded target set`() {
        val unrecorded =
            ReleaseFixtures.all().filter { fixture -> fixture.recordedFingerprint() == null }
        assertTrue(
            "no recorded fingerprint for ${unrecorded.joinToString()}. Record one under " +
                "app/src/test/resources/fingerprints/ once the release has been verified.",
            unrecorded.isEmpty(),
        )
    }
}
