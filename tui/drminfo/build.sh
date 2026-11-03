#!/usr/bin/env bash
# Rebuilds drminfo.jar (the setup app's Widevine reader) from DrmInfo.java. Run it after changing
# DrmInfo.java and commit both; the setup app embeds the jar, so building the app needs no
# Android SDK. Needs JDK 17 and the Android SDK (ANDROID_HOME, default ~/Library/Android/sdk).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$PLATFORM/android.jar" -d "$OUT/classes" DrmInfo.java
"$TOOLS/d8" --release --min-api 21 --output "$OUT/drminfo.jar" "$OUT"/classes/tvhome/*.class
cp "$OUT/drminfo.jar" drminfo.jar
echo "drminfo.jar: $(wc -c < drminfo.jar | tr -d ' ') bytes"
