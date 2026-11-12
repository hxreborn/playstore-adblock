package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.discovery.ConstructorRef
import eu.hxreborn.gplayadblock.discovery.FieldRef
import eu.hxreborn.gplayadblock.discovery.MethodRef

data class OptionalTargets(
    val requested: Set<OptionalGroup>,
    val accountMenu: AccountMenuTargets? = null,
    val manageApps: ManageAppsTargets? = null,
    val persistentNav: PersistentNavTargets? = null,
    val selfUpdate: SelfUpdateTargets? = null,
    val failures: Map<OptionalGroup, String> = emptyMap(),
    val transientFailure: Boolean = false,
) {
    fun hasTargets(group: OptionalGroup): Boolean =
        when (group) {
            OptionalGroup.ACCOUNT_MENU -> accountMenu != null
            OptionalGroup.MANAGE_APPS -> manageApps != null
            OptionalGroup.PERSISTENT_NAV -> persistentNav != null
            OptionalGroup.SELF_UPDATE -> selfUpdate != null
        }

    fun completeGroups(): Set<OptionalGroup> =
        requested.filterTo(LinkedHashSet()) { group ->
            when (group) {
                OptionalGroup.ACCOUNT_MENU -> accountMenu?.unresolved?.isEmpty() == true
                OptionalGroup.MANAGE_APPS -> manageApps?.unresolved?.isEmpty() == true
                else -> hasTargets(group)
            }
        }

    fun withMoreCompleteGroups(next: OptionalTargets): OptionalTargets =
        copy(
            accountMenu = accountMenu?.merge(next.accountMenu) ?: next.accountMenu,
            manageApps = manageApps?.merge(next.manageApps) ?: next.manageApps,
            persistentNav = persistentNav ?: next.persistentNav,
            selfUpdate = selfUpdate ?: next.selfUpdate,
        )
}

data class AccountMenuTargets(
    val featuresClassName: String? = null,
    val immutableListClassName: String? = null,
    val actionRoleField: FieldRef? = null,
    val notificationTopBarConstructor: ConstructorRef? = null,
    val notificationBadgeTypeName: String? = null,
    val labeledActions: LabeledActionTargets? = null,
    val headerCards: HeaderCardTargets? = null,
    val unresolved: List<String> = emptyList(),
    val transientFailure: Boolean = false,
) {
    internal fun merge(next: AccountMenuTargets?): AccountMenuTargets {
        if (next == null) return this
        val features = FeatureTargets(unresolved + next.unresolved)
        val actions = features.merge("account actions", actions(), next.actions())
        val badge = features.merge("notification badge", badge(), next.badge())
        val labeled =
            features.merge("labeled account actions", labeledActions, next.labeledActions)
        val cards = features.merge("account header cards", headerCards, next.headerCards)
        return AccountMenuTargets(
            featuresClassName = actions?.first,
            immutableListClassName = actions?.second,
            actionRoleField = actions?.third,
            notificationTopBarConstructor = badge?.first,
            notificationBadgeTypeName = badge?.second,
            labeledActions = labeled,
            headerCards = cards,
            unresolved = features.unresolved.toList(),
            transientFailure = transientFailure || next.transientFailure,
        )
    }

    private fun actions(): Triple<String, String, FieldRef>? {
        val features = featuresClassName ?: return null
        val list = immutableListClassName ?: return null
        val role = actionRoleField ?: return null
        return Triple(features, list, role)
    }

    private fun badge(): Pair<ConstructorRef, String>? {
        val constructor = notificationTopBarConstructor ?: return null
        val type = notificationBadgeTypeName ?: return null
        return constructor to type
    }
}

data class ResourcePair(
    val labelId: Int,
    val iconId: Int,
)

data class LabeledActionTargets(
    val actionsAccessor: MethodRef,
    val actionClassName: String,
    val immutableListClassName: String,
    val labelIcons: List<ResourcePair>,
)

data class HeaderCardTargets(
    val cardSupplierMethod: MethodRef,
    val cardIconLoaderMethod: MethodRef,
    val titleIcons: List<ResourcePair>,
)

data class SelfUpdateTargets(
    val schedulerMethod: MethodRef,
    val autoUpdateMethod: MethodRef?,
)

data class ManageAppsTargets(
    val manageListClassName: String,
    val playProtectRowClassNames: List<String>,
    val ratingsRowClassName: String?,
    val contributionsRowClassName: String?,
    val deviceSyncRowClassName: String?,
    val searchLandingItemClassName: String?,
    val unresolved: List<String>,
) {
    internal fun merge(next: ManageAppsTargets?): ManageAppsTargets {
        if (next == null) return this
        val features = FeatureTargets(unresolved + next.unresolved)
        val listClass = features.merge("manage list", manageListClassName, next.manageListClassName)
        val protectRows =
            if (listClass == null) {
                emptyList()
            } else {
                (playProtectRowClassNames + next.playProtectRowClassNames).distinct()
            }
        val ratings = features.merge("ratings row", ratingsRowClassName, next.ratingsRowClassName)
        val contributions =
            features.merge(
                "contributions row",
                contributionsRowClassName,
                next.contributionsRowClassName,
            )
        val deviceSync =
            features.merge(
                "device sync row",
                deviceSyncRowClassName,
                next.deviceSyncRowClassName,
            )
        val search =
            features.merge(
                "search landing item",
                searchLandingItemClassName,
                next.searchLandingItemClassName,
            )
        if (protectRows.isNotEmpty()) features.unresolved.remove("play protect rows")
        return ManageAppsTargets(
            manageListClassName = listClass ?: manageListClassName,
            playProtectRowClassNames = protectRows,
            ratingsRowClassName = ratings.takeIf { listClass != null },
            contributionsRowClassName = contributions.takeIf { listClass != null },
            deviceSyncRowClassName = deviceSync.takeIf { listClass != null },
            searchLandingItemClassName = search.takeIf { listClass != null },
            unresolved = features.unresolved.toList(),
        )
    }
}

data class PersistentNavTargets(
    val navConstructor: ConstructorRef,
    val navItemTypeField: FieldRef,
    val navPrimaryCallbackField: FieldRef,
    val navSecondaryCallbackField: FieldRef,
    val navActionConstructor: ConstructorRef,
    val navCallbackMethod: MethodRef,
    val navHiddenInstanceField: FieldRef?,
)

private class FeatureTargets(
    reasons: List<String>,
) {
    val unresolved = reasons.toCollection(LinkedHashSet())

    fun <T> merge(
        name: String,
        current: T?,
        candidate: T?,
    ): T? {
        val conflict = "$name: conflicting targets across APKs"
        if (conflict in unresolved) {
            unresolved.removeAll { it != conflict && (it == name || it.startsWith("$name:")) }
            return null
        }
        if (current != null && candidate != null && current != candidate) {
            unresolved.removeAll { it == name || it.startsWith("$name:") }
            unresolved += conflict
            return null
        }
        val resolved = current ?: candidate
        if (resolved != null) unresolved.removeAll { it == name || it.startsWith("$name:") }
        return resolved
    }
}
