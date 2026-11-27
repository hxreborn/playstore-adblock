package eu.hxreborn.gplayadblock.settings.ui

import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import eu.hxreborn.gplayadblock.settings.prefs.MinimumRatings
import eu.hxreborn.gplayadblock.settings.prefs.StartTab

sealed interface PendingChange {
    data class Switch(
        val filter: FilterSwitch,
        val on: Boolean,
    ) : PendingChange

    data class StartScreen(
        val tab: StartTab,
    ) : PendingChange

    data class RatingMinimum(
        val minimum: MinimumRatings,
    ) : PendingChange

    data object StreamCacheClear : PendingChange
}

internal fun FilterSettings.pendingChangesSince(
    applied: FilterSettings,
    streamCacheClearPending: Boolean,
): List<PendingChange> =
    buildList {
        for (filter in FilterSwitch.entries) {
            val on = isOn(filter)
            if (on != applied.isOn(filter)) add(PendingChange.Switch(filter, on))
        }
        if (startTab != applied.startTab) add(PendingChange.StartScreen(startTab))
        if (minimumRatings != applied.minimumRatings) {
            add(PendingChange.RatingMinimum(minimumRatings))
        }
        if (streamCacheClearPending && !changesStreamContent(applied)) {
            add(PendingChange.StreamCacheClear)
        }
    }
