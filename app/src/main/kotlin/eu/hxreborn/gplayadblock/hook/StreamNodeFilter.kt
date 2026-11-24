package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object StreamNodeFilter {
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: ResolvedTargets.Resolved,
        classifier: RemovalPolicy,
    ) {
        val method = targets.streamDataMethod.resolve(classLoader)
        val streamChildrenField = targets.streamChildrenField.resolve(classLoader)
        val childHandlersField = targets.childHandlersField.resolve(classLoader)
        val childIdField = targets.childIdField.resolve(classLoader)
        val nodeField = targets.nodeField.resolve(classLoader)
        val presentationAccessor = targets.presentationAccessor.resolve(classLoader)
        val streamDataClass = classLoader.loadClass(targets.streamDataMethod.returnTypeName)
        val streamDataConstructor = targets.streamDataConstructor.resolve(classLoader)
        val hasMoreField =
            streamDataClass.declaredFields
                .single { field ->
                    !Modifier.isStatic(field.modifiers) &&
                        field.type == Boolean::class.javaPrimitiveType
                }.apply { isAccessible = true }
        val interceptor =
            StreamNodeInterceptor(
                streamChildrenField = streamChildrenField,
                childHandlersField = childHandlersField,
                childIdField = childIdField,
                nodeField = nodeField,
                presentationAccessor = presentationAccessor,
                classifier = classifier,
                streamDataConstructor = streamDataConstructor,
                hasMoreField = hasMoreField,
            )
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> interceptor.intercept(chain) }
    }

    internal class StreamNodeInterceptor(
        private val streamChildrenField: Field,
        private val childHandlersField: Field,
        private val childIdField: Field,
        private val nodeField: Field,
        private val presentationAccessor: Method,
        private val classifier: RemovalPolicy,
        private val streamDataConstructor: Constructor<*>,
        private val hasMoreField: Field,
        private val reportReorder: (Int) -> Unit = { count ->
            Logger.debug { "stream children reordered=$count" }
        },
        private val reportOrderingFailure: (Exception) -> Unit = {
            Logger.error("search result ordering failed", it)
        },
    ) {
        fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed() ?: return null
            return try {
                filter(chain.thisObject, result, chain.getArg(0))
            } catch (exception: Exception) {
                Logger.error("stream filtering failed", exception)
                result
            }
        }

        internal fun filter(
            parent: Any?,
            result: Any,
            failure: Any?,
        ): Any {
            parent ?: return result
            val children = streamChildrenField.get(result) as? List<*> ?: return result
            if (children.isEmpty()) return result
            val handlers = childHandlersField.get(parent) as? Array<*> ?: return result
            if (handlers.isEmpty()) return result

            val orderingContext = classifier.orderingContext(parent)
            val casesById = HashMap<Any, Int>()
            val nodesById = HashMap<Any, Any>()
            val handlersById = HashMap<Any, Any>()
            for (handler in handlers) {
                if (handler == null) continue
                val childId = childIdField.get(handler) ?: continue
                val case = presentationCase(handler)
                if (classifier.isRemovable(case)) casesById[childId] = case
                if (orderingContext != null) {
                    nodeField.get(handler)?.let { node ->
                        nodesById[childId] = node
                        handlersById[childId] = handler
                    }
                }
            }
            if (casesById.isEmpty() && orderingContext == null) return result

            val filtered = ArrayList<Any?>(children.size)
            for (child in children) {
                if (casesById[child] == null) filtered += child
            }
            if (filtered.size == children.size && orderingContext == null) return result

            val parentId = childIdField.get(parent)
            val parentNode = nodeField.get(parent)
            val parentPresentation = presentationAccessor.invoke(parentNode)
            val ordered =
                if (orderingContext != null && parentId != null && filtered.none { it == null }) {
                    safeOrder(reportOrderingFailure) {
                        classifier.orderedChildren(
                            orderingContext,
                            parentId,
                            parentPresentation,
                            filtered.filterNotNull(),
                            nodesById::get,
                        ) { childId -> nestedNodes(handlersById[childId]) }
                    }
                } else {
                    null
                }
            if (filtered.size == children.size && ordered == null) return result
            val hasMore = hasMoreField.getBoolean(result)
            val replacement =
                streamDataConstructor.newInstance(
                    parentId,
                    parentPresentation,
                    ordered ?: filtered,
                    hasMore,
                    failure,
                )
            if (ordered != null) reportReorder(ordered.size)
            return replacement
        }

        private fun nestedNodes(handler: Any?): List<Any?> {
            val nested = handler?.let(childHandlersField::get) as? Array<*>
            return nested.orEmpty().map { it?.let(nodeField::get) }
        }

        private fun presentationCase(handler: Any): Int {
            val node = nodeField.get(handler) ?: return 0
            val presentation = presentationAccessor.invoke(node) ?: return 0
            return classifier.classify(presentation)
        }
    }

    internal fun safeOrder(
        report: (Exception) -> Unit,
        order: () -> List<Any>?,
    ): List<Any>? =
        try {
            order()
        } catch (exception: Exception) {
            report(exception)
            null
        }
}
