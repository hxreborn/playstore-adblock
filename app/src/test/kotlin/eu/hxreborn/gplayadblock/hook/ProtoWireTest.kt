package eu.hxreborn.gplayadblock.hook

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtoWireTest {
    private fun bytes(vararg values: Int) =
        ByteArray(values.size) { index -> values[index].toByte() }

    private val tabId = bytes(0x0a, 0x03, 'a'.code, 'p'.code, 'p'.code)
    private val position = bytes(0x40, 0x96, 0x01)
    private val inlineHeader = bytes(0x62, 0x02, 0x0a, 0x00)
    private val headerRequest = bytes(0x7a, 0x01, 0x00)
    private val checksum = bytes(0x2d, 1, 2, 3, 4)

    @Test
    fun `field scanning rejects a zero field number or a tag wider than 32 bits`() {
        assertNull(ProtoWire.varintField(byteArrayOf(0, 1, 0x10, 42), 2))
        val wideTag =
            byteArrayOf(0x90.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10, 42)
        assertNull(ProtoWire.varintField(wideTag + byteArrayOf(0x10, 43), 2))
    }

    @Test
    fun `nested length delimited fields follow the path and read the first occurrence`() {
        val inner = bytes(0x0a, 0x02, 'o'.code, 'k'.code)
        val message = bytes(0x08, 0x01, 0x1a, inner.size) + inner + bytes(0x1a, 0x00)

        assertArrayEquals(bytes('o'.code, 'k'.code), ProtoWire.nestedField(message, 3, 1))
        assertNull(ProtoWire.nestedField(message, 3, 2))
        assertNull(ProtoWire.nestedField(message, 4))
    }

    @Test
    fun `varint and fixed32 fields read their value only for the matching wire type`() {
        val rating = 4.5f.toRawBits()
        val message =
            bytes(0x0a, 0x00, 0x10, 0x80, 0xad, 0xe2, 0x04) +
                bytes(
                    0x0d,
                    rating and 0xff,
                    (rating ushr 8) and 0xff,
                    (rating ushr 16) and 0xff,
                    rating ushr 24,
                )

        assertEquals(10_000_000L, ProtoWire.varintField(message, 2))
        assertEquals(4.5f, Float.fromBits(requireNotNull(ProtoWire.fixed32Field(message, 1))))
        assertNull(ProtoWire.varintField(message, 1))
        assertNull(ProtoWire.fixed32Field(message, 2))
    }

    @Test
    fun `listed fields are dropped and every other field keeps its bytes and order`() {
        val message = tabId + inlineHeader + position + headerRequest + checksum

        assertArrayEquals(
            tabId + position + checksum,
            ProtoWire.withoutFields(message, setOf(12, 15)),
        )
    }

    @Test
    fun `a repeated listed field is dropped at every occurrence`() {
        val message = inlineHeader + tabId + inlineHeader

        assertArrayEquals(tabId, ProtoWire.withoutFields(message, setOf(12)))
    }

    @Test
    fun `fixed64 fields and multi byte tags keep their bytes`() {
        val timestamp = bytes(0x09, 1, 2, 3, 4, 5, 6, 7, 8)
        val highField = bytes(0x82, 0x01, 0x01, 0x00)

        assertArrayEquals(
            timestamp + highField,
            ProtoWire.withoutFields(timestamp + inlineHeader + highField, setOf(12)),
        )
    }

    @Test
    fun `an empty message reports no change`() {
        assertNull(ProtoWire.withoutFields(ByteArray(0), setOf(12)))
    }

    @Test
    fun `a truncated varint value is refused`() {
        assertNull(ProtoWire.withoutFields(inlineHeader + bytes(0x40, 0x96), setOf(12)))
    }

    @Test
    fun `a message without the listed fields reports no change`() {
        assertNull(ProtoWire.withoutFields(tabId + position, setOf(12, 15)))
    }

    @Test
    fun `a length running past the end is refused`() {
        assertNull(ProtoWire.withoutFields(inlineHeader + bytes(0x0a, 0x7f), setOf(12)))
    }

    @Test(timeout = 5_000)
    fun `a negative length pointing back at its own tag is refused`() {
        val negativeLength = bytes(0x0a, 0xf5, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01)

        assertNull(ProtoWire.withoutFields(inlineHeader + negativeLength, setOf(12)))
    }

    @Test
    fun `a group wire type is refused`() {
        assertNull(ProtoWire.withoutFields(inlineHeader + bytes(0x0b), setOf(12)))
    }

    @Test
    fun `a truncated fixed width field is refused`() {
        assertNull(ProtoWire.withoutFields(inlineHeader + bytes(0x2d, 1, 2), setOf(12)))
    }
}
