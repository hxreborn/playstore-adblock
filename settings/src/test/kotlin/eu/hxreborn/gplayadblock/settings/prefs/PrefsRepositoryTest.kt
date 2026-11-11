package eu.hxreborn.gplayadblock.settings.prefs

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefsRepositoryTest {
    private val repository = PrefsRepository(FakeSharedPreferences(), FakeSharedPreferences())

    @Test
    fun `a fresh install has default settings and nothing applied`() {
        assertEquals(FilterSettings(), repository.desiredSettings())
        assertNull(repository.appliedSettings())
    }

    @Test
    fun `saved settings read back`() {
        val settings = FilterSettings(setOf(FilterSwitch.HIDE_LIVE_OPS), StartTab.GAMES)
        repository.save(settings)
        assertEquals(settings, repository.desiredSettings())
    }

    @Test
    fun `the desired flow emits the current value then each save`() =
        runTest(UnconfinedTestDispatcher()) {
            val saved = FilterSettings(setOf(FilterSwitch.HIDE_CONTRIBUTIONS))
            val emitted = ArrayList<FilterSettings>()
            val job = launch { repository.observeDesired().take(2).toList(emitted) }
            repository.save(saved)
            job.join()
            assertEquals(listOf(FilterSettings(), saved), emitted)
        }

    @Test
    fun `marking settings applied is observed separately from desired settings`() =
        runTest(UnconfinedTestDispatcher()) {
            val applied = FilterSettings(setOf(FilterSwitch.BLOCK_STORE_SELF_UPDATES))
            repository.markApplied(applied)
            assertEquals(applied, repository.observeApplied().first())
            assertEquals(FilterSettings(), repository.desiredSettings())
        }
}
