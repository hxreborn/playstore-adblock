package eu.hxreborn.gplayadblock

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.os.SystemClock
import eu.hxreborn.gplayadblock.settings.protocol.RestartProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object PlayStoreRestarter {
    enum class Outcome { RESTARTED, BUSY, FAILED }

    private enum class Reply { OK, BUSY, FAILED, NONE }

    fun restart(context: Context): Outcome {
        val appContext = context.applicationContext
        val reply =
            when (val first = askModule(appContext)) {
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
            val deadline =
                SystemClock.elapsedRealtime() +
                    TimeUnit.SECONDS.toMillis(MODULE_REPLY_TIMEOUT_SECONDS) -
                    MODULE_DECISION_MARGIN_MILLIS
            context.sendBroadcast(
                Intent(RestartProtocol.ACTION)
                    .setPackage(BuildConfig.TARGET_PACKAGE)
                    .putExtra(RestartProtocol.EXTRA_CALLBACK, callback)
                    .putExtra(RestartProtocol.EXTRA_DEADLINE, deadline),
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

    @SuppressLint("SdCardPath")
    private fun restartViaRoot(): Outcome =
        runCatching {
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

    @SuppressLint("SdCardPath")
    internal fun rootScript(userId: Int = Process.myUid() / PER_USER_UID_RANGE): String {
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
            "ls /data/app >/dev/null || exit 1",
            "for session in /data/app/vmdl*.tmp /mnt/expand/*/app/vmdl*.tmp " +
                "/data/app-staging/session_*; do " +
                "[ -e \"\$session\" ] && exit $ROOT_EXIT_BUSY; done",
            "activities=\$(dumpsys activity activities) || exit 1",
            "case \"\$activities\" in *'$purchaseActivity'*) exit $ROOT_EXIT_BUSY ;; esac",
            "am force-stop --user $userId $target || exit 1",
            "if ! nsenter -t 1 -m -- sh -c '$cleanup'; then $start; exit 1; fi",
            "$start || exit 1",
        ).joinToString("\n")
    }

    private const val MODULE_REPLY_TIMEOUT_SECONDS = 3L
    private const val MODULE_DECISION_MARGIN_MILLIS = 1_000L
    private const val MODULE_STARTUP_WAIT_MILLIS = 2_000L
    private const val MODULE_RELAUNCH_DELAY_MILLIS = 500L
    private const val ROOT_TIMEOUT_SECONDS = 60L
    private const val ROOT_EXIT_BUSY = 3
    private const val PER_USER_UID_RANGE = 100_000
}
