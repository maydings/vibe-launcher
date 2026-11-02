package com.maydings.vibelauncher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Vibe Launcher settings, full screen: the logo on the left and General, Appearance and API keys on the
 * right. Opening one slides its list over the logo and its page in on the right; Back (or Left) slides them
 * back. Changes apply as they're made; tiles per row and the language rebuild Home behind Settings at once.
 *
 * Built once, as soon as Home is up (prepare), and kept: the frame, the first list and the 51 languages,
 * measured and laid out at the screen's size. The languages are made a few at a time, so Home keeps
 * answering the remote meanwhile (all at once, with a font to load for many of them, it stopped for a
 * second on the 940E). SettingsActivity only puts it on screen, with a fade, and hands
 * it back when it closes. The other pages are made as they're opened (a few rows, current values).
 *
 * General: language, reloading the backdrops, Home at power-on, the Home button service, Restart… (service
 * on), backup and restore (Backup, on the TV or through the phone) and updates (Updater). Appearance: tiles per row, inputs, the top bar and the slideshow's clock. API
 * keys: the TMDB, YouTube and Plex keys, edited in place and explained under the list, SmartTube to install
 * or sign in to (the key-free way to YouTube backdrops), and a QR code for pasting the keys from a phone
 * (PhoneLink). Apps (adb build only): turning the TV's apps off and on through its own network debugging
 * (Adb, TvApps).
 */
final class SettingsScreen {

    private static final long SLIDE_MS = 320;

    /** The one prepared screen: views made with the app's context, so keeping them leaks no Activity. */
    @SuppressLint("StaticFieldLeak")
    private static SettingsScreen prepared;

    /** Languages made in one go, and the wait between goes: a few frames' work each, with frames between. */
    private static final int LANGUAGES_PER_STEP = 3;
    private static final long STEP_GAP_MS = 50;

    /**
     * Makes the screen ahead (Home calls it once its first frame is drawn), again only when the language
     * changed, and never while it's on screen.
     */
    static void prepare(Context context) {
        String language = new VibeSettings(context).language();
        if (prepared != null && (prepared.host != null || prepared.builtFor.equals(language))) return;
        prepared = new SettingsScreen(context.getApplicationContext(), language);
        prepared.handler.postDelayed(prepared::step, STEP_GAP_MS);
    }

    /** The prepared screen for SettingsActivity, made now if Home hasn't yet, and finished now if it's still being made. */
    static SettingsScreen get(Context context) {
        prepare(context);
        prepared.finish();
        return prepared;
    }

    /** A few more languages, and then the next go, or the screen's layout when they're all made. */
    private void step() {
        if (laidOut || prepared != this) return;
        if (addLanguages(LANGUAGES_PER_STEP)) finish();
        else handler.postDelayed(this::step, STEP_GAP_MS);
    }

