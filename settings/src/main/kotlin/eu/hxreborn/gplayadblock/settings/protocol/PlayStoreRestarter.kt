package eu.hxreborn.gplayadblock.settings.protocol

enum class RestartOutcome {
    Restarted,
    RestartedCachePending,
    Busy,
    NoReply,
    Failed,
    RootUnavailable,
}

interface PlayStoreRestarter {
    suspend fun restart(
        clearStreamCache: Boolean,
        onExited: () -> Unit,
    ): RestartOutcome
}
