#!/usr/bin/env python3
"""Sums up what frames.sh recorded: how far apart frames reached the screen while things moved.

    python3 tools/bench/frames_report.py vibe flauncher projectivy

Each press's file holds SurfaceFlinger's refresh period, then one line per frame: desired, actual and
ready times in nanoseconds. Gaps over 250 ms are the pauses between presses, not frames, and are left out.
A gap over 1.5 refresh periods is a late frame: the screen showed the old one again.
"""
import glob
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def report(label):
    files = sorted(glob.glob(os.path.join(HERE, 'results', 'frames-' + label, '*.txt')),
                   key=lambda f: int(os.path.basename(f).split('-')[0]))
    gaps, per_press, period = [], [], None
    for f in files:
        lines = open(f).read().split('\n')
        if not lines or not lines[0].strip():
            continue
        period = int(lines[0])
        shown = sorted({int(p[1]) for p in (l.split() for l in lines[1:])
                        if len(p) == 3 and 0 < int(p[1]) < 2 ** 62})
        per_press.append(len(shown))
        gaps += [(b - a) / 1e6 for a, b in zip(shown, shown[1:]) if (b - a) / 1e6 <= 250]
    if not gaps:
        print(f'{label}: no frames recorded')
        return
    gaps.sort()
    n, refresh = len(gaps), period / 1e6

    def pct(q):
        return gaps[min(n - 1, int(q * n))]

    late = sum(1 for g in gaps if g > refresh * 1.5)
    print(f'{label:12s} median {pct(.5):5.1f} | 95th {pct(.95):5.1f} | 99th {pct(.99):5.1f} | '
          f'worst {gaps[-1]:6.1f} ms | late {100 * late / n:4.1f}% | frames per press {statistics.median(per_press)}')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit('usage: frames_report.py <label> [<label> ...]')
    for label in sys.argv[1:]:
        report(label)
