package eu.hxreborn.gplayadblock.full

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.os.SystemClock
import eu.hxreborn.gplayadblock.BuildConfig
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.settings.protocol.RestartProtocol
import java.io.File
import java.io.IOException
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
                    val clearStreamCache =
                        intent.getBooleanExtra(RestartProtocol.EXTRA_CLEAR_STREAM_CACHE, false)
                    val deadline = intent.getLongExtra(RestartProtocol.EXTRA_DEADLINE, 0L)
                    val callback =
                        intent.getParcelableExtra(RestartProtocol.EXTRA_CALLBACK) as? ResultReceiver
                    val pendingResult = goAsync()
                    Thread {
                        try {
                            val refusal = refusalResult(receiverContext, deadline)
                            if (refusal != null) {
                                pendingResult.resultCode = refusal
                                pendingResult.finish()
                                return@Thread
                            }
                            val cacheClearPending =
                                clearStreamCache && !clearStreamCache(receiverContext)
                            Handler(Looper.getMainLooper()).post {
                                replyAndStopProcess(
                                    crashGuard,
                                    deadline,
                                    cacheClearPending,
                                    callback,
                                    pendingResult,
                                )
                            }
                        } catch (exception: Exception) {
                            Logger.error("Play Store restart failed before stopping", exception)
                            pendingResult.resultCode = RestartProtocol.RESULT_FAILED
                            pendingResult.finish()
                        }
                    }.start()
                }
            },
            IntentFilter(RestartProtocol.ACTION),
            RestartProtocol.PERMISSION,
            Handler(Looper.getMainLooper()),
            Context.RECEIVER_EXPORTED,
        )
        announceStart(application)
    }

    private fun announceStart(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        context.sendBroadcast(
            Intent(RestartProtocol.ACTION_STARTED)
                .setPackage(BuildConfig.APPLICATION_ID)
                .putExtra(RestartProtocol.EXTRA_STARTED_AT, Process.getStartElapsedRealtime()),
            null,
            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
        )
    }

    private fun clearStreamCache(context: Context): Boolean =
        try {
            StreamCacheCleaner.clear(File(context.cacheDir, RestartProtocol.STREAM_DATA_STORE_DIR))
            true
        } catch (exception: IOException) {
            Logger.warn("stream cache clear failed, restart continues", exception)
            false
        } catch (exception: SecurityException) {
            Logger.warn("stream cache clear failed, restart continues", exception)
            false
        }

    private fun replyAndStopProcess(
        crashGuard: CrashLoopGuard,
        deadline: Long,
        cacheClearPending: Boolean,
        callback: ResultReceiver?,
        pendingResult: BroadcastReceiver.PendingResult,
    ) {
        try {
            val expired =
                RestartProtocol.isPastDeadline(SystemClock.elapsedRealtime(), deadline)
            val refusal =
                when {
                    livePurchaseActivities.get() > 0 -> {
                        Logger.info("restart refused: Play Store is in a purchase flow")
                        RestartProtocol.RESULT_BUSY
                    }

                    expired -> {
                        Logger.info("restart refused: request expired")
                        RestartProtocol.RESULT_FAILED
                    }

                    !crashGuard.reset() -> {
                        Logger.warn("restart refused: crash guard kept")
                        RestartProtocol.RESULT_FAILED
                    }

                    else -> {
                        null
                    }
                }
            if (refusal != null) {
                pendingResult.resultCode = refusal
                return
            }
            val reply =
                if (cacheClearPending) {
                    RestartProtocol.RESULT_CACHE_PENDING
                } else {
                    RestartProtocol.RESULT_READY
                }
            callback?.send(reply, Bundle.EMPTY)
            Logger.info("restart requested, ending the Play Store process")
            Process.killProcess(Process.myPid())
        } catch (exception: Exception) {
            Logger.error("Play Store restart failed while stopping", exception)
            pendingResult.resultCode = RestartProtocol.RESULT_FAILED
        } finally {
            pendingResult.finish()
        }
    }

    private fun refusalResult(
        context: Context,
        deadline: Long,
    ): Int? {
        if (RestartProtocol.isPastDeadline(SystemClock.elapsedRealtime(), deadline)) {
            Logger.info("restart refused: request expired")
            return RestartProtocol.RESULT_FAILED
        }
        val busy =
            when {
                hasActiveInstallSession(context) -> "installing apps"
                livePurchaseActivities.get() > 0 -> "in a purchase flow"
                else -> null
            }
        if (busy != null) {
            Logger.info("restart refused: Play Store is $busy")
            return RestartProtocol.RESULT_BUSY
        }
        return null
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
                .any { session ->
                    blocksRestart(session.isActive, session.isStaged, session.isCommittedCompat())
                }
        }.onFailure { error -> Logger.warn("install session check failed", error) }
            .getOrThrow()

    // SessionInfo.isCommitted needs API 31; before it, a ready, applied or failed staged session
    // has been committed.
    private fun PackageInstaller.SessionInfo.isCommittedCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isCommitted
        } else {
            isStagedSessionReady || isStagedSessionApplied || isStagedSessionFailed
        }
}

/**
 * Whether an install session of Play Store makes a restart unsafe. A committed staged session (a
 * system update waiting for the next reboot) stays active until that reboot, but the system
 * applies it without Play Store, so it must not block the restart indefinitely.
 */
internal fun blocksRestart(
    active: Boolean,
    staged: Boolean,
    committed: Boolean,
): Boolean = active && !(staged && committed)
