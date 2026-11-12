package eu.hxreborn.gplayadblock.settings.ui

import eu.hxreborn.gplayadblock.settings.prefs.AppearanceRepository
import eu.hxreborn.gplayadblock.settings.prefs.FakeSharedPreferences
import eu.hxreborn.gplayadblock.settings.prefs.FilterCategory
import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import eu.hxreborn.gplayadblock.settings.prefs.FrameworkConnection
import eu.hxreborn.gplayadblock.settings.prefs.FrameworkInfo
import eu.hxreborn.gplayadblock.settings.prefs.PrefsRepository
import eu.hxreborn.gplayadblock.settings.prefs.RecordingEditor
import eu.hxreborn.gplayadblock.settings.prefs.SettingsKeys
import eu.hxreborn.gplayadblock.settings.prefs.StartTab
import eu.hxreborn.gplayadblock.settings.prefs.ThemeMode
import eu.hxreborn.gplayadblock.settings.protocol.PlayStoreRestarter
import eu.hxreborn.gplayadblock.settings.protocol.RestartOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private class RecordingRestarter(
        var outcome: RestartOutcome = RestartOutcome.Restarted,
        var gate: CompletableDeferred<Unit>? = null,
        var failure: Exception? = null,
    ) : PlayStoreRestarter {
        val clearRequests = ArrayList<Boolean>()
        var delegate: PlayStoreRestarter? = null

        override suspend fun restart(
            clearStreamCache: Boolean,
            onExited: () -> Unit,
        ): RestartOutcome {
            delegate?.let { return it.restart(clearStreamCache, onExited) }
            clearRequests += clearStreamCache
            failure?.let { throw it }
            gate?.await()
            if (outcome == RestartOutcome.Restarted) onExited()
            return outcome
        }
    }

    private class Remote {
        val commits = ArrayList<Map<String, Any?>>()
        var accept = true
        var duringCommit: () -> Unit = {}

        fun editor(): RecordingEditor {
            val written = LinkedHashMap<String, Any?>()
            return RecordingEditor(written) {
                commits += LinkedHashMap(written)
                duringCommit()
                accept
            }
        }
    }

    private val scheduler = TestCoroutineScheduler()
    private val remote = Remote()
    private val connection = FrameworkConnection(Dispatchers.Unconfined)
    private val restarter = RecordingRestarter()
    private val rootRestarter = RecordingRestarter()
    private val desired = FakeSharedPreferences()
    private val applied = FakeSharedPreferences()
    private val preferences = PrefsRepository(desired, applied)
    private val appearance = AppearanceRepository(FakeSharedPreferences())

    @Before
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        ioDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(scheduler),
    ) = SettingsViewModel(
        preferences,
        appearance,
        connection,
        restarter,
        rootRestarter,
        ioDispatcher,
    )

    private fun connectFramework() =
        connection.attach(Any(), FrameworkInfo("Vector", "2.0", 101), remote::editor)

    private fun SettingsViewModel.ready() = uiState.value as SettingsUiState.Ready

    @Test
    fun `state is loading until the stored settings are read`() {
        val viewModel = viewModel(StandardTestDispatcher(scheduler))
        assertEquals(SettingsUiState.Loading, viewModel.uiState.value)
        scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value is SettingsUiState.Ready)
    }

    @Test
    fun `a fresh install has nothing pending and no framework`() {
        val state = viewModel().ready()
        assertEquals(FilterSettings(), state.desired)
        assertEquals(0, state.pendingCount)
        assertNull(state.framework)
        assertEquals(ApplyPhase.Idle, state.phase)
    }

    @Test
    fun `switching a filter on makes one change pending and switching it back clears it`() {
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        assertEquals(1, viewModel.ready().pendingCount)
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, false)
        assertEquals(0, viewModel.ready().pendingCount)
    }

    @Test
    fun `a changed start screen counts as one pending change`() {
        val viewModel = viewModel()
        viewModel.setStartTab(StartTab.BOOKS)
        assertEquals(1, viewModel.ready().pendingCount)
    }

    @Test
    fun `apply publishes the whole snapshot and marks it applied`() {
        val viewModel = viewModel()
        connectFramework()
        remote.commits.clear()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        viewModel.apply()
        val state = viewModel.ready()
        assertEquals(ApplyPhase.Idle, state.phase)
        assertEquals(0, state.pendingCount)
        assertEquals(1, remote.commits.size)
        assertEquals(SettingsKeys.allKeys.toSet(), remote.commits.single().keys)
        assertEquals(true, remote.commits.single()[FilterSwitch.HIDE_HELP.key])
    }

    @Test
    fun `a failed publish keeps the changes pending and a retry succeeds`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        remote.accept = false
        viewModel.apply()
        assertEquals(ApplyPhase.Failed(ApplyFailure.NotSent), viewModel.ready().phase)
        assertEquals(1, viewModel.ready().pendingCount)
        assertTrue(restarter.clearRequests.isEmpty())
        remote.accept = true
        viewModel.apply()
        assertEquals(ApplyPhase.Idle, viewModel.ready().phase)
        assertEquals(0, viewModel.ready().pendingCount)
    }

    @Test
    fun `apply without a framework connection fails and keeps the changes pending`() {
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        viewModel.apply()
        assertEquals(ApplyPhase.Failed(ApplyFailure.NotSent), viewModel.ready().phase)
        assertEquals(1, viewModel.ready().pendingCount)
    }

    @Test
    fun `edits made while publishing stay pending`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        remote.duringCommit = {
            remote.duringCommit = {}
            viewModel.setFilter(FilterSwitch.HIDE_DEVICE_SYNC, true)
        }
        viewModel.apply()
        val state = viewModel.ready()
        assertEquals(setOf(FilterSwitch.HIDE_HELP), state.applied?.enabled)
        assertEquals(
            setOf(FilterSwitch.HIDE_HELP, FilterSwitch.HIDE_DEVICE_SYNC),
            state.desired.enabled,
        )
        assertEquals(1, state.pendingCount)
    }

    @Test
    fun `edits made while waiting for the restart stay pending`() {
        connectFramework()
        restarter.gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        viewModel.apply()
        assertEquals(ApplyPhase.WaitingForExit, viewModel.ready().phase)
        viewModel.setFilter(FilterSwitch.HIDE_DEVICE_SYNC, true)
        restarter.gate?.complete(Unit)
        val state = viewModel.ready()
        assertEquals(ApplyPhase.Idle, state.phase)
        assertEquals(1, state.pendingCount)
    }

    @Test
    fun `a framework rebind during restart republishes the newly applied snapshot`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        viewModel.apply()
        val previous = preferences.appliedSettings()
        val rebound = Remote()
        restarter.gate = CompletableDeferred()
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        remote.commits.clear()
        viewModel.apply()
        assertEquals(ApplyPhase.WaitingForExit, viewModel.ready().phase)
        assertEquals(true, remote.commits.single()[FilterSwitch.HIDE_LIVE_OPS.key])

        connection.attach(Any(), FrameworkInfo("Vector", "2.0", 101), rebound::editor)
        scheduler.runCurrent()
        assertEquals(previous, preferences.appliedSettings())
        assertTrue(rebound.commits.isEmpty())

        restarter.gate?.complete(Unit)
        scheduler.advanceUntilIdle()
        assertEquals(ApplyPhase.Idle, viewModel.ready().phase)
        assertEquals(preferences.desiredSettings(), preferences.appliedSettings())
        assertEquals(0, viewModel.ready().pendingCount)
        assertEquals(true, rebound.commits.single()[FilterSwitch.HIDE_HELP.key])
        assertEquals(true, rebound.commits.single()[FilterSwitch.HIDE_LIVE_OPS.key])
    }

    @Test
    fun `a framework rebind during a failed restart republishes the previous snapshot`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        viewModel.apply()
        val previous = preferences.appliedSettings()
        restarter.gate = CompletableDeferred()
        restarter.outcome = RestartOutcome.Failed
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        viewModel.apply()
        val rebound = Remote()
        connection.attach(Any(), FrameworkInfo("Vector", "2.0", 101), rebound::editor)
        scheduler.runCurrent()
        assertTrue(rebound.commits.isEmpty())

        restarter.gate?.complete(Unit)
        scheduler.advanceUntilIdle()
        assertEquals(previous, preferences.appliedSettings())
        assertEquals(1, viewModel.ready().pendingCount)
        assertTrue(rebound.commits.isNotEmpty())
        rebound.commits.forEach { snapshot ->
            assertEquals(true, snapshot[FilterSwitch.HIDE_HELP.key])
            assertEquals(false, snapshot[FilterSwitch.HIDE_LIVE_OPS.key])
        }
    }

    @Test
    fun `a second apply while one is running is ignored`() {
        connectFramework()
        restarter.gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        viewModel.apply()
        viewModel.apply()
        restarter.gate?.complete(Unit)
        assertEquals(1, restarter.clearRequests.size)
    }

    @Test
    fun `failed restart outcomes keep changes pending with their failure reasons`() {
        connectFramework()
        val viewModel = viewModel()
        val expected =
            mapOf(
                RestartOutcome.Busy to ApplyFailure.Busy,
                RestartOutcome.NoReply to ApplyFailure.NoReply,
                RestartOutcome.Failed to ApplyFailure.RestartFailed,
                RestartOutcome.RootUnavailable to ApplyFailure.RootUnavailable,
            )
        expected.forEach { (outcome, failure) ->
            restarter.outcome = outcome
            viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
            viewModel.apply()
            assertEquals(ApplyPhase.Failed(failure), viewModel.ready().phase)
            assertEquals(1, viewModel.ready().pendingCount)
            assertNull(preferences.appliedSettings())
        }
    }

    @Test
    fun `an apply that does not restart attempts to publish the applied snapshot again`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        viewModel.apply()
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        val attempts =
            listOf<() -> Unit>(
                { restarter.outcome = RestartOutcome.Busy },
                { restarter.outcome = RestartOutcome.NoReply },
                { restarter.outcome = RestartOutcome.Failed },
                { restarter.outcome = RestartOutcome.RootUnavailable },
                { restarter.failure = IllegalStateException("launcher failed") },
                {
                    restarter.failure = null
                    remote.accept = false
                },
            )
        attempts.forEach { makeFail ->
            makeFail()
            remote.commits.clear()
            viewModel.apply()
            assertTrue(viewModel.ready().phase is ApplyPhase.Failed)
            assertEquals(2, remote.commits.size)
            assertEquals(true, remote.commits.first()[FilterSwitch.HIDE_LIVE_OPS.key])
            assertEquals(true, remote.commits.last()[FilterSwitch.HIDE_HELP.key])
            assertEquals(false, remote.commits.last()[FilterSwitch.HIDE_LIVE_OPS.key])
        }
    }

    @Test
    fun `a failed restart stays pending when the screen is reopened`() {
        connectFramework()
        val original = viewModel()
        original.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        restarter.outcome = RestartOutcome.NoReply
        original.apply()
        assertEquals(1, viewModel().ready().pendingCount)
    }

    @Test
    fun `a content filter retry requests a stream cache clear again`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        restarter.outcome = RestartOutcome.Busy
        viewModel.apply()
        restarter.outcome = RestartOutcome.Restarted
        viewModel.apply()
        assertEquals(listOf(true, true), restarter.clearRequests)
        assertEquals(0, viewModel.ready().pendingCount)
    }

    @Test
    fun `a failed restart preserves the previously applied snapshot`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        viewModel.apply()
        val applied = preferences.appliedSettings()
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        restarter.outcome = RestartOutcome.Failed
        viewModel.apply()
        assertEquals(applied, preferences.appliedSettings())
        assertEquals(1, viewModel.ready().pendingCount)
    }

    @Test
    fun `a throwing restart reports failure and allows a retry`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        restarter.failure = IllegalStateException("launcher failed")
        viewModel.apply()
        assertEquals(ApplyPhase.Failed(ApplyFailure.RestartFailed), viewModel.ready().phase)
        assertEquals(1, viewModel.ready().pendingCount)
        restarter.failure = null
        viewModel.apply()
        assertEquals(ApplyPhase.Idle, viewModel.ready().phase)
        assertEquals(0, viewModel.ready().pendingCount)
    }

    @Test
    fun `a settings read failure reports SettingsFailed without requesting a restart`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        desired.readFailure = IllegalStateException("settings read failed")
        viewModel.apply()
        assertEquals(ApplyPhase.Failed(ApplyFailure.SettingsFailed), viewModel.ready().phase)
        assertTrue(restarter.clearRequests.isEmpty())
        assertEquals(1, viewModel.ready().pendingCount)
    }

    @Test
    fun `a failed applied snapshot write reports SettingsFailed after a successful restart`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        applied.editFailure = IllegalStateException("settings write failed")
        remote.commits.clear()
        viewModel.apply()
        assertEquals(ApplyPhase.Failed(ApplyFailure.SettingsFailed), viewModel.ready().phase)
        assertEquals(listOf(false), restarter.clearRequests)
        assertEquals(1, remote.commits.size)
        assertEquals(true, remote.commits.single()[FilterSwitch.HIDE_HELP.key])
        assertEquals(1, viewModel.ready().pendingCount)
        assertNull(preferences.appliedSettings())
    }

    @Test
    fun `a cancelled restart leaves changes pending and allows a retry`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        restarter.failure = CancellationException("restart cancelled")
        viewModel.apply()
        assertEquals(ApplyPhase.Idle, viewModel.ready().phase)
        assertEquals(1, viewModel.ready().pendingCount)
        assertNull(preferences.appliedSettings())
        restarter.failure = null
        viewModel.apply()
        assertEquals(0, viewModel.ready().pendingCount)
    }

    @Test
    fun `stream cache clearing is requested for content edits but not tab or self update edits`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_GAMES_TAB, true)
        viewModel.apply()
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        viewModel.apply()
        viewModel.setFilter(FilterSwitch.BLOCK_STORE_SELF_UPDATES, true)
        viewModel.apply()
        assertEquals(listOf(false, true, false), restarter.clearRequests)
    }

    @Test
    fun `a refused restart keeps the stream cache request for the retry`() {
        connectFramework()
        val viewModel = viewModel()
        restarter.outcome = RestartOutcome.Busy
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        viewModel.apply()
        restarter.outcome = RestartOutcome.Restarted
        viewModel.apply()
        viewModel.apply()
        assertEquals(listOf(true, true, false), restarter.clearRequests)
        assertEquals(ApplyPhase.Idle, viewModel.ready().phase)
    }

    @Test
    fun `the phase becomes Reopening when the restarter invokes onExited`() {
        connectFramework()
        val viewModel = viewModel()
        val exited = CompletableDeferred<Unit>()
        val reopened = CompletableDeferred<Unit>()
        restarter.delegate =
            object : PlayStoreRestarter {
                override suspend fun restart(
                    clearStreamCache: Boolean,
                    onExited: () -> Unit,
                ): RestartOutcome {
                    exited.await()
                    onExited()
                    reopened.await()
                    return RestartOutcome.Restarted
                }
            }
        val phases = ArrayList<ApplyPhase>()
        val collector =
            CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
                viewModel.uiState.collect { state ->
                    (state as? SettingsUiState.Ready)?.phase?.let { phase ->
                        if (phases.lastOrNull() != phase) phases += phase
                    }
                }
            }
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        viewModel.apply()
        exited.complete(Unit)
        reopened.complete(Unit)
        collector.cancel()
        assertEquals(
            listOf(ApplyPhase.WaitingForExit, ApplyPhase.Reopening, ApplyPhase.Idle),
            phases.dropWhile { it != ApplyPhase.WaitingForExit },
        )
    }

    @Test
    fun `root restart is ignored before failure and while Busy and used after NoReply`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.restartWithRoot()
        assertTrue(rootRestarter.clearRequests.isEmpty())

        restarter.outcome = RestartOutcome.Busy
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        viewModel.apply()
        viewModel.restartWithRoot()
        assertTrue(rootRestarter.clearRequests.isEmpty())

        restarter.outcome = RestartOutcome.NoReply
        viewModel.apply()
        assertEquals(ApplyPhase.Failed(ApplyFailure.NoReply), viewModel.ready().phase)
        viewModel.restartWithRoot()
        assertEquals(listOf(true), rootRestarter.clearRequests)
        assertEquals(ApplyPhase.Idle, viewModel.ready().phase)

        viewModel.apply()
        assertEquals(false, restarter.clearRequests.last())
    }

    @Test
    fun `root outcomes RootUnavailable and Busy map to their failure reasons`() {
        connectFramework()
        val viewModel = viewModel()
        restarter.outcome = RestartOutcome.Failed
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        viewModel.apply()
        rootRestarter.outcome = RestartOutcome.RootUnavailable
        viewModel.restartWithRoot()
        assertEquals(ApplyPhase.Failed(ApplyFailure.RootUnavailable), viewModel.ready().phase)
        rootRestarter.outcome = RestartOutcome.Busy
        viewModel.restartWithRoot()
        assertEquals(ApplyPhase.Failed(ApplyFailure.Busy), viewModel.ready().phase)
    }

    @Test
    fun `a restarter throwing SecurityException reports RestartFailed and keeps changes pending`() {
        connectFramework()
        restarter.delegate =
            object : PlayStoreRestarter {
                override suspend fun restart(
                    clearStreamCache: Boolean,
                    onExited: () -> Unit,
                ): RestartOutcome = throw SecurityException("broadcast denied")
            }
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        viewModel.apply()
        assertEquals(ApplyPhase.Failed(ApplyFailure.RestartFailed), viewModel.ready().phase)
        assertEquals(1, viewModel.ready().pendingCount)
    }

    @Test
    fun `a refused content restart survives ViewModel recreation`() {
        connectFramework()
        restarter.outcome = RestartOutcome.Busy
        val first = viewModel()
        first.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        first.apply()
        assertEquals(1, first.ready().pendingCount)
        assertEquals(null, preferences.appliedSettings())

        restarter.outcome = RestartOutcome.Restarted
        val recreated = viewModel()
        recreated.apply()
        assertEquals(listOf(true, true), restarter.clearRequests)
        assertEquals(0, recreated.ready().pendingCount)
    }

    @Test
    fun `RestartedCachePending marks settings applied and keeps the cache clear pending`() {
        connectFramework()
        restarter.outcome = RestartOutcome.RestartedCachePending
        val first = viewModel()
        first.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        remote.commits.clear()
        first.apply()
        assertEquals(ApplyPhase.Failed(ApplyFailure.StreamCacheClearPending), first.ready().phase)
        assertEquals(preferences.desiredSettings(), preferences.appliedSettings())
        assertEquals(1, remote.commits.size)
        assertEquals(true, remote.commits.single()[FilterSwitch.HIDE_LIVE_OPS.key])
        assertTrue(first.ready().streamCacheClearPending)
        assertTrue(first.ready().streamCacheClearWithoutContentChange)
        assertEquals(1, first.ready().pendingCount)

        restarter.outcome = RestartOutcome.Restarted
        viewModel().apply()
        assertEquals(listOf(true, true), restarter.clearRequests)
    }

    @Test
    fun `a rejected publish does not request a stream cache clear`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        remote.accept = false
        viewModel.apply()
        assertEquals(ApplyPhase.Failed(ApplyFailure.NotSent), viewModel.ready().phase)
        assertFalse(preferences.streamCacheClearPending())
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, false)
        assertEquals(0, viewModel.ready().pendingCount)
    }

    @Test
    fun `a pending cache clear is requested after recreation and a reverted content switch`() {
        connectFramework()
        restarter.outcome = RestartOutcome.RestartedCachePending
        val first = viewModel()
        first.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        first.apply()
        first.setFilter(FilterSwitch.HIDE_LIVE_OPS, false)

        val recreated = viewModel()
        assertEquals(1, recreated.ready().pendingCount)
        assertTrue(recreated.ready().streamCacheClearPending)
        restarter.outcome = RestartOutcome.Restarted
        recreated.apply()
        assertEquals(listOf(true, true), restarter.clearRequests)
        assertEquals(0, recreated.ready().pendingCount)
    }

    @Test
    fun `root republishes edits made after the module restart failed`() {
        connectFramework()
        restarter.outcome = RestartOutcome.NoReply
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        viewModel.apply()
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        assertEquals(ApplyPhase.Idle, viewModel.ready().phase)
        viewModel.restartWithRoot()
        assertTrue(rootRestarter.clearRequests.isEmpty())
        viewModel.apply()
        remote.commits.clear()
        viewModel.restartWithRoot()
        assertEquals(listOf(true), rootRestarter.clearRequests)
        assertEquals(true, remote.commits.single()[FilterSwitch.HIDE_HELP.key])
        assertEquals(true, remote.commits.single()[FilterSwitch.HIDE_LIVE_OPS.key])
        assertEquals(preferences.desiredSettings(), preferences.appliedSettings())
    }

    @Test
    fun `hiding the selected start tab resets the start screen and records why`() {
        val viewModel = viewModel()
        viewModel.setStartTab(StartTab.GAMES)
        viewModel.setFilter(FilterSwitch.HIDE_GAMES_TAB, true)
        val state = viewModel.ready()
        assertEquals(StartTab.UNCHANGED, state.desired.startTab)
        assertEquals(StartTab.GAMES, state.startTabBeforeReset)
        viewModel.setStartTab(StartTab.SEARCH)
        assertNull(viewModel.ready().startTabBeforeReset)
    }

    @Test
    fun `a hidden tab cannot be chosen as the start screen`() {
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_APPS_TAB, true)
        viewModel.setStartTab(StartTab.APPS)
        assertEquals(StartTab.UNCHANGED, viewModel.ready().desired.startTab)
        viewModel.setStartTab(StartTab.SEARCH)
        assertEquals(StartTab.SEARCH, viewModel.ready().desired.startTab)
    }

    @Test
    fun `framework binds before and after Apply publish the applied snapshot`() {
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        assertTrue(remote.commits.isEmpty())
        connectFramework()
        assertEquals(1, remote.commits.size)
        assertEquals(false, remote.commits.last()[FilterSwitch.HIDE_HELP.key])
        assertEquals(false, remote.commits.last()[FilterSwitch.HIDE_LIVE_OPS.key])
        viewModel.apply()
        assertEquals(true, remote.commits.last()[FilterSwitch.HIDE_HELP.key])
        viewModel.setFilter(FilterSwitch.HIDE_LIVE_OPS, true)
        val commitsBeforeBind = remote.commits.size
        connectFramework()
        assertEquals(commitsBeforeBind + 1, remote.commits.size)
        assertEquals(true, remote.commits.last()[FilterSwitch.HIDE_HELP.key])
        assertEquals(false, remote.commits.last()[FilterSwitch.HIDE_LIVE_OPS.key])
    }

    @Test
    fun `an edit back to the applied settings clears a failed apply`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        remote.accept = false
        viewModel.apply()
        assertEquals(ApplyPhase.Failed(ApplyFailure.NotSent), viewModel.ready().phase)
        viewModel.setFilter(FilterSwitch.HIDE_HELP, false)
        assertEquals(ApplyPhase.Idle, viewModel.ready().phase)
        assertEquals(0, viewModel.ready().pendingCount)
    }

    @Test
    fun `a start tab edit clears a failed apply`() {
        connectFramework()
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        restarter.outcome = RestartOutcome.Busy
        viewModel.apply()
        assertEquals(ApplyPhase.Failed(ApplyFailure.Busy), viewModel.ready().phase)
        viewModel.setStartTab(StartTab.SEARCH)
        assertEquals(ApplyPhase.Idle, viewModel.ready().phase)
    }

    @Test
    fun `publishing on bind does not mark the settings applied`() {
        val viewModel = viewModel()
        viewModel.setFilter(FilterSwitch.HIDE_HELP, true)
        connectFramework()
        assertEquals(1, viewModel.ready().pendingCount)
    }

    @Test
    fun `appearance changes are stored separately from the filter settings`() {
        val viewModel = viewModel()
        viewModel.setThemeMode(ThemeMode.DARK)
        viewModel.setDynamicColor(false)
        val state = viewModel.ready()
        assertEquals(ThemeMode.DARK, state.appearance.themeMode)
        assertFalse(state.appearance.dynamicColor)
        assertEquals(state.appearance, appearance.appearance())
        assertEquals(FilterSettings(), preferences.desiredSettings())
        assertNull(preferences.appliedSettings())
        assertEquals(0, state.pendingCount)
    }
}
