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
 * on), backup and restore (Backup, on the TV or through the phone) and updates (Updater). Appearance: tiles per row, inputs, the top bar and the slideshow's clock and backdrops. API
 * keys: the TMDB, YouTube and Plex keys, edited in place and explained under the list, SmartTube to install
 * or sign in to (the key-free way to YouTube backdrops), and a QR code for pasting the keys from a phone
 * (PhoneLink). Apps: turning the TV's apps off and on through its own network debugging
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
        if (prepared != null && (prepared.host != null || (prepared.builtFor.equals(language)
                && prepared.builtWith.equals(fontKey(context))))) return;
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
    static final class Row {
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
    final class Page {
        final CharSequence title;
        final LinearLayout view;
        final LinearLayout list;
        final TextView about;
        final List<Row> rows = new ArrayList<>();
        /** What can take focus here: the rows, and the wheel, the QR code and the instructions' sections. */
        final List<View> stops = new ArrayList<>();
        int footnote;   // under the list when the focused row has nothing to say
        Row opened;     // the row whose page is open, while this list is on the left
        /** Keeps what the page changed and holds back for a moment (the opacity's), however it's left; or null. */
        Runnable save;
        /** Its window trimmed to a whole number of rows when it scrolls: not for a page of other things (the wheel). */
        boolean wholeRows = true;

        Page(CharSequence title) {
            this.title = title;
            view = new LinearLayout(ctx);
            view.setOrientation(LinearLayout.VERTICAL);
            // A long list (a clean-up list's plan, the lists themselves) stops short of the column's bottom: the
            // text under it keeps room for four lines (ABOUT_DP), the list as many rows as fit above them.
            int maxListH = screenH - (int) ((82 + 16) * density) - (int) (ABOUT_DP * density);
            // Room for the focused row to grow (1.035 of the column's width) without its corners being cut, and
            // while the list scrolls, a lane at the end for the scroll hint, so a focused row never reaches it.
            int pad = (int) (6 * density), side = (int) (columnW * 0.02f) + 2, lane = (int) (SCROLL_LANE_DP * density);
            ScrollView scroll = new ScrollView(ctx) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    int size = View.MeasureSpec.getSize(heightSpec);
                    if (View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED || size > maxListH) {
                        heightSpec = View.MeasureSpec.makeMeasureSpec(maxListH, View.MeasureSpec.AT_MOST);
                    }
                    super.onMeasure(widthSpec, heightSpec);
                    boolean scrolls = getChildCount() > 0 && getChildAt(0).getMeasuredHeight()
                            > getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
                    int end = side + (scrolls ? lane : 0);
                    if (getPaddingEnd() != end) {   // the lane only while there's a hint to put in it
                        setPaddingRelative(side, pad, end, pad);
                        super.onMeasure(widthSpec, heightSpec);
                    }
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
                    int to = Math.max(0, Math.min(max, getScrollY() + delta));
                    // Within half a row of an end: all the way. Else the last row's margin and the list's padding
                    // stayed below the edge, and Down on it (nowhere to go) scrolled that little bit more on its own.
                    int near = Math.round(24 * density);
                    if (delta > 0 && max - to < near) to = max;
                    else if (delta < 0 && to < near) to = 0;
                    return to - getScrollY();
                }

                // Only what's in the window is drawn: the column around the list doesn't clip (the focused row grows
                // past its edges), so rows scrolled out of sight would be drawn over the text under the list.
                @Override
                protected void dispatchDraw(android.graphics.Canvas canvas) {
                    canvas.save();
                    canvas.clipRect(getScrollX(), getScrollY(), getScrollX() + getWidth(), getScrollY() + getHeight());
                    super.dispatchDraw(canvas);
                    canvas.restore();
                    drawScrollHint(canvas);
                }

                /**
                 * A slim bar at the right while the list is taller than its window, its thumb where the window is:
                 * there are more rows than show. Always there (a TV has no touch to bring a fading one back).
                 */
                private void drawScrollHint(android.graphics.Canvas canvas) {
                    if (getChildCount() == 0) return;
                    int content = getChildAt(0).getHeight() + getPaddingTop() + getPaddingBottom();
                    int window = getHeight();
                    if (content <= window + 1) return;
                    // In its own lane at the list's edge (SCROLL_LANE_DP of padding), clear of a focused row as it
                    // grows, and a little short of the window's ends.
                    float w = 3f * density, inset = 16f * density, edge = 2f * density;
                    boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                    float x = getScrollX() + (rtl ? edge : getWidth() - w - edge);
                    float top = getScrollY() + inset, bottom = getScrollY() + window - inset;
                    float span = bottom - top;
                    float thumbH = Math.max(18f * density, span * window / content);
                    float max = content - window;
                    float thumbTop = top + (span - thumbH) * (max <= 0 ? 0f : getScrollY() / max);
                    scrollRect.set(x, top, x + w, bottom);
                    canvas.drawRoundRect(scrollRect, w, w, scrollTrack);
                    scrollRect.set(x, thumbTop, x + w, thumbTop + thumbH);
                    canvas.drawRoundRect(scrollRect, w, w, scrollThumb);
                }

                @Override
                protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                    super.onScrollChanged(l, t, oldl, oldt);
                    invalidate();   // the thumb follows
                }
            };
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setVerticalFadingEdgeEnabled(false);   // a fading edge would scroll a part-row further in
            // A ScrollView takes focus itself when Up or Down finds no row: on the left list it would hold
            // focus out of sight, so only the rows can have it.
            scroll.setFocusable(false);
            scroll.setClipToPadding(false);
            scroll.setPaddingRelative(side, pad, side, pad);   // and the lane while it scrolls (onMeasure)
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
            about.setAutoSizeTextTypeUniformWithConfiguration(8, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
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
    final Context ctx;
    private final String builtFor;   // the language it was made in
    private final String builtWith;   // and the fonts (fontKey): a new choice makes it again

    /** The font choices the screen is drawn in. */
    private static String fontKey(Context context) {
        VibeSettings s = new VibeSettings(context);
        return s.uiFont() + "|" + s.clockFont();
    }
    final float density;
    final VibeSettings settings;
    private final View screen;
    private final FrameLayout stage;
    private final View logo;
    private final TextView title;
    private final Page root;
    private final Page languagePage;
    private final Row generalRow;
    private Row appearanceRow;
    /** The scroll hint's lane at the end of a list, beside the room a focused row grows into. */
    private static final int SCROLL_LANE_DP = 10;
    /** The room kept under a page's list for its text: four lines (a longer text gets smaller), the rest for rows. */
    private static final int ABOUT_DP = 80;
    /** The scroll hint at the right of a long list (Page): its track and its thumb. */
    private final android.graphics.Paint scrollTrack = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
            scrollThumb = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF scrollRect = new android.graphics.RectF();
    {
        scrollTrack.setColor(0x1FFFFFFF);
        scrollThumb.setColor(0x80FFFFFF);
    }
    final List<Page> pages = new ArrayList<>();
    /** Where the lists sit: the left column (over the logo) and the right one, and their width. */
    private final int leftX, rightX, columnW, screenW, screenH;
    /** For making the languages a few at a time (prepare), the kept phone page's end and the opacity's saving. */
    final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int nextLanguage = -1;   // -1 for the TV's own language, then Lang.TAGS
    private boolean laidOut;

    /** The rows' focused look: Settings' own white (Highlight.SETTINGS), whatever Home's highlight is. */
    private final int highlight = Highlight.SETTINGS;

    /** The SettingsActivity it's on, while it is: what opens other screens, dialogs and the keyboard. */
    Activity host;
    private String language;
    private final String[] keys = new String[VibeSettings.KEY_NAMES.length];   // as saved, in that order
    private Row editing;
    private int editingKey;

    PhoneLink link;
    /**
     * Until when the phone page outlives Settings (System clock millis): five minutes from the pairing page opening,
     * or until a code sent from the phone has been answered. Android may close Settings while its own Settings is in
     * front (Home pressed there resets Vibe Launcher's task), which would leave the phone's code nowhere to go.
     * 0 normally.
     */
    long keepLinkUntil;
    final Runnable stopKeptLink = () -> {
        if (host == null && link != null) link.stop();
        keepLinkUntil = 0;
    };
    String phoneUrl;
    private BroadcastReceiver packages;
    private Row smartTubeRow;

    private SettingsScreen(Context app, String language) {
        ctx = new ContextThemeWrapper(Lang.wrap(app), android.R.style.Theme_Material_NoActionBar);
        builtFor = language;
        builtWith = fontKey(app);
        FontInflater.install(LayoutInflater.from(ctx));   // the chosen font for the layouts made here
        this.language = language;
        density = ctx.getResources().getDisplayMetrics().density;
        settings = new VibeSettings(ctx);
        Adb.use(app);
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
        appearanceRow = root.link(R.string.tab_appearance, null, this::appearance);
        root.link(R.string.tab_backdrops, null, this::backdrops);
        root.link(R.string.media_title, null, this::media);
        root.link(R.string.tab_weather, null, this::weather);
        root.link(R.string.tab_keys, null, this::apiKeys);
        appsRootRow = root.link(R.string.tab_apps, null, adbPages::apps);
        place(root, 0);
        languagePage = new Page(ctx.getString(R.string.language));   // its rows: addLanguages
        place(languagePage, screenW - rightX);   // off the screen until Language is picked
    }

    // ---- on and off the screen ---------------------------------------------------------------------

    /** SettingsActivity showing it: back to the first list, with the values as they are now. */
    View attach(Activity activity) {
        host = activity;
        language = settings.language();
        System.arraycopy(settings.keys(), 0, keys, 0, keys.length);
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
        adbPages.onAttach();
        ViewGroup parent = (ViewGroup) screen.getParent();
        if (parent != null) parent.removeView(screen);
        return screen;
    }

    View first() {
        return root.first();
    }

    /** Settings > Apps, and Apps > Default launcher: where the top bar's Make Vibe Launcher your Home opens. */
    Row appsRootRow, defaultLauncherRow;

    /**
     * Opens on Apps > Default launcher, already in place (the top bar's Make Vibe Launcher your Home, with ADB
     * allowed): choosing Vibe Launcher there switches Home through it.
     */
    void showDefaultLauncher() {
        Page apps = adbPages.apps();
        open(apps, appsRootRow);
        Page launcher = adbPages.defaultLauncher();
        open(launcher, defaultLauncherRow);
        // Where the slides end: they then run from there to there, so nothing moves.
        logo.setTranslationX(offLeft(logo));
        root.view.setTranslationX(offLeft(root.view));
        apps.view.setTranslationX(leftX - rightX);
        launcher.view.setTranslationX(0f);
    }

    /** Opens on General, already in place (Settings back in a new language): Language has the focus. */
    /** Whether the font choices changed since the screen was made (a chosen font deleted meanwhile). */
    boolean fontsChanged() {
        return !builtWith.equals(fontKey(ctx));
    }

    /** Settings made again after a font was chosen: back on Appearance, as it was left. */
    void showAppearance() {
        Page page = appearance();
        open(page, appearanceRow);
        logo.setTranslationX(offLeft(logo));
        root.view.setTranslationX(leftX - rightX);
        page.view.setTranslationX(0f);
    }

    void showGeneral() {
        Page page = general();
        open(page, generalRow);
        // Where the slides end: they then run from there to there, so nothing moves.
        logo.setTranslationX(offLeft(logo));
        root.view.setTranslationX(leftX - rightX);
        page.view.setTranslationX(0f);
    }

    /** SettingsActivity going: everything but the first list and the languages goes, ready for next time. */
    /** The open pages' held-back changes kept now: Settings going out of sight (SettingsActivity.onPause). */
    void save() {
        for (Page page : pages) if (page.save != null) page.save.run();
    }

    void detach(Activity activity) {
        if (host != activity) return;
        save();
        if (editing != null) endEdit();
        if (System.currentTimeMillis() < keepLinkUntil) {
            handler.removeCallbacks(stopKeptLink);
            handler.postDelayed(stopKeptLink, keepLinkUntil - System.currentTimeMillis());
        } else {
            link.stop();
        }
        adbPages.onDetach();
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

    /** Home, if it's there: what tiles per row, the language, the backdrops and the top bar change. */
    private static MainActivity home() {
        return MainActivity.current();
    }

    // ---- the pages ---------------------------------------------------------------------------------

    private Page general() {
        Page page = new Page(ctx.getString(R.string.tab_general));
        page.link(R.string.language, () -> language.isEmpty()
                ? ctx.getString(R.string.language_system) : Lang.name(language), this::languages);
        // Turned on where nothing can do it reliably (not Home, Home button service off): the service offered.
        page.toggle(R.string.power_on_home, settings::powerOnHome, on -> {
            settings.setPowerOnHome(on);
            if (on && host != null && HomeButtonLink.powerOnNeedsIt(ctx)) HomeButtonLink.offerForPowerOn(host, adbPages::showHomeButton);
        });
        page.link(R.string.back_button, () -> backName(settings.backAction()), this::backAction);
        Row channelKeys = page.toggle(R.string.ignore_channel_keys, settings::ignoreChannelKeys,
                settings::setIgnoreChannelKeys);
        channelKeys.about = R.string.ignore_channel_keys_short;
        // The TV restarted through its own ADB (an app may not restart it), after asking. runAdb connects first,
        // walking through turning ADB on where it isn't yet; the reboot then goes without waiting for an answer,
        // which would never come (the connection drops) and read as a failure.
        // No way in over ADB, with the Home button service on: the TV's power menu, which only it can open.
        page.add(R.string.restart, null, () -> explainThenDo(R.string.restart, R.string.restart_confirm,
                () -> adbPages.runAdb(page, null, r -> {
                            adbPages.offAfterRestart();
                            adbPages.adb().execute(() -> Adb.shell("reboot", false));
                        },
                        HomeButtonLink.bound() ? () -> {
                            Runnable powerMenu = HomeButtonLink.powerMenu;
                            if (powerMenu != null) powerMenu.run();
                        } : null)));
        page.link(R.string.backup_restore, null, this::backups);
        // The phone link's code made anew: a phone that kept the page's address can't reach this device any more.
        Row forget = page.add(R.string.phone_forget, null, () -> explainThenDo(R.string.phone_forget,
                R.string.phone_forget_confirm, () -> {
                    PhoneLink.newCode(ctx.getApplicationContext());
                    keepLinkUntil = 0;
                    handler.removeCallbacks(stopKeptLink);
                    startPhoneLink();
                    if (host != null) Toast.makeText(host, R.string.phone_forgotten, Toast.LENGTH_SHORT).show();
                }));
        forget.about = R.string.phone_forget_short;
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
        page.link(R.string.font, () -> Fonts.name(ctx, settings.uiFont()), () -> fontPicker(false));
        page.link(R.string.clock_font, () -> Fonts.name(ctx, settings.clockFont()), () -> fontPicker(true));
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
        Row idle = page.link(R.string.idle_backdrops, null, this::idleBackdrops);
        idle.about = R.string.idle_backdrops_short;
        // The TV's own screensaver (Android's), apart from what Home shows when it's left alone: on or off, with
        // WRITE_SECURE_SETTINGS, which the TV's ADB grants (TvControl). The row says how the TV has it now.
        Row saver = page.toggle(R.string.system_screensaver, () -> TvControl.systemScreensaver(ctx), on -> {
            if (!TvControl.setSystemScreensaver(ctx, on)) MainActivity.showError(host, R.string.system_screensaver_short);
        });
        saver.about = R.string.system_screensaver_short;
        page.link(R.string.clock, () -> clockName(settings.clockHours()), this::clockHours);
        page.toggle(R.string.carets, settings::carets, settings::setCarets);
        page.link(R.string.glass_color, this::swatch, this::glassColour);
        page.link(R.string.dock_opacity, () -> Percent.format(settings.dockOpacity()), () -> opacity(false));
        // Both bars: the full one's strip, and the minimal one's tint where apps scroll up under it.
        page.link(R.string.top_bar_opacity, () -> Percent.format(settings.topBarOpacity()), () -> opacity(true));
        // The TV's own animation speed: with WRITE_SECURE_SETTINGS, which the TV's ADB grants (TvControl).
        Row speed = page.link(R.string.anim_speed, () -> animName(TvControl.animationScale(ctx)), this::animSpeed);
        speed.about = R.string.anim_short;
        return page;
    }

    /** The names of VibeSettings.slideshowFrom's values, by value. */
    private static final int[] IDLE_FROM = {R.string.idle_all, R.string.idle_selected, R.string.idle_own};

    /**
     * Appearance > Screensaver backdrops: what the slideshow shows (its own list, the selected app's or folder's
     * backdrops, or every app's), and its own list, ticked from the Library's pictures and videos.
     */
    private Page idleBackdrops() {
        Page page = new Page(ctx.getString(R.string.idle_backdrops));
        page.footnote = R.string.idle_backdrops_short;
        page.link(R.string.wp_use, () -> ctx.getString(IDLE_FROM[settings.slideshowFrom()]), this::idleFrom);
        String name = ctx.getString(R.string.idle_backdrops);
        for (boolean video : new boolean[]{false, true}) {
            Row row = page.add(video ? R.string.backdrop_videos : R.string.backdrop_pictures,
                    () -> String.valueOf(countOf(Wallpapers.get(ctx).listOf(Wallpapers.SCREENSAVER), video)),
                    () -> host.startActivity(WallpaperBrowserActivity.mine(host, video
                            ? WallpaperBrowserActivity.VIDEOS : WallpaperBrowserActivity.PICTURES,
                            Wallpapers.SCREENSAVER, name)));
            row.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
            row.about = R.string.media_tick_short;
        }
        Row add = page.link(R.string.media_title, null, this::media);
        add.about = R.string.media_short;
        return page;
    }

    private Page idleFrom() {
        Page page = new Page(ctx.getString(R.string.wp_use));
        page.footnote = R.string.idle_backdrops_short;
        for (int from : new int[]{VibeSettings.SLIDES_OWN, VibeSettings.SLIDES_SELECTED, VibeSettings.SLIDES_ALL}) {
            // Picked, it's ticked and the list stays: Back or Left goes back.
            Row row = page.add(IDLE_FROM[from], () -> from == settings.slideshowFrom() ? "✓" : "", () -> {
                settings.setSlideshowFrom(from);
                for (Page open : pages) open.refresh();   // the lists on the left say it too
            });
            if (from == settings.slideshowFrom()) page.opened = row;
        }
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
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                grownH + 2 * glow + (int) (12 * density));
        page.list.addView(preview, lp);
        wheel[0] = new ColorWheelView(ctx, tint, new ColorWheelView.Listener() {
            @Override
            public void picked(int rgb) {
                settings.setGlassTint(rgb);
                glass.setTint(rgb);
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

    /**
     * Dock opacity, or Top bar opacity: a vertical slider, as a volume's (OpacitySliderView), with a small dock or
     * top bar over a wallpaper below it (DockSampleView) that follows as it moves. Saved once it rests a moment
     * (not every step while held), and whenever the page is left: OK, Left, Back or Home.
     */
    private Page opacity(boolean topBar) {
        Page page = new Page(ctx.getString(topBar ? R.string.top_bar_opacity : R.string.dock_opacity));
        page.wholeRows = false;   // the slider and the sample: all of it shows
        int start = topBar ? settings.topBarOpacity() : settings.dockOpacity();
        DockSampleView sample = new DockSampleView(ctx, start, topBar);
        int[] chosen = {start};
        Runnable save = () -> {
            handler.removeCallbacks(page.save);
            int now = topBar ? settings.topBarOpacity() : settings.dockOpacity();
            if (now == chosen[0]) return;
            if (topBar) settings.setTopBarOpacity(chosen[0]);
            else settings.setDockOpacity(chosen[0]);
        };
        page.save = save;
        OpacitySliderView slider = new OpacitySliderView(ctx, start,
                UiFont.get(ctx, R.string.font_regular), new OpacitySliderView.Listener() {
                    @Override
                    public void changed(int percent) {
                        chosen[0] = percent;
                        sample.setPercent(percent);
                        handler.removeCallbacks(save);
                        handler.postDelayed(save, 400);
                    }

                    @Override
                    public void done() {
                        back();   // which saves
                    }
                });
        // Both inside the page's window (Page: the screen less the title and the text under it), the sample's
        // bottom, the dock, too: the slider about half of it, the sample most of the rest.
        int room = screenH - (int) ((82 + 16) * density) - (int) (ABOUT_DP * density) - (int) (24 * density);
        page.list.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, room * 52 / 100));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, room * 42 / 100);
        lp.topMargin = room * 4 / 100;
        page.list.addView(sample, lp);
        page.stops.add(slider);
        page.about.setText(R.string.dock_opacity_hint);
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
        // What it is now: the flights overhead, how many of the Library's are ticked for it, or nothing.
        Row custom = page.link(R.string.wp_custom, () -> {
            Wallpapers w = Wallpapers.get(ctx);
            char pick = w.systemPick(true);
            return pick == Wallpapers.FLIGHTS ? ctx.getString(R.string.flights_title)
                    : pick == Wallpapers.GALLERY ? String.valueOf(w.listOf(Wallpapers.ROTATION).size())
                    : ctx.getString(R.string.art_disabled);
        }, this::wallpapersPage);
        custom.about = R.string.wp_custom_short;
        Row flights = page.link(R.string.flights_title, null, this::flightsPage);
        flights.about = R.string.flights_short;
        Row details = page.toggle(R.string.title_info, settings::titleInfo, settings::setTitleInfo);
        details.about = R.string.title_info_short;
        page.link(R.string.picture_format, () -> ctx.getString(settings.jpegStills() ? R.string.picture_jpeg
                : R.string.picture_png), this::pictureFormat);
        page.add(R.string.reload_backdrops, null, () -> {
            MainActivity home = home();
            if (home != null) home.reloadBackdrops();
            Toast.makeText(host, R.string.backdrops_reloading, Toast.LENGTH_SHORT).show();
        });
        return page;
    }

    // ---- flights overhead ------------------------------------------------------------------------------

    /**
     * Backdrops > Flights overhead: the aircraft passing near here on a map (Flights, FlightBoard), shown where
     * they're enabled: as the system backdrop (wallpapersPage), or in a tile's own order (its menu > Edit >
     * Backdrops). Here, whether their card shows, without the map, over a tile's colour; how far away one still counts, and what's kept of the answers about them, to clear.
     * Home applies it as it comes back (MainActivity.applyFlights). The OpenSky client is with the other keys
     * (API keys).
     */
    private Page flightsPage() {
        Page page = new Page(ctx.getString(R.string.flights_title));
        page.footnote = R.string.flights_short;
        page.toggle(R.string.flights_over_colour, settings::flightsOverColour, settings::setFlightsOverColour)
                .about = R.string.flights_over_colour_short;
        page.link(R.string.flights_distance, () -> flightsDistance(settings.flightsReach()), this::flightsReach);
        Flights flights = Flights.get(ctx);
        Row clear = page.add(R.string.flights_clear, () -> String.valueOf(flights.kept()), null);
        clear.action = () -> flights.clear(clear::refresh);
        flights.load(clear::refresh);   // read from its file, if Home hasn't yet: the number kept
        return page;
    }

    /** "25 km", or "15 mi" where the weather is in Fahrenheit. */
    private String flightsDistance(int index) {
        return settings.flightsImperial() ? VibeSettings.FLIGHTS_MI[index] + " mi"
                : VibeSettings.FLIGHTS_KM[index] + " km";
    }

    private Page flightsReach() {
        Page page = new Page(ctx.getString(R.string.flights_distance));
        for (int i = 0; i < VibeSettings.FLIGHTS_KM.length; i++) {
            int index = i;
            // Picked, it's ticked and the list stays: Back or Left goes back.
            Row row = page.add(flightsDistance(index), () -> index == settings.flightsReach() ? "\u2713" : "", () -> {
                settings.setFlightsReach(index);
                page.refresh();
                for (Page open : pages) open.refresh();   // the list on the left: its value now
            });
            if (index == settings.flightsReach()) page.opened = row;
        }
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

    /**
     * Backdrops > System backdrop: what a tile shows where the system backdrop comes in its order (its menu >
     * Edit > Backdrops). One of two, first to last, neither enabled until chosen: a list of the Library's
     * pictures and videos (ticked here, and the Library, where they're added), and the flights overhead
     * (flightsPage). The first that's enabled and has something to show is it; the other only when that has
     * nothing. OK on one goes from enabled to moving (Up and Down carry it) to disabled; Right enables or
     * disables too.
     */
    private Page wallpapersPage() {
        Page page = new Page(ctx.getString(R.string.wp_custom));
        page.footnote = R.string.wp_custom_short;
        movingSystem = 0;
        for (char c : Wallpapers.get(ctx).systemOrder().toCharArray()) systemRow(page, Character.toLowerCase(c));
        numberSystem(page);
        // Its own list, ticked from everything uploaded (Library, the one place they come in).
        String name = ctx.getString(R.string.wp_custom);
        Row pictures = page.add(R.string.backdrop_pictures,
                () -> String.valueOf(countOf(Wallpapers.get(ctx).listOf(Wallpapers.ROTATION), false)),
                () -> host.startActivity(WallpaperBrowserActivity.mine(host, WallpaperBrowserActivity.PICTURES,
                        Wallpapers.ROTATION, name)));
        pictures.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        pictures.about = R.string.media_tick_short;
        Row videos = page.add(R.string.backdrop_videos,
                () -> String.valueOf(countOf(Wallpapers.get(ctx).listOf(Wallpapers.ROTATION), true)),
                () -> host.startActivity(WallpaperBrowserActivity.mine(host, WallpaperBrowserActivity.VIDEOS,
                        Wallpapers.ROTATION, name)));
        videos.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        videos.about = R.string.media_tick_short;
        Row add = page.link(R.string.media_title, null, this::media);
        add.about = R.string.media_short;
        return page;
    }

    /** The one of the system backdrop's two being moved (OK picked it up), or 0. */
    private char movingSystem;

    /** One of the system backdrop's two (its list of the Library's, the flights): a row of its page, in their order. */
    private void systemRow(Page page, char what) {
        Wallpapers w = Wallpapers.get(ctx);
        Row row = page.add(what == Wallpapers.GALLERY ? R.string.media_title : R.string.flights_title, () -> {
            if (what == movingSystem) return "\u2195";   // up-down arrow: Up and Down move it
            return ctx.getString(Wallpapers.on(w.systemOrder(), what) ? R.string.art_enabled : R.string.art_disabled);
        }, null);
        row.view.setTag(what);
        row.aboutText = ctx.getString(what == Wallpapers.GALLERY ? R.string.wp_custom_short : R.string.flights_short)
                + "\n\n" + ctx.getString(R.string.art_sources_help);
        // OK goes round: Enabled, then moving (Up and Down carry it), then Disabled, then Enabled again.
        row.action = () -> {
            String order = w.systemOrder();
            if (what == movingSystem) {
                movingSystem = 0;
                w.setSystemOrder(Wallpapers.enabled(order, what, false));
            } else if (!Wallpapers.on(order, what)) {
                w.setSystemOrder(Wallpapers.enabled(order, what, true));
            } else {
                movingSystem = what;
            }
            for (Page open : pages) open.refresh();   // the list on the left too: what it is now
        };
        row.view.setOnKeyListener((v, keyCode, event) -> {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (what == movingSystem) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (down) moveSystem(page, row, keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                        return true;
                    case KeyEvent.KEYCODE_BACK:   // both its down and up: Settings doesn't go back as well
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (down) {
                            movingSystem = 0;
                            page.refresh();
                        }
                        return true;
                    default:
                        return false;   // OK: the click drops it
                }
            }
            if (!down) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                String order = w.systemOrder();
                w.setSystemOrder(Wallpapers.enabled(order, what, !Wallpapers.on(order, what)));
                for (Page open : pages) open.refresh();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pages.size() > 1) {
                back();
                return true;
            }
            return false;
        });
    }

    /** The one being moved goes one place up (-1) or down; focus stays on it, the other goes round it. */
    private void moveSystem(Page page, Row row, int dir) {
        Wallpapers w = Wallpapers.get(ctx);
        String order = w.systemOrder(), to = Wallpapers.moved(order, Wallpapers.at(order, (Character) row.view.getTag()), dir);
        if (to.equals(order)) return;
        int at = page.rows.indexOf(row);
        Row other = page.rows.get(at + dir);
        page.list.removeView(other.view);   // not the focused one: it keeps focus
        int here = page.list.indexOfChild(row.view);
        page.list.addView(other.view, dir < 0 ? here + 1 : here);
        java.util.Collections.swap(page.rows, at, at + dir);
        int sa = page.stops.indexOf(row.view), so = page.stops.indexOf(other.view);
        if (sa >= 0 && so >= 0) java.util.Collections.swap(page.stops, sa, so);
        w.setSystemOrder(to);
        numberSystem(page);
        for (Page open : pages) if (open != page) open.refresh();
        row.view.playSoundEffect(dir < 0 ? android.view.SoundEffectConstants.NAVIGATION_UP
                : android.view.SoundEffectConstants.NAVIGATION_DOWN);
    }

    /** Each of the two's place before its name: 1 is what shows, 2 only when 1 has nothing. */
    private void numberSystem(Page page) {
        int n = 0;
        for (Row r : page.rows) {
            if (!(r.view.getTag() instanceof Character)) continue;
            char what = (Character) r.view.getTag();
            String numbered = (++n) + "   " + ctx.getString(what == Wallpapers.GALLERY ? R.string.media_title
                    : R.string.flights_title);
            r.label.setText(numbered);
        }
    }

    /** How many of these are videos (or pictures). */
    private static int countOf(List<Wallpapers.Item> items, boolean video) {
        int n = 0;
        for (Wallpapers.Item item : items) if (item.video == video) n++;
        return n;
    }

    // ---- fonts -----------------------------------------------------------------------------------------

    /**
     * Font, or Clock and weather font: Outfit, the system's families and the fonts on the device (Fonts), each
     * named in itself, the chosen one ticked; the two are chosen apart. A font without the current
     * language's letters says so: the system font shows for that language instead (UiFont). Picked, Home and
     * Settings are made again in it, back here on Appearance.
     */
    private Page fontPicker(boolean clock) {
        Page page = new Page(ctx.getString(clock ? R.string.clock_font : R.string.font));
        List<String> choices = new ArrayList<>();
        choices.add(Fonts.OUTFIT);
        java.util.Collections.addAll(choices, Fonts.SYSTEM);
        for (Fonts.Item item : new Fonts(ctx).list()) choices.add(item.id);
        String sample = UiFont.sample(ctx), language = UiFont.languageName(ctx);
        for (String choice : choices) {
            Row row = page.add(Fonts.name(ctx, choice), () -> choice.equals(clock ? settings.clockFont()
                    : settings.uiFont()) ? "✓" : "", () -> {
                if (clock) settings.setClockFont(choice);
                else settings.setUiFont(choice);
                Fonts.changed();
                rebuildHome();
                Activity h = host;
                if (h != null) {
                    h.setIntent(h.getIntent().putExtra(SettingsActivity.ON_APPEARANCE, true));
                    h.recreate();
                }
            });
            // Each named in itself, Outfit too.
            android.graphics.Typeface face = Fonts.OUTFIT.equals(choice)
                    ? ctx.getResources().getFont(R.font.outfit) : Fonts.typeface(ctx, choice, false);
            if (face != null) row.label.setTypeface(face);
            if (!Fonts.covers(ctx, choice, sample)) {
                row.aboutText = ctx.getString(R.string.font_not_covered, language);
            }
            if (choice.equals(clock ? settings.clockFont() : settings.uiFont())) page.opened = row;   // focus starts here
        }
        Row add = page.link(R.string.font_add, null, this::fontsPage);
        add.about = R.string.fonts_short;
        return page;
    }

    /** Library > Fonts: from the phone, Google Fonts (FontBrowserActivity), and those on the device. */
    private Page fontsPage() {
        Page page = new Page(ctx.getString(R.string.fonts));
        page.footnote = R.string.fonts_short;
        if (phoneUrl != null && link != null && link.fontUrl() != null) {
            Row phone = page.link(R.string.wp_add_phone, null,
                    () -> phone(R.string.wp_add_phone, link.fontUrl(), R.string.font_scan));
            phone.about = R.string.font_add_phone_short;
        }
        Row browse = page.add(R.string.fonts_browse, null,
                () -> host.startActivity(FontBrowserActivity.intent(host, false)));
        browse.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        browse.about = R.string.font_credit;
        Row there = page.add(R.string.media_on_device, () -> String.valueOf(new Fonts(ctx).list().size()),
                () -> host.startActivity(FontBrowserActivity.intent(host, true)));
        there.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        return page;
    }

    // ---- pictures and videos ---------------------------------------------------------------------------

    /**
     * Library: the one place they come in, in three buckets, each kept once: tile pictures (for a tile,
     * tile menu > Edit > Change icon), and backdrop pictures and videos (for an app's, a folder's or Custom
     * wallpapers' list). Each: Add from phone, the sources to browse, and what's on the device (OK deletes).
     */
    private Page media() {
        Page page = new Page(ctx.getString(R.string.media_title));
        page.footnote = R.string.media_short;
        page.link(R.string.wp_pictures, () -> String.valueOf(countOf(Wallpapers.get(ctx).list(), false)),
                () -> libraryMedia(false));
        page.link(R.string.wp_videos, () -> String.valueOf(countOf(Wallpapers.get(ctx).list(), true)),
                () -> libraryMedia(true));
        page.link(R.string.fonts, () -> String.valueOf(new Fonts(ctx).list().size()), this::fontsPage);
        return page;
    }

    /**
     * The Library's pictures, or its videos: from the phone (a picture cut to the screen there, or, Tile pictures,
     * framed small for a tile), the sources that have them, and those on the device. Any of them can be used
     * anywhere: a picture on a tile, either behind Home.
     */
    private Page libraryMedia(boolean video) {
        Page page = new Page(ctx.getString(video ? R.string.wp_videos : R.string.wp_pictures));
        if (phoneUrl != null && link != null && link.wallpaperUrl() != null) {
            Row phone = page.link(R.string.wp_add_phone, null,
                    () -> phone(R.string.wp_add_phone, link.wallpaperUrl(), R.string.wp_scan));
            phone.about = R.string.wp_add_phone_short;
        }
        if (!video && phoneUrl != null && link != null && link.tileUrl() != null) {
            Row tile = page.link(R.string.tile_pictures, null,
                    () -> phone(R.string.tile_pictures, link.tileUrl(), R.string.tp_scan));
            tile.about = R.string.tp_add_phone_short;
        }
        if (!video) {
            Row wallhaven = page.add(R.string.wp_browse_wallhaven, null,
                    () -> host.startActivity(WallpaperBrowserActivity.intent(host, Wallpapers.WALLHAVEN)));
            wallhaven.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
            wallhaven.about = R.string.wp_credit_wallhaven;
        } else {
            Row pexels = page.add(R.string.wp_browse_pexels, null, () -> {
                if (settings.pexelsKey().isEmpty()) MainActivity.showError(host, R.string.wp_pexels_no_key);
                else host.startActivity(WallpaperBrowserActivity.intent(host, Wallpapers.PEXELS));
            });
            pexels.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
            pexels.about = R.string.pexels_short;
        }
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
        if (video) {   // videos only, and big ones
            Row apple = page.add(R.string.wp_browse_apple, null,
                    () -> host.startActivity(WallpaperBrowserActivity.intent(host, Wallpapers.APPLE)));
            apple.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
            apple.about = R.string.wp_apple_short;
        }
        Row there = page.add(R.string.media_on_device, () -> String.valueOf(countOf(Wallpapers.get(ctx).list(), video)),
                () -> host.startActivity(WallpaperBrowserActivity.mine(host,
                        video ? WallpaperBrowserActivity.VIDEOS : WallpaperBrowserActivity.PICTURES, null, null)));
        there.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        return page;
    }

    /** Back on Settings (from a wallpaper grid), or one arrived from the phone: how many there are now. */
    void showWallpapers() {
        for (Page page : pages) page.refresh();   // the counts on whichever pages are open
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
        key(page, R.string.opensky_id, VibeSettings.KEY_OPENSKY_ID, R.string.opensky_short);
        key(page, R.string.opensky_secret, VibeSettings.KEY_OPENSKY_SECRET, R.string.opensky_short);
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
        // The phone's backup page answers only while this code is on the screen: a backup has the keys in full.
        if (phoneUrl != null) page.link(R.string.backup_phone, null, () -> {
            Page code = phone(R.string.backup_phone, link.backupUrl(), R.string.scan_to_backup);
            code.footnote = 0;
            code.about.setText("");
            code.view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    if (link != null) link.backupsOpen = true;
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    if (link != null) link.backupsOpen = false;
                }
            });
            return code;
        });
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
    Page phone(int title, String phoneUrl, int scanText) {
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
        url.setGravity(android.view.Gravity.CENTER_HORIZONTAL);   // under the code, as the instruction is
        box.addView(url);
        page.list.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.stops.add(box);
        page.footnote = R.string.phone_keep;   // the address stays: bookmarked, it's there whenever Settings is
        page.about.setText(R.string.phone_keep);
        return page;
    }

    /** Apps and all under it: the TV's debugging, the clean-up, the default launcher, ad blocking. */
    final AdbSettingsPages adbPages = new AdbSettingsPages(this);

    /**
     * What to do in Android's settings, in a window like the update's (a message at the bottom of the screen went
     * before it could be read), and Continue to open them; Cancel stays here.
     */
    void explainThenOpen(int message, Runnable open) {
        explainThenDo(R.string.adb_connection, message, open);
    }

    /** The same window with a title of its own: Continue runs then. */
    void explainThenDo(int title, int message, Runnable open) {
        explainThenDo(title, message, R.string.continue_button, open);
    }

    /** As explainThenDo, its button saying what it does (Delete). */
    void explainThenDo(int title, int message, int button, Runnable open) {
        confirm(ctx.getString(title), ctx.getString(message), button, false, open);
    }

    /**
     * The launcher's own window asking before something is done: a title, what it's about (null: the title says
     * it all, and the window is narrower), a button that does it and Cancel. cancelFirst: Cancel has the focus,
     * for what can't be undone or wasn't asked for here. The window, or null when Settings isn't showing.
     */
    Dialog confirm(CharSequence title, CharSequence message, int button, boolean cancelFirst, Runnable then) {
        if (MainActivity.gone(host)) return null;
        Activity activity = host;
        View panel = LayoutInflater.from(ctx).inflate(R.layout.dialog_update,
                activity.findViewById(android.R.id.content), false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(title);
        if (message != null) ((TextView) panel.findViewById(R.id.notes)).setText(message);
        else panel.findViewById(R.id.notesScroll).setVisibility(View.GONE);
        TextView go = panel.findViewById(R.id.install), cancel = panel.findViewById(R.id.cancelUpdate);
        go.setText(button);
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) ((message != null ? 560 : 420) * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        Highlight.pill(go, highlight);
        Highlight.pill(cancel, highlight);
        cancel.setOnClickListener(v -> dialog.dismiss());
        go.setOnClickListener(v -> {
            dialog.dismiss();
            if (host == activity) then.run();
        });
        dialog.show();
        (cancelFirst ? cancel : go).requestFocus();
        return dialog;
    }

    /**
     * The TV's About screen, where pressing Build seven times unlocks Developer options. Its action can lead to
     * several screens (Sony's Settings answers it with four, About among them, which would show a chooser): the
     * one named About, or the only one; failing that, Settings itself.
     */
    void openAbout() {
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
    void open(Page next, Row from) {
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
        if (gone.save != null) gone.save.run();
        adbPages.pollAdb();   // back on Apps or ADB connection (from pairing, say): looked at again; stops itself elsewhere
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

    void showAbout(Page page, Row row) {
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
        settings.save(keys);
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
            // Settings may be closed (the link kept a while for a pairing): the phone is told they're saved, so
            // they are, over what's saved now; only showing them waits for Settings.
            boolean open = host != null;
            String[] saved = open ? keys : settings.keys();
            String[] names = VibeSettings.KEY_NAMES;
            for (int i = 0; i < names.length; i++) {
                String k = received.get(names[i]);
                if (k == null) continue;
                saved[i] = k.trim();
                if (!open) continue;
                // Its row is open for typing here: the field shows the new one (as kept ones are shown), or
                // closing it would put what it showed before in the new one's place.
                if (editing != null && i == editingKey) {
                    editing.field.setText(mask(keys[i]));
                    editing.field.selectAll();   // as when it's opened: what's typed next takes its place
                }
            }
            settings.save(saved);
            if (!open) return;
            for (Page page : pages) page.refresh();
            Toast.makeText(host, R.string.saved_from_phone, Toast.LENGTH_SHORT).show();
        }, settings, PhoneLink.savedCode(ctx.getApplicationContext()));
        Context app = ctx.getApplicationContext();
        if (AdBlock.supported()) link.customDns = adbPages::customAdFromPhone;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) link.pairing = adbPages::pairFromPhone;
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
            public boolean received(java.io.File file, boolean video, String name) {
                boolean added = walls.add(file, video, Wallpapers.PHONE, "", name, screenW, screenH) != null;
                if (added) handler.post(SettingsScreen.this::showWallpapers);
                return added;
            }
        };
        link.fonts = new PhoneLink.FontUploads() {
            @Override
            public java.io.File incoming() {
                return new Fonts(app).incoming();
            }

            @Override
            public boolean received(java.io.File file, String name) {
                String family = name.replaceAll("(?i)\\.(ttf|otf)$", "").replace('_', ' ').replace('-', ' ').trim();
                if (family.isEmpty()) family = "Font";
                boolean added = new Fonts(app).add(file, null, family, Fonts.PHONE) != null;
                if (added) handler.post(SettingsScreen.this::showWallpapers);
                return added;
            }
        };
        link.tilePictures = (dataUrl, name) -> {
            android.graphics.Bitmap picture = TilePictures.decode(dataUrl);
            TilePictures tiles = new TilePictures(app);
            String id = picture != null ? tiles.add(picture) : null;
            boolean added = id != null;
            if (added && !name.isEmpty()) tiles.setTitle(id, name);
            if (added) handler.post(SettingsScreen.this::showWallpapers);
            return added;
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
     * The installed version and a row that checks for a newer one, then offers to install it. A development
     * build only says how it updates.
     */
    private void showUpdates(Row status, Row button) {
        String version = Updater.installedVersion(ctx);
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
    static String mask(String key) {
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
        if (MainActivity.gone(activity)) return;
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
        // Opened from Home's gear: Home's highlight; from Settings: Settings' white.
        int highlight = activity instanceof MainActivity ? Highlight.color(activity) : Highlight.SETTINGS;
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
