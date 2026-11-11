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
 */
internal object PlayStoreRestarter {
    enum class Outcome { RESTARTED, BUSY, FAILED }

    fun restart(context: Context): Outcome {
        val appContext = context.applicationContext
        return when (val viaModule = restartViaModule(appContext)) {
            // A busy Play Store must not be force-stopped through root either.
            Outcome.RESTARTED, Outcome.BUSY -> viaModule

            Outcome.FAILED -> if (restartViaRoot()) Outcome.RESTARTED else viaModule
        }
    }

    private fun restartViaModule(context: Context): Outcome =
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
            if (!completed.await(MODULE_RESTART_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return@runCatching Outcome.FAILED
            }
            when (received.get()) {
                RestartProtocol.RESULT_OK -> Unit
                RestartProtocol.RESULT_BUSY -> return@runCatching Outcome.BUSY
                else -> return@runCatching Outcome.FAILED
            }
            Thread.sleep(MODULE_RELAUNCH_DELAY_MILLIS)
            val launchIntent =
                context.packageManager.getLaunchIntentForPackage(BuildConfig.TARGET_PACKAGE)
                    ?: return@runCatching Outcome.FAILED
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
            context.startActivity(launchIntent)
            Outcome.RESTARTED
        }.getOrDefault(Outcome.FAILED)

    // The fixed /data/user path is intended: root operates on Play Store's own data directory.
    @SuppressLint("SdCardPath")
    private fun restartViaRoot(): Boolean =
        runCatching {
            // Target the Android user this app runs in (secondary users and work profiles have
            // their own data directory and their own Play Store process).
            val userId = Process.myUid() / PER_USER_UID_RANGE
            val target = BuildConfig.TARGET_PACKAGE
            val dataDir = "/data/user/$userId/$target"
            val clearCaches = "rm -rf $dataDir/cache/* $dataDir/code_cache/*"
            val command =
                listOf(
                    "am force-stop --user $userId $target",
                    // su may keep this app's mount namespace, where app data isolation hides other
                    // packages' data directories; clear the caches in init's (global) namespace.
                    "{ nsenter -t 1 -m -- sh -c '$clearCaches' || $clearCaches; }",
                    "am start --user $userId --activity-clear-task -a android.intent.action.MAIN " +
                        "-c android.intent.category.LAUNCHER -p $target",
                ).joinToString("; ")
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
                .let { process ->
                    val completed = process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (!completed) process.destroyForcibly()
                    completed && process.exitValue() == 0
                }
        }.getOrDefault(false)

    private const val MODULE_RESTART_TIMEOUT_SECONDS = 3L
    private const val MODULE_RELAUNCH_DELAY_MILLIS = 500L
    private const val ROOT_TIMEOUT_SECONDS = 60L
    private const val PER_USER_UID_RANGE = 100_000
}
