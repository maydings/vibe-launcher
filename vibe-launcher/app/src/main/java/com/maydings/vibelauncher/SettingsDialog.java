package com.maydings.vibelauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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
 * Vibe Launcher settings, full screen: the logo on the left and General, Appearance and
 * API keys on the right. Opening one slides its list over the logo and its page in on the right; Back (or
 * Left) slides them back. Changes apply as they're made; tiles per row and the language
 * rebuild Home, so those wait until Settings closes.
 * General: language, reloading the backdrops, Home at power-on, Restart… (store build, Home button service
 * on) and updates (Updater). Appearance: tiles per row, inputs, the top bar and the slideshow's clock. API
 * keys: the TMDB, YouTube and Plex keys, edited in place and explained under the list, SmartTube to install
 * or sign in to (the key-free way to YouTube backdrops), and a QR code for pasting the keys from a phone
 * (PhoneLink).
 */
final class SettingsDialog {

    private static final long SLIDE_MS = 320;

    static void show(Activity activity, ViewGroup root) {
        new SettingsDialog(activity, root).open();
    }

    /** One row: its name, its value, and what OK does. */
    private static final class Row {
        final View view;
        final TextView label, value;
        EditText field;   // key rows only (see key())
        Supplier<CharSequence> valueOf;
        Runnable action;
        int about;   // the text under the list while it's focused; 0 for none

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
        int footnote;   // under the list when the focused row has nothing to say
        Row opened;     // the row whose page is open, while this list is on the left

