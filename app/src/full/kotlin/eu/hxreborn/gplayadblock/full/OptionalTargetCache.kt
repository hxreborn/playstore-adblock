package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.discovery.TargetCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object OptionalTargetCache {
    const val SCHEMA_VERSION = 21
    private const val FILE_PREFIX = "optional-targets-"

    fun load(
        dataDir: String,
        key: TargetCache.Key,
        groups: Set<OptionalGroup>,
    ): OptionalTargets? {
        val file = cacheFile(dataDir, key)
        if (!file.isFile) return null
        return try {
            val root = JSONObject(file.readText())
            val requested = decodeGroups(root.getJSONArray("requested"))
            when {
                root.getInt("schema") != SCHEMA_VERSION -> null
                TargetCache.decodeKey(root.getJSONObject("key")) != key -> null
                !requested.containsAll(groups) -> null
                else -> decode(root.getJSONObject("targets"), requested)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun store(
        dataDir: String,
        key: TargetCache.Key,
        targets: OptionalTargets,
    ) {
        if (targets.transientFailure) return
        val file = cacheFile(dataDir, key)
        val root =
            JSONObject()
                .put("schema", SCHEMA_VERSION)
                .put("key", TargetCache.encodeKey(key))
                .put("requested", JSONArray(targets.requested.map(OptionalGroup::name)))
                .put("targets", encode(targets))
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

    private fun cacheFile(
        dataDir: String,
        key: TargetCache.Key,
    ): File =
        File(
            dataDir,
            "files/playstore-adblock/$FILE_PREFIX${key.targetVersionCode}-${key.moduleVersionCode}.json",
        )

    private fun decodeGroups(values: JSONArray): Set<OptionalGroup> =
        (0 until values.length()).mapTo(mutableSetOf()) { index ->
            OptionalGroup.valueOf(values.getString(index))
        }

    internal fun encode(targets: OptionalTargets): JSONObject =
        JSONObject()
            .putOpt("accountMenu", targets.accountMenu?.let(::encodeAccountMenu))
            .putOpt("manageApps", targets.manageApps?.let(::encodeManageApps))
            .putOpt("persistentNav", targets.persistentNav?.let(::encodePersistentNav))
            .putOpt("selfUpdate", targets.selfUpdate?.let(::encodeSelfUpdate))
            .putOpt("searchOrder", targets.searchOrder?.let(::encodeSearchOrder))
            .putOpt("searchRoot", targets.searchRoot?.let(::encodeSearchRoot))
            .putOpt("searchSortChip", targets.searchSortChip?.let(::encodeSearchSortChip))
            .putOpt("searchScrollFailure", targets.searchScrollFailure)
            .putOpt(
                "installSessionQueue",
                targets.installSessionQueue?.let(::encodeInstallSessionQueue),
            ).put(
                "failures",
                JSONObject().apply {
                    targets.failures.forEach { (group, reason) -> put(group.name, reason) }
                },
            )

    internal fun decode(
        value: JSONObject,
        requested: Set<OptionalGroup>,
    ): OptionalTargets {
        val failures = value.getJSONObject("failures")
        return OptionalTargets(
            requested = requested,
            accountMenu = value.optJSONObject("accountMenu")?.let(::decodeAccountMenu),
            manageApps = value.optJSONObject("manageApps")?.let(::decodeManageApps),
            persistentNav = value.optJSONObject("persistentNav")?.let(::decodePersistentNav),
            selfUpdate = value.optJSONObject("selfUpdate")?.let(::decodeSelfUpdate),
            searchOrder = value.optJSONObject("searchOrder")?.let(::decodeSearchOrder),
            searchRoot = value.optJSONObject("searchRoot")?.let(::decodeSearchRoot),
            searchSortChip = value.optJSONObject("searchSortChip")?.let(::decodeSearchSortChip),
            installSessionQueue =
                value
                    .optJSONObject(
                        "installSessionQueue",
                    )?.let(::decodeInstallSessionQueue),
            searchScrollFailure = value.optString("searchScrollFailure").takeIf { it.isNotEmpty() },
            failures =
                failures.keys().asSequence().associate { name ->
                    OptionalGroup.valueOf(name) to failures.getString(name)
                },
        )
    }

    private fun encodeAccountMenu(value: AccountMenuTargets): JSONObject =
        JSONObject()
            .putOpt("featuresClassName", value.featuresClassName)
            .putOpt("immutableListClassName", value.immutableListClassName)
            .putOpt("actionRoleField", value.actionRoleField?.let(TargetCache::encodeField))
            .putOpt(
                "notificationTopBarConstructor",
                value.notificationTopBarConstructor?.let(TargetCache::encodeConstructor),
            ).putOpt("notificationBadgeTypeName", value.notificationBadgeTypeName)
            .putOpt("labeledActions", value.labeledActions?.let(::encodeLabeledActions))
            .putOpt("headerCards", value.headerCards?.let(::encodeHeaderCards))
            .put("unresolved", JSONArray(value.unresolved))

    private fun decodeAccountMenu(value: JSONObject): AccountMenuTargets =
        AccountMenuTargets(
            featuresClassName = value.optString("featuresClassName").ifEmpty { null },
            immutableListClassName = value.optString("immutableListClassName").ifEmpty { null },
            actionRoleField = value.optJSONObject("actionRoleField")?.let(TargetCache::decodeField),
            notificationTopBarConstructor =
                value
                    .optJSONObject("notificationTopBarConstructor")
                    ?.let(TargetCache::decodeConstructor),
            notificationBadgeTypeName =
                value
                    .optString(
                        "notificationBadgeTypeName",
                    ).ifEmpty { null },
            labeledActions = value.optJSONObject("labeledActions")?.let(::decodeLabeledActions),
            headerCards = value.optJSONObject("headerCards")?.let(::decodeHeaderCards),
            unresolved = value.getJSONArray("unresolved").strings(),
        )

    private fun encodeLabeledActions(value: LabeledActionTargets): JSONObject =
        JSONObject()
            .put("actionsAccessor", TargetCache.encodeMethod(value.actionsAccessor))
            .put("actionClassName", value.actionClassName)
            .put("immutableListClassName", value.immutableListClassName)
            .put("labelIcons", encodeResourcePairs(value.labelIcons))

    private fun decodeLabeledActions(value: JSONObject): LabeledActionTargets =
        LabeledActionTargets(
            actionsAccessor = TargetCache.decodeMethod(value.getJSONObject("actionsAccessor")),
            actionClassName = value.getString("actionClassName"),
            immutableListClassName = value.getString("immutableListClassName"),
            labelIcons = decodeResourcePairs(value.getJSONArray("labelIcons")),
        )

    private fun encodeHeaderCards(value: HeaderCardTargets): JSONObject =
        JSONObject()
            .put("cardSupplierMethod", TargetCache.encodeMethod(value.cardSupplierMethod))
            .put("cardIconLoaderMethod", TargetCache.encodeMethod(value.cardIconLoaderMethod))
            .put("titleIcons", encodeResourcePairs(value.titleIcons))

    private fun decodeHeaderCards(value: JSONObject): HeaderCardTargets =
        HeaderCardTargets(
            cardSupplierMethod =
                TargetCache.decodeMethod(
                    value.getJSONObject("cardSupplierMethod"),
                ),
            cardIconLoaderMethod =
                TargetCache.decodeMethod(value.getJSONObject("cardIconLoaderMethod")),
            titleIcons = decodeResourcePairs(value.getJSONArray("titleIcons")),
        )

    private fun encodeResourcePairs(values: List<ResourcePair>): JSONArray =
        JSONArray().apply {
            values.forEach { pair -> put(JSONArray().put(pair.labelId).put(pair.iconId)) }
        }

    private fun decodeResourcePairs(values: JSONArray): List<ResourcePair> =
        List(values.length()) { index ->
            val pair = values.getJSONArray(index)
            ResourcePair(labelId = pair.getInt(0), iconId = pair.getInt(1))
        }

    private fun encodeManageApps(value: ManageAppsTargets): JSONObject =
        JSONObject()
            .put("manageListClassName", value.manageListClassName)
            .put("playProtectRowClassNames", JSONArray(value.playProtectRowClassNames))
            .putOpt("ratingsRowClassName", value.ratingsRowClassName)
            .putOpt("contributionsRowClassName", value.contributionsRowClassName)
            .putOpt("deviceSyncRowClassName", value.deviceSyncRowClassName)
            .putOpt("searchLandingItemClassName", value.searchLandingItemClassName)
            .put("unresolved", JSONArray(value.unresolved))

    private fun decodeManageApps(value: JSONObject): ManageAppsTargets =
        ManageAppsTargets(
            manageListClassName = value.getString("manageListClassName"),
            playProtectRowClassNames = value.getJSONArray("playProtectRowClassNames").strings(),
            ratingsRowClassName = value.optString("ratingsRowClassName").ifEmpty { null },
            contributionsRowClassName =
                value
                    .optString(
                        "contributionsRowClassName",
                    ).ifEmpty { null },
            deviceSyncRowClassName = value.optString("deviceSyncRowClassName").ifEmpty { null },
            searchLandingItemClassName =
                value.optString("searchLandingItemClassName").ifEmpty { null },
            unresolved = value.getJSONArray("unresolved").strings(),
        )

    private fun JSONArray.strings(): List<String> = List(length(), ::getString)

    private fun encodeSelfUpdate(value: SelfUpdateTargets): JSONObject =
        JSONObject()
            .put("schedulerMethod", TargetCache.encodeMethod(value.schedulerMethod))
            .putOpt("autoUpdateMethod", value.autoUpdateMethod?.let(TargetCache::encodeMethod))

    private fun decodeSelfUpdate(value: JSONObject): SelfUpdateTargets =
        SelfUpdateTargets(
            schedulerMethod = TargetCache.decodeMethod(value.getJSONObject("schedulerMethod")),
            autoUpdateMethod =
                value.optJSONObject("autoUpdateMethod")?.let(TargetCache::decodeMethod),
        )

    private fun encodeSearchOrder(value: SearchOrderTargets): JSONObject =
        JSONObject().put(
            "itemPrefetchMethods",
            JSONArray(value.itemPrefetchMethods.map(TargetCache::encodeMethod)),
        )

    private fun encodeInstallSessionQueue(value: InstallSessionQueueTargets): JSONObject =
        JSONObject()
            .put("applicationRegistry", TargetCache.encodeField(value.applicationRegistry))
            .put("registryProvider", TargetCache.encodeField(value.registryProvider))
            .put("providerGet", TargetCache.encodeMethod(value.providerGet))
            .put("adminService", TargetCache.encodeField(value.adminService))
            .put("filterDefault", TargetCache.encodeField(value.filterDefault))
            .put("filterBuilder", TargetCache.encodeMethod(value.filterBuilder))
            .put("activeStates", TargetCache.encodeField(value.activeStates))
            .put("addStates", TargetCache.encodeMethod(value.addStates))
            .put("buildFilter", TargetCache.encodeMethod(value.buildFilter))
            .put("query", TargetCache.encodeMethod(value.query))
            .put("taskPackage", TargetCache.encodeMethod(value.taskPackage))
            .put("taskIsGroup", TargetCache.encodeMethod(value.taskIsGroup))
            .put("taskState", TargetCache.encodeMethod(value.taskState))
            .put("stateName", TargetCache.encodeMethod(value.stateName))

    private fun decodeInstallSessionQueue(value: JSONObject): InstallSessionQueueTargets =
        InstallSessionQueueTargets(
            TargetCache.decodeField(value.getJSONObject("applicationRegistry")),
            TargetCache.decodeField(value.getJSONObject("registryProvider")),
            TargetCache.decodeMethod(value.getJSONObject("providerGet")),
            TargetCache.decodeField(value.getJSONObject("adminService")),
            TargetCache.decodeField(value.getJSONObject("filterDefault")),
            TargetCache.decodeMethod(value.getJSONObject("filterBuilder")),
            TargetCache.decodeField(value.getJSONObject("activeStates")),
            TargetCache.decodeMethod(value.getJSONObject("addStates")),
            TargetCache.decodeMethod(value.getJSONObject("buildFilter")),
            TargetCache.decodeMethod(value.getJSONObject("query")),
            TargetCache.decodeMethod(value.getJSONObject("taskPackage")),
            TargetCache.decodeMethod(value.getJSONObject("taskIsGroup")),
            TargetCache.decodeMethod(value.getJSONObject("taskState")),
            TargetCache.decodeMethod(value.getJSONObject("stateName")),
        )

    private fun encodeSearchSortChip(value: SearchSortChipTargets): JSONObject =
        JSONObject()
            .put("pageMethod", TargetCache.encodeMethod(value.pageMethod))
            .put("filterMethod", TargetCache.encodeMethod(value.filterMethod))
            .put("analyticsMethod", TargetCache.encodeMethod(value.analyticsMethod))
            .put("lifecycleMethod", TargetCache.encodeMethod(value.lifecycleMethod))
            .put("selectionMethod", TargetCache.encodeMethod(value.selectionMethod))
            .put("selectionOwnerField", TargetCache.encodeField(value.selectionOwnerField))
            .put("selectionIdField", TargetCache.encodeField(value.selectionIdField))
            .put("filterCallers", JSONArray(value.filterCallers.map(TargetCache::encodeMethod)))
            .put("streamConstructor", TargetCache.encodeConstructor(value.streamConstructor))
            .put(
                "scopeCancellationMethod",
                value.scopeCancellationMethod?.let(TargetCache::encodeMethod),
            ).put(
                "streamScopes",
                JSONArray(
                    value.streamScopes.map { scope ->
                        JSONObject()
                            .put("method", TargetCache.encodeMethod(scope.method))
                            .put("ownerField", TargetCache.encodeField(scope.ownerField))
                    },
                ),
            )

    private fun decodeSearchSortChip(value: JSONObject): SearchSortChipTargets =
        SearchSortChipTargets(
            pageMethod = TargetCache.decodeMethod(value.getJSONObject("pageMethod")),
            filterMethod = TargetCache.decodeMethod(value.getJSONObject("filterMethod")),
            analyticsMethod = TargetCache.decodeMethod(value.getJSONObject("analyticsMethod")),
            lifecycleMethod = TargetCache.decodeMethod(value.getJSONObject("lifecycleMethod")),
            scopeCancellationMethod =
                value
                    .optJSONObject(
                        "scopeCancellationMethod",
                    )?.let(TargetCache::decodeMethod),
            selectionMethod = TargetCache.decodeMethod(value.getJSONObject("selectionMethod")),
            selectionOwnerField =
                TargetCache.decodeField(
                    value.getJSONObject("selectionOwnerField"),
                ),
            selectionIdField = TargetCache.decodeField(value.getJSONObject("selectionIdField")),
            filterCallers =
                value.getJSONArray("filterCallers").let { array ->
                    List(
                        array.length(),
                    ) { index -> TargetCache.decodeMethod(array.getJSONObject(index)) }
                },
            streamConstructor =
                TargetCache.decodeConstructor(value.getJSONObject("streamConstructor")),
            streamScopes =
                value.getJSONArray("streamScopes").let { array ->
                    List(array.length()) { index ->
                        val scope = array.getJSONObject(index)
                        SearchControllerScope(
                            TargetCache.decodeMethod(scope.getJSONObject("method")),
                            TargetCache.decodeField(scope.getJSONObject("ownerField")),
                        )
                    }
                },
        )

    private fun encodeSearchRoot(value: SearchRootTargets): JSONObject =
        JSONObject()
            .put("snapshotConstructor", TargetCache.encodeConstructor(value.snapshotConstructor))
            .put("rootClassName", value.rootClassName)
            .put("refreshRoot", TargetCache.encodeMethod(value.refreshRoot))
            .put("refreshChild", TargetCache.encodeMethod(value.refreshChild))
            .put("loadNext", TargetCache.encodeMethod(value.loadNext))
            .putOpt(
                "scroll",
                value.scroll?.let { scroll ->
                    JSONObject()
                        .put("stateProvider", TargetCache.encodeMethod(scroll.stateProvider))
                        .put("scrollToItem", TargetCache.encodeMethod(scroll.scrollToItem))
                        .put(
                            "providerCallers",
                            JSONArray(scroll.providerCallers.map(TargetCache::encodeMethod)),
                        ).putOpt("renderer", scroll.renderer?.let(TargetCache::encodeMethod))
                        .putOpt("listLayout", scroll.listLayout?.let(TargetCache::encodeMethod))
                        .put(
                            "rendererCallers",
                            JSONArray(scroll.rendererCallers.map(TargetCache::encodeMethod)),
                        )
                },
            )

    private fun decodeSearchRoot(value: JSONObject): SearchRootTargets =
        SearchRootTargets(
            TargetCache.decodeConstructor(value.getJSONObject("snapshotConstructor")),
            value.getString("rootClassName"),
            TargetCache.decodeMethod(value.getJSONObject("refreshRoot")),
            TargetCache.decodeMethod(value.getJSONObject("refreshChild")),
            TargetCache.decodeMethod(value.getJSONObject("loadNext")),
            value.optJSONObject("scroll")?.let { scroll ->
                val callers = scroll.getJSONArray("providerCallers")
                SearchScrollTargets(
                    TargetCache.decodeMethod(scroll.getJSONObject("stateProvider")),
                    TargetCache.decodeMethod(scroll.getJSONObject("scrollToItem")),
                    List(callers.length()) { TargetCache.decodeMethod(callers.getJSONObject(it)) },
                    scroll.optJSONObject("renderer")?.let(TargetCache::decodeMethod),
                    scroll.optJSONObject("listLayout")?.let(TargetCache::decodeMethod),
                    scroll.getJSONArray("rendererCallers").let { values ->
                        List(values.length()) { TargetCache.decodeMethod(values.getJSONObject(it)) }
                    },
                )
            },
        )

    private fun decodeSearchOrder(value: JSONObject): SearchOrderTargets {
        val methods = value.getJSONArray("itemPrefetchMethods")
        return SearchOrderTargets(
            itemPrefetchMethods =
                List(methods.length()) { index ->
                    TargetCache.decodeMethod(methods.getJSONObject(index))
                },
        )
    }

    private fun encodePersistentNav(value: PersistentNavTargets): JSONObject =
        JSONObject()
            .put("navConstructor", TargetCache.encodeConstructor(value.navConstructor))
            .put("navItemTypeField", TargetCache.encodeField(value.navItemTypeField))
            .put("navPrimaryCallbackField", TargetCache.encodeField(value.navPrimaryCallbackField))
            .put(
                "navSecondaryCallbackField",
                TargetCache.encodeField(value.navSecondaryCallbackField),
            ).put("navActionConstructor", TargetCache.encodeConstructor(value.navActionConstructor))
            .put("navCallbackMethod", TargetCache.encodeMethod(value.navCallbackMethod))
            .putOpt(
                "navHiddenInstanceField",
                value.navHiddenInstanceField?.let(TargetCache::encodeField),
            )

    private fun decodePersistentNav(value: JSONObject): PersistentNavTargets =
        PersistentNavTargets(
            navConstructor = TargetCache.decodeConstructor(value.getJSONObject("navConstructor")),
            navItemTypeField = TargetCache.decodeField(value.getJSONObject("navItemTypeField")),
            navPrimaryCallbackField =
                TargetCache.decodeField(value.getJSONObject("navPrimaryCallbackField")),
            navSecondaryCallbackField =
                TargetCache.decodeField(value.getJSONObject("navSecondaryCallbackField")),
            navActionConstructor =
                TargetCache.decodeConstructor(value.getJSONObject("navActionConstructor")),
            navCallbackMethod = TargetCache.decodeMethod(value.getJSONObject("navCallbackMethod")),
            navHiddenInstanceField =
                value.optJSONObject("navHiddenInstanceField")?.let(TargetCache::decodeField),
        )
}
