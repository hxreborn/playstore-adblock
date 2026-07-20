package eu.hxreborn.gplayadblock.discovery

import java.lang.reflect.Field

data class FieldRef(
    val className: String,
    val fieldName: String,
) {
    fun resolve(classLoader: ClassLoader): Field =
        classLoader.loadClass(className).getDeclaredField(fieldName).apply {
            isAccessible = true
        }
}
