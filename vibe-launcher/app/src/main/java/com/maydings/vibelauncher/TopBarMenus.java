package com.maydings.vibelauncher;

import android.app.ActivityManager;
import android.content.Context;
import android.widget.ImageView;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The menus under the top bar's pills (MainActivity's, kept apart from it for their size): the network's, the
 * system's with its meters (processor, memory, storage) and Free memory, and the inputs'. Their windows, rows
 * and what they open are Home's own.
 */
final class TopBarMenus {

    private final MainActivity home;

    TopBarMenus(MainActivity home) {
        this.home = home;
    }

    /**
     * Dropdown under the network pill: each connected interface with its IPv4 address (and Wi-Fi's link
     * speed), then settings.
     */
    void showNetwork() {
        View panel = home.getLayoutInflater().inflate(R.layout.popup_inputs, home.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = home.newMenu(panel);

        // Which device this is (Settings > About), to tell the TVs and the Shield apart.
        String name = Settings.Global.getString(home.getContentResolver(), Settings.Global.DEVICE_NAME);
        name = name == null || name.trim().isEmpty() ? Build.MODEL : name.trim();
        addInfoRow(rows, R.drawable.ic_livetv, name, name.equalsIgnoreCase(Build.MODEL) ? null : Build.MODEL);
        home.addSeparator(rows);
        int firstNetworkRow = rows.getChildCount();

        ConnectivityManager cm = home.getApplicationContext().getSystemService(ConnectivityManager.class);
        if (cm != null) {
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                LinkProperties lp = cm.getLinkProperties(net);
                if (caps == null || lp == null) continue;
                boolean vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                String ip = null;
                for (LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof Inet4Address) {
                        ip = a.getAddress().getHostAddress();
                        break;
                    }
                }
                // A VPN is worth listing even with only an IPv6 address (or none we can show).
                if (ip == null && vpn) {
                    ip = lp.getLinkAddresses().isEmpty() ? home.getString(R.string.connected)
                            : lp.getLinkAddresses().get(0).getAddress().getHostAddress();
                }
                if (ip == null) continue;
                String type;
                int icon = R.drawable.ic_wifi;
                // VPN first: Android often also marks a VPN with the Wi-Fi or Ethernet it runs over.
                if (vpn) {
                    type = home.getString(R.string.vpn_connected);
                    icon = R.drawable.ic_lock;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    type = home.getString(R.string.ethernet);
                    icon = R.drawable.ic_ethernet;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    String ssid = home.wifiName();
                    type = ssid != null ? home.getString(R.string.wifi) + " \u00B7 " + ssid : home.getString(R.string.wifi);
                    // The rate the Wi-Fi link runs at now. Ethernet's isn't readable by apps, so it shows none.
                    int mbps = wifiLinkSpeed();
                    if (mbps > 0) ip += " \u00B7 " + mbps + " Mbps";
                } else {
                    type = home.getString(R.string.network);
                }
                String iface = lp.getInterfaceName();
                addInfoRow(rows, icon, iface != null ? type + " (" + iface + ")" : type, ip);
            }
        }
        if (rows.getChildCount() == firstNetworkRow) {
            addInfoRow(rows, R.drawable.ic_wifi, home.getString(R.string.offline), null);
        }
        home.addSeparator(rows);
        home.addInputRow(rows, popup, R.drawable.ic_settings, home.getString(R.string.network_settings), -1,
                () -> home.open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        home.showMenu(popup, panel, rows, home.btnWifi, Gravity.END);
    }

