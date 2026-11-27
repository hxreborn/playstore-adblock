package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.discovery.ConstructorRef
import eu.hxreborn.gplayadblock.discovery.DexKitResolver
import eu.hxreborn.gplayadblock.discovery.FieldRef
import eu.hxreborn.gplayadblock.discovery.MethodRef
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal fun isVoidSortSelection(returnType: String): Boolean = returnType == "void"

object OptionalDexKitResolver {
    private const val ACCOUNT_FEATURES_ANCHOR = "AccountMenuFeatures{deactivatedAccountsFeature="
    private const val NOTIFICATION_TOP_BAR_ANCHOR =
        "NotificationTopBarItemUiContent(notificationIndicatorBadge="
    private val accountRoleAnchors = listOf("PRIVACY_ADVISOR", "HELP_AND_FEEDBACK")
    private const val ACCOUNT_FEATURE_ANCHOR =
        "CollapsibleAccountManagementFeatureImpl{commonCards="
    private const val ACCOUNT_ACTION_ANCHOR = "CustomActionSpec{id="
    private const val HEADER_CARD_ANCHOR = "PurchaseReadyPersistentReminder"
    private const val APP_RESOURCE_PACKAGE = 0x7f
    private const val MANAGE_LIST_ANCHOR = "VerticalScrollerUiModelContent(itemList="
    private const val PROTECT_SECTION_ANCHOR = "ProtectSectionStateFlowProvider"
    private const val VE_METADATA_ANCHOR = "VeMetadata(uiElementType="
    private const val NAV_CONTENT_ANCHOR = "PersistentNavDataUiContent(items="
    private const val NAV_ITEM_ANCHOR = "PersistentNavItem(title="
    private const val NAV_ACTION_ANCHOR = "PersistentNavUiAction(onPersistentNavItemClicked="
    private const val NAV_HIDDEN_ANCHOR = "NavHidden"
    private val navTypeAnchors = listOf("APPS", "GAMES", "BOOKS", "YOU")
    private val selfUpdateSchedulerAnchors =
        listOf(
            "Skipping DFE self-update check as there is an update already queued.",
            "Bulk scheduling self-update with policies",
        )
    private const val ITEM_PREFETCH_ANCHOR = "HANDLE_ITEM_PREFETCH_START"
    private const val STREAM_SNAPSHOT_ANCHOR = "Snapshot{%s,%s,%s,%s}"
    private const val SEARCH_FILTER_BAR_ANCHOR = "FilterBar#"
    private const val AUTO_UPDATE_ANCHOR =
        "UChk: document %s is not qualified for auto update v2"
    private const val RATINGS_AND_REVIEWS_ELEMENT = 14306
    private const val DEVICE_SYNC_ELEMENT = 14371
    private const val CONTRIBUTIONS_COMPOSE_GROUP = 1056932064
    private const val VISUAL_CATEGORIES_ELEMENT = 7567
    private const val GROUP_SEPARATOR = "|"
    private val nullCheckBranches = setOf("if-nez", "if-eqz")
    private val primitiveTypes =
        setOf("boolean", "byte", "char", "double", "float", "int", "long", "short", "void")

    private val anchorsByGroup: Map<OptionalGroup, List<List<String>>> =
        mapOf(
            OptionalGroup.ACCOUNT_MENU to
                listOf(
                    listOf(ACCOUNT_FEATURES_ANCHOR),
                    listOf(NOTIFICATION_TOP_BAR_ANCHOR),
                    accountRoleAnchors,
                    listOf(ACCOUNT_FEATURE_ANCHOR),
                    listOf(ACCOUNT_ACTION_ANCHOR),
                    listOf(HEADER_CARD_ANCHOR),
                ),
            OptionalGroup.MANAGE_APPS to
                listOf(
                    listOf(MANAGE_LIST_ANCHOR),
                    listOf(PROTECT_SECTION_ANCHOR),
                    listOf(VE_METADATA_ANCHOR),
                ),
            OptionalGroup.PERSISTENT_NAV to
                listOf(
                    listOf(NAV_CONTENT_ANCHOR),
                    listOf(NAV_ITEM_ANCHOR),
                    listOf(NAV_ACTION_ANCHOR),
                    listOf(NAV_HIDDEN_ANCHOR),
                    navTypeAnchors,
                ),
            OptionalGroup.SELF_UPDATE to emptyList(),
            OptionalGroup.SEARCH_ORDER to listOf(listOf(ITEM_PREFETCH_ANCHOR)),
            OptionalGroup.SEARCH_ROOT to emptyList(),
            OptionalGroup.SEARCH_SORT_CHIP to emptyList(),
            OptionalGroup.INSTALL_SESSION_QUEUE to emptyList(),
        )

