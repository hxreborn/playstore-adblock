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
        private val nodeWrapperClass: Class<*> = nodeWrapperKindField.declaringClass
        private val rootWrapperClass: Class<*> = rootWrapperKindField.declaringClass

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
                    .filter(nodeWrapperClass::isInstance)
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
            lists.values
                .asSequence()
                .flatMap(List<*>::asSequence)
                .filterNotNull()
                .filter(rootWrapperClass::isInstance)
                .forEach { wrapper ->
                    decodeRoot(wrapper)?.let { decoded -> decodedRoots[wrapper] = decoded }
                }

            val removableIds = RemovableNodes.select(adCasesById.keys, parents(decodedNodes))
            val replacement =
                editor.copy(response) { mutableResponse ->
                    for ((field, originalList) in lists) {
                        if (originalList.isEmpty()) continue
                        val transformed =
                            originalList.mapNotNull { item ->
                                when {
                                    nodeWrapperClass.isInstance(item) -> {
                                        transformNode(item!!, decodedNodes, removableIds)
                                    }

                                    rootWrapperClass.isInstance(item) -> {
                                        transformRoot(item!!, decodedRoots, removableIds)
                                    }

                                    else -> {
                                        item
                                    }
                                }
                            }
                        editor.replaceList(mutableResponse, field, transformed)
                    }
                }

            val survivors = sponsoredSurvivors(replacement, removableIds)
            if (survivors > 0) {
                Logger.error("response rewrite left $survivors sponsored nodes in place")
                return null
            }
            Logger.debug {
                "response rewrite removed=${removableIds.size} " +
                    "sponsored=${adCasesById.size} " +
                    "collapsed=${(removableIds - adCasesById.keys).size} " +
                    "nodes=${nodeWrappers.size} roots=${decodedRoots.size} " +
                    "cases=${adCasesById.values.map(classifier::caseName).groupingBy { it }
                        .eachCount()}"
            }
            return replacement
        }

        private fun sponsoredSurvivors(
            response: Any,
            removableIds: Set<Any>,
        ): Int =
            responseListFields
                .asSequence()
                .mapNotNull { field -> field.get(response) as? List<*> }
                .flatMap(List<*>::asSequence)
                .filter(nodeWrapperClass::isInstance)
                .count { wrapper -> nodeIdField.get(wrapper) in removableIds }

        private fun parents(
            decodedNodes: IdentityHashMap<Any, Any>,
        ): Map<Any, RemovableNodes.Parent<Any>> =
            buildMap {
                for ((wrapper, node) in decodedNodes) {
                    val id = nodeIdField.get(wrapper) ?: continue
                    val children = nodeChildrenField.get(node) ?: continue
                    val childIds = childIdsField.get(children) as? List<*> ?: continue
                    put(
                        id,
                        RemovableNodes.Parent(
                            childIds = childIds.filterNotNull(),
                            paginated = paginated(children),
                        ),
                    )
                }
            }

        private fun paginated(children: Any): Boolean =
            childPresenceField.getInt(children) and CONTINUATION_PRESENT != 0 ||
                !(childContinuationField.get(children) as? String).isNullOrEmpty()

        private fun transformNode(
            wrapper: Any,
            decodedNodes: IdentityHashMap<Any, Any>,
            removableIds: Set<Any>,
        ): Any? {
            if (nodeIdField.get(wrapper) in removableIds) return null
            val decoded = decodedNodes[wrapper] ?: decodeNode(wrapper) ?: return wrapper
            val filteredChildren =
                filterChildren(nodeChildrenField.get(decoded), removableIds) ?: return wrapper
            val rebuiltNode =
                editor.copy(decoded) { mutableNode ->
                    nodeChildrenField.set(mutableNode, filteredChildren)
                }
            return editor.copy(wrapper) { mutableWrapper ->
                nodeWrapperKindField.setInt(mutableWrapper, DIRECT_PAYLOAD)
                nodeWrapperPayloadField.set(mutableWrapper, rebuiltNode)
            }
        }

        private fun transformRoot(
            wrapper: Any,
            decodedRoots: IdentityHashMap<Any, Any>,
            removableIds: Set<Any>,
        ): Any {
            val decoded = decodedRoots[wrapper] ?: decodeRoot(wrapper) ?: return wrapper
            val filteredChildren =
                filterChildren(rootChildrenField.get(decoded), removableIds) ?: return wrapper
            val rebuiltRoot =
                editor.copy(decoded) { mutableRoot ->
                    rootChildrenField.set(mutableRoot, filteredChildren)
                }
            return editor.copy(wrapper) { mutableWrapper ->
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
