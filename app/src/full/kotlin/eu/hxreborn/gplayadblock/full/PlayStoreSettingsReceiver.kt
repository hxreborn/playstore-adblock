package eu.hxreborn.gplayadblock.full

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.os.SystemClock
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.settings.protocol.RestartProtocol
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object PlayStoreSettingsReceiver {
    private val livePurchaseActivities = AtomicInteger(0)

    @SuppressLint("InlinedApi")
    internal fun register(
        application: Application,
        crashGuard: CrashLoopGuard,
    ) {
        trackPurchaseFlows(application)
        application.registerReceiver(
            object : BroadcastReceiver() {
                @Suppress("DEPRECATION")
                override fun onReceive(
                    receiverContext: Context,
                    intent: Intent,
                ) {
                    if (intent.action != RestartProtocol.ACTION) return
                    val callback =
                        intent.getParcelableExtra(RestartProtocol.EXTRA_CALLBACK) as? ResultReceiver
                    val pendingResult = goAsync()
                    val deadline = intent.getLongExtra(RestartProtocol.EXTRA_DEADLINE, 0L)
                    Thread {
                        if (SystemClock.elapsedRealtime() > deadline) {
                            Logger.info("restart refused: request expired")
                            callback?.send(RestartProtocol.RESULT_FAILED, Bundle.EMPTY)
                            pendingResult.finish()
                            return@Thread
                        }
                        val busy =
                            when {
                                hasActiveInstallSession(receiverContext) -> "installing apps"
                                livePurchaseActivities.get() > 0 -> "in a purchase flow"
                                else -> null
                            }
                        if (busy != null) {
                            Logger.info("restart refused: Play Store is $busy")
                            callback?.send(RestartProtocol.RESULT_BUSY, Bundle.EMPTY)
                            pendingResult.finish()
                            return@Thread
                        }
                        val cleared =
                            runCatching {
                                File(
                                    receiverContext.cacheDir,
                                    RestartProtocol.STREAM_DATA_STORE_DIR,
                                ).deleteRecursively()
                            }.onFailure { error ->
                                Logger.warn("Play Store cache cleanup failed", error)
                            }.getOrDefault(false)
                        Handler(Looper.getMainLooper()).post {
                            val reply = restartReply(crashGuard, deadline, cleared)
                            callback?.send(reply, Bundle.EMPTY)
                            pendingResult.finish()
                            if (reply == RestartProtocol.RESULT_OK) {
                                Process.killProcess(Process.myPid())
                            }
                        }
                    }.start()
                }
            },
            IntentFilter(RestartProtocol.ACTION),
            RestartProtocol.PERMISSION,
            Handler(Looper.getMainLooper()),
            Context.RECEIVER_EXPORTED,
        )
    }

    private fun restartReply(
        crashGuard: CrashLoopGuard,
        deadline: Long,
        cleared: Boolean,
    ): Int {
        if (livePurchaseActivities.get() > 0) {
            Logger.info("restart refused: Play Store is in a purchase flow")
            return RestartProtocol.RESULT_BUSY
        }
        val reply =
            RestartProtocol.finalReply(
                now = SystemClock.elapsedRealtime(),
                deadline = deadline,
                cleared = cleared,
            )
        if (reply != RestartProtocol.RESULT_OK) {
            if (cleared) Logger.info("restart refused: request expired")
            return reply
        }
        if (!crashGuard.reset()) {
            Logger.warn("restart refused: crash guard kept")
            return RestartProtocol.RESULT_FAILED
        }
        return reply
    }

    private fun trackPurchaseFlows(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {
                    if (activity.isPurchaseFlow()) livePurchaseActivities.incrementAndGet()
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (activity.isPurchaseFlow()) livePurchaseActivities.decrementAndGet()
                }

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit
            },
        )
    }

    private fun Activity.isPurchaseFlow(): Boolean =
        javaClass.name.startsWith(RestartProtocol.PURCHASE_ACTIVITY_PACKAGE)

    private fun hasActiveInstallSession(context: Context): Boolean =
        runCatching {
            context.packageManager.packageInstaller.mySessions
                .any { session -> session.isActive }
        }.onFailure { error -> Logger.warn("install session check failed", error) }
            .getOrDefault(true)
}
