#!/usr/bin/env bash
# Switch on an app's home-screen rows ("channels"), best effort, on a TV whose Google launcher is turned off.
#
#   ./channel-rows.sh <tv-ip>        (or TV_HOST=<tv-ip> ./channel-rows.sh)
#
# Apps publish rows of cards, and some fill a row only once it has been switched on in the launcher's
# Customize channels screen: SmartTV Client for Twitch, for one, fills its followed channels' row only then
# (it switches on its Live row itself). Vibe Launcher shows every row's cards, but can't switch rows on: Android
# keeps that to the system, and adb has no way to either. With Google's launcher turned off, that screen is gone
# too. This turns the launcher back on for a moment and opens the screen; switch the rows on there, press
# Enter here, and the launcher is turned off again and Vibe Launcher made Home again. What's switched on is
# kept by Android, not the launcher, so it stays.
#
# Android TV's launcher (com.google.android.tvlauncher, Android 8 to 11) only: Google TV's launcher has no
# such screen for other apps' rows.
set -uo pipefail
cd "$(dirname "$0")"
. tools/lib.sh
HOST=${1:-${TV_HOST:-}}
[ -n "$HOST" ] || die "usage: ./channel-rows.sh <tv-ip>"
GOOGLE=com.google.android.tvlauncher
SETTINGS=$GOOGLE/.settings.HomeScreenSettingsActivity

connect "$HOST"

tv "pm path $GOOGLE" | grep -q '^package:' || die "$GOOGLE isn't on this TV: its launcher has no screen for switching other apps' rows on."
WAS_OFF=no
tv "pm list packages -d" | grep -qx "package:$GOOGLE" && WAS_OFF=yes

# Whatever happens below, the launcher goes back as it was and Vibe Launcher is Home again.
restore() {
  if [ "$WAS_OFF" = yes ]; then
    tv "am force-stop $GOOGLE" >/dev/null
    tv "pm disable-user --user 0 $GOOGLE" >/dev/null
  fi
  # Android forgets the chosen home app whenever one is turned on or off.
  tv "cmd package set-home-activity --user 0 $LAUNCHER_ACTIVITY" >/dev/null
  tv "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $LAUNCHER_ACTIVITY" >/dev/null
}
trap restore EXIT

step "Opening Customize channels"
if [ "$WAS_OFF" = yes ]; then
  say "Turning $GOOGLE on for a moment."
  tv "pm enable --user 0 $GOOGLE" >/dev/null
fi
tv "am start -n $SETTINGS" >/dev/null
say "On the TV: Customize channels, then the app (for example SmartTV Client for Twitch), and switch on"
say "the rows you want, such as its followed channels. Then press Enter here."
read -r _

step "Putting things back"
restore
trap - EXIT
NOW=$(home_app)
[ "$NOW" = "$LAUNCHER_ACTIVITY" ] || die "Home is $NOW instead of Vibe Launcher: run ./build-and-install.sh $HOST to set it again."
say "Done. The app fills the rows switched on at its next update (opening it once helps); Vibe Launcher shows their cards."
