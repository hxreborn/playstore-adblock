package eu.hxreborn.gplayadblock.settings.protocol

object RestartProtocol {
    const val ACTION = "eu.hxreborn.gplayadblock.action.RESTART_PLAY_STORE"
    const val PERMISSION = "eu.hxreborn.gplayadblock.permission.RESTART_PLAY_STORE"
    const val EXTRA_CALLBACK = "restart_callback"

    const val EXTRA_DEADLINE = "restart_deadline"
    const val RESULT_OK = 1
    const val RESULT_FAILED = 0

    const val RESULT_BUSY = 2

    const val STREAM_DATA_STORE_DIR = "streamdatastore"

    const val PURCHASE_ACTIVITY_PACKAGE = "com.google.android.finsky.billing."

    const val CRASH_GUARD_PATH = "files/playstore-adblock/optional-crash-guard"

    fun finalReply(
        now: Long,
        deadline: Long,
        cleared: Boolean,
    ): Int =
        when {
            now > deadline -> RESULT_FAILED
            !cleared -> RESULT_FAILED
            else -> RESULT_OK
        }
}
