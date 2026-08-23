package eu.hxreborn.gplayadblock.discovery

internal object SampleTargets {
    private fun method(name: String) =
        MethodRef(
            className = "a$name",
            methodName = name,
            returnTypeName = "b$name",
            paramTypeNames = listOf("java.lang.String", "int"),
        )

    private fun field(name: String) = FieldRef(className = "c$name", fieldName = name)

    val RESOLVED =
        ResolvedTargets.Resolved(
            streamDataMethod = method("streamData"),
            streamDataConstructor =
                ConstructorRef(
                    className = "streamDataOwner",
                    paramTypeNames =
                        listOf(
                            "childId",
                            "presentation",
                            "java.util.List",
                            "boolean",
                            "java.lang.Throwable",
                        ),
                ),
            streamChildrenField = field("streamChildren"),
            childHandlersField = field("childHandlers"),
            childIdField = field("childId"),
            nodeField = field("node"),
            presentationAccessor = method("presentationAccessor"),
            presentationKindField = field("presentationKind"),
            presentationPayloadField = field("presentationPayload"),
            clusterCaseField = field("clusterCase"),
            clusterPayloadField = field("clusterPayload"),
            clusterServerLogsField = field("clusterServerLogs"),
            cardKindField = field("cardKind"),
            cardPayloadField = field("cardPayload"),
            cardAdMetadataFields = listOf(field("cardAdOne"), field("cardAdTwo")),
            adPresenceField = field("adPresence"),
            responseMethods = listOf(method("responseOne"), method("responseTwo")),
            responseListFields = listOf(field("responseListOne")),
            nodeWrapperKindField = field("nodeWrapperKind"),
            nodeWrapperPayloadField = field("nodeWrapperPayload"),
            nodeIdField = field("nodeId"),
            nodePresentationField = field("nodePresentation"),
            nodeChildrenField = field("nodeChildren"),
            rootWrapperKindField = field("rootWrapperKind"),
            rootWrapperPayloadField = field("rootWrapperPayload"),
            rootChildrenField = field("rootChildren"),
            childIdsField = field("childIds"),
            childPresenceField = field("childPresence"),
            childContinuationField = field("childContinuation"),
            nodeDefaultInstanceField = field("nodeDefaultInstance"),
            rootDefaultInstanceField = field("rootDefaultInstance"),
            cacheAssemblyMethod = method("cacheAssembly"),
            cacheNodeChildrenField = field("cacheNodeChildren"),
            cachePageBoundariesField = field("cachePageBoundaries"),
            cachePageBoundariesCopyMethod = method("cachePageBoundariesCopy"),
            childKeyMethod = method("childKey"),
            protobufNewBuilderMethod = method("protobufNewBuilder"),
            protobufMergeMethod = method("protobufMerge"),
            protobufBuildMethod = method("protobufBuild"),
            protobufBuilderMessageField = field("protobufBuilderMessage"),
            protobufParseMethod = method("protobufParse"),
            protobufRegistryFactory = method("protobufRegistryFactory"),
            byteStringToByteArrayMethod = method("byteStringToByteArray"),
            protobufToByteArrayMethod = method("protobufToByteArray"),
            repeatedListCopyMethod = method("repeatedListCopy"),
            suggestion =
                ResolvedTargets.Suggestion(
                    constructor =
                        ConstructorRef(
                            className = "suggestionOwner",
                            paramTypeNames = listOf("java.util.List", "int", "int"),
                        ),
                    adInfoField = field("adInfo"),
                ),
            suggestionFailure = null,
        )
}
