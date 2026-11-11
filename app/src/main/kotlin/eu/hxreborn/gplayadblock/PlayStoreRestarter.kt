package eu.hxreborn.gplayadblock

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Restarts Play Store so new filter settings apply. Blocking; call it off the main thread.
 * Uses only an application context, so a settings screen closed meanwhile is not retained.
 *
 * The module inside Play Store decides whether a restart is safe (no install, no purchase flow),
 * removes the cached feed responses and resets the crash-loop guard. Without a reply from it,
 * Play Store is never force-stopped blindly: the root fallback repeats the same safety checks.
 */
internal object PlayStoreRestarter {
    enum class Outcome { RESTARTED, BUSY, FAILED }

    private enum class Reply { OK, BUSY, FAILED, NONE }

    fun restart(context: Context): Outcome {
        val appContext = context.applicationContext
        val reply =
            when (val first = askModule(appContext)) {
                // A stopped Play Store has no receiver: start it and ask again. The existing task
                // is kept, so an open purchase stays for the module's safety check.
                Reply.NONE -> {
                    launch(appContext, clearTask = false)
                    Thread.sleep(MODULE_STARTUP_WAIT_MILLIS)
                    askModule(appContext)
                }

                else -> {
                    first
                }
            }
        return when (reply) {
            Reply.OK -> relaunchAfterModuleRestart(appContext)

            Reply.BUSY -> Outcome.BUSY

            // The module answered and could not restart safely: root must not override it.
            Reply.FAILED -> Outcome.FAILED

            Reply.NONE -> restartViaRoot()
        }
    }

    private fun askModule(context: Context): Reply =
        runCatching {
            val completed = CountDownLatch(1)
            val received = AtomicInteger(RestartProtocol.RESULT_FAILED)
            val callback =
                object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(
                        resultCode: Int,
                        resultData: Bundle?,
                    ) {
                        received.set(resultCode)
                        completed.countDown()
                    }
                }
            context.sendBroadcast(
                Intent(RestartProtocol.ACTION)
                    .setPackage(BuildConfig.TARGET_PACKAGE)
                    .putExtra(RestartProtocol.EXTRA_CALLBACK, callback),
            )
            if (!completed.await(MODULE_REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return@runCatching Reply.NONE
            }
            when (received.get()) {
                RestartProtocol.RESULT_OK -> Reply.OK
                RestartProtocol.RESULT_BUSY -> Reply.BUSY
                else -> Reply.FAILED
            }
        }.getOrDefault(Reply.NONE)

    private fun relaunchAfterModuleRestart(context: Context): Outcome =
        runCatching {
            Thread.sleep(MODULE_RELAUNCH_DELAY_MILLIS)
            if (launch(context, clearTask = true)) Outcome.RESTARTED else Outcome.FAILED
        }.getOrDefault(Outcome.FAILED)

    private fun launch(
        context: Context,
        clearTask: Boolean,
    ): Boolean =
        runCatching {
            val launchIntent =
                context.packageManager.getLaunchIntentForPackage(BuildConfig.TARGET_PACKAGE)
                    ?: return@runCatching false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (clearTask) launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)

    // The fixed /data/user path is intended: root operates on Play Store's own data directory.
    @SuppressLint("SdCardPath")
    private fun restartViaRoot(): Outcome =
        runCatching {
            // Output is discarded, so a su that keeps its streams open cannot block the timed wait.
            ProcessBuilder("su", "-c", rootScript())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
                .start()
                .let { process ->
                    if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        return@runCatching Outcome.FAILED
                    }
                    when (process.exitValue()) {
                        0 -> Outcome.RESTARTED
                        ROOT_EXIT_BUSY -> Outcome.BUSY
                        else -> Outcome.FAILED
                    }
                }
        }.getOrDefault(Outcome.FAILED)

    /**
     * The root fallback, run by su's shell. Each step must succeed before the next one runs:
     * - refuse (exit [ROOT_EXIT_BUSY]) while any package installer session is staged or a Play
     *   Store purchase activity exists, the same checks the module makes; a check that cannot
     *   run fails the restart instead of passing;
     * - stop Play Store, then remove the cached feed responses and the crash-loop guard in init's
     *   mount namespace (su may keep this app's namespace, where app data isolation hides other
     *   packages' data directories) and confirm the cache directory is gone. If that fails,
     *   Play Store is started again and the restart is reported as failed;
     * - start Play Store again.
     */
    @SuppressLint("SdCardPath")
    internal fun rootScript(userId: Int = Process.myUid() / PER_USER_UID_RANGE): String {
        // Secondary users and work profiles have their own data directory and Play Store process.
        val target = BuildConfig.TARGET_PACKAGE
        val dataDir = "/data/user/$userId/$target"
        val feedCache = "$dataDir/cache/${RestartProtocol.STREAM_DATA_STORE_DIR}"
        val crashGuard = "$dataDir/${RestartProtocol.CRASH_GUARD_PATH}"
        val purchaseActivity = "$target/${RestartProtocol.PURCHASE_ACTIVITY_PACKAGE}"
        val cleanup = "rm -rf $feedCache && rm -f $crashGuard && [ ! -e $feedCache ]"
        val start =
            "am start --user $userId --activity-clear-task -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -p $target >/dev/null"
        return listOf(
            "[ -d /data/app ] || exit 1",
            "for session in /data/app/vmdl*.tmp; do " +
                "[ -e \"\$session\" ] && exit $ROOT_EXIT_BUSY; done",
            "activities=\$(dumpsys activity activities) || exit 1",
            "case \"\$activities\" in *'$purchaseActivity'*) exit $ROOT_EXIT_BUSY ;; esac",
            "am force-stop --user $userId $target || exit 1",
            "if ! nsenter -t 1 -m -- sh -c '$cleanup'; then $start; exit 1; fi",
            "$start || exit 1",
        ).joinToString("\n")
    }

    private const val MODULE_REPLY_TIMEOUT_SECONDS = 3L
    private const val MODULE_STARTUP_WAIT_MILLIS = 2_000L
    private const val MODULE_RELAUNCH_DELAY_MILLIS = 500L
    private const val ROOT_TIMEOUT_SECONDS = 60L
    private const val ROOT_EXIT_BUSY = 3
    private const val PER_USER_UID_RANGE = 100_000
}
