<h1 align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/hxreborn/playstore-adblock/master/assets/banner_dark.png">
    <img src="https://raw.githubusercontent.com/hxreborn/playstore-adblock/master/assets/banner_light.png" alt="Google Play Store Adblock">
  </picture>
</h1>

<p align="center">
  Xposed module that removes sponsored listings and ads from the Google Play Store.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 11+">
  <img src="https://img.shields.io/badge/libxposed-API_101%2B-ff69b4?style=for-the-badge" alt="libxposed API 101+">
</p>

## Screenshots

<div align="center">

<table>
  <tr>
    <th>Stock</th>
    <th>Patched</th>
  </tr>
  <tr>
    <td><img src="https://raw.githubusercontent.com/hxreborn/playstore-adblock/master/assets/before.png" width="250" alt="Stock: sponsored app above the real result"></td>
    <td><img src="https://raw.githubusercontent.com/hxreborn/playstore-adblock/master/assets/after.png" width="250" alt="Patched: sponsored app removed"></td>
  </tr>
</table>

</div>

## Requirements

- Android 11+ with an arm64-v8a device
- Xposed manager with libxposed API 101+, such as LSPosed

> [!WARNING]
> Hooking the Play Store can trip Google's device-certification or billing checks on devices without a working Play Integrity setup. This is inherent to modifying the Play Store.

## Install

1. Install APK from [Releases](https://github.com/hxreborn/playstore-adblock/releases)
2. Enable the module in your Xposed manager, scoped to Google Play Store (`com.android.vending`)
3. Force-stop the Play Store so it restarts with the module active

> [!NOTE]
> If you still see ads or an empty placeholder shelf, clear the Play Store cache, force-stop it, and relaunch. Play Store keeps serving cached ad responses until they refresh.

## Related

Also dislike ads in Google Discover, the launcher's -1 screen, or Google News? Try [discover-ads-filter](https://github.com/hxreborn/discover-ads-filter), a sister module.

## License

[![GPL-3.0-only](https://img.shields.io/badge/LICENSE-GPL--3.0--only-%23A42E2B?style=for-the-badge&logo=gnu&logoColor=white&logoPosition=right)](https://github.com/hxreborn/playstore-adblock/blob/master/LICENSE)
