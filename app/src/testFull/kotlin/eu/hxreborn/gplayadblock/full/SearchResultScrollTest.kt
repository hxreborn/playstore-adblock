package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.hook.SearchSortInstallation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.Reference

class SearchResultScrollTest {
    @Test
    fun `an attached replacement plan can scroll after polling expires`() {
        val tasks = mutableListOf<() -> Unit>()
        val scrolled = mutableListOf<Any>()
        val attached = Any()
        val scroll =
            SearchResultScroll({ _, task -> tasks.add(task) }, { it === attached }, scrolled::add)
        val owner = Any()
        val root = Any()
        val first = Any()
        val next = Any()
        val oldModel = Any()
        val newModel = Any()
        val detached = Any()
        scroll.observe(oldModel, detached)
        scroll.rendered(first, oldModel)
        scroll.publish(owner, root, first, 1) { true }
        repeat(9) { tasks.removeFirst()() }
        assertTrue(tasks.isEmpty())
        scroll.observe(newModel, attached)
        scroll.rendered(next, newModel)
        assertTrue(tasks.isEmpty())
        scroll.publish(owner, root, next, 1) { true }
        tasks.single()()
        assertEquals(listOf(attached), scrolled)
        Reference.reachabilityFence(
            listOf(owner, root, first, next, oldModel, newModel, detached, attached),
        )
    }

    @Test
    fun `progressive publications share the attachment polling budget`() {
        val tasks = mutableListOf<() -> Unit>()
        val scroll = SearchResultScroll({ _, task -> tasks.add(task) }, { false }, {})
        val owner = Any()
        val root = Any()
        val snapshots = List(10) { Any() }
        val plans = List(10) { Any() }
        val model = Any()
        val list = Any()
        scroll.observe(model, list)
        scroll.rendered(snapshots.first(), model)
        scroll.publish(owner, root, snapshots.first(), 1, plans.first()) { true }
        snapshots.indices.drop(1).forEach { index ->
            tasks.removeFirst()()
            scroll.rendered(snapshots[index], model)
            scroll.publish(owner, root, snapshots[index], 1, plans[index]) { true }
        }
        assertTrue(tasks.isEmpty())
        scroll.publish(owner, root, snapshots.last(), 2, plans.last()) { true }
        assertEquals(1, tasks.size)
        Reference.reachabilityFence(listOf(owner, root, snapshots, plans, model, list))
    }

    @Test
    fun `detached list polling stops and a provider observation can rearm it`() {
        val tasks = mutableListOf<() -> Unit>()
        val scrolled = mutableListOf<Any>()
        var attached = false
        val scroll = SearchResultScroll({ _, task -> tasks.add(task) }, { attached }, scrolled::add)
        val owner = Any()
        val root = Any()
        val snapshot = Any()
        val model = Any()
        val list = Any()
        scroll.observe(model, list)
        scroll.rendered(snapshot, model)
        scroll.publish(owner, root, snapshot, 1) { true }
        repeat(12) { tasks.removeFirstOrNull()?.invoke() }
        assertTrue(tasks.isEmpty())
        assertTrue(scrolled.isEmpty())
        val unrelated = Any()
        val unrelatedList = Any()
        val unrelatedSnapshot = Any()
        scroll.observe(unrelated, unrelatedList)
        scroll.rendered(unrelatedSnapshot, unrelated)
        assertTrue(tasks.isEmpty())
        scroll.observe(model, list)
        tasks.removeFirst()()
        assertTrue(scrolled.isEmpty())
        assertEquals(1, tasks.size)
        attached = true
        tasks.removeFirst()()
        assertEquals(listOf(list), scrolled)
        Reference.reachabilityFence(
            listOf(owner, root, snapshot, model, list, unrelated, unrelatedList, unrelatedSnapshot),
        )
    }

    @Test
    fun `an attached model is used when the newest matching model is detached`() {
        val tasks = mutableListOf<() -> Unit>()
        val scrolled = mutableListOf<Any>()
        val attached = Any()
        val scroll =
            SearchResultScroll({ _, task -> tasks.add(task) }, { it === attached }, scrolled::add)
        val first = Any()
        val second = Any()
        val snapshot = Any()
        val owner = Any()
        val root = Any()
        val detached = Any()
        scroll.observe(first, attached)
        scroll.observe(second, detached)
        scroll.rendered(snapshot, first)
        scroll.rendered(snapshot, second)
        scroll.publish(owner, root, snapshot, 1) { true }
        tasks.removeFirst()()
        assertEquals(listOf(attached), scrolled)
        Reference.reachabilityFence(
            listOf(owner, root, snapshot, first, second, attached, detached),
        )
    }

