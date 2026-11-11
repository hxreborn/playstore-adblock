package eu.hxreborn.gplayadblock.hook

interface RemovalPolicy {
    fun classify(presentation: Any): Int

    fun isRemovable(case: Int): Boolean

    fun caseName(case: Int): String

    fun strippedPresentation(
        presentation: Any,
        editor: ProtoEditor,
    ): Any? = null
}
