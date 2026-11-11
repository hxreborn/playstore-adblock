package eu.hxreborn.gplayadblock.settings.protocol

enum class RestartOutcome {
    Restarted,
    Busy,
    NoReply,
    Failed,
}

interface PlayStoreRestarter {
    suspend fun restart(clearStreamCache: Boolean): RestartOutcome
}

object NoOpPlayStoreRestarter : PlayStoreRestarter {
    override suspend fun restart(clearStreamCache: Boolean): RestartOutcome = RestartOutcome.Failed
}
