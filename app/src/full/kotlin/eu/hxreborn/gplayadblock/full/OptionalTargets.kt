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
            accountMenu = moreComplete(accountMenu, next.accountMenu) { it.unresolved.size },
            manageApps = moreComplete(manageApps, next.manageApps) { it.unresolved.size },
            persistentNav = persistentNav ?: next.persistentNav,
            selfUpdate = selfUpdate ?: next.selfUpdate,
        )

    private inline fun <T : Any> moreComplete(
        current: T?,
        candidate: T?,
        unresolvedCount: (T) -> Int,
    ): T? =
        when {
            current == null -> candidate
            candidate == null -> current
            unresolvedCount(candidate) < unresolvedCount(current) -> candidate
            else -> current
        }
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
)

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
)

data class PersistentNavTargets(
    val navConstructor: ConstructorRef,
    val navItemTypeField: FieldRef,
    val navPrimaryCallbackField: FieldRef,
    val navSecondaryCallbackField: FieldRef,
    val navActionConstructor: ConstructorRef,
    val navCallbackMethod: MethodRef,
    val navHiddenInstanceField: FieldRef?,
)
