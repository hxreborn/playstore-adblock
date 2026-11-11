package eu.hxreborn.gplayadblock.hook

/**
 * Derives the sponsored cluster presentation cases from the names of Play Store's presentation
 * enum (on 54.0.09: 69 ADS_DETAIL_FORMAT_CLUSTER, 85 ADS_DETAIL_FORMAT_VERTICAL_CLUSTER,
 * 86 SEARCH_LIST_VIEW_AD_CLUSTER, 224 SPONSORED_BANNER_CLUSTER). Promotion formats such as
 * INLINE_PROMOTION or LOYALTY_PROMO_CODE are organic and deliberately excluded.
 */
internal object AdPresentationCases {
    private val AD_NAME = Regex("(^|_)(ADS?|SPONSORED)(_|$)")

    /** Highest case probed; Play Store's enum stays well below this. */
    const val MAX_CASE = 1023

    fun isAdFormat(name: String): Boolean = AD_NAME.containsMatchIn(name)

    /** [nameOf] returns the enum constant name for a case, or null for an unknown case. */
    fun derive(nameOf: (Int) -> String?): Set<Int> =
        (0..MAX_CASE)
            .filter { case -> nameOf(case)?.let(::isAdFormat) == true }
            .toSet()
}
