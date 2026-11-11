package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import eu.hxreborn.gplayadblock.settings.prefs.StartTab

enum class OptionalGroup(
    val label: String,
) {
    ACCOUNT_MENU("account-menu"),
    MANAGE_APPS("manage-apps"),
    PERSISTENT_NAV("persistent-nav"),
    SELF_UPDATE("self-update"),
    ;

    companion object {
        fun requiredBy(settings: FilterSettings): Set<OptionalGroup> =
            buildSet {
                settings.enabled.forEach { filter ->
                    when (filter) {
                        FilterSwitch.HIDE_LIVE_OPS,
                        FilterSwitch.HIDE_PRE_REGISTRATION_GAMES,
                        FilterSwitch.HIDE_SEARCH_PROMOTIONS,
                        -> {
                            Unit
                        }

                        FilterSwitch.SUPPRESS_NOTIFICATION_BADGE,
                        FilterSwitch.HIDE_HELP,
                        FilterSwitch.HIDE_PERSONALIZATION,
                        FilterSwitch.HIDE_PLAY_POINTS_REMINDER,
                        FilterSwitch.HIDE_NOTIFICATIONS,
                        FilterSwitch.HIDE_PAYMENTS_AND_SUBSCRIPTIONS,
                        FilterSwitch.HIDE_EXPERIMENTAL_FEATURES,
                        FilterSwitch.HIDE_LIBRARY,
                        FilterSwitch.HIDE_PLAY_PASS,
                        -> {
                            add(ACCOUNT_MENU)
                        }

                        FilterSwitch.HIDE_PLAY_PROTECT -> {
                            add(ACCOUNT_MENU)
                            add(MANAGE_APPS)
                        }

                        FilterSwitch.HIDE_DEVICE_SYNC,
                        FilterSwitch.HIDE_RATINGS_AND_REVIEWS,
                        FilterSwitch.HIDE_CONTRIBUTIONS,
                        -> {
                            add(MANAGE_APPS)
                        }

                        FilterSwitch.HIDE_GAMES_TAB,
                        FilterSwitch.HIDE_APPS_TAB,
                        FilterSwitch.HIDE_BOOKS_TAB,
                        FilterSwitch.HIDE_YOU_TAB,
                        -> {
                            add(PERSISTENT_NAV)
                        }

                        FilterSwitch.MINIMAL_SEARCH_HOME -> {
                            add(MANAGE_APPS)
                            add(PERSISTENT_NAV)
                        }

                        FilterSwitch.BLOCK_STORE_SELF_UPDATES -> {
                            add(SELF_UPDATE)
                        }
                    }
                }
                if (settings.startTab != StartTab.UNCHANGED) add(PERSISTENT_NAV)
            }
    }
}
