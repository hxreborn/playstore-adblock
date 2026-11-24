package eu.hxreborn.gplayadblock.settings.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSortAvailabilityTest {
    @Test
    fun `search result control is available only for compact configurations`() {
        assertTrue(SearchSortAvailability.supports(599))
        assertFalse(SearchSortAvailability.supports(600))
        assertFalse(SearchSortAvailability.supports(0))
    }
}
