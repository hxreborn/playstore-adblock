package eu.hxreborn.gplayadblock.discovery

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

object DexKitResolver {
    private const val GENERIC_CARD_MODEL =
        "com.google.android.finsky.streamclusters.genericcard.contract.GenericCardUiModel"
    private const val CACHE_CALLBACK_ANCHOR = "Can never happen."
    private const val CACHE_MANAGER_ANCHOR =
        "Failed to load a StreamNode with an ID and no URL."
    private const val DATA_STORE_CALLBACK_ANCHOR =
        "resolvedStreamOrError is null for sanitizedRequestUrl: %s \n  streamIdUrlList: %s " +
            "\n streamNodeIdUrlList: %s"
    private const val STREAM_CHILD_CONSUMER_ANCHOR =
        "No child node found for unreviewed cluster"
    private const val PROTOBUF_MERGE_ANCHOR =
        "mergeFrom(MessageLite) can only merge messages of the same type."
    private val adoptionCallbackAnchors =
        listOf(
            "END OF STREAM",
            "Something went wrong creating ResolvedStream for StreamNode.",
        )
    private val streamHandlerAnchors =
        listOf(
            "Should not call fetchStreamNode() twice.",
            "StreamNodeHandler has already been initialized.",
        )
    private val adPresentationAnchors =
        listOf(
            "ADS_DETAIL_FORMAT_CLUSTER_PRESENTATION",
            "ADS_DETAIL_FORMAT_VERTICAL_CLUSTER_PRESENTATION",
            "SEARCH_LIST_VIEW_AD_CLUSTER_PRESENTATION",
            "SPONSORED_BANNER_CLUSTER_PRESENTATION",
        )
    private val remoteSuggestionAnchors =
        listOf(
            "RemoteSuggestResult(suggestions=",
            ", adCount=",
            ", isOnDevice=",
        )
    private val appSuggestionAnchors =
        listOf(
            "AppSuggestion(query=",
            ", itemModelFlow=",
            ", itemClientStateFlow=",
            ", itemAdInfo=",
        )
    private val remoteSuggestionParameters =
        listOf(
            "java.util.List",
            "int",
            "int",
            "java.lang.Integer",
            "byte[]",
            "boolean",
            "boolean",
            "int",
        )
    private val primitiveTypes =
        setOf(
            "boolean",
            "byte",
            "char",
            "double",
            "float",
            "int",
            "long",
            "short",
            "void",
        )

    private val nativeLoadFailure: String? by lazy {
        try {
            System.loadLibrary("dexkit")
            null
        } catch (error: UnsatisfiedLinkError) {
            "libdexkit.so failed to load: ${error.message}"
        }
    }

    fun resolve(apkPaths: List<String>): ResolvedTargets {
        nativeLoadFailure?.let { return ResolvedTargets.Missing(it) }
        val failures = ArrayList<String>()
        for (apkPath in apkPaths.distinct()) {
            try {
                DexKitBridge.create(apkPath).use { bridge ->
                    when (val result = resolve(bridge)) {
                        is ResolvedTargets.Resolved -> return result
                        is ResolvedTargets.Missing -> failures += "$apkPath: ${result.reason}"
                    }
                }
            } catch (exception: Exception) {
                failures += "$apkPath: ${exception.javaClass.simpleName}: ${exception.message}"
            }
        }
        return ResolvedTargets.Missing(failures.joinToString(" | ").ifBlank { "no APK paths" })
    }

