package eu.hxreborn.gplayadblock.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCacheFilterTest {
    private class Node

    @Test
    fun `rewritten arguments stay within the types the signature declares`() {
        for (argOffset in 0..1) {
            val signature = signature(argOffset)
            val rewritten =
                StreamCacheFilter.rewriteArguments(
                    original = signature.map(::sample),
                    argOffset = argOffset,
                    root = Node(),
                    rootChildren = listOf(Node()),
                    nodes = mapOf("key" to Node()),
                )
            assertEquals(signature.size, rewritten.size)
            signature.forEachIndexed { index, type ->
                assertTrue(
                    "offset $argOffset argument $index is ${rewritten[index]?.javaClass?.name} " +
                        "where ${type.name} is declared",
                    type.isInstance(rewritten[index]),
                )
            }
        }
    }

    @Test
    fun `arguments outside the rewritten slots keep their original values`() {
        for (argOffset in 0..1) {
            val original = signature(argOffset).map(::sample)
            val rewritten =
                StreamCacheFilter.rewriteArguments(
                    original = original,
                    argOffset = argOffset,
                    root = Node(),
                    rootChildren = listOf(Node()),
                    nodes = mapOf("key" to Node()),
                )
            val rewrittenIndices =
                setOf(
                    argOffset + StreamCacheFilter.ROOT_OFFSET,
                    argOffset + StreamCacheFilter.ROOT_CHILDREN_OFFSET,
                    argOffset + StreamCacheFilter.NODES_OFFSET,
                )
            original.indices.filterNot(rewrittenIndices::contains).forEach { index ->
                assertEquals(
                    "offset $argOffset argument $index changed",
                    original[index],
                    rewritten[index],
                )
            }
        }
    }

    private fun signature(argOffset: Int): List<Class<*>> =
        List<Class<*>>(argOffset) { Node::class.java } +
            listOf(
                String::class.java,
                Node::class.java,
                List::class.java,
                Map::class.java,
                String::class.java,
                Boolean::class.javaObjectType,
            )

    private fun sample(type: Class<*>): Any =
        when (type) {
            String::class.java -> "sample"
            List::class.java -> emptyList<Any>()
            Map::class.java -> emptyMap<Any, Any>()
            Boolean::class.javaObjectType -> true
            else -> Node()
        }
}
