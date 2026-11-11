package eu.hxreborn.gplayadblock.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingNavigationTest {
    @Test
    fun `repeated submissions before the main thread runs navigate once`() {
        val queue = ArrayList<() -> Unit>()
        val navigation = PendingNavigation { action -> queue.add(action) }
        var calls = 0
        repeat(3) { navigation.submit { calls++ } }
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(1, calls)
        navigation.submit { calls++ }
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(2, calls)
    }

    @Test
    fun `a newer destination replaces the pending one`() {
        val queue = ArrayList<() -> Unit>()
        val navigation = PendingNavigation { action -> queue.add(action) }
        val visited = ArrayList<String>()
        navigation.submit { visited += "apps" }
        navigation.submit { visited += "games" }
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(listOf("games"), visited)
    }

    @Test
    fun `reentrant navigation is suppressed`() {
        val navigation =
            PendingNavigation { action ->
                action()
                true
            }
        var calls = 0
        navigation.submit {
            calls++
            navigation.submit { calls++ }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `a rejected post permits the next navigation`() {
        var accepts = false
        val navigation =
            PendingNavigation { action ->
                if (accepts) action()
                accepts
            }
        var calls = 0
        navigation.submit { calls++ }
        accepts = true
        navigation.submit { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun `a throwing callback permits the next navigation`() {
        val navigation =
            PendingNavigation { action ->
                action()
                true
            }
        assertThrows(IllegalStateException::class.java) {
            navigation.submit { error("navigation failed") }
        }
        var calls = 0
        navigation.submit { calls++ }
        assertEquals(1, calls)
    }
}
