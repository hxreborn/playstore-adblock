package eu.hxreborn.gplayadblock.hook

/**
 * Pure identification rules for optional UI entries, free of Android and reflection.
 * Every rule fails open: an entry that cannot be identified with certainty is kept.
 */
internal object UiEntryMatcher {
    enum class AccountAction(
        val englishLabel: String,
    ) {
        MANAGE_APPS(PlayStoreLabels.MANAGE_APPS),
        NOTIFICATIONS(PlayStoreLabels.NOTIFICATIONS),
        PAYMENTS_AND_SUBSCRIPTIONS(PlayStoreLabels.PAYMENTS_AND_SUBSCRIPTIONS),
        PLAY_PROTECT(PlayStoreLabels.PLAY_PROTECT),
        EXPERIMENTAL_FEATURES(PlayStoreLabels.EXPERIMENTAL_FEATURES),
        LIBRARY(PlayStoreLabels.LIBRARY),
        PLAY_PASS(PlayStoreLabels.PLAY_PASS),
    }

    enum class ManageRow {
        PLAY_PROTECT,
        DEVICE_SYNC,
        RATINGS_AND_REVIEWS,
        CONTRIBUTIONS,
    }

    /** Visual element type of the "visual categories" cluster on the Search landing page. */
    const val VISUAL_CATEGORIES_ELEMENT_TYPE = 7567

    /** Returns the single action whose localized label equals [label], or null if ambiguous. */
    fun accountAction(
        label: String?,
        localized: (String) -> Set<String>,
    ): AccountAction? {
        if (label.isNullOrBlank()) return null
        return AccountAction.entries
            .filter { action -> label in localized(action.englishLabel) }
            .singleOrNull()
    }

    fun manageRow(
        className: String,
        directStrings: List<String>,
        reachableStrings: () -> List<String>,
        playProtectRowClasses: Set<String>,
        localized: (String) -> Set<String>,
    ): ManageRow? {
        fun matches(
            strings: List<String>,
            englishLabel: String,
        ): Boolean {
            val labels = localized(englishLabel)
            return labels.isNotEmpty() && strings.any(labels::contains)
        }

        return when {
            className in playProtectRowClasses -> ManageRow.PLAY_PROTECT

            matches(
                directStrings,
                PlayStoreLabels.RATINGS_AND_REVIEWS,
            ) -> ManageRow.RATINGS_AND_REVIEWS

            matches(directStrings, PlayStoreLabels.CONTRIBUTIONS) -> ManageRow.CONTRIBUTIONS

            matches(reachableStrings(), PlayStoreLabels.DEVICE_SYNC) -> ManageRow.DEVICE_SYNC

            else -> null
        }
    }

    /**
     * The Search landing list holds the visual categories cluster plus content-free placeholders
     * (pagination). [elementTypes] has one entry per item: its visual element type, or null when
     * the item carries no visual element metadata.
     */
    fun isSearchLanding(elementTypes: List<Int?>): Boolean =
        elementTypes.isNotEmpty() &&
            elementTypes.any { type -> type == VISUAL_CATEGORIES_ELEMENT_TYPE } &&
            elementTypes.all { type -> type == null || type == VISUAL_CATEGORIES_ELEMENT_TYPE }
}
