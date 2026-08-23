package eu.hxreborn.gplayadblock.discovery

enum class HookGroup(
    val label: String,
) {
    GRAPH("graph"),
    SEARCH("search"),
    CACHE("cache"),
    RESPONSE("response"),
    ;

    companion object {
        private const val STREAM_DATA_ARGUMENTS = 5

        data class CacheArguments(
            val root: Int,
            val rootChildren: Int,
            val nodes: Int,
        )

        fun cacheArguments(targets: ResolvedTargets.Resolved): CacheArguments? {
            val parameters = targets.cacheAssemblyMethod.paramTypeNames
            val nodeClassName = targets.presentationAccessor.className
            val root = parameters.indexOf(nodeClassName)

            if (root < 0 || parameters.lastIndexOf(nodeClassName) != root) return null

            val rootChildren = parameters.indexOfAfter(root, "java.util.List") ?: return null
            val nodes = parameters.indexOfAfter(rootChildren, "java.util.Map") ?: return null

            return CacheArguments(root, rootChildren, nodes)
        }

        fun inputMismatches(targets: ResolvedTargets.Resolved): Map<HookGroup, List<String>> =
            mapOf(
                GRAPH to streamData(targets),
                SEARCH to suggestion(targets),
                CACHE to cacheAssembly(targets),
                RESPONSE to responseCallbacks(targets),
            ).filterValues(List<String>::isNotEmpty)

        private fun streamData(targets: ResolvedTargets.Resolved): List<String> {
            val method = targets.streamDataMethod
            val expected = listOf("java.lang.Throwable")
            val signature =
                "stream data ${method.className}.${method.methodName} declares " +
                    "${method.paramTypeNames}, expected $expected"

            return listOfNotNull(signature.takeIf { method.paramTypeNames != expected }) +
                streamDataConstructor(targets)
        }

        private fun streamDataConstructor(targets: ResolvedTargets.Resolved): List<String> {
            val constructor = targets.streamDataConstructor
            val parameters = constructor.paramTypeNames

            if (parameters.size != STREAM_DATA_ARGUMENTS) {
                return listOf(
                    "stream data constructor ${constructor.className} takes ${parameters.size} " +
                        "arguments, expected $STREAM_DATA_ARGUMENTS",
                )
            }

            return listOf(
                1 to targets.presentationAccessor.returnTypeName,
                2 to "java.util.List",
                3 to "boolean",
                4 to "java.lang.Throwable",
            ).mapNotNull { (index, type) ->
                val actual = parameters[index]

                "stream data constructor arg$index is $actual, expected $type"
                    .takeIf { actual != type && actual != "java.lang.Object" }
            }
        }

        private fun suggestion(targets: ResolvedTargets.Resolved): List<String> {
            val parameters = targets.suggestion?.constructor?.paramTypeNames ?: return emptyList()

            return listOf(
                0 to "java.util.List",
                2 to "int",
            ).mapNotNull { (index, type) ->
                val actual = parameters.getOrNull(index)

                "suggestion constructor arg$index is $actual, expected $type".takeIf {
                    actual !=
                        type
                }
            }
        }

        private fun cacheAssembly(targets: ResolvedTargets.Resolved): List<String> {
            if (cacheArguments(targets) != null) return emptyList()

            val method = targets.cacheAssemblyMethod

            return listOf(
                "cache assembly ${method.className}.${method.methodName} declares " +
                    "${method.paramTypeNames}, which holds no single " +
                    "${targets.presentationAccessor.className} followed by a List and a Map",
            )
        }

        private fun responseCallbacks(targets: ResolvedTargets.Resolved): List<String> =
            targets.responseMethods
                .filter { method -> method.paramTypeNames.size != 1 }
                .map { method ->
                    "response callback ${method.className}.${method.methodName} takes " +
                        "${method.paramTypeNames.size} arguments, expected 1"
                }

        private fun List<String>.indexOfAfter(
            index: Int,
            type: String,
        ): Int? = drop(index + 1).indexOf(type).takeIf { it >= 0 }?.plus(index + 1)
    }
}
