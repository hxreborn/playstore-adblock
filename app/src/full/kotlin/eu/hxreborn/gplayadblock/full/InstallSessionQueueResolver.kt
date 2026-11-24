package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.discovery.FieldRef
import eu.hxreborn.gplayadblock.discovery.MethodRef
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal object InstallSessionQueueResolver {
    class Missing(
        reason: String,
    ) : RuntimeException(reason)

    private data class Event(
        val field: FieldData? = null,
        val method: MethodData? = null,
    )

    fun resolve(bridge: DexKitBridge): InstallSessionQueueTargets =
        try {
            resolveTargets(bridge)
        } catch (exception: NoSuchElementException) {
            throw Missing(exception.message.orEmpty())
        } catch (exception: IllegalArgumentException) {
            throw Missing(exception.message.orEmpty())
        }

    private fun resolveTargets(bridge: DexKitBridge): InstallSessionQueueTargets {
        val stateMethod =
            bridge
                .findMethod {
                    matcher {
                        usingStrings =
                            listOf("DOWNLOAD_PENDING", "INSTALLING", "INSTALLED", "SCHEDULED")
                    }
                }.single()
        val staleMethod =
            bridge
                .findMethod {
                    matcher { usingStrings = listOf("There are no stale sessions to be pruned") }
                }.single()
        val callback =
            staleMethod.declaredClass!!
                .methods
                .filter { it.isConstructor }
                .flatMap { it.callers }
                .single()
        val app =
            requireNotNull(
                bridge.getClassData(
                    "com.google.android.finsky.application.classic.ClassicApplication",
                ),
            )
        val registryTypes = app.fields.map { it.typeName }.toSet()
        val queryCaller =
            callback.declaredClass!!
                .methods
                .filter { it.isConstructor }
                .flatMap { it.callers }
                .filter { caller ->
                    caller.usingFields.any {
                        it.field.declaredClassName == stateMethod.declaredClassName &&
                            Modifier.isStatic(it.field.modifiers)
                    } &&
                        caller.usingFields.any { it.field.declaredClassName in registryTypes }
                }.distinctBy { it.descriptor }
                .single()
        val events = events(queryCaller)
        val stateEvents =
            events.withIndex().filter { (_, event) ->
                event.field?.let {
                    it.declaredClassName == stateMethod.declaredClassName &&
                        Modifier.isStatic(it.modifiers)
                } ==
                    true
            }
        val matches =
            stateEvents
                .mapNotNull { (index, stateEvent) ->
                    val before = events.take(index)
                    val after = events.drop(index + 1)
                    val filterDefault =
                        before
                            .lastOrNull {
                                it.field?.let { field ->
                                    Modifier.isStatic(field.modifiers) &&
                                        field.typeName == field.declaredClassName
                                } ==
                                    true
                            }?.field
                            ?: return@mapNotNull null
                    val builder =
                        before
                            .lastOrNull {
                                it.method?.let { method ->
                                    method.paramCount == 0 &&
                                        method.returnTypeName != "void"
                                } ==
                                    true
                            }?.method
                            ?: return@mapNotNull null
                    val add =
                        after
                            .firstOrNull {
                                it.method?.let { method ->
                                    method.paramTypeNames ==
                                        listOf("java.lang.Iterable") &&
                                        method.returnTypeName == "void"
                                } ==
                                    true
                            }?.method
                            ?: return@mapNotNull null
                    val build =
                        after
                            .dropWhile { it.method != add }
                            .drop(1)
                            .firstOrNull {
                                it.method?.let { method ->
                                    method.paramCount ==
                                        0 &&
                                        method.returnTypeName != "void"
                                } ==
                                    true
                            }?.method
                            ?: return@mapNotNull null
                    val query =
                        after
                            .dropWhile { it.method != build }
                            .drop(1)
                            .firstOrNull {
                                it.method?.paramTypeNames ==
                                    listOf(filterDefault.typeName)
                            }?.method
                            ?: return@mapNotNull null
                    val service =
                        before
                            .lastOrNull {
                                it.field?.declaredClassName != filterDefault.declaredClassName &&
                                    it.field?.typeName == "java.lang.Object"
                            }?.field
                            ?: return@mapNotNull null
                    val registryIndex =
                        before.indexOfLast { it.field?.declaredClassName in registryTypes }
                    if (registryIndex < 0) return@mapNotNull null
                    val registry = requireNotNull(before[registryIndex].field)
                    val provider =
                        events
                            .drop(registryIndex + 1)
                            .firstOrNull {
                                it.method?.let { method ->
                                    method.paramCount ==
                                        0 &&
                                        method.returnTypeName == "java.lang.Object"
                                } ==
                                    true
                            }?.method
                            ?: return@mapNotNull null
                    val appRegistry =
                        app.fields.singleOrNull { it.typeName == registry.declaredClassName }
                            ?: return@mapNotNull null
                    QueueMethods(
                        applicationRegistry = appRegistry.ref(),
                        registryProvider = registry.ref(),
                        providerGet = provider.ref(),
                        adminService = service.ref(),
                        filterDefault = filterDefault.ref(),
                        filterBuilder = builder.ref(),
                        activeStates = stateEvent.field!!.ref(),
                        addStates = add.ref(),
                        buildFilter = build.ref(),
                        query = query.ref(),
                    )
                }.single()
        val contextTypes =
            staleMethod.invokes
                .filter { it.isConstructor }
                .flatMap { it.declaredClass?.methods.orEmpty() }
                .filter { it.methodName == "test" && it.returnTypeName == "boolean" }
                .flatMap { test ->
                    val fields = test.usingFields.map { it.field }
                    fields
                        .filter { field ->
                            !Modifier.isStatic(field.modifiers) &&
                                fields.any { other ->
                                    Modifier.isStatic(other.modifiers) &&
                                        other.declaredClassName == field.typeName
                                }
                        }.map { it.typeName }
                }.distinct()
        val context = contextTypes.single()
        val getters =
            callback.invokes
                .filter { it.isConstructor }
                .flatMap { it.declaredClass?.methods.orEmpty() }
                .filter { it.methodName == "test" && it.returnTypeName == "boolean" }
                .flatMap { test ->
                    val predicateEvents = events(test)
                    predicateEvents
                        .withIndex()
                        .filter { (_, event) ->
                            event.field?.declaredClassName ==
                                context &&
                                event.field?.typeName == "java.lang.String"
                        }.mapNotNull { (index, _) ->
                            val next = predicateEvents.drop(index + 1).take(10)
                            val getter =
                                next
                                    .firstOrNull {
                                        it.method?.let { method ->
                                            method.declaredClassName ==
                                                stateMethod.declaredClassName &&
                                                method.paramCount == 0 &&
                                                method.returnTypeName == "java.lang.String"
                                        } ==
                                            true
                                    }?.method
                            if (next.any {
                                    it.method?.let { method ->
                                        method.declaredClassName ==
                                            "java.lang.String" &&
                                            method.methodName == "equals"
                                    } ==
                                        true
                                }
                            ) {
                                getter
                            } else {
                                null
                            }
                        }
                }.distinctBy { it.descriptor }
        val getter = getters.single()
        val callbackEvents = events(callback)
        val groupGetters =
            callbackEvents
                .withIndex()
                .mapNotNull { (index, event) ->
                    val method = event.method ?: return@mapNotNull null
                    if (method.declaredClassName != stateMethod.declaredClassName ||
                        method.paramCount != 0 || method.returnTypeName != "boolean"
                    ) {
                        return@mapNotNull null
                    }
                    val next = callbackEvents.drop(index + 1).take(32)
                    if (next.any { it.field?.declaredClassName == stateMethod.declaredClassName } &&
                        next.any { event ->
                            event.method?.let {
                                it.paramCount == 0 && it.returnTypeName !in
                                    setOf("void", "boolean", "int", "long", "java.lang.String")
                            } == true
                        }
                    ) {
                        method
                    } else {
                        null
                    }
                }.distinctBy { it.descriptor }
        val groupGetter = groupGetters.single()
        val stateWriters =
            bridge
                .findMethod {
                    matcher { usingStrings = listOf("state", "reason", "constraints") }
                }.filter { writer ->
                    writer.invokes.any { method ->
                        method.declaredClassName == stateMethod.declaredClassName &&
                            method.paramCount == 0 && method.returnTypeName == "int"
                    }
                }
        val stateGetter =
            stateWriters
                .single()
                .invokes
                .filter { method ->
                    method.declaredClassName == stateMethod.declaredClassName &&
                        method.paramCount == 0 && method.returnTypeName == "int"
                }.distinctBy { it.descriptor }
                .single()
        require(Modifier.isStatic(stateMethod.modifiers))
        require(
            stateMethod.paramTypeNames == listOf("int") &&
                stateMethod.returnTypeName == "java.lang.String",
        )
        return InstallSessionQueueTargets(
            applicationRegistry = matches.applicationRegistry,
            registryProvider = matches.registryProvider,
            providerGet = matches.providerGet,
            adminService = matches.adminService,
            filterDefault = matches.filterDefault,
            filterBuilder = matches.filterBuilder,
            activeStates = matches.activeStates,
            addStates = matches.addStates,
            buildFilter = matches.buildFilter,
            query = matches.query,
            taskPackage = getter.ref(),
            taskIsGroup = groupGetter.ref(),
            taskState = stateGetter.ref(),
            stateName = stateMethod.ref(),
        )
    }

    private data class QueueMethods(
        val applicationRegistry: FieldRef,
        val registryProvider: FieldRef,
        val providerGet: MethodRef,
        val adminService: FieldRef,
        val filterDefault: FieldRef,
        val filterBuilder: MethodRef,
        val activeStates: FieldRef,
        val addStates: MethodRef,
        val buildFilter: MethodRef,
        val query: MethodRef,
    )

    private fun events(method: MethodData): List<Event> {
        var fieldIndex = 0
        var methodIndex = 0
        val result =
            method.opNames.map { op ->
                when {
                    op.startsWith("iget") || op.startsWith("iput") || op.startsWith("sget") ||
                        op.startsWith(
                            "sput",
                        ) -> Event(field = method.usingFields[fieldIndex++].field)

                    isIndexedQueueInvoke(op) -> Event(method = method.invokes[methodIndex++])

                    else -> Event()
                }
            }
        require(fieldIndex == method.usingFields.size && methodIndex == method.invokes.size)
        return result
    }

    private fun FieldData.ref() = FieldRef(declaredClassName, fieldName)

    private fun MethodData.ref() =
        MethodRef(declaredClassName, methodName, returnTypeName, paramTypeNames)
}

private val indexedQueueInvokes =
    setOf(
        "invoke-virtual",
        "invoke-super",
        "invoke-direct",
        "invoke-static",
        "invoke-interface",
    )

internal fun isIndexedQueueInvoke(op: String): Boolean =
    op.removeSuffix("/range") in indexedQueueInvokes
