package eu.hxreborn.gplayadblock

import android.content.SharedPreferences

class FilterPreferences(
    private val preferences: SharedPreferences,
) {
    fun hideLiveOps(): Boolean = preferences.getBoolean(KEY_HIDE_LIVE_OPS, false)

    fun hidePlayPointsReminder(): Boolean =
        preferences.getBoolean(KEY_HIDE_PLAY_POINTS_REMINDER, false)

    fun hidePlayProtect(): Boolean = preferences.getBoolean(KEY_HIDE_PLAY_PROTECT, false)

    fun hideNotifications(): Boolean = preferences.getBoolean(KEY_HIDE_NOTIFICATIONS, false)

    fun suppressNotificationBadge(): Boolean =
        preferences.getBoolean(KEY_SUPPRESS_NOTIFICATION_BADGE, false)

    fun hidePlayPass(): Boolean = preferences.getBoolean(KEY_HIDE_PLAY_PASS, false)

    fun hideLibrary(): Boolean = preferences.getBoolean(KEY_HIDE_LIBRARY, false)

    fun hideHelp(): Boolean = preferences.getBoolean(KEY_HIDE_HELP, false)

    fun hidePersonalization(): Boolean = preferences.getBoolean(KEY_HIDE_PERSONALIZATION, false)

    fun hideExperimentalFeatures(): Boolean =
        preferences.getBoolean(KEY_HIDE_EXPERIMENTAL_FEATURES, false)

    fun hidePaymentsAndSubscriptions(): Boolean =
        preferences.getBoolean(KEY_HIDE_PAYMENTS_AND_SUBSCRIPTIONS, false)

    fun hidePreRegistrationGames(): Boolean =
        preferences.getBoolean(KEY_HIDE_PRE_REGISTRATION_GAMES, false)

    fun hideSearchPromotions(): Boolean =
        preferences.getBoolean(KEY_HIDE_SEARCH_PROMOTIONS, DEFAULT_HIDE_SEARCH_PROMOTIONS)

    fun hideDeviceSync(): Boolean = preferences.getBoolean(KEY_HIDE_DEVICE_SYNC, false)

    fun hideRatingsAndReviews(): Boolean =
        preferences.getBoolean(KEY_HIDE_RATINGS_AND_REVIEWS, false)

    fun hideContributions(): Boolean = preferences.getBoolean(KEY_HIDE_CONTRIBUTIONS, false)

    fun hideGamesTab(): Boolean = preferences.getBoolean(KEY_HIDE_GAMES_TAB, false)

    fun hideAppsTab(): Boolean = preferences.getBoolean(KEY_HIDE_APPS_TAB, false)

    fun hideBooksTab(): Boolean = preferences.getBoolean(KEY_HIDE_BOOKS_TAB, false)

    fun hideYouTab(): Boolean = preferences.getBoolean(KEY_HIDE_YOU_TAB, false)

    fun minimalSearchHome(): Boolean = preferences.getBoolean(KEY_MINIMAL_SEARCH_HOME, false)

    fun startTab(): String =
        preferences.getString(KEY_START_TAB, START_TAB_UNCHANGED) ?: START_TAB_UNCHANGED

    fun blockStoreSelfUpdates(): Boolean =
        preferences.getBoolean(KEY_BLOCK_STORE_SELF_UPDATES, false)

    companion object {
        const val PREFERENCES_NAME = "gplay_adblock_settings"
        const val KEY_HIDE_LIVE_OPS = "hide_live_ops"
        const val KEY_HIDE_PLAY_POINTS_REMINDER = "hide_play_points_reminder"
        const val KEY_HIDE_PLAY_PROTECT = "hide_play_protect"
        const val KEY_HIDE_NOTIFICATIONS = "hide_notifications"
        const val KEY_SUPPRESS_NOTIFICATION_BADGE = "suppress_notification_badge"
        const val KEY_HIDE_PLAY_PASS = "hide_play_pass"
        const val KEY_HIDE_LIBRARY = "hide_library"
        const val KEY_HIDE_HELP = "hide_help"
        const val KEY_HIDE_PERSONALIZATION = "hide_personalization"
        const val KEY_HIDE_EXPERIMENTAL_FEATURES = "hide_experimental_features"
        const val KEY_HIDE_PAYMENTS_AND_SUBSCRIPTIONS = "hide_payments_and_subscriptions"
        const val KEY_HIDE_PRE_REGISTRATION_GAMES = "hide_pre_registration_games"
        const val KEY_HIDE_SEARCH_PROMOTIONS = "hide_search_promotions"

        // Promotional search shelves count as advertising, so they are hidden by default
        // like the main ad filter; the switch still lets users keep them.
        const val DEFAULT_HIDE_SEARCH_PROMOTIONS = true
        const val KEY_HIDE_DEVICE_SYNC = "hide_device_sync"
        const val KEY_HIDE_RATINGS_AND_REVIEWS = "hide_ratings_and_reviews"
        const val KEY_HIDE_CONTRIBUTIONS = "hide_contributions"
        const val KEY_HIDE_GAMES_TAB = "hide_games_tab"
        const val KEY_HIDE_APPS_TAB = "hide_apps_tab"
        const val KEY_HIDE_BOOKS_TAB = "hide_books_tab"
        const val KEY_HIDE_YOU_TAB = "hide_you_tab"
        const val KEY_MINIMAL_SEARCH_HOME = "minimal_search_home"
        const val KEY_START_TAB = "start_tab"
        const val KEY_BLOCK_STORE_SELF_UPDATES = "block_store_self_updates"

        const val START_TAB_UNCHANGED = "UNCHANGED"
        const val START_TAB_GAMES = "GAMES"
        const val START_TAB_APPS = "APPS"
        const val START_TAB_SEARCH = "SEARCH"
        const val START_TAB_BOOKS = "BOOKS"
        const val START_TAB_YOU = "YOU"
    }
}
