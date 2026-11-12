package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.discovery.ConstructorRef
import eu.hxreborn.gplayadblock.discovery.FieldRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalTargetsMergeTest {
    private val groups = setOf(OptionalGroup.ACCOUNT_MENU)
    private val actions =
        AccountMenuTargets(
            featuresClassName = "features",
            immutableListClassName = "list",
            actionRoleField = FieldRef("action", "role"),
            unresolved = listOf("notification badge: anchor missing"),
        )
    private val badge =
        AccountMenuTargets(
            notificationTopBarConstructor = ConstructorRef("top", listOf("badge")),
            notificationBadgeTypeName = "badge",
            unresolved = listOf("account actions: anchor missing"),
        )

    @Test
    fun `a partial account menu is not a complete group`() {
        assertTrue(targets(actions).completeGroups().isEmpty())
    }

    @Test
    fun `complementary account features survive equal unresolved counts in either order`() {
        for ((first, second) in listOf(actions to badge, badge to actions)) {
            val merged = targets(first).withMoreCompleteGroups(targets(second))
            assertEquals(actions.actionRoleField, merged.accountMenu?.actionRoleField)
            assertEquals(
                badge.notificationTopBarConstructor,
                merged.accountMenu?.notificationTopBarConstructor,
            )
            assertEquals(emptyList<String>(), merged.accountMenu?.unresolved)
            assertEquals(groups, merged.completeGroups())
        }
    }

    @Test
    fun `a later result with more missing features preserves a resolved role`() {
        val missing =
            AccountMenuTargets(
                unresolved =
                    listOf(
                        "account actions: anchor missing",
                        "notification badge: anchor missing",
                    ),
            )
        assertEquals(actions, targets(actions).withMoreCompleteGroups(targets(missing)).accountMenu)
    }

    @Test
    fun `conflicting action roles stay unresolved while a badge from either APK survives`() {
        val conflicting = actions.copy(actionRoleField = FieldRef("other", "role"))
        val merged =
            targets(actions)
                .withMoreCompleteGroups(targets(conflicting))
                .withMoreCompleteGroups(targets(badge))
        assertNull(merged.accountMenu?.actionRoleField)
        assertEquals(
            badge.notificationTopBarConstructor,
            merged.accountMenu?.notificationTopBarConstructor,
        )
        assertEquals(
            listOf("account actions: conflicting targets across APKs"),
            merged.accountMenu?.unresolved,
        )
        val third = merged.withMoreCompleteGroups(targets(actions))
        assertNull(third.accountMenu?.actionRoleField)
    }

    @Test
    fun `complementary manage rows are merged without replacing earlier rows`() {
        val first =
            manage().copy(
                playProtectRowClassNames = listOf("protect"),
                ratingsRowClassName = "rating",
                unresolved = listOf("device sync row", "contributions row", "search landing item"),
            )
        val second =
            manage().copy(
                contributionsRowClassName = "contribution",
                deviceSyncRowClassName = "sync",
                searchLandingItemClassName = "search",
                unresolved = listOf("play protect rows", "ratings row"),
            )
        val merged = first.merge(second)
        assertEquals(listOf("protect"), merged.playProtectRowClassNames)
        assertEquals("rating", merged.ratingsRowClassName)
        assertEquals("contribution", merged.contributionsRowClassName)
        assertEquals("sync", merged.deviceSyncRowClassName)
        assertEquals("search", merged.searchLandingItemClassName)
        assertTrue(merged.unresolved.isEmpty())
    }

    @Test
    fun `a conflicting row leaves other manage features intact`() {
        val first = manage().copy(ratingsRowClassName = "one", deviceSyncRowClassName = "sync")
        val second =
            manage().copy(
                ratingsRowClassName = "two",
                contributionsRowClassName = "contribution",
            )
        val merged = first.merge(second)
        assertNull(merged.ratingsRowClassName)
        assertEquals("sync", merged.deviceSyncRowClassName)
        assertEquals("contribution", merged.contributionsRowClassName)
        assertEquals(listOf("ratings row: conflicting targets across APKs"), merged.unresolved)
    }

    @Test
    fun `rows from incompatible manage list owners are rejected`() {
        val first = manage().copy(ratingsRowClassName = "rating")
        val second = manage().copy(manageListClassName = "other", deviceSyncRowClassName = "sync")
        val merged = first.merge(second)
        assertNull(merged.ratingsRowClassName)
        assertNull(merged.deviceSyncRowClassName)
        assertEquals(listOf("manage list: conflicting targets across APKs"), merged.unresolved)
        assertNull(merged.merge(first).ratingsRowClassName)
    }

    private fun targets(account: AccountMenuTargets): OptionalTargets =
        OptionalTargets(requested = groups, accountMenu = account)

    private fun manage(): ManageAppsTargets =
        ManageAppsTargets(
            manageListClassName = "list",
            playProtectRowClassNames = emptyList(),
            ratingsRowClassName = null,
            contributionsRowClassName = null,
            deviceSyncRowClassName = null,
            searchLandingItemClassName = null,
            unresolved = emptyList(),
        )
}
