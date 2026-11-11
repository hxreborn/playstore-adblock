package eu.hxreborn.gplayadblock

object RestartProtocol {
    const val ACTION = "eu.hxreborn.gplayadblock.action.RESTART_PLAY_STORE"
    const val PERMISSION = "eu.hxreborn.gplayadblock.permission.RESTART_PLAY_STORE"
    const val EXTRA_CALLBACK = "restart_callback"
    const val RESULT_OK = 1
    const val RESULT_FAILED = 0

    /** Play Store is installing apps; restarting now would interrupt the installation. */
    const val RESULT_BUSY = 2

    /**
     * Play Store's cached feed responses, relative to its cache directory. The only cache content
     * a restart removes: the rest holds sign-in and install state.
     */
    const val STREAM_DATA_STORE_DIR = "streamdatastore"

    /** Play Store activities of a purchase flow; a restart must not interrupt them. */
    const val PURCHASE_ACTIVITY_PACKAGE = "com.google.android.finsky.billing."

    /** Crash-loop guard state, relative to Play Store's data directory. */
    const val CRASH_GUARD_PATH = "files/playstore-adblock/optional-crash-guard"
}
