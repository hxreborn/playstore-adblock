package eu.hxreborn.gplayadblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityMetadataTest {
    private val metadata = CompatibilityMetadata.load()

    @Test
    fun `every release carries the fields the matrix and the monitor read`() {
        val incomplete =
            metadata.releases.mapNotNull { release ->
                val missing = REQUIRED_FIELDS.filterNot { field -> release.containsKey(field) }
                "${release["version_code"]} is missing $missing".takeIf { missing.isNotEmpty() }
            }
        assertTrue(incomplete.joinToString("\n"), incomplete.isEmpty())
    }

    @Test
    fun `every status is one the matrix knows how to render`() {
        val unknown =
            metadata.releases
                .filterNot { release -> release["status"] in STATUSES }
                .map { release -> "${release["version_code"]} has status ${release["status"]}" }
        assertTrue("$unknown, expected one of $STATUSES", unknown.isEmpty())
    }

    @Test
    fun `the supported releases are exactly the set the module warns from`() {
        val supported =
            metadata.releases
                .filter { release -> release["status"] == "supported" }
                .map { release -> ValidatedReleases.releaseKey(versionCode(release)) }
                .toSet()
        assertEquals(
            "play-store-compatibility.yaml and ValidatedReleases disagree. A release joins that " +
                "set only once it is recorded supported here.",
            supported,
            ValidatedReleases.keys,
        )
    }

    @Test
    fun `a release that is not supported stays out of the validated set`() {
        val leaked =
            metadata.releases
                .filterNot { release -> release["status"] == "supported" }
                .filter { release ->
                    ValidatedReleases.releaseKey(versionCode(release)) in ValidatedReleases.keys
                }.map { release -> release["version_code"] }
        assertTrue("$leaked are validated without being supported", leaked.isEmpty())
    }

    @Test
    fun `version codes are unique and ordered so the file appends cleanly`() {
        val codes = metadata.releases.map { release -> release["version_code"] as Int }
        assertEquals("version codes must be unique", codes.distinct(), codes)
        assertEquals("releases must be ordered by version code", codes.sorted(), codes)
    }

    @Test
    fun `every test date is a real date`() {
        val malformed =
            metadata.releases
                .filterNot { release -> release["tested_on"] is java.util.Date }
                .map { release ->
                    "${release["version_code"]} tested_on is ${release["tested_on"]}"
                }
        assertTrue(malformed.joinToString("\n"), malformed.isEmpty())
    }

    @Test
    fun `every unsupported release states why`() {
        val unexplained =
            metadata.releases
                .filter { release -> release["status"] == "unsupported" }
                .filter { release -> (release["failure"] as? String).isNullOrBlank() }
                .map { release -> release["version_code"] }
        assertTrue("$unexplained are unsupported without a recorded failure", unexplained.isEmpty())
    }

    @Test
    fun `every major with a recorded launch date is one the releases use`() {
        val majors =
            metadata.releases
                .map { release -> (release["version_name"] as String).substringBefore('.') }
                .toSet()
        val unused = metadata.majorReleases.keys - majors
        assertTrue("$unused have launch dates but no release entry", unused.isEmpty())
    }

    private fun versionCode(release: Map<*, *>): Long = (release["version_code"] as Int).toLong()

    private companion object {
        val REQUIRED_FIELDS =
            listOf("version_name", "version_code", "status", "tested_on", "module_version_code")
        val STATUSES = setOf("supported", "partial", "unsupported", "untested")
    }
}