    /**
     * The Settings menu's Free memory: "am kill-all" (the background apps closed, as Android does when it runs short)
     * over the TV's ADB, without asking it; then how much memory came back, or that it didn't take.
     */
    void freeMemory() {
        android.app.ActivityManager am = home.getSystemService(android.app.ActivityManager.class);
        if (am == null) return;
        android.app.ActivityManager.MemoryInfo before = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        Context app = home.getApplicationContext();
        new Thread(() -> {
            Adb.Result r = TvControl.shellQuietly(app, "am kill-all");
            home.handler.postDelayed(() -> {   // a moment for the memory to come back
                if (MainActivity.gone(home)) return;
                if (r.status != Adb.OK) {
                    MainActivity.showError(home, R.string.adb_failed);
                    return;
                }
                android.app.ActivityManager.MemoryInfo after = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(after);
                long freed = Math.max(0, after.availMem - before.availMem);
                Toast.makeText(app, Lang.wrap(app).getString(R.string.memory_freed,
                        android.text.format.Formatter.formatShortFileSize(app, freed)), Toast.LENGTH_SHORT).show();
            }, 800);
        }, "FreeMemory").start();
    }

    /** Dropdown under the gear: the launcher's own settings, or the system's. */
    void showSettingsMenu(View anchor) {
        View panel = home.getLayoutInflater().inflate(R.layout.popup_inputs, home.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = home.newMenu(panel);
        // Usage on top, like the device and addresses in the network menu.
        // CPU only where the device lets apps read the counters (not on Android 8+ TVs like ours).
        CpuInfo cpuInfo = new CpuInfo();
        boolean cpuLive = cpuInfo.canMeasure();
        View cpu = cpuLive ? addMeterRow(rows, home.getString(R.string.cpu)) : null;
        View ram = addMeterRow(rows, home.getString(R.string.ram));
        View disk = addMeterRow(rows, home.getString(R.string.storage));
        home.addSeparator(rows);
        // The last check's answer at once, then ask GitHub again: a release out since then replaces it in
        // this row, or shows the row. Settings → General, opened from here, shows the same answer. The row is
        // always there (hidden without an update) so showMenu gives it the menu bar's Left and Right.
        {
            Updater.Release[] update = {Updater.available(home)};
            View updateRow = home.addInputRow(rows, popup, R.drawable.ic_update, "", -1,
                    () -> SettingsScreen.startUpdate(home, update[0]));
            Runnable showUpdate = () -> {
                updateRow.setVisibility(update[0] != null ? View.VISIBLE : View.GONE);
                if (update[0] != null) {
                    ((TextView) updateRow.findViewById(R.id.title)).setText(home.getString(R.string.update_to, update[0].version));
                }
            };
            showUpdate.run();
            // At most every few minutes: GitHub allows 60 unsigned API calls an hour per address, shared by every
            // TV in the house, and this menu is opened often (it shows the memory meter).
            Updater.checkIfOlderThan(home, 5 * 60 * 1000L, (newer, failed) -> {
                if (MainActivity.gone(home)) return;
                home.showUpdateReady();
                if (newer == null || !popup.isShowing()) return;
                update[0] = newer;
                showUpdate.run();
            });
        }
        home.addInputRow(rows, popup, R.drawable.ic_all_apps, home.getString(R.string.vibe_settings), -1,
                home::openSettings);
        home.addInputRow(rows, popup, R.drawable.ic_settings, home.getString(R.string.android_settings), -1,
                () -> home.open(SystemSettings.intent(home)));
        if (!DefaultHome.isHome(home)) {
            // The TV's own home screen still answers Home: Settings > Advanced > Default launcher switches it.
            home.addInputRow(rows, popup, R.drawable.ic_home, home.getString(R.string.make_home), -1, home::offerHome);
        }
        // Last, under the meters' menu: the background apps closed through the TV's ADB (once Settings >
        // Advanced has been allowed), and how much memory that gave back.
        home.addInputRow(rows, popup, R.drawable.ic_memory, home.getString(R.string.free_memory), -1, this::freeMemory);
        // Live while the menu is open.
        Runnable updateMeters = () -> {
            if (cpuLive) updateCpu(cpu, cpuInfo.usage());
            updateRam(ram);
            updateDisk(disk);
        };
        updateMeters.run();
        alignMeters(cpu, ram, disk);
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!popup.isShowing() || MainActivity.gone(home)) return;
                updateMeters.run();
                home.handler.postDelayed(this, 1000);
            }
        };
        popup.setOnDismissListener(() -> home.handler.removeCallbacks(tick));
        home.showMenu(popup, panel, rows, anchor, Gravity.END);
        if (popup.isShowing()) home.handler.postDelayed(tick, 1000);
    }

    /** A read-only row with a usage bar; fill it with the returned view. */
    View addMeterRow(LinearLayout rows, String label) {
        View row = home.getLayoutInflater().inflate(R.layout.item_meter, rows, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        row.setFocusable(false);
        rows.addView(row);
        return row;
    }

    /**
     * One label width and one figures width for every meter in the menu, so their bars line up: the labels as
     * written (in some languages "Disk" is longer than "RAM"), the figures at their widest for this device (by its
     * totals: widestFigures, left in each row's tag by its first update). Set once, so the bars don't
     * move as the numbers change.
     */
    void alignMeters(View... meters) {
        float labels = 0, values = 0;
        for (View row : meters) {
            if (row == null) continue;
            TextView label = row.findViewById(R.id.label);
            labels = Math.max(labels, label.getPaint().measureText(label.getText().toString()));
            if (row.getTag() instanceof String) {
                // Every digit as the font's widest: its digits aren't all one width ("13" is wider than "16").
                TextView value = row.findViewById(R.id.value);
                values = Math.max(values, value.getPaint().measureText(
                        MainActivity.widestDigits((String) row.getTag(), value.getPaint())));
            }
        }
        float dp = home.getResources().getDisplayMetrics().density;
        int labelWidth = Math.max((int) Math.ceil(labels), Math.round(40 * dp));
        int valueWidth = Math.max((int) Math.ceil(values) + 1, Math.round(64 * dp));
        for (View row : meters) {
            if (row == null) continue;
            row.findViewById(R.id.label).getLayoutParams().width = labelWidth;
            row.findViewById(R.id.value).getLayoutParams().width = valueWidth;
            row.requestLayout();
        }
    }

    /** A figure in GB with at least two significant digits: "8.5" under 10, else whole ("11", "128"). */
    static String gb(float value) {
        float tenths = Math.round(value * 10) / 10f;
        // Both in the language's own digits: one written its way and the other not read as two numbers' worth.
        return tenths < 10 ? String.format(Locale.getDefault(), "%.1f", tenths)
                : String.format(Locale.getDefault(), "%d", Math.round(value));
    }

    /**
     * The widest a meter's figures get for a device with this total: two digits ("8.5 / 16 GB") under 100 GB,
     * three ("100 / 128 GB") from there; alignMeters measures it with the widest digit in every place.
     */
    String widestFigures(float total) {
        return total < 99.5f ? home.getString(R.string.ram_value, gb(8.5f), gb(16)) : home.getString(R.string.ram_value, gb(100), gb(128));
    }

    void updateCpu(View row, float usage) {
        row.setTag(home.getString(R.string.percent, 100));
        if (usage < 0) return;
        ((ProgressBar) row.findViewById(R.id.bar)).setProgress((int) (1000 * usage));
        ((TextView) row.findViewById(R.id.value)).setText(home.getString(R.string.percent, Math.round(100 * usage)));
    }

    /** Memory in use: total minus what the system says it can hand out without killing apps. */
    void updateRam(View row) {
        ActivityManager am = (ActivityManager) home.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        ProgressBar bar = row.findViewById(R.id.bar);
        bar.setProgress((int) (1000 * used / Math.max(1, mi.totalMem)));
        // Green while there's room, yellow under 100 MB free, red under 10 MB.
        long mb = 1024 * 1024;
        int color = mi.availMem > 100 * mb ? 0xFF34C759 : mi.availMem > 10 * mb ? 0xFFFFCC00 : 0xFFFF3B30;
        bar.setProgressTintList(ColorStateList.valueOf(color));
        float gb = 1024f * 1024 * 1024;
        ((TextView) row.findViewById(R.id.value)).setText(home.getString(R.string.ram_value, gb(used / gb), gb(mi.totalMem / gb)));
        row.setTag(widestFigures(mi.totalMem / gb));
    }

    /** Space where apps and their data go: used of total, like RAM. */
    void updateDisk(View row) {
        long free, total;
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            free = fs.getAvailableBytes();
            total = fs.getTotalBytes();
        } catch (IllegalArgumentException e) {
            return;
        }
        ProgressBar bar = row.findViewById(R.id.bar);
        bar.setProgress((int) (1000 * (total - free) / Math.max(1, total)));
        // Green while there's room, yellow under 1 GB free (app updates start failing), red under 200 MB.
        long mb = 1024 * 1024;
        int color = free > 1024 * mb ? 0xFF34C759 : free > 200 * mb ? 0xFFFFCC00 : 0xFFFF3B30;
        bar.setProgressTintList(ColorStateList.valueOf(color));
        float gb = 1024f * 1024 * 1024;
        ((TextView) row.findViewById(R.id.value)).setText(home.getString(R.string.ram_value, gb((total - free) / gb), gb(total / gb)));
        row.setTag(widestFigures(total / gb));
    }

    Intent getLiveTvIntent() {
        Intent launch = home.liveTvPkg.isEmpty() ? null : home.getPackageManager().getLeanbackLaunchIntentForPackage(home.liveTvPkg);
        if (launch == null) return null;
        // Launched plainly, the TV app resumes the last source, often an HDMI input (seen on the
        // XBR-940E). Ask it for the built-in tuner instead, when the TV has one.
        String tuner = builtInTuner();
        return tuner == null ? launch : new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tuner), "vnd.android.cursor.item/channel")
                .setPackage(home.liveTvPkg);
    }

    /** The TV's own tuner input (a system app, not e.g. an HDHomeRun app), or null. */
    String builtInTuner() {
        TvInputManager tim = (TvInputManager) home.getSystemService(Context.TV_INPUT_SERVICE);
        String tuner = null;
        if (tim == null) return null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info != null && info.getType() == TvInputInfo.TYPE_TUNER
                    && info.getServiceInfo() != null && info.getServiceInfo().applicationInfo != null
                    && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (tuner == null || info.getId().compareTo(tuner) < 0)) {
                tuner = info.getId();
            }
        }
        return tuner;
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    void showInputs() {
        View anchor = home.findViewById(R.id.btnInputs);
        View panel = home.getLayoutInflater().inflate(R.layout.popup_inputs, home.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = home.newMenu(panel);

        TvInputManager tim = (TvInputManager) home.getSystemService(Context.TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        // Settings → General: leave out HDMI inputs that say nothing is connected. Ones whose state the TV
        // doesn't know stay, and so do analog inputs (see addPassthroughRow), so nothing usable disappears.
        boolean hideInactive = new VibeSettings(home).hideInactiveInputs();
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(home)).compareToIgnoreCase(String.valueOf(b.loadLabel(home)));
            });
            // A device found over HDMI-CEC is a child input of its port: an Apple TV and a PS5 behind a switch
            // on HDMI 2 are two children of HDMI 2. Picking one has the TV ask over CEC for that device's
            // picture (and wakes it), which also turns a CEC-aware switch or receiver to it. One device shows
            // as "HDMI 2 · Apple TV"; several as an HDMI 2 heading with a row for each. Ordered by id, which
            // follows the device's address, so a switch's inputs come in order.
            Map<String, List<TvInputInfo>> devices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                if (info.getParentId() == null || info.isHidden(home)) continue;
                List<TvInputInfo> known = devices.get(info.getParentId());
                if (known == null) devices.put(info.getParentId(), known = new ArrayList<>());
                known.add(info);
            }
            for (List<TvInputInfo> list : devices.values()) {
                Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
            }
            for (TvInputInfo info : inputs) {
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(home) || info.getParentId() != null) continue;
                List<TvInputInfo> children = devices.get(info.getId());
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hideInactive) {
                    if (children != null) {
                        List<TvInputInfo> active = new ArrayList<>();
                        for (TvInputInfo child : children) if (!inactive(tim, child.getId())) active.add(child);
                        children = active.isEmpty() ? null : active;
                    }
                    // A port stays while any device behind it is connected.
                    if (children == null && hdmi && inactive(tim, info.getId())) continue;
                }
                CharSequence l = info.loadLabel(home);
                String label = l == null ? info.getId() : l.toString();
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                // The port, then the name given in Settings > Inputs.
                CharSequence custom = info.loadCustomLabel(home);
                if (custom != null && custom.length() > 0 && !custom.toString().equals(label)) label = label + " · " + custom;
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) home.addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                if (children == null) {
                    addPassthroughRow(rows, popup, hdmi, label, info.getId(), tim);
                } else if (children.size() == 1) {
                    addPassthroughRow(rows, popup, hdmi, label + " · " + name(children.get(0), label), children.get(0).getId(), tim);
                } else {
                    // Several devices (behind a switch or a receiver): the port as a heading, each device under it.
                    View heading = addPassthroughRow(rows, popup, hdmi, label, info.getId(), tim);
                    heading.setFocusable(false);
                    heading.setClickable(false);
                    // Just the port's name: each device under it says whether it's connected.
                    heading.findViewById(R.id.status).setVisibility(View.GONE);
                    heading.findViewById(R.id.dot).setVisibility(View.GONE);
                    for (TvInputInfo child : children) {
                        View row = addPassthroughRow(rows, popup, hdmi, name(child, label), child.getId(), tim);
                        // Indented: no icon, the name lines up under the port's.
                        row.findViewById(R.id.icon).setVisibility(View.INVISIBLE);
                    }
                }
            }
        }
        Intent liveTv = getLiveTvIntent();
        if (liveTv != null) {
            if (rows.getChildCount() > 0) home.addSeparator(rows);
            home.addInputRow(rows, popup, R.drawable.ic_livetv, home.getString(R.string.live_tv), -1, () -> home.open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            MainActivity.showError(home, R.string.no_inputs);
            return;
        }

        // Right-aligned to the pill so a wider menu grows leftwards instead of off the screen edge.
        home.showMenu(popup, panel, rows, anchor, Gravity.END);
    }

    /** An input that reports nothing connected (not one whose state the TV doesn't know). */
    static boolean inactive(TvInputManager tim, String id) {
        return tim.getInputState(id) == TvInputManager.INPUT_STATE_DISCONNECTED;
    }

    /** A device's name as the TV reports it over CEC, or the port's when it has none. */
    String name(TvInputInfo device, String port) {
        CharSequence name = device.loadLabel(home);
        return name == null || name.length() == 0 ? port : name.toString();
    }

    /**
     * A row that shows an HDMI or AV input (or a device on one). HDMI rows say whether something is plugged
     * in; AV, component and the other analog jacks can't detect it, so TVs report them as always connected
     * or always not (the 940E's Video 1 and 2 say "No device" with a player on them): no state for those.
     */
    View addPassthroughRow(LinearLayout rows, PopupWindow popup, boolean hdmi, String label, String id,
                                   TvInputManager tim) {
        int state = hdmi ? tim.getInputState(id) : -1;   // -1: neither, so no state is shown
        return home.addInputRow(rows, popup, hdmi ? R.drawable.ic_hdmi : R.drawable.ic_av, label, state,
                () -> home.openInput(id));
    }

    /** The connected Wi-Fi's link speed in Mbps, or -1 when unknown. */
    int wifiLinkSpeed() {
        WifiManager wm = (WifiManager) home.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        return info != null ? info.getLinkSpeed() : -1;
    }

    /** A read-only row (focus skips it): icon, title and, when given, a green dot with a status. */
    void addInfoRow(LinearLayout rows, int iconRes, String title, String status) {
        View row = home.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView statusView = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        if (status != null) {
            statusView.setText(status);
        } else {
            statusView.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setFocusable(false);
        row.setClickable(false);
        rows.addView(row);
    }
}
