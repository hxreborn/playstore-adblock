#!/usr/bin/env bash
set -euo pipefail

VERSION="$1"
IFS='.' read -r MAJOR MINOR PATCH <<<"$VERSION"
CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

sed -i "s/^version\.name=.*/version.name=$VERSION/" gradle.properties
sed -i "s/^version\.code=.*/version.code=$CODE/" gradle.properties

python3 scripts/update_contributors.py

./gradlew :app:assembleCoreRelease -Pversion.name="$VERSION" -Pversion.code="$CODE"

mv app/build/outputs/apk/core/release/app-core-release.apk "playstore-adblock-v$VERSION-arm64-v8a.apk"
