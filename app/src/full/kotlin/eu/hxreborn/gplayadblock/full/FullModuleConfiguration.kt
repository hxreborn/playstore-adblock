package eu.hxreborn.gplayadblock.full

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.VariantSetup
import eu.hxreborn.gplayadblock.discovery.HookGroup
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.discovery.TargetCache
import eu.hxreborn.gplayadblock.hook.AccountMenuFilter
import eu.hxreborn.gplayadblock.hook.ItemStatsRecorder
import eu.hxreborn.gplayadblock.hook.ManageAppsFilter
import eu.hxreborn.gplayadblock.hook.PersistentNavFilter
import eu.hxreborn.gplayadblock.hook.PresentationClassifier
import eu.hxreborn.gplayadblock.hook.RemovalPolicy
import eu.hxreborn.gplayadblock.hook.SearchRootFilter
import eu.hxreborn.gplayadblock.hook.SearchSortFilter
import eu.hxreborn.gplayadblock.hook.SearchSortInstallation
import eu.hxreborn.gplayadblock.hook.SelfUpdateFilter
import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import eu.hxreborn.gplayadblock.settings.prefs.SearchSortAvailability
import eu.hxreborn.gplayadblock.settings.prefs.SettingsKeys
import eu.hxreborn.gplayadblock.settings.protocol.RestartProtocol
import io.github.libxposed.api.XposedModule
import java.io.File

