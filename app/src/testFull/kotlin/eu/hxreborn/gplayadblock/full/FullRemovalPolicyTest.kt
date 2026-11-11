package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.hook.PresentationClassifier
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.AdMetadata
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.AppCard
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.ByteString
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.Card
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.Cluster
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.GenericPayload
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.Presentation
import eu.hxreborn.gplayadblock.hook.ProtoEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field

class FullRemovalPolicyTest {
    private val sponsored =
        PresentationClassifier(
            presentationKindField = field<Presentation>("kind"),
            presentationPayloadField = field<Presentation>("payload"),
            clusterCaseField = field<Cluster>("case"),
            clusterPayloadField = field<Cluster>("payload"),
            clusterServerLogsField = field<Cluster>("serverLogs"),
            byteStringToByteArrayMethod = ByteString::class.java.getDeclaredMethod("toByteArray"),
            protobufToByteArrayMethod = GenericPayload::class.java.getDeclaredMethod("toByteArray"),
            cardKindField = field<Card>("kind"),
            cardPayloadField = field<Card>("payload"),
            cardAdMetadataFields =
                mapOf(
                    AppCard::class.java to listOf(field<AppCard>("itemAdInfo")),
                ),
            adPresenceField = field<AdMetadata>("bitField"),
        )

    private fun policy(
        hideLiveOps: Boolean = false,
        hidePreRegistrationGames: Boolean = false,
        hideSearchPromotions: Boolean = false,
    ) = FullRemovalPolicy(
        sponsored = sponsored,
        presentationKindField = field<Presentation>("kind"),
        presentationPayloadField = field<Presentation>("payload"),
        clusterCaseField = field<Cluster>("case"),
        clusterServerLogsField = field<Cluster>("serverLogs"),
        byteStringToByteArrayMethod = ByteString::class.java.getDeclaredMethod("toByteArray"),
        cardKindField = field<Card>("kind"),
        cardPayloadField = field<Card>("payload"),
        protobufToByteArrayMethod = GenericPayload::class.java.getDeclaredMethod("toByteArray"),
        hideLiveOps = hideLiveOps,
        hidePreRegistrationGames = hidePreRegistrationGames,
        hideSearchPromotions = hideSearchPromotions,
    )

    private val everyFilterOn =
        policy(hideLiveOps = true, hidePreRegistrationGames = true, hideSearchPromotions = true)

    @Test
    fun `with every content filter off it removes what the sponsored classifier removes`() {
        val off = policy()
        val samples =
            listOf(
                cluster(69),
                cluster(109),
                cluster(2, serverLogs = "searchpage_ads_slot_1_mdp"),
                cluster(2, serverLogs = "promotion_3000000d51_pre_registration_games"),
                cluster(67, serverLogs = "search_quality_cluster_COLLAPSED_DEV_SUCCESS_CLUSTER_0"),
                card(AppCard(itemAdInfo = null), kind = LIVE_OPS_CARD_KIND),
                card(AppCard(AdMetadata(bitField = ITEM_AD_INFO_BIT)), kind = 1),
            )
        for (presentation in samples) {
            assertEquals(
                sponsored.isRemovable(sponsored.classify(presentation)),
                off.isRemovable(off.classify(presentation)),
            )
        }
    }

    @Test
    fun `an optional check that throws leaves the sponsored result in place`() {
        val reported = ArrayList<Exception>()
        val failing =
            FullRemovalPolicy(
                sponsored = sponsored,
                presentationKindField = field<Presentation>("kind"),
                presentationPayloadField = field<Presentation>("payload"),
                clusterCaseField = field<Cluster>("case"),
                clusterServerLogsField = field<Cluster>("serverLogs"),
                byteStringToByteArrayMethod = String::class.java.getDeclaredMethod("getBytes"),
                cardKindField = field<Card>("kind"),
                cardPayloadField = field<Card>("payload"),
                protobufToByteArrayMethod =
                    GenericPayload::class.java.getDeclaredMethod("toByteArray"),
                hideLiveOps = true,
                hidePreRegistrationGames = true,
                hideSearchPromotions = true,
                reportFailure = reported::add,
            )
        val sponsoredShelf = cluster(69)
        val markedShelf = cluster(2, serverLogs = "promotion_3000000d51_pre_registration_games")

        assertTrue(failing.isRemovable(failing.classify(sponsoredShelf)))
        assertFalse(failing.isRemovable(failing.classify(markedShelf)))
        assertEquals(1, reported.size)
    }