    @Test
    fun `new page rank can replace a pending scroll for the same attempt`() {
        val tasks = mutableListOf<() -> Unit>()
        val scrolled = mutableListOf<Any>()
        val scroll = SearchResultScroll({ _, task -> tasks.add(task) }, { true }, scrolled::add)
        val owner = Any()
        val root = Any()
        val first = Any()
        val next = Any()
        val firstModel = Any()
        val nextModel = Any()
        val firstList = Any()
        val nextList = Any()
        scroll.observe(firstModel, firstList)
        scroll.observe(nextModel, nextList)
        scroll.rendered(first, firstModel)
        scroll.publish(owner, root, first, 1) { true }
        scroll.rendered(next, nextModel)
        scroll.publish(owner, root, next, 1) { true }
        tasks.toList().forEach { it() }
        assertEquals(listOf(nextList), scrolled)
        tasks.clear()
        scroll.publish(owner, root, next, 1) { true }
        assertTrue(tasks.isEmpty())
        Reference.reachabilityFence(
            listOf(owner, root, first, next, firstModel, nextModel, firstList, nextList),
        )
    }

    @Test
    fun `replacement snapshot carrying the same plan completes the pending scroll once`() {
        val tasks = mutableListOf<() -> Unit>()
        val scrolled = mutableListOf<Any>()
        val scroll = SearchResultScroll({ _, task -> tasks.add(task) }, { true }, scrolled::add)
        val owner = Any()
        val root = Any()
        val plan = Any()
        val firstSnapshot = Any()
        val replacement = Any()
        val model = Any()
        val list = Any()
        scroll.publish(owner, root, firstSnapshot, 1, plan) { true }
        scroll.associate(root, replacement, plan, 1)
        scroll.observe(model, list)
        scroll.rendered(replacement, model)
        assertEquals(1, tasks.size)
        tasks.removeAt(0)()
        assertEquals(listOf(list), scrolled)
        scroll.rendered(firstSnapshot, model)
        assertTrue(tasks.isEmpty())
        Reference.reachabilityFence(
            listOf(owner, root, plan, firstSnapshot, replacement, model, list),
        )
    }

    @Test
    fun `a model reused for another snapshot does not scroll until the matching plan renders`() {
        val tasks = mutableListOf<() -> Unit>()
        val scrolled = mutableListOf<Any>()
        val scroll = SearchResultScroll({ _, task -> tasks.add(task) }, { true }, scrolled::add)
        val owner = Any()
        val root = Any()
        val plan = Any()
        val first = Any()
        val replacement = Any()
        val other = Any()
        val model = Any()
        val list = Any()
        scroll.observe(model, list)
        scroll.rendered(first, model)
        scroll.publish(owner, root, first, 1, plan) { true }
        scroll.rendered(other, model)
        tasks.removeAt(0)()
        assertTrue(scrolled.isEmpty())
        assertTrue(tasks.isEmpty())

        scroll.associate(root, replacement, plan, 1)
        scroll.rendered(replacement, model)
        tasks.removeAt(0)()
        assertEquals(listOf(list), scrolled)
        Reference.reachabilityFence(
            listOf(owner, root, plan, first, replacement, other, model, list),
        )
    }

    @Test
    fun `another renderer changing snapshots leaves the matching renderer eligible`() {
        val tasks = mutableListOf<() -> Unit>()
        val scrolled = mutableListOf<Any>()
        val scroll = SearchResultScroll({ _, task -> tasks.add(task) }, { true }, scrolled::add)
        val owner = Any()
        val root = Any()
        val snapshot = Any()
        val otherSnapshot = Any()
        val matchingModel = Any()
        val movingModel = Any()
        val matchingList = Any()
        val movingList = Any()
        scroll.observe(matchingModel, matchingList)
        scroll.observe(movingModel, movingList)
        scroll.rendered(snapshot, matchingModel)
        scroll.publish(owner, root, snapshot, 1) { true }
        scroll.rendered(snapshot, movingModel)
        scroll.rendered(otherSnapshot, movingModel)
        tasks.removeAt(0)()
        assertEquals(listOf(matchingList), scrolled)
        Reference.reachabilityFence(
            listOf(
                owner,
                root,
                snapshot,
                otherSnapshot,
                matchingModel,
                movingModel,
                matchingList,
                movingList,
            ),
        )
    }

