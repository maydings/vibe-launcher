package com.maydings.vibelauncher;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

/**
 * SmartTube, the YouTube app whose home screen cards come from the signed-in user's own feed (the best
 * YouTube backdrop, with no key). Opened to sign in when it's on the TV. Otherwise Downloader (AFTVnews)
 * installs it, as people put Vibe Launcher itself on the TV: opened on SmartTube's Downloader code, which
 * leads to SmartTube's own latest stable release on its GitHub.
 */
final class SmartTube {

    /** The stable build first: the one Downloader's code installs. The rest are its beta and older names. */
    static final String[] PACKAGES = {"org.smarttube.stable", "org.smarttube.beta",
            "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta", "com.teamsmart.videomanager.tv"};

    /** SmartTube stable's Downloader code (aftv.news/28544, to smarttube_stable.apk on its GitHub). */
    static final String CODE = "28544";
    private static final String DOWNLOADER = "com.esaba.downloader";

    private SmartTube() {
    }

    /** The installed SmartTube's launch intent, or null when there's none. */
    static Intent launchIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : PACKAGES) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i;
        }
        return null;
    }

    /**
     * Downloader opened on SmartTube's code, which fetches it and asks to install it. False when Downloader
     * isn't on the TV (or won't open): the caller says to get it and type the code.
     */
    static boolean openDownloader(Context context) {
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse("https://aftv.news/" + CODE))
                .setPackage(DOWNLOADER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (open.resolveActivity(context.getPackageManager()) == null) return false;
        try {
            context.startActivity(open);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
