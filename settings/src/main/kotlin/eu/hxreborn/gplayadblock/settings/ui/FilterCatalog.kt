package eu.hxreborn.gplayadblock.settings.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CallToAction
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CircleNotifications
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Loyalty
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UpdateDisabled
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.ui.graphics.vector.ImageVector
import eu.hxreborn.gplayadblock.settings.R
import eu.hxreborn.gplayadblock.settings.prefs.FilterCategory
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import eu.hxreborn.gplayadblock.settings.prefs.StartTab

internal val FilterCategory.titleRes: Int
    get() =
        when (this) {
            FilterCategory.CONTENT -> R.string.category_content
            FilterCategory.ACCOUNT_MENU -> R.string.category_account_menu
            FilterCategory.MANAGE_APPS -> R.string.category_manage_apps
            FilterCategory.NAVIGATION -> R.string.category_navigation
            FilterCategory.PLAY_STORE_UPDATES -> R.string.category_play_store_updates
        }

internal val FilterCategory.icon: ImageVector
    get() =
        when (this) {
            FilterCategory.CONTENT -> Icons.Outlined.ViewAgenda
            FilterCategory.ACCOUNT_MENU -> Icons.Outlined.AccountCircle
            FilterCategory.MANAGE_APPS -> Icons.Outlined.Apps
            FilterCategory.NAVIGATION -> Icons.Outlined.CallToAction
            FilterCategory.PLAY_STORE_UPDATES -> Icons.Outlined.SystemUpdate
        }

internal val FilterSwitch.titleRes: Int
    get() =
        when (this) {
            FilterSwitch.HIDE_LIVE_OPS -> R.string.hide_live_ops
            FilterSwitch.HIDE_PRE_REGISTRATION_GAMES -> R.string.hide_pre_registration_games
            FilterSwitch.HIDE_SEARCH_PROMOTIONS -> R.string.hide_search_promotions
            FilterSwitch.SUPPRESS_NOTIFICATION_BADGE -> R.string.suppress_notification_badge
            FilterSwitch.HIDE_HELP -> R.string.hide_help
            FilterSwitch.HIDE_PERSONALIZATION -> R.string.hide_personalization
            FilterSwitch.HIDE_PLAY_POINTS_REMINDER -> R.string.hide_play_points_reminder
            FilterSwitch.HIDE_NOTIFICATIONS -> R.string.hide_notifications
            FilterSwitch.HIDE_PAYMENTS_AND_SUBSCRIPTIONS -> R.string.hide_payments_and_subscriptions
            FilterSwitch.HIDE_EXPERIMENTAL_FEATURES -> R.string.hide_experimental_features
            FilterSwitch.HIDE_LIBRARY -> R.string.hide_library
            FilterSwitch.HIDE_PLAY_PASS -> R.string.hide_play_pass
            FilterSwitch.HIDE_PLAY_PROTECT -> R.string.hide_play_protect
            FilterSwitch.HIDE_DEVICE_SYNC -> R.string.hide_device_sync
            FilterSwitch.HIDE_RATINGS_AND_REVIEWS -> R.string.hide_ratings_and_reviews
            FilterSwitch.HIDE_CONTRIBUTIONS -> R.string.hide_contributions
            FilterSwitch.HIDE_GAMES_TAB -> R.string.hide_games_tab
            FilterSwitch.HIDE_APPS_TAB -> R.string.hide_apps_tab
            FilterSwitch.HIDE_BOOKS_TAB -> R.string.hide_books_tab
            FilterSwitch.HIDE_YOU_TAB -> R.string.hide_you_tab
            FilterSwitch.MINIMAL_SEARCH_HOME -> R.string.minimal_search_home
            FilterSwitch.BLOCK_STORE_SELF_UPDATES -> R.string.block_store_self_updates
        }

