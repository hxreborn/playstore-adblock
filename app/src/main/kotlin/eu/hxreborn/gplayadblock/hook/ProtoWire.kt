package eu.hxreborn.gplayadblock.hook

import java.io.ByteArrayOutputStream

internal object ProtoWire {
    const val VARINT = 0
    const val FIXED64 = 1
    const val LENGTH_DELIMITED = 2
    const val FIXED32 = 5

    data class Varint(
        val value: Long,
        val nextIndex: Int,
    )

    fun readVarint(
        message: ByteArray,
        start: Int,
    ): Varint? {
        var index = start
        var value = 0L
        var shift = 0
        while (index < message.size && shift < Long.SIZE_BITS) {
            val byte = message[index++].toInt() and 0xff
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return Varint(value, index)
            shift += 7
        }
        return null
    }

    fun fieldNumber(tag: Varint): Int = (tag.value ushr 3).toInt()

    fun wireType(tag: Varint): Int = (tag.value and 7).toInt()

    fun withoutFields(
        message: ByteArray,
        fieldNumbers: Set<Int>,
    ): ByteArray? {
        var retained: ByteArrayOutputStream? = null
        var index = 0
        while (index < message.size) {
            val tag = readVarint(message, index) ?: return null
            val end = fieldEnd(message, tag) ?: return null
            if (fieldNumber(tag) in fieldNumbers) {
                if (retained == null) {
                    retained = ByteArrayOutputStream(message.size)
                    retained.write(message, 0, index)
                }
            } else {
                retained?.write(message, index, end - index)
            }
            index = end
        }
        return retained?.toByteArray()
    }

    fun fieldEnd(
        message: ByteArray,
        tag: Varint,
    ): Int? {
        val start = tag.nextIndex
        val end =
            when (wireType(tag)) {
                VARINT -> {
                    readVarint(message, start)?.nextIndex
                }

                FIXED64 -> {
                    start + 8
                }

                LENGTH_DELIMITED -> {
                    readVarint(message, start)
                        ?.takeIf { length -> length.value in 0..message.size }
                        ?.let { length -> length.nextIndex + length.value.toInt() }
                }

                FIXED32 -> {
                    start + 4
                }

                else -> {
                    null
                }
            }
        return end?.takeIf { it <= message.size }
    }
}
