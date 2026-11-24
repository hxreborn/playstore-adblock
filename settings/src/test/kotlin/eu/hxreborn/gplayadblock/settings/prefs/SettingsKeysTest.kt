package eu.hxreborn.gplayadblock.settings.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsKeysTest {
    @Test
    fun `an empty group reads as every filter off and the start tab unchanged`() {
        assertEquals(FilterSettings(), SettingsKeys.read(emptyMap<String, Any>()))
    }

    @Test
    fun `sorting defaults off for new and existing preferences`() {
        val sort = FilterSwitch.SORT_SEARCH_RESULTS
        assertFalse(FilterSettings().isOn(sort))
        val previous = SettingsKeys.read(mapOf("hide_help" to true))
        assertFalse(previous.isOn(sort))
        val enabled = previous.with(sort, true)
        val written = LinkedHashMap<String, Any?>()
        SettingsKeys.write(enabled, RecordingEditor(written))
        assertEquals(enabled, SettingsKeys.read(written))
        assertFalse(enabled.changesStreamContent(previous))
    }

    @Test
    fun `minimum ratings defaults to one hundred for new and existing settings`() {
        assertEquals(MinimumRatings.ONE_HUNDRED, FilterSettings().minimumRatings)
        val fresh = SettingsKeys.read(emptyMap<String, Any>())
        assertEquals(MinimumRatings.ONE_HUNDRED, fresh.minimumRatings)
        assertEquals(
            MinimumRatings.ONE_HUNDRED,
            SettingsKeys.read(mapOf(FilterSwitch.SORT_SEARCH_RESULTS.key to true)).minimumRatings,
        )
    }

    @Test
    fun `each minimum rating count is saved as the matching integer`() {
        val counts = listOf(10, 50, 100, 500, 1_000)
        assertEquals(counts, MinimumRatings.entries.map { it.count })
        for ((count, minimum) in counts.zip(MinimumRatings.entries)) {
            val saved = FilterSettings(minimumRatings = minimum)
            val written = LinkedHashMap<String, Any?>()
            SettingsKeys.write(saved, RecordingEditor(written))
            assertEquals(count, written[SettingsKeys.MINIMUM_RATINGS])
            assertEquals(saved, SettingsKeys.read(written))
        }
    }

    @Test
    fun `saved values outside the minimum ratings choices use the default`() {
        for (value in listOf(0, 99, 501, 1_001, "500", 50.5)) {
            assertEquals(
                MinimumRatings.ONE_HUNDRED,
                SettingsKeys.read(mapOf(SettingsKeys.MINIMUM_RATINGS to value)).minimumRatings,
            )
        }
    }

    @Test
    fun `stored Boolean and start tab values read as saved`() {
        val settings =
            SettingsKeys.read(
                mapOf(
                    "hide_live_ops" to true,
                    "hide_search_promotions" to true,
                    "hide_games_tab" to false,
                    "start_tab" to "APPS",
                ),
            )
        assertEquals(
            setOf(FilterSwitch.HIDE_LIVE_OPS, FilterSwitch.HIDE_SEARCH_PROMOTIONS),
            settings.enabled,
        )
        assertEquals(StartTab.APPS, settings.startTab)
    }

    @Test
    fun `string TRUE enables a filter while invalid values use defaults`() {
        val settings =
            SettingsKeys.read(
                mapOf(
                    "hide_live_ops" to 1,
                    "hide_help" to "TRUE",
                    "hide_device_sync" to "yes",
                    "start_tab" to "Settings",
                ),
            )
        assertEquals(setOf(FilterSwitch.HIDE_HELP), settings.enabled)
        assertEquals(StartTab.UNCHANGED, settings.startTab)
    }

    @Test
    fun `a start tab is read trimmed and case insensitively`() {
        assertEquals(StartTab.YOU, SettingsKeys.read(mapOf("start_tab" to " you ")).startTab)
    }

    @Test
    fun `a stored start tab that is also hidden reads as unchanged`() {
        val settings =
            SettingsKeys.read(mapOf("hide_games_tab" to true, "start_tab" to "GAMES"))
        assertEquals(StartTab.UNCHANGED, settings.startTab)
    }

    @Test
    fun `every filter key and the start tab are written`() {
        val written = LinkedHashMap<String, Any?>()
        SettingsKeys.write(
            FilterSettings(setOf(FilterSwitch.HIDE_HELP), StartTab.SEARCH),
            RecordingEditor(written),
        )
        assertEquals(SettingsKeys.allKeys.toSet(), written.keys)
        assertEquals(true, written["hide_help"])
        assertEquals(false, written["hide_live_ops"])
        assertEquals("SEARCH", written["start_tab"])
    }

    @Test
    fun `hiding the selected start tab resets the start tab in the same edit`() {
        val settings =
            FilterSettings(startTab = StartTab.BOOKS).with(FilterSwitch.HIDE_BOOKS_TAB, true)
        assertTrue(settings.isOn(FilterSwitch.HIDE_BOOKS_TAB))
        assertEquals(StartTab.UNCHANGED, settings.startTab)
    }

    @Test
    fun `hiding another tab keeps the start tab`() {
        val settings =
            FilterSettings(startTab = StartTab.BOOKS).with(FilterSwitch.HIDE_GAMES_TAB, true)
        assertEquals(StartTab.BOOKS, settings.startTab)
    }

    @Test
    fun `a hidden tab cannot become the start tab`() {
        val settings = FilterSettings(setOf(FilterSwitch.HIDE_YOU_TAB))
        assertEquals(settings, settings.withStartTab(StartTab.YOU))
        assertEquals(StartTab.SEARCH, settings.withStartTab(StartTab.SEARCH).startTab)
    }

    @Test
    fun `differences count switched filters and a changed start tab`() {
        val applied = FilterSettings(setOf(FilterSwitch.HIDE_HELP))
        val desired = FilterSettings(setOf(FilterSwitch.HIDE_DEVICE_SYNC), StartTab.APPS)
        assertEquals(3, desired.countDifferences(applied))
        assertEquals(0, desired.countDifferences(desired))
        assertFalse(desired.isOn(FilterSwitch.HIDE_HELP))
    }
}
