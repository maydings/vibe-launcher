package com.maydings.vibelauncher;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

import com.maydings.vibelauncher.SettingsScreen.Page;
import com.maydings.vibelauncher.SettingsScreen.Row;

/**
 * Settings > Apps and what's under it (SettingsScreen's pages, kept apart from it for their size): the TV's own
 * network debugging and pairing with it (Adb), the clean-up lists and the apps one by one, the default launcher
 * and the Home button, and ad blocking. The pages, rows and windows are the screen's own.
 */
final class AdbSettingsPages {

    private final SettingsScreen screen;

    AdbSettingsPages(SettingsScreen screen) {
        this.screen = screen;
    }

    /** ADB was used while Settings was open: what Turn debugging off when done turns off as it closes. */
    boolean usedAdb;
    /** A look at the connection running now (pollAdb), so they don't pile up. */
    boolean adbLooking;
    boolean adbPolling;
    /** Apps, and Apps > ADB connection: the pages the connection is looked at for. */
    Page appsPage, adbPage;

    /** ADB calls one at a time, in order, off the main thread (Adb). */
    java.util.concurrent.ExecutorService adbThread;
    /** The last Adb answer (Adb.OK, OFF, …), or -1 while the first check runs. */
    int adbStatus = -1;
    boolean adbBusy;
    /** The apps on the Apps page now, with their rows: what Turn all back on brings up to date. */
    final List<TvApps.App> shownApps = new ArrayList<>();

    /**
     * Apps: the TV's own network debugging (Adb) and whether Vibe Launcher may use it (OK on it, or the first
     * thing done through it, makes the TV ask), then Debloat (the clean-up lists, then the apps one by one),
     * Default launcher (Vibe Launcher or the TV's own) and, last, Turn debugging off when done.
     */
    Page apps() {
        Page page = screen.new Page(screen.ctx.getString(R.string.tab_apps));
        appsPage = page;
        page.footnote = R.string.apps_short;
        Row connection = page.link(R.string.adb_connection, screen::adbStatusText, this::adbConnection);
        connection.about = R.string.adb_connection_short;
        // Whether it's allowed already, without making the TV ask.
        adbStatus = -1;
        adb().execute(() -> {
            Adb.Result r = Adb.check(false);
            screen.handler.post(() -> {
                adbStatus = r.status;
                page.refresh();
                if (r.status == Adb.OK) adbWorks();
            });
            // Paired as far as Vibe Launcher knows, and Wireless debugging on: does the TV still take the key?
            // (Turned down, AdbTlsKey forgets it and the row says Not paired.)
            if (Adb.wirelessOn(screen.ctx) && AdbTlsKey.paired(screen.ctx) && r.status != Adb.NOT_PAIRED) {
                Adb.checkWireless();
                screen.handler.post(page::refresh);
            }
        });
        pollAdb();
        Row debloat = page.link(R.string.debloat, null, this::debloat);
        debloat.about = R.string.apps_short;
        if (AdBlock.supported()) {
            Row ads = page.link(R.string.ad_blocking, this::adBlockName, this::adBlocking);
            ads.about = R.string.ad_blocking_short;
        }
        Row launcher = screen.defaultLauncherRow = page.link(R.string.default_launcher, () -> screen.ctx.getString(DefaultHome.isHome(screen.ctx)
                ? R.string.app_name : R.string.home_stock), this::defaultLauncher);
        launcher.about = TvControl.homeUnsupported() ? R.string.home_unsupported : R.string.home_make_short;
        // Right under Default launcher, the other way, for devices whose software won't let ADB be turned on: an
        // accessibility service that answers the Home button. Always listed; greyed out with a note while ADB works,
        // the recommended way. On, it turns off.
        homeButtonRow = page.add(R.string.home_button_accessibility,
                () -> screen.ctx.getString(DefaultHome.homeButtonOn(screen.ctx) ? R.string.on : R.string.off), () -> {
                    if (DefaultHome.homeButtonOn(screen.ctx)) {
                        homeButtonOff();
                        return;
                    }
                    // A live check first, both ways in (port 5555, opened by USB debugging or a player's own Network
                    // debugging switch, then Wireless debugging), not the last answer: only with no ADB at all is the
                    // service offered.
                    if (adbBusy) return;
                    adbBusy = true;
                    adb().execute(() -> {
                        Adb.Result r = Adb.check(false);
                        screen.handler.post(() -> {
                            adbBusy = false;
                            if (screen.host == null) return;
                            adbStatus = r.status;
                            showHomeButton();
                            if (r.status == Adb.OK || r.status == Adb.NOT_ALLOWED) {
                                // In, or only not allowed yet: ADB is there, Vibe Launcher above is the way.
                                MainActivity.showError(screen.host, R.string.home_button_adb_note);
                            } else if (Adb.everWorked(screen.ctx)) {
                                // Not now, but it has before: most likely debugging is only turned off. Said first;
                                // the service still there to choose.
                                screen.explainThenDo(R.string.home_button_accessibility, R.string.adb_worked_before,
                                        R.string.home_button_anyway, () -> HomeButtonLink.offer(screen.host, null, this::showHomeButton));
                            } else {
                                HomeButtonLink.offer(screen.host, null, this::showHomeButton);
                            }
                        });
                    });
                });
        showHomeButton();   // greyed out while ADB works: the poll above keeps it so as ADB comes or goes
        // Last: a choice most people never touch (and one that locks a computer's ADB out while it's on).
        Row offWhenDone = page.toggle(R.string.debug_off_when_done, screen.settings::adbOffWhenDone, screen.settings::setAdbOffWhenDone);
        offWhenDone.about = R.string.debug_off_when_done_short;
        return page;
    }

