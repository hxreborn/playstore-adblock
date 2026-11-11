package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.hook.UiEntryMatcher.AccountAction
import eu.hxreborn.gplayadblock.hook.UiEntryMatcher.ManageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiEntryMatcherTest {
    // Russian translations as shipped by Play Store 54.0.09, keyed by their English default.
    private val russian =
        mapOf(
            PlayStoreLabels.MANAGE_APPS to setOf("Управление приложениями и устройством"),
            PlayStoreLabels.LIBRARY to setOf("Библиотека"),
            PlayStoreLabels.PLAY_PASS to setOf("Play Pass"),
            PlayStoreLabels.PLAY_PROTECT to setOf("Play Защита"),
            PlayStoreLabels.RATINGS_AND_REVIEWS to setOf("Оценки и отзывы"),
            PlayStoreLabels.CONTRIBUTIONS to setOf("Ваши впечатления"),
            PlayStoreLabels.DEVICE_SYNC to setOf("Синхронизация приложений с устройствами"),
        )
    private val localized: (String) -> Set<String> = { english -> russian[english].orEmpty() }

    @Test
    fun `account actions are recognised by their localized label`() {
        assertEquals(AccountAction.LIBRARY, UiEntryMatcher.accountAction("Библиотека", localized))
        assertEquals(AccountAction.PLAY_PASS, UiEntryMatcher.accountAction("Play Pass", localized))
        assertEquals(
            AccountAction.MANAGE_APPS,
            UiEntryMatcher.accountAction("Управление приложениями и устройством", localized),
        )
    }

    @Test
    fun `an unknown account action is kept`() {
        assertNull(UiEntryMatcher.accountAction("Подарочные карты", localized))
        assertNull(UiEntryMatcher.accountAction(null, localized))
        assertNull(UiEntryMatcher.accountAction("Библиотека") { emptySet() })
    }

    @Test
    fun `small English copy edits still map to the same label`() {
        assertEquals(
            PlayStoreLabels.RATINGS_AND_REVIEWS,
            PlayStoreLabels.canonical("Ratings and reviews"),
        )
        assertEquals(
            PlayStoreLabels.MANAGE_APPS,
            PlayStoreLabels.canonical("  manage apps &  Device "),
        )
        assertEquals(PlayStoreLabels.LIBRARY, PlayStoreLabels.canonical("Library"))
        assertNull(PlayStoreLabels.canonical("Library settings"))
        assertNull(PlayStoreLabels.canonical("Gift cards"))
    }

    @Test
    fun `a label shared by two actions is ambiguous and kept`() {
        val shared: (String) -> Set<String> = { setOf("Same") }
        assertNull(UiEntryMatcher.accountAction("Same", shared))
    }

    @Test
    fun `manage rows are recognised semantically, not by position`() {
        fun row(
            className: String,
            direct: List<String> = emptyList(),
            reachable: List<String> = emptyList(),
        ) = UiEntryMatcher.manageRow(
            className,
            direct,
            { reachable },
            setOf("protectRow"),
            localized,
        )

        assertEquals(ManageRow.PLAY_PROTECT, row("protectRow"))
        assertEquals(ManageRow.RATINGS_AND_REVIEWS, row("a", direct = listOf("Оценки и отзывы")))
        assertEquals(ManageRow.CONTRIBUTIONS, row("b", direct = listOf("Ваши впечатления")))
        assertEquals(
            ManageRow.DEVICE_SYNC,
            row(
                "c",
                reachable =
                    listOf(
                        "Синхронизированных устройств нет",
                        "Синхронизация приложений с устройствами",
                    ),
            ),
        )
        assertNull(row("d", reachable = listOf("Обновления", "Поиск обновлений…")))
    }

    @Test
    fun `reachable strings are only collected when cheaper checks fail`() {
        var walked = false
        UiEntryMatcher.manageRow(
            "protectRow",
            emptyList(),
            {
                walked = true
                emptyList()
            },
            setOf("protectRow"),
            localized,
        )
        assertFalse(walked)
    }

    @Test
    fun `the search landing is the visual categories cluster plus placeholders`() {
        val categories = UiEntryMatcher.VISUAL_CATEGORIES_ELEMENT_TYPE
        assertTrue(UiEntryMatcher.isSearchLanding(listOf(categories, null)))
        assertTrue(UiEntryMatcher.isSearchLanding(listOf(categories)))
        assertFalse(UiEntryMatcher.isSearchLanding(listOf(null, null)))
        assertFalse(UiEntryMatcher.isSearchLanding(listOf(categories, 1234)))
        assertFalse(UiEntryMatcher.isSearchLanding(emptyList()))
    }
}