class FullModuleConfiguration(
    private val module: XposedModule,
    private val application: Application,
    private val classLoader: ClassLoader,
    private val targets: ResolvedTargets.Resolved,
    private val cacheKey: TargetCache.Key,
    requestedSettings: FilterSettings,
) : VariantSetup {
    private val guard = crashGuard()
    private val searchSortAvailable =
        SearchSortAvailability.supports(application.resources.configuration.smallestScreenWidthDp)
    private val settings = guard.settingsForProcess(requestedSettings)
    private val searchSortRequested = settings.isOn(FilterSwitch.SORT_SEARCH_RESULTS)
    private val searchSortEnabled = searchSortRequested && searchSortAvailable
    private val optionalFiltersDisabled = guard.isTripped()
    private val filtersSuppressed =
        settings.enabled != requestedSettings.enabled ||
            settings.startTab != requestedSettings.startTab
    private var contentPolicyCreated = false
    private var graphOrderingCreated = false
    private var graphPolicy: RemovalPolicy? = null
    private val searchSortState by lazy { SearchSortState() }
    private val searchScopeState by lazy { SearchScopeState() }
    private val searchVisibility by lazy { SearchVisibility() }
    private val searchStreamOwners by lazy { SearchStreamOwners() }
    private val itemStats by lazy { ItemStatsIndex() }
    private val searchResultOrdering by lazy {
        SearchResultOrdering.from(
            SearchOrder.RELEVANCE,
            itemStats,
            classLoader,
            targets,
            settings.minimumRatings,
            { node -> searchSortState.orderFor(searchStreamOwners.nodeOwner(node)) },
        )
    }

    override fun removalPolicy(
        sponsored: PresentationClassifier,
        graph: Boolean,
    ): RemovalPolicy {
        if (optionalFiltersDisabled) return sponsored
        if (graph && searchSortEnabled) {
            graphOrderingCreated =
                try {
                    searchResultOrdering
                    true
                } catch (exception: Exception) {
                    Logger.error("search ordering unavailable", exception)
                    false
                } catch (error: LinkageError) {
                    Logger.error("search ordering unavailable", error)
                    false
                }
        }
        val policy =
            if (settings.changesStreamContent(FilterSettings())) {
                contentPolicy(sponsored)
            } else {
                sponsored
            }
        if (graph) graphPolicy = policy
        return policy
    }

    private fun contentPolicy(sponsored: PresentationClassifier): RemovalPolicy =
        try {
            FullRemovalPolicy
                .from(sponsored, classLoader, targets, settings)
                .also { contentPolicyCreated = true }
        } catch (exception: ReflectiveOperationException) {
            Logger.error("content filters unavailable, sponsored filtering only", exception)
            sponsored
        } catch (exception: RuntimeException) {
            Logger.error("content filters unavailable, sponsored filtering only", exception)
            sponsored
        } catch (error: LinkageError) {
            Logger.error("content filters unavailable, sponsored filtering only", error)
            sponsored
        }

    override fun installAfterSponsored(installed: Set<String>) {
        val graphInstalled = HookGroup.GRAPH.label in installed
        try {
            PlayStoreSettingsReceiver.register(application, guard)
        } catch (exception: Exception) {
            Logger.error("Play Store restart receiver unavailable", exception)
        }
        val groups =
            if (optionalFiltersDisabled) {
                setOf(OptionalGroup.INSTALL_SESSION_QUEUE)
            } else {
                OptionalGroup.requiredBy(settings, searchSortAvailable)
            }
        if (searchSortRequested && !searchSortAvailable) {
            Logger.info("search sort groups skipped on tablet")
        }
        if (filtersSuppressed) {
            Logger.error("optional filters skipped after repeated Play Store crashes")
            showOptionalFiltersDisabled()
        }
        if (contentPolicyCreated) installCrashRecorder(guard)
        if (groups.isEmpty()) return
        val optionalTargets = optionalTargets(groups)
        optionalTargets.failures.forEach { (group, reason) ->
            Logger.warn("optional ${group.label} targets unresolved reason=$reason")
        }
        optionalTargets.searchScrollFailure?.let {
            Logger.warn(
                "search scroll unresolved reason=$it",
            )
        }
        val installed =
            groups
                .filter {
                    it !in SEARCH_GROUPS && install(it, optionalTargets)
                }.toMutableList()
        finishOptionalBootstrap(installSort = {
            if (OptionalGroup.SEARCH_SORT_CHIP in groups &&
                installSearchSort(graphInstalled, optionalTargets)
            ) {
                installed.addAll(SEARCH_GROUPS)
            }
        }, finish = {
            if (installed.any { it != OptionalGroup.INSTALL_SESSION_QUEUE } &&
                !contentPolicyCreated
            ) {
                installCrashRecorder(guard)
            }
            Logger.info(
                "optional filters installed=${installed.joinToString(
                    ",",
                    "[",
                    "]",
                ) { it.label }} " +
                    "requested=${groups.joinToString(",", "[", "]") { it.label }}",
            )
        })
    }

    private fun optionalTargets(groups: Set<OptionalGroup>): OptionalTargets {
        val dataDir = application.applicationInfo.dataDir
        OptionalTargetCache.load(dataDir, cacheKey, groups)?.let { return it }
        val started = SystemClock.elapsedRealtime()
        val resolved =
            OptionalDexKitResolver.resolve(
                buildList {
                    add(application.applicationInfo.sourceDir)
                    application.applicationInfo.splitSourceDirs?.let(::addAll)
                },
                groups,
            )
        Logger.info("optional target scan took ${SystemClock.elapsedRealtime() - started} ms")
        OptionalTargetCache.store(dataDir, cacheKey, resolved)
        return resolved
    }

    private fun install(
        group: OptionalGroup,
        optionalTargets: OptionalTargets,
    ): Boolean =
        try {
            when (group) {
                OptionalGroup.ACCOUNT_MENU -> {
                    optionalTargets.accountMenu?.let { accountMenu ->
                        AccountMenuFilter.install(
                            module,
                            application,
                            classLoader,
                            settings,
                            accountMenu,
                        )
                    } ?: false
                }

                OptionalGroup.MANAGE_APPS -> {
                    optionalTargets.manageApps?.let { manageApps ->
                        ManageAppsFilter.install(module, classLoader, settings, manageApps)
                    } ?: false
                }

                OptionalGroup.PERSISTENT_NAV -> {
                    optionalTargets.persistentNav?.let { persistentNav ->
                        PersistentNavFilter.install(
                            module,
                            application,
                            classLoader,
                            settings,
                            persistentNav,
                        )
                    } ?: false
                }

                OptionalGroup.SELF_UPDATE -> {
                    optionalTargets.selfUpdate?.let { selfUpdate ->
                        SelfUpdateFilter.install(module, classLoader, settings, selfUpdate)
                    } ?: false
                }

                OptionalGroup.SEARCH_ORDER,
                OptionalGroup.SEARCH_ROOT,
                OptionalGroup.SEARCH_SORT_CHIP,
                -> {
                    false
                }

                OptionalGroup.INSTALL_SESSION_QUEUE -> {
                    optionalTargets.installSessionQueue?.let { queue ->
                        PlayStoreSettingsReceiver.installSessionQueue(
                            InstallSessionQueue.from(application, classLoader, queue),
                        )
                        true
                    } ?: false
                }
            }
        } catch (exception: Exception) {
            Logger.error("optional ${group.label} filters failed to install", exception)
            false
        } catch (error: LinkageError) {
            Logger.error("optional ${group.label} filters failed to install", error)
            false
        }

    private fun installSearchSort(
        graphInstalled: Boolean,
        optionalTargets: OptionalTargets,
    ): Boolean {
        val policy = graphPolicy ?: return false
        if (!graphInstalled || !graphOrderingCreated ||
            !optionalTargets.canInstallSearchSort()
        ) {
            return false
        }
        val stats = requireNotNull(optionalTargets.searchOrder)
        val root = requireNotNull(optionalTargets.searchRoot)
        val chip = requireNotNull(optionalTargets.searchSortChip)
        val hooks =
            SearchSortInstallation { error ->
                Logger.error("search sort failure", error)
            }
        return try {
            val refresh =
                SearchStreamRefresh.from(
                    classLoader,
                    targets,
                    root,
                    searchStreamOwners,
                    searchResultOrdering,
                    policy,
                    searchSortState::isActive,
                    searchSortState::nativeGeneration,
                    searchSortState::isNativeGenerationCurrent,
                    searchScopeState::token,
                    searchScopeState::isCurrent,
                    searchVisibility.state,
                    { message -> Toast.makeText(application, message, Toast.LENGTH_LONG).show() },
                    searchSortState::attempt,
                    searchSortState::claimFailure,
                    { search, attempt, order ->
                        searchSortState.unappliedOrder(search, attempt) == order
                    },
                    hooks,
                )
            val itemStatsStore =
                ItemStatsStore(
                    File(application.cacheDir, "playstore-adblock-item-stats.json"),
                    itemStats,
                    hooks,
                )
            hooks.install(startAfterActivation = itemStatsStore::start) {
                hooks.onRollback(itemStatsStore::stop)
                ItemStatsRecorder.install(
                    module,
                    hooks,
                    classLoader,
                    stats,
                    targets.protobufToByteArrayMethod.resolve(classLoader),
                    itemStats,
                    itemStatsStore,
                )
                SearchRootFilter.install(
                    module,
                    hooks,
                    classLoader,
                    targets,
                    root,
                    searchResultOrdering,
                    searchStreamOwners,
                    refresh,
                )
                if (!SearchSortFilter.install(
                        module,
                        hooks,
                        classLoader,
                        targets,
                        chip,
                        searchSortState,
                        searchScopeState,
                        searchStreamOwners,
                        root,
                        refresh,
                    )
                ) {
                    return@install false
                }
                application.registerActivityLifecycleCallbacks(searchVisibility)
                hooks.onRollback {
                    application.unregisterActivityLifecycleCallbacks(
                        searchVisibility,
                    )
                }
                hooks.onRollback(
                    itemStatsStore.onRestored { hooks.run(refresh::refreshAfterRestore) },
                )
                hooks.onRollback(
                    itemStats.listen { hooks.run(refresh::scheduleStatisticsRefresh) },
                )
                searchVisibility.onResume = { hooks.run(refresh::resumeVisible) }
                hooks.onRollback { searchVisibility.onResume = {} }
                true
            }
        } catch (exception: Exception) {
            Logger.error("search sort filters failed to install", exception)
            false
        } catch (error: LinkageError) {
            Logger.error("search sort filters failed to install", error)
            false
        }
    }

    private fun crashGuard(): CrashLoopGuard =
        CrashLoopGuard(
            file = File(application.applicationInfo.dataDir, RestartProtocol.CRASH_GUARD_PATH),
            fingerprint = cacheKey.toString(),
        )

    private fun installCrashRecorder(guard: CrashLoopGuard) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                guard.recordCrash()
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    Process.killProcess(Process.myPid())
                    System.exit(CRASH_EXIT_STATUS)
                }
            }
        }
    }

    private fun showOptionalFiltersDisabled() {
        Handler(Looper.getMainLooper()).postDelayed(
            {
                try {
                    Toast
                        .makeText(application, OPTIONAL_FILTERS_DISABLED_MESSAGE, Toast.LENGTH_LONG)
                        .show()
                } catch (_: Exception) {
                }
            },
            TOAST_DELAY_MILLIS,
        )
    }

    companion object {
        private val SEARCH_GROUPS =
            listOf(
                OptionalGroup.SEARCH_ORDER,
                OptionalGroup.SEARCH_ROOT,
                OptionalGroup.SEARCH_SORT_CHIP,
            )
        private const val CRASH_EXIT_STATUS = 10
        private const val TOAST_DELAY_MILLIS = 3000L
        private const val OPTIONAL_FILTERS_DISABLED_MESSAGE =
            "GPlay Adblock turned off its optional filters after repeated Play Store crashes. " +
                "Ad blocking is still active."

        fun readSettings(module: XposedModule): FilterSettings =
            try {
                SettingsKeys.read(module.getRemotePreferences(SettingsKeys.GROUP).all)
            } catch (exception: Exception) {
                Logger.error("optional settings unreadable, every optional filter off", exception)
                FilterSettings()
            }
    }
}

internal fun OptionalTargets.canInstallSearchSort(): Boolean =
    searchOrder != null && searchRoot?.scroll?.canRestorePosition() == true &&
        searchSortChip != null

internal inline fun finishOptionalBootstrap(
    installSort: () -> Unit,
    finish: () -> Unit,
) {
    try {
        installSort()
    } finally {
        finish()
    }
}
