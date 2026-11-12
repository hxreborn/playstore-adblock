package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.settings.prefs.StartTab

internal object NavSelectionPlanner {
    data class Plan(
        val selectedPosition: Int,
        val navigateToIndex: Int?,
        val consumesStartTab: Boolean,
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
