package eu.hxreborn.gplayadblock.full

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.Reference
import java.util.concurrent.CancellationException

class SearchScopeStateTest {
    @Test
    fun `missing cancellation support rejects sort installation`() {
        val state = SearchScopeState()
        assertFalse(state.configure(Scope::class.java, null))
        assertFalse(state.configure(Any::class.java, Job::class.java.getMethod("cancellation")))
        assertTrue(state.configure(Scope::class.java, Job::class.java.getMethod("cancellation")))
    }

    interface Operation {
        fun apply(
            accumulator: Any,
            element: Any,
        ): Any
    }

    interface Context {
        fun fold(
            initial: Any,
            operation: Operation,
        ): Any
    }

    class Job {
        var cancelled = false

        fun cancellation(): CancellationException {
            if (!cancelled) throw IllegalStateException("active")
            return CancellationException()
        }
    }

    class Scope(
        private val job: Job,
    ) {
        fun context(): Context =
            object : Context {
                override fun fold(
                    initial: Any,
                    operation: Operation,
                ): Any = operation.apply(initial, job)
            }
    }

    @Test
    fun `scope exit cancels loading after back within the activity`() {
        val state = SearchScopeState()
        state.configure(Scope::class.java, Job::class.java.getMethod("cancellation"))
        val owner = Any()
        val job = Job()
        val scope = Scope(job)
        state.bind(owner, scope)
        assertFalse(state.isActive(owner))
        assertTrue(state.resolveJob(owner))
        assertTrue(state.isActive(owner))
        job.cancelled = true
        assertFalse(state.isActive(owner))
        Reference.reachabilityFence(scope)
    }

    @Test
    fun `back to a search binds its new scope without reviving the old job`() {
        val state = SearchScopeState()
        state.configure(Scope::class.java, Job::class.java.getMethod("cancellation"))
        val owner = Any()
        val firstJob = Job()
        val firstScope = Scope(firstJob)
        state.bind(owner, firstScope)
        assertTrue(state.resolveJob(owner))
        val firstToken = state.token(owner)
        assertTrue(state.isCurrent(owner, firstToken))
        firstJob.cancelled = true
        assertFalse(state.isActive(owner))
        val secondJob = Job()
        val secondScope = Scope(secondJob)
        state.bind(owner, secondScope)
        assertTrue(state.resolveJob(owner))
        assertTrue(state.isActive(owner))
        assertFalse(state.isCurrent(owner, firstToken))
        assertTrue(state.isCurrent(owner, state.token(owner)))
        Reference.reachabilityFence(listOf(firstScope, secondScope))
    }
}
