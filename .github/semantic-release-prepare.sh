#!/usr/bin/env bash
set -euo pipefail

VERSION="$1"
IFS='.' read -r MAJOR MINOR PATCH <<<"$VERSION"
CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

sed -i "s/^version\.name=.*/version.name=$VERSION/" gradle.properties
sed -i "s/^version\.code=.*/version.code=$CODE/" gradle.properties

./gradlew assembleRelease -Pversion.name="$VERSION" -Pversion.code="$CODE"

APK=$(find app/build/outputs/apk/release -name '*.apk' | head -1)
mv "$APK" "playstore-adblock-v$VERSION-arm64-v8a.apk"
