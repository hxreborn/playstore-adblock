#!/usr/bin/env bash
set -euo pipefail

VERSION="$1"
IFS='.' read -r MAJOR MINOR PATCH <<<"$VERSION"
CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

sed -i "s/^version\.name=.*/version.name=$VERSION/" gradle.properties
sed -i "s/^version\.code=.*/version.code=$CODE/" gradle.properties

python3 scripts/update_contributors.py

rm -rf app/build/outputs/apk app/build/outputs/mapping
rm -f playstore-adblock-v*.apk playstore-adblock-v*-SHA256SUMS.txt
./gradlew --no-daemon :app:ktlintCheck :settings:ktlintCheck \
  :app:testCoreDebugUnitTest :app:testFullDebugUnitTest :settings:testDebugUnitTest \
  :app:lintCoreRelease :app:lintFullRelease :app:assembleCoreRelease :app:assembleFullRelease \
  -Pversion.name="$VERSION" -Pversion.code="$CODE"
(cd tools && python3 -m unittest discover -p 'test_*.py')
CHECK_DIRECTORY=$(mktemp -d)
trap 'rm -rf "$CHECK_DIRECTORY"' EXIT
curl -fsSL -o "$CHECK_DIRECTORY/mirror-README.md" \
  https://raw.githubusercontent.com/Xposed-Modules-Repo/eu.hxreborn.gplayadblock/main/README.md
python3 tools/check_compat_tables.py play-store-compatibility.yaml README.md "$CHECK_DIRECTORY/mirror-README.md"

BUILD_TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
ASSETS=()
for FLAVOR in core full; do
  APK="app/build/outputs/apk/$FLAVOR/release/app-$FLAVOR-release.apk"
  [[ -f "$APK" ]] || { echo "missing signed $FLAVOR release APK at $APK" >&2; exit 1; }
  python3 tools/check_module_apk.py --flavor "$FLAVOR" --release \
    --mapping "app/build/outputs/mapping/${FLAVOR}Release/mapping.txt" "$APK"
  ASSET="playstore-adblock-v$VERSION-$FLAVOR-arm64-v8a.apk"
  cp "$APK" "$ASSET"
  ASSETS+=("$ASSET")
done

sha256sum "${ASSETS[@]}" > "playstore-adblock-v$VERSION-SHA256SUMS.txt"
python3 tools/check_release_assets.py verify "$VERSION" "$CODE" . --build-tools "$BUILD_TOOLS"
