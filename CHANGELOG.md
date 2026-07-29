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
