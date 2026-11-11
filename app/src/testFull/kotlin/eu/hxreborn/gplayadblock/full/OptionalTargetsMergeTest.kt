package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.discovery.MethodRef
import org.junit.Assert.assertEquals
import org.junit.Test

class OptionalTargetsMergeTest {
    private val groups = setOf(OptionalGroup.ACCOUNT_MENU, OptionalGroup.SELF_UPDATE)
    private val selfUpdate =
        SelfUpdateTargets(
            schedulerMethod = MethodRef("a", "b", "boolean", listOf("int")),
            autoUpdateMethod = null,
        )
    private val partialAccountMenu =
        AccountMenuTargets(
            featuresClassName = "f",
            unresolved = listOf("anchor 'CustomActionSpec{id=' candidates=0"),
        )
    private val completeAccountMenu =
        AccountMenuTargets(featuresClassName = "f", immutableListClassName = "l")

    @Test
    fun `a partial account menu is not complete so the next APK is scanned for it`() {
        val base =
            OptionalTargets(
                requested = groups,
                accountMenu = partialAccountMenu,
                selfUpdate = selfUpdate,
            )
        assertEquals(setOf(OptionalGroup.SELF_UPDATE), base.completeGroups())
    }

    @Test
    fun `a split APK that completes the account menu replaces the partial result`() {
        val base =
            OptionalTargets(
                requested = groups,
                accountMenu = partialAccountMenu,
                selfUpdate = selfUpdate,
            )
        val split =
            OptionalTargets(
                requested = setOf(OptionalGroup.ACCOUNT_MENU),
                accountMenu = completeAccountMenu,
            )
        val merged = base.withMoreCompleteGroups(split)
        assertEquals(completeAccountMenu, merged.accountMenu)
        assertEquals(selfUpdate, merged.selfUpdate)
        assertEquals(groups, merged.completeGroups())
    }

    @Test
    fun `a split APK with fewer targets keeps the earlier result`() {
        val base = OptionalTargets(requested = groups, accountMenu = partialAccountMenu)
        val split =
            OptionalTargets(
                requested = setOf(OptionalGroup.ACCOUNT_MENU),
                accountMenu = AccountMenuTargets(unresolved = listOf("a", "b")),
            )
        assertEquals(partialAccountMenu, base.withMoreCompleteGroups(split).accountMenu)
    }
}
