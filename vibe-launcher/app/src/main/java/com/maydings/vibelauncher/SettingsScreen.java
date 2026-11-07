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
            // The name and the value share the row: when both don't fit, the name gets what it needs (up to 60%)
            // and the value gives way, rather than the name being cut short by a long value ("Custom wallp…").
            view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (field != null || value.getVisibility() != View.VISIBLE) return;
                int room = label.getWidth() + value.getWidth();
                if (room <= 0) return;
                float name = label.getPaint().measureText(String.valueOf(label.getText()));
                int wants = (int) Math.ceil(value.getPaint().measureText(String.valueOf(value.getText())))
                        + value.getPaddingLeft() + value.getPaddingRight() + 1;
                int max = name + wants <= room ? wants : Math.max(Math.round(room * 0.4f), room - Math.round(name) - 1);
                max = Math.min(max, wants);
                if (value.getMaxWidth() != max) {
                    int fit = max;
                    v.post(() -> value.setMaxWidth(fit));
                }
            });
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
        /** Its window trimmed to a whole number of rows when it scrolls: not for a page of other things (the wheel). */
        boolean wholeRows = true;

        Page(CharSequence title) {
            this.title = title;
            view = new LinearLayout(ctx);
            view.setOrientation(LinearLayout.VERTICAL);
            // A long list (a clean-up list's plan, the lists themselves) stops short of the column's bottom: the
            // text under it keeps room for five lines, where it had none and was cut off.
            int maxListH = screenH - (int) ((82 + 16) * density) - (int) (110 * density);
            ScrollView scroll = new ScrollView(ctx) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    int size = View.MeasureSpec.getSize(heightSpec);
                    if (View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED || size > maxListH) {
                        heightSpec = View.MeasureSpec.makeMeasureSpec(maxListH, View.MeasureSpec.AT_MOST);
                    }
                    super.onMeasure(widthSpec, heightSpec);
                    // A list taller than the window: the window made a whole number of rows, so no row shows in part
                    // and scrolling to the next row moves exactly one row (rows here are all the same height).
                    if (getChildCount() == 0 || !wholeRows) return;
                    ViewGroup content = (ViewGroup) getChildAt(0);
                    int inner = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
                    if (content.getMeasuredHeight() <= inner) return;
                    View row = null;
                    for (int i = 0; i < content.getChildCount() && row == null; i++) {
                        if (content.getChildAt(i).getVisibility() != View.GONE) row = content.getChildAt(i);
                    }
                    if (row == null) return;
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
                    int gap = lp.topMargin + lp.bottomMargin, pitch = row.getMeasuredHeight() + gap;
                    int whole = (inner + gap) / pitch;
                    if (whole < 1) return;
                    setMeasuredDimension(getMeasuredWidth(), whole * pitch - gap + getPaddingTop() + getPaddingBottom());
                }

                // Scrolled to a focused row with the list's margin above and below counted (Android's own leaves it
                // out): each step moves exactly one row, and the first row comes back to its place in one press.
                @Override
                protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                    if (getChildCount() == 0) return 0;
                    int top = getScrollY() + getPaddingTop(), bottom = getScrollY() + getHeight() - getPaddingBottom();
                    int delta = 0;
                    if (rect.top < top) delta = rect.top - top;
                    else if (rect.bottom > bottom) delta = rect.bottom - bottom;
                    int max = Math.max(0, getChildAt(0).getHeight() + getPaddingTop() + getPaddingBottom() - getHeight());
                    return Math.max(0, Math.min(max, getScrollY() + delta)) - getScrollY();
                }

                // Only what's in the window is drawn: the column around the list doesn't clip (the focused row grows
                // past its edges), so rows scrolled out of sight would be drawn over the text under the list.
                @Override
                protected void dispatchDraw(android.graphics.Canvas canvas) {
                    canvas.save();
                    canvas.clipRect(getScrollX(), getScrollY(), getScrollX() + getWidth(), getScrollY() + getHeight());
                    super.dispatchDraw(canvas);
                    canvas.restore();
                }
            };
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setVerticalFadingEdgeEnabled(false);   // a fading edge would scroll a part-row further in
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
    private final String[] keys = new String[5];   // TMDB, YouTube, Plex, Pexels, Pixabay, as saved
    private Row editing;
    private int editingKey;

    private PhoneLink link;
    /**
     * Until when the phone page outlives Settings (System clock millis): five minutes from the pairing page opening,
     * or until a code sent from the phone has been answered. Android may close Settings while its own Settings is in
     * front (Home pressed there resets Vibe Launcher's task), which would leave the phone's code nowhere to go.
     * 0 normally.
     */
    private long keepLinkUntil;
    private final Runnable stopKeptLink = () -> {
        if (host == null && link != null) link.stop();
        keepLinkUntil = 0;
    };
    private String phoneUrl;
    private BroadcastReceiver packages;
    private Row smartTubeRow;

    private SettingsScreen(Context app, String language) {
        ctx = new ContextThemeWrapper(Lang.wrap(app), android.R.style.Theme_Material_NoActionBar);
        builtFor = language;
        this.language = language;
        density = ctx.getResources().getDisplayMetrics().density;
        settings = new VibeSettings(ctx);
        Adb.use(app);
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
        ((TextView) screen.findViewById(R.id.logoVersion)).setText(
                ctx.getString(R.string.settings_version, BuildConfig.VERSION_NAME));
        title = screen.findViewById(R.id.title);
        ((FrameLayout.LayoutParams) logo.getLayoutParams()).leftMargin = leftX + (columnW - logo.getLayoutParams().width) / 2;

        root = new Page(ctx.getString(R.string.settings));
        generalRow = root.link(R.string.tab_general, null, this::general);
        root.link(R.string.tab_appearance, null, this::appearance);
        root.link(R.string.tab_backdrops, null, this::backdrops);
        root.link(R.string.tab_weather, null, this::weather);
        root.link(R.string.tab_keys, null, this::apiKeys);
        appsRootRow = root.link(R.string.tab_apps, null, this::apps);
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
        keys[3] = settings.pexelsKey();
        keys[4] = settings.pixabayKey();
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

    /** Settings > Apps, and Apps > Default launcher: where the top bar's Make Vibe Launcher your Home opens. */
    private Row appsRootRow, defaultLauncherRow;

    /**
     * Opens on Apps > Default launcher, already in place (the top bar's Make Vibe Launcher your Home, with ADB
     * allowed): choosing Vibe Launcher there switches Home through it.
     */
    void showDefaultLauncher() {
        if (appsRootRow == null) {
            first().requestFocus();
            return;
        }
        Page apps = apps();
        open(apps, appsRootRow);
        Page launcher = defaultLauncher();
        open(launcher, defaultLauncherRow);
        // Where the slides end: they then run from there to there, so nothing moves.
        logo.setTranslationX(offLeft(logo));
        root.view.setTranslationX(offLeft(root.view));
        apps.view.setTranslationX(leftX - rightX);
        launcher.view.setTranslationX(0f);
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
        if (System.currentTimeMillis() < keepLinkUntil) {
            handler.removeCallbacks(stopKeptLink);
            handler.postDelayed(stopKeptLink, keepLinkUntil - System.currentTimeMillis());
        } else {
            link.stop();
        }
        // Turn debugging off when done: the port closes with Settings, once nothing more is queued for it.
        if (usedAdb && settings.adbOffWhenDone() && TvControl.canWrite(ctx)) {
            Context app = ctx.getApplicationContext();
            boolean wireless = Adb.usedWireless();
            adb().execute(() -> {
                TvControl.setDebugging(app, false);
                if (wireless) TvControl.setWirelessDebugging(app, false);
            });
            adbStatus = Adb.OFF;
        }
        usedAdb = false;
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
        page.toggle(R.string.power_on_home, settings::powerOnHome, settings::setPowerOnHome);
        page.link(R.string.back_button, () -> backName(settings.backAction()), this::backAction);
        Row channelKeys = page.toggle(R.string.ignore_channel_keys, settings::ignoreChannelKeys,
                settings::setIgnoreChannelKeys);
        channelKeys.about = R.string.ignore_channel_keys_short;
        // The TV restarted through its own ADB (an app may not restart it), after asking. runAdb connects first,
        // walking through turning ADB on where it isn't yet; the reboot then goes without waiting for an answer,
        // which would never come (the connection drops) and read as a failure.
        page.add(R.string.restart, null, () -> explainThenDo(R.string.restart, R.string.restart_confirm,
                () -> runAdb(page, null, r -> adb().execute(() -> Adb.shell("reboot", false)))));
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
        Row bar = page.link(R.string.top_bar, () -> ctx.getString(settings.topBarMinimal()
                ? R.string.top_bar_minimal : R.string.top_bar_full), this::topBar);
        bar.about = R.string.top_bar_short;
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
        // The TV's own animation speed: with WRITE_SECURE_SETTINGS, which the TV's ADB grants (TvControl).
        Row speed = page.link(R.string.anim_speed, () -> animName(TvControl.animationScale(ctx)), this::animSpeed);
        speed.about = R.string.anim_short;
        return page;
    }

    private String animName(float scale) {
        return ctx.getString(scale == 0f ? R.string.off : scale < 1f ? R.string.anim_fast : R.string.anim_normal);
    }

    /**
     * Normal, Faster (half) or Off for the TV's three animation scales, as Developer options sets them. Before
     * Settings > Apps has been allowed once there's no permission to: the row says where to go.
     */
    private Page animSpeed() {
        Page page = new Page(ctx.getString(R.string.anim_speed));
        page.footnote = R.string.anim_short;
        for (float scale : new float[]{1f, 0.5f, 0f}) {
            // Picked, it's ticked and the list stays: Back or Left goes back.
            Row row = page.add(animName(scale), () -> animName(TvControl.animationScale(ctx)).equals(animName(scale))
                    ? "✓" : "", () -> {
                if (!TvControl.setAnimationScale(ctx, scale)) {
MainActivity.showError(host, R.string.anim_short);
                }
                page.refresh();
            });
            if (animName(TvControl.animationScale(ctx)).equals(animName(scale))) page.opened = row;
        }
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
        page.wholeRows = false;   // a row, the preview and the wheel: all of it shows, not a whole number of rows
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
        page.list.addView(wheel[0], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (180 * density)));
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

    // ---- weather -----------------------------------------------------------------------------------

    private static final int[] WEATHER_STYLES = {R.string.weather_style_sky, R.string.weather_style_high_low,
            R.string.weather_style_temp};

    /**
     * Weather: the line under the date on Home (Weather) on or off, what it shows, in which degrees, and for where.
     * Home looks it up again when it comes back (onResume), so a change here shows as Settings closes.
     */
    private Page weather() {
        Page page = new Page(ctx.getString(R.string.tab_weather));
        page.footnote = R.string.weather_credit;   // Open-Meteo's terms ask for it
        Row on = page.toggle(R.string.weather_show, settings::weatherOn, settings::setWeatherOn);
        on.about = R.string.weather_show_short;
        Row style = page.link(R.string.weather_line, () -> ctx.getString(WEATHER_STYLES[
                Math.max(0, Math.min(WEATHER_STYLES.length - 1, settings.weatherStyle()))]), this::weatherStyle);
        style.about = R.string.weather_line_short;
        page.link(R.string.weather_units, () -> unitsName(settings.weatherUnits()), this::weatherUnits);
        Row where = page.link(R.string.weather_location, this::placeName, this::weatherLocation);
        where.about = R.string.weather_location_short;
        return page;
    }

    private Page weatherStyle() {
        Page page = new Page(ctx.getString(R.string.weather_line));
        for (int i = 0; i < WEATHER_STYLES.length; i++) {
            int value = i;
            // Picked, it's ticked and the list stays: Back or Left goes back.
            Row row = page.add(WEATHER_STYLES[i], () -> value == settings.weatherStyle() ? "✓" : "", () -> {
                settings.setWeatherStyle(value);
                page.refresh();
            });
            if (i == settings.weatherStyle()) page.opened = row;
        }
        return page;
    }

    private String unitsName(int units) {
        if (units == VibeSettings.UNITS_C) return "°C";
        if (units == VibeSettings.UNITS_F) return "°F";
        return ctx.getString(R.string.weather_units_auto, Weather.fahrenheitHere() ? "°F" : "°C");
    }

    private Page weatherUnits() {
        Page page = new Page(ctx.getString(R.string.weather_units));
        for (int units : new int[]{VibeSettings.UNITS_AUTO, VibeSettings.UNITS_C, VibeSettings.UNITS_F}) {
            Row row = page.add(unitsName(units), () -> units == settings.weatherUnits() ? "✓" : "", () -> {
                settings.setWeatherUnits(units);
                page.refresh();
            });
            if (units == settings.weatherUnits()) page.opened = row;
        }
        return page;
    }

    /** The place the weather is for: the city picked, or Automatic (with the city it found, once known). */
    private CharSequence placeName() {
        Weather.Place picked = settings.weatherPlace();
        if (picked != null) return picked.name;
        String auto = Weather.autoPlaceName(ctx);
        return auto != null ? ctx.getString(R.string.weather_auto) + " (" + auto + ")" : ctx.getString(R.string.weather_auto);
    }

    /** Matches for the city typed on Weather > Location, under the search row; picked, one is the weather's. */
    private final List<Row> cityRows = new ArrayList<>();

    /**
     * Weather > Location: Automatic (the TV's city, from its internet address), the city picked if there is one,
     * and a search: type a city, Done, and its matches come under it to pick from.
     */
    private Page weatherLocation() {
        Page page = new Page(ctx.getString(R.string.weather_location));
        page.footnote = R.string.weather_location_short;
        cityRows.clear();
        Row auto = page.add(R.string.weather_auto, () -> settings.weatherPlace() == null ? "✓" : "", () -> {
            settings.setWeatherPlace(null);
            page.refresh();
        });
        String found = Weather.autoPlaceName(ctx);
        if (found != null) auto.aboutText = found;
        page.opened = auto;
        Weather.Place picked = settings.weatherPlace();
        if (picked != null) {
            Row city = page.add(picked.name, () -> picked.name.equals(placeNameIfPicked()) ? "✓" : "", () -> {
                settings.setWeatherPlace(picked);
                page.refresh();
            });
            page.opened = city;
        }
        Row search = page.add(R.string.weather_search, null, null);
        search.field = inflateField(search);
        search.field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        search.action = () -> {
            search.view.setActivated(true);
            search.field.setVisibility(View.VISIBLE);
            search.field.setText("");
            search.field.requestFocus();
            InputMethodManager imm = (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) search.field.post(() -> imm.showSoftInput(search.field, InputMethodManager.SHOW_IMPLICIT));
        };
        search.field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL
                    || actionId == EditorInfo.IME_ACTION_SEARCH) {
                String name = search.field.getText().toString().trim();
                endCitySearch(search);
                if (!name.isEmpty()) findCity(page, search, name);
            }
            return true;
        });
        search.field.setOnFocusChangeListener((v, focused) -> {
            if (!focused && search.field.getVisibility() == View.VISIBLE) endCitySearch(search);
        });
        return page;
    }

    private String placeNameIfPicked() {
        Weather.Place p = settings.weatherPlace();
        return p != null ? p.name : null;
    }

    private void endCitySearch(Row search) {
        InputMethodManager imm = host != null ? (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE) : null;
        if (imm != null) imm.hideSoftInputFromWindow(search.field.getWindowToken(), 0);
        search.field.setVisibility(View.GONE);
        search.view.setActivated(false);
        search.view.requestFocus();
    }

    /** The cities by this name, under the search row (the last search's matches go first); none, a line says so. */
    private void findCity(Page page, Row search, String name) {
        Toast.makeText(host, R.string.weather_searching, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Weather.Place> found;
            try {
                found = Weather.search(name);
            } catch (java.io.IOException e) {
                found = null;
            }
            List<Weather.Place> places = found;
            handler.post(() -> {
                if (host == null) return;
                for (Row old : cityRows) {
                    page.list.removeView(old.view);
                    page.rows.remove(old);
                    page.stops.remove(old.view);
                }
                cityRows.clear();
                if (places == null) {
                    MainActivity.showError(host, R.string.weather_search_failed);
                    return;
                }
                if (places.isEmpty()) {
                    Row none = page.add(ctx.getString(R.string.weather_no_match, name), null, null);
                    cityRows.add(none);
                    return;
                }
                for (Weather.Place place : places) {
                    Row row = page.add(place.name, () -> place.name.equals(placeNameIfPicked()) ? "✓" : "", () -> {
                        settings.setWeatherPlace(place);
                        page.refresh();
                    });
                    cityRows.add(row);
                }
                cityRows.get(0).view.requestFocus();
            });
        }, "city-search").start();
    }

    // ---- backdrops ---------------------------------------------------------------------------------

    /** Backdrops: where films' and shows' pictures come from (and in what order), their file format, and Reload. */
    private Page backdrops() {
        Page page = new Page(ctx.getString(R.string.tab_backdrops));
        Row sources = page.link(R.string.art_sources, this::artSummary, this::artSources);
        sources.about = R.string.art_sources_short;
        Row custom = page.link(R.string.wp_custom, () -> ctx.getString(WALLPAPER_MODES[settings.wallpaperMode()]),
                this::wallpapersPage);
        custom.about = R.string.wp_custom_short;
        page.link(R.string.picture_format, () -> ctx.getString(settings.jpegStills() ? R.string.picture_jpeg
                : R.string.picture_png), this::pictureFormat);
        page.add(R.string.reload_backdrops, null, () -> {
            MainActivity home = home();
            if (home != null) home.reloadBackdrops();
            Toast.makeText(host, R.string.backdrops_reloading, Toast.LENGTH_SHORT).show();
        });
        return page;
    }

    // ---- top bar ---------------------------------------------------------------------------------------

    /**
     * Appearance > Top bar: the full bar, or the minimal one (small round icons, the time and the weather straight
     * over the background, always showing), and what's in it: the clock, the date (the full bar only) and the
     * weather (the Weather tab's own switch). Home applies them as it comes back (MainActivity.applyTopBarStyle).
     */
    private Page topBar() {
        Page page = new Page(ctx.getString(R.string.top_bar));
        Row style = page.link(R.string.top_bar_style, () -> ctx.getString(settings.topBarMinimal()
                ? R.string.top_bar_minimal : R.string.top_bar_full), this::topBarStyle);
        style.about = styleAbout(settings.topBarMinimal());
        topBarPage = page;
        topBarStyleRow = style;
        page.toggle(R.string.show_clock, settings::showClock, settings::setShowClock);
        Row date = page.toggle(R.string.show_date, settings::showDate, settings::setShowDate);
        date.about = R.string.show_date_short;
        Row weather = page.toggle(R.string.tab_weather, settings::weatherOn, settings::setWeatherOn);
        weather.about = R.string.weather_show_short;
        return page;
    }

    /** Top bar while it's open, and its Style row: its note says what the style picked is. */
    private Page topBarPage;
    private Row topBarStyleRow;

    private static int styleAbout(boolean minimal) {
        return minimal ? R.string.top_bar_minimal_short : R.string.top_bar_full_short;
    }

    private Page topBarStyle() {
        Page page = new Page(ctx.getString(R.string.top_bar_style));
        for (boolean minimal : new boolean[]{false, true}) {
            // Picked, it's ticked and the list stays: Back or Left goes back.
            Row row = page.add(minimal ? R.string.top_bar_minimal : R.string.top_bar_full,
                    () -> minimal == settings.topBarMinimal() ? "✓" : "", () -> {
                        settings.setTopBarMinimal(minimal);
                        page.refresh();
                        if (topBarStyleRow != null) {   // the list on the left: its value and note now
                            topBarStyleRow.about = styleAbout(minimal);
                            topBarStyleRow.refresh();
                            if (topBarPage != null) showAbout(topBarPage, topBarStyleRow);
                        }
                    });
            if (minimal == settings.topBarMinimal()) page.opened = row;
        }
        return page;
    }

    // ---- custom wallpapers ---------------------------------------------------------------------------

    private static final int[] WALLPAPER_MODES = {R.string.off, R.string.wp_use_no_picture, R.string.wp_use_always};

    /** Backdrops > Custom wallpapers while it's open: its list is brought up to date as wallpapers come in. */
    private Page wallpaperPage;

    /**
     * Backdrops > Custom wallpapers: when they show (Off, where an app has no picture, or always instead of the
     * backdrops), adding them (from the phone's page, or picked from Wallhaven's, Pexels', Pixabay's, NASA's or
     * Commons' grid), and the ones there, newest first: OK on one asks to delete it.
     */
    private Page wallpapersPage() {
        Page page = new Page(ctx.getString(R.string.wp_custom));
        wallpaperPage = page;
        page.footnote = R.string.wp_custom_short;
        Row use = page.link(R.string.wp_use, () -> ctx.getString(WALLPAPER_MODES[settings.wallpaperMode()]),
                this::wallpaperModes);
        use.about = R.string.wp_use_short;
        if (phoneUrl != null && link != null && link.wallpaperUrl() != null) {
            Row phone = page.link(R.string.wp_add_phone, null,
                    () -> phone(R.string.wp_add_phone, link.wallpaperUrl(), R.string.wp_scan));
            phone.about = R.string.wp_add_phone_short;
        }
        Row wallhaven = page.add(R.string.wp_browse_wallhaven, null,
                () -> host.startActivity(WallpaperBrowserActivity.intent(host, Wallpapers.WALLHAVEN)));
        wallhaven.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        wallhaven.about = R.string.wp_credit_wallhaven;
        Row pexels = page.add(R.string.wp_browse_pexels, null, () -> {
            if (settings.pexelsKey().isEmpty()) MainActivity.showError(host, R.string.wp_pexels_no_key);
            else host.startActivity(WallpaperBrowserActivity.intent(host, Wallpapers.PEXELS));
        });
        pexels.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        pexels.about = R.string.pexels_short;
        Row pixabay = page.add(R.string.wp_browse_pixabay, null, () -> {
            if (settings.pixabayKey().isEmpty()) MainActivity.showError(host, R.string.wp_pixabay_no_key);
            else host.startActivity(WallpaperBrowserActivity.intent(host, Wallpapers.PIXABAY));
        });
        pixabay.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        pixabay.about = R.string.pixabay_short;
        Row nasa = page.add(R.string.wp_browse_nasa, null,
                () -> host.startActivity(WallpaperBrowserActivity.intent(host, Wallpapers.NASA)));
        nasa.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        nasa.about = R.string.wp_credit_nasa;
        Row commons = page.add(R.string.wp_browse_commons, null,
                () -> host.startActivity(WallpaperBrowserActivity.intent(host, Wallpapers.COMMONS)));
        commons.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        commons.about = R.string.wp_credit_commons;
        Row mine = page.add(R.string.wp_mine, () -> String.valueOf(Wallpapers.get(ctx).list().size()),
                () -> host.startActivity(WallpaperBrowserActivity.intent(host, WallpaperBrowserActivity.MINE)));
        mine.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        mine.about = R.string.wp_mine_short;
        return page;
    }

    private Page wallpaperModes() {
        Page page = new Page(ctx.getString(R.string.wp_use));
        for (int mode = 0; mode < WALLPAPER_MODES.length; mode++) {
            int value = mode;
            // Picked, it's ticked and the list stays: Back or Left goes back.
            Row row = page.add(WALLPAPER_MODES[mode], () -> value == settings.wallpaperMode() ? "✓" : "", () -> {
                settings.setWallpaperMode(value);
                page.refresh();
            });
            if (mode == settings.wallpaperMode()) page.opened = row;
        }
        return page;
    }

    /** Back on Settings (from a wallpaper grid), or one arrived from the phone: how many there are now. */
    void showWallpapers() {
        if (wallpaperPage != null && pages.contains(wallpaperPage)) wallpaperPage.refresh();
    }

    /** The sources switched on and usable, in order: "TMDB, Nuvio, …". */
    private CharSequence artSummary() {
        List<String> on = new ArrayList<>();
        for (String s : settings.artOrder()) if (settings.artOn(s) && !artUnusable(s)) on.add(artName(s));
        return on.isEmpty() ? ctx.getString(R.string.off) : android.text.TextUtils.join(", ", on);
    }

    private String artName(String source) {
        switch (source) {
            case VibeSettings.ART_TMDB:
                return "TMDB";
            case VibeSettings.ART_STREMIO:
                return "Stremio";
            case VibeSettings.ART_ID:
                return "Nuvio";
            default:
                return ctx.getString(R.string.art_own);
        }
    }

    /** TMDB with no key: nothing to try, so it's last, greyed out, and can't be moved or switched. */
    private boolean artUnusable(String source) {
        return source.equals(VibeSettings.ART_TMDB) && settings.tmdbKey().isEmpty();
    }

    /** The sources as listed: the person's order, with one that can't be used now (TMDB, no key) last. */
    private List<String> artShown() {
        List<String> shown = new ArrayList<>(), last = new ArrayList<>();
        for (String s : settings.artOrder()) (artUnusable(s) ? last : shown).add(s);
        shown.addAll(last);
        return shown;
    }

    private int artAbout(String source) {
        switch (source) {
            case VibeSettings.ART_TMDB:
                return R.string.art_tmdb_short;
            case VibeSettings.ART_STREMIO:
                return R.string.art_stremio_short;
            case VibeSettings.ART_ID:
                return R.string.art_id_short;
            default:
                return R.string.art_own_short;
        }
    }

    /** The source being moved on Film & show sources (OK picked it up), or null. */
    private String movingArt;
    /** The order when it was picked up: dropped elsewhere, the backdrops are made again. */
    private List<String> orderWhenPicked;

    /**
     * Backdrops > Film & show sources: TMDB, the title id from the app, Stremio and the app's own picture, tried
     * in this order for each film or show card, the first with a picture used. OK picks a source up, Up and Down
     * carry it, OK again disables it (Back or Left put it down still enabled), OK again enables it; Right turns it on or off too. A change makes the backdrops again.
     */
    private Page artSources() {
        Page page = new Page(ctx.getString(R.string.art_sources));
        // What the list means, above it (outside the scrolling list, whose rows are all one height).
        TextView note = new TextView(ctx);
        note.setText(R.string.art_order_note);
        note.setTextColor(0xCCFFFFFF);
        note.setTextSize(15);
        note.setLineSpacing(0, 1.15f);
        note.setTypeface(UiFont.get(ctx, R.string.font_regular));
        int side = (int) (columnW * 0.02f) + 2 + (int) (22 * density);   // in line with the rows' names
        note.setPadding(side, 0, side, (int) (10 * density));
        page.view.addView(note, 0);
        movingArt = null;
        for (String source : artShown()) artRow(page, source);
        numberArt(page);
        return page;
    }

    private void artRow(Page page, String source) {
        boolean unusable = artUnusable(source);
        Row row = page.add(artName(source), () -> {
            if (unusable) return "";
            if (source.equals(movingArt)) return "\u2195";   // up-down arrow: Up and Down move it
            return ctx.getString(settings.artOn(source) ? R.string.art_enabled : R.string.art_disabled);
        }, null);
        row.view.setTag(source);
        if (unusable) {   // greyed out; focusable still, so its line under the list says it needs a key
            row.label.setAlpha(0.4f);
            row.view.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN
                        && pages.size() > 1) {
                    back();
                    return true;
                }
                return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;   // no On/Off
            });
            row.aboutText = ctx.getString(artAbout(source));
            return;
        }
        row.aboutText = ctx.getString(artAbout(source)) + "\n\n" + ctx.getString(R.string.art_sources_help);
        // OK goes round: Enabled, then moving (Up and Down carry it), then Disabled, then Enabled again.
        row.action = () -> {
            if (source.equals(movingArt)) {
                dropArt();
                settings.setArtOn(source, false);
                artChanged();
            } else if (!settings.artOn(source)) {
                settings.setArtOn(source, true);
                artChanged();
            } else {
                movingArt = source;
                orderWhenPicked = settings.artOrder();
            }
            page.refresh();
        };
        row.view.setOnKeyListener((v, keyCode, event) -> {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (source.equals(movingArt)) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (down) moveArt(page, row, keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                        return true;
                    case KeyEvent.KEYCODE_BACK:   // both its down and up: Settings doesn't go back as well
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (down) {
                            dropArt();
                            page.refresh();
                        }
                        return true;
                    default:
                        return false;   // OK: the click drops it
                }
            }
            if (!down) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                settings.setArtOn(source, !settings.artOn(source));
                row.refresh();
                artChanged();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pages.size() > 1) {
                back();
                return true;
            }
            return false;
        });
    }

    /** The source being moved one place up (-1) or down; focus stays on it, its neighbour goes round it. */
    private void moveArt(Page page, Row row, int dir) {
        int at = page.rows.indexOf(row), to = at + dir;
        if (at < 0 || to < 0 || to >= page.rows.size()) return;
        if (artUnusable((String) page.rows.get(to).view.getTag())) return;   // stays last, below the rest
        Row other = page.rows.get(to);
        page.list.removeView(other.view);   // not the focused one: it keeps focus
        int here = page.list.indexOfChild(row.view);
        page.list.addView(other.view, dir < 0 ? here + 1 : here);
        java.util.Collections.swap(page.rows, at, to);
        int sa = page.stops.indexOf(row.view), so = page.stops.indexOf(other.view);
        if (sa >= 0 && so >= 0) java.util.Collections.swap(page.stops, sa, so);
        // Saved in the listed order, except that one unusable now keeps its own place, for when it can be used.
        List<String> before = settings.artOrder(), order = new ArrayList<>();
        String unusable = null;
        for (Row r : page.rows) {
            String s = (String) r.view.getTag();
            if (artUnusable(s)) unusable = s;
            else order.add(s);
        }
        if (unusable != null) order.add(Math.min(before.indexOf(unusable), order.size()), unusable);
        settings.setArtOrder(order);
        numberArt(page);
        row.view.playSoundEffect(dir < 0 ? android.view.SoundEffectConstants.NAVIGATION_UP
                : android.view.SoundEffectConstants.NAVIGATION_DOWN);
    }

    private void dropArt() {
        movingArt = null;
        if (orderWhenPicked != null && !orderWhenPicked.equals(settings.artOrder())) artChanged();
        orderWhenPicked = null;
    }

    /** Each source's place before its name: 1 is tried first. */
    private void numberArt(Page page) {
        int n = 0;
        for (Row r : page.rows) {
            String s = (String) r.view.getTag();
            r.label.setText(artUnusable(s) ? artName(s) : (++n) + "   " + artName(s));
        }
    }

    /** A change in the sources: the backdrops are made again, once, shortly after the last change. */
    private void artChanged() {
        handler.removeCallbacks(artReload);
        handler.postDelayed(artReload, 1500);
    }

    private final Runnable artReload = () -> {
        MainActivity home = home();
        if (home != null) home.reloadBackdrops();
        if (host != null) Toast.makeText(host, R.string.backdrops_reloading, Toast.LENGTH_SHORT).show();
    };

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
        // SmartTube signed in beats a key (the user's own feed). Not on the TV: Downloader installs it from its
        // code, as it put Vibe Launcher on; without Downloader, how to get it.
        smartTubeRow = page.add("", null, () -> {
            Intent open = SmartTube.launchIntent(host);
            if (open != null) {
                host.startActivity(open);
                return;
            }
            if (!SmartTube.openDownloader(host)) {
                MainActivity.showError(host, ctx.getString(R.string.smarttube_get_downloader, SmartTube.CODE));
            }
        });
        smartTubeRow.about = R.string.smarttube_short;
        showSmartTube();
        key(page, R.string.plex_token, 2, R.string.plex_short);
        key(page, R.string.pexels_key, 3, R.string.pexels_short);
        key(page, R.string.pixabay_key, 4, R.string.pixabay_short);
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
                    if (ok) Toast.makeText(host, R.string.backup_saved, Toast.LENGTH_SHORT).show();
                    else MainActivity.showError(host, R.string.backup_failed);
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
                    else if (host != null) MainActivity.showError(host, R.string.restore_failed);
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
        else if (!granted) MainActivity.showError(host, R.string.storage_denied);
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

    /** A row's typing field (keys, the weather's city search); the row then leaves its value alone (Row). */
    private EditText inflateField(Row row) {
        return (EditText) ((android.view.ViewStub) row.view.findViewById(R.id.fieldStub)).inflate();
    }

    /** A key row: the saved key's last four characters; OK edits it in place. */
    private void key(Page page, int label, int index, int about) {
        Row row = page.add(label, () -> keys[index].isEmpty() ? ctx.getString(R.string.not_set) : mask(keys[index]), null);
        row.about = about;
        row.field = inflateField(row);
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

    /** ADB was used while Settings was open: what Turn debugging off when done turns off as it closes. */
    private boolean usedAdb;
    /** A look at the connection running now (pollAdb), so they don't pile up. */
    private boolean adbLooking;
    private boolean adbPolling;
    /** Apps, and Apps > ADB connection: the pages the connection is looked at for. */
    private Page appsPage, adbPage;

    /** ADB calls one at a time, in order, off the main thread (Adb). */
    private java.util.concurrent.ExecutorService adbThread;
    /** The last Adb answer (Adb.OK, OFF, …), or -1 while the first check runs. */
    private int adbStatus = -1;
    private boolean adbBusy;
    /** The apps on the Apps page now, with their rows: what Turn all back on brings up to date. */
    private final List<TvApps.App> shownApps = new ArrayList<>();

    /**
     * Apps: the TV's own network debugging (Adb) and whether Vibe Launcher may use it (OK on it, or the first
     * thing done through it, makes the TV ask), then Debloat (the clean-up lists, then the apps one by one),
     * Default launcher (Vibe Launcher or the TV's own) and, last, Turn debugging off when done.
     */
    private Page apps() {
        Page page = new Page(ctx.getString(R.string.tab_apps));
        appsPage = page;
        page.footnote = R.string.apps_short;
        Row connection = page.link(R.string.adb_connection, this::adbStatusText, this::adbConnection);
        connection.about = R.string.adb_connection_short;
        // Whether it's allowed already, without making the TV ask.
        adbStatus = -1;
        adb().execute(() -> {
            Adb.Result r = Adb.check(false);
            handler.post(() -> {
                adbStatus = r.status;
                page.refresh();
            });
            // Paired as far as Vibe Launcher knows, and Wireless debugging on: does the TV still take the key?
            // (Turned down, AdbTlsKey forgets it and the row says Not paired.)
            if (Adb.wirelessOn(ctx) && AdbTlsKey.paired(ctx) && r.status != Adb.NOT_PAIRED) {
                Adb.checkWireless();
                handler.post(page::refresh);
            }
        });
        pollAdb();
        Row debloat = page.link(R.string.debloat, null, this::debloat);
        debloat.about = R.string.apps_short;
        Row launcher = defaultLauncherRow = page.link(R.string.default_launcher, () -> ctx.getString(DefaultHome.isHome(ctx)
                ? R.string.app_name : R.string.home_stock), this::defaultLauncher);
        launcher.about = TvControl.homeUnsupported() ? R.string.home_unsupported : R.string.home_make_short;
        // Last: a choice most people never touch (and one that locks a computer's ADB out while it's on).
        Row offWhenDone = page.toggle(R.string.debug_off_when_done, settings::adbOffWhenDone, settings::setAdbOffWhenDone);
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
    private Page adbConnection() {
        Page page = new Page(ctx.getString(R.string.adb_connection));
        adbPage = page;
        page.footnote = R.string.adb_connection_short;
        Row status = page.add("", null, () -> {
            if (adbStatus != Adb.OK) runAdb(page, null, r -> {
            });
        });
        status.about = R.string.adb_connect_short;
        statusRow = status;
        statusNote = new TextView(ctx);
        statusNote.setTextColor(0xCCFFFFFF);
        statusNote.setTextSize(15);
        statusNote.setLineSpacing(0, 1.15f);
        statusNote.setTypeface(UiFont.get(ctx, R.string.font_regular));
        int side = (int) (16 * density);
        statusNote.setPadding(side, (int) (8 * density), side, (int) (22 * density));
        page.list.addView(statusNote);
        Row dev = page.add(R.string.dev_options, () -> onOff(devOptionsOn()), this::openDevOrAbout);
        dev.about = R.string.dev_options_short;
        Row usb = page.add(R.string.usb_adb_debugging, () -> inUse(!Adb.usedWireless(),
                onOff(adbSetting(android.provider.Settings.Global.ADB_ENABLED) == 1)),
                () -> unlessConnectedOtherWay(true, this::openDevOrAbout));
        usb.about = R.string.usb_adb_debugging_short;
        wirelessRow = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Row wireless = page.add(R.string.wireless_debugging, () -> inUse(Adb.usedWireless(),
                    Adb.wirelessNotRunning(ctx) ? ctx.getString(R.string.wireless_not_running)
                            : onOff(Adb.wirelessOn(ctx))),
                    () -> unlessConnectedOtherWay(false, this::openDevOrAbout));
            wireless.about = R.string.wireless_debugging_onoff_short;
            wirelessRow = wireless;
            if (link.pairUrl() != null) {
                Row pair = page.add(R.string.pair_with_code, () -> ctx.getString(AdbTlsKey.paired(ctx)
                        ? R.string.paired : R.string.not_paired), null);
                pair.action = () -> unlessConnectedOtherWay(false, () -> open(pairWireless(), pair));
                pair.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
                pair.about = R.string.wireless_debugging_short;
            }
        }
        refreshAdbPages();
        pollAdb();
        return page;
    }

    /** ADB connection's status at the top, and the line under it. */
    private Row statusRow;
    private TextView statusNote;

    /** Apps > ADB connection > Wireless debugging: its text under the list says how to start it when it's stuck. */
    private Row wirelessRow;

    private boolean devOptionsOn() {
        return adbSetting(android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1;
    }

    /** The way Vibe Launcher is connected now has a green dot before its value. */
    private CharSequence inUse(boolean thisWay, CharSequence value) {
        return adbStatus == Adb.OK && thisWay ? dot(0xFF34C759, value) : value;
    }

    private static CharSequence dot(int color, CharSequence text) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder("\u25CF  ");
        b.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
        return b.append(text);
    }

    /**
     * Connected already, the other way (port 5555 for the wireless rows, Wireless debugging for USB/ADB debugging):
     * a window saying this isn't needed, and Continue goes on anyway. Not connected, or this way: straight on.
     */
    private void unlessConnectedOtherWay(boolean portWay, Runnable then) {
        boolean otherWay = adbStatus == Adb.OK && (portWay == Adb.usedWireless());
        if (otherWay) explainThenDo(R.string.adb_connection, R.string.adb_not_needed, then);
        else then.run();
    }

    private CharSequence onOff(boolean on) {
        return ctx.getString(on ? R.string.on : R.string.off);
    }

    /** Developer options, or (still locked) the window saying how to unlock them, then About. */
    private void openDevOrAbout() {
        if (adbSetting(android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1) openDeveloperOptionsNow();
        else explainThenOpen(R.string.adb_dev_options, this::openAbout);
    }

    /**
     * Every three seconds while Apps or ADB connection is open: the connection looked at again (off
     * the main thread, one look at a time), and the rows brought up to date, so whatever was just turned on shows.
     */
    private void pollAdb() {
        if (adbPolling) return;
        adbPolling = true;
        handler.post(adbPoll);
    }

    /** The rows brought up to date, ADB connection's status with them. */
    private void refreshAdbPages() {
        for (Page page : pages) page.refresh();
        if (statusRow != null) {
            statusRow.label.setText(adbStatusText());
            statusNote.setText(adbStatus == Adb.OK ? R.string.adb_ready_note : R.string.adb_connect_short);
        }
        if (wirelessRow != null) {
            wirelessRow.about = Adb.wirelessNotRunning(ctx) ? R.string.wireless_not_running_short
                    : R.string.wireless_debugging_onoff_short;
            if (wirelessRow.view.hasFocus() && adbPage != null) showAbout(adbPage, wirelessRow);
        }
    }

    private final Runnable adbPoll = new Runnable() {
        @Override
        public void run() {
            Page top = pages.isEmpty() ? null : pages.get(pages.size() - 1);
            boolean showing = host != null && top != null && (top == adbPage || top == appsPage);
            if (!showing) {
                adbPolling = false;
                return;
            }
            if (!adbBusy && !adbLooking) {
                adbLooking = true;
                adb().execute(() -> {
                    Adb.Result r = Adb.check(false);
                    handler.post(() -> {
                        adbLooking = false;
                        if (!adbBusy) adbStatus = r.status;
                        refreshAdbPages();
                    });
                });
            } else {
                refreshAdbPages();
            }
            handler.postDelayed(this, 3000);
        }
    };

    /**
     * Debloat: Clean Your TV's lists first, then the options: Turn all back on, Turn off again (only while there's
     * something to), and the apps one by one, those you can turn off and those the TV needs.
     */
    private Page debloat() {
        Page page = new Page(ctx.getString(R.string.debloat));
        page.footnote = R.string.apps_short;
        Row lists = page.link(R.string.cyt_lists, null, this::cleanLists);
        lists.about = R.string.cyt_lists_short;
        Row undo = page.add(R.string.apps_turn_all_on, () -> {
            int n = TvApps.turnedOff(ctx).size();
            return n == 0 ? "" : String.valueOf(n);
        }, () -> turnAllOn(page));
        undo.about = R.string.apps_turn_all_on_short;
        // After a restore (or an update turning some back on): there only while there's something to do.
        Row again = page.link(R.string.apps_turn_off_again, () -> {
            int n = TvApps.toTurnOffAgain(ctx).size();
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
    private Page defaultLauncher() {
        Page page = new Page(ctx.getString(R.string.default_launcher));
        boolean unsupported = TvControl.homeUnsupported();
        page.footnote = unsupported ? R.string.home_unsupported : R.string.home_make_warning;
        Row vibe = page.add(ctx.getString(R.string.app_name), () -> DefaultHome.isHome(ctx) ? "✓" : "", () -> {
            if (DefaultHome.isHome(ctx) && TvControl.stockOn(ctx).isEmpty()) return;   // already
            if (unsupported) {
                MainActivity.showError(host, R.string.home_unsupported);
                return;
            }
            explainThenDo(R.string.home_make, R.string.home_make_warning, () -> makeHome(page));
        });
        Row stock = page.add(R.string.home_stock, () -> DefaultHome.isHome(ctx) ? "" : "✓", () -> {
            if (DefaultHome.isHome(ctx)) restoreHome(page);
        });
        // The TV's own only while there's one turned off to bring back (or it's in use now).
        stock.view.setVisibility(!DefaultHome.isHome(ctx) || !TvControl.stockOff(ctx).isEmpty() ? View.VISIBLE : View.GONE);
        page.opened = DefaultHome.isHome(ctx) ? vibe : stock;
        return page;
    }

    /**
     * The TV's own launchers off and Vibe Launcher set as Home, in one command that turns them back on if a step
     * fails; then Android asked where Home goes now. Not here: the launchers back on, and saying so. Here: they
     * go in our record, so Turn all back on brings them back too.
     */
    private void makeHome(Page page) {
        List<String> stock = TvControl.stockOn(ctx);
        runAdb(page, TvControl.makeHomeCommand(ctx, stock), r -> {
            boolean failed = r.output.contains("FAILED") || !DefaultHome.isHome(ctx);
            if (failed && !r.output.contains("FAILED") && !stock.isEmpty()) {
                // Set, but Home still goes elsewhere: the launchers back on, as they were.
                adb().execute(() -> Adb.shell(TvControl.restoreHomeCommand(ctx, stock), false));
            }
            if (!failed) for (String pkg : stock) TvApps.remember(ctx, pkg, false);
            if (failed) MainActivity.showError(host, R.string.home_failed);
            else Toast.makeText(host, R.string.home_done, Toast.LENGTH_LONG).show();
            page.refresh();   // the tick moves (runAdb refreshes once before the restore above lands)
            showTurnOffAgain();
        });
    }

    /** The TV's own launchers back on, Home given to the first, and taken out of our record. */
    private void restoreHome(Page page) {
        List<String> stock = TvControl.stockOff(ctx);
        if (stock.isEmpty()) return;
        runAdb(page, TvControl.restoreHomeCommand(ctx, stock), r -> {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            for (String pkg : stock) if (TvApps.isOn(pm, pkg)) TvApps.remember(ctx, pkg, true);
            page.refresh();
            showTurnOffAgain();
        });
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
                row.action = () -> MainActivity.showError(host, R.string.app_protected);
                continue;
            }
            row.aboutText = a.pkg;
            row.action = () -> runAdb(page, TvApps.command(a.pkg, !a.on), r -> {
                boolean wanted = !a.on;
                a.on = TvApps.isOn(ctx.getPackageManager(), a.pkg);
                if (a.on == wanted) TvApps.remember(ctx, a.pkg, a.on);
                else MainActivity.showError(host, R.string.adb_failed);
            });
            shownApps.add(a);
        }
        if (to < apps.size()) handler.postDelayed(() -> addAppRows(page, apps, to), 16);
    }

    /**
     * Clean-up lists (CleanLists): Clean Your TV's lists, this TV's brand first and the best matches at the top,
     * each with its TV's name, Android version and notes; OK opens its plan. Fetched as the page opens.
     */
    private Page cleanLists() {
        Page page = new Page(ctx.getString(R.string.cyt_lists));
        page.footnote = R.string.cyt_credit;
        Row loading = page.add(R.string.cyt_loading, null, null);
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            List<CleanLists.Tv> tvs;
            try {
                tvs = CleanLists.lists(app);
            } catch (java.io.IOException e) {
                handler.post(() -> loading.label.setText(R.string.cyt_failed));
                return;
            }
            handler.post(() -> {
                if (!pages.contains(page)) return;
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
    private void addListRows(Page page, List<CleanLists.Tv> tvs, int from) {
        if (!pages.contains(page)) return;
        int to = Math.min(tvs.size(), from + 12);
        for (int i = from; i < to; i++) {
            CleanLists.Tv tv = tvs.get(i);
            Row row = page.add(tv.shortName(), () -> tv.os, null);
            row.aboutText = tv.about();
            row.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
            row.action = () -> open(cleanPlan(tv), row);
        }
        if (to < tvs.size()) handler.postDelayed(() -> addListRows(page, tvs, to), 16);
    }

    /**
     * One list's plan for this TV: only what's installed here, each app with the list's section and reason under
     * the list. ✓ is what Turn off N apps turns off, OK on an app changing it; the maker's "by choice" sections start
     * unticked, what's already off shows Off, and what the TV needs is grayed and stays. Turned off, they go in our
     * own record, so Turn all back on undoes them whatever the list says later.
     */
    private Page cleanPlan(CleanLists.Tv tv) {
        Page page = new Page(tv.shortName());
        page.footnote = R.string.cyt_plan_short;
        Row go = page.add(R.string.cyt_loading, null, null);
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            List<CleanLists.Item> items;
            try {
                items = CleanLists.items(app, tv.folder);
            } catch (java.io.IOException e) {
                handler.post(() -> go.label.setText(R.string.cyt_failed));
                return;
            }
            java.util.Set<String> unique = new java.util.LinkedHashSet<>();   // once each, in the list's order
            for (CleanLists.Item item : items) unique.add(item.pkg);
            List<String> pkgs = new ArrayList<>(unique);
            List<TvApps.App> apps = TvApps.installed(app, pkgs);
            handler.post(() -> {
                if (pages.contains(page)) showPlan(page, go, items, apps);
            });
        }, "CleanLists").start();
        return page;
    }

    private void showPlan(Page page, Row go, List<CleanLists.Item> items, List<TvApps.App> apps) {
        java.util.Map<String, CleanLists.Item> byPkg = new java.util.HashMap<>();
        for (CleanLists.Item item : items) if (!byPkg.containsKey(item.pkg)) byPkg.put(item.pkg, item);
        java.util.Set<String> ticked = new java.util.LinkedHashSet<>();
        for (TvApps.App a : apps) {
            if (!a.needed && a.on && !byPkg.get(a.pkg).optional) ticked.add(a.pkg);
        }
        Runnable count = () -> go.label.setText(ctx.getString(R.string.cyt_turn_off_n, ticked.size()));
        count.run();
        go.action = () -> {
            if (ticked.isEmpty()) return;
            List<String> chosen = new ArrayList<>(ticked);
            StringBuilder cmd = new StringBuilder();
            for (String pkg : chosen) cmd.append(cmd.length() == 0 ? "" : "; ").append(TvApps.command(pkg, false));
            runAdb(page, cmd.toString(), r -> {
                android.content.pm.PackageManager pm = ctx.getPackageManager();
                int off = 0;
                for (String pkg : chosen) {
                    if (TvApps.isOn(pm, pkg)) continue;
                    TvApps.remember(ctx, pkg, false);
                    ticked.remove(pkg);
                    off++;
                }
                for (TvApps.App a : apps) a.on = TvApps.isOn(pm, a.pkg);
                Toast.makeText(host, off == chosen.size() ? ctx.getString(R.string.cyt_done, off)
                        : ctx.getString(R.string.adb_failed), Toast.LENGTH_SHORT).show();
                count.run();
                page.refresh();
            });
        };
        for (TvApps.App a : apps) {
            CleanLists.Item item = byPkg.get(a.pkg);
            Row row = page.add(a.label, () -> a.needed ? "" : !a.on ? ctx.getString(R.string.off)
                    : ticked.contains(a.pkg) ? "✓" : "", null);
            StringBuilder about = new StringBuilder();
            // Short enough for the space under the list: some sections are a sentence long.
            if (!item.section.isEmpty()) about.append(CleanLists.trim(item.section, 70)).append('\n');
            if (!item.reason.isEmpty()) about.append(CleanLists.trim(item.reason, 90)).append('\n');
            about.append(a.pkg);
            if (a.needed) {
                about.append('\n').append(ctx.getString(R.string.app_protected));
                row.label.setAlpha(0.45f);
                row.value.setAlpha(0.45f);
                row.action = () -> MainActivity.showError(host, R.string.app_protected);
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
    private Row turnOffAgainRow;

    private void showTurnOffAgain() {
        if (turnOffAgainRow == null) return;
        turnOffAgainRow.view.setVisibility(TvApps.toTurnOffAgain(ctx).isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * The apps to turn off again (TvApps.toTurnOffAgain), listed first so it's clear what goes, with Turn off
     * now at the top doing them all in one go; then back to Apps, its row gone once there's nothing left.
     */
    private Page turnOffAgain(Page apps) {
        Page page = new Page(ctx.getString(R.string.apps_turn_off_again));
        page.footnote = R.string.apps_turn_off_again_short;
        List<TvApps.App> list = TvApps.toTurnOffAgain(ctx);
        page.add(R.string.apps_turn_off_now, () -> String.valueOf(list.size()), () -> {
            StringBuilder cmd = new StringBuilder();
            for (TvApps.App a : list) cmd.append(cmd.length() == 0 ? "" : "; ").append(TvApps.command(a.pkg, false));
            if (list.isEmpty()) return;
            runAdb(page, cmd.toString(), r -> {
                android.content.pm.PackageManager pm = ctx.getPackageManager();
                boolean all = true;
                for (TvApps.App a : list) {
                    a.on = TvApps.isOn(pm, a.pkg);
                    all &= !a.on;
                }
                if (all) Toast.makeText(host, R.string.apps_all_off, Toast.LENGTH_SHORT).show();
                else MainActivity.showError(host, R.string.adb_failed);
                page.refresh();
                showTurnOffAgain();   // gone from Apps once there's nothing left
                if (all) back();
            });
        });
        for (TvApps.App a : list) {
            Row row = page.add(a.label, () -> ctx.getString(a.on ? R.string.on : R.string.off), null);
            row.aboutText = a.pkg;
        }
        return page;
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
            if (still.isEmpty()) Toast.makeText(host, R.string.apps_all_on, Toast.LENGTH_SHORT).show();
            else MainActivity.showError(host, R.string.adb_failed);
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
    private void runAdb(Page page, String command, java.util.function.Consumer<Adb.Result> then) {
        if (adbBusy || host == null) return;
        // What's off can be seen before trying: Developer options not unlocked yet (its About screen, to unlock
        // them), or USB/ADB debugging off (Developer options). Both are Global settings any app may read.
        int devOptions = adbSetting(android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED);
        int debugging = adbSetting(android.provider.Settings.Global.ADB_ENABLED);
        // Given WRITE_SECURE_SETTINGS before (TvControl), Vibe Launcher turns debugging back on itself, with no
        // trip to Developer options, and waits for its port before connecting.
        boolean waitForPort = false;
        if (debugging != 1 && TvControl.setDebugging(ctx, true)) {
            debugging = 1;
            waitForPort = true;
        }
        if (debugging != 1 && devOptions != 1) {
            adbStatus = Adb.OFF;
            explainThenOpen(R.string.adb_dev_options, this::openAbout);
            page.refresh();
            return;
        }
        android.util.Log.i("VibeAdb", "runAdb: devOptions=" + devOptions + " adb_enabled=" + debugging
                + " wireless=" + Adb.wirelessSetting(ctx) + " paired=" + AdbTlsKey.paired(ctx));
        if (debugging == 0 && !Adb.wirelessOn(ctx)) {
            adbStatus = Adb.OFF;
            openDeveloperOptions();
            page.refresh();
            return;
        }
        boolean wait = waitForPort;
        // Not let in yet: the TV is about to ask whether to allow Vibe Launcher. Said in a window first (a message
        // at the bottom went before it could be read), and Continue connects, which brings up the TV's question.
        boolean asks = adbStatus != Adb.OK && adbStatus != Adb.OFF && adbStatus != Adb.NOT_PAIRED;
        Runnable go = () -> {
        if (adbBusy || host == null) return;
        adbBusy = true;
        if (command == null) {   // a connect (the status row): Connecting…, shown at once
            adbConnecting = true;
            refreshAdbPages();
        }
        Context app = ctx.getApplicationContext();
        // A connect gives up 15 seconds after OK: not answered on the TV by then, it's Not connected again (red),
        // never left on Connecting.
        long deadline = System.currentTimeMillis() + 15000;
        adb().execute(() -> {
            if (wait) {   // adbd takes a moment to open its port once debugging is on: up to ten seconds
                for (int i = 0; i < 20 && System.currentTimeMillis() < deadline - 5000
                        && Adb.check(false).status == Adb.OFF; i++) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            Adb.Result first = command != null ? Adb.shell(command, true) : Adb.check(true, deadline);
            // Port 5555 shut, paired for Wireless debugging and that's off: on again (with the permission), and again.
            if (first.status == Adb.OFF && TvControl.bringUpWireless(app)) {
                first = command != null ? Adb.shell(command, true) : Adb.check(true, deadline);
            }
            Adb.Result r = first;
            // In, and not given WRITE_SECURE_SETTINGS yet: given now, once (what it's used for: TvControl).
            if (r.status == Adb.OK && !TvControl.canWrite(app)) Adb.shell(TvControl.grantCommand(app), false);
            handler.post(() -> {
                adbBusy = false;
                adbConnecting = false;
                adbStatus = r.status;
                if (r.status == Adb.OK) usedAdb = true;
                if (host == null) return;
                if (r.status == Adb.OK) {
                    then.accept(r);
                } else if (r.status == Adb.OFF && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    offerWireless();
                } else if (r.status == Adb.OFF) {
                    // Debugging on, but nothing on port 5555: this TV keeps it to USB; a switch of its own may open
                    // it to the network.
                    explainThenOpen(R.string.adb_network_closed, this::openDeveloperOptionsNow);
                } else if (r.status == Adb.NOT_PAIRED) {
                    offerWireless();   // Wireless debugging turned our key down: forgotten (Revoke), pair again
                } else {
                    MainActivity.showError(host, r.status == Adb.NO_KEY ? R.string.adb_no_key : R.string.adb_failed);
                }
                page.refresh();
            });
        });
        };
        if (asks) explainThenDo(R.string.adb_connection, R.string.adb_allow_prompt, go);
        else go.run();
    }

    /** A connect from the status row running: Connecting…, yellow, until there's an answer (15 seconds at most). */
    private boolean adbConnecting;

    /**
     * Android 11 and up, debugging on but port 5555 shut: Wireless debugging. Not on Wi-Fi, it can't be; paired,
     * it's off (and Vibe Launcher wasn't given the permission to turn it on): Developer options; not paired: the
     * pairing page, after a window saying what's to come.
     */
    private void offerWireless() {
        if (!TvControl.onWifi(ctx)) {
            explainThenOpen(R.string.adb_wireless_wifi, this::openDeveloperOptionsNow);
        } else if (AdbTlsKey.paired(ctx)) {
            explainThenOpen(R.string.adb_wireless_on, this::openDeveloperOptionsNow);
        } else if (link.pairUrl() != null && !pages.isEmpty()) {
            Page here = pages.get(pages.size() - 1);
            Row from = here.rows.isEmpty() ? null : here.rows.get(0);
            for (Row row : here.rows) if (row.view.hasFocus()) from = row;
            if (from != null) open(pairWireless(), from);
        } else {
            explainThenOpen(R.string.adb_network_closed, this::openDeveloperOptionsNow);
        }
    }

    /**
     * Apps > Wireless debugging: a QR code for the phone's pairing page (PhoneLink), and OK for Developer options,
     * where the TV's pairing window shows the code to type there.
     */
    private Page pairWireless() {
        keepLinkUntil = System.currentTimeMillis() + 5 * 60 * 1000;
        Page page = phone(R.string.pair_with_code, link.pairUrl(), R.string.pair_scan);
        page.add(R.string.open_dev_options, null, this::openDevOrAbout);
        return page;
    }

    /**
     * The code from the phone (PhoneLink's thread): the TV's pairing window found on the network (or the port typed),
     * paired, then connected through Wireless debugging, given WRITE_SECURE_SETTINGS (to turn Wireless debugging on
     * by itself from now on), and Settings told. What to tell the phone, in English like the rest of its pages.
     */
    private String pairFromPhone(String code, String typedPort) {
        try {
            return pairWith(code, typedPort);
        } finally {
            handler.post(() -> {   // answered, whatever the answer: the page has done its job unless Settings is open
                keepLinkUntil = 0;
                handler.removeCallbacks(stopKeptLink);
                if (host == null) link.stop();
            });
        }
    }

    private String pairWith(String code, String typedPort) {
        Context app = ctx.getApplicationContext();
        android.util.Log.i("VibeAdb", "pair: code from phone, typed port=" + typedPort + " wifi=" + TvControl.onWifi(app));
        if (!TvControl.onWifi(app)) return "The TV isn't on Wi-Fi. Wireless debugging only works on Wi-Fi.";
        int port = -1;
        if (typedPort != null) {
            port = Integer.parseInt(typedPort.substring(typedPort.lastIndexOf(':') + 1));
        } else {
            port = AdbMdns.find(app, AdbMdns.PAIRING, 8000);
        }
        if (port <= 0 || port > 65535) {
            return "Vibe Launcher couldn't find the pairing window on the TV. Keep it open (Pair device with pairing "
                    + "code), then try again, or type the port as well.";
        }
        int result = AdbPairing.pair(app, "127.0.0.1", port, code);
        android.util.Log.i("VibeAdb", "pair: port=" + port + " result=" + result);
        switch (result) {
            case AdbPairing.OK:
                handler.post(this::afterPairing);
                return "Paired. On the TV, press Back until Vibe Launcher's settings show again.";
            case AdbPairing.WRONG_CODE:
                return "The TV turned the code down. Check it, or choose Pair device with pairing code again for a "
                        + "new one.";
            case AdbPairing.UNREACHABLE:
                return "The TV's pairing window has closed. Choose Pair device with pairing code again, then send the "
                        + "new code.";
            case AdbPairing.NO_KEY:
                return "This TV can't keep the key Vibe Launcher needs.";
            default:
                return "Pairing didn't work. Choose Pair device with pairing code again and send the new code.";
        }
    }

    /** Paired: connected through Wireless debugging now, as OK on Network debugging would, without the windows. */
    private void afterPairing() {
        if (host != null) Toast.makeText(host, R.string.paired_toast, Toast.LENGTH_LONG).show();
        for (Page page : pages) page.refresh();
        Context app = ctx.getApplicationContext();
        adb().execute(() -> {
            Adb.Result r = Adb.check(false);
            if (r.status == Adb.OK && !TvControl.canWrite(app)) Adb.shell(TvControl.grantCommand(app), false);
            handler.post(() -> {
                adbStatus = r.status;
                if (r.status == Adb.OK) usedAdb = true;
                for (Page page : pages) page.refresh();
            });
        });
    }

    /** A Global setting's number, or -1 when the TV doesn't have it. */
    private int adbSetting(String name) {
        return android.provider.Settings.Global.getInt(ctx.getContentResolver(), name, -1);
    }

    /** Developer options, once the window has said what to turn on there. */
    private void openDeveloperOptions() {
        explainThenOpen(R.string.adb_turn_on, this::openDeveloperOptionsNow);
    }

    private void openDeveloperOptionsNow() {
        Screens.open(host, new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
    }

    /**
     * What to do in Android's settings, in a window like the update's (a message at the bottom of the screen went
     * before it could be read), and Continue to open them; Cancel stays here.
     */
    private void explainThenOpen(int message, Runnable open) {
        explainThenDo(R.string.adb_connection, message, open);
    }

    /** The same window with a title of its own: Continue runs then. */
    private void explainThenDo(int title, int message, Runnable open) {
        explainThenDo(title, message, R.string.continue_button, open);
    }

    /** As explainThenDo, its button saying what it does (Delete). */
    private void explainThenDo(int title, int message, int button, Runnable open) {
        if (host == null) return;
        Activity activity = host;
        View panel = LayoutInflater.from(ctx).inflate(R.layout.dialog_update,
                activity.findViewById(android.R.id.content), false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(title);
        ((TextView) panel.findViewById(R.id.notes)).setText(message);
        TextView go = panel.findViewById(R.id.install), cancel = panel.findViewById(R.id.cancelUpdate);
        go.setText(button);
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
        Screens.open(host, open);
    }

    private CharSequence adbStatusText() {
        if (adbConnecting) {   // OK pressed on it: yellow until there's an answer
            return dot(0xFFFFD60A, ctx.getString(R.string.adb_connecting));
        }
        if (adbStatus == -1) return ctx.getString(R.string.adb_checking);
        if (adbStatus == Adb.OK) {
            return dot(0xFF34C759, ctx.getString(Adb.usedWireless() ? R.string.adb_connected_wireless
                    : R.string.adb_connected_port));
        }
        return dot(0xFFFF453A, ctx.getString(R.string.adb_not_connected));
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
        // Only now, with focus on the new page: made unfocusable while one of its rows had focus, the old list
        // handed focus down row by row, scrolling it away from the row opened (and slowly, on a long list).
        current.focusable(false);
        showAbout(current, from);   // under it, what the row opened is about
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
        pollAdb();   // back on Apps or ADB connection (from pairing, say): looked at again; stops itself elsewhere
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
        settings.save(keys[0], keys[1], keys[2], keys[3], keys[4]);
        InputMethodManager imm = host != null ? (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE) : null;
        if (imm != null) imm.hideSoftInputFromWindow(row.field.getWindowToken(), 0);
        row.field.setVisibility(View.GONE);
        row.value.setVisibility(View.VISIBLE);
        row.view.setActivated(false);
        row.refresh();
        row.view.requestFocus();
    }

    private void startPhoneLink() {
        if (link != null && link.running() && System.currentTimeMillis() < keepLinkUntil) {
            handler.removeCallbacks(stopKeptLink);   // still up for a pairing: the same page, the phone's link works
            return;
        }
        if (link != null) link.stop();
        link = new PhoneLink(received -> {
            if (host == null) return;
            String[] names = {"tmdbKey", "youtubeKey", "plexToken", "pexelsKey", "pixabayKey"};
            for (int i = 0; i < names.length; i++) {
                String k = received.get(names[i]);
                if (k != null) keys[i] = k.trim();
            }
            settings.save(keys[0], keys[1], keys[2], keys[3], keys[4]);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) link.pairing = this::pairFromPhone;
        Wallpapers walls = Wallpapers.get(app);
        link.wallpapers = new PhoneLink.WallpaperUploads() {
            @Override
            public java.io.File incoming() {
                return walls.incoming();
            }

            @Override
            public boolean roomFor(long bytes) {
                return walls.roomFor(bytes);
            }

            @Override
            public boolean received(java.io.File file, boolean video) {
                boolean added = walls.add(file, video, Wallpapers.PHONE, "", screenW, screenH) != null;
                if (added) handler.post(SettingsScreen.this::showWallpapers);
                return added;
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

    void showSmartTube() {
        if (smartTubeRow == null) return;
        boolean installed = SmartTube.launchIntent(ctx) != null;
        smartTubeRow.label.setText(installed ? R.string.smarttube_open : R.string.smarttube_install);
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
            Updater.install(activity, release, problem -> MainActivity.showError(activity, problem));
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
