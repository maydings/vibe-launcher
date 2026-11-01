#!/usr/bin/env bash
# Frame times at the display (SurfaceFlinger), for any launcher, Flutter included: when each frame
# actually reached the screen, while the D-pad moves about. Plain bash 3.2.
#
#   tools/bench/frames.sh <serial> <package> <activity> <label>
#   python3 tools/bench/frames_report.py vibe flauncher
#
# Nobody on the remote meanwhile: one D-pad press a second (never OK), the app checked after each.
# SurfaceFlinger keeps only the last 128 frames, so they're read after every press: results/frames-<label>/.
set -u
[ $# -eq 4 ] || { echo "usage: $0 <serial> <package> <activity> <label>" >&2; exit 2; }
S=$1 PKG=$2 ACT=$3 LABEL=$4
HERE=$(cd "$(dirname "$0")" && pwd)
DIR=$HERE/results/frames-$LABEL
rm -rf "$DIR"; mkdir -p "$DIR"

a() { adb -s "$S" shell "$@"; }
front() { a dumpsys activity activities | grep -m1 mResumedActivity | grep -o '[a-z][a-zA-Z0-9_.]*/[a-zA-Z0-9_.$]*' | head -1; }

a am start -W -n "$PKG/$ACT" >/dev/null; sleep 4
# Flutter draws into a SurfaceView of its own; others into the window.
list=$(a dumpsys SurfaceFlinger --list | tr -d '\r')
layer=$(echo "$list" | grep "^SurfaceView - $PKG/" | tail -1)
[ -z "$layer" ] && layer=$(echo "$list" | grep "^$PKG/" | tail -1)
[ -z "$layer" ] && { echo "no layer for $PKG" >&2; exit 1; }
echo "layer: $layer"

n=0
for k in RIGHT RIGHT RIGHT LEFT LEFT LEFT DOWN RIGHT RIGHT DOWN LEFT UP UP RIGHT DOWN DOWN DOWN UP UP UP; do
  a dumpsys SurfaceFlinger --latency-clear "\"$layer\"" >/dev/null
  a input keyevent "KEYCODE_DPAD_$k"; sleep 1
  f=$(front)
  case "$f" in "$PKG"/*) ;; *) echo "STOPPED: after $k the front app is $f" >&2; exit 1 ;; esac
  a dumpsys SurfaceFlinger --latency "\"$layer\"" | tr -d '\r' > "$DIR/$n-$k.txt"
  n=$((n + 1))
done
echo "recorded $n presses in $DIR"
