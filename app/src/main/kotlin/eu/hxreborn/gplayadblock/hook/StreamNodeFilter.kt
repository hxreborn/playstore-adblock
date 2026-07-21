package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object StreamNodeFilter {
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: ResolvedTargets.Resolved,
    ) {
        val method = targets.streamDataMethod.resolve(classLoader)
        val streamChildrenField = targets.streamChildrenField.resolve(classLoader)
        val childHandlersField = targets.childHandlersField.resolve(classLoader)
        val childIdField = targets.childIdField.resolve(classLoader)
        val nodeField = targets.nodeField.resolve(classLoader)
        val presentationAccessor = targets.presentationAccessor.resolve(classLoader)
        val presentationKindField = targets.presentationKindField.resolve(classLoader)
        val presentationPayloadField = targets.presentationPayloadField.resolve(classLoader)
        val clusterCaseField = targets.clusterCaseField.resolve(classLoader)
        val cardKindField = targets.cardKindField.resolve(classLoader)
        val cardPayloadField = targets.cardPayloadField.resolve(classLoader)
        val cardAdMetadataFields =
            targets.cardAdMetadataFields
                .map { field -> field.resolve(classLoader) }
                .groupBy(Field::getDeclaringClass)
        val adPresenceField = targets.adPresenceField.resolve(classLoader)
        val classifier =
            PresentationClassifier(
                presentationKindField = presentationKindField,
                presentationPayloadField = presentationPayloadField,
                clusterCaseField = clusterCaseField,
                clusterPayloadField = targets.clusterPayloadField.resolve(classLoader),
                clusterServerLogsField =
                    targets.clusterServerLogsField.resolve(classLoader),
                byteStringToByteArrayMethod =
                    targets.byteStringToByteArrayMethod.resolve(classLoader),
                protobufToByteArrayMethod =
                    targets.protobufToByteArrayMethod.resolve(classLoader),
                cardKindField = cardKindField,
                cardPayloadField = cardPayloadField,
                cardAdMetadataFields = cardAdMetadataFields,
                adPresenceField = adPresenceField,
            )
        val streamDataClass = classLoader.loadClass(targets.streamDataMethod.returnTypeName)
        val streamDataConstructor =
            streamDataClass
                .getDeclaredConstructor(
                    childIdField.type,
                    presentationAccessor.returnType,
                    List::class.java,
                    Boolean::class.javaPrimitiveType,
                    Throwable::class.java,
                ).apply { isAccessible = true }
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

    private class StreamNodeInterceptor(
        private val streamChildrenField: Field,
        private val childHandlersField: Field,
        private val childIdField: Field,
        private val nodeField: Field,
        private val presentationAccessor: Method,
        private val classifier: PresentationClassifier,
        private val streamDataConstructor: Constructor<*>,
        private val hasMoreField: Field,
    ) {
        private val loggedCases = ConcurrentHashMap.newKeySet<Int>()
        private val errorLogged = AtomicBoolean()

        fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed() ?: return null
            return try {
                filter(chain, result)
            } catch (exception: Exception) {
                if (errorLogged.compareAndSet(false, true)) {
                    Logger.error("stream filtering failed", exception)
                }
                result
            }
        }

        private fun filter(
            chain: XposedInterface.Chain,
            result: Any,
        ): Any {
            val parent = chain.thisObject ?: return result
            val children = streamChildrenField.get(result) as? List<*> ?: return result
            if (children.isEmpty()) return result
            val handlers = childHandlersField.get(parent) as? Array<*> ?: return result
            if (handlers.isEmpty()) return result

            val casesById = HashMap<Any, Int>()
            for (handler in handlers) {
                if (handler == null) continue
                val case = presentationCase(handler)
                if (!classifier.isAd(case)) continue
                val childId = childIdField.get(handler) ?: continue
                casesById[childId] = case
            }
            if (casesById.isEmpty()) return result

            val filtered = ArrayList<Any?>(children.size)
            val removedCases = LinkedHashSet<Int>()
            for (child in children) {
                val case = casesById[child]
                if (case == null) {
                    filtered += child
                } else {
                    removedCases += case
                }
            }
            if (filtered.size == children.size) return result

            val parentId = childIdField.get(parent)
            val parentNode = nodeField.get(parent)
            val parentPresentation = presentationAccessor.invoke(parentNode)
            val hasMore = hasMoreField.getBoolean(result)
            val replacement =
                streamDataConstructor.newInstance(
                    parentId,
                    parentPresentation,
                    filtered,
                    hasMore,
                    chain.getArg(0),
                )
            val firstCases = removedCases.filter(loggedCases::add)
            if (firstCases.isNotEmpty()) {
                val cases = firstCases.joinToString(",", transform = classifier::caseName)
                Logger.info("removed sponsored stream nodes cases=$cases")
            }
            return replacement
        }

        private fun presentationCase(handler: Any): Int {
            val node = nodeField.get(handler) ?: return 0
            val presentation = presentationAccessor.invoke(node) ?: return 0
            return classifier.classify(presentation)
        }
    }
}
