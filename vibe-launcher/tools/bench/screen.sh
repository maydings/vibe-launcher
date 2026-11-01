#!/usr/bin/env bash
# What's on the TV now, for stepping through a screen one key at a time: the front app, then the screen's
# texts with the focused one marked. Permission dialogs hide from it: take a screencap for those.
#
#   tools/bench/screen.sh <serial>
[ $# -eq 1 ] || { echo "usage: $0 <serial>" >&2; exit 2; }
S=$1
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$HERE/results"
adb -s "$S" shell dumpsys activity activities | grep -m1 mResumedActivity | sed 's/.*u0 //; s/ t[0-9]*}//'
adb -s "$S" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb -s "$S" exec-out cat /sdcard/ui.xml > "$HERE/results/ui.xml"
python3 - "$HERE/results/ui.xml" <<'PY'
import re, sys
x = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
for n in re.findall(r'<node [^>]*>', x):
    t = re.search(r' text="([^"]*)"', n).group(1)
    d = re.search(r'content-desc="([^"]*)"', n).group(1)
    focused = 'focused="true"' in n
    if t or focused:
        print(('>> ' if focused else '   ') + t[:110] + (' | ' + d[:40] if d else ''))
PY
