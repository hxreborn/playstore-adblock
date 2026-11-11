package eu.hxreborn.gplayadblock.settings.ui.component

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

private const val LAUNCHER_ALIAS = "LauncherAlias"

private fun launcherAlias(context: Context) =
    ComponentName(context.packageName, "${context.packageName}.$LAUNCHER_ALIAS")

fun isLauncherIconVisible(context: Context): Boolean =
    context.packageManager.getComponentEnabledSetting(launcherAlias(context)) !=
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED

fun setLauncherIconVisible(
    context: Context,
    visible: Boolean,
) {
    context.packageManager.setComponentEnabledSetting(
        launcherAlias(context),
        if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        },
        PackageManager.DONT_KILL_APP,
    )
}

@Composable
fun rememberLauncherIconVisible(): MutableState<Boolean> {
    val context = LocalContext.current
    val visible = remember { mutableStateOf(true) }
    LifecycleResumeEffect(context) {
        visible.value = isLauncherIconVisible(context)
        onPauseOrDispose {}
    }
    return visible
}