    fun resolve(
        apkPaths: List<String>,
        groups: Set<OptionalGroup>,
    ): OptionalTargets {
        if (DexKitResolver.hasNativeLoadFailure()) {
            return OptionalTargets(
                requested = groups,
                failures = groups.associateWith { "libdexkit.so failed to load" },
                transientFailure = true,
            )
        }
        var merged = OptionalTargets(requested = groups)
        var scroll: SearchScrollTargets? = null
        var snapshotKeyType: String? = null
        val reasons = groups.associateWith { ArrayList<String>() }
        val scrollFailures = mutableListOf<String>()
        var transientFailure = false
        val paths = apkPaths.distinct()
        val scannedWithoutKey = mutableListOf<String>()
        for (apkPath in paths) {
            val pending = groups - merged.completeGroups()
            if (pending.isEmpty()) break
            try {
                DexKitBridge.create(apkPath).use { bridge ->
                    val (resolved, discoveredScroll, discoveredKey) =
                        resolveApk(bridge, pending, snapshotKeyType)
                    if (snapshotKeyType == null && discoveredKey == null &&
                        OptionalGroup.SEARCH_SORT_CHIP in pending
                    ) {
                        scannedWithoutKey.add(apkPath)
                    }
                    snapshotKeyType = snapshotKeyType ?: discoveredKey
                    scroll = preferredSearchScroll(scroll, discoveredScroll)
                    resolved.searchScrollFailure?.let { scrollFailures.add("$apkPath: $it") }
                    resolved.failures.forEach { (group, reason) ->
                        reasons.getValue(group) += "$apkPath: $reason"
                    }
                    transientFailure = transientFailure || resolved.transientFailure
                    merged = merged.withMoreCompleteGroups(resolved, scroll)
                }
            } catch (exception: Exception) {
                pending.forEach { group ->
                    reasons.getValue(group) +=
                        "$apkPath: ${exception.javaClass.simpleName}: ${exception.message}"
                }
                transientFailure = true
            } catch (error: LinkageError) {
                pending.forEach { group ->
                    reasons.getValue(group) +=
                        "$apkPath: ${error.javaClass.simpleName}: ${error.message}"
                }
                transientFailure = true
            }
        }
        if (snapshotKeyType != null && OptionalGroup.SEARCH_SORT_CHIP in groups &&
            merged.searchSortChip == null
        ) {
            val chipGroups = setOf(OptionalGroup.SEARCH_SORT_CHIP)
            for (path in scannedWithoutKey) {
                try {
                    DexKitBridge.create(path).use { bridge ->
                        val result = resolveApk(bridge, chipGroups, snapshotKeyType).targets
                        result.failures[OptionalGroup.SEARCH_SORT_CHIP]?.let { reason ->
                            reasons
                                .getValue(
                                    OptionalGroup.SEARCH_SORT_CHIP,
                                ).add("$path retry: $reason")
                        }
                        transientFailure = transientFailure || result.transientFailure
                        merged = merged.withMoreCompleteGroups(result)
                    }
                } catch (_: Exception) {
                    transientFailure = true
                } catch (_: LinkageError) {
                    transientFailure = true
                }
                if (merged.searchSortChip != null) break
            }
        }
        return merged.copy(
            failures =
                groups.filterNot(merged::hasTargets).associateWith { group ->
                    reasons.getValue(group).joinToString(" | ").ifBlank { "no APK paths" }
                },
            transientFailure = transientFailure,
            searchScrollFailure =
                scrollFailures
                    .takeIf {
                        merged.searchRoot != null &&
                            scroll?.canRestorePosition() != true &&
                            it.isNotEmpty()
                    }?.joinToString(" | "),
        )
    }

    private data class ApkTargets(
        val targets: OptionalTargets,
        val searchScroll: SearchScrollTargets?,
        val snapshotKeyType: String?,
    )

    private fun resolveApk(
        bridge: DexKitBridge,
        groups: Set<OptionalGroup>,
        knownKeyType: String?,
    ): ApkTargets {
        val anchored = AnchoredClasses(bridge, groups.flatMap { anchorsByGroup.getValue(it) })
        val snapshot by lazy { runCatching { resolveSnapshotConstructor(bridge) } }
        val scroll =
            if (OptionalGroup.SEARCH_ROOT in groups) {
                attempt("search scroll") { resolveSearchScroll(bridge) }
            } else {
                null
            }
        val results =
            groups.associateWith { group ->
                attempt(group.label) {
                    when (group) {
                        OptionalGroup.ACCOUNT_MENU -> {
                            resolveAccountMenu(bridge, anchored)
                        }

                        OptionalGroup.MANAGE_APPS -> {
                            resolveManageApps(bridge, anchored)
                        }

                        OptionalGroup.PERSISTENT_NAV -> {
                            resolvePersistentNav(bridge, anchored)
                        }

                        OptionalGroup.SELF_UPDATE -> {
                            resolveSelfUpdate(bridge)
                        }

                        OptionalGroup.SEARCH_ORDER -> {
                            resolveSearchOrder(bridge, anchored)
                        }

                        OptionalGroup.SEARCH_ROOT -> {
                            resolveSearchRoot(bridge, snapshot.getOrThrow())
                        }

                        OptionalGroup.SEARCH_SORT_CHIP -> {
                            resolveSearchSortChip(bridge) {
                                knownKeyType ?: snapshot.getOrThrow().paramTypeNames.first()
                            }
                        }

                        OptionalGroup.INSTALL_SESSION_QUEUE -> {
                            InstallSessionQueueResolver.resolve(
                                bridge,
                            )
                        }
                    }
                }
            }
        val failures =
            results
                .mapNotNull { (group, result) ->
                    (result as? Resolution.Failure)?.let { group to it.reason }
                }.toMap()
        val keyLookup =
            if (OptionalGroup.SEARCH_ROOT in groups ||
                (OptionalGroup.SEARCH_SORT_CHIP in groups && knownKeyType == null)
            ) {
                attempt("search snapshot key") { snapshot.getOrThrow().paramTypeNames.first() }
            } else {
                null
            }
        val targets =
            OptionalTargets(
                requested = groups,
                accountMenu = results[OptionalGroup.ACCOUNT_MENU]?.success() as? AccountMenuTargets,
                manageApps = results[OptionalGroup.MANAGE_APPS]?.success() as? ManageAppsTargets,
                persistentNav =
                    results[OptionalGroup.PERSISTENT_NAV]?.success() as? PersistentNavTargets,
                selfUpdate = results[OptionalGroup.SELF_UPDATE]?.success() as? SelfUpdateTargets,
                searchOrder = results[OptionalGroup.SEARCH_ORDER]?.success() as? SearchOrderTargets,
                searchRoot = results[OptionalGroup.SEARCH_ROOT]?.success() as? SearchRootTargets,
                searchSortChip =
                    results[OptionalGroup.SEARCH_SORT_CHIP]?.success() as? SearchSortChipTargets,
                installSessionQueue =
                    results[OptionalGroup.INSTALL_SESSION_QUEUE]?.success()
                        as? InstallSessionQueueTargets,
                failures = failures,
                searchScrollFailure = (scroll as? Resolution.Failure)?.reason,
                transientFailure =
                    (keyLookup as? Resolution.Failure)?.transient == true ||
                        (scroll as? Resolution.Failure)?.transient == true ||
                        results.values.any { result ->
                            (result as? Resolution.Failure)?.transient == true
                        } ||
                        (results[OptionalGroup.ACCOUNT_MENU]?.success() as? AccountMenuTargets)
                            ?.transientFailure == true,
            )
        return ApkTargets(
            targets,
            scroll?.success() as? SearchScrollTargets,
            keyLookup?.success() as? String,
        )
    }

