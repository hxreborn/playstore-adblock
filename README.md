<h1 align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/hxreborn/playstore-adblock/master/assets/banner_dark.png">
    <img src="https://raw.githubusercontent.com/hxreborn/playstore-adblock/master/assets/banner_light.png" alt="Play Store Adblock">
  </picture>
</h1>

<p align="center">
  Xposed module that removes ads, sponsored apps, and promoted recommendations from the Google Play Store.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 11+">
  <img src="https://img.shields.io/badge/libxposed-API_101%2B-ff69b4?style=for-the-badge" alt="libxposed API 101+">
  <a href="https://t.me/PlayStoreAdblock"><img src="https://img.shields.io/badge/Updates-Telegram-26A5E4?style=for-the-badge&logo=telegram&logoColor=white" alt="Updates on Telegram"></a>
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

## Optional interface filters

The module settings also expose opt-in filters for Play Store interface elements. They can hide selected
home shelves, account-menu entries, management-page rows, persistent navigation tabs, Play Points and
notification indicators. A replacement start tab can be selected when its original tab is hidden.
The **Minimal Search home** option keeps the top search field and results routing intact while removing
the Search landing suggestions and the remaining bottom-navigation item.

These filters are disabled by default. Unlike the main ad filter, they depend on internal Play Store UI
models that Google can change without notice. Targets are resolved from code structure and stable string
fingerprints instead of release-specific obfuscated class names. If a fingerprint no longer matches, that
filter fails open and the original interface remains available rather than risking a Play Store crash.
UI entries are matched by model shape, semantic roles, product identifiers, and the resource IDs Play
Store pairs with their English labels, so the filters work in every Play Store language.

**Hide promoted search shelves** removes the collapsed promotional shelf shown among search results
(for example limited-time events). Play Store does not label it as an ad, so it has its own switch.

An English UI label the module cannot find at runtime is reported in the Xposed log. For a full DexKit
resolution on a Linux or WSL host, build the native library once with `tools/build_dexkit_host.sh`,
put release APKs at `analysis/release-fixtures/com.android.vending-<version>/base.apk` and run the
unit tests: every archived release is checked against its recorded fingerprints.

The start tab is applied once when Play Store is opened from the launcher; tabs chosen afterwards are
kept. If Play Store crashes repeatedly (three times within two minutes), the optional interface filters
are skipped on the following launches while ad filtering stays active. Updating Play Store or the module,
or using **Apply and restart Play Store**, re-enables them.

The compatibility section contains two additional controls:

- **Block Play Store self-updates** prevents `com.android.vending` from scheduling its own update. It does
  not block updates of other apps and is disabled by default.
- **Apply and restart Play Store** asks the active module inside Play Store to remove only its cached feed
  responses (`cache/streamdatastore`), terminate the process, discard its old task snapshot, and relaunch. Root is used
  only as a fallback. Accounts, installed-app records, preferences, the rest of the cache, and Play Store app data
  are not cleared.

Some filters require a restart before an already-created screen changes. Hiding a currently selected
persistent tab may still briefly expose cached content on unusual Play Store builds; the module switches to
the configured replacement tab synchronously and falls back to a main-thread callback if the direct switch
is rejected.

## Compatibility

| Play Store release | Version code | Status |
| --- | ---: | --- |
| 41.9.18-XX | 841918XX | unsupported |
| 42.9.17-XX | 842917XX | unsupported |
| 43.9.18-XX | 843918XX | supported |
| 44.9.20-XX | 844920XX | supported |
| 45.9.19-XX | 845919XX | supported |
| 46.9.20-XX | 846920XX | supported |
| 47.0.13-XX | 847013XX | supported |
| 47.6.28-XX | 847628XX | supported |
| 48.5.23-XX | 848523XX | supported |
| 49.9.19-XX | 849919XX | supported |
| 49.9.21-XX | 849921XX | untested |
| 50.1.33-XX | 850133XX | supported |
| 50.7.37-XX | 850737XX | supported |
| 51.0.19-XX | 851019XX | supported |
| 51.9.18-XX | 851918XX | supported |
| 52.2.25-XX | 852225XX | supported |
| 52.3.32-XX | 852332XX | supported |
| 52.4.41-XX | 852441XX | supported |
| 52.5.22-XX | 852522XX | supported |
| 52.6.26-XX | 852626XX | supported |
| 52.7.34-XX | 852734XX | supported |
| 52.8.55-XX | 852855XX | supported |
| 53.0.27-XX | 853027XX | supported |
| 53.3.21-XX | 853321XX | supported |
| 53.4.23-XX | 853423XX | supported |

Find your build either in Play Store → Settings → About or with:

```
adb shell dumpsys package com.android.vending | grep -E 'versionName|versionCode'
```

Builds not listed here are usually still compatible, since the module adapts to changes between
Play Store releases. If sponsored content appears, [open an issue](https://github.com/hxreborn/playstore-adblock/issues)
and include the version name and version code.

## Related

Also dislike ads in Google Discover, the launcher's -1 screen, or Google News? Try <a href="https://github.com/hxreborn/discover-ads-filter"><img src="https://raw.githubusercontent.com/hxreborn/playstore-adblock/master/assets/discover-ads-filter.png" height="16" alt=""> discover-ads-filter</a>, a sister module.

## License

[![GPL-3.0-only](https://img.shields.io/badge/LICENSE-GPL--3.0--only-%23A42E2B?style=for-the-badge&logo=gnu&logoColor=white&logoPosition=right)](https://github.com/hxreborn/playstore-adblock/blob/master/LICENSE)
