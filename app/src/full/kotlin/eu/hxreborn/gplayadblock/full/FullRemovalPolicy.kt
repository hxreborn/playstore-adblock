package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.hook.PresentationClassifier
import eu.hxreborn.gplayadblock.hook.ProtoEditor
import eu.hxreborn.gplayadblock.hook.ProtoWire.LENGTH_DELIMITED
import eu.hxreborn.gplayadblock.hook.ProtoWire.fieldEnd
import eu.hxreborn.gplayadblock.hook.ProtoWire.fieldNumber
import eu.hxreborn.gplayadblock.hook.ProtoWire.readVarint
import eu.hxreborn.gplayadblock.hook.ProtoWire.wireType
import eu.hxreborn.gplayadblock.hook.RemovalPolicy
import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

class FullRemovalPolicy internal constructor(
    private val sponsored: PresentationClassifier,
    private val presentationKindField: Field,
    private val presentationPayloadField: Field,
    private val clusterCaseField: Field,
    private val clusterServerLogsField: Field,
    private val byteStringToByteArrayMethod: Method,
    private val cardKindField: Field,
    private val cardPayloadField: Field,
    private val protobufToByteArrayMethod: Method,
    private val hideLiveOps: Boolean,
    private val hidePreRegistrationGames: Boolean,
    private val hideSearchPromotions: Boolean,
    internal val embeddedFields: EmbeddedEditorialFields =
        EmbeddedEditorialFields(emptyMap(), emptyMap()),
    private val reportFailure: (Exception) -> Unit = { exception ->
        Logger.error("optional content classification failed", exception)
    },
) : RemovalPolicy {
    private val contentCases = Collections.synchronizedMap(WeakHashMap<Any, Int>())

    override fun classify(presentation: Any): Int {
        val sponsoredCase = sponsored.classify(presentation)
        if (sponsored.isRemovable(sponsoredCase)) return sponsoredCase
        contentCases[presentation]?.let { return it }
        val case = optionalCase(presentation) ?: sponsoredCase
        contentCases[presentation] = case
        return case
    }

    override fun isRemovable(case: Int): Boolean =
        sponsored.isRemovable(case) ||
            case == EDITORIAL_PROMOTION_CARD ||
            case == PRE_REGISTRATION_GAMES_CLUSTER ||
            case == SEARCH_PROMOTION_CLUSTER

    override fun caseName(case: Int): String =
        when (case) {
            EDITORIAL_PROMOTION_CARD -> "card:editorialPromotion"
            PRE_REGISTRATION_GAMES_CLUSTER -> "cluster:preRegistrationGames"
            SEARCH_PROMOTION_CLUSTER -> "cluster:searchPromotion"
            else -> sponsored.caseName(case)
        }

    override fun strippedPresentation(
        presentation: Any,
        editor: ProtoEditor,
    ): Any? {
        if (!hideLiveOps) return null
        return try {
            withoutEmbeddedLiveOps(presentation, editor)
        } catch (exception: ReflectiveOperationException) {
            reportFailure(exception)
            null
        } catch (exception: RuntimeException) {
            reportFailure(exception)
            null
        }
    }

    private fun withoutEmbeddedLiveOps(
        presentation: Any,
        editor: ProtoEditor,
    ): Any? {
        if (presentationKindField.getInt(presentation) !=
            PresentationClassifier.CARD_PRESENTATION
        ) {
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
        val mediaItemsField = embeddedFields.mediaItemsByClass[media.javaClass] ?: return null
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
        embeddedFields.childrenByClass[owner.javaClass]
            .orEmpty()
            .filter { field ->
                val value = field.get(owner) ?: return@filter false
                protobufToByteArrayMethod.declaringClass.isInstance(value) &&
                    (protobufToByteArrayMethod.invoke(value) as? ByteArray)
                        ?.containsSubsequence(marker) == true
            }.singleOrNull()

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

    private fun optionalCase(presentation: Any): Int? =
        try {
            contentCase(presentation)
        } catch (exception: ReflectiveOperationException) {
            reportFailure(exception)
            null
        } catch (exception: RuntimeException) {
            reportFailure(exception)
            null
        }

    private fun contentCase(presentation: Any): Int? =
        when (presentationKindField.getInt(presentation)) {
            PresentationClassifier.CARD_PRESENTATION -> {
                if (hideLiveOps) editorialPromotionCase(presentation) else null
            }

            PresentationClassifier.CLUSTER_PRESENTATION -> {
                if (hidePreRegistrationGames || hideSearchPromotions) {
                    markedClusterCase(presentation)
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }

    private fun editorialPromotionCase(presentation: Any): Int? {
        val card = presentationPayloadField.get(presentation)
        if (card == null || !cardKindField.declaringClass.isInstance(card)) return null
        return EDITORIAL_PROMOTION_CARD.takeIf { cardKindField.getInt(card) == LIVE_OPS_CARD_KIND }
    }

    private fun markedClusterCase(presentation: Any): Int? {
        val cluster = presentationPayloadField.get(presentation)
        if (cluster == null || !clusterCaseField.declaringClass.isInstance(cluster)) return null
        val serverLogs = clusterServerLogsField.get(cluster) ?: return null
        val bytes = byteStringToByteArrayMethod.invoke(serverLogs) as? ByteArray ?: return null
        return when {
            hideSearchPromotions && bytes.containsSubsequence(SEARCH_PROMOTION_MARKER) -> {
                SEARCH_PROMOTION_CLUSTER
            }

            hidePreRegistrationGames && bytes.containsSubsequence(PRE_REGISTRATION_MARKER) -> {
                PRE_REGISTRATION_GAMES_CLUSTER
            }

            else -> {
                null
            }
        }
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

    companion object {
        const val EDITORIAL_PROMOTION_CARD = -4
        const val PRE_REGISTRATION_GAMES_CLUSTER = -5
        const val SEARCH_PROMOTION_CLUSTER = -6
        const val LIVE_OPS_CARD_KIND = 32
        const val EMBEDDED_LIVE_OPS_FIELD = 2
        val LIVE_OP_DETAILS_MARKER = "apps/liveOpDetails".toByteArray(Charsets.US_ASCII)
        val PRE_REGISTRATION_MARKER = "pre_registration_games".toByteArray(Charsets.US_ASCII)
        val SEARCH_PROMOTION_MARKER =
            "search_quality_cluster_COLLAPSED_DEV_SUCCESS_CLUSTER".toByteArray(Charsets.US_ASCII)

        fun contentFiltersEnabled(settings: FilterSettings): Boolean =
            settings.isOn(FilterSwitch.HIDE_LIVE_OPS) ||
                settings.isOn(FilterSwitch.HIDE_PRE_REGISTRATION_GAMES) ||
                settings.isOn(FilterSwitch.HIDE_SEARCH_PROMOTIONS)

        fun from(
            sponsored: PresentationClassifier,
            classLoader: ClassLoader,
            targets: ResolvedTargets.Resolved,
            settings: FilterSettings,
        ): FullRemovalPolicy =
            FullRemovalPolicy(
                sponsored = sponsored,
                presentationKindField = targets.presentationKindField.resolve(classLoader),
                presentationPayloadField = targets.presentationPayloadField.resolve(classLoader),
                clusterCaseField = targets.clusterCaseField.resolve(classLoader),
                clusterServerLogsField = targets.clusterServerLogsField.resolve(classLoader),
                byteStringToByteArrayMethod =
                    targets.byteStringToByteArrayMethod.resolve(classLoader),
                cardKindField = targets.cardKindField.resolve(classLoader),
                cardPayloadField = targets.cardPayloadField.resolve(classLoader),
                protobufToByteArrayMethod = targets.protobufToByteArrayMethod.resolve(classLoader),
                embeddedFields =
                    if (settings.isOn(FilterSwitch.HIDE_LIVE_OPS)) {
                        EmbeddedEditorialFields.resolve(
                            targets.cardKindField.resolve(classLoader).declaringClass,
                            targets.protobufToByteArrayMethod.resolve(classLoader).declaringClass,
                        )
                    } else {
                        EmbeddedEditorialFields(emptyMap(), emptyMap())
                    },
                hideLiveOps = settings.isOn(FilterSwitch.HIDE_LIVE_OPS),
                hidePreRegistrationGames = settings.isOn(FilterSwitch.HIDE_PRE_REGISTRATION_GAMES),
                hideSearchPromotions = settings.isOn(FilterSwitch.HIDE_SEARCH_PROMOTIONS),
            )
    }
}
