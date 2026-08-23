package eu.hxreborn.gplayadblock.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HookGroupTest {
    @Test
    fun `a build whose stream data constructor is intact reports no graph mismatch`() {
        assertEquals(emptyList<String>(), graphMismatches(INTACT))
    }

    @Test
    fun `an erased stream data constructor still reports no graph mismatch`() {
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
    fun `a stream data constructor that loses an argument reports a graph mismatch`() {
        val shortened =
            INTACT.copy(
                streamDataConstructor =
                    constructor("childId", "presentation", "java.util.List", "boolean"),
            )

        assertTrue(
            graphMismatches(shortened).single(),
            graphMismatches(shortened).single().contains("takes 4 arguments, expected 5"),
        )
    }

    @Test
    fun `a reordered stream data constructor reports the argument that moved`() {
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

    private companion object {
        fun graphMismatches(targets: ResolvedTargets.Resolved): List<String> =
            HookGroup.inputMismatches(targets)[HookGroup.GRAPH].orEmpty()

        fun constructor(vararg paramTypeNames: String) =
            ConstructorRef(className = "streamData", paramTypeNames = paramTypeNames.toList())

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
                        className = "node",
                        methodName = "i",
                        returnTypeName = "presentation",
                        paramTypeNames = emptyList(),
                    ),
            )
    }
}
