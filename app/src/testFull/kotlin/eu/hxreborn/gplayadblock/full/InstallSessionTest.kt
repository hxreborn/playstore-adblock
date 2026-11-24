package eu.hxreborn.gplayadblock.full

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSessionTest {
    @Test
    fun `only ordinary invokes consume DexKit method entries`() {
        assertTrue(isIndexedQueueInvoke("invoke-virtual"))
        assertTrue(isIndexedQueueInvoke("invoke-interface/range"))
        assertFalse(isIndexedQueueInvoke("invoke-custom"))
        assertFalse(isIndexedQueueInvoke("invoke-polymorphic/range"))
    }

    @Test
    fun `queue snapshot distinguishes scheduled and running work`() {
        val pending = InstallSessionQueue.Task("pending.app", "SCHEDULED", false)
        val active = InstallSessionQueue.Task("active.app", "DOWNLOADING", false)
        val pendingSnapshot = requireNotNull(InstallSessionQueue.summarize(listOf(pending)))
        assertEquals(setOf("pending.app"), pendingSnapshot.packages)
        assertFalse(pendingSnapshot.hasRunningTask)
        val activeSnapshot = requireNotNull(InstallSessionQueue.summarize(listOf(pending, active)))
        assertTrue(activeSnapshot.hasRunningTask)
        assertEquals(setOf("pending.app", "active.app"), activeSnapshot.packages)
        assertNull(
            InstallSessionQueue.summarize(
                listOf(InstallSessionQueue.Task("pending.app", "SCHEDULED", true)),
            ),
        )
        assertNull(
            InstallSessionQueue.summarize(
                listOf(InstallSessionQueue.Task(null, "SCHEDULED", false)),
            ),
        )
    }

    @Test
    fun `queue activity protects an install regardless of age`() {
        val old = 2 * 24 * 60 * 60 * 1000L
        assertFalse(mayBeOrphanedSession(false, "example.app", setOf("example.app"), old))
        assertFalse(mayBeOrphanedSession(true, "example.app", emptySet(), old))
        assertFalse(mayBeOrphanedSession(false, null, emptySet(), old))
        assertFalse(mayBeOrphanedSession(false, "example.app", emptySet(), 1000))
        assertTrue(mayBeOrphanedSession(false, "example.app", setOf("other.app"), old))
        assertTrue(InstallSessionQueue.isRunningTask("DOWNLOADING"))
        assertTrue(InstallSessionQueue.isRunningTask("INSTALLING"))
        assertTrue(InstallSessionQueue.isRunningTask("UNKNOWN"))
        assertFalse(InstallSessionQueue.isRunningTask("SCHEDULED"))
    }

    @Test
    fun `only matching managed session keys identify an orphan`() {
        assertTrue(InstallSessionQueue.matchesSessionKey("example.app_123", "example.app"))
        assertTrue(InstallSessionQueue.matchesSessionKey("parent_example.app_123", "example.app"))
        assertFalse(InstallSessionQueue.matchesSessionKey("example.app_abc", "example.app"))
        assertFalse(InstallSessionQueue.matchesSessionKey("example.app.extra_123", "example.app"))
    }

    @Test
    fun `an app install in progress blocks the restart`() {
        assertTrue(
            blocksRestart(
                active = true,
                staged = false,
                committed = false,
            ),
        )
        assertTrue(
            blocksRestart(
                active = true,
                staged = false,
                committed = true,
            ),
        )
    }

    @Test
    fun `a staged system update still being written blocks the restart`() {
        assertTrue(
            blocksRestart(
                active = true,
                staged = true,
                committed = false,
            ),
        )
    }

    @Test
    fun `a system update waiting for reboot does not block the restart`() {
        assertFalse(
            blocksRestart(
                active = true,
                staged = true,
                committed = true,
            ),
        )
    }

    @Test
    fun `an inactive session does not block the restart`() {
        assertFalse(
            blocksRestart(
                active = false,
                staged = false,
                committed = false,
            ),
        )
    }

    @Test
    fun `only the confirmed orphan is exempt when the queue is idle`() {
        val idle = InstallSessionQueue.Snapshot(emptySet(), false)
        val orphan = BlockingInstallSession(123, false, "orphan.app", ORPHAN_SESSION_GRACE_MILLIS)
        val other =
            BlockingInstallSession(456, false, "unresolved.app", ORPHAN_SESSION_GRACE_MILLIS)
        val isManaged: (Int, String) -> Boolean = { id, pkg ->
            id == orphan.id && pkg == orphan.packageName
        }
        assertFalse(
            installSessionsBlockRestart(listOf(orphan), { idle }, isManaged, { listOf(orphan) }),
        )
        assertTrue(
            installSessionsBlockRestart(
                listOf(orphan, other),
                { idle },
                isManaged,
                { listOf(orphan, other) },
            ),
        )
        assertTrue(
            installSessionsBlockRestart(listOf(orphan), { null }, isManaged, { listOf(orphan) }),
        )
        assertTrue(
            installSessionsBlockRestart(
                listOf(orphan),
                { idle.copy(hasRunningTask = true) },
                isManaged,
                { listOf(orphan) },
            ),
        )
    }

    @Test
    fun `restart checks idle queue packages and the exact current session ID`() {
        val orphan = BlockingInstallSession(123, false, "orphan.app", ORPHAN_SESSION_GRACE_MILLIS)
        val isManaged: (Int, String) -> Boolean = { id, pkg -> id == 123 && pkg == "orphan.app" }

        fun blocks(
            packageName: String,
            current: BlockingInstallSession,
        ): Boolean =
            installSessionsBlockRestart(
                listOf(orphan),
                {
                    InstallSessionQueue.summarize(
                        listOf(InstallSessionQueue.Task(packageName, "SCHEDULED", false)),
                    )
                },
                isManaged,
                { listOf(current) },
            )
        assertTrue(blocks("orphan.app", orphan))
        assertFalse(blocks("other.app", orphan))
        assertTrue(blocks("other.app", orphan.copy(id = 456)))
    }

    @Test
    fun `restart rechecks the queue and framework after identifying managed sessions`() {
        val orphan = BlockingInstallSession(123, false, "orphan.app", ORPHAN_SESSION_GRACE_MILLIS)
        val sessions = listOf(orphan)
        var lookupRan = false
        val isManaged: (Int, String) -> Boolean = { _, _ -> true.also { lookupRan = true } }

        assertTrue(
            installSessionsBlockRestart(
                sessions,
                { InstallSessionQueue.Snapshot(emptySet(), hasRunningTask = lookupRan) },
                isManaged,
                { sessions },
            ),
        )
        assertTrue(
            installSessionsBlockRestart(
                sessions,
                { InstallSessionQueue.Snapshot(emptySet(), hasRunningTask = false) },
                isManaged,
                { listOf(orphan.copy(committed = true)) },
            ),
        )
    }
}
