package eu.hxreborn.gplayadblock.hook

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

class PresentationClassifier(
    private val presentationKindField: Field,
    private val presentationPayloadField: Field,
    private val clusterCaseField: Field,
    private val clusterPayloadField: Field,
    private val clusterServerLogsField: Field,
    private val byteStringToByteArrayMethod: Method,
    private val protobufToByteArrayMethod: Method,
    private val cardKindField: Field,
    private val cardPayloadField: Field,
    private val cardAdMetadataFields: Map<Class<*>, List<Field>>,
    private val adPresenceField: Field,
) {
    private val cases = Collections.synchronizedMap(WeakHashMap<Any, Int>())

    fun classify(presentation: Any): Int {
        cases[presentation]?.let { return it }
        val result =
            when (presentationKindField.getInt(presentation)) {
                CLUSTER_PRESENTATION -> clusterCase(presentation)
                CARD_PRESENTATION -> cardCase(presentation)
                else -> 0
            }
        cases[presentation] = result
        return result
    }

    fun isAd(case: Int): Boolean =
        case in AD_CLUSTER_CASES ||
            case == GENERIC_AD_CARD ||
            case == GENERIC_AD_CLUSTER ||
            case == GENERIC_AD_DISCLOSURE_CLUSTER

    fun caseName(case: Int): String =
        when (case) {
            GENERIC_AD_CARD -> "card:itemAdInfo"
            GENERIC_AD_CLUSTER -> "cluster:adSlot"
            GENERIC_AD_DISCLOSURE_CLUSTER -> "cluster:adDisclosure"
            else -> case.toString()
        }

    private fun clusterCase(presentation: Any): Int {
        val payload = presentationPayloadField.get(presentation)
        if (payload == null || !clusterCaseField.declaringClass.isInstance(payload)) return 0
        val case = clusterCaseField.getInt(payload)
        return when {
            case in AD_CLUSTER_CASES -> case

            hasAdSlotMetadata(payload) -> GENERIC_AD_CLUSTER

            case == GENERIC_CLUSTER &&
                hasAdDisclosureMetadata(
                    payload,
                )
            -> GENERIC_AD_DISCLOSURE_CLUSTER

            else -> case
        }
    }

    private fun hasAdDisclosureMetadata(cluster: Any): Boolean {
        val genericPayload = clusterPayloadField.get(cluster) ?: return false
        val bytes = protobufToByteArrayMethod.invoke(genericPayload) as? ByteArray ?: return false
        val header = lengthDelimitedField(bytes, 6) ?: return false
        val controls = lengthDelimitedField(header, 7) ?: return false
        val primaryAction = lengthDelimitedField(controls, 14) ?: return false
        val overflow = lengthDelimitedField(primaryAction, 4) ?: return false
        return varintField(overflow, 1) == AD_OVERFLOW_ENUM.toLong() &&
            lengthDelimitedField(primaryAction, 10) != null
    }

    private fun hasAdSlotMetadata(cluster: Any): Boolean {
        val serverLogs = clusterServerLogsField.get(cluster) ?: return false
        val bytes = byteStringToByteArrayMethod.invoke(serverLogs) as? ByteArray ?: return false
        return bytes.containsSubsequence(AD_SLOT_MARKER) ||
            bytes.containsSubsequence(SPONSORED_CLUSTER_MARKER)
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    private fun lengthDelimitedField(
        message: ByteArray,
        target: Int,
    ): ByteArray? {
        var index = 0
        while (index < message.size) {
            val tag = readVarint(message, index) ?: return null
            index = tag.nextIndex
            val fieldNumber = (tag.value ushr 3).toInt()
            when ((tag.value and 7).toInt()) {
                0 -> {
                    index = readVarint(message, index)?.nextIndex ?: return null
                }

                1 -> {
                    index += 8
                }

                2 -> {
                    val length = readVarint(message, index) ?: return null
                    index = length.nextIndex
                    if (length.value > Int.MAX_VALUE) return null
                    val end = index + length.value.toInt()
                    if (end < index || end > message.size) return null
                    if (fieldNumber == target) return message.copyOfRange(index, end)
                    index = end
                }

                5 -> {
                    index += 4
                }

                else -> {
                    return null
                }
            }
            if (index > message.size) return null
        }
        return null
    }

    private fun varintField(
        message: ByteArray,
        target: Int,
    ): Long? {
        var index = 0
        while (index < message.size) {
            val tag = readVarint(message, index) ?: return null
            index = tag.nextIndex
            val fieldNumber = (tag.value ushr 3).toInt()
            when ((tag.value and 7).toInt()) {
                0 -> {
                    val value = readVarint(message, index) ?: return null
                    if (fieldNumber == target) return value.value
                    index = value.nextIndex
                }

                1 -> {
                    index += 8
                }

                2 -> {
                    val length = readVarint(message, index) ?: return null
                    index = length.nextIndex
                    if (length.value > Int.MAX_VALUE) return null
                    val end = index + length.value.toInt()
                    if (end < index || end > message.size) return null
                    index = end
                }

                5 -> {
                    index += 4
                }

                else -> {
                    return null
                }
            }
            if (index > message.size) return null
        }
        return null
    }

    private fun readVarint(
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

    private fun cardCase(presentation: Any): Int {
        val card = presentationPayloadField.get(presentation)
        if (card == null || !cardKindField.declaringClass.isInstance(card)) return 0
        val payload = cardPayloadField.get(card) ?: return 0
        val metadataFields = cardAdMetadataFields[payload.javaClass] ?: return 0
        return if (metadataFields.any { field ->
                val metadata = field.get(payload)
                metadata != null &&
                    adPresenceField.declaringClass.isInstance(metadata) &&
                    adPresenceField.getInt(metadata) and AD_INFO_PRESENT != 0
            }
        ) {
            GENERIC_AD_CARD
        } else {
            0
        }
    }

    private companion object {
        val AD_CLUSTER_CASES = setOf(69, 85, 86, 224)
        const val CLUSTER_PRESENTATION = 1
        const val CARD_PRESENTATION = 2
        const val GENERIC_CLUSTER = 2
        const val AD_INFO_PRESENT = 2
        const val GENERIC_AD_CARD = -1
        const val GENERIC_AD_CLUSTER = -2
        const val GENERIC_AD_DISCLOSURE_CLUSTER = -3
        const val AD_OVERFLOW_ENUM = 3
        val AD_SLOT_MARKER = "ads_slot_".toByteArray(Charsets.US_ASCII)
        val SPONSORED_CLUSTER_MARKER = "sponsored_cluster".toByteArray(Charsets.US_ASCII)
    }

    private data class Varint(
        val value: Long,
        val nextIndex: Int,
    )
}
