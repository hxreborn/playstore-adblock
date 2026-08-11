package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.HookGroup
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap

object StreamCacheFilter {
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: ResolvedTargets.Resolved,
    ) {
        val positions =
            requireNotNull(HookGroup.cacheArguments(targets)) {
                "cache assembly arguments not derivable from " +
                    "${targets.cacheAssemblyMethod.paramTypeNames}"
            }
        val method = targets.cacheAssemblyMethod.resolve(classLoader)
        val nodeClass = classLoader.loadClass(targets.presentationAccessor.className)
        val classifier = PresentationClassifier.from(classLoader, targets)
        val editor = ProtoEditor.from(classLoader, targets)
        val interceptor =
            CacheAssemblyInterceptor(
                positions = positions,
                transformer =
                    CacheGraphTransformer(
                        nodeClass = nodeClass,
                        presentationAccessor = targets.presentationAccessor.resolve(classLoader),
                        nodeChildrenField = targets.cacheNodeChildrenField.resolve(classLoader),
                        pageBoundariesField =
                            targets.cachePageBoundariesField.resolve(classLoader),
                        pageBoundariesCopyMethod =
                            targets.cachePageBoundariesCopyMethod.resolve(classLoader),
                        childIdsField = targets.childIdsField.resolve(classLoader),
                        childPresenceField = targets.childPresenceField.resolve(classLoader),
                        childContinuationField =
                            targets.childContinuationField.resolve(classLoader),
                        childKeyMethod = targets.childKeyMethod.resolve(classLoader),
                        classifier = classifier,
                        editor = editor,
                    ),
            )
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> interceptor.intercept(chain) }
    }

    internal fun rewriteArguments(
        original: List<Any?>,
        positions: HookGroup.Companion.CacheArguments,
        root: Any,
        rootChildren: List<*>,
        nodes: Map<*, *>,
    ): Array<Any?> {
        val arguments = original.toTypedArray()
        arguments[positions.root] = root
        arguments[positions.rootChildren] = rootChildren
        arguments[positions.nodes] = nodes
        return arguments
    }

    private class CacheAssemblyInterceptor(
        private val positions: HookGroup.Companion.CacheArguments,
        private val transformer: CacheGraphTransformer,
    ) {
        fun intercept(chain: XposedInterface.Chain): Any? {
            val replacement =
                try {
                    transformer.transform(
                        root = chain.getArg(positions.root),
                        rootChildren = chain.getArg(positions.rootChildren),
                        nodes = chain.getArg(positions.nodes),
                    )
                } catch (exception: Exception) {
                    Logger.error("stream cache filtering failed", exception)
                    null
                }
            if (replacement == null) return chain.proceed()
            return chain.proceed(
                rewriteArguments(
                    original = chain.args,
                    positions = positions,
                    root = replacement.root,
                    rootChildren = replacement.rootChildren,
                    nodes = replacement.nodes,
                ),
            )
        }
    }

    private class CacheGraphTransformer(
        private val nodeClass: Class<*>,
        private val presentationAccessor: Method,
        private val nodeChildrenField: Field,
        private val pageBoundariesField: Field,
        private val pageBoundariesCopyMethod: Method,
        private val childIdsField: Field,
        private val childPresenceField: Field,
        private val childContinuationField: Field,
        private val childKeyMethod: Method,
        private val classifier: PresentationClassifier,
        private val editor: ProtoEditor,
    ) {
        fun transform(
            root: Any?,
            rootChildren: Any?,
            nodes: Any?,
        ): Replacement? {
            if (root == null || !nodeClass.isInstance(root)) return null
            val rootList = rootChildren as? List<*> ?: return null
            val nodeMap = nodes as? Map<*, *> ?: return null
            if (nodeMap.isEmpty()) return null
            if (nodeMap.entries.any { entry ->
                    entry.key !is String || !nodeClass.isInstance(entry.value)
                }
            ) {
                return null
            }

            val keyCache = IdentityHashMap<Any, String>()
            val keyFor = { child: Any ->
                keyCache[child]
                    ?: (childKeyMethod.invoke(null, child) as String).also { key ->
                        keyCache[child] = key
                    }
            }
            val records =
                nodeMap.entries.associate { entry ->
                    val key = entry.key as String
                    val node = requireNotNull(entry.value)
                    key to record(node, keyFor)
                }
            val adCasesByKey = LinkedHashMap<String, Int>()
            for ((key, record) in records) {
                val presentation = presentationAccessor.invoke(record.node) ?: continue
                val case = classifier.classify(presentation)
                if (classifier.isAd(case)) adCasesByKey[key] = case
            }
            val rootCase = presentationAccessor.invoke(root)?.let(classifier::classify) ?: 0
            if (classifier.isAd(rootCase)) {
                Logger.warn(
                    "cache assembly root is sponsored case=${classifier.caseName(rootCase)} " +
                        "and cannot be removed by rewriting this call",
                )
            }
            if (adCasesByKey.isEmpty()) return null

            val rootRecord = record(root, keyFor)
            val removableKeys =
                RemovableNodes.select(
                    adCasesByKey.keys,
                    records.mapValues { (_, record) ->
                        RemovableNodes.Parent(record.childKeys, record.hasContinuation)
                    },
                )

            val filteredRootChildren =
                rootList.filterNot { child ->
                    child != null && keyFor(child) in removableKeys
                }
            var replacementRoot = root
            val filteredRootNode = filterNode(rootRecord, removableKeys)
            if (filteredRootNode != null) replacementRoot = filteredRootNode

            val replacementNodes = LinkedHashMap<Any?, Any?>(nodeMap)
            var changedNodes = replacementNodes.keys.removeAll(removableKeys)
            for ((key, record) in records) {
                if (key in removableKeys) continue
                val replacementNode = filterNode(record, removableKeys) ?: continue
                replacementNodes[key] = replacementNode
                changedNodes = true
            }
            if (filteredRootChildren.size == rootList.size &&
                replacementRoot === root &&
                !changedNodes
            ) {
                return null
            }

            Logger.debug {
                "cache removal removable=${removableKeys.size} " +
                    "children=${rootList.size}->${filteredRootChildren.size} " +
                    "nodes=${nodeMap.size}"
            }
            return Replacement(replacementRoot, filteredRootChildren, replacementNodes)
        }

        private fun record(
            node: Any,
            keyFor: (Any) -> String,
        ): NodeRecord {
            val children = nodeChildrenField.get(node)
            val childIds =
                (children?.let(childIdsField::get) as? List<*>)
                    .orEmpty()
                    .filterNotNull()
            val pageBoundaries =
                (pageBoundariesField.get(node) as? List<*>)
                    .orEmpty()
                    .map { value -> value as Int }
            val hasContinuation =
                children != null &&
                    (
                        childPresenceField.getInt(children) and CONTINUATION_PRESENT != 0 ||
                            !(childContinuationField.get(children) as? String).isNullOrEmpty()
                    )
            return NodeRecord(
                node = node,
                children = children,
                childIds = childIds,
                childKeys = childIds.map(keyFor),
                pageBoundaries = pageBoundaries,
                hasContinuation = hasContinuation,
            )
        }

        private fun filterNode(
            record: NodeRecord,
            removableKeys: Set<String>,
        ): Any? {
            val children = record.children ?: return null
            if (record.childKeys.none(removableKeys::contains)) return null
            val retained =
                record.childIds.filterIndexed { index, _ ->
                    record.childKeys[index] !in removableKeys
                }
            val replacementChildren =
                editor.copy(children) { mutableChildren ->
                    editor.replaceList(mutableChildren, childIdsField, retained)
                }
            val replacementBoundaries = remapBoundaries(record, removableKeys)
            return editor.copy(record.node) { mutableNode ->
                nodeChildrenField.set(mutableNode, replacementChildren)
                editor.replaceList(
                    owner = mutableNode,
                    field = pageBoundariesField,
                    values = replacementBoundaries,
                    copyMethod = pageBoundariesCopyMethod,
                )
            }
        }

        private fun remapBoundaries(
            record: NodeRecord,
            removableKeys: Set<String>,
        ): List<Int> {
            var previous = 0
            var retained = 0
            return record.pageBoundaries.map { boundary ->
                require(boundary in previous..record.childIds.size)
                for (index in previous until boundary) {
                    if (record.childKeys[index] !in removableKeys) retained++
                }
                previous = boundary
                retained
            }
        }
    }

    private data class NodeRecord(
        val node: Any,
        val children: Any?,
        val childIds: List<Any>,
        val childKeys: List<String>,
        val pageBoundaries: List<Int>,
        val hasContinuation: Boolean,
    )

    private data class Replacement(
        val root: Any,
        val rootChildren: List<*>,
        val nodes: Map<Any?, Any?>,
    )

    private const val CONTINUATION_PRESENT = 1
}
