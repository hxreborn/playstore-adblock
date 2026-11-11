package eu.hxreborn.gplayadblock.settings.ui

import android.app.Application
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.hxreborn.gplayadblock.settings.contributors.parseContributorEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class Contributor(
    val login: String,
    val added: Int,
    val removed: Int,
    val url: String,
    val avatar: ImageBitmap?,
    val isOwner: Boolean,
)

@Immutable
data class AboutInfo(
    val versionName: String,
    val versionCode: Long,
    val icon: ImageBitmap?,
    val contributors: List<Contributor>,
)

class AboutViewModel(
    application: Application,
) : ViewModel() {
    private val loaded = MutableStateFlow<AboutInfo?>(null)
    val about: StateFlow<AboutInfo?> = loaded.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { loaded.value = load(application) }
    }

    companion object {
        private const val TAG = "GPlayAdblock"
        private const val SNAPSHOT_ASSET = "contributors.json"
        private const val ICON_PIXELS = 432

        val Factory =
            viewModelFactory {
                initializer { AboutViewModel(this[APPLICATION_KEY] as Application) }
            }

        private fun load(application: Application): AboutInfo {
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            return AboutInfo(
                versionName = packageInfo.versionName.orEmpty(),
                versionCode = packageInfo.longVersionCode,
                icon =
                    runCatching { launcherArt(application) }
                        .onFailure { Log.w(TAG, "launcher icon read failed", it) }
                        .getOrNull(),
                contributors =
                    runCatching { contributors(application.assets) }
                        .onFailure { Log.w(TAG, "contributors snapshot read failed", it) }
                        .getOrDefault(emptyList()),
            )
        }

        private fun launcherArt(application: Application): ImageBitmap? {
            val icon =
                application.packageManager.getApplicationIcon(application.applicationInfo)
                    as? AdaptiveIconDrawable ?: return null
            val bitmap = Bitmap.createBitmap(ICON_PIXELS, ICON_PIXELS, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            listOfNotNull<Drawable>(icon.background, icon.foreground).forEach { layer ->
                layer.setBounds(0, 0, ICON_PIXELS, ICON_PIXELS)
                layer.draw(canvas)
            }
            return bitmap.asImageBitmap()
        }

        private fun contributors(assets: AssetManager): List<Contributor> {
            val json = assets.open(SNAPSHOT_ASSET).bufferedReader().use { it.readText() }
            return parseContributorEntries(json).map { entry ->
                Contributor(
                    login = entry.login,
                    added = entry.added,
                    removed = entry.removed,
                    url = entry.url,
                    avatar =
                        runCatching {
                            assets
                                .open(
                                    entry.avatar,
                                ).use(BitmapFactory::decodeStream)
                                ?.asImageBitmap()
                        }.onFailure { Log.w(TAG, "avatar read failed login=${entry.login}", it) }
                            .getOrNull(),
                    isOwner = entry.owner,
                )
            }
        }
    }
}
