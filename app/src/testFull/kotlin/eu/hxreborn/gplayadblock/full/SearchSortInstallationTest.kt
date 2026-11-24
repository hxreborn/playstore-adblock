package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.discovery.ConstructorRef
import eu.hxreborn.gplayadblock.discovery.FieldRef
import eu.hxreborn.gplayadblock.discovery.MethodRef
import eu.hxreborn.gplayadblock.hook.SearchSortInstallation
import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import eu.hxreborn.gplayadblock.settings.prefs.MinimumRatings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSortInstallationTest {
    @Test
    fun `snapshot matcher reserves the first parameter for a key`() {
        val tail = listOf("boolean", "java.lang.Throwable")
        assertTrue(
            OptionalDexKitResolver.hasSnapshotKeyFirst(listOf("Key", "java.util.List") + tail),
        )
        assertFalse(
            OptionalDexKitResolver.hasSnapshotKeyFirst(listOf("java.util.List", "Key") + tail),
        )
    }

    @Test
    fun `an unchanged native exception preserves sorting and disable releases jobs once`() {
        val hooks = SearchSortInstallation {}
        val jobs = SearchSortJobs()
        val root = Any()
        val job = jobs.replace(root)
        var cleanups = 0
        hooks.onDisable {
            cleanups++
            jobs.clear()
        }
        hooks.install { true }
        val error = IllegalStateException("native")
        val propagated =
            assertThrows(IllegalStateException::class.java) {
                hooks.dispatch({ error("replayed") }, { error }) { proceeded ->
                    proceeded()
                    throw error
                }
            }
        assertEquals(error, propagated)
        assertTrue(hooks.isActive())
        assertTrue(jobs.isCurrent(root, job))
        hooks.disable(error)
        hooks.disable(error)
        assertFalse(jobs.contains(root))
        assertEquals(1, cleanups)
    }

    @Test
    fun `an unreadable stats cache is repaired without disabling sorting`() {
        val hooks = SearchSortInstallation { throw AssertionError(it) }
        hooks.install { true }
        var repairs = 0
        hooks.run {
            for (failure in listOf(java.io.IOException("read"), org.json.JSONException("parse"))) {
                assertTrue(
                    readStatsCache(
                        java.io.File("unused"),
                        { throw failure },
                    ) { repairs++ }.isEmpty(),
                )
            }
        }
        assertEquals(2, repairs)
        assertTrue(hooks.isActive())
    }

    @Test
    fun `a failed atomic move preserves the previous cache and can retry`() {
        val directory =
            java.nio.file.Files
                .createTempDirectory("item-stats-test")
                .toFile()
        try {
            val destination = directory.resolve("stats.json").apply { writeText("old") }
            assertThrows(java.io.IOException::class.java) {
                replaceStatsCache(
                    destination,
                    "new".toByteArray(),
                ) { _, _ -> throw java.io.IOException("rename") }
            }
            assertEquals("old", destination.readText())
            replaceStatsCache(destination, "new".toByteArray())
            assertEquals("new", destination.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `store startup work runs only after successful activation`() {
        val hooks = SearchSortInstallation {}
        var restored = false
        val restore = hooks.guarded { restored = true }
        assertTrue(
            hooks.install(startAfterActivation = restore::run) {
                assertFalse(restored)
                true
            },
        )
        assertTrue(restored)
        val rejected = SearchSortInstallation {}
        assertFalse(rejected.install(startAfterActivation = { error("startup ran") }) { false })
    }

    @Test
    fun `native reflective errors cannot close the sorting gate`() {
        val errors = mutableListOf<Throwable>()
        val hooks = SearchSortInstallation(errors::add)
        hooks.install { true }
        hooks.run { throw java.lang.reflect.InvocationTargetException(IllegalStateException()) }
        assertTrue(hooks.isActive())
        assertTrue(errors.isEmpty())
        hooks.run { throw IllegalAccessException("module reflection") }
        assertFalse(hooks.isActive())
        assertEquals(1, errors.size)
    }

    @Test
    fun `rejected store startup unwinds existing registrations`() {
        val hooks = SearchSortInstallation {}
        val undo = mutableListOf<String>()
        assertThrows(java.util.concurrent.RejectedExecutionException::class.java) {
            hooks.install(startAfterActivation = {
                throw java.util.concurrent.RejectedExecutionException()
            }) {
                hooks.onRollback { undo.add("hook") }
                hooks.onRollback { undo.add("listener") }
                hooks.onRollback { undo.add("store") }
                true
            }
        }
        assertEquals(listOf("store", "listener", "hook"), undo)
        assertFalse(hooks.isActive())
    }

    @Test
    fun `worker error is contained and skips previously queued work`() {
        val errors = mutableListOf<Throwable>()
        val hooks = SearchSortInstallation(errors::add)
        assertTrue(hooks.install { true })
        val queued = hooks.guarded { error("queued worker ran") }
        val failure = AssertionError("worker failed")
        hooks.guarded { throw failure }.run()
        queued.run()
        assertEquals(listOf(failure), errors)
        assertFalse(hooks.isActive())
    }

    @Test
    fun `an admitted hook uses native arguments after a nested failure disables sorting`() {
        val hooks = SearchSortInstallation {}
        hooks.install { true }
        val result =
            hooks.dispatch({ "fallback" }) { proceeded ->
                hooks.run { throw LinkageError("nested failure") }
                proceeded()
                hooks.proceedRewrite({ "original" }) { error("replacement was sent") }
            }
        assertEquals("original", result)
    }

    @Test
    fun `an interceptor failure before proceeding falls back and disables sorting`() {
        val hooks = SearchSortInstallation {}
        hooks.install { true }
        assertEquals(
            "sanitized native",
            hooks.dispatch({ "sanitized native" }) { error("rewrite") },
        )
        assertEquals("native", hooks.dispatch({ "native" }) { "sorted" })
    }

    @Test
    fun `a downstream throw after substitution disables sorting without replaying the call`() {
        val hooks = SearchSortInstallation {}
        hooks.install { true }
        val failure = IllegalStateException("native rejection")
        var nativeCalls = 0
        val caught =
            assertThrows(IllegalStateException::class.java) {
                hooks.dispatch({ nativeCalls++ }) { proceeded ->
                    proceeded()
                    throw failure
                }
            }
        assertEquals(failure, caught)
        assertEquals(0, nativeCalls)
        assertEquals("native", hooks.dispatch({ "native" }) { "sorted" })
    }

    @Test
    fun `only a void selection callback can suppress native selection`() {
        assertTrue(isVoidSortSelection("void"))
        assertFalse(isVoidSortSelection("java.lang.Void"))
        assertFalse(isVoidSortSelection("java.lang.Object"))
    }

    @Test
    fun `fatal sort registration error still runs bootstrap bookkeeping`() {
        val failure = AssertionError("framework registration failed")
        var finished = false
        val thrown =
            assertThrows(AssertionError::class.java) {
                finishOptionalBootstrap({ throw failure }, { finished = true })
            }
        assertEquals(failure, thrown)
        assertTrue(finished)
    }

    @Test
    fun `a deferred failure permanently disables sort work`() {
        val failures = mutableListOf<Throwable>()
        val installation = SearchSortInstallation(failures::add)
        assertTrue(installation.install { true })
        var ran = false
        installation.run { error("deferred failure") }
        installation.run { ran = true }
        assertFalse(ran)
        assertEquals(1, failures.size)
        assertEquals("native", installation.intercept({ "native" }) { "sorted" })
    }

    @Test
    fun `rollback survives both an unhook error and a throwing reporter`() {
        val installation = SearchSortInstallation { throw AssertionError("reporter") }
        val cleanups = mutableListOf<String>()
        assertFalse(
            installation.install {
                installation.onRollback { cleanups.add("listener") }
                installation.onRollback { cleanups.add("store") }
                installation.onRollback { throw AssertionError("unhook") }
                false
            },
        )
        assertEquals(listOf("store", "listener"), cleanups)
    }

    @Test
    fun `pagination stops requesting at three quarters of the Java heap limit`() {
        val mebibyte = 1024L * 1024
        val limit = 256 * mebibyte
        assertTrue(hasSearchHeapHeadroom(192 * mebibyte - 1, limit))
        assertFalse(hasSearchHeapHeadroom(192 * mebibyte, limit))
        assertFalse(hasSearchHeapHeadroom(255 * mebibyte, limit))
        assertFalse(hasSearchHeapHeadroom(96 * mebibyte, 128 * mebibyte))
    }

    @Test
    fun `sorting off requests no sort hooks even with a saved minimum rating count`() {
        val settings =
            FilterSettings(
                enabled = setOf(FilterSwitch.HIDE_SEARCH_PROMOTIONS),
                minimumRatings = MinimumRatings.FIVE_HUNDRED,
            )
        assertEquals(setOf(OptionalGroup.INSTALL_SESSION_QUEUE), OptionalGroup.requiredBy(settings))
        val enabled = settings.with(FilterSwitch.SORT_SEARCH_RESULTS, true)
        val sortGroups =
            listOf(
                OptionalGroup.SEARCH_ORDER,
                OptionalGroup.SEARCH_ROOT,
                OptionalGroup.SEARCH_SORT_CHIP,
            )
        val enabledGroups = OptionalGroup.requiredBy(enabled)
        assertEquals(sortGroups.toSet() + OptionalGroup.INSTALL_SESSION_QUEUE, enabledGroups)
        assertEquals(sortGroups, enabledGroups.filter { it in sortGroups })
        assertEquals(
            setOf(OptionalGroup.INSTALL_SESSION_QUEUE),
            OptionalGroup.requiredBy(enabled, searchSortAvailable = false),
        )
    }

    @Test
    fun `multi-app ranges fail before pagination`() {
        assertTrue(GlobalSearchCoordinator.supportsSingleItemRanges(listOf(1, 0, 1)))
        assertFalse(GlobalSearchCoordinator.supportsSingleItemRanges(listOf(1, 2, 1)))
    }

    @Test
    fun `scroll from a different APK completes the first discovered root`() {
        val group = OptionalGroup.SEARCH_ROOT
        val method = MethodRef("owner", "method", "void", emptyList())
        val root =
            SearchRootTargets(
                ConstructorRef("snapshot", emptyList()),
                "root",
                method,
                method,
                method,
            )
        val scroll =
            SearchScrollTargets(method, method, listOf(method), method, method, listOf(method))
        val base = OptionalTargets(setOf(group), searchRoot = root)

        assertTrue(base.hasTargets(group))
        assertFalse(group in base.completeGroups())
        val merged = base.withMoreCompleteGroups(OptionalTargets(setOf(group)), scroll)
        assertEquals(root.copy(scroll = scroll), merged.searchRoot)
        assertTrue(group in merged.completeGroups())
    }

    @Test
    fun `complete scroll targets take precedence over the first partial lookup`() {
        val group = OptionalGroup.SEARCH_ROOT
        val method = MethodRef("owner", "method", "void", emptyList())
        val scroll =
            SearchScrollTargets(method, method, listOf(method), method, method, listOf(method))
        val incomplete = scroll.copy(rendererCallers = emptyList())
        val root =
            SearchRootTargets(
                ConstructorRef("snapshot", emptyList()),
                "root",
                method,
                method,
                method,
                incomplete,
            )
        val initial = OptionalTargets(setOf(group), searchRoot = root)
        val later =
            OptionalTargets(
                setOf(group),
                searchRoot = root.copy(rootClassName = "later", scroll = scroll),
            )
        assertFalse(group in initial.completeGroups())
        assertEquals(scroll, preferredSearchScroll(incomplete, scroll))
        assertEquals(scroll, preferredSearchScroll(scroll, incomplete))
        assertEquals(incomplete, preferredSearchScroll(incomplete, null))
        val merged = initial.withMoreCompleteGroups(later, incomplete)
        assertEquals(root.copy(scroll = scroll), merged.searchRoot)
        assertTrue(group in merged.completeGroups())
    }

    @Test
    fun `no sort hooks start without statistics root chip and verified scroll targets`() {
        val method = MethodRef("owner", "method", "void", emptyList())
        val constructor = ConstructorRef("snapshot", emptyList())
        val field = FieldRef("owner", "field")
        val scroll =
            SearchScrollTargets(method, method, listOf(method), method, method, listOf(method))
        val root = SearchRootTargets(constructor, "root", method, method, method, scroll)
        val chip =
            SearchSortChipTargets(
                method,
                method,
                method,
                method,
                method,
                field,
                field,
                listOf(method),
                constructor,
                emptyList(),
            )
        val targets =
            OptionalTargets(
                emptySet(),
                searchOrder = SearchOrderTargets(listOf(method)),
                searchRoot = root,
                searchSortChip = chip,
            )
        assertTrue(targets.canInstallSearchSort())
        assertFalse(targets.copy(searchOrder = null).canInstallSearchSort())
        assertFalse(targets.copy(searchRoot = null).canInstallSearchSort())
        assertFalse(targets.copy(searchSortChip = null).canInstallSearchSort())
        assertFalse(targets.copy(searchRoot = root.copy(scroll = null)).canInstallSearchSort())
        assertFalse(
            targets
                .copy(
                    searchRoot = root.copy(scroll = scroll.copy(renderer = null)),
                ).canInstallSearchSort(),
        )
    }

    @Test
    fun `final page hook failure rolls back all sort hooks and preserves native calls`() {
        val errors = mutableListOf<Throwable>()
        val installation = SearchSortInstallation(errors::add)
        val installed = linkedMapOf<String, () -> String>()
        val unhooks = mutableListOf<String>()
        var nativeCalls = 0
        var sortCalls = 0
        var callback = "native"
        val registered = listOf("statistics", "root", "selection", "filter")
        val escapedInterceptors = mutableListOf<() -> String>()
        assertThrows(IllegalStateException::class.java) {
            installation.install {
                registered.forEach { name ->
                    val call = {
                        installation.intercept(
                            {
                                nativeCalls++
                                "native"
                            },
                            {
                                sortCalls++
                                "sorted"
                            },
                        )
                    }
                    installed[name] = call
                    escapedInterceptors.add(call)
                    assertEquals("native", call())
                    installation.onRollback {
                        unhooks.add(name)
                        installed.remove(name)
                        if (name == "filter") throw IllegalArgumentException("unhook failed")
                    }
                }
                installation.onRollback { callback = "native" }
                callback = "sorting"
                error("page hook registration failed")
            }
        }
        assertEquals(registered.reversed(), unhooks)
        assertTrue(installed.isEmpty())
        assertEquals("native", callback)
        escapedInterceptors.forEach { assertEquals("native", it()) }
        assertEquals(registered.size * 2, nativeCalls)
        assertEquals(0, sortCalls)
        assertEquals(1, errors.size)
    }

    @Test
    fun `successful registration activates all registered interceptors together`() {
        val installation = SearchSortInstallation { throw it }
        val call = { installation.intercept({ "native" }, { "sorted" }) }
        assertTrue(
            installation.install {
                assertEquals("native", call())
                true
            },
        )
        assertEquals("sorted", call())
    }
}
