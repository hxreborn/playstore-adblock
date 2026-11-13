package eu.hxreborn.gplayadblock.full

import java.lang.reflect.Field
import java.lang.reflect.Modifier

internal class EmbeddedEditorialFields(
    val childrenByClass: Map<Class<*>, List<Field>>,
    val mediaItemsByClass: Map<Class<*>, Field>,
) {
    companion object {
        fun resolve(
            cardClass: Class<*>,
            protobufClass: Class<*>,
        ): EmbeddedEditorialFields {
            val payloadClasses = schemaPayloadClasses(cardClass, protobufClass)
            require(payloadClasses.isNotEmpty()) {
                "card schema contains no protobuf payload types"
            }
            return resolvePayloads(payloadClasses, protobufClass)
        }

        internal fun resolvePayloads(
            payloadClasses: List<Class<*>>,
            protobufClass: Class<*>,
        ): EmbeddedEditorialFields {
            val children = LinkedHashMap<Class<*>, List<Field>>()
            var classes = payloadClasses.toSet()
            repeat(2) {
                val next = mutableSetOf<Class<*>>()
                for (type in classes) {
                    val fields =
                        instanceFields(type).filter { field ->
                            field.type == Any::class.java ||
                                protobufClass.isAssignableFrom(field.type)
                        }
                    children[type] = fields
                    next += fields.map(Field::getType).filter(protobufClass::isAssignableFrom)
                    if (fields.any { it.type == Any::class.java }) {
                        next += schemaPayloadClasses(type, protobufClass)
                    }
                }
                classes = next
            }
            val lists =
                classes
                    .mapNotNull { type ->
                        instanceFields(type)
                            .filter { field ->
                                List::class.java.isAssignableFrom(field.type)
                            }.singleOrNull()
                            ?.let { type to it }
                    }.toMap()
            return EmbeddedEditorialFields(children, lists)
        }

        private fun schemaPayloadClasses(
            type: Class<*>,
            protobufClass: Class<*>,
        ): List<Class<*>> {
            val defaultField =
                type.declaredFields
                    .single { field ->
                        Modifier.isStatic(field.modifiers) && field.type == type
                    }.apply { isAccessible = true }
            val schemaMethod =
                type.declaredMethods
                    .single { method ->
                        !Modifier.isStatic(
                            method.modifiers,
                        ) && method.returnType == Any::class.java &&
                            method.parameterTypes.firstOrNull() == Integer.TYPE &&
                            method.parameterTypes.drop(1).all { it == Any::class.java }
                    }.apply { isAccessible = true }
            val arguments = arrayOfNulls<Any>(schemaMethod.parameterCount)
            arguments[0] = 3
            val schema = requireNotNull(schemaMethod.invoke(defaultField.get(null), *arguments))
            val objects =
                schema.javaClass.declaredFields
                    .single { field ->
                        !Modifier.isStatic(field.modifiers) && field.type == Array<Any>::class.java
                    }.apply { isAccessible = true }
                    .get(schema) as Array<*>
            return objects.filterIsInstance<Class<*>>().filter(protobufClass::isAssignableFrom)
        }

        private fun instanceFields(type: Class<*>): List<Field> =
            type.declaredFields
                .filterNot { field ->
                    field.isSynthetic || Modifier.isStatic(field.modifiers)
                }.onEach { it.isAccessible = true }
    }
}
