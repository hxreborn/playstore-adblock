package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.full.SearchScrollTargets
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class SearchRendererBinding private constructor(
    val provider: Method,
    val scroll: Method,
    val renderer: Method,
    val rendererCallers: List<Method>,
    val modelCaller: Method,
    val modelIndex: Int,
    private val presenter: Field,
    private val carrier: Field,
    private val carrierValues: List<Field>,
    private val holder: Field,
    private val model: Field,
    private val attached: Field,
    private val snapshotType: Class<*>,
) {
    fun rendered(receiver: Any): Pair<Any, Any>? {
        val presenterValue = presenter.get(receiver) ?: return null
        val carrierValue = carrier.get(presenterValue) ?: return null
        val snapshot =
            carrierValues
                .mapNotNull { it.get(carrierValue) }
                .filter { snapshotType.isInstance(it) }
                .singleOrNull() ?: return null
        val holderValue = holder.get(receiver) ?: return null
        val modelValue = model.get(holderValue) ?: return null
        return snapshot to modelValue
    }

    fun isAttached(state: Any): Boolean = attached.get(state) != null

    companion object {
        fun resolve(
            loader: ClassLoader,
            target: SearchScrollTargets,
            snapshotType: Class<*>,
        ): SearchRendererBinding {
            val provider = target.stateProvider.resolve(loader)
            val scroll = target.scrollToItem.resolve(loader)
            val renderer = requireNotNull(target.renderer).resolve(loader)
            val callers = target.providerCallers.map { it.resolve(loader) }
            val rendererCallers = target.rendererCallers.map { it.resolve(loader) }
            val carrierType = renderer.parameterTypes.first()
            val presenterMatches =
                renderer.declaringClass.declaredFields.flatMap { outer ->
                    outer.type.declaredFields
                        .filter { it.type == carrierType }
                        .map { outer to it }
                }
            val (presenter, carrier) = presenterMatches.single()
            val carrierValues =
                carrierType.declaredFields.filter {
                    !Modifier.isStatic(it.modifiers) && it.type == Any::class.java
                }
            require(carrierValues.isNotEmpty())
            val models =
                callers.flatMap { caller ->
                    caller.parameterTypes.mapIndexedNotNull { index, type ->
                        if (type.isInterface || type.isPrimitive || type.isArray) {
                            return@mapIndexedNotNull null
                        }
                        val fields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                        val interfaces = type.interfaces.toSet()
                        if (fields.count { it.type == String::class.java } != 1 ||
                            fields.count { it.type == ByteArray::class.java } != 1 ||
                            fields.none { it.type in interfaces }
                        ) {
                            return@mapIndexedNotNull null
                        }
                        Triple(caller, index, type)
                    }
                }
            val matches =
                models.flatMap { (caller, index, type) ->
                    renderer.declaringClass.declaredFields.flatMap { outer ->
                        outer.type.declaredFields
                            .filter { field ->
                                field.type.isInterface && field.type.isAssignableFrom(type) &&
                                    type.declaredFields.any { it.type == field.type }
                            }.map { field -> arrayOf(caller, index, outer, field) }
                    }
                }
            val match = matches.single()
            val attached =
                scroll.declaringClass.declaredFields
                    .filter { field ->
                        !Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers) &&
                            field.type.isInterface && field.type.methods.count { method ->
                                method.returnType == Void.TYPE && method.parameterCount == 0
                            } == 1
                    }.single()
            (
                listOf(presenter, carrier, match[2] as Field, match[3] as Field, attached) +
                    carrierValues
            ).forEach { it.isAccessible = true }
            return SearchRendererBinding(
                provider,
                scroll,
                renderer,
                rendererCallers,
                match[0] as Method,
                match[1] as Int,
                presenter,
                carrier,
                carrierValues,
                match[2] as Field,
                match[3] as Field,
                attached,
                snapshotType,
            )
        }
    }
}
