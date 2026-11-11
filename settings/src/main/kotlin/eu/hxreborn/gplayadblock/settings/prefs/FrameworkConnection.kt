package eu.hxreborn.gplayadblock.settings.prefs

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class FrameworkInfo(
    val name: String,
    val version: String,
    val apiVersion: Int,
) {
    val versionName: String
        get() = version.trim().substringBefore(' ')
}

class FrameworkConnection(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : XposedServiceHelper.OnServiceListener {
    private class Session(
        val service: Any,
        val openRemoteEditor: () -> SharedPreferences.Editor,
    )

    private val session = MutableStateFlow<Session?>(null)
    private val connectedFramework = MutableStateFlow<FrameworkInfo?>(null)
    private val bindCount = MutableStateFlow(0)
    private val publishLock = Mutex()
    private val connectionLock = Any()

    val framework: StateFlow<FrameworkInfo?> = connectedFramework.asStateFlow()
    val binds: StateFlow<Int> = bindCount.asStateFlow()

    override fun onServiceBind(service: XposedService) {
        val info =
            try {
                FrameworkInfo(service.frameworkName, service.frameworkVersion, service.apiVersion)
            } catch (exception: RuntimeException) {
                null
            }
        attach(service, info) { service.getRemotePreferences(SettingsKeys.GROUP).edit() }
    }

    override fun onServiceDied(service: XposedService) {
        detach(service)
    }

    internal fun attach(
        service: Any,
        info: FrameworkInfo?,
        openRemoteEditor: () -> SharedPreferences.Editor,
    ) {
        synchronized(connectionLock) {
            session.value = Session(service, openRemoteEditor)
            connectedFramework.value = info
            bindCount.update { it + 1 }
        }
    }

    internal fun detach(service: Any) {
        synchronized(connectionLock) {
            val current = session.value ?: return
            if (current.service !== service) return
            session.value = null
            connectedFramework.value = null
        }
    }

    suspend fun publish(settings: FilterSettings): Boolean =
        publishLock.withLock {
            withContext(ioDispatcher) {
                val current = session.value ?: return@withContext false
                try {
                    SettingsKeys.write(settings, current.openRemoteEditor()).commit()
                } catch (exception: RuntimeException) {
                    false
                }
            }
        }
}
