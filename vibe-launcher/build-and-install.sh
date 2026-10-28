#!/usr/bin/env bash
# Build the launcher from source and install it on a TV as the home screen.
#
#   ./build-and-install.sh <tv-ip>        (or TV_HOST=<tv-ip> ./build-and-install.sh)
#
# Connects with adb, or with the Python client in tools/ where macOS blocks adb (tools/lib.sh). Pass
# STOCK_LAUNCHER=<package> to name the launcher to disable yourself, or STOCK_LAUNCHER=none to disable
# nothing.
#
# FLAVOR=store installs the app store build instead, to try it as a store user would get it: it leaves the
# stock launcher and Home alone, and its Home button is turned on in the TV's Settings > Accessibility.
set -uo pipefail
cd "$(dirname "$0")"
. tools/lib.sh
HOST=${1:-${TV_HOST:-}}
[ -n "$HOST" ] || die "usage: ./build-and-install.sh <tv-ip>"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
FLAVOR=${FLAVOR:-adb}
case "$FLAVOR" in adb|store) ;; *) die "FLAVOR is adb or store, not '$FLAVOR'" ;; esac
CAP=$(printf '%s' "${FLAVOR:0:1}" | tr a-z A-Z)${FLAVOR:1}
step "Building ($FLAVOR)"
./gradlew "assemble${CAP}Debug" -q --console=plain || die "build failed"
APK=app/build/outputs/apk/$FLAVOR/debug/app-$FLAVOR-debug.apk

connect "$HOST"

if [ "$FLAVOR" = store ]; then
  step "Installing"
  RESULT=$(tv_install "$APK")
  [[ "$RESULT" == *Success* ]] || die "install failed: $RESULT (the adb build installed? uninstall it first if it was signed with another key)"
  say "Installed the store build. Home and the stock launcher are as they were: on the TV, open Settings >"
  say "Accessibility and turn on \"Vibe Launcher Home button\" (the gear menu's Make Home row goes there)."
  exit 0
fi

# Which app owns Home differs by build (com.google.android.tvlauncher on Android TV,
# com.google.android.apps.tv.launcherx on newer Google TV), so ask the TV instead of guessing. Only a
# launcher we recognise is disabled: the answer can also be Android's chooser ("android"), the setup
# wizard, or another third-party launcher, none of which should be disabled by accident.
if [ -z "${STOCK_LAUNCHER:-}" ]; then
  HOME_PKG=$(home_app | cut -d/ -f1)
  case " $STOCK_LAUNCHERS " in
    *" $HOME_PKG "*) STOCK_LAUNCHER=$HOME_PKG ;;
    *)
      if [ "$HOME_PKG" = "$LAUNCHER" ]; then
        STOCK_LAUNCHER=none; say "Home already belongs to Vibe Launcher; nothing will be disabled."
      elif [ "$HOME_PKG" = android ]; then
        # Android's chooser: two or more launchers tie and no default is set. Take the one known stock
        # launcher among the apps claiming Home, if there is exactly one.
        CLAIMANTS=$(tv "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME" \
          | grep -o '[A-Za-z0-9_.]*/' | tr -d / | sort -u)
        FOUND=""; N=0
        for k in $STOCK_LAUNCHERS; do
          if grep -qx "$k" <<< "$CLAIMANTS"; then FOUND=$k; N=$((N + 1)); fi
        done
        [ "$N" -eq 1 ] || die "Home is Android's launcher chooser and $N known stock launchers claim it: pick a default on the TV first, or run again with STOCK_LAUNCHER=<package> or STOCK_LAUNCHER=none."
        STOCK_LAUNCHER=$FOUND; say "Home is Android's chooser; the stock launcher among the candidates is $STOCK_LAUNCHER."
      else
        die "Home is '${HOME_PKG:-nothing}', not a stock launcher this script knows (com.google.android.tungsten.setupwraith is Google TV's setup wizard). Run again with STOCK_LAUNCHER=<package to disable> or STOCK_LAUNCHER=none."
      fi ;;
  esac
fi
say "Stock launcher: $STOCK_LAUNCHER"

# Order matters: install, start Vibe Launcher (so Home is never left without a running launcher),
# disable the stock launcher, and set Home last. Android forgets the chosen home app whenever the set
# of home apps changes; on Google TV 14 the next candidate would be the setup wizard's recovery
# screen, which crash-loops the system.
step "Installing"
RESULT=$(tv_install "$APK")
[[ "$RESULT" == *Success* ]] || die "install failed: $RESULT (a release build installed? uninstall it first: debug and release builds are signed with different keys)"
# Started as Home (the HOME action and category), not as an app: "am start -n" alone opens it in an ordinary
# task, and the next Home press starts a second copy beside it.
# Except with a stock launcher left on (STOCK_LAUNCHER=none while one is enabled): started as Home, Vibe
# Launcher stays Android's home process until a restart, and Android then blocks the stock launcher's
# launches from the remote's app buttons (upstream #1274). There it's started by its name only.
# Replaced while it was Home, Android may have restarted it mid-update, and upstream saw such an instance
# (a TCL, Android 11) leave the previous app's picture on screen; stopped, the start below makes a fresh one.
STOCK_ON=""
if [ "$STOCK_LAUNCHER" = none ]; then
  ENABLED=$(tv "pm list packages -e" | sed 's/^package://')
  for k in $STOCK_LAUNCHERS; do
    if grep -qx "$k" <<< "$ENABLED"; then STOCK_ON=$k; fi
  done
fi
tv "am force-stop ${LAUNCHER_ACTIVITY%%/*}" >/dev/null
if [ -n "$STOCK_ON" ]; then
  say "$STOCK_ON stays on: starting Vibe Launcher as an app, not as Home."
  tv "am start -n $LAUNCHER_ACTIVITY" >/dev/null
else
  tv "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $LAUNCHER_ACTIVITY" >/dev/null
fi
sleep 2
[ "$STOCK_LAUNCHER" = none ] || tv "pm disable-user --user 0 $STOCK_LAUNCHER"
tv "cmd package set-home-activity --user 0 $LAUNCHER_ACTIVITY" >/dev/null
NOW=$(home_app)
[ "$NOW" = "$LAUNCHER_ACTIVITY" ] || die "Home is $NOW instead of Vibe Launcher"
say "Installed and set as home. Press Home on the remote to see it."
