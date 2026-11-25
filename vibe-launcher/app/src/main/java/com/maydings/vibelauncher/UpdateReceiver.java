package com.maydings.vibelauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * Hears back from Android's installer about an update Updater handed it: shows its confirmation screen,
 * or says why it didn't install. Installing ends the launcher, and some TVs then leave the screen blank
 * until Home is pressed, so once the new version is in, this brings Home back.
 */
public class UpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            // Only after an update from here: one installed from a computer mustn't take over the screen.
            if (Updater.justUpdated(context)) {
                try {
                    context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .setPackage(context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                    // Android 10+ background activity launch restriction when not default Home
                }
            }
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = android.os.Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
            if (pkg == null || pkg.equals(context.getPackageName())) Updater.installStopped(context);
            // Aborted is the user pressing Cancel: nothing to say.
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(context, Lang.wrap(context).getString(R.string.update_failed), Toast.LENGTH_LONG).show();
            }
        }
    }
}