    @Test
    fun `snapshot from another plan or attempt cannot complete the scroll`() {
        val tasks = mutableListOf<() -> Unit>()
        val scroll = SearchResultScroll({ _, task -> tasks.add(task) }, { true }, {})
        val owner = Any()
        val root = Any()
        val plan = Any()
        val model = Any()
        val list = Any()
        val wrongPlan = Any()
        val wrongAttempt = Any()
        val initialSnapshot = Any()
        val otherPlan = Any()
        scroll.publish(owner, root, initialSnapshot, 4, plan) { true }
        scroll.observe(model, list)
        scroll.associate(root, wrongPlan, otherPlan, 4)
        scroll.rendered(wrongPlan, model)
        scroll.associate(root, wrongAttempt, plan, 3)
        scroll.rendered(wrongAttempt, model)
        assertTrue(tasks.isEmpty())
        Reference.reachabilityFence(
            listOf(
                owner,
                root,
                plan,
                model,
                list,
                wrongPlan,
                wrongAttempt,
                initialSnapshot,
                otherPlan,
            ),
        )
    }

    @Test
    fun `two retained searches with the same query scroll only the active snapshot`() {
        val tasks = mutableListOf<() -> Unit>()
        val scrolled = mutableListOf<Any>()
        val scroll = SearchResultScroll({ _, task -> tasks.add(task) }, { true }, scrolled::add)
        val firstOwner = Any()
        val secondOwner = Any()
        val firstRoot = Any()
        val secondRoot = Any()
        val firstSnapshot = Any()
        val secondSnapshot = Any()
        val firstModel = Any()
        val secondModel = Any()
        val firstList = Any()
        val secondList = Any()
        var active = firstOwner
        scroll.observe(firstModel, firstList)
        scroll.rendered(firstSnapshot, firstModel)
        scroll.publish(firstOwner, firstRoot, firstSnapshot, 1) { active === firstOwner }
        active = secondOwner
        scroll.publish(secondOwner, secondRoot, secondSnapshot, 1) { active === secondOwner }
        scroll.observe(secondModel, secondList)
        scroll.rendered(secondSnapshot, secondModel)
        tasks.removeAt(0)()
        tasks.removeAt(0)()
        assertEquals(listOf(secondList), scrolled)
        Reference.reachabilityFence(
            listOf(
                firstOwner,
                secondOwner,
                firstRoot,
                secondRoot,
                firstSnapshot,
                secondSnapshot,
                firstModel,
                secondModel,
                firstList,
                secondList,
            ),
        )
    }

    @Test
    fun `publication waits for the matching renderer and attached list`() {
        val tasks = mutableListOf<() -> Unit>()
        val scrolled = mutableListOf<Any>()
        var attached = false
        val scroll = SearchResultScroll({ _, task -> tasks.add(task) }, { attached }, scrolled::add)
        val owner = Any()
        val root = Any()
        val snapshot = Any()
        val model = Any()
        val list = Any()
        scroll.publish(owner, root, snapshot, 1) { true }
        scroll.observe(model, list)
        assertTrue(tasks.isEmpty())
        val otherSnapshot = Any()
        scroll.rendered(otherSnapshot, model)
        assertTrue(tasks.isEmpty())
        scroll.rendered(snapshot, model)
        tasks.removeAt(0)()
        assertTrue(scrolled.isEmpty())
        attached = true
        tasks.removeAt(0)()
        assertEquals(listOf(list), scrolled)
        scroll.publish(owner, root, snapshot, 1) { true }
        assertTrue(tasks.isEmpty())
        Reference.reachabilityFence(listOf(owner, root, snapshot, otherSnapshot, model, list))
    }

    @Test
    fun `late provider binds to the rendered model rather than another equal-key list`() {
        val tasks = mutableListOf<() -> Unit>()
        val scrolled = mutableListOf<Any>()
        val scroll = SearchResultScroll({ _, task -> tasks.add(task) }, { true }, scrolled::add)
        val owner = Any()
        val root = Any()
        val snapshot = Any()

        data class Model(
            val key: String,
        )
        val correctModel = Model("same")
        val otherModel = Model("same")
        val correctList = Any()
        scroll.publish(owner, root, snapshot, 1) { true }
        scroll.rendered(snapshot, correctModel)
        val otherList = Any()
        scroll.observe(otherModel, otherList)
        assertTrue(tasks.isEmpty())
        scroll.observe(correctModel, correctList)
        tasks.removeAt(0)()
        assertEquals(listOf(correctList), scrolled)
        Reference.reachabilityFence(
            listOf(owner, root, snapshot, correctModel, otherModel, correctList, otherList),
        )
    }

