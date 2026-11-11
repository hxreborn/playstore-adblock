package eu.hxreborn.gplayadblock.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import eu.hxreborn.gplayadblock.settings.ui.SettingsApp
import eu.hxreborn.gplayadblock.settings.ui.SettingsUiState
import eu.hxreborn.gplayadblock.settings.ui.SettingsViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels { SettingsViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.uiState.value is SettingsUiState.Loading }
        setContent { SettingsApp(viewModel) }
    }
}
