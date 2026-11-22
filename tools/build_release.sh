#!/usr/bin/env bash
# Build the signed release APK that is uploaded to Myket.
#
# Needs JDK 17 and an Android SDK with platform 35 + build-tools 35 installed:
#   ANDROID_HOME=/path/to/android-sdk bash tools/build_release.sh
#
# The signing key comes from GB_KEYSTORE_PATH / GB_STORE_PASSWORD / GB_KEY_ALIAS /
# GB_KEY_PASSWORD when set, otherwise the keystore in app/ (see RELEASE.md).
set -euo pipefail

cd "$(dirname "$0")/.."

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
  echo "ANDROID_HOME (or ANDROID_SDK_ROOT) must point at an installed Android SDK." >&2
  exit 1
fi

GRADLE="${GRADLE_BIN:-gradle}"
VERSION="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"

echo "Building FLASH BOOSTER $VERSION (release)"
"$GRADLE" --no-daemon --console=plain :app:assembleRelease

APK="$(find app/build/outputs/apk/release -maxdepth 1 -name '*.apk' -print -quit)"
[ -n "$APK" ] || { echo "No release APK was produced." >&2; exit 1; }

mkdir -p dist
DIST="dist/flash-booster-$VERSION.apk"
cp "$APK" "$DIST"

if [ -x "$APKSIGNER" ]; then
  "$APKSIGNER" verify --print-certs "$DIST"
else
  echo "apksigner not found at $APKSIGNER; skipped signature verification." >&2
fi

echo
echo "Store package ready: $DIST"
