package eu.hxreborn.gplayadblock.hook

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamNodeFilterTest {
    @Test
    fun `ordering failure returns no replacement`() {
        var reported = false
        val ordered = StreamNodeFilter.safeOrder({ reported = true }) { error("bad node") }
        assertNull(ordered)
        assertTrue(reported)
    }
}
