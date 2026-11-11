package eu.hxreborn.gplayadblock.settings.protocol

object RestartProtocol {
    const val PLAY_STORE_PACKAGE = "com.android.vending"
    const val ACTION = "eu.hxreborn.gplayadblock.action.RESTART_PLAY_STORE"
    const val ACTION_STARTED = "eu.hxreborn.gplayadblock.action.PLAY_STORE_STARTED"
    const val PERMISSION = "eu.hxreborn.gplayadblock.permission.RESTART_PLAY_STORE"
    const val EXTRA_CALLBACK = "restart_callback"
    const val EXTRA_CLEAR_STREAM_CACHE = "clear_stream_cache"
    const val EXTRA_DEADLINE = "restart_deadline"
    const val EXTRA_STARTED_AT = "started_at"
    const val RESULT_FAILED = 0
    const val RESULT_NO_RECEIVER = 1
    const val RESULT_READY = 3
    const val RESULT_CACHE_PENDING = 4
    const val RESULT_BUSY = 2
    const val RESULT_RESTARTED_WITHOUT_REPLY = 5
    const val STREAM_DATA_STORE_DIR = "streamdatastore"
    const val PURCHASE_ACTIVITY_PACKAGE = "com.google.android.finsky.billing."
    const val CRASH_GUARD_PATH = "files/playstore-adblock/optional-crash-guard"

    fun refusalBeforeStop(
        now: Long,
        deadline: Long,
    ): Int? = if (now > deadline) RESULT_FAILED else null
}
