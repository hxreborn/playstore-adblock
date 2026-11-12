package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.discovery.ConstructorRef
import eu.hxreborn.gplayadblock.discovery.DexKitResolver
import eu.hxreborn.gplayadblock.discovery.FieldRef
import eu.hxreborn.gplayadblock.discovery.MethodRef
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

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
        val reasons = groups.associateWith { ArrayList<String>() }
        var transientFailure = false
        for (apkPath in apkPaths.distinct()) {
            val pending = groups - merged.completeGroups()
            if (pending.isEmpty()) break
            try {
                DexKitBridge.create(apkPath).use { bridge ->
                    val resolved = resolve(bridge, pending)
                    resolved.failures.forEach { (group, reason) ->
                        reasons.getValue(group) += "$apkPath: $reason"
                    }
                    transientFailure = transientFailure || resolved.transientFailure
                    merged = merged.withMoreCompleteGroups(resolved)
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
        return merged.copy(
            failures =
                groups.filterNot(merged::hasTargets).associateWith { group ->
                    reasons.getValue(group).joinToString(" | ").ifBlank { "no APK paths" }
                },
            transientFailure = transientFailure,
        )
    }

    internal fun resolve(
        bridge: DexKitBridge,
        groups: Set<OptionalGroup>,
    ): OptionalTargets {
        val anchored = AnchoredClasses(bridge, groups.flatMap { anchorsByGroup.getValue(it) })
        val results =
            groups.associateWith { group ->
                attempt(group.label) {
                    when (group) {
                        OptionalGroup.ACCOUNT_MENU -> resolveAccountMenu(bridge, anchored)
                        OptionalGroup.MANAGE_APPS -> resolveManageApps(bridge, anchored)
                        OptionalGroup.PERSISTENT_NAV -> resolvePersistentNav(bridge, anchored)
                        OptionalGroup.SELF_UPDATE -> resolveSelfUpdate(bridge)
                    }
                }
            }
        return OptionalTargets(
            requested = groups,
            accountMenu = results[OptionalGroup.ACCOUNT_MENU]?.success() as? AccountMenuTargets,
            manageApps = results[OptionalGroup.MANAGE_APPS]?.success() as? ManageAppsTargets,
            persistentNav =
                results[OptionalGroup.PERSISTENT_NAV]?.success() as? PersistentNavTargets,
            selfUpdate = results[OptionalGroup.SELF_UPDATE]?.success() as? SelfUpdateTargets,
            failures =
                results
                    .mapNotNull { (group, result) ->
                        (result as? Resolution.Failure)?.let { failure -> group to failure.reason }
                    }.toMap(),
            transientFailure =
                results.values.any { result ->
                    (result as? Resolution.Failure)?.transient == true
                } ||
                    (results[OptionalGroup.ACCOUNT_MENU]?.success() as? AccountMenuTargets)
                        ?.transientFailure == true,
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
        val results = listOf(actions, badge, labeledActions, headerCards)
        return targets.copy(
            notificationTopBarConstructor = notification?.notificationTopBarConstructor,
            notificationBadgeTypeName = notification?.notificationBadgeTypeName,
            labeledActions = labeledActions.success() as? LabeledActionTargets,
            headerCards = headerCards.success() as? HeaderCardTargets,
            unresolved =
                results.mapNotNull { result ->
                    (result as? Resolution.Failure)?.reason
                },
            transientFailure =
                results.any { result ->
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
            throw ResolutionFailure("account role class ${roleEnum.name} is no enum")
        }
        return AccountMenuTargets(
            featuresClassName = featuresClass.name,
            immutableListClassName = immutableList.name,
            actionRoleField =
                actionRoleField(bridge, roleEnum)?.toRef()
                    ?: throw ResolutionFailure("account action role field not unique"),
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
            } ?: throw ResolutionFailure("notification top bar hashCode not unique")
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
            } ?: throw ResolutionFailure("notification badge constructor not unique")
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
            throw ResolutionFailure("nav action fields=${actionFields.size}")
        }
        val callbackClassName = actionFields.first().typeName
        val callbackClass =
            bridge.getClassData(callbackClassName)
                ?: throw ResolutionFailure("nav callback class missing")
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
            } ?: throw ResolutionFailure("nav action constructor not unique")
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
            navPrimaryCallbackField = actionFields[0].toRef(),
            navSecondaryCallbackField = actionFields[1].toRef(),
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
}
