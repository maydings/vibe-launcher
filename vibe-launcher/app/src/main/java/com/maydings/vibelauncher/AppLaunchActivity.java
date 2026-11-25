package com.maydings.vibelauncher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * market://launch?id=<package>: the TV's phone-remote service (the Google TV app's "open app", Home
 * Assistant's Android TV Remote) opens an app by its package name this way. The stock launcher serves it;
 * with that turned off over adb, nothing did, and the launch failed silently (upstream #1059). Where it's
 * still on (no adb), Android asks which of the two to use. Opens the app's TV launch intent, else
 * its phone one. No screen of its own.
 */
@SuppressLint("CustomSplashScreen")   // not a splash screen: it opens another app and closes
public class AppLaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent intent = getIntent();
            Uri data = intent != null ? intent.getData() : null;
            if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())
                    && data != null && "market".equals(data.getScheme()) && "launch".equals(data.getHost())) {
                String pkg = data.getQueryParameter("id");
                if (pkg != null && !pkg.isEmpty() && !pkg.equals(getPackageName())) {
                    Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                    if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            }
        } catch (RuntimeException ignored) {
            // Gone, not ours to open, or an address that isn't one of these (any app can send one): nothing to show.
        } finally {
            finish();
        }
    }
}
