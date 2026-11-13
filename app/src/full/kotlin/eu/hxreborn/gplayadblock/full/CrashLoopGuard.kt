package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import java.io.File

/**
 * Remembers recent Play Store crashes so that optional UI hooks can be skipped after a crash
 * loop. Ad filtering is not affected.
 *
 * The guard is keyed by [fingerprint] (Play Store and module build), so updating either one,
 * or calling [reset] (the settings restart button), gives the optional hooks another chance.
 */
internal class CrashLoopGuard(
    private val file: File,
    private val fingerprint: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun isTripped(): Boolean = read()?.takeIf { it.fingerprint == fingerprint }?.tripped == true

    fun settingsForProcess(requested: FilterSettings): FilterSettings =
        if (requested != FilterSettings() && isTripped()) FilterSettings() else requested

    fun recordCrash() {
        val now = clock()
        val previous = read()?.takeIf { it.fingerprint == fingerprint }
        val crashes =
            (previous?.crashes.orEmpty() + now).filter { time -> now - time in 0..WINDOW_MILLIS }
        val tripped = previous?.tripped == true || crashes.size >= THRESHOLD
        write(State(fingerprint, tripped, crashes))
    }

    /** Forgets recorded crashes. Returns false when the state file exists and was not removed. */
    fun reset(): Boolean = file.delete() || !file.exists()

    private data class State(
        val fingerprint: String,
        val tripped: Boolean,
        val crashes: List<Long>,
    )

    private fun read(): State? =
        try {
            if (!file.isFile) {
                null
            } else {
                val lines = file.readLines()
                State(
                    fingerprint = lines[0],
                    tripped = lines[1] == "1",
                    crashes =
                        lines
                            .getOrNull(2)
                            .orEmpty()
                            .split(',')
                            .mapNotNull(String::toLongOrNull),
                )
            }
        } catch (_: Exception) {
            null
        }

    private fun write(state: State) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(
                listOf(
                    state.fingerprint,
                    if (state.tripped) "1" else "0",
                    state.crashes.joinToString(","),
                ).joinToString("\n"),
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        const val THRESHOLD = 3
        const val WINDOW_MILLIS = 120_000L
    }
}
