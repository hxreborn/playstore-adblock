package eu.hxreborn.gplayadblock.hook

import android.content.Context
import eu.hxreborn.gplayadblock.FilterPreferences
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.hook.UiEntryMatcher.ManageRow
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Filters the generic vertical list used by the "Manage apps & device" overview and by the
 * Search landing page. Rows are identified semantically (localized label, Play Protect section
 * class, visual element type), never by their position, so an unknown row is always kept.
 */
object ManageAppsFilter {
    fun install(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
        targets: ResolvedTargets.ManageApps,
    ) {
        val listClass = classLoader.loadClass(targets.manageListClassName)
        val labels = LocalizedLabels.of(context)
        val playProtectRowClasses = targets.playProtectRowClassNames.toSet()
        val elementTypeField =
            targets.veMetadataTypeField?.let { field ->
                runCatching { field.resolve(classLoader) }
                    .onFailure { Logger.warn("visual element metadata unavailable", it) }
                    .getOrNull()
            }
        val stringFields = ConcurrentHashMap<Class<*>, List<Field>>()
        val metadataFields = ConcurrentHashMap<Class<*>, List<Field>>()

        fun directStrings(item: Any): List<String> =
            stringFields
                .getOrPut(item.javaClass) {
                    item.javaClass.instanceFields().filter { it.type == String::class.java }
                }.mapNotNull { field -> runCatching { field.get(item) as? String }.getOrNull() }

        fun elementType(item: Any): Int? {
            val typeField = elementTypeField ?: return null
            val metadataClass = typeField.declaringClass
            return metadataFields
                .getOrPut(item.javaClass) {
                    item.javaClass.instanceFields().filter {
                        metadataClass.isAssignableFrom(
                            it.type,
                        )
                    }
                }.firstNotNullOfOrNull { field ->
                    runCatching { field.get(item)?.let(typeField::getInt) }.getOrNull()
                }
        }

        listClass.declaredConstructors
            .filter { constructor -> constructor.parameterTypes.firstOrNull() == List::class.java }
            .forEach { constructor ->
                constructor.isAccessible = true
                module
                    .hook(constructor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val original =
                            chain.getArg(0) as? List<*> ?: return@intercept chain.proceed()
                        if (
                            // The visual categories cluster (VE 7567) only appears on the
                            // Search landing page (checked on the Apps home and Categories too).
                            preferences.minimalSearchHome() &&
                            elementTypeField != null &&
                            UiEntryMatcher.isSearchLanding(original.map { it?.let(::elementType) })
                        ) {
                            Logger.debug { "minimal search landing removed=${original.size}" }
                            val arguments = chain.args.toTypedArray()
                            arguments[0] = emptyList<Any>()
                            return@intercept chain.proceed(arguments)
                        }
                        if (!anyRowHidden(preferences)) return@intercept chain.proceed()

                        val retained =
                            original.filterNot { item ->
                                if (item == null) return@filterNot false
                                val row =
                                    UiEntryMatcher.manageRow(
                                        className = item.javaClass.name,
                                        directStrings = directStrings(item),
                                        // Only the device sync row needs the object walk.
                                        reachableStrings = {
                                            if (preferences.hideDeviceSync()) {
                                                reachableStrings(item)
                                            } else {
                                                emptyList()
                                            }
                                        },
                                        playProtectRowClasses = playProtectRowClasses,
                                        localized = labels::localized,
                                    )
                                row != null && shouldHide(row, preferences)
                            }
                        if (retained.size == original.size) return@intercept chain.proceed()
                        Logger.debug { "manage apps removed=${original.size - retained.size}" }
                        val arguments = chain.args.toTypedArray()
                        arguments[0] = retained
                        chain.proceed(arguments)
                    }
            }
    }

    private fun anyRowHidden(preferences: FilterPreferences): Boolean =
        preferences.hidePlayProtect() ||
            preferences.hideDeviceSync() ||
            preferences.hideRatingsAndReviews() ||
            preferences.hideContributions()

    private fun shouldHide(
        row: ManageRow,
        preferences: FilterPreferences,
    ): Boolean =
        when (row) {
            ManageRow.PLAY_PROTECT -> preferences.hidePlayProtect()
            ManageRow.DEVICE_SYNC -> preferences.hideDeviceSync()
            ManageRow.RATINGS_AND_REVIEWS -> preferences.hideRatingsAndReviews()
            ManageRow.CONTRIBUTIONS -> preferences.hideContributions()
        }

    /**
     * Collects text reachable from a row model, including the value held by its UI state
     * wrapper. Bounded in depth and size; platform classes other than text and collections are
     * not entered.
     */
    private fun reachableStrings(root: Any): List<String> {
        val found = ArrayList<String>()
        val seen = IdentityHashMap<Any, Unit>()

        fun walk(
            value: Any?,
            depth: Int,
        ) {
            if (value == null || depth > MAX_DEPTH || found.size >= MAX_STRINGS) return
            if (seen.put(value, Unit) != null) return
            when (value) {
                is CharSequence -> {
                    found += value.toString()
                    return
                }

                is Iterable<*> -> {
                    value.take(MAX_ELEMENTS).forEach { walk(it, depth + 1) }
                    return
                }

                is Array<*> -> {
                    value.take(MAX_ELEMENTS).forEach { walk(it, depth + 1) }
                    return
                }
            }
            val type = value.javaClass
            if (type.isPrimitive || type.isPlatformClass()) return
            type
                .instanceFields(includeInherited = true)
                .filterNot { field -> field.type.isPrimitive }
                .forEach { field -> walk(runCatching { field.get(value) }.getOrNull(), depth + 1) }
        }
        walk(root, 0)
        return found
    }

    private fun Class<*>.isPlatformClass(): Boolean =
        name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin.")

    private fun Class<*>.instanceFields(includeInherited: Boolean = false): List<Field> =
        (if (includeInherited) generateSequence(this) { it.superclass } else sequenceOf(this))
            .takeWhile { type -> type != Any::class.java }
            .flatMap { type -> type.declaredFields.asSequence() }
            .filterNot { field -> field.isSynthetic || Modifier.isStatic(field.modifiers) }
            .onEach { field -> field.isAccessible = true }
            .toList()

    private const val MAX_DEPTH = 6
    private const val MAX_STRINGS = 64
    private const val MAX_ELEMENTS = 8
}
