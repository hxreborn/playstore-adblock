package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.hook.ProtoWire
import eu.hxreborn.gplayadblock.hook.sortChipArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchSortChipTest {
    @Test
    fun `local filter IDs are stripped even when the page cannot be rewritten`() {
        val page = Any()
        val original = listOf(page, listOf("native", "local"))
        val filters = listOf("native")
        val args = sortChipArguments(original, null, filters)
        assertEquals(listOf(page, filters), args?.toList())
        assertNull(sortChipArguments(original, null, null))
    }

    @Test
    fun `a previously enriched page does not get another sort chip`() {
        val page = field(14, byteArrayOf(8, 1))
        val enriched = requireNotNull(SearchSortChip.addToPage(page, SearchOrder.RATING))
        assertNull(SearchSortChip.addToPage(enriched, SearchOrder.DOWNLOADS))
    }

    @Test
    fun `selection removes local ids and preserves other filters in order`() {
        val result =
            SearchSortChip.selection(
                listOf("fbtag;new_60_days", "gplayadblock;sort_rating_all", "fbtag;premium"),
            )

        assertEquals(SearchOrder.RATING, result.order)
        assertEquals(listOf("fbtag;new_60_days", "fbtag;premium"), result.filters)
    }

    @Test
    fun `conflicting local selections are removed without choosing an order`() {
        val result =
            SearchSortChip.selection(
                listOf(
                    "gplayadblock;sort_rating_all",
                    "gplayadblock;sort_downloads_all",
                    "fbtag;premium",
                ),
            )

        assertNull(result.order)
        assertEquals(listOf("fbtag;premium"), result.filters)
    }

    @Test
    fun `dropdown contains the selected option and an unselected default`() {
        val dropdown =
            requireNotNull(
                ProtoWire.lengthDelimitedField(SearchSortChip.encode(SearchOrder.DOWNLOADS), 2),
            )
        assertEquals("Sort", String(requireNotNull(ProtoWire.lengthDelimitedField(dropdown, 1))))
        val choices =
            fields(dropdown, 2).map { option ->
                requireNotNull(ProtoWire.lengthDelimitedField(option, 1))
            }
        assertEquals(2, choices.size)
        assertEquals(
            listOf("gplayadblock;sort_rating_all" to null, "gplayadblock;sort_downloads_all" to 1L),
            choices.map { entry ->
                ProtoWire.lengthDelimitedField(entry, 1)?.toString(Charsets.UTF_8) to
                    ProtoWire.varintField(entry, 3)
            },
        )
        val default = requireNotNull(ProtoWire.nestedField(dropdown, 3, 1))
        assertEquals(
            "gplayadblock;sort_relevance_all",
            String(requireNotNull(ProtoWire.lengthDelimitedField(default, 1))),
        )
        assertNull(ProtoWire.varintField(default, 3))
    }

    @Test
    fun `a page without a filter bar gets the original search request and a chip`() {
        val request = byteArrayOf(0x12, 0x01, 0x01)
        val page = field(14, request)
        val result = requireNotNull(SearchSortChip.addToPage(page, SearchOrder.RELEVANCE))
        val filters = requireNotNull(ProtoWire.lengthDelimitedField(result, 13))
        org.junit.Assert.assertArrayEquals(request, ProtoWire.lengthDelimitedField(filters, 4))
        org.junit.Assert.assertArrayEquals(request, ProtoWire.lengthDelimitedField(result, 14))
        assertEquals(1, fields(requireNotNull(ProtoWire.lengthDelimitedField(filters, 6)), 1).size)
    }

    @Test
    fun `a page with filters retains its existing chip and request`() {
        val request = byteArrayOf(0x12, 0x01, 0x01)
        val existingChip = byteArrayOf(0x08, 0x01)
        val filters = field(4, request) + field(6, field(1, existingChip))
        val result =
            requireNotNull(
                SearchSortChip.addToPage(
                    field(13, filters) + field(14, request),
                    SearchOrder.RATING,
                ),
            )
        val updated = requireNotNull(ProtoWire.lengthDelimitedField(result, 13))
        org.junit.Assert.assertArrayEquals(request, ProtoWire.lengthDelimitedField(updated, 4))
        val chips = fields(requireNotNull(ProtoWire.lengthDelimitedField(updated, 6)), 1)
        assertEquals(2, chips.size)
        org.junit.Assert.assertArrayEquals(existingChip, chips[1])
    }

    @Test
    fun `duplicate native filter messages leave the page untouched`() {
        val page =
            field(14, byteArrayOf(8, 1)) + field(13, byteArrayOf()) + field(13, byteArrayOf())
        assertNull(SearchSortChip.addToPage(page, SearchOrder.RELEVANCE))
    }

    @Test
    fun `duplicate native filter bars leave the page untouched`() {
        val filters = field(6, byteArrayOf()) + field(6, byteArrayOf())
        val page = field(14, byteArrayOf(8, 1)) + field(13, filters)
        assertNull(SearchSortChip.addToPage(page, SearchOrder.RELEVANCE))
    }

    private fun field(
        number: Int,
        content: ByteArray,
    ): ByteArray {
        require(number < 16 && content.size < 128)
        return byteArrayOf((number * 8 + 2).toByte(), content.size.toByte()) + content
    }

    private fun fields(
        message: ByteArray,
        number: Int,
    ): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var index = 0
        while (index < message.size) {
            val tag = requireNotNull(ProtoWire.readVarint(message, index))
            val end = requireNotNull(ProtoWire.fieldEnd(message, tag))
            if (ProtoWire.fieldNumber(tag) == number &&
                ProtoWire.wireType(tag) == ProtoWire.LENGTH_DELIMITED
            ) {
                val length = requireNotNull(ProtoWire.readVarint(message, tag.nextIndex))
                result += message.copyOfRange(length.nextIndex, end)
            }
            index = end
        }
        return result
    }
}
