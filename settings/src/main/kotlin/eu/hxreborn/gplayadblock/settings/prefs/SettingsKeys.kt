package eu.hxreborn.gplayadblock.settings.prefs

import android.content.SharedPreferences

object SettingsKeys {
    const val GROUP = "gplay_adblock_settings"
    const val START_TAB = "start_tab"

    val allKeys: List<String> = FilterSwitch.entries.map(FilterSwitch::key) + START_TAB

    fun read(values: Map<String, *>): FilterSettings =
        FilterSettings(
            enabled =
                FilterSwitch.entries
                    .filterTo(mutableSetOf()) { filter -> readBoolean(values[filter.key]) },
            startTab = readStartTab(values[START_TAB]),
        ).let { settings ->
            if (settings.startTab in settings.hiddenTabs()) {
                settings.copy(startTab = StartTab.UNCHANGED)
            } else {
                settings
            }
        }

    fun write(
        settings: FilterSettings,
        editor: SharedPreferences.Editor,
    ): SharedPreferences.Editor {
        FilterSwitch.entries.forEach { filter ->
            editor.putBoolean(filter.key, settings.isOn(filter))
        }
        return editor.putString(START_TAB, settings.startTab.name)
    }

    fun readBoolean(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }

    fun readStartTab(value: Any?): StartTab =
        (value as? String)
            ?.trim()
            ?.uppercase()
            ?.let { name -> StartTab.entries.firstOrNull { tab -> tab.name == name } }
            ?: StartTab.UNCHANGED
}