    private class AnchoredClasses(
        bridge: DexKitBridge,
        anchorGroups: List<List<String>>,
    ) {
        private val found: Map<List<String>, List<ClassData>> =
            if (anchorGroups.isEmpty()) {
                emptyMap()
            } else {
                val keyed = anchorGroups.distinct().associateBy { it.joinToString(GROUP_SEPARATOR) }
                val result =
                    bridge.batchFindClassUsingStrings {
                        groups(keyed, StringMatchType.Contains, false)
                    }
                keyed.entries.associate { (key, strings) -> strings to result[key].orEmpty() }
            }

        fun single(vararg strings: String): ClassData {
            val candidates = found[strings.toList()].orEmpty()
            return candidates.singleOrNull()
                ?: throw ResolutionFailure(
                    "anchor '${strings.joinToString()}' candidates=${candidates.size}",
                )
        }

        fun all(strings: List<String>): List<ClassData> = found[strings].orEmpty()
    }

    private fun resolveAccountMenu(
        bridge: DexKitBridge,
        anchored: AnchoredClasses,
    ): AccountMenuTargets {
        val actions = attempt("account actions") { resolveAccountActions(bridge, anchored) }
        val badge = attempt("notification badge") { resolveNotificationBadge(anchored) }
        val labeledActions =
            attempt("labeled account actions") { resolveLabeledActions(bridge, anchored) }
        val headerCards = attempt("account header cards") { resolveHeaderCards(anchored) }
        val targets = actions.success() as? AccountMenuTargets ?: AccountMenuTargets()
        val notification = badge.success() as? AccountMenuTargets
        val results =
            mapOf(
                "account actions" to actions,
                "notification badge" to badge,
                "labeled account actions" to labeledActions,
                "account header cards" to headerCards,
            )
        return targets.copy(
            notificationTopBarConstructor = notification?.notificationTopBarConstructor,
            notificationBadgeTypeName = notification?.notificationBadgeTypeName,
            labeledActions = labeledActions.success() as? LabeledActionTargets,
            headerCards = headerCards.success() as? HeaderCardTargets,
            unresolved =
                results.mapNotNull { (name, result) ->
                    (result as? Resolution.Failure)?.reason?.let { "$name: $it" }
                },
            transientFailure =
                results.values.any { result ->
                    (result as? Resolution.Failure)?.transient == true
                },
        )
    }

    private fun resolveAccountActions(
        bridge: DexKitBridge,
        anchored: AnchoredClasses,
    ): AccountMenuTargets {
        val featuresClass = anchored.single(ACCOUNT_FEATURES_ANCHOR)
        val immutableLists =
            featuresClass.methods
                .filter(MethodData::isConstructor)
                .flatMap(MethodData::paramTypeNames)
                .distinct()
                .filterNot(primitiveTypes::contains)
                .mapNotNull(bridge::getClassData)
                .filter(::isImmutableList)
        val immutableList =
            immutableLists.singleOrNull()
                ?: throw ResolutionFailure("account immutable lists=${immutableLists.size}")
        val roleEnum = anchored.single(*accountRoleAnchors.toTypedArray())
        if (roleEnum.superClass?.name != "java.lang.Enum") {
            throw ResolutionFailure("account role class ${roleEnum.name} is not an enum")
        }
        return AccountMenuTargets(
            featuresClassName = featuresClass.name,
            immutableListClassName = immutableList.name,
            actionRoleField =
                actionRoleField(bridge, roleEnum)?.toRef()
                    ?: throw ResolutionFailure("account action role field missing or ambiguous"),
        )
    }

