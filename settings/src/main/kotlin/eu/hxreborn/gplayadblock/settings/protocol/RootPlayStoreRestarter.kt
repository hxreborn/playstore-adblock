package eu.hxreborn.gplayadblock.settings.protocol

import android.annotation.SuppressLint
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class RootPlayStoreRestarter(
    private val cacheDirectory: File,
    private val userId: Int = Process.myUid() / PER_USER_UID_RANGE,
    private val timeoutSeconds: Long = ROOT_TIMEOUT_SECONDS,
    private val startProcess: (String, File) -> java.lang.Process = { script, output ->
        ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true)
            .redirectOutput(output)
            .start()
    },
) : PlayStoreRestarter {
    override suspend fun restart(
        clearStreamCache: Boolean,
        onExited: () -> Unit,
    ): RestartOutcome {
        val outcomeAfterStop =
            when (runAsRoot(stopScript(userId, clearStreamCache))) {
                0 -> RestartOutcome.Restarted
                EXIT_CACHE_PENDING -> RestartOutcome.RestartedCachePending
                EXIT_CRASH_GUARD_KEPT -> RestartOutcome.Failed
                EXIT_BUSY -> return RestartOutcome.Busy
                EXIT_FAILED -> return RestartOutcome.Failed
                EXIT_ROOT_UNAVAILABLE -> return RestartOutcome.RootUnavailable
                else -> return RestartOutcome.Failed
            }
        onExited()
        if (runAsRoot(startScript(userId)) != 0) return RestartOutcome.Failed
        return outcomeAfterStop
    }

    private suspend fun runAsRoot(script: String): Int =
        runInterruptible(Dispatchers.IO) {
            val output = File.createTempFile("gplay-root-", ".log", cacheDirectory)
            try {
                val process =
                    try {
                        startProcess("printf '%s\\n' '$SHELL_STARTED'\n$script", output)
                    } catch (exception: IOException) {
                        Log.w(TAG, "starting su failed", exception)
                        val message = exception.message.orEmpty()
                        return@runInterruptible if (message.contains("error=2,") ||
                            message.contains("error=13,")
                        ) {
                            EXIT_ROOT_UNAVAILABLE
                        } else {
                            EXIT_FAILED
                        }
                    }
                try {
                    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                        Log.w(TAG, "root command timed out")
                        return@runInterruptible EXIT_FAILED
                    }
                    val exitCode = process.exitValue()
                    val lines = output.bufferedReader().useLines { it.take(16).toList() }
                    if (SHELL_STARTED !in lines) {
                        val denied =
                            exitCode != 0 && lines.any { it.trim() in ROOT_DENIAL_MESSAGES }
                        Log.w(TAG, "su did not start the command: exit=$exitCode")
                        return@runInterruptible if (denied) EXIT_ROOT_UNAVAILABLE else EXIT_FAILED
                    }
                    if (exitCode != 0) Log.w(TAG, "root command failed: exit=$exitCode")
                    return@runInterruptible exitCode.takeUnless { it == EXIT_ROOT_UNAVAILABLE }
                        ?: EXIT_FAILED
                } finally {
                    process.destroyForcibly()
                }
            } finally {
                output.delete()
            }
        }

    companion object {
        private const val TAG = "GPlayAdblock"
        private const val SHELL_STARTED = "GPLAY_ADBLOCK_SHELL_STARTED"
        private val ROOT_DENIAL_MESSAGES = setOf("Permission denied", "su: Permission denied")
        const val EXIT_FAILED = 4
        const val EXIT_BUSY = 3
        const val EXIT_CACHE_PENDING = 5
        const val EXIT_CRASH_GUARD_KEPT = 7
        private const val EXIT_ROOT_UNAVAILABLE = 6
        private const val ROOT_TIMEOUT_SECONDS = 60L
        private const val PER_USER_UID_RANGE = 100_000
        private const val ACTIVE_SESSIONS_HEADER = "Active install sessions:"

        @SuppressLint("SdCardPath")
        private fun dataDir(userId: Int): String =
            "/data/user/$userId/${RestartProtocol.PLAY_STORE_PACKAGE}"

        fun stopScript(
            userId: Int,
            clearStreamCache: Boolean,
        ): String {
            require(userId >= 0)
            val target = RestartProtocol.PLAY_STORE_PACKAGE
            val dataDir = dataDir(userId)
            val cacheDir = "$dataDir/cache"
            val streamCache = "$cacheDir/${RestartProtocol.STREAM_DATA_STORE_DIR}"
            val crashGuard = "$dataDir/${RestartProtocol.CRASH_GUARD_PATH}"
            val purchaseActivity = "$target/${RestartProtocol.PURCHASE_ACTIVITY_PACKAGE}"
            val crashGuardRemoval = "rm -f $crashGuard && [ ! -e $crashGuard ]"
            val cacheCleanup =
                "[ ! -L $cacheDir ] && [ ! -L $streamCache ] && " +
                    "rm -rf $streamCache && [ ! -e $streamCache ]"
            return listOfNotNull(
                "ls /data/app >/dev/null || exit $EXIT_FAILED",
                "package_state=\$(dumpsys package) || exit $EXIT_FAILED",
                "sessions=\$(printf '%s\\n' \"\$package_state\" | " +
                    "sed -n '/^$ACTIVE_SESSIONS_HEADER/,/^[^ ]/p')",
                "case \"\$sessions\" in '$ACTIVE_SESSIONS_HEADER'*) ;; *) exit $EXIT_FAILED ;; esac",
                "case \"\$sessions\" in *'installerPackageName=$target '*) exit $EXIT_BUSY ;; esac",
                "activities=\$(dumpsys activity activities) || exit $EXIT_FAILED",
                "case \"\$activities\" in *'$purchaseActivity'*) exit $EXIT_BUSY ;; esac",
                "am force-stop --user $userId $target || exit $EXIT_FAILED",
                "nsenter -t 1 -m -- sh -c '$crashGuardRemoval' || exit $EXIT_CRASH_GUARD_KEPT",
                "nsenter -t 1 -m -- sh -c '$cacheCleanup' || exit $EXIT_CACHE_PENDING".takeIf {
                    clearStreamCache
                },
            ).joinToString("\n")
        }

        fun startScript(userId: Int): String {
            require(userId >= 0)
            val target = RestartProtocol.PLAY_STORE_PACKAGE
            val dataDir = dataDir(userId)
            return listOf(
                "launch=\$(am start -W --user $userId --activity-clear-task " +
                    "-a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER -p $target) || exit $EXIT_FAILED",
                "printf '%s\\n' \"\$launch\" | grep -qx 'Status: ok' || exit $EXIT_FAILED",
                "uid=\$(stat -c %u $dataDir) || exit $EXIT_FAILED",
                "for pid in \$(pidof $target); do " +
                    "[ \"\$(stat -c %u /proc/\$pid)\" = \"\$uid\" ] && exit 0; done",
                "exit $EXIT_FAILED",
            ).joinToString("\n")
        }
    }
}
