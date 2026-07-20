package eu.hxreborn.gplayadblock.discovery

import java.lang.reflect.Constructor

data class ConstructorRef(
    val className: String,
    val paramTypeNames: List<String>,
) {
    fun resolve(classLoader: ClassLoader): Constructor<*> {
        val declaringClass = classLoader.loadClass(className)
        val parameterTypes = paramTypeNames.map { resolveType(classLoader, it) }.toTypedArray()
        return declaringClass.getDeclaredConstructor(*parameterTypes).apply {
            isAccessible = true
        }
    }
}