    private fun resolveLabeledActions(
        bridge: DexKitBridge,
        anchored: AnchoredClasses,
    ): LabeledActionTargets {
        val feature = anchored.single(ACCOUNT_FEATURE_ANCHOR)
        val action = anchored.single(ACCOUNT_ACTION_ANCHOR)
        val accessors =
            feature.methods.filter { method ->
                !method.isConstructor &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.paramCount == 0 &&
                    method.returnType?.let(::isImmutableList) == true
            }
        val accessor =
            accessors.singleOrNull()
                ?: throw ResolutionFailure("account action accessors=${accessors.size}")
        val factory =
            action.methods.singleOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.paramCount == 0 &&
                    method.returnTypeName != action.name
            } ?: throw ResolutionFailure("account action builder factory not unique")
        val labelIcons =
            bridge
                .findMethod { matcher { addInvoke { descriptor = factory.descriptor } } }
                .flatMap(::adjacentResourcePairs)
                .distinct()
        if (labelIcons.isEmpty()) throw ResolutionFailure("account action label icons=0")
        return LabeledActionTargets(
            actionsAccessor = accessor.toRef(),
            actionClassName = action.name,
            immutableListClassName = accessor.returnTypeName,
            labelIcons = labelIcons,
        )
    }

    private fun resolveHeaderCards(anchored: AnchoredClasses): HeaderCardTargets {
        val suppliers =
            anchored.all(listOf(HEADER_CARD_ANCHOR)).filter { candidate ->
                candidate.fields
                    .filterNot { field -> Modifier.isStatic(field.modifiers) }
                    .map(FieldData::typeName)
                    .sorted() == listOf("int", "java.lang.Object")
            }
        val supplier =
            suppliers.singleOrNull()
                ?: throw ResolutionFailure("account header card suppliers=${suppliers.size}")
        val supplierMethod =
            supplier.methods.singleOrNull { method ->
                !method.isConstructor &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.paramTypeNames == listOf("java.lang.Object")
            } ?: throw ResolutionFailure("account header card supplier method not unique")
        val iconLoaders =
            supplierMethod.invokes
                .filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.paramTypeNames.take(2) ==
                        listOf("android.content.res.Resources", "int")
                }.distinctBy(MethodData::descriptor)
        val iconLoader =
            iconLoaders.singleOrNull()
                ?: throw ResolutionFailure("account header card icon loaders=${iconLoaders.size}")
        val titleIcons = adjacentResourcePairs(supplierMethod).distinct()
        if (titleIcons.isEmpty()) throw ResolutionFailure("account header card title icons=0")
        return HeaderCardTargets(
            cardSupplierMethod = supplierMethod.toRef(),
            cardIconLoaderMethod = iconLoader.toRef(),
            titleIcons = titleIcons,
        )
    }

    private fun isImmutableList(candidate: ClassData): Boolean =
        candidate.methods.any { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == candidate.name &&
                method.paramTypeNames == listOf("java.util.Collection")
        }

    @OptIn(DexKitExperimentalApi::class)
    private fun adjacentResourcePairs(method: MethodData): List<ResourcePair> =
        method.usingNumbers
            .map { number -> if (number.bitWidth == Int.SIZE_BITS) number.intValue() else null }
            .zipWithNext()
            .mapNotNull { (label, icon) ->
                if (label != null && icon != null && isAppResource(label) && isAppResource(icon)) {
                    ResourcePair(labelId = label, iconId = icon)
                } else {
                    null
                }
            }

    private fun isAppResource(value: Int): Boolean = value ushr 24 == APP_RESOURCE_PACKAGE

    private fun resolveNotificationBadge(anchored: AnchoredClasses): AccountMenuTargets {
        val topBar = anchored.single(NOTIFICATION_TOP_BAR_ANCHOR)
        val hashCode =
            topBar.methods.singleOrNull { method ->
                method.methodName == "hashCode" && method.paramCount == 0
            } ?: throw ResolutionFailure("notification top bar hashCode missing or ambiguous")
        val operations = hashCode.opNames
        val badgeField = hashCode.usingFields.firstOrNull()?.field
        if (operations.size < 2 || !operations[0].startsWith("iget-object") ||
            operations[1] !in nullCheckBranches || badgeField == null
        ) {
            throw ResolutionFailure("notification badge null check not found")
        }
        val constructor =
            topBar.methods.singleOrNull { method ->
                method.isConstructor &&
                    method.paramTypeNames.count { it == badgeField.typeName } == 1
            } ?: throw ResolutionFailure("notification badge constructor missing or ambiguous")
        return AccountMenuTargets(
            notificationTopBarConstructor = constructor.toConstructorRef(),
            notificationBadgeTypeName = badgeField.typeName,
        )
    }

    private fun actionRoleField(
        bridge: DexKitBridge,
        roleEnum: ClassData,
    ): FieldData? {
        val holders =
            bridge
                .findClass {
                    matcher {
                        fields { add { type(roleEnum.name, StringMatchType.Equals, false) } }
                    }
                }.mapNotNull { holder ->
                    holder.fields
                        .filter { field ->
                            !Modifier.isStatic(field.modifiers) && field.typeName == roleEnum.name
                        }.singleOrNull()
                }.associateBy(FieldData::declaredClassName)
        val built =
            holders.keys.filter { holder ->
                holders.keys.any { other ->
                    other != holder &&
                        bridge.getClassData(other)?.methods?.any { method ->
                            !method.isConstructor &&
                                !Modifier.isStatic(method.modifiers) &&
                                method.returnTypeName == holder
                        } == true
                }
            }
        return built.singleOrNull()?.let(holders::getValue)
    }

    private fun resolveManageApps(
        bridge: DexKitBridge,
        anchored: AnchoredClasses,
    ): ManageAppsTargets {
        val manageList = anchored.single(MANAGE_LIST_ANCHOR)
        val listConstructors =
            manageList.methods.count { method ->
                method.isConstructor && method.paramTypeNames.count { it == "java.util.List" } == 1
            }
        if (listConstructors == 0) throw ResolutionFailure("manage list constructors=0")
        val missing = ArrayList<String>()

        fun row(
            name: String,
            number: Int,
        ): String? = composableRowClass(bridge, number).also { if (it == null) missing += name }

        val playProtectRows = playProtectRows(anchored)
        if (playProtectRows.isEmpty()) missing += "play protect rows"
        val veMetadata = anchored.all(listOf(VE_METADATA_ANCHOR)).singleOrNull()
        return ManageAppsTargets(
            manageListClassName = manageList.name,
            playProtectRowClassNames = playProtectRows,
            ratingsRowClassName = row("ratings row", RATINGS_AND_REVIEWS_ELEMENT),
            contributionsRowClassName = row("contributions row", CONTRIBUTIONS_COMPOSE_GROUP),
            deviceSyncRowClassName = row("device sync row", DEVICE_SYNC_ELEMENT),
            searchLandingItemClassName =
                veMetadata?.let { searchLandingItemClass(bridge, it) }.also {
                    if (it == null) missing += "search landing item"
                },
            unresolved = missing,
        )
    }

    private fun composableRowClass(
        bridge: DexKitBridge,
        number: Int,
    ): String? =
        bridge
            .findMethod {
                matcher {
                    usingNumbers(number)
                    returnType = "void"
                }
            }.flatMap { method -> method.paramTypeNames }
            .distinct()
            .filterNot(primitiveTypes::contains)
            .mapNotNull(bridge::getClassData)
            .filter { type -> !Modifier.isInterface(type.modifiers) && type.interfaceCount > 0 }
            .singleOrNull()
            ?.name

    /**
     * Row models created by the Play Protect section provider of the Manage overview. They are
     * single-state wrappers built directly by the class that logs under the provider's tag.
     */
    private fun playProtectRows(anchored: AnchoredClasses): List<String> {
        val provider =
            anchored.all(listOf(PROTECT_SECTION_ANCHOR)).singleOrNull() ?: return emptyList()
        return provider.methods
            .asSequence()
            .flatMap { method -> method.invokes.asSequence() }
            .filter { method ->
                method.isConstructor &&
                    method.paramCount == 1 &&
                    !method.declaredClassName.startsWith("java.") &&
                    !method.declaredClassName.startsWith("kotlin.")
            }.mapNotNull(MethodData::declaredClass)
            .filter { row ->
                // A row wraps one UI state; plain text wrappers are not rows.
                row.fields
                    .filter { field -> !Modifier.isStatic(field.modifiers) }
                    .singleOrNull()
                    ?.typeName
                    .let { type -> type != null && !type.startsWith("java.") }
            }.map(ClassData::name)
            .distinct()
            .toList()
    }

    private fun searchLandingItemClass(
        bridge: DexKitBridge,
        veMetadata: ClassData,
    ): String? =
        bridge
            .findMethod { matcher { usingNumbers(VISUAL_CATEGORIES_ELEMENT) } }
            .flatMap { method -> method.invokes }
            .filter { method -> method.isConstructor && veMetadata.name in method.paramTypeNames }
            .map(MethodData::declaredClassName)
            .filter { name -> name != veMetadata.name }
            .distinct()
            .singleOrNull()

    private fun resolvePersistentNav(
        bridge: DexKitBridge,
        anchored: AnchoredClasses,
    ): PersistentNavTargets {
        val navClass = anchored.single(NAV_CONTENT_ANCHOR)
        val navItemClass = anchored.single(NAV_ITEM_ANCHOR)
        val navActionClass = anchored.single(NAV_ACTION_ANCHOR)
        val navTypeNames = anchored.all(navTypeAnchors).mapTo(mutableSetOf(), ClassData::name)
        val navItemTypeFields =
            navItemClass.fields.filter { field ->
                !Modifier.isStatic(field.modifiers) && field.typeName in navTypeNames
            }
        val navItemTypeField =
            navItemTypeFields.singleOrNull()
                ?: throw ResolutionFailure(
                    "nav type candidates=${navTypeNames.size} " +
                        "item intersections=${navItemTypeFields.size}",
                )
        val actionFields =
            navActionClass.fields.filter { field ->
                !Modifier.isStatic(field.modifiers)
            }
        if (actionFields.size != 2 || actionFields.map(FieldData::typeName).distinct().size != 1) {
            throw ResolutionFailure(
                "nav action fields=${actionFields.size} " +
                    "distinctTypes=${actionFields.map(FieldData::typeName).distinct().size}",
            )
        }
        val actionDescription =
            navActionClass.methods.singleOrNull { method ->
                method.methodName == "toString" && method.paramCount == 0 &&
                    method.usingStrings.filter { it != ")" } ==
                    listOf(
                        NAV_ACTION_ANCHOR,
                        ", onPersistentNavItemOnboardingAnimationPlayed=",
                    )
            } ?: throw ResolutionFailure("nav action role description missing or ambiguous")
        val roleReads =
            actionDescription.opNames
                .filter { operation ->
                    operation.startsWith("const-string") || operation == "iget-object"
                }.map { operation ->
                    if (operation.startsWith("const-string")) "string" else "field"
                }
        if (roleReads != listOf("string", "field", "string", "field") &&
            roleReads != listOf("string", "field", "string", "field", "string")
        ) {
            throw ResolutionFailure("nav action role read order is not established")
        }
        val describedFields = actionDescription.usingFields.map { it.field }
        if (describedFields.size != 2 || describedFields.toSet() != actionFields.toSet()) {
            throw ResolutionFailure("nav action role fields missing or ambiguous")
        }
        val callbackClassName = actionFields.first().typeName
        val callbackClass =
            bridge.getClassData(callbackClassName)
                ?: throw ResolutionFailure("nav callback class missing")
        // The filter wraps the callback in a java.lang.reflect.Proxy, which needs an interface.
        if (!Modifier.isInterface(callbackClass.modifiers)) {
            throw ResolutionFailure("nav callback type is not an interface")
        }
        val callbackMethods =
            callbackClass.methods.filter { method ->
                !method.isConstructor &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.paramTypeNames == listOf("java.lang.Object") &&
                    method.returnTypeName == "java.lang.Object"
            }
        val callbackMethod =
            callbackMethods.singleOrNull()
                ?: throw ResolutionFailure("nav callback methods=${callbackMethods.size}")
        val actionConstructor =
            navActionClass.methods.singleOrNull { method ->
                method.isConstructor &&
                    method.paramTypeNames == listOf(callbackClassName, callbackClassName)
            } ?: throw ResolutionFailure("nav action constructor missing or ambiguous")
        val navConstructors =
            navClass.methods.filter { method ->
                val types = method.paramTypeNames
                method.isConstructor &&
                    types.count { it == "java.util.List" } == 1 &&
                    types.count { it == "int" } == 1 &&
                    types.count { it == navActionClass.name } == 1
            }
        val navConstructor =
            navConstructors.singleOrNull()
                ?: throw ResolutionFailure("nav constructors=${navConstructors.size}")
        val navHiddenField =
            anchored.all(listOf(NAV_HIDDEN_ANCHOR)).singleOrNull()?.let { hidden ->
                hidden.fields.singleOrNull { field ->
                    Modifier.isStatic(field.modifiers) && field.typeName == hidden.name
                }
            }
        return PersistentNavTargets(
            navConstructor = navConstructor.toConstructorRef(),
            navItemTypeField = navItemTypeField.toRef(),
            navPrimaryCallbackField = describedFields[0].toRef(),
            navSecondaryCallbackField = describedFields[1].toRef(),
            navActionConstructor = actionConstructor.toConstructorRef(),
            navCallbackMethod = callbackMethod.toRef(),
            navHiddenInstanceField = navHiddenField?.toRef(),
        )
    }

    private fun resolveSelfUpdate(bridge: DexKitBridge): SelfUpdateTargets {
        val schedulers =
            bridge.findMethod {
                matcher {
                    returnType = "boolean"
                    usingStrings(selfUpdateSchedulerAnchors, StringMatchType.Contains)
                }
            }
        val scheduler =
            schedulers.singleOrNull()
                ?: throw ResolutionFailure("self-update schedulers=${schedulers.size}")
        val autoUpdateMethod =
            bridge
                .findMethod {
                    matcher { usingStrings(listOf(AUTO_UPDATE_ANCHOR), StringMatchType.Contains) }
                }.singleOrNull()
                ?.declaredClass
                ?.methods
                ?.filter { method ->
                    val types = method.paramTypeNames
                    method.returnTypeName == "void" &&
                        types.count { it == "int" } == 1 &&
                        types.count { it == "java.util.List" } == 1 &&
                        types.count { type -> completesWithSet(bridge, type) } == 1
                }?.singleOrNull()
        return SelfUpdateTargets(
            schedulerMethod = scheduler.toRef(),
            autoUpdateMethod = autoUpdateMethod?.toRef(),
        )
    }

    private fun resolveSearchOrder(
        bridge: DexKitBridge,
        anchored: AnchoredClasses,
    ): SearchOrderTargets {
        val dispatcher = anchored.single(ITEM_PREFETCH_ANCHOR)
        val prefetchInterfaces =
            dispatcher.fields
                .filterNot { field -> Modifier.isStatic(field.modifiers) }
                .map(FieldData::typeName)
                .distinct()
                .filterNot(primitiveTypes::contains)
                .mapNotNull(bridge::getClassData)
                .filter { type ->
                    Modifier.isInterface(type.modifiers) &&
                        type.methods.singleOrNull()?.let(::takesItemBatch) == true
                }
        val prefetchInterface =
            prefetchInterfaces.singleOrNull()
                ?: throw ResolutionFailure("item prefetch interfaces=${prefetchInterfaces.size}")
        val prefetchMethodName = prefetchInterface.methods.single().name
        val implementations =
            bridge.findClass {
                matcher { addInterface(prefetchInterface.name) }
            }
        val methods =
            implementations.mapNotNull { implementation ->
                implementation.methods.singleOrNull { method ->
                    method.name == prefetchMethodName &&
                        !Modifier.isAbstract(method.modifiers) &&
                        takesItemBatch(method)
                }
            }
        if (methods.isEmpty()) {
            throw ResolutionFailure(
                "item prefetch implementations=${implementations.size} methods=0",
            )
        }
        return SearchOrderTargets(methods.map { it.toRef() }.sortedBy(MethodRef::className))
    }

    private fun resolveSnapshotConstructor(bridge: DexKitBridge): MethodData {
        val snapshots =
            bridge
                .findMethod {
                    matcher { usingStrings(STREAM_SNAPSHOT_ANCHOR) }
                }.filter {
                    it.returnTypeName == "java.lang.String" &&
                        !Modifier.isStatic(it.modifiers)
                }
        val snapshot =
            snapshots.singleOrNull()
                ?: throw ResolutionFailure("stream snapshot methods=${snapshots.size}")
        val constructors =
            snapshot.declaredClass?.methods.orEmpty().filter { method ->
                method.isConstructor && hasSnapshotKeyFirst(method.paramTypeNames)
            }
        return constructors.singleOrNull()
            ?: throw ResolutionFailure("stream snapshot constructors=${constructors.size}")
    }

    private fun resolveSearchRoot(
        bridge: DexKitBridge,
        constructor: MethodData,
    ): SearchRootTargets {
        val roots =
            constructor.callers.filter { method ->
                !Modifier.isStatic(method.modifiers) && method.returnTypeName == "void" &&
                    method.paramCount == 1 &&
                    method.declaredClassName != constructor.declaredClassName
            }
        val root =
            roots.singleOrNull()
                ?: throw ResolutionFailure("stream root callers=${roots.size}")
        val handlers =
            bridge.findClass {
                matcher { usingStrings = listOf("StreamNodeHandler has already been initialized.") }
            }
        val handler =
            handlers.singleOrNull()
                ?: throw ResolutionFailure("stream handlers=${handlers.size}")
        val dataMethods =
            handler.methods.filter {
                Modifier.isPrivate(it.modifiers) &&
                    it.paramTypeNames == listOf("java.lang.Throwable") &&
                    it.returnTypeName != "void"
            }
        val dataMethod =
            dataMethods.singleOrNull()
                ?: throw ResolutionFailure("stream data methods=${dataMethods.size}")
        val refreshMethods =
            dataMethod.callers.filter {
                it.declaredClassName == handler.name && !Modifier.isStatic(it.modifiers) &&
                    it.paramCount == 0 && it.returnTypeName == "void"
            }
        val refresh =
            refreshMethods.singleOrNull()
                ?: throw ResolutionFailure("stream refresh methods=${refreshMethods.size}")
        val loadMethods =
            bridge
                .findMethod {
                    matcher { usingStrings("StreamDataStore is not initialized.") }
                }.filter {
                    !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
                        it.returnTypeName == "void"
                }
        val loadNext =
            loadMethods.singleOrNull()
                ?: throw ResolutionFailure("stream load methods=${loadMethods.size}")
        return SearchRootTargets(
            constructor.toConstructorRef(),
            root.declaredClassName,
            root.toRef(),
            refresh.toRef(),
            loadNext.toRef(),
        )
    }

    private fun resolveSearchScroll(bridge: DexKitBridge): SearchScrollTargets {
        val layouts =
            bridge.findMethod {
                matcher { usingStrings("firstVisibleItem:index") }
            }
        val owners =
            layouts
                .mapNotNull { it.declaredClass }
                .distinctBy { it.name }
        val stateClass =
            owners.singleOrNull()
                ?: throw ResolutionFailure("search list state classes=${owners.size}")
        val layoutMethods = layouts.filter { it.declaredClassName == stateClass.name }
        val layout =
            layoutMethods.singleOrNull()
                ?: throw ResolutionFailure("search list layout methods=${layoutMethods.size}")
        val methods =
            stateClass.methods.filter {
                !Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                    it.paramTypeNames == listOf("int", "int", "boolean")
            }
        val scroll =
            methods.singleOrNull()
                ?: throw ResolutionFailure("search list scroll methods=${methods.size}")
        val providers =
            bridge
                .findMethod {
                    matcher { returnType(stateClass.name) }
                }.filter {
                    Modifier.isStatic(it.modifiers) && it.paramCount == 2 &&
                        it.paramTypeNames.firstOrNull() == "java.lang.String" &&
                        it.callers.isNotEmpty()
                }
        val pairedProviders =
            providers.filter { candidate ->
                candidate.invokes
                    .filter {
                        it.returnTypeName == "java.lang.Object" &&
                            it.descriptor.contains("([Ljava/lang/Object;")
                    }.groupingBy { it.descriptor }
                    .eachCount()
                    .values
                    .any { it >= 2 }
            }
        val provider =
            pairedProviders.singleOrNull()
                ?: throw ResolutionFailure("search list state providers=${pairedProviders.size}")
        val renderers =
            bridge
                .findMethod {
                    matcher { usingStrings("UI_MODEL_RECEIVED") }
                }.filter {
                    !Modifier.isStatic(it.modifiers) && it.returnTypeName == "java.lang.Object" &&
                        it.paramCount == 2 &&
                        it.usingFields.any { access ->
                            access.field.declaredClassName == it.paramTypeNames.first()
                        }
                }
        val renderer =
            renderers.singleOrNull()
                ?: throw ResolutionFailure("search renderer methods=${renderers.size}")
        val rendererCallers = renderer.callers
        if (rendererCallers.isEmpty()) throw ResolutionFailure("search renderer callers=0")
        return SearchScrollTargets(
            provider.toRef(),
            scroll.toRef(),
            provider.callers.map { it.toRef() },
            renderer.toRef(),
            layout.toRef(),
            rendererCallers.map { it.toRef() },
        )
    }

    private fun resolveSearchSortChip(
        bridge: DexKitBridge,
        snapshotKeyType: () -> String,
    ): SearchSortChipTargets {
        val pageMethods =
            bridge
                .findMethod {
                    matcher {
                        usingStrings(
                            listOf(SEARCH_FILTER_BAR_ANCHOR),
                            StringMatchType.Contains,
                        )
                    }
                }.filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.paramTypeNames.getOrNull(1) == "java.util.List" &&
                        method.paramTypeNames.getOrNull(2) == "boolean" &&
                        method.returnTypeName == "java.lang.Object"
                }
        val page =
            pageMethods.singleOrNull()
                ?: throw ResolutionFailure("search filter bar methods=${pageMethods.size}")
        val controller =
            page.declaredClass
                ?: throw ResolutionFailure("search filter controller missing")
        val filters =
            controller.methods.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.paramTypeNames.getOrNull(0) == page.paramTypeNames.first() &&
                    method.paramTypeNames.getOrNull(1) == "java.util.List" &&
                    "boolean" !in method.paramTypeNames &&
                    method.returnTypeName == "java.lang.Object"
            }
        val filter =
            filters.singleOrNull()
                ?: throw ResolutionFailure("search filter change methods=${filters.size}")
        val urls =
            bridge
                .findMethod {
                    matcher { usingStrings(listOf("_all", "sft"), StringMatchType.Contains) }
                }.filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.paramTypeNames == listOf("java.lang.String", "java.util.List") &&
                        method.returnTypeName == "java.lang.String"
                }
        val url =
            urls.singleOrNull()
                ?: throw ResolutionFailure("search filter URL methods=${urls.size}")
        val analytics =
            url.declaredClass?.methods.orEmpty().filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.returnTypeName == "void" &&
                    method.paramTypeNames.count { it == "java.util.List" } == 1 &&
                    method.paramTypeNames.take(2) == listOf("java.lang.String", "java.lang.String")
            }
        val analyticsMethod =
            analytics.singleOrNull()
                ?: throw ResolutionFailure("search filter analytics methods=${analytics.size}")
        val lifecycleMethods =
            bridge
                .findMethod {
                    matcher {
                        usingStrings(
                            listOf("PAGE_LOAD_FIRST_RPC_INITIATED"),
                            StringMatchType.Contains,
                        )
                    }
                }.filter { it.declaredClassName == controller.name }
        val lifecycle =
            lifecycleMethods.singleOrNull()
                ?: throw ResolutionFailure("search lifecycle methods=${lifecycleMethods.size}")
        val filterConstructors =
            filter.invokes
                .filter { it.isConstructor }
                .map { it.declaredClassName }
                .toSet()
        val structured =
            bridge
                .findMethod { matcher { usingStrings(listOf("_all"), StringMatchType.Contains) } }
                .filter { method ->
                    val types =
                        method.declaredClass
                            ?.fields
                            .orEmpty()
                            .map(FieldData::typeName)
                    val typedCaptures =
                        controller.name in types && page.paramTypeNames.first() in types
                    val constructedByFilter = method.declaredClassName in filterConstructors
                    (typedCaptures || constructedByFilter) &&
                        method.invokes.any { it.descriptor == url.descriptor }
                }
        if (structured.size != 1) {
            throw ResolutionFailure("search structured filter methods=${structured.size}")
        }
        val callbacks =
            analyticsMethod.callers.filter { method ->
                isVoidSortSelection(method.returnTypeName) &&
                    method.paramTypeNames == listOf("java.util.List", "boolean") &&
                    method.usingFields.any { it.field.declaredClassName == controller.name }
            }
        val callback =
            callbacks.singleOrNull()
                ?: throw ResolutionFailure("search filter callbacks=${callbacks.size}")
        val ownerFields =
            callback.declaredClass?.fields.orEmpty().filter { field ->
                !Modifier.isStatic(field.modifiers) && field.typeName == "java.lang.Object"
            }
        val ownerField =
            ownerFields.singleOrNull()
                ?: throw ResolutionFailure("search callback owner fields=${ownerFields.size}")
        val selectionFields =
            callback.usingFields
                .map { it.field }
                .distinctBy(FieldData::descriptor)
                .filter { field ->
                    val types =
                        field.declaredClass.fields
                            .filterNot { Modifier.isStatic(it.modifiers) }
                            .map(FieldData::typeName)
                            .sorted()
                    field.typeName == "java.lang.String" &&
                        types == listOf("boolean", "java.lang.String")
                }
        val selectionField =
            selectionFields.singleOrNull()
                ?: throw ResolutionFailure("search selection id fields=${selectionFields.size}")
        val filterCallers = filter.callers.distinctBy(MethodData::descriptor)
        if (filterCallers.isEmpty()) throw ResolutionFailure("search filter callers=0")
        val keyType = snapshotKeyType()
        val scopeClasses =
            bridge.findClass {
                matcher { fields { add { type(controller.name, StringMatchType.Equals, false) } } }
            }
        val scopes =
            scopeClasses.flatMap { scope ->
                val owner = scope.fields.filter { it.typeName == controller.name }.singleOrNull()
                if (owner == null) {
                    emptyList()
                } else {
                    scope.methods.flatMap { method ->
                        method.invokes
                            .filter { invoked ->
                                invoked.isConstructor &&
                                    invoked.paramTypeNames.count { it == keyType } == 1
                            }.map { constructor -> Triple(method, owner, constructor) }
                    }
                }
            }
        val streamConstructors = scopes.map { it.third }.distinctBy(MethodData::descriptor)
        val streamConstructor =
            streamConstructors.singleOrNull()
                ?: throw ResolutionFailure("search stream constructors=${streamConstructors.size}")
        val streamScopes =
            scopes
                .map { (method, owner, _) ->
                    SearchControllerScope(method.toRef(), owner.toRef())
                }.distinct()
        if (streamScopes.isEmpty()) throw ResolutionFailure("search controller scopes=0")
        val scopeCancellation =
            bridge
                .findMethod {
                    matcher {
                        usingStrings(
                            listOf("Job is still new or active:"),
                            StringMatchType.Contains,
                        )
                    }
                }.filter {
                    !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
                        it.returnTypeName == "java.util.concurrent.CancellationException"
                }.singleOrNull()
        return SearchSortChipTargets(
            page.toRef(),
            filter.toRef(),
            analyticsMethod.toRef(),
            lifecycle.toRef(),
            callback.toRef(),
            ownerField.toRef(),
            selectionField.toRef(),
            filterCallers.map { it.toRef() },
            streamConstructor.toConstructorRef(),
            streamScopes,
            scopeCancellation?.toRef(),
        )
    }

    private fun takesItemBatch(method: MethodData): Boolean =
        !Modifier.isStatic(method.modifiers) &&
            method.returnTypeName == "void" &&
            method.paramTypeNames == listOf("java.util.List", "boolean")

    private fun completesWithSet(
        bridge: DexKitBridge,
        typeName: String,
    ): Boolean =
        typeName !in primitiveTypes &&
            bridge.getClassData(typeName)?.methods?.any { method ->
                method.returnTypeName == "void" && method.paramTypeNames == listOf("java.util.Set")
            } == true

    private inline fun attempt(
        name: String,
        block: () -> Any,
    ): Resolution =
        try {
            Resolution.Success(block())
        } catch (failure: InstallSessionQueueResolver.Missing) {
            Resolution.Failure(failure.message.orEmpty(), transient = false)
        } catch (failure: ResolutionFailure) {
            Resolution.Failure(failure.message.orEmpty(), transient = false)
        } catch (exception: Exception) {
            Resolution.Failure(
                "$name resolution threw ${exception.javaClass.simpleName}: ${exception.message}",
                transient = true,
            )
        } catch (error: LinkageError) {
            Resolution.Failure(
                "$name resolution threw ${error.javaClass.simpleName}: ${error.message}",
                transient = true,
            )
        }

    private sealed interface Resolution {
        data class Success(
            val value: Any,
        ) : Resolution

        data class Failure(
            val reason: String,
            val transient: Boolean,
        ) : Resolution
    }

    private fun Resolution.success(): Any? = (this as? Resolution.Success)?.value

    private class ResolutionFailure(
        reason: String,
    ) : RuntimeException(reason)

    private fun MethodData.toRef(): MethodRef =
        MethodRef(
            className = declaredClassName,
            methodName = methodName,
            returnTypeName = returnTypeName,
            paramTypeNames = paramTypeNames,
        )

    private fun MethodData.toConstructorRef(): ConstructorRef =
        ConstructorRef(className = declaredClassName, paramTypeNames = paramTypeNames)

    private fun FieldData.toRef(): FieldRef =
        FieldRef(className = declaredClassName, fieldName = fieldName)

    internal fun hasSnapshotKeyFirst(types: List<String>): Boolean =
        types.firstOrNull()?.let {
            it !in primitiveTypes &&
                it !in setOf("java.util.List", "java.lang.Throwable", "java.lang.String") &&
                types.count { type -> type == "java.util.List" } == 1 &&
                "boolean" in types && "java.lang.Throwable" in types
        } == true
}
