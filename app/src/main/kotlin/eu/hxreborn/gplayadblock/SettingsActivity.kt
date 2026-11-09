package eu.hxreborn.gplayadblock

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        val preferences = getSharedPreferences(FilterPreferences.PREFERENCES_NAME, MODE_PRIVATE)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(32))
                addView(
                    TextView(context).apply {
                        text = getString(R.string.settings_title)
                        textSize =
                            26f
                    },
                )
                addView(
                    TextView(context).apply {
                        text = getString(R.string.settings_description)
                        textSize = 15f
                        setPadding(0, dp(8), 0, dp(8))
                    },
                )

                addHeader(R.string.category_content)
                addFilter(
                    R.string.hide_live_ops,
                    R.string.hide_live_ops_summary,
                    FilterPreferences.KEY_HIDE_LIVE_OPS,
                    preferences,
                )
                addFilter(
                    R.string.hide_pre_registration_games,
                    R.string.hide_pre_registration_games_summary,
                    FilterPreferences.KEY_HIDE_PRE_REGISTRATION_GAMES,
                    preferences,
                )

                addHeader(R.string.category_account_menu)
                addFilter(
                    R.string.hide_play_points_reminder,
                    R.string.hide_play_points_reminder_summary,
                    FilterPreferences.KEY_HIDE_PLAY_POINTS_REMINDER,
                    preferences,
                )
                addFilter(
                    R.string.hide_notifications,
                    R.string.hide_notifications_summary,
                    FilterPreferences.KEY_HIDE_NOTIFICATIONS,
                    preferences,
                )
                addFilter(
                    R.string.suppress_notification_badge,
                    R.string.suppress_notification_badge_summary,
                    FilterPreferences.KEY_SUPPRESS_NOTIFICATION_BADGE,
                    preferences,
                )
                addFilter(
                    R.string.hide_play_pass,
                    R.string.hide_play_pass_summary,
                    FilterPreferences.KEY_HIDE_PLAY_PASS,
                    preferences,
                )
                addFilter(
                    R.string.hide_library,
                    R.string.hide_library_summary,
                    FilterPreferences.KEY_HIDE_LIBRARY,
                    preferences,
                )
                addFilter(
                    R.string.hide_help,
                    R.string.hide_help_summary,
                    FilterPreferences.KEY_HIDE_HELP,
                    preferences,
                )
                addFilter(
                    R.string.hide_personalization,
                    R.string.hide_personalization_summary,
                    FilterPreferences.KEY_HIDE_PERSONALIZATION,
                    preferences,
                )
                addFilter(
                    R.string.hide_experimental_features,
                    R.string.hide_experimental_features_summary,
                    FilterPreferences.KEY_HIDE_EXPERIMENTAL_FEATURES,
                    preferences,
                )
                addFilter(
                    R.string.hide_payments_and_subscriptions,
                    R.string.hide_payments_and_subscriptions_summary,
                    FilterPreferences.KEY_HIDE_PAYMENTS_AND_SUBSCRIPTIONS,
                    preferences,
                )

                addHeader(R.string.category_manage_apps)
                addFilter(
                    R.string.hide_play_protect,
                    R.string.hide_play_protect_summary,
                    FilterPreferences.KEY_HIDE_PLAY_PROTECT,
                    preferences,
                )
                addFilter(
                    R.string.hide_device_sync,
                    R.string.hide_device_sync_summary,
                    FilterPreferences.KEY_HIDE_DEVICE_SYNC,
                    preferences,
                )
                addFilter(
                    R.string.hide_ratings_and_reviews,
                    R.string.hide_ratings_and_reviews_summary,
                    FilterPreferences.KEY_HIDE_RATINGS_AND_REVIEWS,
                    preferences,
                )
                addFilter(
                    R.string.hide_contributions,
                    R.string.hide_contributions_summary,
                    FilterPreferences.KEY_HIDE_CONTRIBUTIONS,
                    preferences,
                )

                addHeader(R.string.category_bottom_navigation)
                addFilter(
                    R.string.hide_games_tab,
                    R.string.hide_tab_summary,
                    FilterPreferences.KEY_HIDE_GAMES_TAB,
                    preferences,
                )
                addFilter(
                    R.string.hide_apps_tab,
                    R.string.hide_tab_summary,
                    FilterPreferences.KEY_HIDE_APPS_TAB,
                    preferences,
                )
                addFilter(
                    R.string.hide_books_tab,
                    R.string.hide_tab_summary,
                    FilterPreferences.KEY_HIDE_BOOKS_TAB,
                    preferences,
                )
                addFilter(
                    R.string.hide_you_tab,
                    R.string.hide_tab_summary,
                    FilterPreferences.KEY_HIDE_YOU_TAB,
                    preferences,
                )
                addStartTabPreference(preferences)

                addView(
                    TextView(context).apply {
                        text = getString(R.string.restart_hint)
                        textSize = 13f
                        setPadding(0, dp(18), 0, 0)
                    },
                )
            }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun LinearLayout.addHeader(title: Int) {
        addView(
            TextView(context).apply {
                text = getString(title)
                textSize = 20f
                setPadding(0, dp(22), 0, dp(6))
            },
        )
    }

    private fun LinearLayout.addFilter(
        title: Int,
        summary: Int,
        key: String,
        preferences: SharedPreferences,
    ) = addSwitch(title, summary, key, preferences.getBoolean(key, false))

    @Suppress("DEPRECATION")
    private fun LinearLayout.addSwitch(
        title: Int,
        summary: Int,
        key: String,
        initialValue: Boolean,
    ) {
        addView(
            Switch(context).apply {
                text = getString(title)
                contentDescription = getString(title)
                isChecked = initialValue
                textSize = 17f
                setPadding(0, dp(10), 0, 0)
                setOnCheckedChangeListener { _, checked ->
                    getSharedPreferences(FilterPreferences.PREFERENCES_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(key, checked)
                        .apply()
                    GPlayAdblockApp.writeRemotePreference(key, checked)
                }
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            TextView(context).apply {
                text = getString(summary)
                textSize = 13f
                setPadding(0, dp(2), 0, dp(9))
            },
        )
    }

    private fun LinearLayout.addStartTabPreference(preferences: SharedPreferences) {
        addView(
            TextView(context).apply {
                text = getString(R.string.start_tab)
                textSize = 17f
                setPadding(0, dp(12), 0, dp(3))
            },
        )
        val values =
            arrayOf(
                FilterPreferences.START_TAB_UNCHANGED,
                FilterPreferences.START_TAB_GAMES,
                FilterPreferences.START_TAB_APPS,
                FilterPreferences.START_TAB_SEARCH,
                FilterPreferences.START_TAB_BOOKS,
                FilterPreferences.START_TAB_YOU,
            )
        val labels = resources.getStringArray(R.array.start_tab_labels)
        addView(
            Spinner(context).apply {
                adapter =
                    ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                val current =
                    preferences.getString(
                        FilterPreferences.KEY_START_TAB,
                        FilterPreferences.START_TAB_UNCHANGED,
                    )
                setSelection(values.indexOf(current).takeIf { it >= 0 } ?: 0, false)
                onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long,
                        ) {
                            val value = values[position]
                            preferences
                                .edit()
                                .putString(
                                    FilterPreferences.KEY_START_TAB,
                                    value,
                                ).apply()
                            GPlayAdblockApp.writeRemotePreference(
                                FilterPreferences.KEY_START_TAB,
                                value,
                            )
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    }
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            TextView(context).apply {
                text = getString(R.string.start_tab_summary)
                textSize = 13f
                setPadding(0, dp(2), 0, dp(9))
            },
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
