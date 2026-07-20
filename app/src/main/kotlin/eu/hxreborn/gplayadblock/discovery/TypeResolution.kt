package eu.hxreborn.gplayadblock.discovery

internal fun resolveType(
    classLoader: ClassLoader,
    typeName: String,
): Class<*> =
    when (typeName) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "byte[]" -> ByteArray::class.java
        "char" -> Char::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        else -> classLoader.loadClass(typeName)
    }