    @Test
    fun `throwing host scroll stays pending and retries`() {
        val hooks =
            eu.hxreborn.gplayadblock.hook
                .SearchSortInstallation {}
        hooks.install { true }
        val tasks = mutableListOf<() -> Unit>()
        val failures = mutableListOf<Exception>()
        var calls = 0
        val scroll =
            SearchResultScroll({ _, task -> tasks.add { hooks.run(task) } }, { true }, {
                calls++
                if (calls == 1) throw IllegalStateException("failed")
            }, failures::add)
        val owner = Any()
        val root = Any()
        val snapshot = Any()
        val model = Any()
        val list = Any()
        scroll.observe(model, list)
        scroll.rendered(snapshot, model)
        scroll.publish(owner, root, snapshot, 1) { true }
        tasks.removeAt(0)()
        assertEquals(1, failures.size)
        tasks.removeAt(0)()
        assertEquals(2, calls)
        assertTrue(hooks.isActive())
        Reference.reachabilityFence(listOf(owner, root, snapshot, model, list))
    }

    @Test
    fun `unexpected scroll state failure reaches the sorting gate`() {
        val errors = mutableListOf<Throwable>()
        val tasks = mutableListOf<() -> Unit>()
        val hooks = SearchSortInstallation(errors::add)
        hooks.install { true }
        val failure = IllegalStateException("attachment lookup")
        val scroll =
            SearchResultScroll({ _, task -> tasks.add { hooks.run(task) } }, { throw failure }, {})
        val owner = Any()
        val root = Any()
        val snapshot = Any()
        val model = Any()
        val list = Any()
        scroll.observe(model, list)
        scroll.rendered(snapshot, model)
        scroll.publish(owner, root, snapshot, 1) { true }
        tasks.single()()
        assertEquals(listOf(failure), errors)
        assertTrue(!hooks.isActive())
        Reference.reachabilityFence(listOf(owner, root, snapshot, model, list))
    }

    @Test
    fun `scroll failures stop across publications of the same attempt`() {
        val tasks = mutableListOf<() -> Unit>()
        val failures = mutableListOf<Exception>()
        val scroll =
            SearchResultScroll({ _, task -> tasks.add(task) }, { true }, {
                throw IllegalStateException("scroll failed")
            }, failures::add)
        val owner = Any()
        val root = Any()
        val first = Any()
        val next = Any()
        val model = Any()
        val list = Any()
        scroll.observe(model, list)
        scroll.rendered(first, model)
        scroll.publish(owner, root, first, 1) { true }
        tasks.removeAt(0)()
        scroll.rendered(next, model)
        scroll.publish(owner, root, next, 1) { true }
        repeat(2) { tasks.removeAt(0)() }
        assertEquals(3, failures.size)
        assertTrue(tasks.isEmpty())
        scroll.publish(owner, root, next, 1) { true }
        assertTrue(tasks.isEmpty())
        scroll.publish(owner, root, next, 2) { true }
        assertEquals(1, tasks.size)
        Reference.reachabilityFence(listOf(owner, root, first, next, model, list))
    }

    @Test
    fun `pause and resume does not revive a publication from the old lifecycle`() {
        val tasks = mutableListOf<() -> Unit>()
        val scrolled = mutableListOf<Any>()
        val visibility = SearchVisibilityState()
        val token = visibility.token()
        val scroll = SearchResultScroll({ _, task -> tasks.add(task) }, { true }, scrolled::add)
        val owner = Any()
        val root = Any()
        val snapshot = Any()
        val model = Any()
        val list = Any()
        scroll.publish(owner, root, snapshot, 1) { visibility.isCurrent(token) }
        visibility.pause()
        visibility.resume()
        scroll.rendered(snapshot, model)
        scroll.observe(model, list)
        tasks.toList().forEach { it() }
        assertTrue(scrolled.isEmpty())
        Reference.reachabilityFence(listOf(owner, root, snapshot, model, list))
    }

    @Test
    fun `replacement attempt cancels a posted scroll even for the same root`() {
        val tasks = mutableListOf<() -> Unit>()
        val scrolled = mutableListOf<Any>()
        val scroll = SearchResultScroll({ _, task -> tasks.add(task) }, { true }, scrolled::add)
        val owner = Any()
        val root = Any()
        val first = Any()
        val second = Any()
        val firstModel = Any()
        val secondModel = Any()
        val firstList = Any()
        val secondList = Any()
        scroll.observe(firstModel, firstList)
        scroll.observe(secondModel, secondList)
        scroll.rendered(first, firstModel)
        scroll.publish(owner, root, first, 1) { true }
        scroll.publish(owner, root, second, 2) { true }
        scroll.rendered(second, secondModel)
        tasks.removeAt(0)()
        tasks.removeAt(0)()
        assertEquals(listOf(secondList), scrolled)
        Reference.reachabilityFence(
            listOf(owner, root, first, second, firstModel, secondModel, firstList, secondList),
        )
    }
}
