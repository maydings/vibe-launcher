package com.maydings.vibelauncher;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Live CPU usage for the settings menu, from /proc/stat. Android 8+ hides that file from apps on most
 * devices (all of ours), and then the menu leaves the CPU bar out.
 */
final class CpuInfo {

    private long lastTotal = -1, lastIdle;

    /** Busy share since the previous call, 0..1 (0 on the first, with none before it); -1 when /proc/stat can't be read. */
    float usage() {
        long[] t;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = r.readLine();   // "cpu  user nice system idle iowait irq softirq steal ..."
            if (line == null || !line.startsWith("cpu ")) return -1;
            String[] f = line.trim().split("\\s+");
            t = new long[Math.min(8, f.length - 1)];
            for (int i = 0; i < t.length; i++) t[i] = Long.parseLong(f[i + 1]);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        long total = 0;
        for (long v : t) total += v;
        long idle = t[3] + (t.length > 4 ? t[4] : 0);   // idle + iowait
        boolean first = lastTotal < 0;
        long dTotal = total - lastTotal, dIdle = idle - lastIdle;
        lastTotal = total;
        lastIdle = idle;
        if (first || dTotal <= 0) return first ? 0f : -1;
        return Math.max(0f, Math.min(1f, 1f - dIdle / (float) dTotal));
    }

    /** True when live usage can be shown on this device. */
    boolean canMeasure() {
        return usage() >= 0;
    }
}
