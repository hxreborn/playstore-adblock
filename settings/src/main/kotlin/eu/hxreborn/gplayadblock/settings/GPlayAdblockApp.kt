package eu.hxreborn.gplayadblock.settings

import android.app.Application
import eu.hxreborn.gplayadblock.settings.prefs.FrameworkConnection
import eu.hxreborn.gplayadblock.settings.prefs.PrefsRepository
import io.github.libxposed.service.XposedServiceHelper

class GPlayAdblockApp : Application() {
    val frameworkConnection = FrameworkConnection()

    val prefsRepository: PrefsRepository by lazy {
        PrefsRepository(
            desired = getSharedPreferences(PrefsRepository.DESIRED_FILE, MODE_PRIVATE),
            applied = getSharedPreferences(PrefsRepository.APPLIED_FILE, MODE_PRIVATE),
        )
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(frameworkConnection)
    }
}
