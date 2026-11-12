package eu.hxreborn.gplayadblock.settings

import android.app.Application
import eu.hxreborn.gplayadblock.settings.prefs.AppearanceRepository
import eu.hxreborn.gplayadblock.settings.prefs.FrameworkConnection
import eu.hxreborn.gplayadblock.settings.prefs.PrefsRepository
import eu.hxreborn.gplayadblock.settings.protocol.BroadcastPlayStoreRestarter
import eu.hxreborn.gplayadblock.settings.protocol.PlayStoreRestarter
import eu.hxreborn.gplayadblock.settings.protocol.RootPlayStoreRestarter
import io.github.libxposed.service.XposedServiceHelper

class GPlayAdblockApp : Application() {
    val frameworkConnection = FrameworkConnection()

    val broadcastPlayStoreRestarter: PlayStoreRestarter by lazy {
        BroadcastPlayStoreRestarter(this)
    }

    val rootPlayStoreRestarter: PlayStoreRestarter by lazy { RootPlayStoreRestarter(cacheDir) }

    val prefsRepository: PrefsRepository by lazy {
        PrefsRepository(
            desired = getSharedPreferences(PrefsRepository.DESIRED_FILE, MODE_PRIVATE),
            applied = getSharedPreferences(PrefsRepository.APPLIED_FILE, MODE_PRIVATE),
        )
    }

    val appearanceRepository: AppearanceRepository by lazy {
        AppearanceRepository(getSharedPreferences(AppearanceRepository.FILE, MODE_PRIVATE))
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(frameworkConnection)
    }
}
