package eu.hxreborn.gplayadblock.hook

import org.junit.Assert.assertArrayEquals
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