    /** The rest of the languages, then the whole screen measured and laid out at the screen's size. */
    private void finish() {
        if (laidOut) return;
        handler.removeCallbacksAndMessages(null);
        addLanguages(Integer.MAX_VALUE);
        languagePage.focusable(false);
        // Laid out now: showing it only has to draw it.
        screen.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.EXACTLY));
        screen.layout(0, 0, screenW, screenH);
        laidOut = true;
    }

    /** One row: its name, its value, and what OK does. */
    private static final class Row {
        final View view;
        final TextView label, value;
        EditText field;   // key rows only (see key())
        Supplier<CharSequence> valueOf;
        Runnable action;
        int about;   // the text under the list while it's focused; 0 for none
        CharSequence aboutText;   // or text of its own (an app's package name, in Apps)

        Row(View view) {
            this.view = view;
            label = view.findViewById(R.id.label);
            value = view.findViewById(R.id.value);
        }

        void refresh() {
            if (valueOf != null) value.setText(valueOf.get());
        }
    }

    /** A page: its list of rows (scrolling when long) and the text under it. */
    private final class Page {
        final CharSequence title;
        final LinearLayout view;
        final LinearLayout list;
        final TextView about;
        final List<Row> rows = new ArrayList<>();
        /** What can take focus here: the rows, and the wheel, the QR code and the instructions' sections. */
        final List<View> stops = new ArrayList<>();
        int footnote;   // under the list when the focused row has nothing to say
        Row opened;     // the row whose page is open, while this list is on the left

        Page(CharSequence title) {
            this.title = title;
            view = new LinearLayout(ctx);
            view.setOrientation(LinearLayout.VERTICAL);
            ScrollView scroll = new ScrollView(ctx);
            scroll.setVerticalScrollBarEnabled(false);
            // A ScrollView takes focus itself when Up or Down finds no row: on the left list it would hold
            // focus out of sight, so only the rows can have it.
            scroll.setFocusable(false);
            scroll.setClipToPadding(false);
            // Room for the focused row to grow (1.035 of the column's width) without its corners being cut.
            int pad = (int) (6 * density), side = (int) (columnW * 0.02f) + 2;
            scroll.setPadding(side, pad, side, pad);
            list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(list);
            // The focused row grows past the list's edges: drawn whole, not cut square at them.
            list.setClipChildren(false);
            scroll.setClipChildren(false);
            view.setClipChildren(false);
            view.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            about = new TextView(ctx);
            about.setTextColor(0x99FFFFFF);
            about.setTextSize(14);
            about.setLineSpacing(0, 1.15f);
            about.setTypeface(UiFont.get(ctx, R.string.font_regular));
            about.setPadding(side + pad, (int) (10 * density), side + pad, 0);
            // The rest of the column, down to the bottom; a long text (a long translation) gets smaller to fit.
            view.addView(about, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            about.setAutoSizeTextTypeUniformWithConfiguration(11, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        }

        Row add(int label, Supplier<CharSequence> valueOf, Runnable action) {
            return add(ctx.getString(label), valueOf, action);
        }

        Row add(CharSequence label, Supplier<CharSequence> valueOf, Runnable action) {
            Row row = new Row(LayoutInflater.from(ctx).inflate(R.layout.item_setting, list, false));
            Highlight.settingRow(row.view, highlight);
            row.label.setText(label);
            row.valueOf = valueOf;
            row.action = action;
            row.refresh();
            row.view.setOnClickListener(v -> {
                if (row.action != null) row.action.run();
                row.refresh();
            });
            row.view.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f).setDuration(150).start();
                if (focused) showAbout(this, row);
            });
            row.view.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pages.size() > 1) {
                    back();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {   // opens a page, like OK; else nothing to the right
                    if (row.view.findViewById(R.id.chevron).getVisibility() == View.VISIBLE) v.performClick();
                    return true;
                }
                return false;
            });
            list.addView(row.view);
            rows.add(row);
            stops.add(row.view);
            return row;
        }

        /** A row that opens a page, with > at its end. */
        Row link(int label, Supplier<CharSequence> valueOf, Supplier<Page> page) {
            Row row = add(label, valueOf, null);
            row.action = () -> open(page.get(), row);
            row.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
            return row;
        }

        /** An Off/On row: OK switches it. */
        Row toggle(int label, Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
            return add(label, () -> ctx.getString(get.get() ? R.string.on : R.string.off),
                    () -> set.accept(!get.get()));
        }

        void refresh() {
            for (Row row : rows) row.refresh();
        }

        View first() {
            for (View stop : stops) if (stop.getVisibility() == View.VISIBLE) return stop;
            return view;
        }

        /** On the left, the list is only looked at: its rows can't take focus. */
        void focusable(boolean on) {
            for (View stop : stops) stop.setFocusable(on);
        }
    }

    /** For making the views and reading strings: the app's context in the chosen language, with a theme. */
    private final Context ctx;
    private final String builtFor;   // the language it was made in
    private final float density;
    private final VibeSettings settings;
    private final View screen;
    private final FrameLayout stage;
    private final View logo;
    private final TextView title;
    private final Page root;
    private final Page languagePage;
    private final Row generalRow;
    private final List<Page> pages = new ArrayList<>();
    /** Where the lists sit: the left column (over the logo) and the right one, and their width. */
    private final int leftX, rightX, columnW, screenW, screenH;
    /** For making the languages a few at a time (prepare); nothing else is posted on it. */
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int nextLanguage = -1;   // -1 for the TV's own language, then Lang.TAGS
    private boolean laidOut;

    /** The highlight color, for the rows' focused look. */
    private int highlight;

    /** The SettingsActivity it's on, while it is: what opens other screens, dialogs and the keyboard. */
    private Activity host;
    private String language;
    private final String[] keys = new String[3];   // TMDB, YouTube, Plex, as saved
    private Row editing;
    private int editingKey;

    private PhoneLink link;
    private String phoneUrl;
    private BroadcastReceiver packages;
    private Row smartTubeRow;
    private Row homeButtonRow, restartRow;   // General's, while it's made

    private SettingsScreen(Context app, String language) {
        ctx = new ContextThemeWrapper(Lang.wrap(app), android.R.style.Theme_Material_NoActionBar);
        builtFor = language;
        this.language = language;
        density = ctx.getResources().getDisplayMetrics().density;
        settings = new VibeSettings(ctx);
        highlight = Highlight.color(ctx);
        int w = screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        // The list from 55% of the width to 95%, the picture centred in the left half.
        rightX = (int) (w * 0.55f);
        columnW = (int) (w * 0.40f);
        leftX = (int) (w * 0.06f);

        screen = LayoutInflater.from(ctx).inflate(R.layout.settings_screen, new FrameLayout(ctx), false);
        stage = screen.findViewById(R.id.stage);
        logo = screen.findViewById(R.id.logo);
        title = screen.findViewById(R.id.title);
        ((FrameLayout.LayoutParams) logo.getLayoutParams()).leftMargin = leftX + (columnW - logo.getLayoutParams().width) / 2;

        root = new Page(ctx.getString(R.string.settings));
        generalRow = root.link(R.string.tab_general, null, this::general);
        root.link(R.string.tab_appearance, null, this::appearance);
        root.link(R.string.tab_keys, null, this::apiKeys);
        // Turning apps off goes through the TV's ADB, which Play wouldn't take: the adb build only.
        if (!BuildConfig.STORE) root.link(R.string.tab_apps, null, this::apps);
        place(root, 0);
        languagePage = new Page(ctx.getString(R.string.language));   // its rows: addLanguages
        place(languagePage, screenW - rightX);   // off the screen until Language is picked
    }

    // ---- on and off the screen ---------------------------------------------------------------------

    /** SettingsActivity showing it: back to the first list, with the values as they are now. */
    View attach(Activity activity) {
        host = activity;
        recolor(Highlight.color(ctx));
        language = settings.language();
        keys[0] = settings.tmdbKey();
        keys[1] = settings.youtubeKey();
        keys[2] = settings.plexToken();
        pages.clear();
        pages.add(root);
        root.view.animate().cancel();
        root.view.setTranslationX(0f);
        root.focusable(true);
        for (Row row : root.rows) row.view.setActivated(false);
        root.opened = null;
        logo.animate().cancel();
        logo.setTranslationX(0f);
        title.setText(root.title);
        startPhoneLink();
        watchSmartTube();
        ViewGroup parent = (ViewGroup) screen.getParent();
        if (parent != null) parent.removeView(screen);
        return screen;
    }

    View first() {
        return root.first();
    }

    /** Opens on General, already in place (Settings back in a new language): Language has the focus. */
    void showGeneral() {
        Page page = general();
        open(page, generalRow);
        // Where the slides end: they then run from there to there, so nothing moves.
        logo.setTranslationX(offLeft(logo));
        root.view.setTranslationX(leftX - rightX);
        page.view.setTranslationX(0f);
    }

    /** SettingsActivity going: everything but the first list and the languages goes, ready for next time. */
    void detach(Activity activity) {
        if (host != activity) return;
        if (editing != null) endEdit();
        link.stop();
        host.unregisterReceiver(packages);
        smartTubeRow = null;
        for (Page page : pages) {
            if (page != root && page != languagePage) stage.removeView(page.view);
        }
        for (int i = stage.getChildCount() - 1; i >= 0; i--) {   // pages still sliding out
            View v = stage.getChildAt(i);
            if (v != root.view && v != languagePage.view) stage.removeView(v);
        }
        languagePage.view.animate().cancel();
        languagePage.view.setTranslationX(screenW - rightX);
        languagePage.focusable(false);
        for (Row row : languagePage.rows) row.view.setActivated(false);
        // The focused row is grown (1.035): kept for next time, it would come back grown with another focused.
        for (Page page : new Page[]{root, languagePage}) {
            for (Row row : page.rows) {
                row.view.animate().cancel();
                row.view.setScaleX(1f);
                row.view.setScaleY(1f);
            }
        }
        ViewGroup parent = (ViewGroup) screen.getParent();
        if (parent != null) parent.removeView(screen);
        host = null;
    }

    /** The rows on screen, and the languages kept ready, in this highlight color. */
    private void recolor(int color) {
        if (color == highlight) return;
        highlight = color;
        for (Page page : pages) for (Row row : page.rows) Highlight.settingRow(row.view, color);
        for (Row row : root.rows) Highlight.settingRow(row.view, color);
        for (Row row : languagePage.rows) Highlight.settingRow(row.view, color);
    }

    /** Home, if it's there: what tiles per row, the language, the backdrops and the top bar change. */
    private static MainActivity home() {
        return MainActivity.current();
    }

    // ---- the pages ---------------------------------------------------------------------------------

    private Page general() {
        Page page = new Page(ctx.getString(R.string.tab_general));
        page.link(R.string.language, () -> language.isEmpty()
                ? ctx.getString(R.string.language_system) : Lang.name(language), this::languages);
        page.add(R.string.reload_backdrops, null, () -> {
            MainActivity home = home();
            if (home != null) home.reloadBackdrops();
            Toast.makeText(host, R.string.backdrops_reloading, Toast.LENGTH_SHORT).show();
        });
        page.link(R.string.picture_format, () -> ctx.getString(settings.jpegStills() ? R.string.picture_jpeg
                : R.string.picture_png), this::pictureFormat);
        page.toggle(R.string.power_on_home, settings::powerOnHome, settings::setPowerOnHome);
        page.link(R.string.back_button, () -> backName(settings.backAction()), this::backAction);
        // The Home button service, on or off at any time (Home offers it once, on its first start): while
        // Vibe Launcher isn't the TV's Home, or while it's on. On explains it first and opens Accessibility;
        // Off turns it off here.
        homeButtonRow = page.add(R.string.home_button,
                () -> ctx.getString(DefaultHome.homeButtonOn(ctx) ? R.string.on : R.string.off), () -> {
                    Runnable off = HomeButtonLink.turnOff;
                    if (!DefaultHome.homeButtonOn(ctx)) {
                        HomeButtonLink.offer(host);
                    } else if (off != null) {
                        off.run();
                        handler.postDelayed(this::showHomeButton, 300);   // Android notes it a moment later
                    } else {
                        HomeButtonLink.openSettings(host);   // on, but not bound yet: off from Accessibility
                    }
                });
        homeButtonRow.about = R.string.home_button_short;
        homeButtonRow.view.setVisibility(BuildConfig.HOME_BUTTON
                && (DefaultHome.homeButtonOn(ctx) || !DefaultHome.isHome(ctx)) ? View.VISIBLE : View.GONE);
        // The TV's power menu, which asks before it restarts: only the Home button service can open it (an
        // app may not restart the TV), so only while that's on.
        restartRow = page.add(R.string.restart, null, () -> {
            Runnable powerMenu = HomeButtonLink.powerMenu;
            if (powerMenu != null) powerMenu.run();
        });
        showHomeButton();
        page.link(R.string.backup_restore, null, this::backups);
        Row version = page.add(R.string.updates, null, null);
        Row check = page.add(R.string.check_updates, null, null);
        showUpdates(version, check);
        page.link(R.string.about, null, this::about);
        return page;
    }

    private static final String AUTHOR = "Mahir Aydın";
    private static final String GITHUB_USER = "maydings";
    private static final String PROJECT = "maydings/vibe-launcher";
    private static final String PROJECT_URL = "https://github.com/" + PROJECT;

    /**
     * Who made it and where it lives: the author's picture, name and GitHub name, with a QR code for the project
     * page (TVs rarely have a browser), then the version, the project, where to report a problem and the license;
     * the credits under the list.
     */
    private Page about() {
        Page page = new Page(ctx.getString(R.string.about));
        page.footnote = R.string.about_credits;
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = (int) (12 * density);
        header.setPadding(pad, pad, pad, (int) (18 * density));
        ImageView avatar = new ImageView(ctx);
        avatar.setImageResource(R.drawable.about_avatar);   // bundled: no request to GitHub to show it
        int avatarSize = (int) (84 * density);
        header.addView(avatar, new LinearLayout.LayoutParams(avatarSize, avatarSize));
        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding((int) (18 * density), 0, (int) (12 * density), 0);
        TextView name = new TextView(ctx);
        name.setText(AUTHOR);
        name.setTextColor(0xF2FFFFFF);
        name.setTextSize(24);
        name.setTypeface(UiFont.get(ctx, R.string.font_medium));
        names.addView(name);
        TextView handle = new TextView(ctx);
        handle.setText(ctx.getString(R.string.github_handle, GITHUB_USER));
        handle.setTextColor(0x99FFFFFF);
        handle.setTextSize(15);
        handle.setTypeface(UiFont.get(ctx, R.string.font_regular));
        names.addView(handle);
        TextView scan = new TextView(ctx);
        scan.setText(R.string.scan_project);
        scan.setTextColor(0x80FFFFFF);
        scan.setTextSize(13);
        scan.setTypeface(UiFont.get(ctx, R.string.font_regular));
        scan.setPadding(0, (int) (10 * density), 0, 0);
        names.addView(scan);
        header.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView qr = new ImageView(ctx);
        int qrSize = (int) (84 * density), qrPad = (int) (5 * density);
        qr.setImageBitmap(PhoneLink.qr(PROJECT_URL, qrSize));
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(qrPad, qrPad, qrPad, qrPad);
        header.addView(qr, new LinearLayout.LayoutParams(qrSize + 2 * qrPad, qrSize + 2 * qrPad));
        page.list.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // Information only: rows to look at, nothing to press.
        page.add(R.string.updates, () -> Updater.installedVersion(ctx), null);
        page.add(R.string.project, () -> PROJECT, null);
        page.add(R.string.report_problem, () -> ctx.getString(R.string.issues_on_github), null);
        page.add(R.string.license, () -> "MIT", null);
        return page;
    }

    /** The language list, made with the screen, opening on the one in use. */
    private Page languages() {
        languagePage.refresh();
        for (Row row : languagePage.rows) {
            if (language.equals(row.view.getTag())) languagePage.opened = row;   // open() focuses it
        }
        return languagePage;
    }

    /**
     * Up to n more of the language rows, each measured as it's made so its font is loaded and its text laid
     * out now, not all together; true once they're all made.
     */
    private boolean addLanguages(int n) {
        Page page = languagePage;
        int side = (int) (columnW * 0.02f) + 2;   // the list's padding (Page)
        int width = View.MeasureSpec.makeMeasureSpec(columnW - 2 * side, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        for (; n > 0 && nextLanguage < Lang.TAGS.length; n--, nextLanguage++) {
            int i = nextLanguage;
            String tag = i < 0 ? "" : Lang.TAGS[i];
            Row row = page.add(i < 0 ? ctx.getString(R.string.language_system) : Lang.NAMES[i],
                    () -> tag.equals(language) ? "✓" : "", () -> {
                        if (tag.equals(language)) {
                            back();
                            return;
                        }
                        // Everything is made again in the new language: Home behind, and Settings, which
                        // comes back on General after a moment of nothing.
                        language = tag;
                        settings.setLanguage(tag);
                        rebuildHome();
                        Activity h = host;
                        if (h != null) {
                            h.setIntent(h.getIntent().putExtra(SettingsActivity.ON_GENERAL, true));
                            h.recreate();
                        }
                    });
            row.view.setTag(tag);
            row.view.measure(width, height);
        }
        return nextLanguage >= Lang.TAGS.length;
    }

    /** Home again with the new grid or language, made now, behind Settings, so it's ready when Settings closes. */
    private static void rebuildHome() {
        MainActivity home = home();
        if (home != null) home.recreate();
    }

    private Page appearance() {
        Page page = new Page(ctx.getString(R.string.tab_appearance));
        page.link(R.string.tiles_per_row, () -> String.valueOf(MainActivity.COLUMNS), this::tiles);
        page.link(R.string.dock_apps, () -> String.valueOf(MainActivity.DOCK), this::dockApps);
        page.toggle(R.string.hide_inactive_inputs, settings::hideInactiveInputs, settings::setHideInactiveInputs);
        page.toggle(R.string.auto_hide_bar, settings::autoHideBar, on -> {
            settings.setAutoHideBar(on);
            MainActivity home = home();
            if (home != null) home.updateTopBar();
        });
        page.toggle(R.string.slideshow_clock, settings::slideshowClock, settings::setSlideshowClock);
        page.link(R.string.clock, () -> clockName(settings.clockHours()), this::clockHours);
        page.toggle(R.string.carets, settings::carets, settings::setCarets);
        page.link(R.string.glass_color, this::swatch, this::glassColour);
        return page;
    }

    /** A dot in the highlight colour, for its row. */
    private CharSequence swatch() {
        android.text.SpannableString dot = new android.text.SpannableString("●");
        dot.setSpan(new android.text.style.ForegroundColorSpan(0xFF000000 | settings.glassTint()), 0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return dot;
    }

    /**
     * The glass highlight's colour: White (as it was), a preview of the glass on a white tile (where white
     * glass hardly shows), and the colour wheel. Each move is saved at once; Home takes it when it's back.
     */
    private Page glassColour() {
        Page page = new Page(ctx.getString(R.string.glass_color));
        page.footnote = R.string.glass_color_hint;
        int tint = settings.glassTint();
        // The preview: a white tile, the glass over it as Home draws it on a focused tile.
        FrameLayout preview = new FrameLayout(ctx);
        int tileW = (int) (150 * density), tileH = Math.round(tileW * AppAdapter.DESIGN_H_DP / AppAdapter.DESIGN_W_DP);
        View tile = new View(ctx);
        android.graphics.drawable.GradientDrawable white = new android.graphics.drawable.GradientDrawable();
        white.setColor(0xFFF4F4F4);
        white.setCornerRadius(AppAdapter.cornerRadius(tileW, density) * AppAdapter.FOCUS_SCALE);
        tile.setBackground(white);
        int grownW = (int) (tileW * AppAdapter.FOCUS_SCALE), grownH = (int) (tileH * AppAdapter.FOCUS_SCALE);
        preview.addView(tile, new FrameLayout.LayoutParams(grownW, grownH, android.view.Gravity.CENTER));
        GlassView glass = new GlassView(ctx, null);
        glass.setRadius(AppAdapter.cornerRadius(tileW, density) * AppAdapter.FOCUS_SCALE);
        glass.setTint(tint);
        int glow = (int) (6 * density);   // GlassView's room for its glow
        preview.addView(glass, new FrameLayout.LayoutParams(grownW + 2 * glow, grownH + 2 * glow, android.view.Gravity.CENTER));
        ColorWheelView[] wheel = new ColorWheelView[1];
        page.add(R.string.glass_white, null, () -> {
            settings.setGlassTint(0xFFFFFF);
            glass.setTint(0xFFFFFF);
            wheel[0].set(0xFFFFFF);
            recolor(0xFFFFFFFF);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                grownH + 2 * glow + (int) (12 * density));
        page.list.addView(preview, lp);
        wheel[0] = new ColorWheelView(ctx, tint, new ColorWheelView.Listener() {
            @Override
            public void picked(int rgb) {
                settings.setGlassTint(rgb);
                glass.setTint(rgb);
                recolor(0xFF000000 | rgb);
            }

            @Override
            public void done() {
                back();
            }
        });
        page.list.addView(wheel[0], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (200 * density)));
        page.stops.add(wheel[0]);
        page.about.setText(R.string.glass_color_hint);
        return page;
    }

    private Page tiles() {
        Page page = new Page(ctx.getString(R.string.tiles_per_row));
        for (int n = 4; n <= 6; n++) {
            int value = n;
            // Picked, it's ticked and the list stays: Back or Left goes back.
            Row row = page.add(String.valueOf(n), () -> value == MainActivity.COLUMNS ? "✓" : "", () -> {
                if (value == MainActivity.COLUMNS) return;
                settings.setColumns(value);
                MainActivity.COLUMNS = value;   // shown here at once; the new Home reads the setting
                MainActivity.DOCK = settings.dockApps(value);   // a full dock follows; a shorter one stays
                rebuildHome();
                page.refresh();   // the tick moves
            });
            if (n == MainActivity.COLUMNS) page.opened = row;
        }
        return page;
    }

    /**
     * PNG or JPEG for the backdrops. A change reloads them all, as Reload backdrops does, so the new format
     * shows at once instead of over the next six hours.
     */
    private Page pictureFormat() {
        Page page = new Page(ctx.getString(R.string.picture_format));
        for (boolean jpeg : new boolean[]{false, true}) {
            // Picked, it's ticked and the list stays: Back or Left goes back.
            Row row = page.add(ctx.getString(jpeg ? R.string.picture_jpeg : R.string.picture_png),
                    () -> jpeg == settings.jpegStills() ? "✓" : "", () -> {
                        if (jpeg == settings.jpegStills()) return;
                        settings.setJpegStills(jpeg);
                        MainActivity home = home();
                        if (home != null) home.reloadBackdrops();
                        Toast.makeText(host, R.string.backdrops_reloading, Toast.LENGTH_SHORT).show();
                        page.refresh();
                    });
            if (jpeg == settings.jpegStills()) page.opened = row;
        }
        return page;
    }

    private static final int[] BACK_NAMES = {R.string.off, R.string.back_first_app, R.string.back_poster,
            R.string.back_screensaver};

    private String backName(int action) {
        return ctx.getString(BACK_NAMES[action >= 0 && action < BACK_NAMES.length ? action : 0]);
    }

    /** What Back does on Home (MainActivity.onBackPressed). Home reads it at each press. */
    private Page backAction() {
        Page page = new Page(ctx.getString(R.string.back_button));
        for (int i = 0; i < BACK_NAMES.length; i++) {
            int action = i;
            // Picked, it's ticked and the list stays: Back or Left goes back.
            Row row = page.add(backName(action), () -> action == settings.backAction() ? "✓" : "", () -> {
                settings.setBackAction(action);
                page.refresh();
            });
            if (action == settings.backAction()) page.opened = row;
        }
        return page;
    }

    private String clockName(int hours) {
        return ctx.getString(hours == 12 ? R.string.clock_12 : hours == 24 ? R.string.clock_24 : R.string.language_system);
    }

    /** The clock: the TV's 12/24-hour setting, or either one whatever the TV says. Home takes it on resume. */
    private Page clockHours() {
        Page page = new Page(ctx.getString(R.string.clock));
        for (int hours : new int[]{0, 12, 24}) {
            // Picked, it's ticked and the list stays: Back or Left goes back.
            Row row = page.add(clockName(hours), () -> hours == settings.clockHours() ? "✓" : "", () -> {
                settings.setClockHours(hours);
                page.refresh();
            });
            if (hours == settings.clockHours()) page.opened = row;
        }
        return page;
    }

    /** Apps in dock: 1 up to tiles per row (a full row), the shorter dock centred. Home is rebuilt at once. */
    private Page dockApps() {
        Page page = new Page(ctx.getString(R.string.dock_apps));
        for (int n = 1; n <= MainActivity.COLUMNS; n++) {
            int value = n;
            // Picked, it's ticked and the list stays: Back or Left goes back.
            Row row = page.add(String.valueOf(n), () -> value == MainActivity.DOCK ? "✓" : "", () -> {
                if (value == MainActivity.DOCK) return;
                settings.setDockApps(value == MainActivity.COLUMNS ? 0 : value);
                MainActivity.DOCK = value;
                rebuildHome();
                page.refresh();
            });
            if (n == MainActivity.DOCK) page.opened = row;
        }
        return page;
    }

    private Page apiKeys() {
        Page page = new Page(ctx.getString(R.string.tab_keys));
        page.footnote = R.string.tmdb_notice;   // TMDB's terms ask apps using its API to say so
        // First the instructions, on a page of their own; under the list, each row says in a line what it adds.
        page.link(R.string.keys_help, null, this::keysHelp);
        key(page, R.string.tmdb_key, 0, R.string.tmdb_short);
        key(page, R.string.youtube_key, 1, R.string.youtube_short);
        // SmartTube signed in beats a key (the user's own feed). The store build can't install apps: there
        // the row only opens SmartTube, when it's on the TV.
        smartTubeRow = page.add("", null, () -> {
            Intent open = SmartTube.launchIntent(host);
            if (open != null) {
                host.startActivity(open);
                return;
            }
            if (BuildConfig.STORE) return;
            boolean started = SmartTube.install(host, res -> Toast.makeText(host,
                    res == R.string.update_failed ? R.string.smarttube_failed : res, Toast.LENGTH_LONG).show());
            if (started) Toast.makeText(host, R.string.smarttube_downloading, Toast.LENGTH_LONG).show();
        });
        smartTubeRow.about = R.string.smarttube_short;
        showSmartTube();
        key(page, R.string.plex_token, 2, R.string.plex_short);
        if (phoneUrl != null) page.link(R.string.phone_paste, null,
                () -> phone(R.string.phone_paste, phoneUrl, R.string.scan_to_paste));
        return page;
    }

    // ---- backup and restore ------------------------------------------------------------------------

    /** Storage access was asked for: what to do once it's given (Backup.needsPermission). */
    private Runnable afterStorage;
    static final int STORAGE_REQUEST = 7;

    /**
     * Backup and restore (Backup): a copy kept on the TV, made or put back here, and the phone's page for
     * taking one away or sending one back. What's in a backup is said under the list.
     */
    private Page backups() {
        Page page = new Page(ctx.getString(R.string.backup_restore));
        page.footnote = R.string.backup_short;
        Row save = page.add(R.string.backup_to_tv, null, null);
        save.about = R.string.backup_tv_short;
        save.action = () -> withStorage(() -> {
            Context app = ctx.getApplicationContext();
            new Thread(() -> {
                boolean ok = Backup.saveToTv(app);
                handler.post(() -> {
                    if (host == null) return;
                    Toast.makeText(host, ok ? R.string.backup_saved : R.string.backup_failed, Toast.LENGTH_SHORT).show();
                    page.refresh();   // the copy's new date
                });
            }, "Backup").start();
        });
        // Its value is when the TV's copy was made; it opens a page that asks first, only when there's one.
        Row restore = page.link(R.string.restore_from_tv, this::savedOnTv, null);
        restore.action = () -> withStorage(() -> {
            page.refresh();
            if (Backup.savedOnTv(ctx) == 0) Toast.makeText(host, R.string.backup_none, Toast.LENGTH_SHORT).show();
            else open(restorePage(), restore);
        });
        if (phoneUrl != null) page.link(R.string.backup_phone, null,
                () -> phone(R.string.backup_phone, link.backupUrl(), R.string.scan_to_backup));
        return page;
    }

    /** When the TV's copy was made, or None yet; nothing while storage access hasn't been given. */
    private CharSequence savedOnTv() {
        if (Backup.needsPermission(ctx)) return "";
        long made = Backup.savedOnTv(ctx);
        if (made == 0) return ctx.getString(R.string.backup_none);
        return android.text.format.DateUtils.formatDateTime(ctx, made, android.text.format.DateUtils.FORMAT_SHOW_DATE
                | android.text.format.DateUtils.FORMAT_SHOW_TIME | android.text.format.DateUtils.FORMAT_ABBREV_MONTH);
    }

    /** Restoring replaces everything: a page of its own that says so, and Restore now to go ahead. */
    private Page restorePage() {
        Page page = new Page(ctx.getString(R.string.restore_from_tv));
        page.footnote = R.string.restore_warning;
        page.add(R.string.restore_now, this::savedOnTv, () -> {
            Context app = ctx.getApplicationContext();
            new Thread(() -> {
                boolean ok = restore(Backup.loadFromTv(app));
                handler.post(() -> {
                    if (ok) restored();
                    else if (host != null) Toast.makeText(host, R.string.restore_failed, Toast.LENGTH_LONG).show();
                });
            }, "Restore").start();
        });
        return page;
    }

    /** Off the main thread: the backup read through, then put in place; false when it isn't one, and nothing changed. */
    private boolean restore(String json) {
        Backup.Contents contents = Backup.read(json);
        if (contents == null) return false;
        restoredLanguage = contents.language;
        return Backup.apply(ctx.getApplicationContext(), contents);
    }

    private volatile String restoredLanguage;

    /**
     * A backup is in place: Settings closes and Home is made again from it, as after a language change. Settings
     * in another language is made again too, the next time Home prepares it.
     */
    private void restored() {
        Context app = ctx.getApplicationContext();
        if (!builtFor.equals(restoredLanguage) && prepared == this) prepared = null;
        // Said in the backup's language: Home comes back in it.
        Toast.makeText(app, Lang.wrap(app).getString(R.string.restored), Toast.LENGTH_SHORT).show();
        rebuildHome();
        if (host != null) host.finish();
    }

    /**
     * Runs this now, or once storage access is given (up to Android 10, asked for the first time a backup is
     * saved or restored on the TV); turned down, it says so.
     */
    private void withStorage(Runnable then) {
        if (host == null) return;
        if (!Backup.needsPermission(host)) {
            then.run();
            return;
        }
        afterStorage = then;
        host.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_REQUEST);
    }

    /** SettingsActivity's answer to the storage request. */
    void onStoragePermission(boolean granted) {
        Runnable then = afterStorage;
        afterStorage = null;
        if (host == null) return;
        if (granted && then != null) then.run();
        else if (!granted) Toast.makeText(host, R.string.storage_denied, Toast.LENGTH_LONG).show();
        for (Page page : pages) page.refresh();   // the TV's copy can be looked at now
    }

    /**
     * How to get each key and what it adds, a section for each. Each section takes focus in turn, so Up and
     * Down move through them and the page scrolls to the one focused.
     */
    private Page keysHelp() {
        Page page = new Page(ctx.getString(R.string.keys_help));
        int[][] sections = {{R.string.tmdb_key, R.string.tmdb_info}, {R.string.youtube_key, R.string.youtube_info},
                {R.string.plex_token, R.string.plex_info}};
        int pad = (int) (16 * density);
        for (int[] section : sections) {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, pad, pad, pad);
            box.setBackgroundResource(R.drawable.setting_text_bg);
            box.setFocusable(true);
            box.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false;
                back();
                return true;
            });
            TextView heading = new TextView(ctx);
            heading.setText(section[0]);
            heading.setTextColor(0xEBFFFFFF);
            heading.setTextSize(18);
            heading.setTypeface(UiFont.get(ctx, R.string.font_medium));
            box.addView(heading);
            TextView text = new TextView(ctx);
            text.setText(section[1]);
            text.setTextColor(0xB3FFFFFF);
            text.setTextSize(15);
            text.setLineSpacing(0, 1.15f);
            text.setTypeface(UiFont.get(ctx, R.string.font_regular));
            text.setPadding(0, (int) (8 * density), 0, 0);
            box.addView(text);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * density);
            page.list.addView(box, lp);
            page.stops.add(box);
        }
        return page;
    }

    /** A key row: the saved key's last four characters; OK edits it in place. */
    private void key(Page page, int label, int index, int about) {
        Row row = page.add(label, () -> keys[index].isEmpty() ? ctx.getString(R.string.not_set) : mask(keys[index]), null);
        row.about = about;
        row.field = (EditText) ((android.view.ViewStub) row.view.findViewById(R.id.fieldStub)).inflate();
        row.action = () -> edit(row, index);
        row.field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) endEdit();
            return true;
        });
        row.field.setOnFocusChangeListener((v, focused) -> {
            if (!focused && editing == row) endEdit();
        });
    }

    /**
     * A QR code for a page on the phone (PhoneLink), with what to do there under it: pasting the keys (typing a
     * 32-character key with a remote is painful), or a backup to take away or send back.
     */
    private Page phone(int title, String phoneUrl, int scanText) {
        Page page = new Page(ctx.getString(title));
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setFocusable(true);   // somewhere for focus to be: Back and Left go back
        box.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false;
            back();
            return true;
        });
        ImageView qr = new ImageView(ctx);
        int size = (int) (180 * density);
        qr.setImageBitmap(PhoneLink.qr(phoneUrl, size));
        qr.setBackgroundColor(Color.WHITE);
        int pad = (int) (10 * density);
        qr.setPadding(pad, pad, pad, pad);
        box.addView(qr, new LinearLayout.LayoutParams(size + 2 * pad, size + 2 * pad));
        TextView scan = new TextView(ctx);
        scan.setText(scanText);
        scan.setGravity(android.view.Gravity.CENTER_HORIZONTAL);   // a longer instruction wraps centred
        scan.setTextColor(0xCCFFFFFF);
        scan.setTextSize(17);
        scan.setTypeface(UiFont.get(ctx, R.string.font_regular));
        scan.setPadding(0, (int) (18 * density), 0, 0);
        box.addView(scan);
        TextView url = new TextView(ctx);
        url.setText(phoneUrl.replace("http://", ""));
        url.setTextColor(0x80FFFFFF);
        url.setTextSize(13);
        url.setTypeface(UiFont.get(ctx, R.string.font_regular));
        url.setPadding(0, (int) (6 * density), 0, 0);
        box.addView(url);
        page.list.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.stops.add(box);
        return page;
    }

    // ---- apps -------------------------------------------------------------------------------------

    /** ADB calls one at a time, in order, off the main thread (Adb). */
    private java.util.concurrent.ExecutorService adbThread;
    /** The last Adb answer (Adb.OK, OFF, …), or -1 while the first check runs. */
    private int adbStatus = -1;
    private boolean adbBusy;
    /** The apps on the Apps page now, with their rows: what Turn all back on brings up to date. */
    private final List<TvApps.App> shownApps = new ArrayList<>();

    /**
     * Apps: the TV's own network debugging (Adb) and whether Vibe Launcher may use it, Turn all back on, then
     * the two lists (appList): the apps you can turn off, and the ones the TV needs. OK on Network debugging, or
     * the first switch, makes the TV ask whether to allow Vibe Launcher.
     */
    private Page apps() {
        Page page = new Page(ctx.getString(R.string.tab_apps));
        page.footnote = R.string.apps_short;
        Row connection = page.add(R.string.adb_connection, this::adbStatusText, null);
        connection.about = R.string.adb_connection_short;
        connection.action = () -> {
            if (adbStatus == Adb.OK) return;
            runAdb(page, null, r -> {
            });
        };
        Row undo = page.add(R.string.apps_turn_all_on, () -> {
            int n = TvApps.turnedOff(ctx).size();
            return n == 0 ? "" : String.valueOf(n);
        }, () -> turnAllOn(page));
        undo.about = R.string.apps_turn_all_on_short;
        // Whether it's allowed already, without making the TV ask.
        adbStatus = -1;
        adb().execute(() -> {
            Adb.Result r = Adb.check(false);
            handler.post(() -> {
                adbStatus = r.status;
                page.refresh();
            });
        });
        page.link(R.string.apps_can_turn_off, null, () -> appList(false));
        page.link(R.string.apps_tv_needs, null, () -> appList(true));
        return page;
    }

    /**
     * The apps you can turn off (needed false), On or Off, OK switching each; or (true) the ones the TV needs,
     * grayed, where OK only says why they stay on. Made off the main thread (each app's name is read from it)
     * and its rows a few at a time, so the page slides in at once.
     */
    private Page appList(boolean needed) {
        Page page = new Page(ctx.getString(needed ? R.string.apps_tv_needs : R.string.apps_can_turn_off));
        page.footnote = needed ? R.string.app_protected : R.string.apps_short;
        if (!needed) shownApps.clear();
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            List<TvApps.App> apps = TvApps.list(app, needed);
            handler.post(() -> addAppRows(page, apps, 0));
        }, "TvApps").start();
        return page;
    }

    private java.util.concurrent.ExecutorService adb() {
        if (adbThread == null) adbThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        return adbThread;
    }

    /** A dozen more rows a frame, while the page is still open. */
    private void addAppRows(Page page, List<TvApps.App> apps, int from) {
        if (!pages.contains(page)) return;
        int to = Math.min(apps.size(), from + 12);
        for (int i = from; i < to; i++) {
            TvApps.App a = apps.get(i);
            Row row = page.add(a.label, () -> ctx.getString(a.on ? R.string.on : R.string.off), null);
            if (a.needed) {
                // Grayed, but still focusable, so OK can say why: the TV needs it, and it stays as it is.
                row.label.setAlpha(0.45f);
                row.value.setAlpha(0.45f);
                row.aboutText = a.pkg + "\n" + ctx.getString(R.string.app_protected);
                row.action = () -> Toast.makeText(host, R.string.app_protected, Toast.LENGTH_LONG).show();
                continue;
            }
            row.aboutText = a.pkg;
            row.action = () -> runAdb(page, TvApps.command(a.pkg, !a.on), r -> {
                boolean wanted = !a.on;
                a.on = TvApps.isOn(ctx.getPackageManager(), a.pkg);
                if (a.on == wanted) TvApps.remember(ctx, a.pkg, a.on);
                else Toast.makeText(host, R.string.adb_failed, Toast.LENGTH_SHORT).show();
            });
            shownApps.add(a);
        }
        if (to < apps.size()) handler.postDelayed(() -> addAppRows(page, apps, to), 16);
    }

    /** Every app turned off here and still off, back on in one go. */
    private void turnAllOn(Page page) {
        List<String> off = TvApps.turnedOff(ctx);
        StringBuilder cmd = new StringBuilder();
        for (String pkg : off) cmd.append(cmd.length() == 0 ? "" : "; ").append(TvApps.command(pkg, true));
        Runnable tidy = () -> {
            // The record keeps only what's still off (anything turned on by other means is forgotten too).
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            java.util.Set<String> still = new java.util.HashSet<>();
            for (String pkg : settings.appsOff()) if (!TvApps.isOn(pm, pkg)) still.add(pkg);
            settings.setAppsOff(still);
            for (TvApps.App a : shownApps) a.on = TvApps.isOn(pm, a.pkg);
            Toast.makeText(host, still.isEmpty() ? R.string.apps_all_on : R.string.adb_failed, Toast.LENGTH_SHORT).show();
            page.refresh();
        };
        if (off.isEmpty()) tidy.run();   // nothing to ask the TV for
        else runAdb(page, cmd.toString(), r -> tidy.run());
    }

    /**
     * Runs a shell command through the TV's ADB (or, command null, only connects), asking the TV to allow
     * Vibe Launcher if it hasn't yet; then, back on the main thread and only if it got in, then. Debugging off
     * opens Developer options, saying what to turn on. One at a time: OK pressed meanwhile does nothing.
     */
    private void runAdb(Page page, String command, java.util.function.Consumer<Adb.Result> then) {
        if (adbBusy || host == null) return;
        // What's off can be seen before trying: Developer options not unlocked yet (its About screen, to unlock
        // them), or USB/ADB debugging off (Developer options). Both are Global settings any app may read.
        int devOptions = adbSetting(android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED);
        int debugging = adbSetting(android.provider.Settings.Global.ADB_ENABLED);
        if (debugging != 1 && devOptions != 1) {
            adbStatus = Adb.OFF;
            explainThenOpen(R.string.adb_dev_options, this::openAbout);
            page.refresh();
            return;
        }
        if (debugging == 0) {
            adbStatus = Adb.OFF;
            openDeveloperOptions();
            page.refresh();
            return;
        }
        adbBusy = true;
        if (adbStatus != Adb.OK && adbStatus != Adb.OFF) {
            Toast.makeText(host, R.string.adb_allow_prompt, Toast.LENGTH_LONG).show();
        }
        adb().execute(() -> {
            Adb.Result r = command != null ? Adb.shell(command, true) : Adb.check(true);
            handler.post(() -> {
                adbBusy = false;
                adbStatus = r.status;
                if (host == null) return;
                if (r.status == Adb.OK) {
                    then.accept(r);
                } else if (r.status == Adb.OFF) {   // debugging on, but not over the network (port 5555 closed)
                    openDeveloperOptions();
                } else {
                    Toast.makeText(host, r.status == Adb.NO_KEY ? R.string.adb_no_key : R.string.adb_failed,
                            Toast.LENGTH_LONG).show();
                }
                page.refresh();
            });
        });
    }

    /** A Global setting's number, or -1 when the TV doesn't have it. */
    private int adbSetting(String name) {
        return android.provider.Settings.Global.getInt(ctx.getContentResolver(), name, -1);
    }

    /** Developer options, once the window has said what to turn on there. */
    private void openDeveloperOptions() {
        explainThenOpen(R.string.adb_turn_on, () -> {
            try {
                host.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (RuntimeException e) {
                // not there on this TV: the window said where to look
            }
        });
    }

    /**
     * What to do in Android's settings, in a window like the update's (a message at the bottom of the screen went
     * before it could be read), and Continue to open them; Cancel stays here.
     */
    private void explainThenOpen(int message, Runnable open) {
        if (host == null) return;
        Activity activity = host;
        View panel = LayoutInflater.from(ctx).inflate(R.layout.dialog_update,
                activity.findViewById(android.R.id.content), false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(R.string.adb_connection);
        ((TextView) panel.findViewById(R.id.notes)).setText(message);
        TextView go = panel.findViewById(R.id.install), cancel = panel.findViewById(R.id.cancelUpdate);
        go.setText(R.string.continue_button);
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (560 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        Highlight.pill(go, highlight);
        Highlight.pill(cancel, highlight);
        cancel.setOnClickListener(v -> dialog.dismiss());
        go.setOnClickListener(v -> {
            dialog.dismiss();
            if (host == activity) open.run();
        });
        dialog.show();
        go.requestFocus();
    }

    /**
     * The TV's About screen, where pressing Build seven times unlocks Developer options. Its action can lead to
     * several screens (Sony's Settings answers it with four, About among them, which would show a chooser): the
     * one named About, or the only one; failing that, Settings itself.
     */
    private void openAbout() {
        Intent about = new Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS);
        List<android.content.pm.ResolveInfo> found = host.getPackageManager().queryIntentActivities(about, 0);
        android.content.pm.ActivityInfo pick = found.size() == 1 ? found.get(0).activityInfo : null;
        for (android.content.pm.ResolveInfo r : found) {
            if (r.activityInfo.name.toLowerCase(java.util.Locale.ROOT).contains("about")) pick = r.activityInfo;
        }
        Intent open = pick != null ? about.setClassName(pick.packageName, pick.name)
                : new Intent(android.provider.Settings.ACTION_SETTINGS);
        try {
            host.startActivity(open);
        } catch (RuntimeException e) {
            // the window said where to look
        }
    }

    private CharSequence adbStatusText() {
        if (adbStatus == -1) return ctx.getString(R.string.adb_checking);
        if (adbStatus == Adb.OK) return ctx.getString(R.string.adb_ready);
        if (adbStatus == Adb.OFF) return ctx.getString(R.string.off);
        return ctx.getString(R.string.adb_not_allowed);
    }

    // ---- sliding between pages ---------------------------------------------------------------------

    /** Puts a page's list in the right column (offset 0) or the left one (offset leftX - rightX). */
    private void place(Page page, float translationX) {
        page.view.setTranslationX(translationX);
        if (page.view.getParent() != null) return;   // the first list and the languages, kept laid out
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(columnW, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.leftMargin = rightX;
        lp.topMargin = (int) (82 * density);
        lp.bottomMargin = (int) (16 * density);
        stage.addView(page.view, lp);
    }

    /**
     * The page's list slides left over the logo (or the list before it), which slides off the screen's left
     * edge, and the new page comes in from off its right edge. Moves only, no fades.
     */
    private void open(Page next, Row from) {
        Page current = pages.get(pages.size() - 1);
        View leftNow = pages.size() >= 2 ? pages.get(pages.size() - 2).view : logo;
        // The language list may still be sliding out from last time: stopped now, its end (which takes its
        // rows' focus away) doesn't land on it coming back in.
        next.view.animate().cancel();
        next.focusable(true);
        current.opened = from;
        current.focusable(false);
        from.view.setActivated(true);
        place(next, screenW - rightX);
        // Started once the new page is laid out, so the whole slide is seen.
        next.view.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                next.view.getViewTreeObserver().removeOnPreDrawListener(this);
                if (pages.get(pages.size() - 1) != next) return true;   // gone back already
                slide(leftNow, offLeft(leftNow));
                slide(current.view, leftX - rightX);
                slide(next.view, 0f);
                return true;
            }
        });
        pages.add(next);
        title.setText(next.title);
        // Lists that pick one (the language, tiles per row) open on the one in use.
        View focus = next.opened != null ? next.opened.view : next.first();
        next.opened = null;
        focus.requestFocus();
    }

    /** Back a page, everything sliding the other way: Settings closes from the first. */
    void back() {
        if (editing != null) {
            endEdit();
            return;
        }
        if (pages.size() == 1) {
            if (host != null) host.finish();
            return;
        }
        Page gone = pages.remove(pages.size() - 1);
        gone.view.animate().cancel();
        gone.view.animate().translationX(screenW - rightX).setDuration(SLIDE_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f)).withLayer().withEndAction(() -> {
                    // The language list stays, off the screen and out of focus's way, for next time.
                    if (gone == languagePage) gone.focusable(false);
                    else stage.removeView(gone.view);
                }).start();
        Page current = pages.get(pages.size() - 1);
        title.setText(current.title);
        current.focusable(true);
        current.refresh();
        slide(current.view, 0f);
        View leftNow = pages.size() >= 2 ? pages.get(pages.size() - 2).view : logo;
        slide(leftNow, leftNow == logo ? 0f : leftX - rightX);
        Row from = current.opened;
        current.opened = null;
        if (from != null) {
            from.view.setActivated(false);
            from.view.requestFocus();
        } else {
            current.first().requestFocus();
        }
    }

    /** The translation that puts the logo, or a list in the left column, just past the screen's left edge. */
    private float offLeft(View v) {
        return v == logo ? -(leftX + columnW) : -(rightX + columnW);
    }

    private void slide(View v, float x) {
        v.animate().cancel();
        // withLayer: drawn as one texture while it moves, not redrawn row by row on every frame.
        v.animate().translationX(x).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator(1.6f))
                .withLayer().start();
    }

    private void showAbout(Page page, Row row) {
        if (row.aboutText != null) {
            page.about.setText(row.aboutText);
            return;
        }
        int text = row.about != 0 ? row.about : page.footnote;
        if (text == 0) page.about.setText(null);
        else page.about.setText(text);
    }

    // ---- keys --------------------------------------------------------------------------------------

    /**
     * The key's field in place of its value, the saved key shown masked and selected: typing replaces it,
     * clearing it removes it, and left alone it stays.
     */
    private void edit(Row row, int index) {
        if (editing != null) endEdit();
        editing = row;
        editingKey = index;
        row.view.setActivated(true);
        row.value.setVisibility(View.GONE);
        row.field.setVisibility(View.VISIBLE);
        row.field.setText(mask(keys[index]));
        row.field.setSelectAllOnFocus(true);
        row.field.requestFocus();
        row.field.selectAll();
        InputMethodManager imm = (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) row.field.post(() -> imm.showSoftInput(row.field, InputMethodManager.SHOW_IMPLICIT));
    }

    private void endEdit() {
        Row row = editing;
        if (row == null) return;
        editing = null;
        keys[editingKey] = value(row.field, keys[editingKey]);
        settings.save(keys[0], keys[1], keys[2]);
        InputMethodManager imm = host != null ? (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE) : null;
        if (imm != null) imm.hideSoftInputFromWindow(row.field.getWindowToken(), 0);
        row.field.setVisibility(View.GONE);
        row.value.setVisibility(View.VISIBLE);
        row.view.setActivated(false);
        row.refresh();
        row.view.requestFocus();
    }

    private void startPhoneLink() {
        link = new PhoneLink(received -> {
            if (host == null) return;
            String[] names = {"tmdbKey", "youtubeKey", "plexToken"};
            for (int i = 0; i < names.length; i++) {
                String k = received.get(names[i]);
                if (k != null) keys[i] = k.trim();
            }
            settings.save(keys[0], keys[1], keys[2]);
            for (Page page : pages) page.refresh();
            Toast.makeText(host, R.string.saved_from_phone, Toast.LENGTH_SHORT).show();
        }, settings);
        Context app = ctx.getApplicationContext();
        link.backups = new PhoneLink.Backups() {
            @Override
            public String make() {
                try {
                    return Backup.make(app);
                } catch (org.json.JSONException | java.io.IOException e) {
                    return null;
                }
            }

            @Override
            public boolean restore(String json) {
                return SettingsScreen.this.restore(json);
            }

            @Override
            public void restored() {
                SettingsScreen.this.restored();
            }
        };
        phoneUrl = link.start();
    }

    // ---- SmartTube ---------------------------------------------------------------------------------

    /**
     * Install or Open, checked again when an app is installed or removed (the installer's screen closes
     * before the install is done) and whenever Settings gets the screen back (SettingsActivity.onResume).
     */
    private void watchSmartTube() {
        packages = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                showSmartTube();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) host.registerReceiver(packages, filter, Context.RECEIVER_NOT_EXPORTED);
        else host.registerReceiver(packages, filter);
    }

    /** General's Home button row and Restart…, as the service is now; again when Settings is back in front. */
    void showHomeButton() {
        if (homeButtonRow == null) return;
        homeButtonRow.refresh();
        restartRow.view.setVisibility(HomeButtonLink.bound() ? View.VISIBLE : View.GONE);
    }

    void showSmartTube() {
        if (smartTubeRow == null) return;
        boolean installed = SmartTube.launchIntent(ctx) != null;
        smartTubeRow.label.setText(installed ? R.string.smarttube_open : R.string.smarttube_install);
        if (BuildConfig.STORE) smartTubeRow.view.setVisibility(installed ? View.VISIBLE : View.GONE);
    }

    // ---- updates -----------------------------------------------------------------------------------

    /**
     * The installed version and a row that checks for a newer one, then offers to install it. The store
     * build leaves it to Play: the row is there only while Play has a newer version. A development build
     * only says how it updates.
     */
    private void showUpdates(Row status, Row button) {
        String version = Updater.installedVersion(ctx);
        if (BuildConfig.STORE) {
            status.value.setText(ctx.getString(R.string.version_current, version));
            button.view.setVisibility(StoreUpdates.available() ? View.VISIBLE : View.GONE);
            button.label.setText(R.string.store_update);
            button.action = () -> StoreUpdates.open(host);
            return;
        }
        if (Updater.isDevelopmentBuild(ctx)) {
            status.value.setText(ctx.getString(R.string.version_current, version));
            status.about = R.string.update_development;
            button.view.setVisibility(View.GONE);
            return;
        }
        // What the last check found: the gear menu, which opens Settings, has just asked GitHub again.
        Updater.Release found = Updater.available(ctx);
        if (found != null) {
            offerUpdate(status, button, found);
            return;
        }
        status.value.setText(ctx.getString(R.string.version_current, version));
        boolean[] checking = {false};
        button.action = () -> {
            // Not disabled while it checks: a disabled row loses focus, which went to the top of the list.
            if (checking[0]) return;
            checking[0] = true;
            button.label.setText(R.string.checking_updates);
            Updater.check(ctx, (newer, failed) -> {
                checking[0] = false;
                if (newer != null) {
                    offerUpdate(status, button, newer);
                } else {
                    status.value.setText(ctx.getString(failed ? R.string.update_check_failed
                            : R.string.version_up_to_date, version));
                    button.label.setText(R.string.check_updates);
                }
            });
        };
    }

    private void offerUpdate(Row status, Row button, Updater.Release release) {
        status.value.setText(ctx.getString(R.string.update_available, release.version));
        button.label.setText(ctx.getString(R.string.update_to, release.version));
        button.action = () -> {
            if (host != null) startUpdate(host, release);
        };
    }

    /** A saved key as shown: bullets and the last four characters; "" when not set. */
    private static String mask(String key) {
        return key.isEmpty() ? "" : "••••••••" + key.substring(Math.max(0, key.length() - 4));
    }

    /** What a field holds: the saved key while it still shows the mask, else what was typed. */
    private static String value(EditText field, String saved) {
        String text = field.getText().toString().trim();
        return !saved.isEmpty() && text.equals(mask(saved)) ? saved : text;
    }

    /**
     * What's new in the release, with Install and Cancel; Install downloads it and Android's own screen asks
     * to confirm. Shared with the gear menu.
     */
    static void startUpdate(Activity activity, Updater.Release release) {
        View panel = activity.getLayoutInflater().inflate(R.layout.dialog_update,
                activity.findViewById(android.R.id.content), false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(activity.getString(R.string.update_to, release.version));
        TextView notes = panel.findViewById(R.id.notes);
        CharSequence text = changes(activity, release.notes);
        notes.setText(text);
        ScrollView scroll = panel.findViewById(R.id.notesScroll);
        scroll.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        float density = activity.getResources().getDisplayMetrics().density;
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (560 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        // Long notes scroll in at most half the screen; then Up from the buttons reaches them, and Up and
        // Down scroll.
        int cap = activity.getResources().getDisplayMetrics().heightPixels / 2;
        scroll.post(() -> {
            if (scroll.getHeight() <= cap) return;
            scroll.getLayoutParams().height = cap;
            scroll.setFocusable(true);
            scroll.requestLayout();
        });
        View install = panel.findViewById(R.id.install), cancel = panel.findViewById(R.id.cancelUpdate);
        int highlight = Highlight.color(activity);
        Highlight.pill((TextView) install, highlight);
        Highlight.pill((TextView) cancel, highlight);
        cancel.setOnClickListener(v -> dialog.dismiss());
        install.setOnClickListener(v -> {
            dialog.dismiss();
            Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show();
            Updater.install(activity, release, problem -> Toast.makeText(activity, problem, Toast.LENGTH_LONG).show());
        });
        dialog.show();
        install.requestFocus();
    }

    /**
     * The release notes as shown before installing: "## 1.4.3" lines as version headings, **bold** in white
     * Outfit Medium (Outfit has no bold), "- " items as bullets, links as their text, and the download link
     * and checksum (for people downloading by hand) left out.
     */
    private static CharSequence changes(Context context, String markdown) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        android.graphics.Typeface medium = UiFont.get(context, R.string.font_medium);
        for (String line : markdown.replace("\r", "").split("\n")) {
            String l = line.trim();
            if (l.startsWith("Latest APK") || l.startsWith("SHA-256")) continue;
            // A link shows as its text (a TV can't follow it), inline code without its backticks.
            l = l.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1").replaceAll("`([^`]*)`", "$1");
            if (l.startsWith("- ") || l.startsWith("* ")) l = "• " + l.substring(2);
            if (l.isEmpty() && (out.length() == 0 || endsWithBlankLine(out))) continue;
            if (l.startsWith("#")) {
                // A version's notes when several are shown (Updater.notesSince): its number, larger, after a
                // blank line.
                if (out.length() > 0 && !endsWithBlankLine(out)) out.append('\n');
                int start = out.length();
                out.append(l.replaceFirst("^#+\\s*", ""));
                Object weight = android.os.Build.VERSION.SDK_INT >= 28 ? new android.text.style.TypefaceSpan(medium)
                        : new android.text.style.StyleSpan(android.graphics.Typeface.BOLD);
                out.setSpan(weight, start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.ForegroundColorSpan(Color.WHITE), start, out.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.RelativeSizeSpan(1.25f), start, out.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }
            int at = 0;
            while (true) {
                int open = l.indexOf("**", at);
                int close = open < 0 ? -1 : l.indexOf("**", open + 2);
                if (close < 0) break;
                out.append(l, at, open);
                int start = out.length();
                out.append(l, open + 2, close);
                Object weight = android.os.Build.VERSION.SDK_INT >= 28 ? new android.text.style.TypefaceSpan(medium)
                        : new android.text.style.StyleSpan(android.graphics.Typeface.BOLD);
                out.setSpan(weight, start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.ForegroundColorSpan(Color.WHITE), start, out.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                at = close + 2;
            }
            out.append(l.substring(at)).append('\n');
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.delete(out.length() - 1, out.length());
        return out;
    }

    private static boolean endsWithBlankLine(CharSequence s) {
        return s.length() >= 2 && s.charAt(s.length() - 1) == '\n' && s.charAt(s.length() - 2) == '\n';
    }
}
