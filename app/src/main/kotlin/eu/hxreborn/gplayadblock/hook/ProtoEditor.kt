package eu.hxreborn.gplayadblock.hook

import java.lang.reflect.Field
import java.lang.reflect.Method

class ProtoEditor(
    private val newBuilderMethod: Method,
    private val mergeMethod: Method,
    private val buildMethod: Method,
    private val builderMessageField: Field,
    private val parseMethod: Method,
    private val registry: Any,
    private val toByteArrayMethod: Method,
    private val repeatedListCopyMethod: Method,
) {
    fun copy(
        original: Any,
        mutate: (Any) -> Unit,
    ): Any {
        val builder = requireNotNull(newBuilderMethod.invoke(original))
        mergeMethod.invoke(builder, original)
        mutate(requireNotNull(builderMessageField.get(builder)))
        return requireNotNull(buildMethod.invoke(builder))
    }

    fun parse(
        defaultInstance: Any,
        byteString: Any,
    ): Any {
        val bytes = toByteArrayMethod.invoke(byteString) as ByteArray
        return requireNotNull(
            parseMethod.invoke(null, defaultInstance, bytes, 0, bytes.size, registry),
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun replaceList(
        owner: Any,
        field: Field,
        values: List<*>,
        copyMethod: Method = repeatedListCopyMethod,
    ) {
        val original = field.get(owner) as List<*>
        val mutable =
            copyMethod.invoke(
                original,
                maxOf(original.size, values.size),
            ) as MutableList<Any?>
        mutable.clear()
        mutable.addAll(values)
        field.set(owner, mutable)
    }
}
