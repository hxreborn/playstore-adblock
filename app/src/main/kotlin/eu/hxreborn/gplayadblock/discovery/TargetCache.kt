package eu.hxreborn.gplayadblock.discovery

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TargetCache {
    fun load(
        dataDir: String,
        targetVersionCode: Long,
        moduleVersionCode: Long,
    ): ResolvedTargets? {
        val file = cacheFile(dataDir, targetVersionCode, moduleVersionCode)
        if (!file.isFile) return null
        return try {
            val root = JSONObject(file.readText())
            if (root.getLong("targetVersionCode") != targetVersionCode ||
                root.getLong("moduleVersionCode") != moduleVersionCode
            ) {
                null
            } else if (root.getString("status") == "missing") {
                ResolvedTargets.Missing(root.getString("reason"))
            } else {
                decodeResolved(root.getJSONObject("targets"))
            }
        } catch (_: Exception) {
            null
        }
    }

    fun store(
        dataDir: String,
        targetVersionCode: Long,
        moduleVersionCode: Long,
        targets: ResolvedTargets,
    ) {
        val file = cacheFile(dataDir, targetVersionCode, moduleVersionCode)
        val root =
            JSONObject()
                .put("targetVersionCode", targetVersionCode)
                .put("moduleVersionCode", moduleVersionCode)
        when (targets) {
            is ResolvedTargets.Missing -> {
                root.put("status", "missing")
                root.put("reason", targets.reason)
            }

            is ResolvedTargets.Resolved -> {
                root.put("status", "resolved")
                root.put("targets", encodeResolved(targets))
            }
        }
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(root.toString())
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: Exception) {
        }
    }

    private fun cacheFile(
        dataDir: String,
        targetVersionCode: Long,
        moduleVersionCode: Long,
    ): File =
        File(
            dataDir,
            "files/playstore-adblock/targets-$targetVersionCode-$moduleVersionCode.json",
        )

    private fun encodeResolved(targets: ResolvedTargets.Resolved): JSONObject =
        JSONObject()
            .put("streamDataMethod", encodeMethod(targets.streamDataMethod))
            .put("streamChildrenField", encodeField(targets.streamChildrenField))
            .put("childHandlersField", encodeField(targets.childHandlersField))
            .put("childIdField", encodeField(targets.childIdField))
            .put("nodeField", encodeField(targets.nodeField))
            .put("presentationAccessor", encodeMethod(targets.presentationAccessor))
            .put("presentationKindField", encodeField(targets.presentationKindField))
            .put("presentationPayloadField", encodeField(targets.presentationPayloadField))
            .put("clusterCaseField", encodeField(targets.clusterCaseField))
            .put("clusterPayloadField", encodeField(targets.clusterPayloadField))
            .put("clusterServerLogsField", encodeField(targets.clusterServerLogsField))
            .put("cardKindField", encodeField(targets.cardKindField))
            .put("cardPayloadField", encodeField(targets.cardPayloadField))
            .put("cardAdMetadataFields", encodeFields(targets.cardAdMetadataFields))
            .put("adPresenceField", encodeField(targets.adPresenceField))
            .put("responseMethods", encodeMethods(targets.responseMethods))
            .put("responseListFields", encodeFields(targets.responseListFields))
            .put("nodeWrapperKindField", encodeField(targets.nodeWrapperKindField))
            .put("nodeWrapperPayloadField", encodeField(targets.nodeWrapperPayloadField))
            .put("nodeIdField", encodeField(targets.nodeIdField))
            .put("nodePresentationField", encodeField(targets.nodePresentationField))
            .put("nodeChildrenField", encodeField(targets.nodeChildrenField))
            .put("rootWrapperKindField", encodeField(targets.rootWrapperKindField))
            .put("rootWrapperPayloadField", encodeField(targets.rootWrapperPayloadField))
            .put("rootChildrenField", encodeField(targets.rootChildrenField))
            .put("childIdsField", encodeField(targets.childIdsField))
            .put("childPresenceField", encodeField(targets.childPresenceField))
            .put("childContinuationField", encodeField(targets.childContinuationField))
            .put("nodeDefaultInstanceField", encodeField(targets.nodeDefaultInstanceField))
            .put("rootDefaultInstanceField", encodeField(targets.rootDefaultInstanceField))
            .put("cacheAssemblyMethod", encodeMethod(targets.cacheAssemblyMethod))
            .put("cacheNodeChildrenField", encodeField(targets.cacheNodeChildrenField))
            .put("cachePageBoundariesField", encodeField(targets.cachePageBoundariesField))
            .put(
                "cachePageBoundariesCopyMethod",
                encodeMethod(targets.cachePageBoundariesCopyMethod),
            ).put("childKeyMethod", encodeMethod(targets.childKeyMethod))
            .put("protobufNewBuilderMethod", encodeMethod(targets.protobufNewBuilderMethod))
            .put("protobufMergeMethod", encodeMethod(targets.protobufMergeMethod))
            .put("protobufBuildMethod", encodeMethod(targets.protobufBuildMethod))
            .put(
                "protobufBuilderMessageField",
                encodeField(targets.protobufBuilderMessageField),
            ).put("protobufParseMethod", encodeMethod(targets.protobufParseMethod))
            .put("protobufRegistryFactory", encodeMethod(targets.protobufRegistryFactory))
            .put(
                "byteStringToByteArrayMethod",
                encodeMethod(targets.byteStringToByteArrayMethod),
            ).put(
                "protobufToByteArrayMethod",
                encodeMethod(targets.protobufToByteArrayMethod),
            ).put(
                "repeatedListCopyMethod",
                encodeMethod(targets.repeatedListCopyMethod),
            ).put(
                "searchSuggestionConstructor",
                encodeConstructor(targets.searchSuggestionConstructor),
            ).put("suggestionAdInfoField", encodeField(targets.suggestionAdInfoField))

    private fun decodeResolved(value: JSONObject): ResolvedTargets.Resolved =
        ResolvedTargets.Resolved(
            streamDataMethod = decodeMethod(value.getJSONObject("streamDataMethod")),
            streamChildrenField = decodeField(value.getJSONObject("streamChildrenField")),
            childHandlersField = decodeField(value.getJSONObject("childHandlersField")),
            childIdField = decodeField(value.getJSONObject("childIdField")),
            nodeField = decodeField(value.getJSONObject("nodeField")),
            presentationAccessor = decodeMethod(value.getJSONObject("presentationAccessor")),
            presentationKindField = decodeField(value.getJSONObject("presentationKindField")),
            presentationPayloadField = decodeField(value.getJSONObject("presentationPayloadField")),
            clusterCaseField = decodeField(value.getJSONObject("clusterCaseField")),
            clusterPayloadField = decodeField(value.getJSONObject("clusterPayloadField")),
            clusterServerLogsField =
                decodeField(value.getJSONObject("clusterServerLogsField")),
            cardKindField = decodeField(value.getJSONObject("cardKindField")),
            cardPayloadField = decodeField(value.getJSONObject("cardPayloadField")),
            cardAdMetadataFields = decodeFields(value.getJSONArray("cardAdMetadataFields")),
            adPresenceField = decodeField(value.getJSONObject("adPresenceField")),
            responseMethods = decodeMethods(value.getJSONArray("responseMethods")),
            responseListFields = decodeFields(value.getJSONArray("responseListFields")),
            nodeWrapperKindField = decodeField(value.getJSONObject("nodeWrapperKindField")),
            nodeWrapperPayloadField = decodeField(value.getJSONObject("nodeWrapperPayloadField")),
            nodeIdField = decodeField(value.getJSONObject("nodeIdField")),
            nodePresentationField = decodeField(value.getJSONObject("nodePresentationField")),
            nodeChildrenField = decodeField(value.getJSONObject("nodeChildrenField")),
            rootWrapperKindField = decodeField(value.getJSONObject("rootWrapperKindField")),
            rootWrapperPayloadField = decodeField(value.getJSONObject("rootWrapperPayloadField")),
            rootChildrenField = decodeField(value.getJSONObject("rootChildrenField")),
            childIdsField = decodeField(value.getJSONObject("childIdsField")),
            childPresenceField = decodeField(value.getJSONObject("childPresenceField")),
            childContinuationField = decodeField(value.getJSONObject("childContinuationField")),
            nodeDefaultInstanceField = decodeField(value.getJSONObject("nodeDefaultInstanceField")),
            rootDefaultInstanceField = decodeField(value.getJSONObject("rootDefaultInstanceField")),
            cacheAssemblyMethod = decodeMethod(value.getJSONObject("cacheAssemblyMethod")),
            cacheNodeChildrenField =
                decodeField(value.getJSONObject("cacheNodeChildrenField")),
            cachePageBoundariesField =
                decodeField(value.getJSONObject("cachePageBoundariesField")),
            cachePageBoundariesCopyMethod =
                decodeMethod(value.getJSONObject("cachePageBoundariesCopyMethod")),
            childKeyMethod = decodeMethod(value.getJSONObject("childKeyMethod")),
            protobufNewBuilderMethod =
                decodeMethod(value.getJSONObject("protobufNewBuilderMethod")),
            protobufMergeMethod = decodeMethod(value.getJSONObject("protobufMergeMethod")),
            protobufBuildMethod = decodeMethod(value.getJSONObject("protobufBuildMethod")),
            protobufBuilderMessageField =
                decodeField(value.getJSONObject("protobufBuilderMessageField")),
            protobufParseMethod = decodeMethod(value.getJSONObject("protobufParseMethod")),
            protobufRegistryFactory =
                decodeMethod(value.getJSONObject("protobufRegistryFactory")),
            byteStringToByteArrayMethod =
                decodeMethod(value.getJSONObject("byteStringToByteArrayMethod")),
            protobufToByteArrayMethod =
                decodeMethod(value.getJSONObject("protobufToByteArrayMethod")),
            repeatedListCopyMethod =
                decodeMethod(value.getJSONObject("repeatedListCopyMethod")),
            searchSuggestionConstructor =
                decodeConstructor(value.getJSONObject("searchSuggestionConstructor")),
            suggestionAdInfoField = decodeField(value.getJSONObject("suggestionAdInfoField")),
        )

    private fun encodeConstructor(value: ConstructorRef): JSONObject =
        JSONObject()
            .put("className", value.className)
            .put("paramTypeNames", JSONArray(value.paramTypeNames))

    private fun decodeConstructor(value: JSONObject): ConstructorRef {
        val parameters = value.getJSONArray("paramTypeNames")
        return ConstructorRef(
            className = value.getString("className"),
            paramTypeNames = List(parameters.length()) { parameters.getString(it) },
        )
    }

    private fun encodeMethod(value: MethodRef): JSONObject =
        JSONObject()
            .put("className", value.className)
            .put("methodName", value.methodName)
            .put("returnTypeName", value.returnTypeName)
            .put("paramTypeNames", JSONArray(value.paramTypeNames))

    private fun decodeMethod(value: JSONObject): MethodRef {
        val parameters = value.getJSONArray("paramTypeNames")
        return MethodRef(
            className = value.getString("className"),
            methodName = value.getString("methodName"),
            returnTypeName = value.getString("returnTypeName"),
            paramTypeNames = List(parameters.length()) { parameters.getString(it) },
        )
    }

    private fun encodeMethods(values: List<MethodRef>): JSONArray =
        JSONArray().apply {
            values.forEach { value -> put(encodeMethod(value)) }
        }

    private fun decodeMethods(values: JSONArray): List<MethodRef> =
        List(values.length()) { index -> decodeMethod(values.getJSONObject(index)) }

    private fun encodeField(value: FieldRef): JSONObject =
        JSONObject()
            .put("className", value.className)
            .put("fieldName", value.fieldName)

    private fun encodeFields(values: List<FieldRef>): JSONArray =
        JSONArray().apply {
            values.forEach { value -> put(encodeField(value)) }
        }

    private fun decodeFields(values: JSONArray): List<FieldRef> =
        List(values.length()) { index -> decodeField(values.getJSONObject(index)) }

    private fun decodeField(value: JSONObject): FieldRef =
        FieldRef(
            className = value.getString("className"),
            fieldName = value.getString("fieldName"),
        )
}
