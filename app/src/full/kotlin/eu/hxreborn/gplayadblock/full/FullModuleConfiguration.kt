package eu.hxreborn.gplayadblock.full

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.VariantSetup
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.discovery.TargetCache
import eu.hxreborn.gplayadblock.hook.AccountMenuFilter
import eu.hxreborn.gplayadblock.hook.ManageAppsFilter
import eu.hxreborn.gplayadblock.hook.PersistentNavFilter
import eu.hxreborn.gplayadblock.hook.PresentationClassifier
import eu.hxreborn.gplayadblock.hook.RemovalPolicy
import eu.hxreborn.gplayadblock.hook.SelfUpdateFilter
import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
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
    private val settings = guard.settingsForProcess(requestedSettings)
    private val optionalFiltersDisabled = settings != requestedSettings
    private var contentPolicyCreated = false

    override fun removalPolicy(sponsored: PresentationClassifier): RemovalPolicy {
        if (!FullRemovalPolicy.contentFiltersEnabled(settings)) return sponsored
        return try {
            FullRemovalPolicy.from(sponsored, classLoader, targets, settings).also {
                contentPolicyCreated = true
            }
        } catch (exception: ReflectiveOperationException) {
            Logger.error("content filters unavailable, sponsored filtering only", exception)
            sponsored
        } catch (error: LinkageError) {
            Logger.error("content filters unavailable, sponsored filtering only", error)
            sponsored
        }
    }

    override fun installAfterSponsored() {
        try {
            PlayStoreSettingsReceiver.register(application, guard)
        } catch (exception: Exception) {
            Logger.error("Play Store restart receiver unavailable", exception)
        }
        val groups = OptionalGroup.requiredBy(settings)
        if (optionalFiltersDisabled) {
            Logger.error("optional filters skipped after repeated Play Store crashes")
            showOptionalFiltersDisabled()
            return
        }
        if (contentPolicyCreated) installCrashRecorder(guard)
        if (groups.isEmpty()) return
        val optionalTargets = optionalTargets(groups)
        optionalTargets.failures.forEach { (group, reason) ->
            Logger.warn("optional ${group.label} targets unresolved reason=$reason")
        }
        val installed = groups.filter { group -> install(group, optionalTargets) }
        if (installed.isNotEmpty() && !contentPolicyCreated) installCrashRecorder(guard)
        Logger.info(
            "optional filters installed=${installed.joinToString(",", "[", "]") { it.label }} " +
                "requested=${groups.joinToString(",", "[", "]") { it.label }}",
        )
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
                    } != null
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
                    } != null
                }

                OptionalGroup.SELF_UPDATE -> {
                    optionalTargets.selfUpdate?.let { selfUpdate ->
                        SelfUpdateFilter.install(module, classLoader, settings, selfUpdate)
                    } != null
                }
            }
        } catch (exception: Exception) {
            Logger.error("optional ${group.label} filters failed to install", exception)
            false
        } catch (error: LinkageError) {
            Logger.error("optional ${group.label} filters failed to install", error)
            false
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
