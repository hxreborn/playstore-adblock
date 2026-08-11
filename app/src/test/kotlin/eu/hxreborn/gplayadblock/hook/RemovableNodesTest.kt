package eu.hxreborn.gplayadblock.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class RemovableNodesTest {
    private fun parent(
        vararg childIds: String,
        paginated: Boolean = false,
    ) = RemovableNodes.Parent(childIds.toList(), paginated)

    @Test
    fun `a sponsored node no parent references is still removed`() {
        val removable =
            RemovableNodes.select(
                sponsored = setOf("shelf"),
                parents = mapOf("shelf" to parent("card1", "card2")),
            )

        assertEquals(setOf("shelf"), removable)
    }

    @Test
    fun `an expansion whose unreferenced root is sponsored is removed whole`() {
        val cards = List(5) { index -> "card$index" }
        val removable =
            RemovableNodes.select(
                sponsored = (cards + "shelf").toSet(),
                parents = mapOf("shelf" to RemovableNodes.Parent(cards, paginated = false)),
            )

        assertEquals((cards + "shelf").toSet(), removable)
    }

    @Test
    fun `a parent whose children are all sponsored collapses`() {
        val removable =
            RemovableNodes.select(
                sponsored = setOf("card1", "card2"),
                parents = mapOf("shelf" to parent("card1", "card2")),
            )

        assertEquals(setOf("card1", "card2", "shelf"), removable)
    }

    @Test
    fun `a parent keeping one organic child survives`() {
        val removable =
            RemovableNodes.select(
                sponsored = setOf("card1"),
                parents = mapOf("shelf" to parent("card1", "organic")),
            )

        assertEquals(setOf("card1"), removable)
    }

    @Test
    fun `a paginated parent survives even when every loaded child is sponsored`() {
        val removable =
            RemovableNodes.select(
                sponsored = setOf("card1", "card2"),
                parents = mapOf("shelf" to parent("card1", "card2", paginated = true)),
            )

        assertEquals(setOf("card1", "card2"), removable)
    }

    @Test
    fun `a parent with no children survives`() {
        val removable =
            RemovableNodes.select(
                sponsored = setOf("card1"),
                parents = mapOf("empty" to parent(), "shelf" to parent("card1")),
            )

        assertEquals(setOf("card1", "shelf"), removable)
    }

    @Test
    fun `collapse reaches a grandparent`() {
        val removable =
            RemovableNodes.select(
                sponsored = setOf("card"),
                parents =
                    mapOf(
                        "page" to parent("shelf"),
                        "shelf" to parent("card"),
                    ),
            )

        assertEquals(setOf("card", "shelf", "page"), removable)
    }

    @Test
    fun `a paginated grandparent stops the collapse`() {
        val removable =
            RemovableNodes.select(
                sponsored = setOf("card"),
                parents =
                    mapOf(
                        "page" to parent("shelf", paginated = true),
                        "shelf" to parent("card"),
                    ),
            )

        assertEquals(setOf("card", "shelf"), removable)
    }

    @Test
    fun `a cycle terminates`() {
        val removable =
            RemovableNodes.select(
                sponsored = emptySet(),
                parents = mapOf("a" to parent("b"), "b" to parent("a")),
            )

        assertEquals(emptySet<String>(), removable)
    }

    @Test
    fun `nothing sponsored removes nothing`() {
        val removable =
            RemovableNodes.select(
                sponsored = emptySet<String>(),
                parents = mapOf("shelf" to parent("card1", "card2")),
            )

        assertEquals(emptySet<String>(), removable)
    }
}
