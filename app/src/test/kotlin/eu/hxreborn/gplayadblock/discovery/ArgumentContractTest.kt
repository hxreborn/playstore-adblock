package eu.hxreborn.gplayadblock.discovery

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class ArgumentContractTest {
    @Before
    fun requireReleaseFixtures() {
        val unavailable = ReleaseFixtures.unavailableReason()
        assumeTrue(unavailable, unavailable == null)
    }

    @Test
    fun `every release fixture places arguments where the filters read them`() {
        val violations =
            ReleaseFixtures.all().flatMap { fixture ->
                val targets =
                    fixture.resolveTargets() as? ResolvedTargets.Resolved
                        ?: return@flatMap emptyList()
                buildList {
                    addAll(cacheAssemblyViolations(targets))
                    addAll(responseCallbackViolations(targets))
                    addAll(streamDataViolations(targets))
                    addAll(suggestionViolations(targets))
                }.map { violation -> "${fixture.release}: $violation" }
            }
        assertTrue(
            "hook filters read arguments by index, and these releases resolve to methods whose " +
                "arguments are not where the filter expects them:\n" +
                violations.joinToString(
                    "\n",
                ),
            violations.isEmpty(),
        )
    }

    private fun cacheAssemblyViolations(targets: ResolvedTargets.Resolved): List<String> {
        val method = targets.cacheAssemblyMethod
        val offset = method.syntheticSelfParameters
        val parameters = method.paramTypeNames.drop(offset)
        val positional =
            listOf(
                0 to "java.lang.String",
                2 to "java.util.List",
                3 to "java.util.Map",
            ).mapNotNull { (index, type) ->
                val actual = parameters.getOrNull(index)
                "cacheAssemblyMethod arg${index + offset} is $actual, expected $type"
                    .takeIf { actual != type }
            }
        val rootWrapper = parameters.getOrNull(1)
        val rootWrapperIsClass =
            rootWrapper != null && rootWrapper !in PRIMITIVES && '.' !in rootWrapper
        return positional +
            listOfNotNull(
                "cacheAssemblyMethod arg${offset + 1} is $rootWrapper, expected a class"
                    .takeUnless { rootWrapperIsClass },
            )
    }

    private fun responseCallbackViolations(targets: ResolvedTargets.Resolved): List<String> =
        targets.responseMethods.mapNotNull { method ->
            (
                "response callback ${method.className}.${method.methodName} takes " +
                    "${method.paramTypeNames.size} arguments, expected 1"
            ).takeIf { method.paramTypeNames.size != 1 }
        }

    private fun streamDataViolations(targets: ResolvedTargets.Resolved): List<String> {
        val parameters = targets.streamDataMethod.paramTypeNames
        return listOfNotNull(
            "streamDataMethod takes $parameters, expected [java.lang.Throwable]"
                .takeIf { parameters != listOf("java.lang.Throwable") },
        )
    }

    private fun suggestionViolations(targets: ResolvedTargets.Resolved): List<String> {
        val suggestion = targets.suggestion ?: return emptyList()
        val parameters = suggestion.constructor.paramTypeNames
        val positional =
            listOf(
                SUGGESTION_LIST_INDEX to "java.util.List",
                AD_COUNT_INDEX to "int",
            ).mapNotNull { (index, type) ->
                val actual = parameters.getOrNull(index)
                "suggestion constructor arg$index is $actual, expected $type"
                    .takeIf { actual != type }
            }
        val mask = parameters.getOrNull(MASK_INDEX)
        return positional +
            listOfNotNull(
                "suggestion constructor arg$MASK_INDEX is $mask, expected int or absent"
                    .takeIf { mask != null && mask != "int" },
            )
    }

    private companion object {
        const val SUGGESTION_LIST_INDEX = 0
        const val AD_COUNT_INDEX = 2
        const val MASK_INDEX = 7
        val PRIMITIVES =
            setOf("boolean", "byte", "char", "double", "float", "int", "long", "short", "void")
    }
}
