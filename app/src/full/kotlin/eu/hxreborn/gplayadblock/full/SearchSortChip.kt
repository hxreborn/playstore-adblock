package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.hook.ProtoWire
import java.io.ByteArrayOutputStream

internal object SearchSortChip {
    private const val RELEVANCE_ID = "gplayadblock;sort_relevance_all"
    private const val RATING_ID = "gplayadblock;sort_rating_all"
    private const val DOWNLOADS_ID = "gplayadblock;sort_downloads_all"

    private val options =
        listOf(
            Option(RELEVANCE_ID, "Play Store order", SearchOrder.RELEVANCE),
            Option(RATING_ID, "Highest rated", SearchOrder.RATING),
            Option(DOWNLOADS_ID, "Most downloaded", SearchOrder.DOWNLOADS),
        )

    data class Selection(
        val order: SearchOrder?,
        val filters: List<String>,
    )

    fun selection(filters: List<String>): Selection {
        val chosen = filters.mapNotNull { id -> options.singleOrNull { it.id == id } }
        return Selection(
            order = chosen.singleOrNull()?.order,
            filters = filters.filterNot(::isSortOption),
        )
    }

    fun isSortOption(id: String?): Boolean = options.any { it.id == id }

    fun encode(selected: SearchOrder): ByteArray {
        val dropdown = ByteArrayOutputStream()
        dropdown.write(field(1, "Sort".toByteArray(Charsets.UTF_8)))
        options.drop(1).forEach { option -> dropdown.write(field(2, option.encode(selected))) }
        dropdown.write(field(3, options.first().encode(selected)))
        return field(2, dropdown.toByteArray())
    }

    fun addToPage(
        page: ByteArray,
        selected: SearchOrder,
    ): ByteArray? {
        val pageFields = fields(page) ?: return null
        val filters = pageFields.filter { it.number == 13 }
        if (filters.size > 1) return null
        val existing = filters.singleOrNull()
        val request = pageFields.singleOrNull { it.number == 14 }?.content ?: return null
        val filterFields =
            existing?.content?.let(::fields) ?: if (existing == null) emptyList() else return null
        if (filterFields.any { it.number == 1 }) return null
        val bars = filterFields.filter { it.number == 6 }
        if (bars.size > 1) return null
        val existingBar = bars.singleOrNull()
        val bar = existingBar?.content ?: if (existingBar == null) ByteArray(0) else return null
        val chips = fields(bar) ?: return null
        if (chips.any { chip ->
                chip.number == 1 && chip.content?.let { bytes ->
                    val dropdown = ProtoWire.lengthDelimitedField(bytes, 2)
                    dropdown != null &&
                        fields(dropdown).orEmpty().any { option ->
                            option.number in 2..3 && option.content?.let {
                                isSortOption(
                                    ProtoWire.nestedField(it, 1, 1)?.toString(Charsets.UTF_8),
                                )
                            } == true
                        }
                } == true
            }
        ) {
            return null
        }
        val replacement = ByteArrayOutputStream()
        if (existing == null) replacement.write(field(4, request))
        filterFields.filterNot { it.number == 6 }.forEach { replacement.write(it.bytes) }
        replacement.write(field(6, field(1, encode(selected)) + bar))
        val result = ByteArrayOutputStream()
        pageFields.filterNot { it.number == 13 }.forEach { result.write(it.bytes) }
        result.write(field(13, replacement.toByteArray()))
        return result.toByteArray()
    }

    private data class WireField(
        val number: Int,
        val bytes: ByteArray,
        val content: ByteArray?,
    )

    private fun fields(message: ByteArray): List<WireField>? {
        val result = ArrayList<WireField>()
        var index = 0
        while (index < message.size) {
            val tag = ProtoWire.readVarint(message, index) ?: return null
            val end = ProtoWire.fieldEnd(message, tag) ?: return null
            val content =
                if (ProtoWire.wireType(tag) == ProtoWire.LENGTH_DELIMITED) {
                    val length = ProtoWire.readVarint(message, tag.nextIndex) ?: return null
                    message.copyOfRange(length.nextIndex, end)
                } else {
                    null
                }
            result +=
                WireField(ProtoWire.fieldNumber(tag), message.copyOfRange(index, end), content)
            index = end
        }
        return result
    }

    private data class Option(
        val id: String,
        val label: String,
        val order: SearchOrder,
    ) {
        fun encode(selected: SearchOrder): ByteArray {
            val entry = ByteArrayOutputStream()
            entry.write(field(1, id.toByteArray(Charsets.UTF_8)))
            entry.write(field(2, label.toByteArray(Charsets.UTF_8)))
            if (order == selected) entry.write(byteArrayOf(0x18, 0x01))
            return field(1, entry.toByteArray())
        }
    }

    private fun field(
        number: Int,
        bytes: ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeVarint(output, (number shl 3 or 2).toLong())
        writeVarint(output, bytes.size.toLong())
        output.write(bytes)
        return output.toByteArray()
    }

    private fun writeVarint(
        output: ByteArrayOutputStream,
        value: Long,
    ) {
        var remaining = value
        while (remaining >= 128) {
            output.write((remaining.toInt() and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
    }
}
