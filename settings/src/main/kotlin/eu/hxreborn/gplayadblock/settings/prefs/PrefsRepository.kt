package eu.hxreborn.gplayadblock.settings.prefs

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class PrefsRepository(
    private val desired: SharedPreferences,
    private val applied: SharedPreferences,
) {
    fun desiredSettings(): FilterSettings = SettingsKeys.read(desired.all)

    fun appliedSettings(): FilterSettings? =
        if (applied.contains(SettingsKeys.START_TAB)) SettingsKeys.read(applied.all) else null

    fun observeDesired(): Flow<FilterSettings> = desired.observe(::desiredSettings)

    fun observeApplied(): Flow<FilterSettings?> = applied.observe(::appliedSettings)

    fun save(settings: FilterSettings) {
        SettingsKeys.write(settings, desired.edit()).apply()
    }

    fun markApplied(settings: FilterSettings) {
        SettingsKeys.write(settings, applied.edit()).apply()
    }

    companion object {
        const val DESIRED_FILE = SettingsKeys.GROUP
        const val APPLIED_FILE = "gplay_adblock_applied"
    }
}

internal fun <T> SharedPreferences.observe(read: () -> T): Flow<T> =
    callbackFlow {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener {
                _,
                _,
                ->
                trySend(read())
            }
        registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
