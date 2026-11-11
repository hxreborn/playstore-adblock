package eu.hxreborn.gplayadblock

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
