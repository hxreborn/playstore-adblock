## [2.0.0-beta.1](https://github.com/hxreborn/playstore-adblock/compare/v1.6.1...v2.0.0-beta.1) (2026-11-12)

### ⚠ BREAKING CHANGES

* ship core and full downloads in place of the single APK

### Features

* add optional Play Store UI filters with a settings screen ([9458035](https://github.com/hxreborn/playstore-adblock/commit/94580358ada85ddd38fd96f8575defb5ef3f33ec))
* **prefs:** save optional Play Store settings ([2445c95](https://github.com/hxreborn/playstore-adblock/commit/2445c9560a84ae79b3eeb6bd536dbf1c68199aad))
* ship core and full downloads in place of the single APK ([8f3cae6](https://github.com/hxreborn/playstore-adblock/commit/8f3cae6e64c7881fd8b60cb20ca7080890321e94))
* **ui:** add a Telegram row and the GitHub mark to About ([a945406](https://github.com/hxreborn/playstore-adblock/commit/a94540646fe977d15d00548678bad95d032d80fb))
* **ui:** add about screen with contributors ([4c1598e](https://github.com/hxreborn/playstore-adblock/commit/4c1598e85bb211129303fdba8e23d27a4999f4e4))
* **ui:** add launcher icon visibility setting ([50cd361](https://github.com/hxreborn/playstore-adblock/commit/50cd361f9d626c1a652f3aea0bd0197310c08fb4))
* **ui:** add optional filter settings ([c3e45a4](https://github.com/hxreborn/playstore-adblock/commit/c3e45a4bb87b97055acc01d754cf6f98e1dfac78))
* **ui:** replace the Apply bar with a standard bottom sheet ([e2e3de2](https://github.com/hxreborn/playstore-adblock/commit/e2e3de2192a9e864daac0106910fffab5dbc6249))
* **ui:** restart Play Store from the module or with root ([187edb1](https://github.com/hxreborn/playstore-adblock/commit/187edb11ce2dcc10fd67c01b9154cf07daee9437))
* **ui:** show sponsored listing removal as always on in Home ([6b3613f](https://github.com/hxreborn/playstore-adblock/commit/6b3613f6ad8b2cd3c8c5d77f8ae6ab55fbf66f1a))

### Bug Fixes

* **ui:** describe an unconfirmed cache clear as pending ([c90c17c](https://github.com/hxreborn/playstore-adblock/commit/c90c17c3afc9f539d42b67d29b1af8f24c10c0f7))
* **ui:** distinguish root denial from failed restart commands ([35e9510](https://github.com/hxreborn/playstore-adblock/commit/35e9510350b5df9bf2d0ef768fa7ce639e1cf580))
* **ui:** keep applied settings consistent across framework rebinds ([708a56d](https://github.com/hxreborn/playstore-adblock/commit/708a56dbbb7538bdd35b5e78c077021a2f478db7))
* **ui:** keep restart recovery actions reachable with large text ([85eac45](https://github.com/hxreborn/playstore-adblock/commit/85eac457053a88c4c62d85a01de2fe5f4274a72c))
* **ui:** log failures when opening Play Store ([1d36a15](https://github.com/hxreborn/playstore-adblock/commit/1d36a15be7b46181ac6e705c94ce24b106483fb1))
* **ui:** report failed install checks as restart failures ([f09453a](https://github.com/hxreborn/playstore-adblock/commit/f09453af321f942be627fc52ba036b265a0de0d2))
* **ui:** report settings failures separately from restart failures ([4741d5b](https://github.com/hxreborn/playstore-adblock/commit/4741d5b5ee711755cdd7dc24890fbface0033762))
* **ui:** retain sheet content through Compose state ([85fdce3](https://github.com/hxreborn/playstore-adblock/commit/85fdce3afff645ef21744a441b7abf6dc8350676))
* **ui:** translate the remaining settings screens into Russian ([f74f227](https://github.com/hxreborn/playstore-adblock/commit/f74f227bbb83365848df50f03d1cad1179ed54c8))
* **ui:** use the system motion setting on the first frame ([a35e9ba](https://github.com/hxreborn/playstore-adblock/commit/a35e9ba3bad2cafed1d4cd5686a03f831d743733))

### Refactor

* isolate optional Play Store filters ([2226e0f](https://github.com/hxreborn/playstore-adblock/commit/2226e0f4bdb77f9e49519b7ba29ebfc33adceb26))
* **ui:** name restart results and tests by their behavior ([602cd45](https://github.com/hxreborn/playstore-adblock/commit/602cd450793d7bde2399ec61594ea639becf7221))


---

📣 [Telegram Updates channel](https://t.me/PlayStoreAdblock) for new Play Store version checks and release notices.

## [1.6.1](https://github.com/hxreborn/playstore-adblock/compare/v1.6.0...v1.6.1) (2026-11-07)

### Bug Fixes

* **hook:** start filtering with restricted package visibility ([02066db](https://github.com/hxreborn/playstore-adblock/commit/02066db75519d9ff101e2be0864878478282ac20)), closes [#35](https://github.com/hxreborn/playstore-adblock/issues/35)


---

📣 [Telegram Updates channel](https://t.me/PlayStoreAdblock) for new Play Store version checks and release notices.

## [1.6.0](https://github.com/hxreborn/playstore-adblock/compare/v1.5.0...v1.6.0) (2026-11-02)

### Features

* **hook:** remove the promotional banner at the top of the Apps tab ([42b0f49](https://github.com/hxreborn/playstore-adblock/commit/42b0f495f19016e48939b0996f793d5afc8bba8e))

### Reverts

* **deps:** pin io.github.libxposed:api to 101.0.1 ([4a27ad3](https://github.com/hxreborn/playstore-adblock/commit/4a27ad3153ab3afefcf6c5ac6e96b405605115ef))


---

📣 [Telegram Updates channel](https://t.me/PlayStoreAdblock) for new Play Store version checks and release notices.

## [1.5.0](https://github.com/hxreborn/playstore-adblock/compare/v1.4.0...v1.5.0) (2026-08-23)

### Features

* **compat:** support Play Store 52.8.55 ([4d4e186](https://github.com/hxreborn/playstore-adblock/commit/4d4e18664dcc6cbe9605452d99e490373617296b))

### Bug Fixes

* **discovery:** refuse the graph hook when its arguments move ([be6b2bd](https://github.com/hxreborn/playstore-adblock/commit/be6b2bdc8f0d857de841947b6a3466ce0f70e855))
* **discovery:** resolve stream targets when parameter types are erased ([e077b7c](https://github.com/hxreborn/playstore-adblock/commit/e077b7c7cb8a3221202649acd288bd17df8580e6))
* **hook:** look up the stream data constructor by its declared signature ([7d9b61c](https://github.com/hxreborn/playstore-adblock/commit/7d9b61c7ac22f762843736e2a292d7c26f9df08e))

### Refactor

* **test:** extract the sample target set into a shared fixture ([c2420f0](https://github.com/hxreborn/playstore-adblock/commit/c2420f057f538f46f55c5c68fb2395a8c19c6687))


---

📣 [Telegram Updates channel](https://t.me/PlayStoreAdblock) for new Play Store version checks and release notices.

## [1.4.0](https://github.com/hxreborn/playstore-adblock/compare/v1.3.1...v1.4.0) (2026-08-11)

### Features

* **compat:** support Play Store 52.6.26 ([d2213e2](https://github.com/hxreborn/playstore-adblock/commit/d2213e28744783d59fe1fc6804ffc251797358e4))

### Bug Fixes

* **hook:** keep the original page when sponsored content cannot be fully removed ([98da285](https://github.com/hxreborn/playstore-adblock/commit/98da2854215e69a325841589a6aa406389d8317e))
* **hook:** remove sponsored cards in result lists that were previously skipped ([1b8d179](https://github.com/hxreborn/playstore-adblock/commit/1b8d17949e9037e32ed8c38bbe24abff03e09046))
* **hook:** remove sponsored shelves no other shelf links to ([7274dd5](https://github.com/hxreborn/playstore-adblock/commit/7274dd59e490af9ce3133e7e48c08dd6aa4121de))

### Refactor

* drop the validated release list ([493eaf2](https://github.com/hxreborn/playstore-adblock/commit/493eaf2c33a89da0a0eb2d11fadafe3ecd4b3185))
* **hook:** locate cache filter arguments by type instead of fixed position ([04cbf88](https://github.com/hxreborn/playstore-adblock/commit/04cbf88774cc459fa0b5f8b4843c670bde2c3c31))


---

📣 [Telegram Updates channel](https://t.me/PlayStoreAdblock) for new Play Store version checks and release notices.

## [1.3.1](https://github.com/hxreborn/playstore-adblock/compare/v1.3.0...v1.3.1) (2026-07-29)

### Bug Fixes

* **hook:** skip cache filtering on builds it cannot handle safely ([1934dd4](https://github.com/hxreborn/playstore-adblock/commit/1934dd4a37002a0cb3b4d54f59d17e6f3a1b891b))
* **hook:** stop breaking Play Store when filtering a cached page ([98ac065](https://github.com/hxreborn/playstore-adblock/commit/98ac065e97645aadbdfca4607bcd3e8502c57eb8))


---

📣 [Telegram Updates channel](https://t.me/PlayStoreAdblock) for new Play Store version checks and release notices.

## [1.3.0](https://github.com/hxreborn/playstore-adblock/compare/v1.2.0...v1.3.0) (2026-07-29)

### Features

* **compat:** publish per-release compatibility metadata ([f1df2e6](https://github.com/hxreborn/playstore-adblock/commit/f1df2e6599d31780e992463463887fc50ba7ee0f))
* **compat:** report new Play Store releases and whether discovery still resolves ([19eb6ca](https://github.com/hxreborn/playstore-adblock/commit/19eb6ca4654cecb08fe3d51e338d40d54f82fd46))
* **compat:** support Play Store 43.9.18 ([525f33f](https://github.com/hxreborn/playstore-adblock/commit/525f33fe2c7de021814633e5d7b88c038086dc2a))
* **compat:** support Play Store 44.9.20 ([2228613](https://github.com/hxreborn/playstore-adblock/commit/2228613f2fc39a63d20f5d0228a68823b6968a89))
* **compat:** support Play Store 45.9.19 ([b37360e](https://github.com/hxreborn/playstore-adblock/commit/b37360ec89e4f9aed52fa5c1a40cc1ca460ae571))
* **compat:** support Play Store 46.9.20 ([d1b622d](https://github.com/hxreborn/playstore-adblock/commit/d1b622dfcf676970bd2d9ae8058e1d1f279f1d99))
* **compat:** support Play Store 47.0.13 ([e99cdc7](https://github.com/hxreborn/playstore-adblock/commit/e99cdc73f4e576742f536cf89b9901fd7dfc86d5))
* **compat:** support Play Store 48.5.23 ([b87fe8d](https://github.com/hxreborn/playstore-adblock/commit/b87fe8db637c8a8459aa71ba69372404997d5319))
* **compat:** support Play Store 49.9.19 ([719ca62](https://github.com/hxreborn/playstore-adblock/commit/719ca629a79ed9fea30ffa6ce7308e2570514736))
* **compat:** support Play Store 50.1.33 ([e36bf60](https://github.com/hxreborn/playstore-adblock/commit/e36bf607c5292e11dfa0250f498f5630be72f0a8))
* **compat:** support Play Store 50.7.37 ([9082080](https://github.com/hxreborn/playstore-adblock/commit/90820807ab5b3d3d85645c219d8638e4d9c3f419))
* **compat:** support Play Store 51.0.19 ([f140cd5](https://github.com/hxreborn/playstore-adblock/commit/f140cd5321b3a8f855f547b8ae9b2c519d7a5895))
* **compat:** support Play Store 51.9.18 ([a584d8b](https://github.com/hxreborn/playstore-adblock/commit/a584d8b517f90a1f99b0a5387c5d8b83d2fd1b04))

### Bug Fixes

* **discovery:** drop the ad presentation anchor absent before Play Store 49.x ([0e9f6d3](https://github.com/hxreborn/playstore-adblock/commit/0e9f6d356c4f9157fe3a05439c5b16f3ab5831ce))
* **discovery:** drop the co-located anchor assumption for the stream handler ([c1031b6](https://github.com/hxreborn/playstore-adblock/commit/c1031b62077a9f88628523bb83a046ab57799d50))
* **discovery:** fall back to presentation kind readers when R8 unboxes the enum ([5e0327c](https://github.com/hxreborn/playstore-adblock/commit/5e0327c2086a042f4e2905e0b4adb3adb30409a9))
* **discovery:** select the cache assembly method by its return type shape ([8254dda](https://github.com/hxreborn/playstore-adblock/commit/8254ddad419e8993dcd239dd8e621dac722533eb))
* **discovery:** stop caching recoverable resolution failures ([5be5462](https://github.com/hxreborn/playstore-adblock/commit/5be546250eb689c41444d96df1ff21a396aaca8c))

### Refactor

* **compat:** track validated releases by key rather than version code ([3b45801](https://github.com/hxreborn/playstore-adblock/commit/3b45801f85b4225feae46553d6dac664f4fd641f))
* **discovery:** extract stream chain generic card and cluster case stages ([4577493](https://github.com/hxreborn/playstore-adblock/commit/457749323b27656d6072c7866dd20bbc847a9343))

## [1.2.0](https://github.com/hxreborn/playstore-adblock/compare/v1.1.0...v1.2.0) (2026-07-28)

### Features

* **compat:** add support for Play Store 52.4.41 (85244140) ([4ace3e1](https://github.com/hxreborn/playstore-adblock/commit/4ace3e190c935c79f7803c873d204614e46db593))

### Bug Fixes

* **compat:** match suggestion constructor by the fields it writes ([6f4c02a](https://github.com/hxreborn/playstore-adblock/commit/6f4c02a71686fa184ad8b35691601a02628bba64))
* **discovery:** match cache and stream targets by structure ([4e23162](https://github.com/hxreborn/playstore-adblock/commit/4e231627634b6787b29d39045480e7e821eb3563))

### Refactor

* **discovery:** let supplementary targets degrade instead of failing the whole set ([9b91d0c](https://github.com/hxreborn/playstore-adblock/commit/9b91d0c79b6e13bad4c42adff5a0cf7976dc6c70))

### Build System

* **deps:** bump com.android.application in the gradle group ([#5](https://github.com/hxreborn/playstore-adblock/issues/5)) ([fca9c78](https://github.com/hxreborn/playstore-adblock/commit/fca9c780e90eafec1b4f0d71ad12c1bddd13bf43))
* use libxposed 101 to clear API warning ([c0392f1](https://github.com/hxreborn/playstore-adblock/commit/c0392f18b53d4a6e19032336574a1095841ca5a3))

## [1.1.0](https://github.com/hxreborn/playstore-adblock/compare/v1.0.0...v1.1.0) (2026-07-22)

### Features

* show toast when Play Store version is unsupported ([0bc4794](https://github.com/hxreborn/playstore-adblock/commit/0bc4794ace073120242e9753a7f890e7f4427ad1))

### Bug Fixes

* byte-scan blob for sponsored markers ([a10ca0a](https://github.com/hxreborn/playstore-adblock/commit/a10ca0a04e11690324ff2ccef79f1c842ecb3ceb))
* run sponsored checks on more shelf types ([6aa12ac](https://github.com/hxreborn/playstore-adblock/commit/6aa12ac757154a52beb8881fd54c7acf53ba14d7))
* avoid injection in Google Play subprocesses ([5353a2b](https://github.com/hxreborn/playstore-adblock/commit/5353a2b683b95f1351458c51ae33f421d7cb9a15))
* **dexkit:** resolve protobuf newBuilder ambiguity ([6591c3d](https://github.com/hxreborn/playstore-adblock/commit/6591c3d9912d8a733094214da56968fecc624691))

### Refactor

* **dexkit:** extract protobuf/suggestion resolvers ([aa5dbd2](https://github.com/hxreborn/playstore-adblock/commit/aa5dbd240192cdda9e95f50f0ad2cd87e11a285c))
* centralize logging in a Logger object ([caa92b6](https://github.com/hxreborn/playstore-adblock/commit/caa92b6bf88ff584d70ce2dd8512ce0c91432777))
* **hook:** dedupe classifier and editor wiring ([85dab6c](https://github.com/hxreborn/playstore-adblock/commit/85dab6cb5ec9601e594e8d8d904d47707901fdd4))

## 1.0.0 (2026-07-20)

### Features

* add Play Store sponsored listing filters ([c6d255b](https://github.com/hxreborn/playstore-adblock/commit/c6d255b5d90655d4800eb676bfe63fb84be061c1))