    private fun resolve(bridge: DexKitBridge): ResolvedTargets {
        val handlerClasses =
            bridge.findClass {
                matcher {
                    usingStrings = streamHandlerAnchors
                }
            }
        val handlerClass =
            handlerClasses.singleOrNull()
                ?: return missing("stream handler candidates=${handlerClasses.size}")
        val handlerSuperClass =
            handlerClass.superClass
                ?: return missing("stream handler has no dex superclass")

        val streamMethods =
            handlerClass.methods.filter { method ->
                Modifier.isPrivate(method.modifiers) &&
                    method.paramTypeNames == listOf("java.lang.Throwable") &&
                    streamDataConstructor(method) != null
            }
        val streamMethod =
            streamMethods.singleOrNull()
                ?: return missing("stream data method candidates=${streamMethods.size}")
        val streamConstructor =
            streamDataConstructor(streamMethod)
                ?: return missing("stream data constructor not found")
        val streamDataClass =
            streamMethod.returnType
                ?: return missing("stream data class not found")
        val streamChildConsumers =
            bridge
                .findMethod {
                    matcher {
                        usingStrings = listOf(STREAM_CHILD_CONSUMER_ANCHOR)
                    }
                }.filter { method ->
                    method.paramTypeNames == listOf(streamDataClass.name)
                }
        val streamChildConsumer =
            streamChildConsumers.singleOrNull()
                ?: return missing("stream child consumers=${streamChildConsumers.size}")
        val streamChildrenFields =
            streamChildConsumer.usingFields
                .filter { usage -> usage.usingType.isRead() }
                .map { usage -> usage.field }
                .filter { field ->
                    !field.isStatic && field.declaredClassName == streamDataClass.name
                }.distinctBy(FieldData::descriptor)
        val streamChildrenField =
            streamChildrenFields.singleOrNull()
                ?: return missing("stream children fields=${streamChildrenFields.size}")

        val childHandlersField =
            handlerSuperClass.fields.singleOrNull { field ->
                !field.isStatic && field.typeName == "${handlerClass.name}[]"
            } ?: return missing("child handler array field not found")
        val childIdType = streamConstructor.paramTypeNames[0]
        val childIdField =
            handlerClass.fields.singleOrNull { field ->
                !field.isStatic && field.typeName == childIdType
            } ?: return missing("child ID field not found")

        val nodeAccessors =
            streamMethod.invokes
                .filter { method ->
                    method.declaredClassName == handlerSuperClass.name &&
                        method.paramCount == 0 &&
                        method.returnTypeName !in primitiveTypes
                }.distinctBy(MethodData::descriptor)
        val nodeAccessor =
            nodeAccessors.singleOrNull()
                ?: return missing("node accessor candidates=${nodeAccessors.size}")
        val nodeClass =
            nodeAccessor.returnType
                ?: return missing("node proto class not found")
        val nodeField =
            handlerSuperClass.fields.singleOrNull { field ->
                !field.isStatic && field.typeName == nodeClass.name
            } ?: return missing("node proto field not found")

        val presentationType = streamConstructor.paramTypeNames[1]
        val presentationAccessors =
            nodeClass.methods.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.paramCount == 0 &&
                    method.returnTypeName == presentationType
            }
        val presentationAccessor =
            presentationAccessors.singleOrNull()
                ?: return missing("presentation accessor candidates=${presentationAccessors.size}")
        val presentationClass =
            presentationAccessor.returnType
                ?: return missing("presentation wrapper class not found")
        val presentationKindField =
            presentationClass.fields.singleOrNull { field ->
                !field.isStatic && field.typeName == "int"
            } ?: return missing("presentation kind field not found")
        val presentationPayloadField =
            presentationClass.fields.singleOrNull { field ->
                !field.isStatic && field.typeName == "java.lang.Object"
            } ?: return missing("presentation payload field not found")

