package eu.hxreborn.gplayadblock.discovery

sealed interface ResolvedTargets {
    data class AccountMenu(
        val featureClassName: String,
        val actionClassName: String,
        val featuresClassName: String,
        val immutableListClassName: String,
        val notificationTopBarConstructor: ConstructorRef?,
    )

    data class SelfUpdate(
        val schedulerMethod: MethodRef,
        val autoUpdateMethod: MethodRef?,
    )

    data class ManageApps(
        val manageListClassName: String,
        val playProtectRowClassNames: List<String> = emptyList(),
        val veMetadataTypeField: FieldRef? = null,
    )

    data class PersistentNav(
        val navConstructor: ConstructorRef,
        val navItemTypeField: FieldRef,
        val navPrimaryCallbackField: FieldRef,
        val navSecondaryCallbackField: FieldRef,
        val navActionConstructor: ConstructorRef,
        val navCallbackMethod: MethodRef,
        val navHiddenInstanceField: FieldRef?,
    )

    data class Suggestion(
        val constructor: ConstructorRef,
        val adInfoField: FieldRef,
    )

    data class Spotlight(
        val tabAccessor: MethodRef,
        val tabDefaultInstanceField: FieldRef,
    )

    data class Resolved(
        val streamDataMethod: MethodRef,
        val streamDataConstructor: ConstructorRef,
        val streamChildrenField: FieldRef,
        val childHandlersField: FieldRef,
        val childIdField: FieldRef,
        val nodeField: FieldRef,
        val presentationAccessor: MethodRef,
        val presentationKindField: FieldRef,
        val presentationPayloadField: FieldRef,
        val clusterCaseField: FieldRef,
        val clusterPayloadField: FieldRef,
        val clusterServerLogsField: FieldRef,
        val cardKindField: FieldRef,
        val cardPayloadField: FieldRef,
        val cardAdMetadataFields: List<FieldRef>,
        val adPresenceField: FieldRef,
        val responseMethods: List<MethodRef>,
        val responseListFields: List<FieldRef>,
        val nodeWrapperKindField: FieldRef,
        val nodeWrapperPayloadField: FieldRef,
        val nodeIdField: FieldRef,
        val nodePresentationField: FieldRef,
        val nodeChildrenField: FieldRef,
        val rootWrapperKindField: FieldRef,
        val rootWrapperPayloadField: FieldRef,
        val rootChildrenField: FieldRef,
        val childIdsField: FieldRef,
        val childPresenceField: FieldRef,
        val childContinuationField: FieldRef,
        val nodeDefaultInstanceField: FieldRef,
        val rootDefaultInstanceField: FieldRef,
        val cacheAssemblyMethod: MethodRef,
        val cacheNodeChildrenField: FieldRef,
        val cachePageBoundariesField: FieldRef,
        val cachePageBoundariesCopyMethod: MethodRef,
        val childKeyMethod: MethodRef,
        val protobufNewBuilderMethod: MethodRef,
        val protobufMergeMethod: MethodRef,
        val protobufBuildMethod: MethodRef,
        val protobufBuilderMessageField: FieldRef,
        val protobufParseMethod: MethodRef,
        val protobufRegistryFactory: MethodRef,
        val byteStringToByteArrayMethod: MethodRef,
        val protobufToByteArrayMethod: MethodRef,
        val repeatedListCopyMethod: MethodRef,
        val suggestion: Suggestion?,
        val suggestionFailure: String?,
        val spotlight: Spotlight?,
        val spotlightFailure: String?,
        val manageApps: ManageApps?,
        val manageAppsFailure: String?,
        val persistentNav: PersistentNav?,
        val persistentNavFailure: String?,
        val accountMenu: AccountMenu?,
        val accountMenuFailure: String?,
        val selfUpdate: SelfUpdate?,
        val selfUpdateFailure: String?,
    ) : ResolvedTargets

    data class Missing(
        val reason: String,
        val retryable: Boolean = false,
    ) : ResolvedTargets
}
