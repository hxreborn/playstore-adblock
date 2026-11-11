package eu.hxreborn.gplayadblock.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.hxreborn.gplayadblock.settings.GPlayAdblockApp
import eu.hxreborn.gplayadblock.settings.prefs.Appearance
import eu.hxreborn.gplayadblock.settings.prefs.AppearanceRepository
import eu.hxreborn.gplayadblock.settings.prefs.FilterCategory
import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import eu.hxreborn.gplayadblock.settings.prefs.FrameworkConnection
import eu.hxreborn.gplayadblock.settings.prefs.FrameworkInfo
import eu.hxreborn.gplayadblock.settings.prefs.PrefsRepository
import eu.hxreborn.gplayadblock.settings.prefs.StartTab
import eu.hxreborn.gplayadblock.settings.prefs.ThemeMode
import eu.hxreborn.gplayadblock.settings.protocol.PlayStoreRestarter
import eu.hxreborn.gplayadblock.settings.protocol.RestartOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ApplyFailure {
    NotSent,
    Busy,
    NoReply,
    RestartFailed,
    RootUnavailable,
    CacheNotCleared,
}

sealed interface ApplyPhase {
    data object Idle : ApplyPhase

    data object Saving : ApplyPhase

    data object WaitingForExit : ApplyPhase

    data object Reopening : ApplyPhase

    data class Failed(
        val reason: ApplyFailure,
    ) : ApplyPhase

    val isRunning: Boolean
        get() = this == Saving || this == WaitingForExit || this == Reopening
}

sealed interface SettingsUiState {
    data object Loading : SettingsUiState

    data class Ready(
        val desired: FilterSettings,
        val applied: FilterSettings?,
        val appearance: Appearance,
        val framework: FrameworkInfo?,
        val phase: ApplyPhase,
        val startTabReset: StartTab?,
        val streamCacheClearPending: Boolean = false,
    ) : SettingsUiState {
        val appliedOrDefault: FilterSettings get() = applied ?: FilterSettings()
        val feedCacheClearWithoutContentChange: Boolean
            get() =
                streamCacheClearPending &&
                    desired.differingSwitches(appliedOrDefault).none {
                        it.category == FilterCategory.CONTENT
                    }
        val pendingCount: Int
            get() {
                val switchChanges = desired.countDifferences(appliedOrDefault)
                return if (feedCacheClearWithoutContentChange) switchChanges + 1 else switchChanges
            }
        val startScreenChanged: Boolean get() = desired.startTab != appliedOrDefault.startTab
    }
}

private data class StoredSettings(
    val desired: FilterSettings,
    val applied: FilterSettings?,
    val appearance: Appearance,
    val streamCacheClearPending: Boolean,
)

