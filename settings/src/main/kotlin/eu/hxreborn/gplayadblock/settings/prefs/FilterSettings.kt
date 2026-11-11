package eu.hxreborn.gplayadblock.settings.prefs

enum class FilterCategory {
    CONTENT,
    ACCOUNT_MENU,
    MANAGE_APPS,
    NAVIGATION,
    PLAY_STORE_UPDATES,
}

enum class StartTab {
    UNCHANGED,
    GAMES,
    APPS,
    SEARCH,
    BOOKS,
    YOU,
}

enum class FilterSwitch(
    val key: String,
    val category: FilterCategory,
    val hiddenTab: StartTab? = null,
) {
    HIDE_LIVE_OPS("hide_live_ops", FilterCategory.CONTENT),
    HIDE_PRE_REGISTRATION_GAMES("hide_pre_registration_games", FilterCategory.CONTENT),
    HIDE_SEARCH_PROMOTIONS("hide_search_promotions", FilterCategory.CONTENT),
    SUPPRESS_NOTIFICATION_BADGE("suppress_notification_badge", FilterCategory.ACCOUNT_MENU),
    HIDE_HELP("hide_help", FilterCategory.ACCOUNT_MENU),
    HIDE_PERSONALIZATION("hide_personalization", FilterCategory.ACCOUNT_MENU),
    HIDE_PLAY_POINTS_REMINDER("hide_play_points_reminder", FilterCategory.ACCOUNT_MENU),
    HIDE_NOTIFICATIONS("hide_notifications", FilterCategory.ACCOUNT_MENU),
    HIDE_PAYMENTS_AND_SUBSCRIPTIONS("hide_payments_and_subscriptions", FilterCategory.ACCOUNT_MENU),
    HIDE_EXPERIMENTAL_FEATURES("hide_experimental_features", FilterCategory.ACCOUNT_MENU),
    HIDE_LIBRARY("hide_library", FilterCategory.ACCOUNT_MENU),
    HIDE_PLAY_PASS("hide_play_pass", FilterCategory.ACCOUNT_MENU),
    HIDE_PLAY_PROTECT("hide_play_protect", FilterCategory.MANAGE_APPS),
    HIDE_DEVICE_SYNC("hide_device_sync", FilterCategory.MANAGE_APPS),
    HIDE_RATINGS_AND_REVIEWS("hide_ratings_and_reviews", FilterCategory.MANAGE_APPS),
    HIDE_CONTRIBUTIONS("hide_contributions", FilterCategory.MANAGE_APPS),
    HIDE_GAMES_TAB("hide_games_tab", FilterCategory.NAVIGATION, StartTab.GAMES),
    HIDE_APPS_TAB("hide_apps_tab", FilterCategory.NAVIGATION, StartTab.APPS),
    HIDE_BOOKS_TAB("hide_books_tab", FilterCategory.NAVIGATION, StartTab.BOOKS),
    HIDE_YOU_TAB("hide_you_tab", FilterCategory.NAVIGATION, StartTab.YOU),
    MINIMAL_SEARCH_HOME("minimal_search_home", FilterCategory.NAVIGATION),
    BLOCK_STORE_SELF_UPDATES("block_store_self_updates", FilterCategory.PLAY_STORE_UPDATES),
}

data class FilterSettings(
    val enabled: Set<FilterSwitch> = emptySet(),
    val startTab: StartTab = StartTab.UNCHANGED,
) {
    fun isOn(filter: FilterSwitch): Boolean = filter in enabled

    fun hiddenTabs(): Set<StartTab> = enabled.mapNotNullTo(mutableSetOf(), FilterSwitch::hiddenTab)

    fun with(
        filter: FilterSwitch,
        on: Boolean,
    ): FilterSettings {
        val switched = if (on) enabled + filter else enabled - filter
        val keptStartTab = if (on && filter.hiddenTab == startTab) StartTab.UNCHANGED else startTab
        return FilterSettings(switched, keptStartTab)
    }

    fun withStartTab(tab: StartTab): FilterSettings =
        if (tab in hiddenTabs()) this else copy(startTab = tab)

    fun differingSwitches(other: FilterSettings): Set<FilterSwitch> =
        (enabled - other.enabled) + (other.enabled - enabled)

    fun countDifferences(other: FilterSettings): Int =
        differingSwitches(other).size + if (startTab == other.startTab) 0 else 1
}
