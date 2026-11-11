package eu.hxreborn.gplayadblock.discovery

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OptionalUiResolverTest {
    @Test
    fun `optional UI fingerprints resolve on an explicitly supplied Play Store APK`() {
        val configuredPath = System.getProperty(APK_PROPERTY)
        assumeTrue("set -D$APK_PROPERTY=/path/to/base.apk", !configuredPath.isNullOrBlank())
        val path = requireNotNull(configuredPath)
        assumeTrue("Play Store APK not found: $path", File(path).isFile)
        assumeTrue("host DexKit native library unavailable", !DexKitResolver.hasNativeLoadFailure())

        val resolved = DexKitResolver.resolve(listOf(path))
        assertTrue("full target resolution failed: $resolved", resolved is ResolvedTargets.Resolved)
        resolved as ResolvedTargets.Resolved
        assertNotNull(resolved.manageAppsFailure, resolved.manageApps)
        val manageApps = requireNotNull(resolved.manageApps)
        assertTrue(
            "play protect rows=${manageApps.playProtectRowClassNames}",
            manageApps.playProtectRowClassNames.isNotEmpty(),
        )
        assertNotNull("visual element type field", manageApps.veMetadataTypeField)
        assertNotNull(resolved.persistentNavFailure, resolved.persistentNav)
        assertNotNull(resolved.accountMenuFailure, resolved.accountMenu)
        assertNotNull(resolved.selfUpdateFailure, resolved.selfUpdate)
    }

    private companion object {
        const val APK_PROPERTY = "gplayadblock.playStoreApk"
    }
}
