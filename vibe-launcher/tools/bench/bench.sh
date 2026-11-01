#!/usr/bin/env bash
# Speed of a launcher on a TV, over adb: cold start, time to the first picture, memory, idle CPU, and the
# app's own frame counter. Plain bash 3.2 (the version macOS ships).
#
#   tools/bench/bench.sh <serial> <package> <activity> <label>
#   tools/bench/bench.sh 192.168.1.41:5555 com.maydings.vibelauncher .MainActivity vibe
#
# Nobody on the remote meanwhile: it opens apps and presses the D-pad (never OK), one key a second, and
# stops if another app comes to the front. Results print and go to results/<label>.txt.
# Android's frame counter (gfxinfo) sees nothing of a Flutter app, and on Android 9 calls most frames
# "janky" that reach the screen on time: frames.sh measures at the display instead.
set -u
[ $# -eq 4 ] || { echo "usage: $0 <serial> <package> <activity> <label>" >&2; exit 2; }
S=$1 PKG=$2 ACT=$3 LABEL=$4
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$HERE/results"
OUT=$HERE/results/$LABEL.txt

a() { adb -s "$S" shell "$@"; }
front() { a dumpsys activity activities | grep -m1 mResumedActivity | grep -o '[a-z][a-zA-Z0-9_.]*/[a-zA-Z0-9_.$]*' | head -1; }
median() { sort -n | awk '{v[NR]=$1} END {if (NR==0) print "n/a"; else if (NR%2) print v[(NR+1)/2]; else print (v[NR/2]+v[NR/2+1])/2}'; }
other_app_in_front() { a am start -W -a android.settings.SETTINGS >/dev/null 2>&1; sleep 2; }
pss() { a dumpsys meminfo "$PKG" | tr -d '\r' | grep -m1 -E 'TOTAL( PSS)?[: ]' | awk '{print $2}'; }
log() { echo "$*" | tee -a "$OUT"; }

: > "$OUT"
log "== $LABEL ($PKG) on $(a getprop ro.product.model | tr -d '\r'), Android $(a getprop ro.build.version.release | tr -d '\r'), $(date -u +%FT%TZ)"

# Cold start, 10 times. Another app goes in front first: a killed Home in front would be restarted by
# Android itself before am start, which would then time a warm start.
cold="" drawn=""
for i in 1 2 3 4 5 6 7 8 9 10; do
  other_app_in_front
  a am force-stop "$PKG"; sleep 2
  a logcat -c
  cold="$cold $(a am start -W -n "$PKG/$ACT" | tr -d '\r' | awk -F': ' '/TotalTime/ {print $2}')"
  sleep 12
  # "Fully drawn": the app's reportFullyDrawn (Vibe Launcher's first backdrop), where it calls it.
  d=$(a logcat -d -s ActivityManager:I ActivityTaskManager:I | tr -d '\r' | grep "Fully drawn $PKG" | tail -1 \
      | grep -o '+[0-9a-z]*' | head -1)
  [ -n "$d" ] && drawn="$drawn $d"
done
log "cold start ms:$cold"
log "cold start median ms: $(echo $cold | tr ' ' '\n' | median)"
[ -n "$drawn" ] && log "first picture (Fully drawn):$drawn"
log "memory after start: $(pss) KB PSS"

# Back to it from another app, its process still running, 10 times. 0 means it was already there.
warm=""
for i in 1 2 3 4 5 6 7 8 9 10; do
  other_app_in_front
  warm="$warm $(a am start -W -n "$PKG/$ACT" | tr -d '\r' | awk -F': ' '/TotalTime/ {print $2}')"
  sleep 3
done
log "return ms:$warm"
log "return median ms: $(echo $warm | tr ' ' '\n' | median)"

# CPU over 15 s with nothing pressed (Vibe Launcher's slideshow waits 60 s, so it isn't running yet).
sleep 5
log "idle CPU: $(a top -b -n 2 -d 15 -o PID,%CPU,ARGS 2>/dev/null | tr -d '\r' | grep "$PKG" | tail -1 | awk '{print $2}')%"

# Moving about: one D-pad press a second, the app checked after each.
a dumpsys gfxinfo "$PKG" reset >/dev/null
for k in RIGHT RIGHT RIGHT LEFT LEFT LEFT DOWN RIGHT RIGHT DOWN LEFT UP UP RIGHT DOWN DOWN DOWN UP UP UP; do
  a input keyevent "KEYCODE_DPAD_$k"; sleep 1
  f=$(front)
  case "$f" in "$PKG"/*) ;; *) log "STOPPED: after $k the front app is $f"; exit 1 ;; esac
done
g=$(a dumpsys gfxinfo "$PKG" | tr -d '\r')
log "gfxinfo frames: $(echo "$g" | grep -m1 'Total frames rendered' | awk -F': ' '{print $2}'), janky: $(echo "$g" | grep -m1 'Janky frames' | awk -F': ' '{print $2}')"
log "gfxinfo $(echo "$g" | grep -m4 -E '^(50|90|95|99)th percentile' | tr '\n' ' ')"
log "memory after moving: $(pss) KB PSS"
