package eu.hxreborn.gplayadblock.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdPresentationCasesTest {
    // Excerpt of the presentation enum of Play Store 54.0.09 (case -> constant name).
    private val enum =
        mapOf(
            2 to "GENERIC_CLUSTER_PRESENTATION",
            69 to "ADS_DETAIL_FORMAT_CLUSTER_PRESENTATION",
            85 to "ADS_DETAIL_FORMAT_VERTICAL_CLUSTER_PRESENTATION",
            86 to "SEARCH_LIST_VIEW_AD_CLUSTER_PRESENTATION",
            140 to "INLINE_PROMOTION_CLUSTER_PRESENTATION",
            224 to "SPONSORED_BANNER_CLUSTER_PRESENTATION",
            229 to "LOYALTY_PROMO_CODE_CLUSTER_PRESENTATION",
            300 to "HEADER_CLUSTER_PRESENTATION",
        )

    @Test
    fun `derived cases match the recorded sponsored cases of 54_0_09`() {
        assertEquals(
            PresentationClassifier.AD_CLUSTER_CASES,
            AdPresentationCases.derive(enum::get),
        )
    }

    @Test
    fun `promotion and lookalike names are not ads`() {
        assertFalse(AdPresentationCases.isAdFormat("INLINE_PROMOTION_CLUSTER_PRESENTATION"))
        assertFalse(AdPresentationCases.isAdFormat("LOYALTY_PROMO_CODE_CLUSTER_PRESENTATION"))
        assertFalse(AdPresentationCases.isAdFormat("HEADER_CLUSTER_PRESENTATION"))
        assertFalse(AdPresentationCases.isAdFormat("ADDRESS_CLUSTER_PRESENTATION"))
        assertFalse(AdPresentationCases.isAdFormat("LOADED_CLUSTER_PRESENTATION"))
    }

    @Test
    fun `a new sponsored format is picked up by name`() {
        assertTrue(AdPresentationCases.isAdFormat("NATIVE_AD_GRID_CLUSTER_PRESENTATION"))
        assertTrue(AdPresentationCases.isAdFormat("ADS_CAROUSEL_CLUSTER_PRESENTATION"))
        assertTrue(AdPresentationCases.isAdFormat("SPONSORED_VIDEO_CLUSTER_PRESENTATION"))
    }
}