    @Test
    fun `a card without embedded LiveOps details keeps its presentation`() {
        val unusedEditor =
            ProtoEditor(
                newBuilderMethod = Any::class.java.getDeclaredMethod("toString"),
                mergeMethod = Any::class.java.getDeclaredMethod("toString"),
                buildMethod = Any::class.java.getDeclaredMethod("toString"),
                builderMessageField = field<Card>("payload"),
                parseMethod = Any::class.java.getDeclaredMethod("toString"),
                registry = Any(),
                toByteArrayMethod = Any::class.java.getDeclaredMethod("toString"),
                repeatedListCopyMethod = Any::class.java.getDeclaredMethod("toString"),
            )
        val presentation = card(GenericPayload(byteArrayOf(0x08, 0x01)), kind = 1)
        assertNull(policy().strippedPresentation(presentation, unusedEditor))
        assertNull(everyFilterOn.strippedPresentation(presentation, unusedEditor))
    }

    @Test
    fun `a sponsored editorial card stays sponsored with editorial filtering on and off`() {
        val presentation =
            card(AppCard(AdMetadata(bitField = ITEM_AD_INFO_BIT)), kind = LIVE_OPS_CARD_KIND)
        for (candidate in listOf(policy(), everyFilterOn)) {
            assertEquals(PresentationClassifier.GENERIC_AD_CARD, candidate.classify(presentation))
            assertTrue(candidate.isRemovable(candidate.classify(presentation)))
        }
    }

    @Test
    fun `an unsponsored editorial card is removed only when its filter is on`() {
        val presentation = card(AppCard(itemAdInfo = null), kind = LIVE_OPS_CARD_KIND)
        assertFalse(policy().isRemovable(policy().classify(presentation)))
        val on = policy(hideLiveOps = true)
        assertEquals(FullRemovalPolicy.EDITORIAL_PROMOTION_CARD, on.classify(presentation))
        assertTrue(on.isRemovable(on.classify(presentation)))
    }

    @Test
    fun `a pre-registration shelf is removed only when its filter is on`() {
        val presentation = cluster(2, serverLogs = "promotion_3000000d51_pre_registration_games")
        val searchOnly = policy(hideSearchPromotions = true)
        assertFalse(searchOnly.isRemovable(searchOnly.classify(presentation)))
        val on = policy(hidePreRegistrationGames = true)
        assertEquals(FullRemovalPolicy.PRE_REGISTRATION_GAMES_CLUSTER, on.classify(presentation))
    }

    @Test
    fun `a promoted search shelf is removed only when its filter is on`() {
        val presentation =
            cluster(67, serverLogs = "search_quality_cluster_COLLAPSED_DEV_SUCCESS_CLUSTER_0")
        assertFalse(policy().isRemovable(policy().classify(presentation)))
        val on = policy(hideSearchPromotions = true)
        assertEquals(FullRemovalPolicy.SEARCH_PROMOTION_CLUSTER, on.classify(presentation))
    }

    @Test
    fun `a marker inside the cluster payload does not classify the cluster`() {
        val presentation =
            cluster(2, payload = "pre_registration_games".toByteArray(Charsets.US_ASCII))
        assertFalse(everyFilterOn.isRemovable(everyFilterOn.classify(presentation)))
    }

    @Test
    fun `a sponsored cluster keeps its sponsored case whatever markers it carries`() {
        val presentation = cluster(2, serverLogs = "pre_registration_games_ads_slot_1")
        assertEquals(
            PresentationClassifier.GENERIC_AD_CLUSTER,
            everyFilterOn.classify(presentation),
        )
        assertEquals("cluster:adSlot", everyFilterOn.caseName(everyFilterOn.classify(presentation)))
    }

    private fun cluster(
        case: Int,
        serverLogs: String? = null,
        payload: ByteArray? = null,
    ): Presentation =
        Presentation(
            kind = PresentationClassifier.CLUSTER_PRESENTATION,
            payload =
                Cluster(
                    case = case,
                    payload = payload?.let(::GenericPayload),
                    serverLogs = serverLogs?.let { logs -> ByteString(logs.toByteArray()) },
                ),
        )

    private fun card(
        payload: Any,
        kind: Int,
    ): Presentation =
        Presentation(
            kind = PresentationClassifier.CARD_PRESENTATION,
            payload = Card(kind = kind, payload = payload),
        )

    private inline fun <reified T> field(name: String): Field =
        T::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        const val LIVE_OPS_CARD_KIND = 32
        const val ITEM_AD_INFO_BIT = 2
    }
}
