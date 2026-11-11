package eu.hxreborn.gplayadblock.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.os.SystemClock
import eu.hxreborn.gplayadblock.Logger
import java.util.Locale

/**
 * Resolves Play Store UI labels by their English default text.
 *
 * Play Store ships obfuscated resource names and its resource IDs change between builds, but the
 * English default text of a label is part of the product and stays stable. The string resources of
 * the target package are scanned once per locale: every resource whose English value is one of
 * [PlayStoreLabels.ALL] contributes its current-locale translation. Comparing a displayed label
 * with those translations identifies an entry regardless of the user's language or its position.
 */
internal class LocalizedLabels private constructor(
    private val context: Context,
) {
    @Volatile
    private var cache: Pair<LocaleList, Map<String, Set<String>>>? = null

    fun localized(englishDefault: String): Set<String> = current()[englishDefault].orEmpty()

    /** Runs the resource scan ahead of time (off the main thread) so menus never wait for it. */
    fun warmUp() {
        current()
    }

    private fun current(): Map<String, Set<String>> {
        val locales = context.resources.configuration.locales
        cache?.takeIf { (cachedLocales, _) -> cachedLocales == locales }?.let { return it.second }
        synchronized(this) {
            cache
                ?.takeIf { (cachedLocales, _) ->
                    cachedLocales == locales
                }?.let { return it.second }
            val started = SystemClock.elapsedRealtime()
            val labels =
                runCatching { scan(context.resources) }
                    .onFailure { error -> Logger.warn("label scan failed", error) }
                    .getOrDefault(emptyMap())
            Logger.debug {
                val elapsed = SystemClock.elapsedRealtime() - started
                "label scan locales=$locales found=${labels.keys.size}/" +
                    "${PlayStoreLabels.ALL.size} in ${elapsed}ms"
            }
            // A missing label means Play Store changed that text: the matching filter keeps the
            // entry visible. Say which one so the change is noticed instead of failing silently.
            val missing = PlayStoreLabels.ALL - labels.keys
            if (missing.isNotEmpty()) {
                Logger.warn(
                    "optional UI labels not found, their filters keep entries visible: " +
                        missing.joinToString(", ") { "\"$it\"" },
                )
            }
            cache = locales to labels
            return labels
        }
    }

    private fun scan(resources: Resources): Map<String, Set<String>> {
        val stringType = stringTypeBase(resources) ?: return emptyMap()

        // Reads Play Store's own English resources; it does not change any app's locale.
        @SuppressLint("AppBundleLocaleChanges")
        val englishConfiguration =
            Configuration(resources.configuration).apply { setLocale(Locale.US) }
        val english = context.createConfigurationContext(englishConfiguration).resources
        val found = HashMap<String, MutableSet<String>>()
        var misses = 0
        var entry = 0
        while (entry <= MAX_ENTRY && misses < MAX_CONSECUTIVE_MISSES) {
            val id = stringType or entry
            val englishValue = runCatching { english.getString(id) }.getOrNull()
            if (englishValue == null) {
                misses++
            } else {
                misses = 0
                PlayStoreLabels.canonical(englishValue)?.let { label ->
                    runCatching { resources.getString(id) }.getOrNull()?.let { localized ->
                        found.getOrPut(label, ::HashSet) += localized
                    }
                }
            }
            entry++
        }
        return found
    }

    /** The package's string type ID, taken from the application label, which is a string. */
    private fun stringTypeBase(resources: Resources): Int? {
        val labelRes = context.applicationInfo.labelRes
        if (labelRes == 0) return null
        val typeName = runCatching { resources.getResourceTypeName(labelRes) }.getOrNull()
        return if (typeName == "string") labelRes and TYPE_MASK else null
    }

    companion object {
        private const val TYPE_MASK = 0xFFFF0000.toInt()
        private const val MAX_ENTRY = 0xFFFF
        private const val MAX_CONSECUTIVE_MISSES = 256

        // Holds only the application context (see of()), which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: LocalizedLabels? = null

        fun of(context: Context): LocalizedLabels =
            instance ?: synchronized(this) {
                // Application-scoped context only: the process-wide instance must not retain
                // anything shorter-lived. During Application.attach getApplicationContext() is
                // still null, and the Application passed in is itself process-scoped.
                instance
                    ?: LocalizedLabels(context.applicationContext ?: context).also { instance = it }
            }
    }
}

/** English default texts of the Play Store labels the optional filters identify. */
internal object PlayStoreLabels {
    const val MANAGE_APPS = "Manage apps & device"
    const val NOTIFICATIONS = "Notifications & offers"
    const val PAYMENTS_AND_SUBSCRIPTIONS = "Payments & subscriptions"
    const val PLAY_PROTECT = "Play Protect"
    const val EXPERIMENTAL_FEATURES = "Try experimental features"
    const val LIBRARY = "Library"
    const val PLAY_PASS = "Play Pass"
    const val RATINGS_AND_REVIEWS = "Ratings & reviews"
    const val CONTRIBUTIONS = "Your contributions"
    const val DEVICE_SYNC = "Sync apps to devices"

    val ALL =
        setOf(
            MANAGE_APPS,
            NOTIFICATIONS,
            PAYMENTS_AND_SUBSCRIPTIONS,
            PLAY_PROTECT,
            EXPERIMENTAL_FEATURES,
            LIBRARY,
            PLAY_PASS,
            RATINGS_AND_REVIEWS,
            CONTRIBUTIONS,
            DEVICE_SYNC,
        )

    // Declared before byNormalizedText: object properties initialize in source order.
    private val WHITESPACE = Regex("\\s+")
    private val byNormalizedText: Map<String, String> = ALL.associateBy(::normalize)

    /**
     * Maps an English resource value to the label it denotes. Matching ignores case, spacing and
     * "&" versus "and", so small copy edits ("Ratings and reviews") keep working.
     */
    fun canonical(englishValue: String): String? = byNormalizedText[normalize(englishValue)]

    fun normalize(text: String): String =
        text
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(WHITESPACE, " ")
            .trim()
}
