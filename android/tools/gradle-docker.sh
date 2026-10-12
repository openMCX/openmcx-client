#!/usr/bin/env bash
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

# Run the Gradle wrapper inside the Android build image; nothing is installed on the host.
# Usage: tools/gradle-docker.sh :core:test :app:assembleDebug ...
# The Gradle cache lives in the Docker volume mcx-android-gradle. The host debug keystore,
# if present, is copied in so debug APKs keep the signature of builds already on the phones.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
image="${MCX_ANDROID_BUILD_IMAGE:-mcx-android-build:jdk17-sdk37}"
docker image inspect "$image" >/dev/null 2>&1 || docker build -t "$image" "$here/tools/build-image"
docker volume create mcx-android-gradle >/dev/null
keystore=()
[ -f "$HOME/.android/debug.keystore" ] && keystore=(-v "$HOME/.android/debug.keystore:/host-debug.keystore:ro")
exec docker run --rm -u "$(id -u):$(id -g)" -e HOME=/home/builder -e ANDROID_USER_HOME=/home/builder/.android \
  -e GRADLE_USER_HOME=/gradle -v mcx-android-gradle:/gradle -v "$here:$here" -w "$here" "${keystore[@]}" \
  "$image" sh -c 'mkdir -p "$ANDROID_USER_HOME" && { [ ! -f /host-debug.keystore ] || cp /host-debug.keystore "$ANDROID_USER_HOME/debug.keystore"; } && exec ./gradlew "$@"' gradle "$@"
