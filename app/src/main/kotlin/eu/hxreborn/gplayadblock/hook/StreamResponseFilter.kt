package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.IdentityHashMap

object StreamResponseFilter {
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: ResolvedTargets.Resolved,
    ) {
        val classifier = PresentationClassifier.from(classLoader, targets)
        val editor = ProtoEditor.from(classLoader, targets)
        val transformer =
            ResponseTransformer(
                responseListFields =
                    targets.responseListFields.map { field -> field.resolve(classLoader) },
                nodeWrapperKindField = targets.nodeWrapperKindField.resolve(classLoader),
                nodeWrapperPayloadField = targets.nodeWrapperPayloadField.resolve(classLoader),
                nodeIdField = targets.nodeIdField.resolve(classLoader),
                nodePresentationField = targets.nodePresentationField.resolve(classLoader),
                nodeChildrenField = targets.nodeChildrenField.resolve(classLoader),
                rootWrapperKindField = targets.rootWrapperKindField.resolve(classLoader),
                rootWrapperPayloadField = targets.rootWrapperPayloadField.resolve(classLoader),
                rootChildrenField = targets.rootChildrenField.resolve(classLoader),
                childIdsField = targets.childIdsField.resolve(classLoader),
                childPresenceField = targets.childPresenceField.resolve(classLoader),
                childContinuationField = targets.childContinuationField.resolve(classLoader),
                nodeDefaultInstance =
                    requireNotNull(
                        targets.nodeDefaultInstanceField.resolve(classLoader).get(null),
                    ),
                rootDefaultInstance =
                    requireNotNull(
                        targets.rootDefaultInstanceField.resolve(classLoader).get(null),
                    ),
                classifier = classifier,
                editor = editor,
            )
        targets.responseMethods.forEach { methodRef ->
            val method = methodRef.resolve(classLoader)
            val interceptor =
                ResponseInterceptor(
                    callbackName = methodRef.className,
                    transformer = transformer,
                )
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> interceptor.intercept(chain) }
        }
    }

    private class ResponseInterceptor(
        private val callbackName: String,
        private val transformer: ResponseTransformer,
    ) {
        fun intercept(chain: XposedInterface.Chain): Any? {
            val replacement =
                try {
                    chain.getArg(0)?.let(transformer::transform)
                } catch (exception: Exception) {
                    Logger.error(
                        "stream response filtering failed callback=$callbackName",
                        exception,
                    )
                    null
                }
            if (replacement == null) return chain.proceed()
            val arguments = chain.args.toTypedArray()
            arguments[0] = replacement
            return chain.proceed(arguments)
        }
    }

    private class ResponseTransformer(
        private val responseListFields: List<Field>,
        private val nodeWrapperKindField: Field,
        private val nodeWrapperPayloadField: Field,
        private val nodeIdField: Field,
        private val nodePresentationField: Field,
        private val nodeChildrenField: Field,
        private val rootWrapperKindField: Field,
        private val rootWrapperPayloadField: Field,
        private val rootChildrenField: Field,
        private val childIdsField: Field,
        private val childPresenceField: Field,
        private val childContinuationField: Field,
        private val nodeDefaultInstance: Any,
        private val rootDefaultInstance: Any,
        private val classifier: PresentationClassifier,
        private val editor: ProtoEditor,
    ) {
        fun transform(response: Any): Any? {
            val lists =
                responseListFields.associateWith { field ->
                    field.get(response) as? List<*> ?: emptyList<Any>()
                }
            val nodeWrappers =
                lists.values
                    .asSequence()
                    .flatMap(List<*>::asSequence)
                    .filterNotNull()
                    .filter(nodeWrapperKindField.declaringClass::isInstance)
                    .toList()
            if (nodeWrappers.isEmpty()) return null

            val decodedNodes = IdentityHashMap<Any, Any>()
            val adCasesById = LinkedHashMap<Any, Int>()
            for (wrapper in nodeWrappers) {
                val decoded = decodeNode(wrapper) ?: continue
                decodedNodes[wrapper] = decoded
                val presentation = nodePresentationField.get(decoded) ?: continue
                val case = classifier.classify(presentation)
                if (!classifier.isAd(case)) continue
                val id = nodeIdField.get(wrapper) ?: continue
                adCasesById[id] = case
            }
            if (adCasesById.isEmpty()) return null

            val decodedRoots = IdentityHashMap<Any, Any>()
            val referencedIds = HashSet<Any>()
            decodedNodes.values.forEach { node ->
                collectChildIds(nodeChildrenField.get(node), referencedIds)
            }
            lists.values
                .asSequence()
                .flatMap(List<*>::asSequence)
                .filterNotNull()
                .filter(rootWrapperKindField.declaringClass::isInstance)
                .forEach { wrapper ->
                    val decoded = decodeRoot(wrapper) ?: return@forEach
                    decodedRoots[wrapper] = decoded
                    collectChildIds(rootChildrenField.get(decoded), referencedIds)
                }

            val directRemovableIds =
                adCasesById.keys.filterTo(HashSet(), referencedIds::contains)
            if (directRemovableIds.isEmpty()) return null
            val removableIds =
                expandRemovableParents(decodedNodes, referencedIds, directRemovableIds)

            val replacement =
                editor.copy(response) { mutableResponse ->
                    for ((field, originalList) in lists) {
                        if (originalList.isEmpty()) continue
                        val transformed =
                            when {
                                originalList.all { item ->
                                    item != null &&
                                        nodeWrapperKindField.declaringClass.isInstance(item)
                                } -> {
                                    transformNodes(
                                        wrappers = originalList.filterNotNull(),
                                        decodedNodes = decodedNodes,
                                        removableIds = removableIds,
                                    )
                                }

                                originalList.all { item ->
                                    item != null &&
                                        rootWrapperKindField.declaringClass.isInstance(item)
                                } -> {
                                    transformRoots(
                                        wrappers = originalList.filterNotNull(),
                                        decodedRoots = decodedRoots,
                                        removableIds = removableIds,
                                    )
                                }

                                else -> {
                                    originalList
                                }
                            }
                        editor.replaceList(mutableResponse, field, transformed)
                    }
                }
            return replacement
        }

        private fun expandRemovableParents(
            decodedNodes: IdentityHashMap<Any, Any>,
            referencedIds: Set<Any>,
            directRemovableIds: Set<Any>,
        ): Set<Any> {
            val removableIds = HashSet(directRemovableIds)
            var changed: Boolean
            do {
                changed = false
                for ((wrapper, node) in decodedNodes) {
                    val id = nodeIdField.get(wrapper) ?: continue
                    if (id !in referencedIds || id in removableIds) continue
                    val children = nodeChildrenField.get(node) ?: continue
                    val childIds = childIdsField.get(children) as? List<*> ?: continue
                    val continuation = childContinuationField.get(children) as? String
                    val hasContinuation =
                        childPresenceField.getInt(children) and CONTINUATION_PRESENT != 0 ||
                            !continuation.isNullOrEmpty()
                    if (!hasContinuation &&
                        childIds.isNotEmpty() &&
                        childIds.all(removableIds::contains)
                    ) {
                        removableIds += id
                        changed = true
                    }
                }
            } while (changed)
            return removableIds
        }

        private fun transformNodes(
            wrappers: List<Any>,
            decodedNodes: IdentityHashMap<Any, Any>,
            removableIds: Set<Any>,
        ): List<Any> {
            val result = ArrayList<Any>(wrappers.size)
            for (wrapper in wrappers) {
                val id = nodeIdField.get(wrapper)
                if (id != null && id in removableIds) continue
                val decoded = decodedNodes[wrapper] ?: decodeNode(wrapper)
                if (decoded == null) {
                    result += wrapper
                    continue
                }
                val children = nodeChildrenField.get(decoded)
                val filteredChildren = filterChildren(children, removableIds)
                if (filteredChildren == null) {
                    result += wrapper
                    continue
                }
                val rebuiltNode =
                    editor.copy(decoded) { mutableNode ->
                        nodeChildrenField.set(mutableNode, filteredChildren)
                    }
                result +=
                    editor.copy(wrapper) { mutableWrapper ->
                        nodeWrapperKindField.setInt(mutableWrapper, DIRECT_PAYLOAD)
                        nodeWrapperPayloadField.set(mutableWrapper, rebuiltNode)
                    }
            }
            return result
        }

        private fun transformRoots(
            wrappers: List<Any>,
            decodedRoots: IdentityHashMap<Any, Any>,
            removableIds: Set<Any>,
        ): List<Any> =
            wrappers.map { wrapper ->
                val decoded = decodedRoots[wrapper] ?: decodeRoot(wrapper) ?: return@map wrapper
                val children = rootChildrenField.get(decoded)
                val filteredChildren = filterChildren(children, removableIds) ?: return@map wrapper
                val rebuiltRoot =
                    editor.copy(decoded) { mutableRoot ->
                        rootChildrenField.set(mutableRoot, filteredChildren)
                    }
                editor.copy(wrapper) { mutableWrapper ->
                    rootWrapperKindField.setInt(mutableWrapper, DIRECT_PAYLOAD)
                    rootWrapperPayloadField.set(mutableWrapper, rebuiltRoot)
                }
            }

        private fun filterChildren(
            children: Any?,
            removableIds: Set<Any>,
        ): Any? {
            if (children == null) return null
            val ids = childIdsField.get(children) as? List<*> ?: return null
            if (ids.none(removableIds::contains)) return null
            return editor.copy(children) { mutableChildren ->
                val retained =
                    (
                        childIdsField.get(
                            mutableChildren,
                        ) as List<*>
                    ).filterNot(removableIds::contains)
                editor.replaceList(mutableChildren, childIdsField, retained)
            }
        }

        private fun collectChildIds(
            children: Any?,
            destination: MutableSet<Any>,
        ) {
            if (children == null) return
            val ids = childIdsField.get(children) as? List<*> ?: return
            ids.filterNotNullTo(destination)
        }

        private fun decodeNode(wrapper: Any): Any? =
            decode(
                kind = nodeWrapperKindField.getInt(wrapper),
                payload = nodeWrapperPayloadField.get(wrapper),
                defaultInstance = nodeDefaultInstance,
            )

        private fun decodeRoot(wrapper: Any): Any? =
            decode(
                kind = rootWrapperKindField.getInt(wrapper),
                payload = rootWrapperPayloadField.get(wrapper),
                defaultInstance = rootDefaultInstance,
            )

        private fun decode(
            kind: Int,
            payload: Any?,
            defaultInstance: Any,
        ): Any? {
            return when (kind) {
                DIRECT_PAYLOAD -> {
                    payload?.takeIf(defaultInstance.javaClass::isInstance)
                }

                SERIALIZED_PAYLOAD -> {
                    if (payload == null) return null
                    editor.parse(defaultInstance, payload)
                }

                else -> {
                    null
                }
            }
        }
    }

    private const val DIRECT_PAYLOAD = 2
    private const val SERIALIZED_PAYLOAD = 4
    private const val CONTINUATION_PRESENT = 1
}
