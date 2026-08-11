package eu.hxreborn.gplayadblock.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Field

class PresentationClassifierTest {
    class Presentation(
        @JvmField val kind: Int,
        @JvmField val payload: Any?,
    )

    class Cluster(
        @JvmField val case: Int,
        @JvmField val payload: GenericPayload?,
        @JvmField val serverLogs: ByteString?,
    )

    class Card(
        @JvmField val kind: Int,
        @JvmField val payload: Any?,
    )

    class AppCard(
        @JvmField val itemAdInfo: AdMetadata?,
    )

    class AdMetadata(
        @JvmField val bitField: Int,
    )

    class ByteString(
        private val bytes: ByteArray,
    ) {
        fun toByteArray(): ByteArray = bytes
    }

    class GenericPayload(
        private val bytes: ByteArray,
    ) {
        fun toByteArray(): ByteArray = bytes
    }

    private val classifier =
        PresentationClassifier(
            presentationKindField = field<Presentation>("kind"),
            presentationPayloadField = field<Presentation>("payload"),
            clusterCaseField = field<Cluster>("case"),
            clusterPayloadField = field<Cluster>("payload"),
            clusterServerLogsField = field<Cluster>("serverLogs"),
            byteStringToByteArrayMethod =
                ByteString::class.java.getDeclaredMethod("toByteArray"),
            protobufToByteArrayMethod =
                GenericPayload::class.java.getDeclaredMethod("toByteArray"),
            cardKindField = field<Card>("kind"),
            cardPayloadField = field<Card>("payload"),
            cardAdMetadataFields =
                mapOf(
                    AppCard::class.java to listOf(field<AppCard>("itemAdInfo")),
                ),
            adPresenceField = field<AdMetadata>("bitField"),
        )

    @Test
    fun `every dedicated sponsored cluster case is an ad`() {
        for (case in listOf(69, 85, 86, 224)) {
            val presentation = cluster(case = case)
            assertEquals(case, classifier.classify(presentation))
            assertTrue("case $case", classifier.isAd(classifier.classify(presentation)))
        }
    }

    @Test
    fun `an ads slot marker in the server log makes any cluster an ad`() {
        val presentation = cluster(case = 109, serverLogs = "searchpage_ads_slot_1_mdp")
        assertEquals(PresentationClassifier.GENERIC_AD_CLUSTER, classifier.classify(presentation))
        assertTrue(classifier.isAd(classifier.classify(presentation)))
    }

    @Test
    fun `a sponsored cluster marker in the server log makes any cluster an ad`() {
        val presentation = cluster(case = 2, serverLogs = "home_sponsored_cluster_a")
        assertEquals(PresentationClassifier.GENERIC_AD_CLUSTER, classifier.classify(presentation))
    }

    @Test
    fun `a generic cluster carrying the ad disclosure control is an ad`() {
        val presentation =
            cluster(
                case = GENERIC_CLUSTER_CASE,
                payload = disclosurePayload(overflowEnum = AD_DISCLOSURE_ENUM, withLink = true),
            )
        assertEquals(
            PresentationClassifier.GENERIC_AD_DISCLOSURE_CLUSTER,
            classifier.classify(presentation),
        )
    }

    @Test
    fun `a disclosure control with another overflow enum is not an ad`() {
        val presentation =
            cluster(
                case = GENERIC_CLUSTER_CASE,
                payload = disclosurePayload(overflowEnum = 1, withLink = true),
            )
        assertFalse(classifier.isAd(classifier.classify(presentation)))
    }

    @Test
    fun `a disclosure control without its link action is not an ad`() {
        val presentation =
            cluster(
                case = GENERIC_CLUSTER_CASE,
                payload = disclosurePayload(overflowEnum = AD_DISCLOSURE_ENUM, withLink = false),
            )
        assertFalse(classifier.isAd(classifier.classify(presentation)))
    }

    @Test
    fun `a card whose itemAdInfo presence bit is set is an ad`() {
        val presentation =
            card(AppCard(AdMetadata(bitField = ITEM_AD_INFO_BIT)))
        assertEquals(PresentationClassifier.GENERIC_AD_CARD, classifier.classify(presentation))
        assertTrue(classifier.isAd(classifier.classify(presentation)))
    }

    @Test
    fun `a card carrying other presence bits is organic`() {
        val presentation = card(AppCard(AdMetadata(bitField = 1 or 4)))
        assertFalse(classifier.isAd(classifier.classify(presentation)))
    }

    @Test
    fun `a card with no ad metadata at all is organic`() {
        assertFalse(classifier.isAd(classifier.classify(card(AppCard(itemAdInfo = null)))))
    }

    @Test
    fun `case 109 on its own is organic`() {
        val presentation = cluster(case = 109)
        assertEquals(109, classifier.classify(presentation))
        assertFalse(classifier.isAd(classifier.classify(presentation)))
    }

    @Test
    fun `an ordinary cluster with unrelated server logs is organic`() {
        val presentation = cluster(case = 2, serverLogs = "homepage_editorial_shelf")
        assertFalse(classifier.isAd(classifier.classify(presentation)))
    }

    @Test
    fun `an unknown presentation kind is organic`() {
        assertFalse(classifier.isAd(classifier.classify(Presentation(kind = 7, payload = null))))
    }

    private fun cluster(
        case: Int,
        serverLogs: String? = null,
        payload: ByteArray? = null,
    ): Presentation =
        Presentation(
            kind = CLUSTER_PRESENTATION_KIND,
            payload =
                Cluster(
                    case = case,
                    payload = payload?.let(::GenericPayload),
                    serverLogs = serverLogs?.let { logs -> ByteString(logs.toByteArray()) },
                ),
        )

    private fun card(payload: Any): Presentation =
        Presentation(
            kind = CARD_PRESENTATION_KIND,
            payload = Card(kind = 1, payload = payload),
        )

    private fun disclosurePayload(
        overflowEnum: Int,
        withLink: Boolean,
    ): ByteArray {
        val overflow = varint(1, overflowEnum.toLong())
        val primaryAction =
            lengthDelimited(4, overflow) +
                if (withLink) lengthDelimited(10, varint(1, 1)) else ByteArray(0)
        return lengthDelimited(6, lengthDelimited(7, lengthDelimited(14, primaryAction)))
    }

    private fun lengthDelimited(
        fieldNumber: Int,
        value: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream()
            .apply {
                writeVarint((fieldNumber shl 3 or 2).toLong())
                writeVarint(value.size.toLong())
                write(value)
            }.toByteArray()

    private fun varint(
        fieldNumber: Int,
        value: Long,
    ): ByteArray =
        ByteArrayOutputStream()
            .apply {
                writeVarint((fieldNumber shl 3).toLong())
                writeVarint(value)
            }.toByteArray()

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var remaining = value
        while (true) {
            val byte = (remaining and 0x7f).toInt()
            remaining = remaining ushr 7
            if (remaining == 0L) {
                write(byte)
                return
            }
            write(byte or 0x80)
        }
    }

    private inline fun <reified T> field(name: String): Field =
        T::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        const val CLUSTER_PRESENTATION_KIND = 1
        const val CARD_PRESENTATION_KIND = 2
        const val GENERIC_CLUSTER_CASE = 2
        const val ITEM_AD_INFO_BIT = 2
        const val AD_DISCLOSURE_ENUM = 3
    }
}
