package eu.hxreborn.gplayadblock.discovery

import java.lang.reflect.Method

data class MethodRef(
    val className: String,
    val methodName: String,
    val returnTypeName: String,
    val paramTypeNames: List<String>,
) {
    val syntheticSelfParameters: Int
        get() = if (paramTypeNames.firstOrNull() == className) 1 else 0

    fun resolve(classLoader: ClassLoader): Method {
        val declaringClass = classLoader.loadClass(className)
        val parameterTypes = paramTypeNames.map { resolveType(classLoader, it) }.toTypedArray()
        val returnType = resolveType(classLoader, returnTypeName)
        return declaringClass.declaredMethods
            .single { method ->
                method.name == methodName &&
                    method.returnType == returnType &&
                    method.parameterTypes.contentEquals(parameterTypes)
            }.apply {
                isAccessible = true
            }
    }
}
