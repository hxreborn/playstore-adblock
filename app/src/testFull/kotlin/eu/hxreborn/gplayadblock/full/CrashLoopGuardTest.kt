package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import eu.hxreborn.gplayadblock.settings.prefs.StartTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashLoopGuardTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private var now = 1_000_000L

    private fun guard(
        fingerprint: String = "build-a",
        file: File = File(temporary.root, "guard"),
    ) = CrashLoopGuard(file, fingerprint) { now }

    @Test
    fun `a tripped guard disables every optional setting including content only settings`() {
        val guard = guard()
        val content = FilterSettings(enabled = setOf(FilterSwitch.HIDE_LIVE_OPS))
        val navigation = FilterSettings(startTab = StartTab.SEARCH)
        assertEquals(emptySet<OptionalGroup>(), OptionalGroup.requiredBy(content))
        assertEquals(content, guard.settingsForProcess(content))
        assertEquals(navigation, guard.settingsForProcess(navigation))
        repeat(CrashLoopGuard.THRESHOLD) { guard.recordCrash() }
        for (requested in listOf(
            content,
            navigation,
            FilterSettings(FilterSwitch.entries.toSet()),
        )) {
            assertEquals(FilterSettings(), guard.settingsForProcess(requested))
        }
        assertTrue(guard.reset())
        assertEquals(content, guard.settingsForProcess(content))
    }

    @Test
    fun `repeated crashes inside the window trip the guard`() {
        val guard = guard()
        repeat(CrashLoopGuard.THRESHOLD - 1) {
            guard.recordCrash()
            now += 10_000
        }
        assertFalse(guard.isTripped())
        guard.recordCrash()
        assertTrue(guard.isTripped())
    }

    @Test
    fun `crashes spread beyond the window do not trip the guard`() {
        val guard = guard()
        repeat(CrashLoopGuard.THRESHOLD * 2) {
            guard.recordCrash()
            now += CrashLoopGuard.WINDOW_MILLIS
        }
        assertFalse(guard.isTripped())
    }

    @Test
    fun `a tripped guard stays tripped until reset`() {
        val guard = guard()
        repeat(CrashLoopGuard.THRESHOLD) { guard.recordCrash() }
        now += CrashLoopGuard.WINDOW_MILLIS * 10
        assertTrue(guard.isTripped())
        guard.reset()
        assertFalse(guard.isTripped())
    }

    @Test
    fun `reset reports whether the guard is gone`() {
        val guard = guard()
        assertTrue("nothing recorded", guard.reset())
        guard.recordCrash()
        assertTrue("state removed", guard.reset())

        // A directory in place of the state file cannot be deleted while it has content.
        val stuck = File(temporary.root, "stuck").apply { mkdirs() }
        File(stuck, "child").writeText("x")
        assertFalse(guard(file = stuck).reset())
    }

    @Test
    fun `a new Play Store or module build gets another chance`() {
        repeat(CrashLoopGuard.THRESHOLD) { guard("build-a").recordCrash() }
        assertTrue(guard("build-a").isTripped())
        assertFalse(guard("build-b").isTripped())
    }

    @Test
    fun `a corrupt guard file is ignored`() {
        val file = File(temporary.root, "guard").apply { writeText("") }
        assertFalse(guard(file = file).isTripped())
    }
}
