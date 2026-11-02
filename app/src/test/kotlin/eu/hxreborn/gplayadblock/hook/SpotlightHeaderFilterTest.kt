package eu.hxreborn.gplayadblock.hook

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotlightHeaderFilterTest {
    private fun bytes(vararg values: Int) =
        ByteArray(values.size) { index -> values[index].toByte() }

    private val tabTitle = bytes(0x0a, 0x04, 'A'.code, 'p'.code, 'p'.code, 's'.code)
    private val inlineHeader = bytes(0x62, 0x03, 0x0a, 0x01, 0x00)
    private val tabContent = bytes(0x6a, 0x02, 0x08, 0x01)
    private val headerRequest = bytes(0x7a, 0x02, 0x0a, 0x00)

    @Test
    fun `the inline header and the header request are removed from a tab`() {
        val tab = tabTitle + inlineHeader + tabContent + headerRequest

        assertArrayEquals(tabTitle + tabContent, SpotlightHeaderFilter.withoutHeaderFields(tab))
    }

    @Test
    fun `a tab without a header is left to pass through`() {
        assertNull(SpotlightHeaderFilter.withoutHeaderFields(tabTitle + tabContent))
    }
}
