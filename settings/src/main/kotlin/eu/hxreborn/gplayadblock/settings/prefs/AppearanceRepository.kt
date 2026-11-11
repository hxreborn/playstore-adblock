package eu.hxreborn.gplayadblock.settings.prefs

import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class Appearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

class AppearanceRepository(
    private val preferences: SharedPreferences,
) {
    fun appearance(): Appearance =
        Appearance(
            themeMode =
                ThemeMode.entries.firstOrNull { it.name == preferences.getString(THEME_MODE, null) }
                    ?: ThemeMode.SYSTEM,
            dynamicColor = preferences.getBoolean(DYNAMIC_COLOR, true),
        )

    fun observe(): Flow<Appearance> = preferences.observe(::appearance)

    fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString(THEME_MODE, mode.name).apply()
    }

    fun setDynamicColor(enabled: Boolean) {
        preferences.edit().putBoolean(DYNAMIC_COLOR, enabled).apply()
    }

    companion object {
        const val FILE = "gplay_adblock_appearance"
        private const val THEME_MODE = "theme_mode"
        private const val DYNAMIC_COLOR = "dynamic_color"
    }
}
