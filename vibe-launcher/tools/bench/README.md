# Speed tests

Measures a launcher on a TV over adb, the same way each time, so a change can be checked against the last
numbers, or Vibe Launcher against another launcher. Nobody on the remote while they run: they open apps and
press the D-pad (never OK), one key a second, and stop if another app comes to the front.

```bash
# Startup, first picture, memory, idle CPU (about 5 minutes)
tools/bench/bench.sh 192.168.1.41:5555 com.maydings.vibelauncher .MainActivity vibe

# Frame times at the display while moving about (about 1 minute), then the summary
tools/bench/frames.sh 192.168.1.41:5555 com.maydings.vibelauncher .MainActivity vibe
python3 tools/bench/frames_report.py vibe
```

Results go to `results/` (not in git). `screen.sh <serial>` prints the front app and the screen's texts with
the focused one marked, for stepping through another launcher's first-run screens one key at a time.

**Use the frame times from `frames.sh`, not `gfxinfo`.** Android's own counter sees nothing of a Flutter app
(FLauncher), and on Android 9 it calls most of Vibe Launcher's frames "janky" that reach the screen on time.

**Installing another launcher resets the default Home** on Android 9. Afterwards:
`adb shell cmd package set-home-activity com.maydings.vibelauncher/.MainActivity`.

## Baseline: 1 November 2026, Sony XBR-940E

Android 9, 32-bit ARMv7, 2 GB: the slowest TV here. Medians of 10 where repeated; frames over 20 D-pad moves.

| | Vibe Launcher 2.9.1 | Projectivy 4.72 | FLauncher 2025.07 |
|---|---|---|---|
| Cold start | **0.90 s** | 0.92 s | 1.26 s |
| First picture on screen | **0.91 s** | 1.26 s | (no backdrops) |
| Memory after start (PSS) | **42 MB** | 58 MB | 58 MB |
| Memory after moving about | 50 MB | 61 MB | 44 MB |
| CPU, idle | **0%** | 36% (its background never stops drawing) | 0% |
| Typical frame, moving | **16.6 ms (60 fps)** | 16.6 ms | 33.2 ms (30 fps) |
| Late frames | **1.1%** | 8.9% | 75% |
| Worst frame | **50 ms** | 133 ms | 199 ms |

Projectivy was set up with storage and TV listings allowed; notification access, usage reporting and its
accessibility service declined. Both others were uninstalled afterwards.
