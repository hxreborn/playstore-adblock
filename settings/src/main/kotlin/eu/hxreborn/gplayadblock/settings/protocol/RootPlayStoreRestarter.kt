package eu.hxreborn.gplayadblock.settings.protocol

import android.annotation.SuppressLint
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class RootPlayStoreRestarter(
    private val userId: Int = Process.myUid() / PER_USER_UID_RANGE,
    private val timeoutSeconds: Long = ROOT_TIMEOUT_SECONDS,
) : PlayStoreRestarter {
    override suspend fun restart(
        clearStreamCache: Boolean,
        onExited: () -> Unit,
    ): RestartOutcome {
        val restartedOutcome =
            when (execute(rootScript(userId, clearStreamCache))) {
                0 -> RestartOutcome.Restarted
                EXIT_CACHE_PENDING -> RestartOutcome.RestartedCachePending
                EXIT_GUARD_KEPT -> RestartOutcome.Failed
                EXIT_BUSY -> return RestartOutcome.Busy
                EXIT_FAILED -> return RestartOutcome.Failed
                else -> return RestartOutcome.RootUnavailable
            }
        onExited()
        if (execute(startScript(userId)) != 0) return RestartOutcome.Failed
        return restartedOutcome
    }

    private suspend fun execute(script: String): Int =
        runInterruptible(Dispatchers.IO) {
            val process =
                try {
                    ProcessBuilder("su", "-c", script)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                        .start()
                } catch (_: IOException) {
                    return@runInterruptible EXIT_ROOT_UNAVAILABLE
                }
            try {
                val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (exited) process.exitValue() else EXIT_FAILED
            } finally {
                process.destroyForcibly()
            }
        }

    companion object {
        const val EXIT_FAILED = 4
        const val EXIT_BUSY = 3
        const val EXIT_CACHE_PENDING = 5
        const val EXIT_GUARD_KEPT = 7
        private const val EXIT_ROOT_UNAVAILABLE = 6
        private const val ROOT_TIMEOUT_SECONDS = 60L
        private const val PER_USER_UID_RANGE = 100_000
        private const val ACTIVE_SESSIONS_HEADER = "Active install sessions:"

        // Exits 1 when a Play Store install session would be aborted by stopping Play Store. Each
        // "Active Session"/"Active Child Session" block is one session. A committed staged session
        // (a system update waiting for the next reboot) stays listed until that reboot, but the
        // system applies it without Play Store, so it does not count.
        internal val BLOCKING_SESSION_FILTER =
            "/^ *Active (Child )?Session [0-9]+:/ { " +
                "if (mine && !(staged && committed)) busy = 1; mine = staged = committed = 0 } " +
                "/ installerPackageName=" +
                RestartProtocol.PLAY_STORE_PACKAGE.replace(".", "\\.") +
                "( |\$)/ { mine = 1 } " +
                "/ isStaged=true( |\$)/ { staged = 1 } " +
                "/ mCommitted=true( |\$)/ { committed = 1 } " +
                "END { if (mine && !(staged && committed)) busy = 1; exit busy }"

        @SuppressLint("SdCardPath")
        private fun dataDir(userId: Int): String =
            "/data/user/$userId/${RestartProtocol.PLAY_STORE_PACKAGE}"

        fun rootScript(
            userId: Int,
            clearStreamCache: Boolean,
        ): String {
            require(userId >= 0)
            val target = RestartProtocol.PLAY_STORE_PACKAGE
            val dataDir = dataDir(userId)
            val cacheDir = "$dataDir/cache"
            val feedCache = "$cacheDir/${RestartProtocol.STREAM_DATA_STORE_DIR}"
            val crashGuard = "$dataDir/${RestartProtocol.CRASH_GUARD_PATH}"
            val purchaseActivity = "$target/${RestartProtocol.PURCHASE_ACTIVITY_PACKAGE}"
            val guardRemoval = "rm -f $crashGuard && [ ! -e $crashGuard ]"
            val cacheCleanup =
                "[ ! -L $cacheDir ] && [ ! -L $feedCache ] && " +
                    "rm -rf $feedCache && [ ! -e $feedCache ]"
            return listOfNotNull(
                "ls /data/app >/dev/null || exit $EXIT_FAILED",
                "package_state=\$(dumpsys package) || exit $EXIT_FAILED",
                "sessions=\$(printf '%s\\n' \"\$package_state\" | " +
                    "sed -n '/^$ACTIVE_SESSIONS_HEADER/,/^[^ ]/p')",
                "case \"\$sessions\" in '$ACTIVE_SESSIONS_HEADER'*) ;; *) exit $EXIT_FAILED ;; esac",
                "printf '%s\\n' \"\$sessions\" | awk '$BLOCKING_SESSION_FILTER' || exit $EXIT_BUSY",
                "activities=\$(dumpsys activity activities) || exit $EXIT_FAILED",
                "case \"\$activities\" in *'$purchaseActivity'*) exit $EXIT_BUSY ;; esac",
                "am force-stop --user $userId $target || exit $EXIT_FAILED",
                "nsenter -t 1 -m -- sh -c '$guardRemoval' || exit $EXIT_GUARD_KEPT",
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