class SettingsViewModel(
    private val preferences: PrefsRepository,
    private val appearanceRepository: AppearanceRepository,
    private val framework: FrameworkConnection,
    private val restarter: PlayStoreRestarter,
    private val rootRestarter: PlayStoreRestarter,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val phase = MutableStateFlow<ApplyPhase>(ApplyPhase.Idle)
    private val startTabReset = MutableStateFlow<StartTab?>(null)

    val uiState: StateFlow<SettingsUiState> =
        combine(
            combine(
                preferences.observeDesired().flowOn(ioDispatcher),
                preferences.observeApplied().flowOn(ioDispatcher),
                appearanceRepository.observe().flowOn(ioDispatcher),
                preferences.observeStreamCacheClearPending().flowOn(ioDispatcher),
                ::StoredSettings,
            ),
            framework.framework,
            phase,
            startTabReset,
        ) { stored, frameworkInfo, applyPhase, reset ->
            SettingsUiState.Ready(
                desired = stored.desired,
                applied = stored.applied,
                appearance = stored.appearance,
                framework = frameworkInfo,
                phase = applyPhase,
                startTabReset = reset,
                streamCacheClearPending = stored.streamCacheClearPending,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState.Loading)

    init {
        viewModelScope.launch {
            framework.binds.filter { it > 0 }.collect {
                publishApplied()
            }
        }
    }

    fun setFilter(
        filter: FilterSwitch,
        on: Boolean,
    ) {
        val before = preferences.desiredSettings()
        val after = before.with(filter, on)
        preferences.save(after)
        clearFailure()
        if (filter.hiddenTab != null) {
            startTabReset.value =
                before.startTab.takeIf {
                    after.startTab == StartTab.UNCHANGED &&
                        it != after.startTab
                }
        }
    }

    fun setStartTab(tab: StartTab) {
        preferences.save(preferences.desiredSettings().withStartTab(tab))
        startTabReset.value = null
        clearFailure()
    }

    private fun clearFailure() {
        phase.update { current -> if (current is ApplyPhase.Failed) ApplyPhase.Idle else current }
    }

    fun setThemeMode(mode: ThemeMode) = appearanceRepository.setThemeMode(mode)

    fun setDynamicColor(enabled: Boolean) = appearanceRepository.setDynamicColor(enabled)

    fun apply() = applyWith(restarter)

    fun restartWithRoot() {
        val current = phase.value as? ApplyPhase.Failed ?: return
        if (current.reason !in ROOT_RECOVERABLE_FAILURES) return
        applyWith(rootRestarter)
    }

    private fun applyWith(route: PlayStoreRestarter) {
        val current = phase.value
        if (current.isRunning || !phase.compareAndSet(current, ApplyPhase.Saving)) return
        viewModelScope.launch {
            var restarted = false
            try {
                val snapshot = preferences.desiredSettings()
                val previous = preferences.appliedSettings() ?: FilterSettings()
                val changedSwitches = snapshot.differingSwitches(previous)
                if (!framework.publish(snapshot)) {
                    phase.value = ApplyPhase.Failed(ApplyFailure.NotSent)
                    return@launch
                }
                if (changedSwitches.any { it.category == FilterCategory.CONTENT }) {
                    preferences.requestStreamCacheClear()
                }
                phase.value = ApplyPhase.WaitingForExit
                val outcome =
                    route.restart(clearStreamCache = preferences.streamCacheClearPending()) {
                        phase.value = ApplyPhase.Reopening
                    }
                val cachePending = outcome == RestartOutcome.RestartedCachePending
                restarted = outcome == RestartOutcome.Restarted || cachePending
                if (restarted) {
                    preferences.markApplied(snapshot, streamCacheClearPending = cachePending)
                }
                phase.value = outcome.toPhase()
            } catch (exception: CancellationException) {
                phase.value = ApplyPhase.Idle
                throw exception
            } catch (_: Exception) {
                phase.value = ApplyPhase.Failed(ApplyFailure.RestartFailed)
            } finally {
                if (!restarted) withContext(NonCancellable) { publishApplied() }
            }
        }
    }

    private fun RestartOutcome.toPhase(): ApplyPhase =
        when (this) {
            RestartOutcome.Restarted -> ApplyPhase.Idle
            RestartOutcome.RestartedCachePending -> ApplyPhase.Failed(ApplyFailure.CacheNotCleared)
            RestartOutcome.Busy -> ApplyPhase.Failed(ApplyFailure.Busy)
            RestartOutcome.NoReply -> ApplyPhase.Failed(ApplyFailure.NoReply)
            RestartOutcome.Failed -> ApplyPhase.Failed(ApplyFailure.RestartFailed)
            RestartOutcome.RootUnavailable -> ApplyPhase.Failed(ApplyFailure.RootUnavailable)
        }

    private suspend fun publishApplied(): Boolean =
        framework.publish(preferences.appliedSettings() ?: FilterSettings())

    companion object {
        val ROOT_RECOVERABLE_FAILURES =
            setOf(
                ApplyFailure.NoReply,
                ApplyFailure.RestartFailed,
                ApplyFailure.RootUnavailable,
                ApplyFailure.CacheNotCleared,
            )

        val Factory =
            viewModelFactory {
                initializer {
                    val app = this[APPLICATION_KEY] as GPlayAdblockApp
                    SettingsViewModel(
                        preferences = app.prefsRepository,
                        appearanceRepository = app.appearanceRepository,
                        framework = app.frameworkConnection,
                        restarter = app.playStoreRestarter,
                        rootRestarter = app.rootPlayStoreRestarter,
                    )
                }
            }
    }
}
