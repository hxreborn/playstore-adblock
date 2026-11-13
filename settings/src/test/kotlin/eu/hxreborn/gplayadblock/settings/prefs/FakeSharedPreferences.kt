package eu.hxreborn.gplayadblock.settings.prefs

import android.content.SharedPreferences

class FakeSharedPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    var readFailure: Exception? = null
    var editFailure: Exception? = null

    override fun getAll(): Map<String, *> {
        readFailure?.let { throw it }
        return LinkedHashMap(values)
    }

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = key in values

    override fun edit(): SharedPreferences.Editor {
        editFailure?.let { throw it }
        val pending = LinkedHashMap<String, Any?>()
        return RecordingEditor(pending) {
            values.putAll(pending)
            pending.keys.forEach { key ->
                listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) }
            }
            true
        }
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners -= listener
    }
}
