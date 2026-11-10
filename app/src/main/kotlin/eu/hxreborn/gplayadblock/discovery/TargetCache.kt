package eu.hxreborn.gplayadblock.discovery

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TargetCache {
    data class Key(
        val targetVersionCode: Long,
        val targetLastUpdateTime: Long,
        val moduleVersionCode: Long,
        val moduleApkPath: String,
    )

    fun load(
        dataDir: String,
        key: Key,
    ): ResolvedTargets? {
        val file = cacheFile(dataDir, key)
        if (!file.isFile) return null
        return try {
            val root = JSONObject(file.readText())
            if (root.optJSONObject("key")?.let(::decodeKey) != key) {
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
        key: Key,
        targets: ResolvedTargets,
    ) {
        val file = cacheFile(dataDir, key)
        val root = JSONObject().put("key", encodeKey(key))
        when (targets) {
            is ResolvedTargets.Missing -> {
                if (targets.retryable) return
                root.put("status", "missing")
                root.put("reason", targets.reason)
            }

            is ResolvedTargets.Resolved -> {
                root.put("status", "resolved")
                root.put("targets", encodeResolved(targets))
            }
        }
        try {
            val directory = file.parentFile
            directory?.mkdirs()
            directory
                ?.listFiles { candidate ->
                    candidate.name.startsWith(FILE_PREFIX) && candidate.name != file.name
                }?.forEach(File::delete)
            val temporary = File(directory, "${file.name}.tmp")
            temporary.writeText(root.toString())
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: Exception) {
        }
    }

    private const val FILE_PREFIX = "targets-"

    private fun cacheFile(
        dataDir: String,
        key: Key,
    ): File =
        File(
            dataDir,
            "files/playstore-adblock/$FILE_PREFIX${key.targetVersionCode}-${key.moduleVersionCode}.json",
        )

    private fun encodeKey(key: Key): JSONObject =
        JSONObject()
            .put("targetVersionCode", key.targetVersionCode)
            .put("targetLastUpdateTime", key.targetLastUpdateTime)
            .put("moduleVersionCode", key.moduleVersionCode)
            .put("moduleApkPath", key.moduleApkPath)

    private fun decodeKey(value: JSONObject): Key =
        Key(
            targetVersionCode = value.getLong("targetVersionCode"),
            targetLastUpdateTime = value.getLong("targetLastUpdateTime"),
            moduleVersionCode = value.getLong("moduleVersionCode"),
            moduleApkPath = value.getString("moduleApkPath"),
        )

    private fun encodeResolved(targets: ResolvedTargets.Resolved): JSONObject =
        JSONObject()
            .put("streamDataMethod", encodeMethod(targets.streamDataMethod))
            .put("streamDataConstructor", encodeConstructor(targets.streamDataConstructor))
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
            ).putOpt("suggestion", targets.suggestion?.let(::encodeSuggestion))
            .putOpt("suggestionFailure", targets.suggestionFailure)
            .putOpt("spotlight", targets.spotlight?.let(::encodeSpotlight))
            .putOpt("spotlightFailure", targets.spotlightFailure)
            .putOpt("manageApps", targets.manageApps?.let(::encodeManageApps))
            .putOpt("manageAppsFailure", targets.manageAppsFailure)
            .putOpt("persistentNav", targets.persistentNav?.let(::encodePersistentNav))
            .putOpt("persistentNavFailure", targets.persistentNavFailure)
            .putOpt("accountMenu", targets.accountMenu?.let(::encodeAccountMenu))
            .putOpt("accountMenuFailure", targets.accountMenuFailure)
            .putOpt("selfUpdate", targets.selfUpdate?.let(::encodeSelfUpdate))
            .putOpt("selfUpdateFailure", targets.selfUpdateFailure)

    private fun decodeResolved(value: JSONObject): ResolvedTargets.Resolved =
        ResolvedTargets.Resolved(
            streamDataMethod = decodeMethod(value.getJSONObject("streamDataMethod")),
            streamDataConstructor = decodeConstructor(value.getJSONObject("streamDataConstructor")),
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
            suggestion = value.optJSONObject("suggestion")?.let(::decodeSuggestion),
            suggestionFailure = value.optString("suggestionFailure").ifEmpty { null },
            spotlight = value.optJSONObject("spotlight")?.let(::decodeSpotlight),
            spotlightFailure = value.optString("spotlightFailure").ifEmpty { null },
            manageApps = value.optJSONObject("manageApps")?.let(::decodeManageApps),
            manageAppsFailure = value.optString("manageAppsFailure").ifEmpty { null },
            persistentNav = value.optJSONObject("persistentNav")?.let(::decodePersistentNav),
            persistentNavFailure = value.optString("persistentNavFailure").ifEmpty { null },
            accountMenu = value.optJSONObject("accountMenu")?.let(::decodeAccountMenu),
            accountMenuFailure = value.optString("accountMenuFailure").ifEmpty { null },
            selfUpdate = value.optJSONObject("selfUpdate")?.let(::decodeSelfUpdate),
            selfUpdateFailure = value.optString("selfUpdateFailure").ifEmpty { null },
        )

    private fun encodeAccountMenu(value: ResolvedTargets.AccountMenu): JSONObject =
        JSONObject()
            .put("featureClassName", value.featureClassName)
            .put("actionClassName", value.actionClassName)
            .put("featuresClassName", value.featuresClassName)
            .put("immutableListClassName", value.immutableListClassName)

    private fun decodeAccountMenu(value: JSONObject): ResolvedTargets.AccountMenu =
        ResolvedTargets.AccountMenu(
            featureClassName = value.getString("featureClassName"),
            actionClassName = value.getString("actionClassName"),
            featuresClassName = value.getString("featuresClassName"),
            immutableListClassName = value.getString("immutableListClassName"),
        )

    private fun encodeSelfUpdate(value: ResolvedTargets.SelfUpdate): JSONObject =
        JSONObject()
            .put("schedulerMethod", encodeMethod(value.schedulerMethod))
            .putOpt("autoUpdateMethod", value.autoUpdateMethod?.let(::encodeMethod))

    private fun decodeSelfUpdate(value: JSONObject): ResolvedTargets.SelfUpdate =
        ResolvedTargets.SelfUpdate(
            schedulerMethod = decodeMethod(value.getJSONObject("schedulerMethod")),
            autoUpdateMethod = value.optJSONObject("autoUpdateMethod")?.let(::decodeMethod),
        )

    private fun encodeManageApps(value: ResolvedTargets.ManageApps): JSONObject =
        JSONObject()
            .put("manageListClassName", value.manageListClassName)
            .putOpt("deviceSyncClassName", value.deviceSyncClassName)
            .putOpt("playProtectClassName", value.playProtectClassName)

    private fun decodeManageApps(value: JSONObject): ResolvedTargets.ManageApps =
        ResolvedTargets.ManageApps(
            manageListClassName = value.getString("manageListClassName"),
            deviceSyncClassName = value.optString("deviceSyncClassName").ifEmpty { null },
            playProtectClassName = value.optString("playProtectClassName").ifEmpty { null },
        )

    private fun encodePersistentNav(value: ResolvedTargets.PersistentNav): JSONObject =
        JSONObject()
            .put("navConstructor", encodeConstructor(value.navConstructor))
            .put("navItemTypeField", encodeField(value.navItemTypeField))
            .put("navPrimaryCallbackField", encodeField(value.navPrimaryCallbackField))
            .put("navSecondaryCallbackField", encodeField(value.navSecondaryCallbackField))
            .put("navActionConstructor", encodeConstructor(value.navActionConstructor))
            .put("navCallbackMethod", encodeMethod(value.navCallbackMethod))

    private fun decodePersistentNav(value: JSONObject): ResolvedTargets.PersistentNav =
        ResolvedTargets.PersistentNav(
            navConstructor = decodeConstructor(value.getJSONObject("navConstructor")),
            navItemTypeField = decodeField(value.getJSONObject("navItemTypeField")),
            navPrimaryCallbackField =
                decodeField(value.getJSONObject("navPrimaryCallbackField")),
            navSecondaryCallbackField =
                decodeField(value.getJSONObject("navSecondaryCallbackField")),
            navActionConstructor =
                decodeConstructor(value.getJSONObject("navActionConstructor")),
            navCallbackMethod = decodeMethod(value.getJSONObject("navCallbackMethod")),
        )

    private fun encodeSuggestion(value: ResolvedTargets.Suggestion): JSONObject =
        JSONObject()
            .put("constructor", encodeConstructor(value.constructor))
            .put("adInfoField", encodeField(value.adInfoField))

    private fun decodeSuggestion(value: JSONObject): ResolvedTargets.Suggestion =
        ResolvedTargets.Suggestion(
            constructor = decodeConstructor(value.getJSONObject("constructor")),
            adInfoField = decodeField(value.getJSONObject("adInfoField")),
        )

    private fun encodeSpotlight(value: ResolvedTargets.Spotlight): JSONObject =
        JSONObject()
            .put("tabAccessor", encodeMethod(value.tabAccessor))
            .put("tabDefaultInstanceField", encodeField(value.tabDefaultInstanceField))

    private fun decodeSpotlight(value: JSONObject): ResolvedTargets.Spotlight =
        ResolvedTargets.Spotlight(
            tabAccessor = decodeMethod(value.getJSONObject("tabAccessor")),
            tabDefaultInstanceField = decodeField(value.getJSONObject("tabDefaultInstanceField")),
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
