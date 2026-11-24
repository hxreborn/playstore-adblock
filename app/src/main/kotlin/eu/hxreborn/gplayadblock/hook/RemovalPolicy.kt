package eu.hxreborn.gplayadblock.hook

interface RemovalPolicy {
    fun classify(presentation: Any): Int

    fun isRemovable(case: Int): Boolean

    fun caseName(case: Int): String

    fun orderingContext(parent: Any): Any? = null

    fun strippedPresentation(
        presentation: Any,
        editor: ProtoEditor,
    ): Any? = null

    fun orderedChildren(
        context: Any,
        nodeId: Any,
        presentation: Any?,
        childIds: List<Any>,
        nodeFor: (Any) -> Any?,
        childNodesFor: (Any) -> List<Any?> = { emptyList() },
    ): List<Any>? = null
}
