package com.maydings.vibelauncher;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates from the project's GitHub releases: checks for a newer version (at most once a day on its own,
 * or when asked), downloads its APK, checks it against the SHA-256 the release publishes, and hands it to
 * Android's installer, which asks the user to confirm. Only builds signed with the release key update this
 * way (releases, and debug builds made with it): Android refuses an update signed with another key.
 */
final class Updater {

    /** A newer release: its version, where its APK and checksum are, and its notes (Markdown; may be ""). */
    static final class Release {
        final String version, apkUrl, sha256, notes;

        Release(String version, String apkUrl, String sha256, String notes) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.notes = notes;
        }
    }

    interface CheckListener {
        /** On the main thread: the newer release, or null when up to date; failed when it couldn't check. */
        void onChecked(Release newer, boolean failed);
    }

    interface InstallListener {
        /** On the main thread: a message for the user when it stopped short of Android's confirmation. */
        void onProblem(int messageRes);
    }

    /** SHA-256 of the release signing certificate (apksigner verify --print-certs). */
    private static final String RELEASE_CERT = "6d7445860131aff657cf7749f7b7be5c0faaeb6d267f1422e3c24a6fe036cfa9";
    private static final String LATEST = "https://api.github.com/repos/maydings/vibe-launcher/releases/latest";
    private static final String RECENT = "https://api.github.com/repos/maydings/vibe-launcher/releases?per_page=30";
    private static final String APK = "vibe-launcher.apk";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_APK_BYTES = 40 * 1024 * 1024;   // room for SmartTube's (about 26 MB)
    private static final Pattern BODY_SHA = Pattern.compile("SHA-256\\s*`([0-9a-fA-F]{64})`");
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Updater() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    /** This build's version, e.g. "1.2.6". */
    static String installedVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** A build signed with a key other than the release one (someone's own): releases can't install over it. */
    static boolean isDevelopmentBuild(Context context) {
        return !RELEASE_CERT.equals(signingCert(context));
    }

    /** SHA-256 of this app's signing certificate, or "" when it can't be read. */
    @SuppressWarnings("deprecation")
    private static String signingCert(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Signature[] signers;
            if (Build.VERSION.SDK_INT >= 28) {
                signers = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo.getApkContentsSigners();
            } else {
                signers = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            }
            return signers == null || signers.length != 1 ? "" : sha256(signers[0].toByteArray());
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "";
        }
    }

    /** The newer release found by the last check, or null (none, not checked yet, or a development build). */
    static Release available(Context context) {
        if (isDevelopmentBuild(context)) return null;
        SharedPreferences p = prefs(context);
        String version = p.getString("version", "");
        if (version.isEmpty() || !newer(version, installedVersion(context))) return null;
        return new Release(version, p.getString("apk", ""), p.getString("sha256", ""), p.getString("notes", ""));
    }

    /** Checks in the background if the last check is a day old; then tells the listener, on the main thread. */
    static void checkIfDue(Context context, CheckListener listener) {
        checkIfOlderThan(context, DAY_MS, listener);
    }

    /**
     * Checks in the background if the last check is older than this; the listener only hears of a check
     * made. False when none was started (checked recently enough, or a development build).
     */
    static boolean checkIfOlderThan(Context context, long ms, CheckListener listener) {
        if (isDevelopmentBuild(context)) return false;
        if (System.currentTimeMillis() - prefs(context).getLong("checked", 0) < ms) return false;
        check(context, listener);
        return true;
    }

    /** Asks GitHub for the latest release now. */
    static void check(Context context, CheckListener listener) {
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            boolean failed = false;
            try {
                fetchLatest(app);
            } catch (IOException | JSONException | RuntimeException e) {
                failed = true;   // offline, or GitHub busy: the next check tries again
            }
            boolean f = failed;
            main.post(() -> listener.onChecked(available(app), f));
        });
    }

    private static void fetchLatest(Context context) throws IOException, JSONException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("User-Agent", "VibeLauncher/" + installedVersion(context));   // GitHub's API requires one
        JSONObject release = new JSONObject(Http.getText(LATEST, headers));
        String version = release.getString("tag_name").replaceFirst("^[vV]", "");
        String apk = null, sha = null;
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (!APK.equals(a.optString("name"))) continue;
            apk = a.getString("browser_download_url");
            String digest = a.optString("digest");   // "sha256:<hex>", which GitHub works out itself
            if (digest.startsWith("sha256:")) sha = digest.substring(7);
        }
        if (sha == null) {   // older releases: the checksum written in the notes
            Matcher m = BODY_SHA.matcher(release.optString("body"));
            if (m.find()) sha = m.group(1);
        }
        SharedPreferences.Editor e = prefs(context).edit().putLong("checked", System.currentTimeMillis());
        if (apk != null && sha != null) {
            e.putString("version", version).putString("apk", apk).putString("sha256", sha.toLowerCase(Locale.ROOT))
                    .putString("notes", notesSince(context, headers, release, version));
        }
        e.apply();
    }

    /**
     * The notes of every release newer than this build, up to the latest, newest first and each under a
     * "## version" line, so skipping versions still shows what each one changed. Just the latest's notes
     * when it's the only one, or when the list can't be read.
     */
    private static String notesSince(Context context, Map<String, String> headers, JSONObject latest, String latestVersion) {
        String installed = installedVersion(context);
        StringBuilder out = new StringBuilder();
        int count = 0;
        try {
            JSONArray releases = new JSONArray(Http.getText(RECENT, headers));
            for (int i = 0; i < releases.length(); i++) {   // GitHub lists the newest first
                JSONObject r = releases.getJSONObject(i);
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
                String v = r.optString("tag_name").replaceFirst("^[vV]", "");
                if (!newer(v, installed) || newer(v, latestVersion)) continue;
                out.append("## ").append(v).append("\n").append(r.optString("body").trim()).append("\n\n");
                count++;
            }
        } catch (IOException | JSONException | RuntimeException e) {
            count = 0;
        }
        return count > 1 ? out.toString().trim() : latest.optString("body");
    }

    /**
     * Downloads the release, checks its SHA-256 and opens Android's installer, which asks the user to confirm.
     * Installing apps needs the "Install unknown apps" switch for Vibe Launcher; until it's on, this opens
     * that switch instead.
     */
    static void install(Context context, Release release, InstallListener listener) {
        if (isDevelopmentBuild(context)) {
            listener.onProblem(R.string.update_development);
            return;
        }
        installApk(context, release.apkUrl, release.sha256, context.getPackageName(), R.string.update_allow_installs,
                listener);
    }

    /**
     * Downloads an APK, checks its SHA-256 and hands it to Android's installer for the given package (this
     * app's update). Without the "Install unknown apps" switch for Vibe Launcher,
     * opens that switch instead and says so with allowRes.
     */
    static void installApk(Context context, String url, String sha, String pkg, int allowRes, InstallListener listener) {
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            int problem = installNow(app, url, sha, pkg, allowRes);
            if (problem != 0) main.post(() -> listener.onProblem(problem));
        });
    }

    /** installApk on the calling (worker) thread: 0 once it's with the installer, else a message for the user. */
    // The adb build declares the permission (src/adb); the store build never installs anything.
    @SuppressLint("MissingPermission")
    static int installNow(Context app, String url, String sha, String pkg, int allowRes) {
        if (!app.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent allow = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + app.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // The TV's Settings remembers apps by their APK file, which every install replaces; left
                // running since, it lists this one by its package name and a blank icon. Closed while it's
                // in the background, it reloads and shows Vibe Launcher.
                ResolveInfo settings = app.getPackageManager().resolveActivity(allow, 0);
                ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
                if (settings != null && am != null) am.killBackgroundProcesses(settings.activityInfo.packageName);
                app.startActivity(allow);
                return allowRes;
            } catch (ActivityNotFoundException e) {
                return R.string.update_no_installs;
            }
        }
        // One file per app: the launcher's update and SmartTube can be on their way at once.
        File apk = new File(new File(app.getCacheDir(), "update"), pkg + ".apk");
        try {
            apk.getParentFile().mkdirs();
            if (!Http.download(url, apk, MAX_APK_BYTES).equals(sha)) {
                return R.string.update_bad_download;   // corrupted or tampered with: never installed
            }
            commit(app, apk, pkg);
            return 0;
        } catch (IOException | RuntimeException e) {
            return R.string.update_failed;
        } finally {
            apk.delete();   // the installer has its own copy by now
        }
    }

    /** Hands the APK to Android's installer; UpdateReceiver shows its confirmation. */
    @SuppressLint("ApplySharedPref")   // on disk at once: the process ends straight after
    private static void commit(Context app, File apk, String pkg) throws IOException {
        boolean self = pkg.equals(app.getPackageName());
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new java.io.FileInputStream(apk);
                 OutputStream out = session.openWrite(APK, 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            } catch (IOException | RuntimeException e) {
                session.abandon();   // or the half-written session stays behind in the installer
                throw e;
            }
            // Written now (not applied later): installing ends this process. UpdateReceiver reads it back.
            if (self) prefs(app).edit().putLong("installing", System.currentTimeMillis()).commit();
            // Mutable: the installer adds the status and its confirmation screen to this intent.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            Intent result = new Intent(app, UpdateReceiver.class);
            PendingIntent status = PendingIntent.getBroadcast(app, id, result, flags);
            session.commit(status.getIntentSender());
        }
    }

    /**
     * Whether an update handed to the installer from here in the last quarter hour just went in, and
     * forgets it. Updates installed from a computer don't count.
     */
    @SuppressLint("ApplySharedPref")   // on disk at once: Home starts straight after, maybe in a new process
    static boolean justUpdated(Context context) {
        SharedPreferences p = prefs(context);
        long at = p.getLong("installing", 0);
        p.edit().remove("installing").commit();
        return System.currentTimeMillis() - at < 15 * 60 * 1000L;
    }

    /** An update that didn't go in: a later install from a computer isn't mistaken for it. */
    static void installStopped(Context context) {
        prefs(context).edit().remove("installing").apply();
    }

    /** Whether version a is newer than b, comparing "1.10.0" and "1.9.2" number by number. */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String sha256(byte[] data) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
