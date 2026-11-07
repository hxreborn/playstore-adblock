package eu.hxreborn.gplayadblock.discovery

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TargetCacheTest {
    @get:Rule
    val dataDir = TemporaryFolder()

    private val targetVersion = 85244130L
    private val moduleVersion = 10200L
    private val key =
        TargetCache.Key(
            targetVersionCode = targetVersion,
            targetLastUpdateTime = 1_793_000_000_000L,
            moduleVersionCode = moduleVersion,
            moduleApkPath = "/data/app/module-install/base.apk",
        )

    @Test
    fun `a stored target set survives the round trip unchanged`() {
        TargetCache.store(root(), key, RESOLVED)

        assertEquals(RESOLVED, TargetCache.load(root(), key))
    }

    @Test
    fun `a terminal failure is remembered so it is not rediscovered every launch`() {
        TargetCache.store(
            root(),
            key,
            ResolvedTargets.Missing("no anchor"),
        )

        val loaded = TargetCache.load(root(), key)

        assertEquals(ResolvedTargets.Missing("no anchor"), loaded)
    }

    @Test
    fun `a retryable failure is never written so the next launch resolves again`() {
        TargetCache.store(
            root(),
            key,
            ResolvedTargets.Missing("native library missing", retryable = true),
        )

        assertNull(TargetCache.load(root(), key))
    }

    @Test
    fun `a target set stored for another Play Store build is not reused`() {
        TargetCache.store(root(), key, RESOLVED)

        assertNull(TargetCache.load(root(), key.copy(targetVersionCode = targetVersion + 100)))
    }

    @Test
    fun `a target set stored by another module build is not reused`() {
        TargetCache.store(root(), key, RESOLVED)

        assertNull(TargetCache.load(root(), key.copy(moduleVersionCode = moduleVersion + 1)))
    }

    @Test
    fun `storing a new target set drops the entry it supersedes`() {
        TargetCache.store(root(), key, RESOLVED)
        TargetCache.store(root(), key.copy(targetVersionCode = targetVersion + 100), RESOLVED)

        val remaining = cacheDirectory().list().orEmpty().toList()
        assertEquals(listOf("targets-${targetVersion + 100}-$moduleVersion.json"), remaining)
    }

    @Test
    fun `a corrupt entry is ignored rather than crashing the module`() {
        TargetCache.store(root(), key, RESOLVED)
        File(cacheDirectory(), "targets-$targetVersion-$moduleVersion.json").writeText("{ not json")

        assertNull(TargetCache.load(root(), key))
    }

    @Test
    fun `loading before anything was stored reports no entry`() {
        assertNull(TargetCache.load(root(), key))
    }

    @Test
    fun `every resolved role is written, so a new target cannot be dropped silently`() {
        val everyRolePresent =
            RESOLVED.copy(suggestionFailure = "recorded", spotlightFailure = "recorded")
        TargetCache.store(root(), key, everyRolePresent)
        val stored = File(cacheDirectory(), "targets-$targetVersion-$moduleVersion.json").readText()

        val unwritten =
            ResolvedTargets.Resolved::class.java.declaredFields
                .map { field -> field.name }
                .filterNot { name -> name == "\$stable" || name.contains("$") }
                .filterNot { name -> stored.contains("\"$name\"") }
        assertTrue("roles missing from the cache entry: $unwritten", unwritten.isEmpty())
    }

    @Test
    fun `an optional suggestion target is preserved when absent`() {
        val withoutSuggestion = RESOLVED.copy(suggestion = null, suggestionFailure = "no candidate")
        TargetCache.store(root(), key, withoutSuggestion)

        assertEquals(withoutSuggestion, TargetCache.load(root(), key))
    }

    @Test
    fun `an optional spotlight target is preserved when absent`() {
        val withoutSpotlight = RESOLVED.copy(spotlight = null, spotlightFailure = "no candidate")
        TargetCache.store(root(), key, withoutSpotlight)

        assertEquals(withoutSpotlight, TargetCache.load(root(), key))
    }

    @Test
    fun `a target set stored before the module was reinstalled is not reused`() {
        TargetCache.store(root(), key, RESOLVED)

        val reinstalled = key.copy(moduleApkPath = "/data/app/module-reinstall/base.apk")
        assertNull(TargetCache.load(root(), reinstalled))
    }

    @Test
    fun `an entry keyed by the module package update time is resolved again`() {
        TargetCache.store(root(), key, RESOLVED)
        val file = File(cacheDirectory(), "targets-$targetVersion-$moduleVersion.json")
        val entry = JSONObject(file.readText())
        val storedKey = entry.getJSONObject("key")
        storedKey.remove("moduleApkPath")
        storedKey.put("moduleLastUpdateTime", 1_793_100_000_000L)
        file.writeText(entry.toString())

        assertNull(TargetCache.load(root(), key))
    }

    @Test
    fun `a target set stored before Play Store was reinstalled is not reused`() {
        TargetCache.store(root(), key, RESOLVED)

        val reinstalled = key.copy(targetLastUpdateTime = key.targetLastUpdateTime + 1)
        assertNull(TargetCache.load(root(), reinstalled))
    }

    @Test
    fun `an entry without a stored key is resolved again`() {
        TargetCache.store(root(), key, RESOLVED)
        val file = File(cacheDirectory(), "targets-$targetVersion-$moduleVersion.json")
        val entry = JSONObject(file.readText())
        entry.remove("key")
        file.writeText(
            entry
                .put("targetVersionCode", targetVersion)
                .put("moduleVersionCode", moduleVersion)
                .toString(),
        )

        assertNull(TargetCache.load(root(), key))
    }

    private fun root(): String = dataDir.root.absolutePath

    private fun cacheDirectory(): File = File(dataDir.root, "files/playstore-adblock")

    private companion object {
        val RESOLVED = SampleTargets.RESOLVED
    }
}
