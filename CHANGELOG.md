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
