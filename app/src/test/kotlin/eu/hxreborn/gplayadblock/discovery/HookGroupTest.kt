package eu.hxreborn.gplayadblock.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookGroupTest {
    @Test
    fun `a target set matching every filter reports no mismatches`() {
        assertEquals(emptyMap<HookGroup, List<String>>(), HookGroup.inputMismatches(INTACT))
    }

    @Test
    fun `a build whose stream data constructor is intact reports no graph mismatch`() {
        assertEquals(emptyList<String>(), graphMismatches(INTACT))
    }

    @Test
    fun `a stream data constructor with erased parameter types reports no graph mismatch`() {
        val erased =
            INTACT.copy(
                streamDataConstructor =
                    constructor(
                        "java.lang.Object",
                        "java.lang.Object",
                        "java.lang.Object",
                        "boolean",
                        "java.lang.Object",
                    ),
            )

        assertEquals(emptyList<String>(), graphMismatches(erased))
    }

    @Test
    fun `a shorter stream data constructor reports its argument count`() {
        val shortened =
            INTACT.copy(
                streamDataConstructor =
                    constructor("childId", "presentation", "java.util.List", "boolean"),
            )

        val mismatch = graphMismatches(shortened).single()

        assertTrue(mismatch, mismatch.contains("takes 4 arguments, expected 5"))
    }

    @Test
    fun `a reordered stream data constructor reports both moved arguments`() {
        val reordered =
            INTACT.copy(
                streamDataConstructor =
                    constructor(
                        "childId",
                        "presentation",
                        "boolean",
                        "java.util.List",
                        "java.lang.Throwable",
                    ),
            )

        assertEquals(
            listOf(
                "stream data constructor arg2 is boolean, expected java.util.List",
                "stream data constructor arg3 is java.util.List, expected boolean",
            ),
            graphMismatches(reordered),
        )
    }

    @Test
    fun `a stream data method with an extra parameter reports its declared types`() {
        val widened =
            INTACT.copy(
                streamDataMethod =
                    MethodRef(
                        className = "handler",
                        methodName = "z",
                        returnTypeName = "streamData",
                        paramTypeNames = listOf("java.lang.Throwable", "int"),
                    ),
            )

        assertEquals(
            listOf(
                "stream data handler.z declares [java.lang.Throwable, int], " +
                    "expected [java.lang.Throwable]",
            ),
            graphMismatches(widened),
        )
    }

    @Test
    fun `a build with no resolved suggestion target reports no search mismatch`() {
        val withoutSuggestion = INTACT.copy(suggestion = null, suggestionFailure = "no candidate")

        assertEquals(emptyList<String>(), searchMismatches(withoutSuggestion))
    }

    @Test
    fun `a suggestion constructor whose first argument is not a list reports a search mismatch`() {
        val moved = INTACT.copy(suggestion = suggestion("java.lang.String", "int", "int"))

        assertEquals(
            listOf("suggestion constructor arg0 is java.lang.String, expected java.util.List"),
            searchMismatches(moved),
        )
    }

    @Test
    fun `a suggestion constructor missing the index argument reports a search mismatch`() {
        val shortened = INTACT.copy(suggestion = suggestion("java.util.List", "int"))

        assertEquals(
            listOf("suggestion constructor arg2 is null, expected int"),
            searchMismatches(shortened),
        )
    }

    @Test
    fun `cache write indices come from the resolved signature`() {
        val shifted =
            INTACT.copy(
                cacheAssemblyMethod =
                    cacheAssembly(
                        "int",
                        "java.lang.String",
                        NODE,
                        "java.util.List",
                        "boolean",
                        "java.util.Map",
                    ),
            )

        assertEquals(
            HookGroup.Companion.CacheArguments(root = 2, rootChildren = 3, nodes = 5),
            HookGroup.cacheArguments(shifted),
        )
        assertEquals(emptyList<String>(), cacheMismatches(shifted))
    }

    @Test
    fun `a cache signature with two candidate roots reports no cache arguments`() {
        val ambiguous =
            INTACT.copy(
                cacheAssemblyMethod = cacheAssembly(NODE, "java.util.List", "java.util.Map", NODE),
            )

        assertNull(HookGroup.cacheArguments(ambiguous))
        val mismatch = cacheMismatches(ambiguous).single()

        assertTrue(mismatch, mismatch.contains("holds no single $NODE"))
    }

    @Test
    fun `a cache signature without the root node refuses the group`() {
        val rootless =
            INTACT.copy(
                cacheAssemblyMethod =
                    cacheAssembly(
                        "java.lang.String",
                        "java.util.List",
                        "java.util.Map",
                    ),
            )

        assertNull(HookGroup.cacheArguments(rootless))
        assertEquals(1, cacheMismatches(rootless).size)
    }

    @Test
    fun `a cache child list ahead of the root refuses the group`() {
        val ahead =
            INTACT.copy(
                cacheAssemblyMethod = cacheAssembly("java.util.List", NODE, "java.util.Map"),
            )

        assertNull(HookGroup.cacheArguments(ahead))
        assertEquals(1, cacheMismatches(ahead).size)
    }

    @Test
    fun `a cache node map ahead of the child list refuses the group`() {
        val ahead =
            INTACT.copy(
                cacheAssemblyMethod = cacheAssembly(NODE, "java.util.Map", "java.util.List"),
            )

        assertNull(HookGroup.cacheArguments(ahead))
        assertEquals(1, cacheMismatches(ahead).size)
    }

    @Test
    fun `a response callback that gains an argument reports a response mismatch`() {
        val widened =
            INTACT.copy(
                responseMethods = listOf(responseCallback("one"), responseCallback("two", "int")),
            )

        assertEquals(
            listOf("response callback responsetwo.two takes 2 arguments, expected 1"),
            responseMismatches(widened),
        )
    }

    @Test
    fun `every moved response callback is reported`() {
        val widened =
            INTACT.copy(
                responseMethods =
                    listOf(responseCallback("one", "int"), responseCallback("two", "int")),
            )

        assertEquals(2, responseMismatches(widened).size)
    }

    private companion object {
        const val NODE = "node"

        fun mismatches(
            targets: ResolvedTargets.Resolved,
            group: HookGroup,
        ): List<String> = HookGroup.inputMismatches(targets)[group].orEmpty()

        fun graphMismatches(targets: ResolvedTargets.Resolved): List<String> =
            mismatches(targets, HookGroup.GRAPH)

        fun searchMismatches(targets: ResolvedTargets.Resolved): List<String> =
            mismatches(targets, HookGroup.SEARCH)

        fun cacheMismatches(targets: ResolvedTargets.Resolved): List<String> =
            mismatches(targets, HookGroup.CACHE)

        fun responseMismatches(targets: ResolvedTargets.Resolved): List<String> =
            mismatches(targets, HookGroup.RESPONSE)

        fun constructor(vararg paramTypeNames: String) =
            ConstructorRef(className = "streamData", paramTypeNames = paramTypeNames.toList())

        fun suggestion(vararg paramTypeNames: String) =
            ResolvedTargets.Suggestion(
                constructor =
                    ConstructorRef(
                        className = "suggestionOwner",
                        paramTypeNames = paramTypeNames.toList(),
                    ),
                adInfoField = FieldRef(className = "suggestionOwner", fieldName = "adInfo"),
            )

        fun cacheAssembly(vararg paramTypeNames: String) =
            MethodRef(
                className = "assembly",
                methodName = "c",
                returnTypeName = "cacheNode",
                paramTypeNames = paramTypeNames.toList(),
            )

        fun responseCallback(
            name: String,
            vararg extraParamTypeNames: String,
        ) = MethodRef(
            className = "response$name",
            methodName = name,
            returnTypeName = "void",
            paramTypeNames = listOf("bwsr") + extraParamTypeNames,
        )

        val INTACT =
            SampleTargets.RESOLVED.copy(
                streamDataMethod =
                    MethodRef(
                        className = "handler",
                        methodName = "z",
                        returnTypeName = "streamData",
                        paramTypeNames = listOf("java.lang.Throwable"),
                    ),
                streamDataConstructor =
                    constructor(
                        "childId",
                        "presentation",
                        "java.util.List",
                        "boolean",
                        "java.lang.Throwable",
                    ),
                presentationAccessor =
                    MethodRef(
                        className = NODE,
                        methodName = "i",
                        returnTypeName = "presentation",
                        paramTypeNames = emptyList(),
                    ),
                cacheAssemblyMethod =
                    cacheAssembly("java.lang.String", NODE, "java.util.List", "java.util.Map"),
                responseMethods = listOf(responseCallback("one"), responseCallback("two")),
            )
    }
}
