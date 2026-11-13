package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.settings.prefs.StartTab

/**
 * Pure tab-selection policy for [PersistentNavFilter], kept free of Android and reflection so
 * it can be unit tested.
 *
 * Positions refer to the filtered (retained) tab list; indices refer to the original list
 * that Play Store built and that its click callback understands.
 */
internal object NavSelectionPlanner {
    data class Plan(
        /** Position, in the retained list, that the rebuilt model reports as selected. */
        val selectedPosition: Int,
        val navigateToIndex: Int?,
        /** True when the one-shot start-tab request has been honoured and must be cleared. */
        val consumesStartTab: Boolean,
        /** Original tab index to switch to after an external link, or null. */
        val externalRecoveryIndex: Int?,
        // Back on the tab that replaces a hidden home tab: leave Play Store like stock Back does.
        val leavesApp: Boolean = false,
    )

    fun plan(
        retainedTypes: List<String?>,
        retainedIndices: List<Int>,
        selected: Int,
        startTab: String,
        applyStartTab: Boolean,
        isExternalNavigation: Boolean,
        externalRecoveryRequested: Boolean,
        previousSelected: Int? = null,
    ): Plan {
        require(retainedTypes.isNotEmpty() && retainedTypes.size == retainedIndices.size)
        val currentPosition = retainedIndices.indexOf(selected)
        val startPosition =
            if (startTab == StartTab.UNCHANGED.name) {
                -1
            } else {
                retainedTypes.indexOf(startTab)
            }
        val fallbackPosition = startPosition.takeIf { it >= 0 } ?: 0
        val selectedPosition =
            when {
                applyStartTab && startPosition >= 0 -> startPosition
                currentPosition >= 0 -> currentPosition
                else -> fallbackPosition
            }
        val navigateToIndex =
            retainedIndices[selectedPosition]
                .takeIf { index -> !isExternalNavigation && index != selected }
        val externalRecoveryIndex =
            if (isExternalNavigation && externalRecoveryRequested) {
                val recoveryPosition =
                    startPosition.takeIf { it >= 0 }
                        ?: currentPosition.takeIf { it >= 0 }
                        ?: 0
                // Re-selecting the current tab can pop the stack to the tab root and close the
                // page the external link opened, so only an actual tab change is scheduled.
                retainedIndices[recoveryPosition].takeIf { index -> index != selected }
            } else {
                null
            }
        // Stock Back from any tab first selects the home tab, and Back on the home tab leaves.
        // When the home tab is hidden, the fallback tab stands in for it: Back from the fallback
        // tab selects the hidden home tab, which would only lead back to the fallback tab again.
        val leavesApp =
            currentPosition < 0 &&
                !isExternalNavigation &&
                previousSelected == retainedIndices[fallbackPosition]
        return Plan(
            selectedPosition = selectedPosition,
            navigateToIndex = navigateToIndex,
            consumesStartTab = applyStartTab && startPosition >= 0,
            externalRecoveryIndex = externalRecoveryIndex,
            leavesApp = leavesApp,
        )
    }
}
