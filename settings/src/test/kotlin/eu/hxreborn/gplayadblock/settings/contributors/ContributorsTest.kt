package eu.hxreborn.gplayadblock.settings.contributors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContributorsTest {
    @Test
    fun `the committed snapshot lists the owner first with an avatar for every entry`() {
        val directory = File("contributors")
        val entries = parseContributorEntries(File(directory, "contributors.json").readText())
        assertEquals(true, entries.first().owner)
        assertEquals(1, entries.count { it.owner })
        entries.forEach { entry -> assertTrue(entry.avatar, File(directory, entry.avatar).isFile) }
    }

    @Test
    fun `an entry without the owner key is not the owner`() {
        val entries =
            parseContributorEntries(
                """[{"login":"a","added":3,"removed":1,"url":"u","avatar":"avatars/a.webp"}]""",
            )
        assertEquals(
            ContributorEntry("a", 3, 1, "u", "avatars/a.webp", owner = false),
            entries.single(),
        )
    }

    @Test
    fun `blocks follow the add and remove ratio and round down`() {
        assertEquals(DiffBlocks(added = 4, removed = 0), diffBlocks(added = 5, removed = 1))
        assertEquals(DiffBlocks(added = 4, removed = 0), diffBlocks(added = 9637, removed = 1964))
        assertEquals(DiffBlocks(added = 3, removed = 2), diffBlocks(added = 6, removed = 4))
        assertEquals(DiffBlocks(added = 0, removed = 5), diffBlocks(added = 0, removed = 7))
        assertEquals(DiffBlocks(added = 0, removed = 0), diffBlocks(added = 0, removed = 0))
    }

    @Test
    fun `blocks do not overflow on large counts`() {
        assertEquals(
            DiffBlocks(added = 2, removed = 2),
            diffBlocks(added = Int.MAX_VALUE, removed = Int.MAX_VALUE),
        )
    }
}