        val enumClasses =
            bridge.findClass {
                matcher {
                    usingStrings = adPresentationAnchors
                }
            }
        val enumClass =
            enumClasses.singleOrNull()
                ?: return missing("ad presentation enum candidates=${enumClasses.size}")
        val enumMappers =
            enumClass.methods.filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.paramTypeNames == listOf("int") &&
                    method.returnTypeName == enumClass.name
            }
        val enumMapper =
            enumMappers.singleOrNull()
                ?: return missing("presentation mapper candidates=${enumMappers.size}")
        val caseFieldScores =
            enumMapper.callers
                .asSequence()
                .flatMap { caller ->
                    caller.usingFields
                        .asSequence()
                        .map { usingField -> usingField.field }
                        .filter { field ->
                            !field.isStatic &&
                                field.typeName == "int" &&
                                field.declaredClass.fields.count { declaredField ->
                                    !declaredField.isStatic && declaredField.typeName == "int"
                                } >= 2
                        }
                }.groupingBy(FieldData::descriptor)
                .eachCount()
        val caseFieldsByDescriptor =
            enumMapper.callers
                .asSequence()
                .flatMap { caller -> caller.usingFields.asSequence() }
                .map { usingField -> usingField.field }
                .associateBy(FieldData::descriptor)
        val rankedCaseFields =
            caseFieldScores.entries.sortedByDescending(Map.Entry<String, Int>::value)
        val topCaseField = rankedCaseFields.firstOrNull()
        if (topCaseField == null || rankedCaseFields.getOrNull(1)?.value == topCaseField.value) {
            return missing("cluster case field was not unique")
        }
        val clusterCaseField =
            caseFieldsByDescriptor[topCaseField.key]
                ?: return missing("cluster case field metadata missing")
        val clusterPayloadFields =
            clusterCaseField.declaredClass
                ?.fields
                .orEmpty()
                .filter { field ->
                    !field.isStatic && field.typeName == "java.lang.Object"
                }
        val clusterPayloadField =
            clusterPayloadFields.singleOrNull()
                ?: return missing("cluster payload fields=${clusterPayloadFields.size}")

        val genericCardModel =
            bridge.getClassData(GENERIC_CARD_MODEL)
                ?: return missing("generic card model missing")
        val genericCardConstructors = genericCardModel.methods.filter(MethodData::isConstructor)
        val genericCardConstructor =
            genericCardConstructors.singleOrNull()
                ?: return missing("generic card constructors=${genericCardConstructors.size}")
        val genericCardBuilders = genericCardConstructor.callers
        val genericCardBuilder =
            genericCardBuilders.singleOrNull()
                ?: return missing("generic card builders=${genericCardBuilders.size}")
        val genericCardAccessors =
            genericCardBuilder.invokes
                .filter { method ->
                    method.declaredClassName == genericCardBuilder.declaredClassName &&
                        Modifier.isPrivate(method.modifiers) &&
                        Modifier.isStatic(method.modifiers) &&
                        method.paramTypeNames == listOf(streamMethod.returnTypeName) &&
                        method.returnTypeName !in primitiveTypes
                }.distinctBy(MethodData::descriptor)
        val genericCardAccessor =
            genericCardAccessors.singleOrNull()
                ?: return missing("generic card accessors=${genericCardAccessors.size}")
        val genericCardPayloadClass =
            genericCardAccessor.returnType
                ?: return missing("generic card payload class missing")
        val adMetadataFields =
            genericCardPayloadClass.fields.filter { field ->
                !field.isStatic && field.typeName !in primitiveTypes
            }
        val genericAdMetadataField =
            adMetadataFields.singleOrNull()
                ?: return missing("generic ad metadata fields=${adMetadataFields.size}")
        val adMetadataClass =
            genericAdMetadataField.type
                ?: return missing("ad metadata class missing")
        val adMetadataBaseClass =
            adMetadataClass.superClass
                ?: return missing("ad metadata base class missing")
        val adPresenceFields =
            adMetadataClass.fields.filter { field ->
                !field.isStatic && field.typeName == "int"
            }
        val adPresenceField =
            adPresenceFields.singleOrNull()
                ?: return missing("ad presence fields=${adPresenceFields.size}")
        val genericCardEvidenceMethods =
            bridge.findMethod {
                matcher {
                    descriptor = genericCardBuilder.descriptor
                    usingStrings = listOf("#ad")
                    usingNumbers = listOf(2)
                }
            }
        if (genericCardEvidenceMethods.size != 1) {
            return missing("generic card evidence methods=${genericCardEvidenceMethods.size}")
        }
        val genericCardEvidenceFields =
            genericCardBuilder.usingFields
                .map { usage -> usage.field.descriptor }
                .toSet()
        if (genericAdMetadataField.descriptor !in genericCardEvidenceFields ||
            adPresenceField.descriptor !in genericCardEvidenceFields
        ) {
            return missing("generic card evidence fields missing")
        }

        val cardWrapperCandidates =
            genericCardAccessor.usingFields
                .map { usage -> usage.field }
                .filter { field -> !field.isStatic }
                .groupBy(FieldData::declaredClassName)
                .filter { (className, fields) ->
                    className != streamMethod.returnTypeName &&
                        className != presentationClass.name &&
                        className != genericCardPayloadClass.name &&
                        fields.any { field -> field.typeName == "int" } &&
                        fields.any { field -> field.typeName == "java.lang.Object" }
                }
        val cardWrapperFields =
            cardWrapperCandidates.values.singleOrNull()
                ?: return missing("card wrapper candidates=${cardWrapperCandidates.size}")
        val cardKindFields = cardWrapperFields.filter { field -> field.typeName == "int" }
        val cardKindField =
            cardKindFields.singleOrNull()
                ?: return missing("card kind fields=${cardKindFields.size}")
        val cardPayloadFields =
            cardWrapperFields.filter { field -> field.typeName == "java.lang.Object" }
        val cardPayloadField =
            cardPayloadFields.singleOrNull()
                ?: return missing("card payload fields=${cardPayloadFields.size}")
        val cardAdMetadataFields =
            bridge
                .findField {
                    matcher {
                        type = adMetadataClass.name
                    }
                }.filter { field ->
                    !field.isStatic &&
                        field.declaredClass.superClass?.name == adMetadataBaseClass.name
                }
        val duplicateAdMetadataHolders =
            cardAdMetadataFields.groupBy(FieldData::declaredClassName).filterValues { fields ->
                fields.size != 1
            }
        if (duplicateAdMetadataHolders.isNotEmpty()) {
            return missing("duplicate card metadata holders=${duplicateAdMetadataHolders.size}")
        }
        if (cardAdMetadataFields.none { field ->
                field.descriptor == genericAdMetadataField.descriptor
            }
        ) {
            return missing("generic card metadata field absent from global field search")
        }

        val cacheCallbackMethods =
            bridge
                .findMethod {
                    matcher {
                        usingStrings = listOf(CACHE_CALLBACK_ANCHOR)
                    }
                }.filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.paramCount == 1 &&
                        method.returnTypeName == "void"
                }
        val cacheCallbackMethod =
            cacheCallbackMethods.singleOrNull()
                ?: return missing("cache callback methods=${cacheCallbackMethods.size}")
        val responseClass =
            cacheCallbackMethod.paramTypes.singleOrNull()
                ?: return missing("response class missing")
        val cacheManagerClasses =
            bridge.findClass {
                matcher {
                    usingStrings = listOf(CACHE_MANAGER_ANCHOR)
                }
            }
        val cacheManagerClass =
            cacheManagerClasses.singleOrNull()
                ?: return missing("cache manager candidates=${cacheManagerClasses.size}")
        val cacheCallbackConstructors =
            cacheCallbackMethod.declaredClass
                ?.methods
                ?.filter { method ->
                    method.isConstructor &&
                        method.paramTypeNames.size == 4 &&
                        method.paramTypeNames[0] == "java.lang.String" &&
                        method.paramTypeNames[1] == cacheManagerClass.name
                }.orEmpty()
        if (cacheCallbackConstructors.size != 1) {
            return missing("cache callback constructors=${cacheCallbackConstructors.size}")
        }

        val dataStoreWorkerMethods =
            bridge
                .findMethod {
                    matcher {
                        usingStrings = listOf(DATA_STORE_CALLBACK_ANCHOR)
                    }
                }
        val dataStoreCallbackMethods =
            dataStoreWorkerMethods
                .asSequence()
                .mapNotNull(MethodData::declaredClass)
                .flatMap { workerClass ->
                    workerClass.methods
                        .asSequence()
                        .filter(MethodData::isConstructor)
                        .flatMap { constructor -> constructor.callers.asSequence() }
                }.filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.paramTypeNames == listOf(responseClass.name) &&
                        method.returnTypeName == "void"
                }.distinctBy(MethodData::descriptor)
                .toList()
        val dataStoreCallbackMethod =
            dataStoreCallbackMethods.singleOrNull()
                ?: return missing(
                    "data store workers=${dataStoreWorkerMethods.size} " +
                        "callbacks=${dataStoreCallbackMethods.size}",
                )
        val adoptionCallbackClasses =
            bridge.findClass {
                matcher {
                    usingStrings = adoptionCallbackAnchors
                }
            }
        val adoptionCallbackClass =
            adoptionCallbackClasses.singleOrNull()
                ?: return missing("adoption callback classes=${adoptionCallbackClasses.size}")
        val adoptionCallbackMethods =
            adoptionCallbackClass.methods.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.paramTypeNames == listOf(responseClass.name) &&
                    method.returnTypeName == "void"
            }
        val adoptionCallbackMethod =
            adoptionCallbackMethods.singleOrNull()
                ?: return missing("adoption callback methods=${adoptionCallbackMethods.size}")
        val responseMethods =
            listOf(cacheCallbackMethod, dataStoreCallbackMethod, adoptionCallbackMethod)
                .distinctBy(MethodData::descriptor)
        if (responseMethods.size != 3) {
            return missing("response callback target count=${responseMethods.size}")
        }

        val responseListFields =
            cacheCallbackMethod.usingFields
                .map { usage -> usage.field }
                .filter { field ->
                    !field.isStatic && field.declaredClassName == responseClass.name
                }.distinctBy(FieldData::descriptor)
        if (responseListFields.size != 2 ||
            responseListFields.map(FieldData::typeName).distinct().size != 1
        ) {
            return missing("response list fields=${responseListFields.size}")
        }
        val repeatedListType = responseListFields.first().typeName
        val repeatedListClass = responseListFields.first().type
        val repeatedListCopyMethods =
            repeatedListClass.methods.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.paramTypeNames == listOf("int") &&
                    method.returnTypeName == repeatedListClass.name
            }
        val repeatedListCopyMethod =
            repeatedListCopyMethods.singleOrNull()
                ?: return missing("repeated list copy methods=${repeatedListCopyMethods.size}")

        val nodeWrapperClasses =
            cacheCallbackMethod.usingFields
                .map { usage -> usage.field }
                .filter { field -> !field.isStatic && field.typeName == childIdType }
                .map(FieldData::declaredClass)
                .filterNotNull()
                .filter { candidate ->
                    candidate.fields.any { field ->
                        !field.isStatic && field.typeName == "java.lang.Object"
                    } &&
                        candidate.fields.count { field ->
                            !field.isStatic && field.typeName == "int"
                        } >= 2
                }.distinctBy(ClassData::descriptor)
        val nodeWrapperClass =
            nodeWrapperClasses.singleOrNull()
                ?: return missing("node wrapper candidates=${nodeWrapperClasses.size}")
        val nodeIdFields =
            nodeWrapperClass.fields.filter { field ->
                !field.isStatic && field.typeName == childIdType
            }
        val nodeIdField =
            nodeIdFields.singleOrNull()
                ?: return missing("node ID fields=${nodeIdFields.size}")
        val nodeWrapperKindFields =
            cacheCallbackMethod.usingFields
                .map { usage -> usage.field }
                .filter { field ->
                    !field.isStatic &&
                        field.declaredClassName == nodeWrapperClass.name &&
                        field.typeName == "int"
                }.distinctBy(FieldData::descriptor)
        val nodeWrapperKindField =
            nodeWrapperKindFields.singleOrNull()
                ?: return missing("node wrapper kind fields=${nodeWrapperKindFields.size}")
        val nodeWrapperPayloadFields =
            nodeWrapperClass.fields.filter { field ->
                !field.isStatic && field.typeName == "java.lang.Object"
            }
        val nodeWrapperPayloadField =
            nodeWrapperPayloadFields.singleOrNull()
                ?: return missing("node wrapper payload fields=${nodeWrapperPayloadFields.size}")

        val decodedNodeClasses =
            cacheCallbackMethod.usingFields
                .map { usage -> usage.field }
                .map(FieldData::declaredClass)
                .filter { candidate ->
                    candidate.fields.any { field ->
                        !field.isStatic && field.typeName == presentationClass.name
                    }
                }.distinctBy(ClassData::descriptor)
        val decodedNodeClass =
            decodedNodeClasses.singleOrNull()
                ?: return missing("decoded node classes=${decodedNodeClasses.size}")
        val nodePresentationFields =
            decodedNodeClass.fields.filter { field ->
                !field.isStatic && field.typeName == presentationClass.name
            }
        val nodePresentationField =
            nodePresentationFields.singleOrNull()
                ?: return missing("node presentation fields=${nodePresentationFields.size}")

        val childContainerClasses =
            cacheCallbackMethod.usingFields
                .map { usage -> usage.field }
                .filter { field ->
                    !field.isStatic &&
                        field.typeName == repeatedListType &&
                        field.declaredClassName != responseClass.name
                }.map(FieldData::declaredClass)
                .filterNotNull()
                .filter { candidate ->
                    candidate.fields.any { field ->
                        !field.isStatic && field.typeName == "java.lang.String"
                    } &&
                        candidate.fields.any { field ->
                            !field.isStatic && field.typeName == "int"
                        }
                }.distinctBy(ClassData::descriptor)
        val childContainerClass =
            childContainerClasses.singleOrNull()
                ?: return missing("child container candidates=${childContainerClasses.size}")
        val childIdsFields =
            childContainerClass.fields.filter { field ->
                !field.isStatic && field.typeName == repeatedListType
            }
        val childIdsField =
            childIdsFields.singleOrNull()
                ?: return missing("child ID list fields=${childIdsFields.size}")
        val childPresenceFields =
            childContainerClass.fields.filter { field ->
                !field.isStatic && field.typeName == "int"
            }
        val childPresenceField =
            childPresenceFields.singleOrNull()
                ?: return missing("child presence fields=${childPresenceFields.size}")
        val childContinuationFields =
            childContainerClass.fields.filter { field ->
                !field.isStatic && field.typeName == "java.lang.String"
            }
        val childContinuationField =
            childContinuationFields.singleOrNull()
                ?: return missing("child continuation fields=${childContinuationFields.size}")
        val nodeChildrenFields =
            decodedNodeClass.fields.filter { field ->
                !field.isStatic && field.typeName == childContainerClass.name
            }
        val nodeChildrenField =
            nodeChildrenFields.singleOrNull()
                ?: return missing("node children fields=${nodeChildrenFields.size}")
        val rootChildrenFields =
            cacheCallbackMethod.usingFields
                .map { usage -> usage.field }
                .filter { field ->
                    !field.isStatic &&
                        field.typeName == childContainerClass.name &&
                        field.declaredClassName != decodedNodeClass.name
                }.distinctBy(FieldData::descriptor)
        val rootChildrenField =
            rootChildrenFields.singleOrNull()
                ?: return missing("root children fields=${rootChildrenFields.size}")
        val decodedRootClass = rootChildrenField.declaredClass

        val rootWrapperClasses =
            cacheCallbackMethod.usingFields
                .map { usage -> usage.field }
                .filter { field ->
                    !field.isStatic &&
                        field.typeName == "java.lang.Object" &&
                        field.declaredClassName != nodeWrapperClass.name &&
                        field.declaredClassName != presentationClass.name &&
                        field.declaredClassName != cardKindField.declaredClassName
                }.map(FieldData::declaredClass)
                .filterNotNull()
                .filter { candidate ->
                    candidate.fields.any { field ->
                        !field.isStatic && field.typeName == "java.lang.String"
                    } &&
                        candidate.fields.count { field ->
                            !field.isStatic && field.typeName == "int"
                        } >= 2
                }.distinctBy(ClassData::descriptor)
        val rootWrapperClass =
            rootWrapperClasses.singleOrNull()
                ?: return missing("root wrapper candidates=${rootWrapperClasses.size}")
        val rootWrapperKindFields =
            cacheCallbackMethod.usingFields
                .map { usage -> usage.field }
                .filter { field ->
                    !field.isStatic &&
                        field.declaredClassName == rootWrapperClass.name &&
                        field.typeName == "int"
                }.distinctBy(FieldData::descriptor)
        val rootWrapperKindField =
            rootWrapperKindFields.singleOrNull()
                ?: return missing("root wrapper kind fields=${rootWrapperKindFields.size}")
        val rootWrapperPayloadFields =
            rootWrapperClass.fields.filter { field ->
                !field.isStatic && field.typeName == "java.lang.Object"
            }
        val rootWrapperPayloadField =
            rootWrapperPayloadFields.singleOrNull()
                ?: return missing("root wrapper payload fields=${rootWrapperPayloadFields.size}")

        val nodeDefaultInstanceFields =
            decodedNodeClass.fields.filter { field ->
                field.isStatic && field.typeName == decodedNodeClass.name
            }
        val nodeDefaultInstanceField =
            nodeDefaultInstanceFields.singleOrNull()
                ?: return missing("node default fields=${nodeDefaultInstanceFields.size}")
        val rootDefaultInstanceFields =
            decodedRootClass
                ?.fields
                ?.filter { field ->
                    field.isStatic && field.typeName == decodedRootClass.name
                }.orEmpty()
        val rootDefaultInstanceField =
            rootDefaultInstanceFields.singleOrNull()
                ?: return missing("root default fields=${rootDefaultInstanceFields.size}")

        val cacheNodeChildrenFields =
            nodeClass.fields.filter { field ->
                !field.isStatic && field.typeName == childContainerClass.name
            }
        val cacheNodeChildrenField =
            cacheNodeChildrenFields.singleOrNull()
                ?: return missing("cache node children fields=${cacheNodeChildrenFields.size}")
        val cachePageBoundariesFields =
            nodeClass.fields.filter { field ->
                !field.isStatic &&
                    field.type?.methods?.any { method ->
                        !Modifier.isStatic(method.modifiers) &&
                            method.paramTypeNames == listOf("int") &&
                            method.returnTypeName == "int"
                    } == true &&
                    field.type?.methods?.any { method ->
                        !Modifier.isStatic(method.modifiers) &&
                            method.paramTypeNames == listOf("int") &&
                            method.returnTypeName == field.typeName
                    } == true
            }
        val cachePageBoundariesField =
            cachePageBoundariesFields.singleOrNull()
                ?: return missing("cache page boundary fields=${cachePageBoundariesFields.size}")
        val cachePageBoundariesCopyMethods =
            cachePageBoundariesField.type
                ?.methods
                ?.filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.paramTypeNames == listOf("int") &&
                        method.returnTypeName == cachePageBoundariesField.typeName
                }.orEmpty()
        val cachePageBoundariesCopyMethod =
            cachePageBoundariesCopyMethods.singleOrNull()
                ?: return missing(
                    "cache page boundary copy methods=${cachePageBoundariesCopyMethods.size}",
                )

        val cacheAssemblyMethods =
            bridge
                .findMethod {
                    matcher {
                        paramTypes =
                            listOf(
                                null,
                                "java.lang.String",
                                nodeClass.name,
                                "java.util.List",
                                "java.util.Map",
                                null,
                                "boolean",
                                "boolean",
                                null,
                                "java.util.Map",
                                "java.lang.String",
                                "java.lang.String",
                                null,
                                "int",
                            )
                    }
                }.filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.paramTypeNames[0] == method.declaredClassName
                }
        val cacheAssemblyMethod =
            cacheAssemblyMethods.singleOrNull()
                ?: return missing("cache assembly methods=${cacheAssemblyMethods.size}")
        val cacheReaderCallers =
            cacheAssemblyMethod.callers.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.paramCount == 0 &&
                    method.returnTypeName == "void" &&
                    method.declaredClassName != cacheAssemblyMethod.declaredClassName
            }
        if (cacheReaderCallers.size != 1) {
            return missing("cache reader callers=${cacheReaderCallers.size}")
        }
        val childKeyMethods =
            cacheAssemblyMethod.declaredClass
                ?.methods
                .orEmpty()
                .asSequence()
                .flatMap { method -> method.invokes.asSequence() }
                .filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.paramTypeNames == listOf(childIdType) &&
                        method.returnTypeName == "java.lang.String"
                }.distinctBy(MethodData::descriptor)
                .toList()
        val childKeyMethod =
            childKeyMethods.singleOrNull()
                ?: return missing("child key methods=${childKeyMethods.size}")

        val protobufMessageClass =
            responseClass.superClass
                ?: return missing("protobuf message base class missing")
        val protobufSerializableClass =
            protobufMessageClass.superClass
                ?: return missing("protobuf serializable base class missing")
        val protobufToByteArrayMethods =
            protobufSerializableClass.methods.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.paramCount == 0 &&
                    method.returnTypeName == "byte[]"
            }
        val protobufToByteArrayMethod =
            protobufToByteArrayMethods.singleOrNull()
                ?: return missing(
                    "protobuf byte array methods=${protobufToByteArrayMethods.size}",
                )
        val protobufNewBuilderMethods =
            protobufMessageClass.methods.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.paramCount == 0 &&
                    method.returnType?.fields?.count { field ->
                        !field.isStatic && field.typeName == protobufMessageClass.name
                    } == 2
            }
        val protobufNewBuilderMethod =
            protobufNewBuilderMethods.singleOrNull()
                ?: return missing("protobuf new builder methods=${protobufNewBuilderMethods.size}")
        val protobufBuilderClass =
            protobufNewBuilderMethod.returnType
                ?: return missing("protobuf builder class missing")
        val protobufBuilderMessageFields =
            protobufBuilderClass.fields.filter { field ->
                !field.isStatic &&
                    !Modifier.isFinal(field.modifiers) &&
                    field.typeName == protobufMessageClass.name
            }
        val protobufBuilderMessageField =
            protobufBuilderMessageFields.singleOrNull()
                ?: return missing(
                    "protobuf builder message fields=${protobufBuilderMessageFields.size}",
                )
        val protobufMergeMethods =
            protobufBuilderClass.methods
                .filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.paramTypeNames == listOf(protobufMessageClass.name) &&
                        method.returnTypeName == "void" &&
                        PROTOBUF_MERGE_ANCHOR in method.usingStrings
                }.distinctBy(MethodData::descriptor)
        val protobufMergeMethod =
            protobufMergeMethods.singleOrNull()
                ?: return missing("protobuf merge methods=${protobufMergeMethods.size}")
        val protobufBuildMethods =
            cacheCallbackMethod.invokes
                .filter { method ->
                    method.declaredClassName == protobufBuilderClass.name &&
                        method.paramCount == 0 &&
                        method.returnTypeName == protobufMessageClass.name
                }.distinctBy(MethodData::descriptor)
        val protobufBuildMethod =
            protobufBuildMethods.singleOrNull()
                ?: return missing("protobuf build methods=${protobufBuildMethods.size}")
        val protobufParseMethods =
            protobufMessageClass.methods.filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.paramTypeNames.size == 5 &&
                    method.paramTypeNames[0] == protobufMessageClass.name &&
                    method.paramTypeNames[1] == "byte[]" &&
                    method.paramTypeNames[2] == "int" &&
                    method.paramTypeNames[3] == "int" &&
                    method.returnTypeName == protobufMessageClass.name
            }
        val protobufParseMethod =
            protobufParseMethods.singleOrNull()
                ?: return missing("protobuf parse methods=${protobufParseMethods.size}")
        val registryClass =
            protobufParseMethod.paramTypes.getOrNull(4)
                ?: return missing("protobuf registry class missing")
        val protobufRegistryFactories =
            registryClass.methods.filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.paramCount == 0 &&
                    method.returnTypeName == registryClass.name
            }
        val protobufRegistryFactory =
            protobufRegistryFactories.singleOrNull()
                ?: return missing("protobuf registry factories=${protobufRegistryFactories.size}")

        val protobufBuilderBaseClass =
            protobufBuilderClass.superClass
                ?: return missing("protobuf builder base class missing")
        val byteStringConsumerMethods =
            cacheCallbackMethod.invokes
                .filter { method ->
                    method.declaredClassName == protobufBuilderBaseClass.name &&
                        !Modifier.isStatic(method.modifiers) &&
                        method.paramCount == 1 &&
                        method.returnTypeName == "void" &&
                        method.paramTypes.single().methods.any { parameterMethod ->
                            !Modifier.isStatic(parameterMethod.modifiers) &&
                                parameterMethod.paramCount == 0 &&
                                parameterMethod.returnTypeName == "byte[]"
                        }
                }.distinctBy(MethodData::descriptor)
        val byteStringConsumerMethod =
            byteStringConsumerMethods.singleOrNull()
                ?: return missing("byte string consumers=${byteStringConsumerMethods.size}")
        val byteStringClass = byteStringConsumerMethod.paramTypes.single()
        val byteStringToByteArrayMethods =
            byteStringClass.methods
                .filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.paramCount == 0 &&
                        method.returnTypeName == "byte[]"
                }.distinctBy(MethodData::descriptor)
        val byteStringToByteArrayMethod =
            byteStringToByteArrayMethods.singleOrNull()
                ?: return missing(
                    "byte string conversion methods=${byteStringToByteArrayMethods.size}",
                )
        val clusterServerLogsFields =
            clusterCaseField.declaredClass
                ?.fields
                .orEmpty()
                .filter { field ->
                    !field.isStatic && field.typeName == byteStringClass.name
                }
        val clusterServerLogsField =
            clusterServerLogsFields.singleOrNull()
                ?: return missing(
                    "cluster server logs fields=${clusterServerLogsFields.size}",
                )

        val remoteSuggestionClasses =
            bridge.findClass {
                matcher {
                    usingStrings = remoteSuggestionAnchors
                }
            }
        val remoteSuggestionClass =
            remoteSuggestionClasses.singleOrNull()
                ?: return missing("remote suggestion candidates=${remoteSuggestionClasses.size}")
        val remoteSuggestionConstructors =
            remoteSuggestionClass.methods.filter { method ->
                method.isConstructor && method.paramTypeNames == remoteSuggestionParameters
            }
        val remoteSuggestionConstructor =
            remoteSuggestionConstructors.singleOrNull()
                ?: return missing(
                    "remote suggestion constructors=${remoteSuggestionConstructors.size}",
                )
        val remoteConstructorEvidence =
            bridge.findMethod {
                matcher {
                    descriptor = remoteSuggestionConstructor.descriptor
                    usingNumbers = listOf(-1, 2, 4, 8, 16, 32, 64)
                }
            }
        if (remoteConstructorEvidence.size != 1) {
            return missing("remote constructor evidence=${remoteConstructorEvidence.size}")
        }
        val remoteFieldTypes =
            remoteSuggestionClass.fields
                .filter { field -> !field.isStatic }
                .groupingBy(FieldData::typeName)
                .eachCount()
        val expectedRemoteFieldTypes =
            mapOf(
                "java.util.List" to 1,
                "int" to 2,
                "java.lang.Integer" to 1,
                "byte[]" to 1,
                "boolean" to 2,
            )
        if (remoteFieldTypes != expectedRemoteFieldTypes) {
            return missing("remote suggestion field shape=$remoteFieldTypes")
        }

        val appSuggestionClasses =
            bridge.findClass {
                matcher {
                    usingStrings = appSuggestionAnchors
                }
            }
        val appSuggestionClass =
            appSuggestionClasses.singleOrNull()
                ?: return missing("app suggestion candidates=${appSuggestionClasses.size}")
        val appSuggestionConstructors =
            appSuggestionClass.methods.filter { method ->
                method.isConstructor &&
                    method.paramTypeNames.size == 12 &&
                    method.paramTypeNames[0] == "java.lang.String" &&
                    method.paramTypeNames[1] == "int" &&
                    method.paramTypeNames[3] == "int" &&
                    method.paramTypeNames[5] == "boolean" &&
                    method.paramTypeNames[6] == "boolean"
            }
        val appSuggestionConstructor =
            appSuggestionConstructors.singleOrNull()
                ?: return missing("app suggestion constructors=${appSuggestionConstructors.size}")
        val adInfoType = appSuggestionConstructor.paramTypeNames[11]
        val suggestionAdInfoFields =
            appSuggestionClass.fields.filter { field ->
                !field.isStatic && field.typeName == adInfoType
            }
        val suggestionAdInfoField =
            suggestionAdInfoFields.singleOrNull()
                ?: return missing("suggestion ad info fields=${suggestionAdInfoFields.size}")
        val metadataAdInfoFields =
            adMetadataClass.fields.filter { field ->
                !field.isStatic && field.typeName == adInfoType
            }
        val metadataAdInfoField =
            metadataAdInfoFields.singleOrNull()
                ?: return missing("metadata ad info fields=${metadataAdInfoFields.size}")
        val adInfoAccessorMethods =
            metadataAdInfoField.readers
                .filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.paramCount == 1 &&
                        method.returnTypeName == adInfoType &&
                        method.usingFields.any { usage ->
                            usage.field.descriptor == adPresenceField.descriptor
                        }
                }.distinctBy(MethodData::descriptor)
        if (adInfoAccessorMethods.size != 1) {
            return missing("ad info accessor methods=${adInfoAccessorMethods.size}")
        }
        val commonSuggestionProducers =
            remoteSuggestionConstructor.callers
                .map(MethodData::declaredClassName)
                .toSet()
                .intersect(
                    appSuggestionConstructor.callers
                        .map(MethodData::declaredClassName)
                        .filterNot { className -> className == appSuggestionClass.name }
                        .toSet(),
                )
        if (commonSuggestionProducers.size != 1) {
            return missing("common suggestion producers=${commonSuggestionProducers.size}")
        }

        return ResolvedTargets.Resolved(
            streamDataMethod = streamMethod.toRef(),
            streamChildrenField = streamChildrenField.toRef(),
            childHandlersField = childHandlersField.toRef(),
            childIdField = childIdField.toRef(),
            nodeField = nodeField.toRef(),
            presentationAccessor = presentationAccessor.toRef(),
            presentationKindField = presentationKindField.toRef(),
            presentationPayloadField = presentationPayloadField.toRef(),
            clusterCaseField = clusterCaseField.toRef(),
            clusterPayloadField = clusterPayloadField.toRef(),
            clusterServerLogsField = clusterServerLogsField.toRef(),
            cardKindField = cardKindField.toRef(),
            cardPayloadField = cardPayloadField.toRef(),
            cardAdMetadataFields = cardAdMetadataFields.map { field -> field.toRef() },
            adPresenceField = adPresenceField.toRef(),
            responseMethods = responseMethods.map { method -> method.toRef() },
            responseListFields = responseListFields.map { field -> field.toRef() },
            nodeWrapperKindField = nodeWrapperKindField.toRef(),
            nodeWrapperPayloadField = nodeWrapperPayloadField.toRef(),
            nodeIdField = nodeIdField.toRef(),
            nodePresentationField = nodePresentationField.toRef(),
            nodeChildrenField = nodeChildrenField.toRef(),
            rootWrapperKindField = rootWrapperKindField.toRef(),
            rootWrapperPayloadField = rootWrapperPayloadField.toRef(),
            rootChildrenField = rootChildrenField.toRef(),
            childIdsField = childIdsField.toRef(),
            childPresenceField = childPresenceField.toRef(),
            childContinuationField = childContinuationField.toRef(),
            nodeDefaultInstanceField = nodeDefaultInstanceField.toRef(),
            rootDefaultInstanceField = rootDefaultInstanceField.toRef(),
            cacheAssemblyMethod = cacheAssemblyMethod.toRef(),
            cacheNodeChildrenField = cacheNodeChildrenField.toRef(),
            cachePageBoundariesField = cachePageBoundariesField.toRef(),
            cachePageBoundariesCopyMethod = cachePageBoundariesCopyMethod.toRef(),
            childKeyMethod = childKeyMethod.toRef(),
            protobufNewBuilderMethod = protobufNewBuilderMethod.toRef(),
            protobufMergeMethod = protobufMergeMethod.toRef(),
            protobufBuildMethod = protobufBuildMethod.toRef(),
            protobufBuilderMessageField = protobufBuilderMessageField.toRef(),
            protobufParseMethod = protobufParseMethod.toRef(),
            protobufRegistryFactory = protobufRegistryFactory.toRef(),
            byteStringToByteArrayMethod = byteStringToByteArrayMethod.toRef(),
            protobufToByteArrayMethod = protobufToByteArrayMethod.toRef(),
            repeatedListCopyMethod = repeatedListCopyMethod.toRef(),
            searchSuggestionConstructor = remoteSuggestionConstructor.toConstructorRef(),
            suggestionAdInfoField = suggestionAdInfoField.toRef(),
        )
    }

    private fun streamDataConstructor(method: MethodData): MethodData? =
        method.returnType?.methods?.singleOrNull { constructor ->
            constructor.isConstructor &&
                constructor.paramTypeNames.size == 5 &&
                constructor.paramTypeNames[2] == "java.util.List" &&
                constructor.paramTypeNames[3] == "boolean" &&
                constructor.paramTypeNames[4] == "java.lang.Throwable"
        }

    private fun missing(reason: String): ResolvedTargets.Missing = ResolvedTargets.Missing(reason)

    private fun MethodData.toRef(): MethodRef =
        MethodRef(
            className = declaredClassName,
            methodName = methodName,
            returnTypeName = returnTypeName,
            paramTypeNames = paramTypeNames,
        )

    private fun MethodData.toConstructorRef(): ConstructorRef =
        ConstructorRef(
            className = declaredClassName,
            paramTypeNames = paramTypeNames,
        )

    private fun FieldData.toRef(): FieldRef =
        FieldRef(
            className = declaredClassName,
            fieldName = fieldName,
        )

    private val FieldData.isStatic: Boolean
        get() = Modifier.isStatic(modifiers)
}