    /**
     * Apps > ADB connection: the status on its own at the top, the note under it setting it apart, with a dot (green while Vibe Launcher reaches
     * the TV's own ADB, red while it doesn't) and a line under it: connected, there's nothing to do here and Debloat
     * can go ahead. OK on it connects, or goes to the first thing still missing. Under it, everything that goes into
     * it, always shown: Developer options, then the two ways in, either of them enough: USB/ADB debugging (port 5555)
     * and, on Android 11 and up, Wireless debugging with its pairing. The way in use has its own green dot; OK on the
     * other while connected says it isn't needed, and goes on if asked. Looked at again every few seconds while open.
     */
    Page adbConnection() {
        Page page = screen.new Page(screen.ctx.getString(R.string.adb_connection));
        adbPage = page;
        page.footnote = R.string.adb_connection_short;
        Row status = page.add("", null, () -> {
            if (adbStatus != Adb.OK) runAdb(page, null, r -> {
            });
        });
        status.about = R.string.adb_connect_short;
        statusRow = status;
        statusNote = new TextView(screen.ctx);
        statusNote.setTextColor(0xCCFFFFFF);
        statusNote.setTextSize(15);
        statusNote.setLineSpacing(0, 1.15f);
        statusNote.setTypeface(UiFont.get(screen.ctx, R.string.font_regular));
        int side = (int) (16 * screen.density);
        statusNote.setPadding(side, (int) (8 * screen.density), side, (int) (22 * screen.density));
        page.list.addView(statusNote);
        Row dev = page.add(R.string.dev_options, () -> onOff(devOptionsOn()), this::openDevOrAbout);
        dev.about = R.string.dev_options_short;
        // The Shield's own Network debugging switch in place of USB debugging, which there is only its USB port.
        boolean ownSwitch = TvControl.networkDebugging(screen.ctx) != -1;
        Row usb = page.add(ownSwitch ? R.string.network_debugging : R.string.usb_adb_debugging,
                () -> inUse(!Adb.usedWireless(), onOff(ownSwitch ? TvControl.networkDebugging(screen.ctx) == 1
                        : adbSetting(android.provider.Settings.Global.ADB_ENABLED) == 1)),
                () -> unlessConnectedOtherWay(true, this::openDevOrAbout));
        usb.about = ownSwitch ? R.string.network_debugging_short : R.string.usb_adb_debugging_short;
        wirelessRow = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Row wireless = page.add(R.string.wireless_debugging, () -> inUse(Adb.usedWireless(),
                    Adb.wirelessNotRunning(screen.ctx) ? screen.ctx.getString(R.string.wireless_not_running)
                            : onOff(Adb.wirelessOn(screen.ctx))),
                    () -> unlessConnectedOtherWay(false, this::openDevOrAbout));
            wireless.about = R.string.wireless_debugging_onoff_short;
            wirelessRow = wireless;
            if (screen.link.pairUrl() != null) {
                Row pair = page.add(R.string.pair_with_code, () -> screen.ctx.getString(AdbTlsKey.paired(screen.ctx)
                        ? R.string.paired : R.string.not_paired), null);
                pair.action = () -> unlessConnectedOtherWay(false, () -> screen.open(pairWireless(), pair));
                pair.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
                pair.about = R.string.wireless_debugging_short;
            }
        }
        refreshAdbPages();
        pollAdb();
        return page;
    }

    /** ADB connection's status at the top, and the line under it. */
    Row statusRow;
    TextView statusNote;

    /** Apps > ADB connection > Wireless debugging: its text under the list says how to start it when it's stuck. */
    Row wirelessRow;

    boolean devOptionsOn() {
        return adbSetting(android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1;
    }

    /** The way Vibe Launcher is connected now has a green dot before its value. */
    CharSequence inUse(boolean thisWay, CharSequence value) {
        return adbStatus == Adb.OK && thisWay ? dot(0xFF34C759, value) : value;
    }

    static CharSequence dot(int color, CharSequence text) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder("\u25CF  ");
        b.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
        return b.append(text);
    }

    /**
     * Connected already, the other way (port 5555 for the wireless rows, Wireless debugging for USB/ADB debugging):
     * a window saying this isn't needed, and Continue goes on anyway. Not connected, or this way: straight on.
     */
    void unlessConnectedOtherWay(boolean portWay, Runnable then) {
        boolean otherWay = adbStatus == Adb.OK && (portWay == Adb.usedWireless());
        if (otherWay) screen.explainThenDo(R.string.adb_connection, R.string.adb_not_needed, then);
        else then.run();
    }

    CharSequence onOff(boolean on) {
        return screen.ctx.getString(on ? R.string.on : R.string.off);
    }

    /** Developer options, or (still locked) the window saying how to unlock them, then About. */
    void openDevOrAbout() {
        if (adbSetting(android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1) openDeveloperOptionsNow();
        else screen.explainThenOpen(R.string.adb_dev_options, screen::openAbout);
    }

    /**
     * Every three seconds while Apps or ADB connection is open: the connection looked at again (off
     * the main thread, one look at a time), and the rows brought up to date, so whatever was just turned on shows.
     */
    void pollAdb() {
        if (adbPolling) return;
        adbPolling = true;
        screen.handler.post(adbPoll);
    }

    /** The rows brought up to date, ADB connection's status with them. */
    void refreshAdbPages() {
        for (Page page : screen.pages) page.refresh();
        showHomeButton();
        if (statusRow != null) {
            statusRow.label.setText(screen.adbStatusText());
            statusNote.setText(adbStatus == Adb.OK ? R.string.adb_ready_note : R.string.adb_connect_short);
        }
        if (wirelessRow != null) {
            wirelessRow.about = Adb.wirelessNotRunning(screen.ctx) ? R.string.wireless_not_running_short
                    : R.string.wireless_debugging_onoff_short;
            if (wirelessRow.view.hasFocus() && adbPage != null) screen.showAbout(adbPage, wirelessRow);
        }
    }

    final Runnable adbPoll = new Runnable() {
        @Override
        public void run() {
            Page top = screen.pages.isEmpty() ? null : screen.pages.get(screen.pages.size() - 1);
            boolean showing = screen.host != null && top != null && (top == adbPage || top == appsPage);
            if (!showing) {
                adbPolling = false;
                return;
            }
            if (!adbBusy && !adbLooking) {
                adbLooking = true;
                adb().execute(() -> {
                    Adb.Result r = Adb.check(false);
                    screen.handler.post(() -> {
                        adbLooking = false;
                        if (!adbBusy) adbStatus = r.status;
                        refreshAdbPages();
                        if (adbStatus == Adb.OK) adbWorks();
                    });
                });
            } else {
                refreshAdbPages();
            }
            screen.handler.postDelayed(this, 3000);
        }
    };

    /**
     * Debloat: Clean Your TV's lists first, then the options: Turn all back on, Turn off again (only while there's
     * something to), and the apps one by one, those you can turn off and those the TV needs.
     */
    Page debloat() {
        Page page = screen.new Page(screen.ctx.getString(R.string.debloat));
        page.footnote = R.string.apps_short;
        Row lists = page.link(R.string.cyt_lists, null, this::cleanLists);
        lists.about = R.string.cyt_lists_short;
        Row undo = page.add(R.string.apps_turn_all_on, () -> {
            int n = TvApps.turnedOff(screen.ctx).size();
            return n == 0 ? "" : String.valueOf(n);
        }, () -> turnAllOn(page));
        undo.about = R.string.apps_turn_all_on_short;
        // After a restore (or an update turning some back on): there only while there's something to do.
        Row again = page.link(R.string.apps_turn_off_again, () -> {
            int n = TvApps.toTurnOffAgain(screen.ctx).size();
            return n == 0 ? "" : String.valueOf(n);
        }, () -> turnOffAgain(page));
        again.about = R.string.apps_turn_off_again_short;
        turnOffAgainRow = again;
        showTurnOffAgain();
        page.link(R.string.apps_can_turn_off, null, () -> appList(false));
        page.link(R.string.apps_tv_needs, null, () -> appList(true));
        return page;
    }

    /**
     * Default launcher: Vibe Launcher or the TV's own home screen, the one in use ticked; picking the other
     * switches (Vibe Launcher after a window saying what happens). The TV's own is only there while it has one
     * that can come back; on Google's own players only the TV's own is offered (another Home stops casting).
     */
    Page defaultLauncher() {
        Page page = screen.new Page(screen.ctx.getString(R.string.default_launcher));
        boolean unsupported = TvControl.homeUnsupported();
        page.footnote = unsupported ? R.string.home_unsupported : R.string.home_make_warning;
        Row vibe = page.add(screen.ctx.getString(R.string.app_name), () -> DefaultHome.isHome(screen.ctx) ? "✓" : "", () -> {
            if (DefaultHome.isHome(screen.ctx) && TvControl.stockOn(screen.ctx).isEmpty()) return;   // already
            if (unsupported) {
                MainActivity.showError(screen.host, R.string.home_unsupported);
                return;
            }
            screen.explainThenDo(R.string.home_make, R.string.home_make_warning, () -> makeHome(page));
        });
        Row stock = page.add(R.string.home_stock, () -> DefaultHome.isHome(screen.ctx) ? "" : "✓", () -> {
            if (DefaultHome.isHome(screen.ctx)) restoreHome(page);
        });
        // The stock launcher only while there's one turned off to bring back (or it's in use now).
        stock.view.setVisibility(!DefaultHome.isHome(screen.ctx) || !TvControl.stockOff(screen.ctx).isEmpty() ? View.VISIBLE : View.GONE);
        page.opened = DefaultHome.isHome(screen.ctx) ? vibe : stock;
        return page;
    }

    /** Advanced's accessibility Home button row, while it's made. */
    Row homeButtonRow;
    /** The switch from the Home button service to ADB has been offered this visit to Settings. */
    boolean switchOffered;

    /** The accessibility row as the service and ADB are now; again when Settings is back from Accessibility. */
    void showHomeButton() {
        if (homeButtonRow == null) return;
        homeButtonRow.refresh();
        boolean greyed = adbStatus == Adb.OK && !DefaultHome.homeButtonOn(screen.ctx);
        homeButtonRow.label.setAlpha(greyed ? 0.45f : 1f);
        homeButtonRow.value.setAlpha(greyed ? 0.45f : 1f);
        homeButtonRow.about = greyed ? R.string.home_button_adb_note : R.string.home_button_short;
        if (homeButtonRow.view.hasFocus() && appsPage != null) screen.showAbout(appsPage, homeButtonRow);
    }

    /** The Home button service off: by itself while it's running, else from the TV's Accessibility settings. */
    void homeButtonOff() {
        Runnable off = HomeButtonLink.turnOff;
        if (off != null) off.run();
        else if (screen.host != null) HomeButtonLink.openSettings(screen.host);
        screen.handler.postDelayed(this::showHomeButton, 300);   // Android notes it a moment later
    }

    /**
     * ADB works now, while the Home button service is on: asked once a visit whether to make Vibe Launcher Home
     * through ADB and turn the service off. Cancel leaves both as they are. Already Home that way, only the
     * service goes.
     */
    void adbWorks() {
        if (switchOffered || screen.host == null || !DefaultHome.homeButtonOn(screen.ctx)) return;
        switchOffered = true;
        screen.explainThenDo(R.string.adb_found_title, R.string.adb_found, R.string.home_make, () -> {
            if (DefaultHome.isHome(screen.ctx) && TvControl.stockOn(screen.ctx).isEmpty()) {
                homeButtonOff();
                return;
            }
            makeHome(screen.pages.get(screen.pages.size() - 1), this::homeButtonOff);
        });
    }

    /**
     * The TV's own launchers off and Vibe Launcher set as Home, in one command that turns them back on if a step
     * fails; then Android asked where Home goes now. Not here: the launchers back on, and saying so. Here: they
     * go in our record, so Turn all back on brings them back too.
     */
    void makeHome(Page page) {
        makeHome(page, null);
    }

    /** As makeHome, then done once Vibe Launcher is Home (the Home button service turned off, after adbWorks). */
    void makeHome(Page page, Runnable done) {
        List<String> stock = TvControl.stockOn(screen.ctx);
        runAdb(page, TvControl.makeHomeCommand(screen.ctx, stock), r -> {
            boolean failed = r.output.contains("FAILED") || !DefaultHome.isHome(screen.ctx);
            if (failed && !r.output.contains("FAILED") && !stock.isEmpty()) {
                // Set, but Home still goes elsewhere: the launchers back on, as they were.
                adb().execute(() -> Adb.shell(TvControl.restoreHomeCommand(screen.ctx, stock), false));
            }
            if (!failed) for (String pkg : stock) TvApps.remember(screen.ctx, pkg, false);
            if (failed) MainActivity.showError(screen.host, R.string.home_failed);
            else Toast.makeText(screen.host, R.string.home_done, Toast.LENGTH_LONG).show();
            if (!failed && done != null) done.run();
            page.refresh();   // the tick moves (runAdb refreshes once before the restore above lands)
            showTurnOffAgain();
        });
    }

    /** The TV's own launchers back on, Home given to the first, and taken out of our record. */
    void restoreHome(Page page) {
        List<String> stock = TvControl.stockOff(screen.ctx);
        if (stock.isEmpty()) return;
        runAdb(page, TvControl.restoreHomeCommand(screen.ctx, stock), r -> {
            android.content.pm.PackageManager pm = screen.ctx.getPackageManager();
            for (String pkg : stock) if (TvApps.isOn(pm, pkg)) TvApps.remember(screen.ctx, pkg, true);
            // The Home button service would cover the stock home screen as soon as it showed, and going back would
            // seem not to work: off with it too.
            if (DefaultHome.homeButtonOn(screen.ctx)) homeButtonOff();
            page.refresh();
            showTurnOffAgain();
        });
    }

    /**
     * The apps you can turn off (needed false), On or Off, OK switching each; or (true) the ones the TV needs,
     * grayed, where OK only says why they stay on. Made off the main thread (each app's name is read from it)
     * and its rows a few at a time, so the page slides in at once.
     */
    Page appList(boolean needed) {
        Page page = screen.new Page(screen.ctx.getString(needed ? R.string.apps_tv_needs : R.string.apps_can_turn_off));
        page.footnote = needed ? R.string.app_protected : R.string.apps_short;
        if (!needed) shownApps.clear();
        Context app = screen.ctx.getApplicationContext();
        new Thread(() -> {
            List<TvApps.App> apps = TvApps.list(app, needed);
            screen.handler.post(() -> addAppRows(page, apps, 0));
        }, "TvApps").start();
        return page;
    }

    // ---- ad blocking -----------------------------------------------------------------------------------

    /** Ad blocking while it's open, its Add custom DNS row and phone page, and the hostname being checked. */
    Page adBlockPage, adPhonePage;
    Row adAddRow;
    String adChecking;
    /** The person's own services' rows on the open Ad blocking page, by hostname (lower case). */
    final java.util.Map<String, Row> adCustomRows = new java.util.HashMap<>();

    /** What Ad blocking uses now: Off, a service's name, or a hostname of the device's own. */
    String adBlockName() {
        String now = AdBlock.active(screen.ctx);
        return now == null ? screen.ctx.getString(R.string.off) : AdBlock.nameOf(screen.ctx, now);
    }

    /** A choice's mark: ticked when in use, Checking… while it's being tried. */
    String adMark(String hostname) {
        if (adChecking != null) return adChecking.equalsIgnoreCase(hostname) ? screen.ctx.getString(R.string.ad_checking) : "";
        String now = AdBlock.active(screen.ctx);
        return (now == null ? hostname == null : now.equalsIgnoreCase(hostname)) ? "✓" : "";
    }

    /**
     * Advanced > Ad blocking (AdBlock): Off, three filtering DNS services, the person's own (each with its name),
     * and Add custom DNS, which opens the phone's page for a name and an address. Picked, a choice is tried and the
     * list stays; one that doesn't answer is undone, with a note. Hold OK on one's own: Delete.
     */
    Page adBlocking() {
        Page page = screen.new Page(screen.ctx.getString(R.string.ad_blocking));
        adBlockPage = page;
        adCustomRows.clear();
        page.footnote = R.string.ad_blocking_short;
        String now = AdBlock.active(screen.ctx);
        Row off = page.add(R.string.off, () -> adMark(null), () -> chooseAdBlock(null));
        if (now == null) page.opened = off;
        for (int i = 0; i < AdBlock.HOSTS.length; i++) {
            Row row = adRow(page, AdBlock.NAMES[i], AdBlock.HOSTS[i]);
            if (i == 1) row.about = R.string.ad_family_short;
        }
        for (String[] c : AdBlock.customs(screen.ctx)) customAdRow(page, c[0], c[1]);
        adAddRow = page.add(R.string.ad_custom_add, null, () -> {
            if (screen.phoneUrl == null || screen.link == null || screen.link.dnsUrl() == null) {
                MainActivity.showError(screen.host, R.string.ad_no_network);
                return;
            }
            adPhonePage = screen.phone(R.string.ad_custom_add, screen.link.dnsUrl(), R.string.ad_custom_scan);
            screen.open(adPhonePage, adAddRow);
        });
        adAddRow.about = R.string.ad_custom_add_short;
        adAddRow.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        return page;
    }

    Row adRow(Page page, String name, String hostname) {
        Row row = page.add(name, () -> adMark(hostname), () -> chooseAdBlock(hostname));
        if (hostname.equalsIgnoreCase(AdBlock.active(screen.ctx))) page.opened = row;
        return row;
    }

    /** One of the person's own: its address under the list, and Hold OK to delete it. */
    Row customAdRow(Page page, String name, String hostname) {
        Row row = adRow(page, name, hostname);
        row.aboutText = screen.ctx.getString(R.string.ad_custom_item_short, hostname);
        adCustomRows.put(hostname.toLowerCase(java.util.Locale.ROOT), row);
        row.view.setOnLongClickListener(v -> {
            askDeleteCustomAd(page, row, hostname);
            return true;
        });
        return row;
    }

    /** "Delete this item from your device?", Cancel first, as in the Library. In use, ad blocking goes off first. */
    void askDeleteCustomAd(Page page, Row row, String hostname) {
        if (screen.host == null || adChecking != null) return;
        screen.confirm(screen.ctx.getString(R.string.media_delete_title), null, R.string.wp_delete, true, () -> {
            if (hostname.equalsIgnoreCase(AdBlock.active(screen.ctx))) AdBlock.off(screen.ctx);
            AdBlock.removeCustom(screen.ctx, hostname);
            adCustomRows.remove(hostname.toLowerCase(java.util.Locale.ROOT));
            int at = page.rows.indexOf(row);
            page.rows.remove(row);
            page.stops.remove(row.view);
            page.list.removeView(row.view);
            if (page.opened == row) page.opened = null;
            refreshAdBlock();
            Row next = at >= 0 && at < page.rows.size() ? page.rows.get(at) : adAddRow;
            if (next != null) next.view.requestFocus();
        });
    }

    /** A service the phone sent is being asked about (customAdFromPhone): one at a time. */
    boolean adAsking;

    /**
     * A service the phone sent (PhoneLink's DNS page). It changes where every app's lookups go, so it's asked
     * here first, with its name and address, Cancel first: nothing is added or turned on without Continue.
     */
    void customAdFromPhone(String name, String hostname) {
        if (screen.host == null || adAsking) return;
        String label = name == null ? "" : name.replaceAll("\\s+", " ").trim();
        adAsking = true;
        Dialog asked = screen.confirm(screen.ctx.getString(R.string.ad_custom_add),
                label.isEmpty() || label.equalsIgnoreCase(hostname) ? hostname : label + "\n" + hostname,
                R.string.continue_button, true, () -> addCustomAd(name, hostname));
        if (asked == null) adAsking = false;
        else asked.setOnDismissListener(d -> adAsking = false);
    }

    /** The phone's service, agreed to here: its row (or its new name), back to the list, turned on. */
    void addCustomAd(String name, String hostname) {
        if (screen.host == null) return;
        AdBlock.addCustom(screen.ctx, name, hostname);
        if (adBlockPage != null && screen.pages.contains(adBlockPage)) {
            Row known = adCustomRows.get(hostname.toLowerCase(java.util.Locale.ROOT));
            if (known == null) {
                // Before Add custom DNS, after the others.
                Row row = customAdRow(adBlockPage, AdBlock.nameOf(screen.ctx, hostname), hostname);
                adBlockPage.list.removeView(row.view);
                adBlockPage.list.addView(row.view, adBlockPage.list.indexOfChild(adAddRow.view));
                adBlockPage.rows.remove(row);
                adBlockPage.rows.add(adBlockPage.rows.indexOf(adAddRow), row);
                adBlockPage.stops.remove(row.view);
                adBlockPage.stops.add(adBlockPage.stops.indexOf(adAddRow.view), row.view);
            } else {
                known.label.setText(AdBlock.nameOf(screen.ctx, hostname));   // sent again: renamed
            }
            if (adPhonePage != null && !screen.pages.isEmpty() && screen.pages.get(screen.pages.size() - 1) == adPhonePage) screen.back();
        }
        adPhonePage = null;
        chooseAdBlock(hostname);
    }

    /** Ad blocking off (null) or through a hostname: set, then checked off the main thread, undone if it fails. */
    void chooseAdBlock(String hostname) {
        if (adChecking != null) return;   // one at a time
        if (!TvControl.canWrite(screen.ctx)) {
            MainActivity.showError(screen.host, R.string.ad_needs_adb);
            return;
        }
        String now = AdBlock.active(screen.ctx);
        if (hostname == null ? now == null : hostname.equalsIgnoreCase(now)) return;
        if (hostname == null) {
            if (!AdBlock.off(screen.ctx)) MainActivity.showError(screen.host, R.string.ad_needs_adb);
            refreshAdBlock();
            return;
        }
        adChecking = hostname;
        refreshAdBlock();
        Context app = screen.ctx.getApplicationContext();
        new Thread(() -> {
            boolean ok = AdBlock.use(app, hostname) && AdBlock.check(app, hostname);
            if (!ok) AdBlock.off(app);   // never left with names that don't resolve
            screen.handler.post(() -> {
                adChecking = null;
                refreshAdBlock();
                if (!ok && screen.host != null) {
                    MainActivity.showError(screen.host, screen.ctx.getString(R.string.ad_failed, AdBlock.nameOf(screen.ctx, hostname)));
                }
            });
        }, "AdBlock").start();
    }

    void refreshAdBlock() {
        if (adBlockPage != null) adBlockPage.refresh();
        if (appsPage != null) appsPage.refresh();
    }

    java.util.concurrent.ExecutorService adb() {
        if (adbThread == null) adbThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        return adbThread;
    }

    /** A dozen more rows a frame, while the page is still open. */
    void addAppRows(Page page, List<TvApps.App> apps, int from) {
        if (!screen.pages.contains(page)) return;
        int to = Math.min(apps.size(), from + 12);
        for (int i = from; i < to; i++) {
            TvApps.App a = apps.get(i);
            Row row = page.add(a.label, () -> screen.ctx.getString(a.on ? R.string.on : R.string.off), null);
            if (a.needed) {
                // Grayed, but still focusable, so OK can say why: the TV needs it, and it stays as it is.
                row.label.setAlpha(0.45f);
                row.value.setAlpha(0.45f);
                row.aboutText = a.pkg + "\n" + screen.ctx.getString(R.string.app_protected);
                row.action = () -> MainActivity.showError(screen.host, R.string.app_protected);
                continue;
            }
            row.aboutText = a.pkg;
            row.action = () -> runAdb(page, TvApps.command(a.pkg, !a.on), r -> {
                boolean wanted = !a.on;
                a.on = TvApps.isOn(screen.ctx.getPackageManager(), a.pkg);
                if (a.on == wanted) TvApps.remember(screen.ctx, a.pkg, a.on);
                else MainActivity.showError(screen.host, R.string.adb_failed);
            });
            shownApps.add(a);
        }
        if (to < apps.size()) screen.handler.postDelayed(() -> addAppRows(page, apps, to), 16);
    }

    /**
     * Clean-up lists (CleanLists): Clean Your TV's lists, this TV's brand first and the best matches at the top,
     * each with its TV's name, Android version and notes; OK opens its plan. Fetched as the page opens.
     */
    Page cleanLists() {
        Page page = screen.new Page(screen.ctx.getString(R.string.cyt_lists));
        page.footnote = R.string.cyt_credit;
        Row loading = page.add(R.string.cyt_loading, null, null);
        Context app = screen.ctx.getApplicationContext();
        new Thread(() -> {
            List<CleanLists.Tv> tvs;
            try {
                tvs = CleanLists.lists(app);
            } catch (java.io.IOException e) {
                screen.handler.post(() -> loading.label.setText(R.string.cyt_failed));
                return;
            }
            screen.handler.post(() -> {
                if (!screen.pages.contains(page)) return;
                addListRows(page, tvs, 0);
                // Focus on the best match before Loading goes, or it would land wherever Android picks.
                if (loading.view.isFocused() && page.rows.size() > 1) page.rows.get(1).view.requestFocus();
                loading.view.setVisibility(View.GONE);
                page.stops.remove(loading.view);
            });
        }, "CleanLists").start();
        return page;
    }

    /** A dozen list rows a frame (there are hundreds), while the page is still open. */
    void addListRows(Page page, List<CleanLists.Tv> tvs, int from) {
        if (!screen.pages.contains(page)) return;
        int to = Math.min(tvs.size(), from + 12);
        for (int i = from; i < to; i++) {
            CleanLists.Tv tv = tvs.get(i);
            Row row = page.add(tv.shortName(), () -> tv.os, null);
            row.aboutText = tv.about();
            row.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
            row.action = () -> screen.open(cleanPlan(tv), row);
        }
        if (to < tvs.size()) screen.handler.postDelayed(() -> addListRows(page, tvs, to), 16);
    }

    /**
     * One list's plan for this TV: only what's installed here, each app with the list's section and reason under
     * the list. ✓ is what Turn off N apps turns off, OK on an app changing it; the maker's "by choice" sections start
     * unticked, what's already off shows Off, and what the TV needs is grayed and stays. Turned off, they go in our
     * own record, so Turn all back on undoes them whatever the list says later.
     */
    Page cleanPlan(CleanLists.Tv tv) {
        Page page = screen.new Page(tv.shortName());
        page.footnote = R.string.cyt_plan_short;
        Row go = page.add(R.string.cyt_loading, null, null);
        Context app = screen.ctx.getApplicationContext();
        new Thread(() -> {
            List<CleanLists.Item> items;
            try {
                items = CleanLists.items(app, tv.folder);
            } catch (java.io.IOException e) {
                screen.handler.post(() -> go.label.setText(R.string.cyt_failed));
                return;
            }
            java.util.Set<String> unique = new java.util.LinkedHashSet<>();   // once each, in the list's order
            for (CleanLists.Item item : items) unique.add(item.pkg);
            List<String> pkgs = new ArrayList<>(unique);
            List<TvApps.App> apps = TvApps.installed(app, pkgs);
            screen.handler.post(() -> {
                if (screen.pages.contains(page)) showPlan(page, go, items, apps);
            });
        }, "CleanLists").start();
        return page;
    }

    void showPlan(Page page, Row go, List<CleanLists.Item> items, List<TvApps.App> apps) {
        java.util.Map<String, CleanLists.Item> byPkg = new java.util.HashMap<>();
        for (CleanLists.Item item : items) if (!byPkg.containsKey(item.pkg)) byPkg.put(item.pkg, item);
        java.util.Set<String> ticked = new java.util.LinkedHashSet<>();
        for (TvApps.App a : apps) {
            if (!a.needed && a.on && !byPkg.get(a.pkg).optional) ticked.add(a.pkg);
        }
        Runnable count = () -> go.label.setText(screen.ctx.getString(R.string.cyt_turn_off_n, ticked.size()));
        count.run();
        go.action = () -> {
            if (ticked.isEmpty()) return;
            List<String> chosen = new ArrayList<>(ticked);
            StringBuilder cmd = new StringBuilder();
            for (String pkg : chosen) cmd.append(cmd.length() == 0 ? "" : "; ").append(TvApps.command(pkg, false));
            runAdb(page, cmd.toString(), r -> {
                android.content.pm.PackageManager pm = screen.ctx.getPackageManager();
                int off = 0;
                for (String pkg : chosen) {
                    if (TvApps.isOn(pm, pkg)) continue;
                    TvApps.remember(screen.ctx, pkg, false);
                    ticked.remove(pkg);
                    off++;
                }
                for (TvApps.App a : apps) a.on = TvApps.isOn(pm, a.pkg);
                Toast.makeText(screen.host, off == chosen.size() ? screen.ctx.getString(R.string.cyt_done, off)
                        : screen.ctx.getString(R.string.adb_failed), Toast.LENGTH_SHORT).show();
                count.run();
                page.refresh();
            });
        };
        for (TvApps.App a : apps) {
            CleanLists.Item item = byPkg.get(a.pkg);
            Row row = page.add(a.label, () -> a.needed ? "" : !a.on ? screen.ctx.getString(R.string.off)
                    : ticked.contains(a.pkg) ? "✓" : "", null);
            StringBuilder about = new StringBuilder();
            // Short enough for the space under the list: some sections are a sentence long.
            if (!item.section.isEmpty()) about.append(CleanLists.trim(item.section, 70)).append('\n');
            if (!item.reason.isEmpty()) about.append(CleanLists.trim(item.reason, 90)).append('\n');
            about.append(a.pkg);
            if (a.needed) {
                about.append('\n').append(screen.ctx.getString(R.string.app_protected));
                row.label.setAlpha(0.45f);
                row.value.setAlpha(0.45f);
                row.action = () -> MainActivity.showError(screen.host, R.string.app_protected);
            } else {
                row.action = () -> {
                    if (!a.on) return;   // off already
                    if (!ticked.remove(a.pkg)) ticked.add(a.pkg);
                    count.run();
                };
            }
            row.aboutText = about;
        }
    }

    /** Apps' Turn off again row, while that page is made: shown only while there's something to turn off. */
    Row turnOffAgainRow;

    void showTurnOffAgain() {
        if (turnOffAgainRow == null) return;
        turnOffAgainRow.view.setVisibility(TvApps.toTurnOffAgain(screen.ctx).isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * The apps to turn off again (TvApps.toTurnOffAgain), listed first so it's clear what goes, with Turn off
     * now at the top doing them all in one go; then back to Apps, its row gone once there's nothing left.
     */
    Page turnOffAgain(Page apps) {
        Page page = screen.new Page(screen.ctx.getString(R.string.apps_turn_off_again));
        page.footnote = R.string.apps_turn_off_again_short;
        List<TvApps.App> list = TvApps.toTurnOffAgain(screen.ctx);
        page.add(R.string.apps_turn_off_now, () -> String.valueOf(list.size()), () -> {
            StringBuilder cmd = new StringBuilder();
            for (TvApps.App a : list) cmd.append(cmd.length() == 0 ? "" : "; ").append(TvApps.command(a.pkg, false));
            if (list.isEmpty()) return;
            runAdb(page, cmd.toString(), r -> {
                android.content.pm.PackageManager pm = screen.ctx.getPackageManager();
                boolean all = true;
                for (TvApps.App a : list) {
                    a.on = TvApps.isOn(pm, a.pkg);
                    all &= !a.on;
                }
                if (all) Toast.makeText(screen.host, R.string.apps_all_off, Toast.LENGTH_SHORT).show();
                else MainActivity.showError(screen.host, R.string.adb_failed);
                page.refresh();
                showTurnOffAgain();   // gone from Apps once there's nothing left
                if (all) screen.back();
            });
        });
        for (TvApps.App a : list) {
            Row row = page.add(a.label, () -> screen.ctx.getString(a.on ? R.string.on : R.string.off), null);
            row.aboutText = a.pkg;
        }
        return page;
    }

    /** Every app turned off here and still off, back on in one go. */
    void turnAllOn(Page page) {
        List<String> off = TvApps.turnedOff(screen.ctx);
        StringBuilder cmd = new StringBuilder();
        for (String pkg : off) cmd.append(cmd.length() == 0 ? "" : "; ").append(TvApps.command(pkg, true));
        Runnable tidy = () -> {
            // The record keeps only what's still off (anything turned on by other means is forgotten too).
            android.content.pm.PackageManager pm = screen.ctx.getPackageManager();
            java.util.Set<String> still = new java.util.HashSet<>();
            for (String pkg : screen.settings.appsOff()) if (!TvApps.isOn(pm, pkg)) still.add(pkg);
            screen.settings.setAppsOff(still);
            for (TvApps.App a : shownApps) a.on = TvApps.isOn(pm, a.pkg);
            if (still.isEmpty()) Toast.makeText(screen.host, R.string.apps_all_on, Toast.LENGTH_SHORT).show();
            else MainActivity.showError(screen.host, R.string.adb_failed);
            page.refresh();
            showTurnOffAgain();   // what was on is forgotten now: nothing left to turn off again
        };
        if (off.isEmpty()) tidy.run();   // nothing to ask the TV for
        else runAdb(page, cmd.toString(), r -> tidy.run());
    }

    /**
     * Runs a shell command through the TV's ADB (or, command null, only connects), asking the TV to allow
     * Vibe Launcher if it hasn't yet; then, back on the main thread and only if it got in, then. Debugging off
     * opens Developer options, saying what to turn on. One at a time: OK pressed meanwhile does nothing.
     */
    void runAdb(Page page, String command, java.util.function.Consumer<Adb.Result> then) {
        runAdb(page, command, then, null);
    }

    /** As runAdb, with otherwise run instead of the explanations when there's no way in (null for those). */
    void runAdb(Page page, String command, java.util.function.Consumer<Adb.Result> then, Runnable otherwise) {
        if (adbBusy || screen.host == null) return;
        // Developer options not unlocked yet: its About screen, to unlock them. A Global setting any app may read.
        int devOptions = adbSetting(android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED);
        int debugging = adbSetting(android.provider.Settings.Global.ADB_ENABLED);
        int network = TvControl.networkDebugging(screen.ctx);   // the Shield's Network debugging; -1 on other TVs
        if (debugging != 1 && network != 1 && devOptions != 1 && !Adb.wirelessOn(screen.ctx)) {
            adbStatus = Adb.OFF;
            if (otherwise != null) {
                otherwise.run();
                return;
            }
            screen.explainThenOpen(R.string.adb_dev_options, screen::openAbout);
            page.refresh();
            return;
        }
        android.util.Log.i("VibeAdb", "runAdb: devOptions=" + devOptions + " adb_enabled=" + debugging
                + " network=" + network + " wireless=" + Adb.wirelessSetting(screen.ctx) + " paired=" + AdbTlsKey.paired(screen.ctx));
        // USB debugging off isn't taken as no way in: some players open port 5555 with a switch of their own (the
        // Shield's Network debugging) while USB debugging stays off. Tried first; only if nothing answers is USB
        // debugging turned on (with WRITE_SECURE_SETTINGS) or asked for; never on a player with a network switch of
        // its own, where USB debugging is only its USB port and changing it restarts adbd (dropping the connection).
        boolean usbOff = debugging != 1 && network == -1;
        // Not let in yet: the TV is about to ask whether to allow Vibe Launcher. Said in a window first (a message
        // at the bottom went before it could be read), and Continue connects, which brings up the TV's question.
        boolean asks = adbStatus != Adb.OK && adbStatus != Adb.OFF && adbStatus != Adb.NOT_PAIRED;
        Runnable go = () -> {
        if (adbBusy || screen.host == null) return;
        adbBusy = true;
        if (command == null) {   // a connect (the status row): Connecting…, shown at once
            adbConnecting = true;
            refreshAdbPages();
        }
        Context app = screen.ctx.getApplicationContext();
        // A connect gives up 15 seconds after OK: not answered on the TV by then, it's Not connected again (red),
        // never left on Connecting.
        long deadline = System.currentTimeMillis() + 15000;
        adb().execute(() -> {
            Adb.Result first = command != null ? Adb.shell(command, true) : Adb.check(true, deadline);
            // Nothing answered with USB debugging off: on now, given WRITE_SECURE_SETTINGS before (TvControl), with
            // no trip to Developer options. adbd takes a moment to open its port: up to ten seconds.
            if (first.status == Adb.OFF && usbOff && TvControl.setDebugging(app, true)) {
                for (int i = 0; i < 20 && System.currentTimeMillis() < deadline - 5000
                        && Adb.check(false).status == Adb.OFF; i++) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                first = command != null ? Adb.shell(command, true) : Adb.check(true, deadline);
            }
            // Port 5555 shut, paired for Wireless debugging and that's off: on again (with the permission), and again.
            if (first.status == Adb.OFF && TvControl.bringUpWireless(app)) {
                first = command != null ? Adb.shell(command, true) : Adb.check(true, deadline);
            }
            Adb.Result r = first;
            // In, and not given WRITE_SECURE_SETTINGS yet: given now, once (what it's used for: TvControl).
            if (r.status == Adb.OK && !TvControl.canWrite(app)) Adb.shell(TvControl.grantCommand(app), false);
            screen.handler.post(() -> {
                adbBusy = false;
                adbConnecting = false;
                adbStatus = r.status;
                if (r.status == Adb.OK) usedAdb = true;
                if (screen.host == null) return;
                int net = TvControl.networkDebugging(screen.ctx);
                boolean stillOff = net != -1 ? net != 1
                        : adbSetting(android.provider.Settings.Global.ADB_ENABLED) != 1;
                if (r.status == Adb.OK) {
                    then.accept(r);
                    adbWorks();
                } else if (otherwise != null) {
                    otherwise.run();
                } else if (r.status == Adb.OFF && stillOff && !Adb.wirelessOn(screen.ctx)) {
                    // Nothing open at all: the player's Network debugging, else USB debugging (or the TV's name for it).
                    if (net != -1) screen.explainThenOpen(R.string.adb_network_turn_on, this::openDeveloperOptionsNow);
                    else openDeveloperOptions();
                } else if (r.status == Adb.OFF && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    offerWireless();
                } else if (r.status == Adb.OFF) {
                    // Debugging on, but nothing on port 5555: this TV keeps it to USB; a switch of its own may open
                    // it to the network.
                    screen.explainThenOpen(R.string.adb_network_closed, this::openDeveloperOptionsNow);
                } else if (r.status == Adb.NOT_PAIRED) {
                    offerWireless();   // Wireless debugging turned our key down: forgotten (Revoke), pair again
                } else {
                    MainActivity.showError(screen.host, r.status == Adb.NO_KEY ? R.string.adb_no_key : R.string.adb_failed);
                }
                page.refresh();
            });
        });
        };
        if (!asks) {
            go.run();
            return;
        }
        // Not known yet (Settings > Apps not opened this time): asked quietly first, and the window only when the TV
        // really is going to ask. Already allowed, the action goes straight on.
        adbBusy = true;
        adb().execute(() -> {
            Adb.Result known = Adb.check(false);
            screen.handler.post(() -> {
                adbBusy = false;
                if (screen.host == null) return;
                adbStatus = known.status;
                if (known.status == Adb.OK || known.status == Adb.OFF || known.status == Adb.NOT_PAIRED) go.run();
                else screen.explainThenDo(R.string.adb_connection, R.string.adb_allow_prompt, go);
            });
        });
    }

    /** A connect from the status row running: Connecting…, yellow, until there's an answer (15 seconds at most). */
    boolean adbConnecting;

    /**
     * Android 11 and up, debugging on but port 5555 shut: Wireless debugging. Not on Wi-Fi, it can't be; paired,
     * it's off (and Vibe Launcher wasn't given the permission to turn it on): Developer options; not paired: the
     * pairing page, after a window saying what's to come.
     */
    void offerWireless() {
        if (!TvControl.onWifi(screen.ctx)) {
            screen.explainThenOpen(R.string.adb_wireless_wifi, this::openDeveloperOptionsNow);
        } else if (AdbTlsKey.paired(screen.ctx)) {
            screen.explainThenOpen(R.string.adb_wireless_on, this::openDeveloperOptionsNow);
        } else if (screen.link.pairUrl() != null && !screen.pages.isEmpty()) {
            Page here = screen.pages.get(screen.pages.size() - 1);
            Row from = here.rows.isEmpty() ? null : here.rows.get(0);
            for (Row row : here.rows) if (row.view.hasFocus()) from = row;
            if (from != null) screen.open(pairWireless(), from);
        } else {
            screen.explainThenOpen(R.string.adb_network_closed, this::openDeveloperOptionsNow);
        }
    }

    /**
     * Apps > Wireless debugging: a QR code for the phone's pairing page (PhoneLink), and OK for Developer options,
     * where the TV's pairing window shows the code to type there.
     */
    Page pairWireless() {
        screen.keepLinkUntil = System.currentTimeMillis() + 5 * 60 * 1000;
        Page page = screen.phone(R.string.pair_with_code, screen.link.pairUrl(), R.string.pair_scan);
        page.add(R.string.open_dev_options, null, this::openDevOrAbout);
        return page;
    }

    /**
     * The code from the phone (PhoneLink's thread): the TV's pairing window found on the network (or the port typed),
     * paired, then connected through Wireless debugging, given WRITE_SECURE_SETTINGS (to turn Wireless debugging on
     * by itself from now on), and Settings told. What to tell the phone, in English like the rest of its pages.
     */
    String pairFromPhone(String code, String typedPort) {
        try {
            return pairWith(code, typedPort);
        } finally {
            screen.handler.post(() -> {   // answered, whatever the answer: the page has done its job unless Settings is open
                screen.keepLinkUntil = 0;
                screen.handler.removeCallbacks(screen.stopKeptLink);
                if (screen.host == null) screen.link.stop();
            });
        }
    }

    String pairWith(String code, String typedPort) {
        Context app = screen.ctx.getApplicationContext();
        android.util.Log.i("VibeAdb", "pair: code from phone, typed port=" + typedPort + " wifi=" + TvControl.onWifi(app));
        if (!TvControl.onWifi(app)) return "The device isn't on Wi-Fi. Wireless debugging only works on Wi-Fi.";
        int port = -1;
        if (typedPort != null) {
            port = Integer.parseInt(typedPort.substring(typedPort.lastIndexOf(':') + 1));
        } else {
            port = AdbMdns.find(app, AdbMdns.PAIRING, 8000);
        }
        if (port <= 0 || port > 65535) {
            return "Vibe Launcher couldn't find the pairing window on the device. Keep it open (Pair device with pairing "
                    + "code), then try again, or type the port as well.";
        }
        int result = AdbPairing.pair(app, "127.0.0.1", port, code);
        android.util.Log.i("VibeAdb", "pair: port=" + port + " result=" + result);
        switch (result) {
            case AdbPairing.OK:
                screen.handler.post(this::afterPairing);
                return "Paired. On the device, press Back until Vibe Launcher's settings show again.";
            case AdbPairing.WRONG_CODE:
                return "The device turned the code down. Check it, or choose Pair device with pairing code again for a "
                        + "new one.";
            case AdbPairing.UNREACHABLE:
                return "The device's pairing window has closed. Choose Pair device with pairing code again, then send the "
                        + "new code.";
            case AdbPairing.NO_KEY:
                return "This device can't keep the key Vibe Launcher needs.";
            default:
                return "Pairing didn't work. Choose Pair device with pairing code again and send the new code.";
        }
    }

    /** Paired: connected through Wireless debugging now, as OK on Network debugging would, without the windows. */
    void afterPairing() {
        if (screen.host != null) Toast.makeText(screen.host, R.string.paired_toast, Toast.LENGTH_LONG).show();
        for (Page page : screen.pages) page.refresh();
        Context app = screen.ctx.getApplicationContext();
        adb().execute(() -> {
            Adb.Result r = Adb.check(false);
            if (r.status == Adb.OK && !TvControl.canWrite(app)) Adb.shell(TvControl.grantCommand(app), false);
            screen.handler.post(() -> {
                adbStatus = r.status;
                if (r.status == Adb.OK) usedAdb = true;
                for (Page page : screen.pages) page.refresh();
                if (r.status == Adb.OK) adbWorks();
            });
        });
    }

    /** A Global setting's number, or -1 when the TV doesn't have it. */
    int adbSetting(String name) {
        return android.provider.Settings.Global.getInt(screen.ctx.getContentResolver(), name, -1);
    }

    /** Developer options, once the window has said what to turn on there. */
    void openDeveloperOptions() {
        screen.explainThenOpen(R.string.adb_turn_on, this::openDeveloperOptionsNow);
    }

    void openDeveloperOptionsNow() {
        Screens.open(screen.host, new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
    }
}
