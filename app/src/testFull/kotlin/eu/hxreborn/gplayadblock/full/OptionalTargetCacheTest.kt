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
    fun `search order targets survive a round trip`() {
        val searchOrder =
            OptionalTargets(
                requested = setOf(OptionalGroup.SEARCH_ORDER),
                searchOrder =
                    SearchOrderTargets(
                        itemPrefetchMethods =
                            listOf("aiix", "aikn", "aiks").map { className ->
                                MethodRef(
                                    className,
                                    "a",
                                    "void",
                                    listOf("java.util.List", "boolean"),
                                )
                            },
                    ),
            )
        val dataDir = folder.root.absolutePath
        OptionalTargetCache.store(dataDir, key, searchOrder)
        assertEquals(searchOrder, OptionalTargetCache.load(dataDir, key, searchOrder.requested))
    }

    @Test
    fun `search root targets survive a round trip`() {
        val root =
            OptionalTargets(
                requested = setOf(OptionalGroup.SEARCH_ROOT),
                searchRoot =
                    SearchRootTargets(
                        ConstructorRef("snapshot", listOf("owner", "java.util.List")),
                        "root",
                        MethodRef("root", "refresh", "void", listOf("event")),
                        MethodRef("child", "refresh", "void", emptyList()),
                        MethodRef("child", "load", "void", emptyList()),
                        SearchScrollTargets(
                            MethodRef(
                                "ui",
                                "state",
                                "list",
                                listOf("java.lang.String", "composer"),
                            ),
                            MethodRef("list", "scroll", "void", listOf("int", "int", "boolean")),
                            listOf(MethodRef("ui", "render", "void", emptyList())),
                            MethodRef(
                                "renderer",
                                "accept",
                                "java.lang.Object",
                                listOf("snapshot", "continuation"),
                            ),
                            MethodRef("list", "layout", "void", emptyList()),
                            listOf(MethodRef("caller", "resume", "java.lang.Object", emptyList())),
                        ),
                    ),
            )
        val dataDir = folder.root.absolutePath
        OptionalTargetCache.store(dataDir, key, root)
        assertEquals(root, OptionalTargetCache.load(dataDir, key, root.requested))
    }

    @Test
    fun `install queue targets survive a round trip`() {
        fun field(name: String) = FieldRef("owner", name)

        fun method(name: String) = MethodRef("owner", name, "java.lang.Object", emptyList())
        val queue =
            OptionalTargets(
                requested = setOf(OptionalGroup.INSTALL_SESSION_QUEUE),
                installSessionQueue =
                    InstallSessionQueueTargets(
                        applicationRegistry = field("applicationRegistry"),
                        registryProvider = field("registryProvider"),
                        providerGet = method("providerGet"),
                        adminService = field("adminService"),
                        filterDefault = field("filterDefault"),
                        filterBuilder = method("filterBuilder"),
                        activeStates = field("activeStates"),
                        addStates = method("addStates"),
                        buildFilter = method("buildFilter"),
                        query = method("query"),
                        taskPackage = method("taskPackage"),
                        taskIsGroup = method("taskIsGroup"),
                        taskState = method("taskState"),
                        stateName = method("stateName"),
                    ),
            )
        val dataDir = folder.root.absolutePath
        OptionalTargetCache.store(dataDir, key, queue)
        assertEquals(queue, OptionalTargetCache.load(dataDir, key, queue.requested))
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
