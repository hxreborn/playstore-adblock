package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.hook.ProtoWire.LENGTH_DELIMITED
import eu.hxreborn.gplayadblock.hook.ProtoWire.VARINT
import eu.hxreborn.gplayadblock.hook.ProtoWire.fieldEnd
import eu.hxreborn.gplayadblock.hook.ProtoWire.fieldNumber
import eu.hxreborn.gplayadblock.hook.ProtoWire.readVarint
import eu.hxreborn.gplayadblock.hook.ProtoWire.wireType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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
    private val hideLiveOps: () -> Boolean = { false },
    private val hidePreRegistrationGames: () -> Boolean = { false },
    private val hideSearchPromotions: () -> Boolean = { false },
    private val adClusterCases: Set<Int> = AD_CLUSTER_CASES,
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
        case in adClusterCases ||
            case == GENERIC_AD_CARD ||
            case == GENERIC_AD_CLUSTER ||
            case == GENERIC_AD_DISCLOSURE_CLUSTER ||
            (case == GENERIC_SEARCH_QUALITY_PROMOTION && hideSearchPromotions()) ||
            (case == GENERIC_PROMOTIONAL_CARD && hideLiveOps()) ||
            (case == GENERIC_PRE_REGISTRATION_CLUSTER && hidePreRegistrationGames())

    fun caseName(case: Int): String =
        when (case) {
            GENERIC_AD_CARD -> "card:itemAdInfo"
            GENERIC_AD_CLUSTER -> "cluster:adSlot"
            GENERIC_AD_DISCLOSURE_CLUSTER -> "cluster:adDisclosure"
            GENERIC_PROMOTIONAL_CARD -> "card:liveOps"
            GENERIC_PRE_REGISTRATION_CLUSTER -> "cluster:preRegistrationGames"
            GENERIC_SEARCH_QUALITY_PROMOTION -> "cluster:searchQualityPromotion"
            else -> case.toString()
        }

    fun stripEmbeddedLiveOps(
        presentation: Any,
        editor: ProtoEditor,
    ): Any? {
        if (!hideLiveOps() || presentationKindField.getInt(presentation) != CARD_PRESENTATION) {
            return null
        }
        val card = presentationPayloadField.get(presentation) ?: return null
        if (!cardKindField.declaringClass.isInstance(card)) return null
        val payload = cardPayloadField.get(card) ?: return null
        if (!protobufToByteArrayMethod.declaringClass.isInstance(payload)) return null
        val bytes = protobufToByteArrayMethod.invoke(payload) as? ByteArray ?: return null
        if (
            lengthDelimitedField(bytes, EMBEDDED_LIVE_OPS_FIELD)
                ?.containsSubsequence(LIVE_OP_DETAILS_MARKER) != true
        ) {
            return null
        }
        val detailsField = protobufChildContaining(payload, LIVE_OP_DETAILS_MARKER) ?: return null
        val details = detailsField.get(payload) ?: return null
        val mediaField = protobufChildContaining(details, LIVE_OP_DETAILS_MARKER) ?: return null
        val media = mediaField.get(details) ?: return null
        val mediaItemsField =
            instanceFields(media)
                .filter { field ->
                    field.isAccessible = true
                    field.get(media) is List<*>
                }.singleOrNull()
                ?: return null
        val mediaItems = mediaItemsField.get(media) as? List<*> ?: return null
        val filteredMediaItems =
            mediaItems.filterNot { item ->
                item != null &&
                    protobufToByteArrayMethod.declaringClass.isInstance(item) &&
                    (protobufToByteArrayMethod.invoke(item) as? ByteArray)
                        ?.containsSubsequence(LIVE_OP_DETAILS_MARKER) == true
            }
        if (filteredMediaItems.size == mediaItems.size) return null
        val strippedMedia =
            editor.copy(media) { mutableMedia ->
                editor.replaceList(mutableMedia, mediaItemsField, filteredMediaItems)
            }
        val strippedDetails =
            editor.copy(details) { mutableDetails ->
                mediaField.set(mutableDetails, strippedMedia)
            }
        val strippedPayload =
            editor.copy(payload) { mutablePayload ->
                detailsField.set(mutablePayload, strippedDetails)
            }
        val strippedCard =
            editor.copy(card) { mutableCard ->
                cardPayloadField.set(mutableCard, strippedPayload)
            }
        return editor.copy(presentation) { mutablePresentation ->
            presentationPayloadField.set(mutablePresentation, strippedCard)
        }
    }

    private fun protobufChildContaining(
        owner: Any,
        marker: ByteArray,
    ): Field? =
        instanceFields(owner)
            .filter { field ->
                field.isAccessible = true
                val value = field.get(owner) ?: return@filter false
                protobufToByteArrayMethod.declaringClass.isInstance(value) &&
                    (protobufToByteArrayMethod.invoke(value) as? ByteArray)
                        ?.containsSubsequence(marker) == true
            }.singleOrNull()

    private fun instanceFields(owner: Any): List<Field> =
        owner.javaClass.declaredFields.filterNot { field ->
            field.isSynthetic || Modifier.isStatic(field.modifiers)
        }

    private fun clusterCase(presentation: Any): Int {
        val payload = presentationPayloadField.get(presentation)
        if (payload == null || !clusterCaseField.declaringClass.isInstance(payload)) return 0
        val case = clusterCaseField.getInt(payload)
        // Results are cached per presentation, so classification must not depend on user
        // preferences: sponsored checks run first, optional categories are always detected,
        // and isAd() applies the live preference values.
        return when {
            case in adClusterCases -> {
                case
            }

            hasAdSlotMetadata(payload) -> {
                GENERIC_AD_CLUSTER
            }

            case == GENERIC_CLUSTER &&
                hasAdDisclosureMetadata(
                    payload,
                )
            -> {
                GENERIC_AD_DISCLOSURE_CLUSTER
            }

            else -> {
                optionalClusterCase(payload) ?: case
            }
        }
    }

    private fun optionalClusterCase(cluster: Any): Int? {
        val serverLogBytes =
            clusterServerLogsField.get(cluster)?.let { value ->
                byteStringToByteArrayMethod.invoke(value) as? ByteArray
            }
        optionalMarkerCase(serverLogBytes)?.let { return it }

        // A serialized cluster message already contains its payload, so serialize only once.
        val serialized =
            listOfNotNull(cluster, clusterPayloadField.get(cluster))
                .firstOrNull(protobufToByteArrayMethod.declaringClass::isInstance)
                ?.let { message -> protobufToByteArrayMethod.invoke(message) as? ByteArray }
        return optionalMarkerCase(serialized)
    }

    private fun optionalMarkerCase(bytes: ByteArray?): Int? =
        when {
            bytes == null -> {
                null
            }

            bytes.containsSubsequence(SEARCH_QUALITY_PROMOTION_MARKER) -> {
                GENERIC_SEARCH_QUALITY_PROMOTION
            }

            bytes.containsSubsequence(PRE_REGISTRATION_GAMES_MARKER) -> {
                GENERIC_PRE_REGISTRATION_CLUSTER
            }

            else -> {
                null
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
            val end = fieldEnd(message, tag) ?: return null
            if (fieldNumber(tag) == target && wireType(tag) == LENGTH_DELIMITED) {
                val length = readVarint(message, tag.nextIndex) ?: return null
                return message.copyOfRange(length.nextIndex, end)
            }
            index = end
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
            val end = fieldEnd(message, tag) ?: return null
            if (fieldNumber(tag) == target && wireType(tag) == VARINT) {
                return readVarint(message, tag.nextIndex)?.value
            }
            index = end
        }
        return null
    }

    private fun cardCase(presentation: Any): Int {
        val card = presentationPayloadField.get(presentation)
        if (card == null || !cardKindField.declaringClass.isInstance(card)) return 0
        if (cardKindField.getInt(card) == LIVE_OPS_CARD) return GENERIC_PROMOTIONAL_CARD
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

    companion object {
        fun from(
            classLoader: ClassLoader,
            targets: ResolvedTargets.Resolved,
            hideLiveOps: () -> Boolean = { false },
            hidePreRegistrationGames: () -> Boolean = { false },
            hideSearchPromotions: () -> Boolean = { false },
        ): PresentationClassifier =
            PresentationClassifier(
                presentationKindField = targets.presentationKindField.resolve(classLoader),
                presentationPayloadField = targets.presentationPayloadField.resolve(classLoader),
                clusterCaseField = targets.clusterCaseField.resolve(classLoader),
                clusterPayloadField = targets.clusterPayloadField.resolve(classLoader),
                clusterServerLogsField = targets.clusterServerLogsField.resolve(classLoader),
                byteStringToByteArrayMethod =
                    targets.byteStringToByteArrayMethod.resolve(classLoader),
                protobufToByteArrayMethod = targets.protobufToByteArrayMethod.resolve(classLoader),
                cardKindField = targets.cardKindField.resolve(classLoader),
                cardPayloadField = targets.cardPayloadField.resolve(classLoader),
                cardAdMetadataFields =
                    targets.cardAdMetadataFields
                        .map { field -> field.resolve(classLoader) }
                        .groupBy(Field::getDeclaringClass),
                adPresenceField = targets.adPresenceField.resolve(classLoader),
                hideLiveOps = hideLiveOps,
                hidePreRegistrationGames = hidePreRegistrationGames,
                hideSearchPromotions = hideSearchPromotions,
                adClusterCases = adClusterCases(classLoader, targets),
            )

        /**
         * Sponsored cluster cases read from Play Store's presentation enum through its static
         * `int -> enum` mapper, so new ad formats are recognised by name. Falls back to
         * [AD_CLUSTER_CASES] when the mapper is unavailable or yields nothing.
         */
        private fun adClusterCases(
            classLoader: ClassLoader,
            targets: ResolvedTargets.Resolved,
        ): Set<Int> {
            val derived =
                runCatching {
                    val mapper = targets.presentationCaseMapper?.resolve(classLoader)
                    mapper?.let { method ->
                        AdPresentationCases.derive { case ->
                            (method.invoke(null, case) as? Enum<*>)?.name
                        }
                    }
                }.getOrNull()
            val cases = derived?.takeIf(Set<Int>::isNotEmpty) ?: AD_CLUSTER_CASES
            if (cases != AD_CLUSTER_CASES) {
                Logger.info(
                    "sponsored cluster cases ${cases.sorted()} (built-in $AD_CLUSTER_CASES)",
                )
            }
            return cases
        }

        val AD_CLUSTER_CASES = setOf(69, 85, 86, 224)
        const val CLUSTER_PRESENTATION = 1
        const val CARD_PRESENTATION = 2
        const val GENERIC_CLUSTER = 2
        const val AD_INFO_PRESENT = 2
        const val GENERIC_AD_CARD = -1
        const val GENERIC_AD_CLUSTER = -2
        const val GENERIC_AD_DISCLOSURE_CLUSTER = -3
        const val GENERIC_PROMOTIONAL_CARD = -4
        const val GENERIC_PRE_REGISTRATION_CLUSTER = -5
        const val GENERIC_SEARCH_QUALITY_PROMOTION = -6
        const val AD_OVERFLOW_ENUM = 3
        const val LIVE_OPS_CARD = 32
        const val EMBEDDED_LIVE_OPS_FIELD = 2
        val AD_SLOT_MARKER = "ads_slot_".toByteArray(Charsets.US_ASCII)
        val SPONSORED_CLUSTER_MARKER = "sponsored_cluster".toByteArray(Charsets.US_ASCII)
        val PRE_REGISTRATION_GAMES_MARKER =
            "pre_registration_games".toByteArray(Charsets.US_ASCII)
        val LIVE_OP_DETAILS_MARKER = "apps/liveOpDetails".toByteArray(Charsets.US_ASCII)
        val SEARCH_QUALITY_PROMOTION_MARKER =
            "search_quality_cluster_COLLAPSED_DEV_SUCCESS_CLUSTER".toByteArray(Charsets.US_ASCII)
    }
}