        Page(CharSequence title) {
            this.title = title;
            view = new LinearLayout(activity);
            view.setOrientation(LinearLayout.VERTICAL);
            ScrollView scroll = new ScrollView(activity);
            scroll.setVerticalScrollBarEnabled(false);
            // A ScrollView takes focus itself when Up or Down finds no row: on the left list it would hold
            // focus out of sight, so only the rows can have it.
            scroll.setFocusable(false);
            scroll.setClipToPadding(false);
            // Room for the focused row to grow (1.035 of the column's width) without its corners being cut.
            int pad = (int) (6 * density), side = (int) (columnW * 0.02f) + 2;
            scroll.setPadding(side, pad, side, pad);
            list = new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(list);
            // The focused row grows past the list's edges: drawn whole, not cut square at them.
            list.setClipChildren(false);
            scroll.setClipChildren(false);
            view.setClipChildren(false);
            view.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            about = new TextView(activity);
            about.setTextColor(0x99FFFFFF);
            about.setTextSize(14);
            about.setLineSpacing(0, 1.15f);
            about.setTypeface(UiFont.get(activity, R.string.font_regular));
            about.setPadding(side + pad, (int) (10 * density), side + pad, 0);
            // The rest of the column, down to the bottom; a long text (a long translation) gets smaller to fit.
            view.addView(about, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            about.setAutoSizeTextTypeUniformWithConfiguration(11, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        }

        Row add(int label, Supplier<CharSequence> valueOf, Runnable action) {
            return add(activity.getString(label), valueOf, action);
        }

        Row add(CharSequence label, Supplier<CharSequence> valueOf, Runnable action) {
            Row row = adopt(LayoutInflater.from(activity).inflate(R.layout.item_setting, list, false), valueOf, action);
            row.label.setText(label);
            return row;
        }

        /** Makes a row of an item_setting view: a new one, or one prepared ahead (prepareLanguages). */
        Row adopt(View view, Supplier<CharSequence> valueOf, Runnable action) {
            Row row = new Row(view);
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
            return add(label, () -> activity.getString(get.get() ? R.string.on : R.string.off),
                    () -> set.accept(!get.get()));
        }

        void refresh() {
            for (Row row : rows) row.refresh();
        }

        View first() {
            for (Row row : rows) if (row.view.getVisibility() == View.VISIBLE) return row.view;
            return list.getChildCount() > 0 ? list.getChildAt(0) : view;
        }

        /** On the left, the list is only looked at: its rows can't take focus. */
        void focusable(boolean on) {
            for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setFocusable(on);
        }
    }

    private final Activity activity;
    /** Home's screen, hidden while Settings covers it: it would go on drawing (the frosted bar's blur) under it. */
    private final View home;
    private final float density;
    private final VibeSettings settings;
    private final Dialog dialog;
    private final FrameLayout stage;
    private final View logo;
    private final TextView title;
    private final List<Page> pages = new ArrayList<>();
    /** Where the lists sit: the left column (over the logo) and the right one, and their width. */
    private final int leftX, rightX, columnW, screenW;

    private final int startColumns;
    private int columns;
    private final String startLanguage;
    private String language;
    private final String[] keys;   // TMDB, YouTube, Plex, as saved
    private Row editing;
    private int editingKey;

    private PhoneLink link;
    private String phoneUrl;
    private BroadcastReceiver packages;
    private Row smartTubeRow;
    private Page languagePage;
    private long slidAt;   // when the last slide started (uptime)

    private SettingsDialog(Activity activity, View home) {
        this.activity = activity;
        this.home = home;
        density = activity.getResources().getDisplayMetrics().density;
        settings = new VibeSettings(activity);
        startColumns = columns = MainActivity.COLUMNS;
        startLanguage = language = settings.language();
        keys = new String[]{settings.tmdbKey(), settings.youtubeKey(), settings.plexToken()};

        View screen = LayoutInflater.from(activity).inflate(R.layout.dialog_settings, new FrameLayout(activity), false);
        stage = screen.findViewById(R.id.stage);
        logo = screen.findViewById(R.id.logo);
        title = screen.findViewById(R.id.title);
        int w = screenW = activity.getResources().getDisplayMetrics().widthPixels;
        // The list from 55% of the width to 95%, the picture centred in the left half.
        rightX = (int) (w * 0.55f);
        columnW = (int) (w * 0.40f);
        leftX = (int) (w * 0.06f);
        ((FrameLayout.LayoutParams) logo.getLayoutParams()).leftMargin = leftX + (columnW - logo.getLayoutParams().width) / 2;
        View projectLink = screen.findViewById(R.id.projectLink);
        FrameLayout.LayoutParams linkLp = (FrameLayout.LayoutParams) projectLink.getLayoutParams();
        linkLp.width = columnW;
        linkLp.leftMargin = leftX;

        dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        dialog.setContentView(screen);
        Window window = dialog.getWindow();
        if (window != null) {
            // Opaque, so the TV draws Settings alone instead of blending it over Home on every frame.
            window.setBackgroundDrawableResource(R.drawable.settings_bg);
            window.setFormat(android.graphics.PixelFormat.OPAQUE);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            // The keyboard only comes up once a key field is picked.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            window.setWindowAnimations(android.R.style.Animation_Toast);   // a fade
        }
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) back();
            return true;
        });
    }

    private void open() {
        Page root = new Page(activity.getString(R.string.settings));
        root.link(R.string.tab_general, null, this::general);
        root.link(R.string.tab_appearance, null, this::appearance);
        root.link(R.string.tab_keys, null, this::apiKeys);
        place(root, 0);
        pages.add(root);
        title.setText(root.title);
        startPhoneLink();
        watchSmartTube();
        dialog.setOnDismissListener(d -> closed());
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).closeSettings = () -> {
                home.setVisibility(View.VISIBLE);
                dialog.dismiss();
            };
        }
        dialog.show();
        root.first().requestFocus();
        // Once Settings has faded in over it.
        stage.postDelayed(() -> {
            if (dialog.isShowing()) home.setVisibility(View.INVISIBLE);
        }, 400);
        stage.postDelayed(this::prebuildLanguages, 450);
    }

    // ---- the pages ---------------------------------------------------------------------------------

    private Page general() {
        Page page = new Page(activity.getString(R.string.tab_general));
        page.link(R.string.language, () -> language.isEmpty()
                ? activity.getString(R.string.language_system) : Lang.name(language), this::languages);
        page.add(R.string.reload_backdrops, null, () -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).reloadBackdrops();
            Toast.makeText(activity, R.string.backdrops_reloading, Toast.LENGTH_SHORT).show();
        });
        page.toggle(R.string.power_on_home, settings::powerOnHome, settings::setPowerOnHome);
        // The TV's power menu, which asks before it restarts: only the Home button service can open it (an
        // app may not restart the TV), so only while that's on.
        if (HomeButtonLink.bound()) {
            page.add(R.string.restart, null, () -> {
                Runnable powerMenu = HomeButtonLink.powerMenu;
                if (powerMenu != null) powerMenu.run();
            });
        }
        Row version = page.add(R.string.updates, null, null);
        Row check = page.add(R.string.check_updates, null, null);
        showUpdates(version, check);
        return page;
    }

    /**
     * The language list (51 rows), built and laid out off the screen once Settings is showing, so Language
     * slides in at once. Never while a page slides: built then, it held the slide up.
     */
    private void prebuildLanguages() {
        if (languagePage != null || !dialog.isShowing()) return;
        long sinceSlide = android.os.SystemClock.uptimeMillis() - slidAt;
        if (sinceSlide < SLIDE_MS + 50) {
            stage.postDelayed(this::prebuildLanguages, SLIDE_MS + 50 - sinceSlide);
            return;
        }
        languagePage = buildLanguages();
        place(languagePage, screenW - rightX);
        languagePage.focusable(false);
    }

    /** The language list, built ahead (prebuildLanguages) or now, opening on the one in use. */
    private Page languages() {
        if (languagePage == null) languagePage = buildLanguages();
        Page page = languagePage;
        page.refresh();
        for (Row row : page.rows) {
            if (language.equals(row.view.getTag())) page.opened = row;   // open() focuses it
        }
        return page;
    }

    private Page buildLanguages() {
        Page page = new Page(activity.getString(R.string.language));
        // The rows prepared when Home started, else made now.
        ViewGroup prepared = prepared();
        if (prepared == null || prepared.getChildCount() == 0) prepared = makeLanguageRows(activity, 0);
        while (prepared.getChildCount() > 0) {
            View view = prepared.getChildAt(0);
            prepared.removeViewAt(0);
            String tag = (String) view.getTag();
            page.adopt(view, () -> tag.equals(language) ? "✓" : "", () -> {
                language = tag;
                back();
            });
        }
        return page;
    }

    // ---- the language rows, prepared ahead -----------------------------------------------------------

    /**
     * Makes the 51 language rows and measures them at the list's width, without drawing them, so Settings
     * only has to put them on its Language page. Home keeps them (MainActivity.languageRows), so a new Home
     * (a new language or grid) makes its own.
     */
    static ViewGroup prepareLanguages(Activity activity) {
        int w = activity.getResources().getDisplayMetrics().widthPixels;
        int columnW = (int) (w * 0.40f);
        return makeLanguageRows(activity, columnW - 2 * ((int) (columnW * 0.02f) + 2));
    }

    /** The rows Home prepared, while they're not on a Language page; null if it hasn't yet. */
    private ViewGroup prepared() {
        return activity instanceof MainActivity ? ((MainActivity) activity).languageRows : null;
    }

    /** Every language's row, its tag on it; measured at listW when it's known (> 0). */
    private static ViewGroup makeLanguageRows(Activity activity, int listW) {
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (int i = -1; i < Lang.TAGS.length; i++) {
            View row = inflater.inflate(R.layout.item_setting, rows, false);
            ((TextView) row.findViewById(R.id.label)).setText(i < 0 ? activity.getString(R.string.language_system) : Lang.NAMES[i]);
            row.setTag(i < 0 ? "" : Lang.TAGS[i]);
            rows.addView(row);
        }
        if (listW > 0) {
            rows.measure(View.MeasureSpec.makeMeasureSpec(listW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        }
        return rows;
    }

    /** Settings closing: its language rows go back, as they were, for the next time. */
    private void returnLanguageRows() {
        // Not when a set was prepared meanwhile (these were made on the spot): one set is kept.
        ViewGroup prepared = prepared();
        if (languagePage == null || prepared == null || prepared.getChildCount() > 0) return;
        for (Row row : languagePage.rows) {
            View view = row.view;
            ((ViewGroup) view.getParent()).removeView(view);
            view.animate().cancel();
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setActivated(false);
            view.setFocusable(true);
            view.setOnClickListener(null);
            view.setOnFocusChangeListener(null);
            view.setOnKeyListener(null);
            prepared.addView(view);
        }
        languagePage = null;
    }

    private Page appearance() {
        Page page = new Page(activity.getString(R.string.tab_appearance));
        page.link(R.string.tiles_per_row, () -> String.valueOf(columns), this::tiles);
        page.toggle(R.string.hide_inactive_inputs, settings::hideInactiveInputs, settings::setHideInactiveInputs);
        page.toggle(R.string.auto_hide_bar, settings::autoHideBar, on -> {
            settings.setAutoHideBar(on);
            if (activity instanceof MainActivity) ((MainActivity) activity).updateTopBar();
        });
        page.toggle(R.string.slideshow_clock, settings::slideshowClock, settings::setSlideshowClock);
        return page;
    }

    private Page tiles() {
        Page page = new Page(activity.getString(R.string.tiles_per_row));
        for (int n = 4; n <= 6; n++) {
            int value = n;
            Row row = page.add(String.valueOf(n), () -> value == columns ? "✓" : "", () -> {
                columns = value;
                back();
            });
            if (n == columns) page.opened = row;
        }
        return page;
    }

    private Page apiKeys() {
        Page page = new Page(activity.getString(R.string.tab_keys));
        page.footnote = R.string.tmdb_notice;   // TMDB's terms ask apps using its API to say so
        key(page, R.string.tmdb_key, 0, R.string.tmdb_info);
        key(page, R.string.youtube_key, 1, R.string.youtube_info);
        // SmartTube signed in beats a key (the user's own feed). The store build can't install apps: there
        // the row only opens SmartTube, when it's on the TV.
        smartTubeRow = page.add("", null, () -> {
            Intent open = SmartTube.launchIntent(activity);
            if (open != null) {
                activity.startActivity(open);
                return;
            }
            if (BuildConfig.STORE) return;
            boolean started = SmartTube.install(activity, res -> Toast.makeText(activity,
                    res == R.string.update_failed ? R.string.smarttube_failed : res, Toast.LENGTH_LONG).show());
            if (started) Toast.makeText(activity, R.string.smarttube_downloading, Toast.LENGTH_LONG).show();
        });
        smartTubeRow.about = R.string.youtube_info;
        showSmartTube();
        key(page, R.string.plex_token, 2, R.string.plex_info);
        if (phoneUrl != null) page.link(R.string.phone_paste, null, this::phone);
        return page;
    }

    /** A key row: the saved key's last four characters; OK edits it in place. */
    private void key(Page page, int label, int index, int about) {
        Row row = page.add(label, () -> keys[index].isEmpty() ? activity.getString(R.string.not_set) : mask(keys[index]), null);
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

    /** Typing a 32-character key with a remote is painful: a page to paste it from a phone instead. */
    private Page phone() {
        Page page = new Page(activity.getString(R.string.phone_paste));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setFocusable(true);   // somewhere for focus to be: Back and Left go back
        box.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false;
            back();
            return true;
        });
        ImageView qr = new ImageView(activity);
        int size = (int) (180 * density);
        qr.setImageBitmap(PhoneLink.qr(phoneUrl, size));
        qr.setBackgroundColor(Color.WHITE);
        int pad = (int) (10 * density);
        qr.setPadding(pad, pad, pad, pad);
        box.addView(qr, new LinearLayout.LayoutParams(size + 2 * pad, size + 2 * pad));
        TextView scan = new TextView(activity);
        scan.setText(R.string.scan_to_paste);
        scan.setTextColor(0xCCFFFFFF);
        scan.setTextSize(17);
        scan.setTypeface(UiFont.get(activity, R.string.font_regular));
        scan.setPadding(0, (int) (18 * density), 0, 0);
        box.addView(scan);
        TextView url = new TextView(activity);
        url.setText(phoneUrl.replace("http://", ""));
        url.setTextColor(0x80FFFFFF);
        url.setTextSize(13);
        url.setTypeface(UiFont.get(activity, R.string.font_regular));
        url.setPadding(0, (int) (6 * density), 0, 0);
        box.addView(url);
        page.list.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return page;
    }

    // ---- sliding between pages ---------------------------------------------------------------------

    /** Puts a page's list in the right column (offset 0) or the left one (offset leftX - rightX). */
    private void place(Page page, float translationX) {
        page.view.setTranslationX(translationX);
        if (page.view.getParent() != null) return;   // the language list, kept laid out
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
        // Started once the new page is laid out: a long one (51 languages) takes longer than the slide to lay
        // out the first time, and the slide was over before its first frame.
        next.view.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
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
    private void back() {
        if (editing != null) {
            endEdit();
            return;
        }
        if (pages.size() == 1) {
            home.setVisibility(View.VISIBLE);   // back under Settings as it fades out
            dialog.dismiss();
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
        slidAt = android.os.SystemClock.uptimeMillis();
        v.animate().cancel();
        // withLayer: drawn as one texture while it moves, not redrawn row by row on every frame.
        v.animate().translationX(x).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator(1.6f))
                .withLayer().start();
    }

    private void showAbout(Page page, Row row) {
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
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) row.field.post(() -> imm.showSoftInput(row.field, InputMethodManager.SHOW_IMPLICIT));
    }

    private void endEdit() {
        Row row = editing;
        if (row == null) return;
        editing = null;
        keys[editingKey] = value(row.field, keys[editingKey]);
        settings.save(keys[0], keys[1], keys[2]);
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(row.field.getWindowToken(), 0);
        row.field.setVisibility(View.GONE);
        row.value.setVisibility(View.VISIBLE);
        row.view.setActivated(false);
        row.refresh();
        row.view.requestFocus();
    }

    private void startPhoneLink() {
        link = new PhoneLink(received -> {
            if (!dialog.isShowing()) return;
            String[] names = {"tmdbKey", "youtubeKey", "plexToken"};
            for (int i = 0; i < names.length; i++) {
                String k = received.get(names[i]);
                if (k != null) keys[i] = k.trim();
            }
            settings.save(keys[0], keys[1], keys[2]);
            for (Page page : pages) page.refresh();
            Toast.makeText(activity, R.string.saved_from_phone, Toast.LENGTH_SHORT).show();
        }, settings);
        phoneUrl = link.start();
    }

    // ---- SmartTube ---------------------------------------------------------------------------------

    /**
     * Install or Open, checked again when an app is installed or removed (the installer's screen closes
     * before the install is done) and whenever Settings gets the screen back.
     */
    private void watchSmartTube() {
        dialog.getWindow().getDecorView().getViewTreeObserver()
                .addOnWindowFocusChangeListener(hasFocus -> { if (hasFocus) showSmartTube(); });
        packages = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                showSmartTube();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(packages, filter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(packages, filter);
    }

    private void showSmartTube() {
        if (smartTubeRow == null) return;
        boolean installed = SmartTube.launchIntent(activity) != null;
        smartTubeRow.label.setText(installed ? R.string.smarttube_open : R.string.smarttube_install);
        if (BuildConfig.STORE) smartTubeRow.view.setVisibility(installed ? View.VISIBLE : View.GONE);
    }

    // ---- closing -----------------------------------------------------------------------------------

    private void closed() {
        home.setVisibility(View.VISIBLE);
        if (activity instanceof MainActivity) ((MainActivity) activity).closeSettings = null;
        if (editing != null) endEdit();
        returnLanguageRows();
        link.stop();
        activity.unregisterReceiver(packages);
        boolean rebuild = columns != startColumns || !language.equals(startLanguage);
        if (columns != startColumns) settings.setColumns(columns);
        if (!language.equals(startLanguage)) settings.setLanguage(language);
        // An update found here shows on the Settings pill straight away, not at the next return to Home.
        if (activity instanceof MainActivity) ((MainActivity) activity).showUpdateReady();
        if (rebuild) activity.recreate();   // Home again with the new grid or language
    }

    // ---- updates -----------------------------------------------------------------------------------

    /**
     * The installed version and a row that checks for a newer one, then offers to install it. The store
     * build leaves it to Play: the row is there only while Play has a newer version. A development build
     * only says how it updates.
     */
    private void showUpdates(Row status, Row button) {
        String version = Updater.installedVersion(activity);
        if (BuildConfig.STORE) {
            status.value.setText(activity.getString(R.string.version_current, version));
            button.view.setVisibility(StoreUpdates.available() ? View.VISIBLE : View.GONE);
            button.label.setText(R.string.store_update);
            button.action = () -> StoreUpdates.open(activity);
            return;
        }
        if (Updater.isDevelopmentBuild(activity)) {
            status.value.setText(activity.getString(R.string.version_current, version));
            status.about = R.string.update_development;
            button.view.setVisibility(View.GONE);
            return;
        }
        // What the last check found: the gear menu, which opens Settings, has just asked GitHub again.
        Updater.Release found = Updater.available(activity);
        if (found != null) {
            offerUpdate(status, button, found);
            return;
        }
        status.value.setText(activity.getString(R.string.version_current, version));
        boolean[] checking = {false};
        button.action = () -> {
            // Not disabled while it checks: a disabled row loses focus, which went to the top of the list.
            if (checking[0]) return;
            checking[0] = true;
            button.label.setText(R.string.checking_updates);
            Updater.check(activity, (newer, failed) -> {
                checking[0] = false;
                if (newer != null) {
                    offerUpdate(status, button, newer);
                } else {
                    status.value.setText(activity.getString(failed ? R.string.update_check_failed
                            : R.string.version_up_to_date, version));
                    button.label.setText(R.string.check_updates);
                }
            });
        };
    }

    private void offerUpdate(Row status, Row button, Updater.Release release) {
        status.value.setText(activity.getString(R.string.update_available, release.version));
        button.label.setText(activity.getString(R.string.update_to, release.version));
        button.action = () -> startUpdate(activity, release);
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
