package eu.hxreborn.gplayadblock.settings.prefs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameworkConnectionTest {
    private val connection = FrameworkConnection(Dispatchers.Unconfined)
    private val settings = FilterSettings(setOf(FilterSwitch.HIDE_HELP), StartTab.APPS)

    @Test
    fun `publishing without a framework connection reports failure`() =
        runTest {
            assertFalse(connection.publish(settings))
        }

    @Test
    fun `a publish writes every key in one commit`() =
        runTest {
            val written = LinkedHashMap<String, Any?>()
            val editor = RecordingEditor(written)
            connection.attach(Any(), null) { editor }
            assertTrue(connection.publish(settings))
            assertEquals(SettingsKeys.allKeys.toSet(), written.keys)
            assertEquals(1, editor.commits)
        }

    @Test
    fun `a failed commit reports failure and a retry writes the whole snapshot again`() =
        runTest {
            var accept = false
            val attempts = ArrayList<Map<String, Any?>>()
            connection.attach(Any(), null) {
                val written = LinkedHashMap<String, Any?>()
                RecordingEditor(written) {
                    attempts += LinkedHashMap(written)
                    accept
                }
            }
            assertFalse(connection.publish(settings))
            accept = true
            assertTrue(connection.publish(settings))
            assertEquals(2, attempts.size)
            assertEquals(attempts[0], attempts[1])
            assertEquals(SettingsKeys.allKeys.toSet(), attempts[1].keys)
        }

    @Test
    fun `an editor that throws reports failure`() =
        runTest {
            connection.attach(Any(), null) { throw IllegalStateException("binder died") }
            assertFalse(connection.publish(settings))
        }

    @Test
    fun `the version name drops the version code and build hash`() {
        val info = FrameworkInfo("Vector", "2.2 (3080) 88f8e1fa-JingMatrix-Vector", 102)
        assertEquals("2.2", info.versionName)
    }

    @Test
    fun `a version that is only a version name stays whole`() {
        assertEquals("2.0", FrameworkInfo("Vector", "2.0", 101).versionName)
    }

    @Test
    fun `a death callback for an older service keeps the current connection`() {
        val older = Any()
        val current = Any()
        val info = FrameworkInfo("Vector", "2.0", 101)
        connection.attach(older, null) { RecordingEditor(LinkedHashMap()) }
        connection.attach(current, info) { RecordingEditor(LinkedHashMap()) }
        connection.detach(older)
        assertEquals(info, connection.framework.value)
        connection.detach(current)
        assertNull(connection.framework.value)
    }
}
