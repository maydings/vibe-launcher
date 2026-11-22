#!/usr/bin/env bash
# Builds Vibe Launcher from source and installs it on a TV over adb, for working on it.
#
#   ./build-and-install.sh <tv-ip>        (or TV_HOST=<tv-ip> ./build-and-install.sh)
#
# Making it Home, debloating and the rest are done on the TV, in Vibe Launcher's own Settings.
set -uo pipefail
cd "$(dirname "$0")"

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

HOST=${1:-${TV_HOST:-}}
[ -n "$HOST" ] || die "usage: ./build-and-install.sh <tv-ip>"
case "$HOST" in *:*) TV=$HOST ;; *) TV=$HOST:5555 ;; esac
command -v adb >/dev/null 2>&1 || die "adb not found: install Android's platform-tools"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

say "Building..."
./gradlew assembleDebug -q --console=plain || die "build failed"
APK=app/build/outputs/apk/debug/app-debug.apk

say "Connecting to $TV..."
adb connect "$TV" >/dev/null 2>&1
for i in $(seq 1 30); do
  state=$(adb -s "$TV" get-state 2>&1 | tr -d '\r')
  case "$state" in
    device) break ;;
    *unauthorized*) [ "$i" = 1 ] && say "Allow the debugging prompt on the TV (tick Always allow)."; sleep 2 ;;
    *) [ "$i" -ge 3 ] && die "can't reach $TV: is the TV on, on this network, with debugging turned on?"
       adb connect "$TV" >/dev/null 2>&1; sleep 1 ;;
  esac
done

say "Installing..."
RESULT=$(adb -s "$TV" install -r -g "$APK" 2>&1 | tr -d '\r' | tail -n 1)
[[ "$RESULT" == *Success* ]] || die "install failed: $RESULT (a release build installed? it's signed with another key: uninstall it first)"
adb -s "$TV" shell am force-stop com.maydings.vibelauncher
adb -s "$TV" shell am start -n com.maydings.vibelauncher/.MainActivity >/dev/null
say "Installed and started."
