package eu.hxreborn.gplayadblock.settings.prefs

import android.content.SharedPreferences

class RecordingEditor(
    private val values: MutableMap<String, Any?>,
) : SharedPreferences.Editor {
    var commits = 0

    override fun putString(
        key: String,
        value: String?,
    ): SharedPreferences.Editor = apply { values[key] = value }

    override fun putStringSet(
        key: String,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor = apply { this.values[key] = values }

    override fun putInt(
        key: String,
        value: Int,
    ): SharedPreferences.Editor = apply { values[key] = value }

    override fun putLong(
        key: String,
        value: Long,
    ): SharedPreferences.Editor = apply { values[key] = value }

    override fun putFloat(
        key: String,
        value: Float,
    ): SharedPreferences.Editor = apply { values[key] = value }

    override fun putBoolean(
        key: String,
        value: Boolean,
    ): SharedPreferences.Editor = apply { values[key] = value }

    override fun remove(key: String): SharedPreferences.Editor = apply { values.remove(key) }

    override fun clear(): SharedPreferences.Editor = apply { values.clear() }

    override fun commit(): Boolean {
        commits++
        return true
    }

    override fun apply() {
        commits++
    }
}
