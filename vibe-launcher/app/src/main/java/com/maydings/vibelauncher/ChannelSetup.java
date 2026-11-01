package com.maydings.vibelauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asks apps to publish their home screen cards. Some (Prime Video) only create their channel when the
 * home screen sends them INITIALIZE_PROGRAMS, which the stock launcher does after each install or
 * update; with it disabled they publish nothing, and Backdrops has nothing to show for them. Sent once
 * per app and version, like the stock launcher.
 */
final class ChannelSetup {

    private static final String FILE = "channel_setup";
    /** One at a time, off the main thread: a question to Android per app, on every rebuild of Home. */
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    private ChannelSetup() {
    }

    /** Sends the broadcast to each app that listens for it and hasn't had it since it was installed or updated. */
    static void run(Context context) {
        Context app = context.getApplicationContext();
        worker.execute(() -> send(app));
    }

    private static void send(Context context) {
        PackageManager pm = context.getPackageManager();
        SharedPreferences sent = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = sent.edit();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo r : pm.queryBroadcastReceivers(new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS), 0)) {
            String pkg = r.activityInfo.packageName;
            if (!seen.add(pkg)) continue;
            long updated;
            try {
                updated = pm.getPackageInfo(pkg, 0).lastUpdateTime;
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            if (sent.getLong(pkg, 0) == updated) continue;
            // Explicit: Android 8+ doesn't deliver implicit broadcasts to receivers in manifests.
            context.sendBroadcast(new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS).setPackage(pkg));
            edit.putLong(pkg, updated);
        }
        // Forget removed apps, so a reinstall gets it again.
        for (String pkg : sent.getAll().keySet()) {
            if (!seen.contains(pkg)) edit.remove(pkg);
        }
        edit.apply();
    }
}
