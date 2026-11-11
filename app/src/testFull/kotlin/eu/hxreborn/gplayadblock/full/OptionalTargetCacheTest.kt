package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.discovery.ConstructorRef
import eu.hxreborn.gplayadblock.discovery.FieldRef
import eu.hxreborn.gplayadblock.discovery.MethodRef
import eu.hxreborn.gplayadblock.discovery.TargetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OptionalTargetCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key =
        TargetCache.Key(
            targetVersionCode = 85400940,
            targetLastUpdateTime = 1_700_000_000_000,
            moduleVersionCode = 10601,
            moduleApkPath = "/data/app/~~a/eu.hxreborn.gplayadblock-b/base.apk",
        )

    private val targets =
        OptionalTargets(
            requested = setOf(OptionalGroup.MANAGE_APPS, OptionalGroup.SELF_UPDATE),
            manageApps =
                ManageAppsTargets(
                    manageListClassName = "altd",
                    playProtectRowClassNames = listOf("altx"),
                    ratingsRowClassName = "alty",
                    contributionsRowClassName = null,
                    deviceSyncRowClassName = "alts",
                    searchLandingItemClassName = "biro",
                    unresolved = listOf("contributions row"),
                ),
            selfUpdate =
                SelfUpdateTargets(
                    schedulerMethod = MethodRef("a", "b", "boolean", listOf("int")),
                    autoUpdateMethod = null,
                ),
            failures = mapOf(OptionalGroup.SELF_UPDATE to "self-update schedulers=2"),
        )

    @Test
    fun `a badge target survives absent account action targets in the cache`() {
        val badgeOnly =
            OptionalTargets(
                requested = setOf(OptionalGroup.ACCOUNT_MENU),
                accountMenu =
                    AccountMenuTargets(
                        notificationTopBarConstructor =
                            ConstructorRef(
                                "top",
                                listOf("callback", "badge"),
                            ),
                        notificationBadgeTypeName = "badge",
                        unresolved = listOf("account action role field not unique"),
                    ),
            )
        OptionalTargetCache.store(folder.root.absolutePath, key, badgeOnly)
        assertEquals(
            badgeOnly,
            OptionalTargetCache.load(folder.root.absolutePath, key, badgeOnly.requested),
        )
    }

    @Test
    fun `stored targets load back unchanged`() {
        val dataDir = folder.root.absolutePath
        OptionalTargetCache.store(dataDir, key, targets)
        assertEquals(targets, OptionalTargetCache.load(dataDir, key, targets.requested))
    }

    @Test
    fun `a lookup that threw is not cached so the next start scans again`() {
        val dataDir = folder.root.absolutePath
        OptionalTargetCache.store(dataDir, key, targets.copy(transientFailure = true))
        assertNull(OptionalTargetCache.load(dataDir, key, targets.requested))
    }

    @Test
    fun `account and nav targets survive a round trip`() {
        val full =
            OptionalTargets(
                requested = setOf(OptionalGroup.ACCOUNT_MENU, OptionalGroup.PERSISTENT_NAV),
                accountMenu =
                    AccountMenuTargets(
                        featuresClassName = "f",
                        immutableListClassName = "l",
                        actionRoleField = FieldRef("bopr", "i"),
                        notificationTopBarConstructor = ConstructorRef("n", listOf("b", "c")),
                        notificationBadgeTypeName = "b",
                    ),
                persistentNav =
                    PersistentNavTargets(
                        navConstructor = ConstructorRef("aoky", listOf("java.util.List", "int")),
                        navItemTypeField = FieldRef("i", "t"),
                        navPrimaryCallbackField = FieldRef("aolc", "a"),
                        navSecondaryCallbackField = FieldRef("aolc", "b"),
                        navActionConstructor = ConstructorRef("aolc", listOf("x", "x")),
                        navCallbackMethod =
                            MethodRef(
                                "x",
                                "a",
                                "java.lang.Object",
                                listOf("java.lang.Object"),
                            ),
                        navHiddenInstanceField = null,
                    ),
            )
        val dataDir = folder.root.absolutePath
        OptionalTargetCache.store(dataDir, key, full)
        assertEquals(full, OptionalTargetCache.load(dataDir, key, full.requested))
    }

    @Test
    fun `a scan for fewer groups is not reused for more groups`() {
        val dataDir = folder.root.absolutePath
        OptionalTargetCache.store(dataDir, key, targets)
        assertNull(
            OptionalTargetCache.load(
                dataDir,
                key,
                targets.requested + OptionalGroup.PERSISTENT_NAV,
            ),
        )
    }

    @Test
    fun `a replaced module APK invalidates the cache even with an equal version code`() {
        val dataDir = folder.root.absolutePath
        OptionalTargetCache.store(dataDir, key, targets)
        val replaced = key.copy(moduleApkPath = "/data/app/~~c/eu.hxreborn.gplayadblock-d/base.apk")
        assertNull(OptionalTargetCache.load(dataDir, replaced, targets.requested))
    }

    @Test
    fun `the sponsored target cache file is left in place`() {
        val dataDir = folder.root.absolutePath
        val sponsoredFile =
            folder
                .newFolder(
                    "files",
                    "playstore-adblock",
                ).resolve("targets-85400940-10601.json")
        sponsoredFile.writeText("{}")
        OptionalTargetCache.store(dataDir, key, targets)
        assertEquals("{}", sponsoredFile.readText())
    }
}
