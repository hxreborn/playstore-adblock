package eu.hxreborn.gplayadblock

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class GPlayAdblockApp :
    Application(),
    XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        syncPreferencesToFramework()
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) xposedService = null
    }

    private fun syncPreferencesToFramework() {
        val local = getSharedPreferences(FilterPreferences.PREFERENCES_NAME, MODE_PRIVATE)
        val remote =
            xposedService?.getRemotePreferences(FilterPreferences.PREFERENCES_NAME) ?: return
        remote
            .edit()
            .putBoolean(
                FilterPreferences.KEY_HIDE_LIVE_OPS,
                local.getBoolean(FilterPreferences.KEY_HIDE_LIVE_OPS, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_PLAY_POINTS_REMINDER,
                local.getBoolean(FilterPreferences.KEY_HIDE_PLAY_POINTS_REMINDER, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_PLAY_PROTECT,
                local.getBoolean(FilterPreferences.KEY_HIDE_PLAY_PROTECT, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_NOTIFICATIONS,
                local.getBoolean(FilterPreferences.KEY_HIDE_NOTIFICATIONS, false),
            ).putBoolean(
                FilterPreferences.KEY_SUPPRESS_NOTIFICATION_BADGE,
                local.getBoolean(FilterPreferences.KEY_SUPPRESS_NOTIFICATION_BADGE, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_PLAY_PASS,
                local.getBoolean(FilterPreferences.KEY_HIDE_PLAY_PASS, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_LIBRARY,
                local.getBoolean(FilterPreferences.KEY_HIDE_LIBRARY, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_HELP,
                local.getBoolean(FilterPreferences.KEY_HIDE_HELP, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_PERSONALIZATION,
                local.getBoolean(FilterPreferences.KEY_HIDE_PERSONALIZATION, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_EXPERIMENTAL_FEATURES,
                local.getBoolean(FilterPreferences.KEY_HIDE_EXPERIMENTAL_FEATURES, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_PAYMENTS_AND_SUBSCRIPTIONS,
                local.getBoolean(FilterPreferences.KEY_HIDE_PAYMENTS_AND_SUBSCRIPTIONS, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_PRE_REGISTRATION_GAMES,
                local.getBoolean(FilterPreferences.KEY_HIDE_PRE_REGISTRATION_GAMES, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_DEVICE_SYNC,
                local.getBoolean(FilterPreferences.KEY_HIDE_DEVICE_SYNC, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_RATINGS_AND_REVIEWS,
                local.getBoolean(FilterPreferences.KEY_HIDE_RATINGS_AND_REVIEWS, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_CONTRIBUTIONS,
                local.getBoolean(FilterPreferences.KEY_HIDE_CONTRIBUTIONS, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_GAMES_TAB,
                local.getBoolean(FilterPreferences.KEY_HIDE_GAMES_TAB, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_APPS_TAB,
                local.getBoolean(FilterPreferences.KEY_HIDE_APPS_TAB, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_BOOKS_TAB,
                local.getBoolean(FilterPreferences.KEY_HIDE_BOOKS_TAB, false),
            ).putBoolean(
                FilterPreferences.KEY_HIDE_YOU_TAB,
                local.getBoolean(FilterPreferences.KEY_HIDE_YOU_TAB, false),
            ).putString(
                FilterPreferences.KEY_START_TAB,
                local.getString(
                    FilterPreferences.KEY_START_TAB,
                    FilterPreferences.START_TAB_UNCHANGED,
                ),
            ).putBoolean(
                FilterPreferences.KEY_BLOCK_STORE_SELF_UPDATES,
                local.getBoolean(FilterPreferences.KEY_BLOCK_STORE_SELF_UPDATES, false),
            ).commit()
    }

    companion object {
        @Volatile
        private var xposedService: XposedService? = null

        fun writeRemotePreference(
            key: String,
            value: Boolean,
        ) {
            xposedService
                ?.getRemotePreferences(FilterPreferences.PREFERENCES_NAME)
                ?.edit()
                ?.putBoolean(key, value)
                ?.apply()
        }

        fun writeRemotePreference(
            key: String,
            value: String,
        ) {
            xposedService
                ?.getRemotePreferences(FilterPreferences.PREFERENCES_NAME)
                ?.edit()
                ?.putString(key, value)
                ?.apply()
        }
    }
}