internal val FilterSwitch.summaryRes: Int
    get() =
        when (this) {
            FilterSwitch.HIDE_LIVE_OPS -> {
                R.string.hide_live_ops_summary
            }

            FilterSwitch.HIDE_PRE_REGISTRATION_GAMES -> {
                R.string.hide_pre_registration_games_summary
            }

            FilterSwitch.HIDE_SEARCH_PROMOTIONS -> {
                R.string.hide_search_promotions_summary
            }

            FilterSwitch.SUPPRESS_NOTIFICATION_BADGE -> {
                R.string.suppress_notification_badge_summary
            }

            FilterSwitch.HIDE_HELP -> {
                R.string.hide_help_summary
            }

            FilterSwitch.HIDE_PERSONALIZATION -> {
                R.string.hide_personalization_summary
            }

            FilterSwitch.HIDE_PLAY_POINTS_REMINDER -> {
                R.string.hide_play_points_reminder_summary
            }

            FilterSwitch.HIDE_NOTIFICATIONS -> {
                R.string.hide_notifications_summary
            }

            FilterSwitch.HIDE_PAYMENTS_AND_SUBSCRIPTIONS -> {
                R.string.hide_payments_and_subscriptions_summary
            }

            FilterSwitch.HIDE_EXPERIMENTAL_FEATURES -> {
                R.string.hide_experimental_features_summary
            }

            FilterSwitch.HIDE_LIBRARY -> {
                R.string.hide_library_summary
            }

            FilterSwitch.HIDE_PLAY_PASS -> {
                R.string.hide_play_pass_summary
            }

            FilterSwitch.HIDE_PLAY_PROTECT -> {
                R.string.hide_play_protect_summary
            }

            FilterSwitch.HIDE_DEVICE_SYNC -> {
                R.string.hide_device_sync_summary
            }

            FilterSwitch.HIDE_RATINGS_AND_REVIEWS -> {
                R.string.hide_ratings_and_reviews_summary
            }

            FilterSwitch.HIDE_CONTRIBUTIONS -> {
                R.string.hide_contributions_summary
            }

            FilterSwitch.HIDE_GAMES_TAB -> {
                R.string.hide_games_tab_summary
            }

            FilterSwitch.HIDE_APPS_TAB -> {
                R.string.hide_apps_tab_summary
            }

            FilterSwitch.HIDE_BOOKS_TAB -> {
                R.string.hide_books_tab_summary
            }

            FilterSwitch.HIDE_YOU_TAB -> {
                R.string.hide_you_tab_summary
            }

            FilterSwitch.MINIMAL_SEARCH_HOME -> {
                R.string.minimal_search_home_summary
            }

            FilterSwitch.BLOCK_STORE_SELF_UPDATES -> {
                R.string.block_store_self_updates_summary
            }
        }

internal val FilterSwitch.icon: ImageVector
    get() =
        when (this) {
            FilterSwitch.HIDE_LIVE_OPS -> Icons.Outlined.Campaign
            FilterSwitch.HIDE_PRE_REGISTRATION_GAMES -> Icons.Outlined.Event
            FilterSwitch.HIDE_SEARCH_PROMOTIONS -> Icons.AutoMirrored.Outlined.ManageSearch
            FilterSwitch.SUPPRESS_NOTIFICATION_BADGE -> Icons.Outlined.CircleNotifications
            FilterSwitch.HIDE_HELP -> Icons.AutoMirrored.Outlined.Help
            FilterSwitch.HIDE_PERSONALIZATION -> Icons.Outlined.Tune
            FilterSwitch.HIDE_PLAY_POINTS_REMINDER -> Icons.Outlined.Loyalty
            FilterSwitch.HIDE_NOTIFICATIONS -> Icons.Outlined.Notifications
            FilterSwitch.HIDE_PAYMENTS_AND_SUBSCRIPTIONS -> Icons.Outlined.CreditCard
            FilterSwitch.HIDE_EXPERIMENTAL_FEATURES -> Icons.Outlined.Science
            FilterSwitch.HIDE_LIBRARY -> Icons.Outlined.Folder
            FilterSwitch.HIDE_PLAY_PASS -> Icons.Outlined.BookmarkBorder
            FilterSwitch.HIDE_PLAY_PROTECT -> Icons.Outlined.Shield
            FilterSwitch.HIDE_DEVICE_SYNC -> Icons.Outlined.Devices
            FilterSwitch.HIDE_RATINGS_AND_REVIEWS -> Icons.Outlined.RateReview
            FilterSwitch.HIDE_CONTRIBUTIONS -> Icons.Outlined.VolunteerActivism
            FilterSwitch.HIDE_GAMES_TAB -> Icons.Outlined.SportsEsports
            FilterSwitch.HIDE_APPS_TAB -> Icons.Outlined.Apps
            FilterSwitch.HIDE_BOOKS_TAB -> Icons.AutoMirrored.Outlined.MenuBook
            FilterSwitch.HIDE_YOU_TAB -> Icons.Outlined.Person
            FilterSwitch.MINIMAL_SEARCH_HOME -> Icons.Outlined.SearchOff
            FilterSwitch.BLOCK_STORE_SELF_UPDATES -> Icons.Outlined.UpdateDisabled
        }

internal val StartTab.labelRes: Int
    get() =
        when (this) {
            StartTab.UNCHANGED -> R.string.start_tab_unchanged
            StartTab.GAMES -> R.string.start_tab_games
            StartTab.APPS -> R.string.start_tab_apps
            StartTab.SEARCH -> R.string.start_tab_search
            StartTab.BOOKS -> R.string.start_tab_books
            StartTab.YOU -> R.string.start_tab_you
        }
