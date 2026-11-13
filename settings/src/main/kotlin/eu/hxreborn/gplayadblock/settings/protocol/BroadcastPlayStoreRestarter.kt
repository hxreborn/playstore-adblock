package eu.hxreborn.gplayadblock.settings.protocol

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

class BroadcastPlayStoreRestarter(
    private val context: Context,
    private val replyTimeoutMillis: Long = REPLY_TIMEOUT_MILLIS,
) : PlayStoreRestarter {
    override suspend fun restart(
        clearStreamCache: Boolean,
        onExited: () -> Unit,
    ): RestartOutcome {
        val requestedAt = SystemClock.elapsedRealtime()
        val deadline = requestedAt + replyTimeoutMillis
        val freshStart = CompletableDeferred<Unit>()
        val startReceiver = registerFreshStartReceiver(requestedAt, freshStart)
        val resultCode =
            try {
                withTimeoutOrNull(replyTimeoutMillis) {
                    requestStopAndAwaitResult(clearStreamCache, deadline, freshStart)
                }
            } finally {
                startReceiver?.let(context::unregisterReceiver)
            } ?: return RestartOutcome.NoReply
        val cacheClearPending =
            resultCode == RestartProtocol.RESULT_CACHE_PENDING ||
                (resultCode == RestartProtocol.RESULT_RESTARTED_WITHOUT_REPLY && clearStreamCache)
        return when (resultCode) {
            RestartProtocol.RESULT_READY,
            RestartProtocol.RESULT_CACHE_PENDING,
            RestartProtocol.RESULT_RESTARTED_WITHOUT_REPLY,
            -> {
                onExited()
                when {
                    !launch() -> {
                        RestartOutcome.Failed
                    }

                    cacheClearPending -> {
                        RestartOutcome.RestartedCachePending
                    }

                    else -> {
                        RestartOutcome.Restarted
                    }
                }
            }

            RestartProtocol.RESULT_BUSY -> {
                RestartOutcome.Busy
            }

            else -> {
                RestartOutcome.Failed
            }
        }
    }

    private fun registerFreshStartReceiver(
        requestedAt: Long,
        freshStart: CompletableDeferred<Unit>,
    ): BroadcastReceiver? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: Intent,
                ) {
                    val startedAt = intent.getLongExtra(RestartProtocol.EXTRA_STARTED_AT, 0L)
                    if (sentFromPackage == RestartProtocol.PLAY_STORE_PACKAGE &&
                        startedAt >= requestedAt
                    ) {
                        freshStart.complete(Unit)
                    }
                }
            }
        context.registerReceiver(
            receiver,
            IntentFilter(RestartProtocol.ACTION_STARTED),
            null,
            Handler(Looper.getMainLooper()),
            Context.RECEIVER_EXPORTED,
        )
        return receiver
    }

    private suspend fun requestStopAndAwaitResult(
        clearStreamCache: Boolean,
        deadline: Long,
        freshStart: Deferred<Unit>,
    ): Int {
        val callbackReply = CompletableDeferred<Int>()
        val broadcastFinished = CompletableDeferred<Int>()
        val handler = Handler(Looper.getMainLooper())
        val callback =
            object : ResultReceiver(handler) {
                override fun onReceiveResult(
                    resultCode: Int,
                    resultData: Bundle?,
                ) {
                    callbackReply.complete(resultCode)
                }
            }
        context.sendOrderedBroadcast(
            Intent(RestartProtocol.ACTION)
                .setPackage(RestartProtocol.PLAY_STORE_PACKAGE)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(RestartProtocol.EXTRA_CALLBACK, callback.platformCopy())
                .putExtra(RestartProtocol.EXTRA_CLEAR_STREAM_CACHE, clearStreamCache)
                .putExtra(RestartProtocol.EXTRA_DEADLINE, deadline),
            null,
            object : BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: Intent,
                ) {
                    broadcastFinished.complete(resultCode)
                }
            },
            handler,
            RestartProtocol.RESULT_UNSET,
            null,
            null,
        )
        return awaitRestartResult(broadcastFinished, callbackReply, freshStart)
    }

    private fun ResultReceiver.platformCopy(): ResultReceiver {
        val parcel = Parcel.obtain()
        return try {
            writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun launch(): Boolean {
        val launchIntent =
            context.packageManager.getLaunchIntentForPackage(RestartProtocol.PLAY_STORE_PACKAGE)
                ?: return false
        return try {
            context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "opening Play Store failed", exception)
            false
        } catch (exception: SecurityException) {
            Log.w(TAG, "opening Play Store failed", exception)
            false
        }
    }

    companion object {
        private const val TAG = "GPlayAdblock"
        private const val REPLY_TIMEOUT_MILLIS = 15_000L
    }
}

internal suspend fun awaitRestartResult(
    broadcastFinished: Deferred<Int>,
    callbackReply: Deferred<Int>,
    freshStart: Deferred<Unit>,
): Int {
    val broadcastResult = broadcastFinished.await()
    if (broadcastResult != RestartProtocol.RESULT_UNSET) return broadcastResult
    return select {
        callbackReply.onAwait { reply -> reply }
        freshStart.onAwait { RestartProtocol.RESULT_RESTARTED_WITHOUT_REPLY }
    }
}
