package com.maydings.vibelauncher;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.Layout;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.net.Inet4Address;
import java.net.URISyntaxException;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The home screen: a backdrop for the focused dock app, a pinned dock row, an app grid, and one glass
 * highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots: Vibe Launcher settings (4, 5 or 6), else config.xml. Set in onCreate. */
    static int COLUMNS = 4;
    /** How long the rows take to slide out of sight; the top bar moves at the same speed. */
    private static final long PICTURE_SLIDE_MS = 450;
    private static final long CARET_FADE_MS = 150;   // the carets go before the menus move
    private static final float CARET_ALPHA = 0.25f;
    private static final long PLAY_SLIDE_MS = 350;    // the Play pill, in from the left
    private static final long BAR_SLIDE_MS = 250;     // the top bar in or out as focus moves
    private static final long IDLE_MS = 60_000;       // left alone this long, Home shows the slideshow
    private static final long SLIDE_MS = 10_000;      // each picture in the slideshow
    /**
     * Up on the dock waits this long before the picture shows alone: another Up meanwhile (scrolling up
     * quickly) goes straight on to the top bar, and any other key stays, with no slide started and undone.
     * Only with the top bar always showing: set to auto-hide, there's no bar to scroll on to, so no wait.
     */
    private static final long PICTURE_DELAY_MS = 300;
    private static final long HOLD_FINISH_MS = 250;   // an empty Play pill to full, once the long press comes
    private static final long MINUTE_MS = 60_000L;
    /** 2025-01-01: a clock before this hasn't been set yet. */
    private static final long TIME_SET_AFTER = 1_735_689_600_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    /** The app rows' frame and the top bar: slid away to leave the focused app's picture alone. */
    private View rows, topBar;
    private boolean pictureOnly;
    /** The rows are down out of sight while the top bar has focus (auto-hide, Up from the picture alone). */
    private boolean rowsAway;
    /** While the menus move to or from the picture alone: no carets (see transition). */
    private boolean caretsHeld;
    private Runnable moveMenus = () -> { };
    private final Runnable caretsBack = this::menusMoved;
    private final Runnable nameBack = this::showName;
    private boolean sideHeld;   // Left or Right held with the picture alone: it switched app, its release does nothing
    /**
     * Up pressed on the dock, or Down on the top bar: the picture shows alone after PICTURE_DELAY_MS,
     * unless pictureSoonKey comes again meanwhile (scrolling on) or another key does.
     */
    private boolean pictureSoon;
    private int pictureSoonKey;
    private int focusAfterSlide = -1;   // from the top bar: the dock tile to focus once the rows are gone
    /**
     * The slideshow: after IDLE_MS without a key on Home, every dock app's pictures at random, SLIDE_MS
     * each, with nothing else on screen. Any key stops it on the picture showing (see endSlideshow).
     */
    private boolean slideshow;
    private String slidePkg;            // the app of the picture showing in the slideshow
    private Backdrops.Entry slideEntry;
    private int swallowUp = -1;         // the key that stopped the slideshow: its release does nothing
    private long lastKeyAt;
    private final Runnable startSlideshow = this::startSlideshow;
    private final Runnable nextSlide = this::nextSlide;
    /** The screen stays on for the slideshow only until the TV's own screen-off time since the last key. */
    private final Runnable letScreenOff =
            () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    private final Runnable showPictureSoon = () -> {
        pictureSoon = false;
        showPictureOnly(SoundEffectConstants.NAVIGATION_UP);
    };
    private final Runnable showPictureFromBar = this::showPictureFromBar;
    private float dockPanelTop;   // the dock's panel top on screen: how far the rows slide to leave it
    private View caretDock, caretBar, caretUp, caretDown, caretLeft, caretRight;
    private boolean playShown;   // the Play pill is in (or coming in)
    private VibeSettings settings;
    /** Hold OK with the picture alone: the Play pill fills, and a long press opens backdropLink. */
    private View holdPlay, holdPlayFill;
    private View slideClock;   // the slideshow's clock, where the top bar's is
    private TextView playInfo;   // under the Play pill: the time left on something you're watching
    private ValueAnimator holdFill;
    private boolean holdOpened;   // this press already opened the title: its release changes nothing
    private String backdropLink;   // what opens the picture on screen's title; null for none
    /** A picture (not the glow) is behind the dock: without one there's no picture to show alone. */
    private boolean stillShown;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private int backdropColor;   // the focused app's glow colour, for showing its still once it's in
    private boolean heroShown;
    private View backdrop;
    private ImageView backdropImage;
    /** The still being faded out under backdropImage while the next one fades in. */
    private ImageView backdropPrevious;
    private View backdropTitleBox;
    private ImageView backdropLogo;
    private TextView backdropTitle;
    private Backdrops backdrops;
    /** The dock app whose backdrop is showing or loading, or null. */
    private String backdropPkg;
    private TextView btnWifi;
    /** The network pill's icon as last set (updateWifiLabel). */
    private int wifiIcon;
    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    /** Scroll the glass target already accounts for, so it heads (mostly) to where the row will settle. */
    private int plannedScroll;
    /** The focused tile, so the glass can re-measure it once a scroll settles. */
    private View focusedCard;
    /** Width of the tile the glass was last fitted to. */
    private int glassCardW;
    /** Share of a vertical scroll the glass runs ahead into before settling back: a gentle bounce. */
    private static final float GLASS_LEAD = 0.12f;
    private boolean glassShown;
    /** The dropdown currently on screen (Inputs or a tile menu), if any. */
    private PopupWindow openMenu;
    private int lastFocused = 0;
    private float density;
    /**
     * Rebuild the tiles on resume: Home is starting, or let go of them for memory (onTrimMemory), or a
     * permission came in. Coming back from an app rebuilds them only if apps changed meanwhile.
     */
    private boolean rebuildOnResume = true;
    /** Home was covered by an app (stopped) since the last resume: a new picture for this visit. */
    private boolean backFromApp;
    /** An app was installed, updated, removed, enabled or disabled while Home was stopped. */
    private boolean appsChanged;
    /** PackageManager's change counter at the last rebuild, for a change whose broadcast never came. */
    private int packageSequence;
    /** Home was pressed while Home was in front: go back to the first dock tile. */
    private boolean homeToTop;
    /** The focused app was on its glow, waiting for its first still: show it now. */
    private final Backdrops.FirstStillListener firstStill = pkg -> {
        if (!heroShown || !pkg.equals(backdropPkg)) return;
        backdropPkg = null;
        showBackdrop(pkg, backdropColor);
    };
    /** Plex's progress arrives after its carousel opened: the time left follows. */
    private final Runnable watchingChanged = () -> {
        if (pictureOnly) updatePlayInfo();
    };
    /** Between onStart and onStop: Home is (at least partly) on screen. */
    private boolean started;
    /** The time box's width for each length of time text ("8:08" is 4, "10:08" is 5); see sizeTimeBox. */
    private final Map<Integer, Integer> timeBoxWidths = new HashMap<>();
    /** The 12/24-hour setting and language timeBoxWidths were measured for. */
    private String timeBoxMeasuredFor;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Home is covered by Vibe Launcher's own Settings, not an app: coming back is no new visit. */
    private boolean inSettings;
    /** The Home there is, for Settings (it makes Home again after a new grid or language). */
    private static java.lang.ref.WeakReference<MainActivity> current = new java.lang.ref.WeakReference<>(null);

    static MainActivity current() {
        return current.get();
    }
    /** Refreshes the network pill when the connection changes: after a reboot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
            handler.post(MainActivity.this::wakeUpdateCheck);   // waiting for the network after waking
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /**
     * The TV was just turned on (Home starts with it) or woken from sleep: check for an update once the
     * network is up, so a new version is offered at the start of an evening's viewing.
     */
    private boolean wakeCheck = true;
    private boolean wakeChecking;   // one on its way: a network change meanwhile doesn't start another
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            wakeCheck = true;
            wakeUpdateCheck();
        }
    };
    /**
     * Apps installed, updated or removed while Home is on screen show up (or go) straight away, instead
     * of at the next return to Home. An update sends "removed" then "added": keep its tile meanwhile.
     */
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            if (!started) {   // behind an app: the tiles are rebuilt once, on the way back
                appsChanged = true;
                return;
            }
            adapter.reload();
            appsChangedUnseen();   // shown already: no rebuild for it on the next return
            ChannelSetup.run(MainActivity.this);   // a new or updated app asked for its home screen cards now
            // Tiles may have moved: once they settle, bring the glass to wherever focus went.
            grid.postOnAnimation(() -> {
                RecyclerView.ItemAnimator animator = grid.getItemAnimator();
                if (animator != null) animator.isRunning(MainActivity.this::refreshFocusedTile);
                else refreshFocusedTile();
            });
        }
    };
    /**
     * Rebuilds the dock apps' backdrop pools that are due (six hours old), then comes back when the next
     * one will be. Only while Home is on screen; the next visit catches up on any that fell due meanwhile.
     */
    private final Runnable refreshPools = new Runnable() {
        @Override
        public void run() {
            List<String> dock = new ArrayList<>();
            for (int i = 0; i < COLUMNS; i++) {
                String pkg = adapter.packageAt(i);
                if (pkg != null) dock.add(pkg);
            }
            for (String pkg : dock) backdrops.refresh(pkg);
            handler.removeCallbacks(this);
            // Builds just started count as fresh, so this lands on the next pool to fall due.
            handler.postDelayed(this, Math.max(MINUTE_MS, backdrops.untilDue(dock)));
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));   // the language chosen in Settings, or the TV's own
    }

    /**
     * Nothing of the last Home is put back (the grid's scroll, the focus): made again (a new grid or language
     * from Settings), it came back scrolled to the bottom. Home always starts on the dock's first app.
     */
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);
        PowerOnHome.register(this);
        current = new java.lang.ref.WeakReference<>(this);
        // Settings, made ahead once Home's first frame is up (and a moment for its entrance), so opening it is
        // only a fade.
        getWindow().getDecorView().post(() -> handler.postDelayed(() -> SettingsScreen.prepare(this), 1000));
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // Tiles per row: chosen in Vibe Launcher settings (4, 5 or 6), else by the screen's size.
        settings = new VibeSettings(this);
        COLUMNS = settings.columns(defaultColumns());
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        backdrop = findViewById(R.id.backdrop);
        backdropImage = findViewById(R.id.backdropImage);
        backdropPrevious = findViewById(R.id.backdropPrevious);
        backdropTitleBox = findViewById(R.id.backdropTitleBox);
        backdropLogo = findViewById(R.id.backdropLogo);
        backdropTitle = findViewById(R.id.backdropTitle);
        backdrops = Backdrops.get(this);
        backdrops.attach(firstStill, watchingChanged);
        if (!askPermissionsOnce()) askHomeOnce();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        // For Home's whole life: on screen a change shows at once; behind an app it only marks the tiles out of
        // date, so coming back rebuilds them only when apps changed.
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);   // enabled or disabled
        packages.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, packages, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packageReceiver, packages);
        IntentFilter screenOn = new IntentFilter(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, screenOn, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, screenOn);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        // One drawable, nothing overlapping: its fades can apply alpha while drawing, instead of rendering
        // the full-screen glow into an offscreen buffer on every frame of the fade.
        ambient.forceHasOverlappingRendering(false);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        btnWifi = findViewById(R.id.btnWifi);
        TextView clock = findViewById(R.id.clock), date = findViewById(R.id.date);
        localDate((TextClock) date, "EEEE d MMMM yyyy");
        showClockOnceTimeIsSet(findViewById(R.id.clockRow));
        sizeTimeBox();
        clock.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                fitTimeBox(s);   // the hour gained or lost a digit
            }
        });
        date.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> centreTwoLineDate(clock, date));

        grid = findViewById(R.id.grid);
        rows = findViewById(R.id.rows);
        topBar = findViewById(R.id.topBar);
        caretDock = findViewById(R.id.caretDock);
        caretBar = findViewById(R.id.caretBar);
        caretUp = findViewById(R.id.caretUp);
        caretDown = findViewById(R.id.caretDown);
        caretLeft = findViewById(R.id.caretLeft);
        caretRight = findViewById(R.id.caretRight);
        holdPlay = findViewById(R.id.holdPlay);
        slideClock = findViewById(R.id.slideClock);
        holdPlayFill = findViewById(R.id.holdPlayFill);
        playInfo = findViewById(R.id.playInfo);
        setHoldFill(0f);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(new TileAnimator());
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);
        FrostView frost = findViewById(R.id.frost);
        frost.setSource(grid);
        frost.setBackdrop(backdrop);

        // Push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int tileH = AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(
                    (r - l) - grid.getPaddingLeft() - grid.getPaddingRight(), COLUMNS, density));
            int dockRow = AppAdapter.itemHeightPx(tileH, density) + (int) ((6 + 12) * density);   // plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            boolean moved = grid.getPaddingTop() != want;
            if (moved) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            // Cold start, or a new column count: the focused tile can move or change size after the glass was
            // fitted to it. Fit it again once this layout settles.
            View card = focusedCard;
            if (card != null && card.isFocused() && (moved || card.getWidth() != glassCardW)) {
                grid.post(() -> {
                    RecyclerView.ViewHolder holder = grid.findContainingViewHolder(card);
                    int pos = holder != null ? holder.getBindingAdapterPosition() : RecyclerView.NO_POSITION;
                    if (card.isFocused() && pos != RecyclerView.NO_POSITION) onTileFocused(pos, card);
                });
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                if (plannedScroll != 0 && (dy > 0) == (plannedScroll > 0)) {
                    int take = Math.abs(dy) >= Math.abs(plannedScroll) ? plannedScroll : dy;
                    plannedScroll -= take;
                    dy -= take;
                }
                if (dy == 0) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                // Settle exactly on the tile: the plan can be off by the few pixels the grid scrolled on
                // its own before onTileFocused ran, or the scroll stopped short at the end of the list.
                if (newState != RecyclerView.SCROLL_STATE_IDLE || !glassShown || focusedCard == null
                        || !focusedCard.isAttachedToWindow()) return;
                plannedScroll = 0;
                Rect r = new Rect(0, 0, focusedCard.getWidth(), focusedCard.getHeight());
                root.offsetDescendantRectToMyCoords(focusedCard, r);
                float y = r.exactCenterY() - glass.getLayoutParams().height / 2f;
                if (Math.abs(y - glassTargetY) < 0.5f) return;
                glassTargetY = y;
                springY.animateToFinalPosition(y);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(TileAnimator.STIFFNESS).setDampingRatio(TileAnimator.DAMPING));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> showNetwork());
        btnSettings.setOnClickListener(v -> showSettingsMenu(btnSettings));

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (!stillShown) {   // no picture to show alone (none, or not loaded yet): the dock tile
                    cancelPictureSoon();
                    focusTile(lastFocused % COLUMNS);
                    return true;
                }
                if (settings.autoHideBar()) {   // the bar goes either way: the picture alone at once, no wait
                    cancelPictureSoon();
                    showPictureFromBar();
                    return true;
                }
                if (pictureSoon) {   // Down again straight away: scrolling down, to the dock
                    cancelPictureSoon();
                    // Back to the dock tile Up came from, not whichever tile the focus finder picks.
                    focusTile(lastFocused % COLUMNS);
                    return true;
                }
                // The picture alone, as Up from the dock, after the same wait.
                pictureSoon = true;
                pictureSoonKey = KeyEvent.KEYCODE_DPAD_DOWN;
                handler.postDelayed(showPictureFromBar, PICTURE_DELAY_MS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && (v.getId() == R.id.btnInputs
                    || (v.getId() == R.id.btnWifi && btnInputs.getVisibility() != View.VISIBLE))) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
            // Arriving on the top bar: the network pill as it is now (it otherwise follows the network
            // callbacks and each return to Home).
            if (isPill(newF) && !isPill(oldF)) updateWifiLabel();
            if (onTile && rowsAway && !pictureOnly) bringRowsBack();   // Down twice from the top bar
            updateTopBar();
            updateCarets();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        // Back to the rows for this visit. Here rather than in onStop: Home can still be on screen while
        // the app it opened animates in, and the rows jumping back would show.
        showRows(false, false);
    }

    /**
     * Home pressed: singleTask delivers it here, then onResume runs. From an app, Home was stopped and
     * returns to the tile you left from as usual. Pressed on Home itself (Home never stopped), it goes
     * back to the top and the first dock tile, like the stock launcher.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean pressed = intent != null && intent.hasCategory(Intent.CATEGORY_HOME)
                && !intent.getBooleanExtra(PowerOnHome.EXTRA, false);   // the power-on one leaves Home as it was
        // Pressed with Home already in front.
        if (pressed && !rebuildOnResume && !backFromApp) homeToTop = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        inSettings = false;
        lastKeyAt = SystemClock.uptimeMillis();
        armIdle();
        showUpdateReady();
        if (BuildConfig.STORE) StoreUpdates.check(this, this::showUpdateReady);   // Play keeps the answer
        else if (wakeCheck) wakeUpdateCheck();
        else Updater.checkIfDue(this, (newer, failed) -> showUpdateReady());   // at most once a day
        sizeTimeBox();   // the 12/24-hour setting may have changed meanwhile
        // The pills keep their state while Home is away, and the network callback updates the network one, so
        // this only catches anything missed. Its binder calls (the Wi-Fi name, the TV's inputs) would hold up
        // the first frame back on Home, so after the first resume they run once that frame is drawn.
        boolean rebuild = rebuildOnResume || appsChanged || appsChangedUnseen();
        boolean returning = backFromApp;
        appsChanged = false;
        backFromApp = false;
        if (pillsShown) {
            refreshPillsAfterNextDraw(rebuild || returning);
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        if (homeToTop) {
            homeToTop = false;
            lastFocused = 0;
            // Focused once the grid is laid out at the top: before it, the glass was fitted to where the
            // tile was (pressed again and again, up by the clock), and the jump doesn't move it. Fitted
            // again too, for when the tile kept its focus and no focus change would.
            grid.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    grid.removeOnLayoutChangeListener(this);
                    focusTile(0);
                    refreshFocusedTile();
                }
            });
            grid.scrollToPosition(0);
            grid.requestLayout();
        }
        updateTopBar();
        if (!rebuild) {
            if (returning) {
                // Back from an app with the same apps: the tiles stay as they were (no entrance replayed), and
                // the focused app gets this visit's picture.
                backdrops.reshuffle();
                backdropPkg = null;
                // Loaded here: the tile usually kept its focus, so no focus change would load it.
                if (heroShown && lastFocused < COLUMNS) {
                    showBackdrop(adapter.packageAt(lastFocused), adapter.ambientColorAt(lastFocused));
                }
            }
            // Else back from a side panel, such as the TV's Settings: Home is as it was.
            refreshPools.run();
            return;
        }
        rebuildOnResume = false;
        appsChangedUnseen();   // caught up: count from here
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        if (grid.getAdapter() == null) grid.setAdapter(adapter);   // let go of in onTrimMemory
        adapter.reload();
        // After the reload: on a cold start the grid was still empty, and with no dock apps the next check
        // would have been six hours away.
        refreshPools.run();
        ChannelSetup.run(this);
        backdropPkg = null;
        backdrop.setAlpha(0f);
        stillShown = false;
        backdrops.reshuffle();
        grid.scheduleLayoutAnimation();
        grid.requestLayout();   // reload() only touches changed tiles, so ask for the pass that plays the entrance

        // Return to the tile we left from, or the first one. When a tile kept focus while we were away
        // (the usual case), no focus change follows, so bring back its glass, name and backdrop here:
        // the reset above cleared them.
        grid.post(() -> {
            if (!refreshFocusedTile()) focusTile(lastFocused);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshPools);
        cancelPictureSoon();
        handler.removeCallbacks(startSlideshow);
        if (slideshow) endSlideshow();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        backFromApp = !inSettings;   // Settings is part of Home: no new picture for coming back from it
    }

    /**
     * The check after turning on or waking. At most every few minutes (GitHub's limit on unsigned calls is
     * shared by every TV in the house). If it fails, as when Wi-Fi is still reconnecting after sleep, it
     * runs again once the network is back.
     */
    private void wakeUpdateCheck() {
        if (BuildConfig.STORE || !wakeCheck || wakeChecking) return;   // the store updates it
        wakeChecking = Updater.checkIfOlderThan(this, 5 * 60 * 1000L, (newer, failed) -> {
            wakeChecking = false;
            if (!failed) wakeCheck = false;
            showUpdateReady();
        });
        if (!wakeChecking) wakeCheck = false;   // checked just now anyway
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) current.clear();
        unregisterReceiver(screenReceiver);
        unregisterReceiver(packageReceiver);
        // The pools go on filling for the next Home; only this one's handlers go.
        backdrops.detach(firstStill, watchingChanged);
        handler.removeCallbacksAndMessages(null);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
                // never registered
            }
        }
        super.onDestroy();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of the tiles and the backdrop
     * pictures (a full-screen still is 8 MB at 1080p), which Home rebuilds and reloads when it comes back
     * anyway. Not at UI_HIDDEN, the ordinary trip to an app.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL can also arrive while Home is on screen: never blank what's showing.
        boolean due = level >= TRIM_MEMORY_BACKGROUND
                || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL;
        if (!due || started) return;
        adapter.clear();
        // Detached too: with Home stopped no layout pass runs, so the tile views (and the banners they
        // hold) would stay until Home came back. setAdapter(null) recycles them now.
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        focusedCard = null;
        backdropImage.animate().cancel();
        backdropImage.setImageDrawable(null);
        backdropPrevious.setImageDrawable(null);
        backdropPrevious.setVisibility(View.GONE);
        backdropLogo.setImageDrawable(null);
        backdropPkg = null;
        stillShown = false;
        rebuildOnResume = true;
    }

    /** A menu or dialog opened or closed over Home: its carets go or come back. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        updateCarets();
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    /** Puts the glass, name and backdrop on the tile that has focus; false when no tile has it. */
    private boolean refreshFocusedTile() {
        View focused = getCurrentFocus();
        RecyclerView.ViewHolder holder = focused != null ? grid.findContainingViewHolder(focused) : null;
        int position = holder != null ? holder.getBindingAdapterPosition() : RecyclerView.NO_POSITION;
        if (position == RecyclerView.NO_POSITION || focused.getId() != R.id.card) return false;
        onTileFocused(position, focused);
        return true;
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        focusedCard = card;
        // Focus can arrive before the first layout (cold start): the tile has no size yet, and the glass would
        // shrink to its padding. Fit it once the tile is laid out.
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card);
            });
            return;
        }
        glassCardW = card.getWidth();
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * AppAdapter.FOCUS_SCALE);   // same corners as the tile
        }
        // Work out the scroll first so the glass goes straight to the row's final spot. Aiming at the
        // row's current spot and then following the scroll sent it too far up/down and back.
        View item = (View) card.getParent();
        boolean dock = position < COLUMNS;
        int dy;
        if (dock) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            dy = decoratedTop - grid.getPaddingTop();
        } else {
            // Scroll just enough so the whole glass (not only the tile) is visible.
            int top = item.getTop() - (int) pad * 2, bottom = item.getBottom() + (int) pad;
            int parentTop = grid.getPaddingTop(), parentBottom = grid.getHeight() - grid.getPaddingBottom();
            // When the glass is taller than the space (the 940E: 328 px of glass, 318 of room), favour the
            // top: fitting the bottom pushed the top out, so each sideways press scrolled 10 px back and forth.
            dy = top < parentTop ? top - parentTop
                    : (bottom > parentBottom ? Math.min(bottom - parentBottom, top - parentTop) : 0);
        }
        // The grid can't scroll past its ends (the bottom row), so only plan what is actually left.
        // Measured from the first or last tile when it's laid out: RecyclerView's scroll range is only an
        // estimate for a grid, and with a short last row it came out too small, stopping the scroll with
        // the bottom row cut off.
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (dy > 0 && lm != null) {
            View last = lm.findViewByPosition(adapter.getItemCount() - 1);
            if (last != null) {
                dy = Math.min(dy, Math.max(0, lm.getDecoratedBottom(last) + grid.getPaddingBottom() - grid.getHeight()));
            }
        } else if (dy < 0 && lm != null) {
            View first = lm.findViewByPosition(0);
            if (first != null) dy = Math.max(dy, Math.min(0, lm.getDecoratedTop(first) - grid.getPaddingTop()));
        }
        if (Math.abs(dy) <= 1) dy = 0;
        plannedScroll = Math.round(dy * (1f - GLASS_LEAD));
        y -= plannedScroll;

        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        if (dy != 0) grid.smoothScrollBy(0, dy);
        if (dock) showHero(position, r.top - dy);
        else hideHero();
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        // Centred just above the dock's panel, which starts 14dp above its tiles.
        dockPanelTop = dockTopInRoot - 14 * density;
        caretDock.setY(dockPanelTop - 6 * density - caretDock.getLayoutParams().height);
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
        } else {
            heroName.setY(y);
        }
        showBackdrop(adapter.packageAt(position), color);
    }

    /**
     * The app's still behind the dock when it has one, otherwise its colour glow. Nothing changes until
     * the still has loaded, so the screen goes straight from one app's look to the next: the glow is
     * drawn above the stills and would otherwise flash over a picture while the next one loads.
     */
    private void showBackdrop(String pkg, int color) {
        if (pkg == null) {   // e.g. the All apps tile in the dock
            backdropPkg = null;
            fadeBackdrop(null, null, null, color);
            return;
        }
        if (pkg.equals(backdropPkg)) return;
        backdropPkg = pkg;
        backdropColor = color;
        backdrops.load(pkg, (loaded, bitmap, logo, title, link) -> {
            if (!heroShown || !loaded.equals(backdropPkg)) return;   // moved on while it loaded
            backdropLink = bitmap != null ? link : null;
            fadeBackdrop(bitmap, logo, title, color);
            if (pictureOnly) updateCarets();   // its first pictures may have come in meanwhile
        });
    }

    /** Shows a still (cross-fading from the one before, if any), or the glow in this colour. */
    private void fadeBackdrop(Bitmap bitmap, Bitmap logo, String title, int color) {
        stillShown = bitmap != null;
        if (bitmap == null) {
            backdrop.animate().alpha(0f).setDuration(300).withLayer().start();
            if (ambient.getAlpha() > 0f && color != ambientColor) {
                // Glow to glow: dip, change colour, come back.
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            } else {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(500).start();
            }
            return;
        }
        ambient.animate().alpha(0f).setDuration(300).start();
        if (backdrop.getAlpha() > 0f && backdropImage.getDrawable() != null) {
            // Picture to picture: the old one stays underneath while the new one fades in over it.
            backdropPrevious.setImageDrawable(backdropImage.getDrawable());
            backdropPrevious.setVisibility(View.VISIBLE);
            backdropImage.animate().cancel();
            backdropImage.setAlpha(0f);
            backdropImage.setImageBitmap(bitmap);
            backdropImage.animate().alpha(1f).setDuration(450).withEndAction(() -> {
                backdropPrevious.setImageDrawable(null);   // let the old picture go
                backdropPrevious.setVisibility(View.GONE);
            }).start();
            backdropTitleBox.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                showBackdropTitle(logo, title);
                backdropTitleBox.animate().alpha(1f).setDuration(300).start();
            }).start();
            // In case it was fading out. No layer: the picture inside is cross-fading, so a layer would be
            // redrawn on every frame anyway.
            backdrop.animate().alpha(1f).setDuration(200).start();
            return;
        }
        backdropImage.animate().cancel();
        backdropImage.setAlpha(1f);
        backdropImage.setImageBitmap(bitmap);
        backdropTitleBox.animate().cancel();
        backdropTitleBox.setAlpha(1f);
        showBackdropTitle(logo, title);
        backdrop.animate().alpha(1f).setDuration(450).withLayer().start();
    }

    /** The film or show's logo, else its name, at the right, level with the app name; nothing otherwise. */
    private void showBackdropTitle(Bitmap logo, String title) {
        backdropLogo.setImageBitmap(logo);
        backdropLogo.setVisibility(logo != null ? View.VISIBLE : View.GONE);
        backdropTitle.setText(title);
        backdropTitle.setVisibility(logo == null && title != null ? View.VISIBLE : View.GONE);
        backdropTitleBox.post(() -> backdropTitleBox.setY(
                heroName.getY() + heroName.getHeight() - backdropTitleBox.getHeight()));
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        stillShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
        backdrop.animate().alpha(0f).setDuration(300).withLayer().start();
        backdropPkg = null;
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {                      // dock row: the picture alone, then the top bar
                if (!stillShown) {                         // no picture (none, or not loaded yet): the top bar
                    cancelPictureSoon();
                    focusTopBar();
                    return true;
                }
                if (settings.autoHideBar()) {              // no top bar to scroll on to: the picture at once
                    showPictureOnly(SoundEffectConstants.NAVIGATION_UP);
                    return true;
                }
                if (pictureSoon) {                         // Up again straight away: scrolling up, to the top bar
                    cancelPictureSoon();
                    return false;
                }
                pictureSoon = true;
                pictureSoonKey = KeyEvent.KEYCODE_DPAD_UP;
                handler.postDelayed(showPictureSoon, PICTURE_DELAY_MS);
                return true;
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        // Handled here rather than by Android's focus search, which is what plays the click for left/right.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    // ---- the picture alone ---------------------------------------------------------------------------

    /**
     * Up from the dock: the app rows slide down out of sight and the top bar up (if it was showing),
     * leaving the focused app's picture (and its title's logo) alone on screen, and then the Play pill slides
     * in. Focus stays on the tile meanwhile; the keys are handled in dispatchKeyEvent.
     */
    private void showPictureOnly(int sound) {
        if (pictureOnly || rows.getHeight() == 0) return;
        pictureOnly = true;
        rowsAway = false;   // the picture alone keeps them away now
        if (sound >= 0) grid.playSoundEffect(sound);
        if (!slideshow) openCarousel(true);
        // The name and the glass don't move with the rows: they go with the carets, before anything moves.
        heroName.animate().alpha(0f).setDuration(CARET_FADE_MS).start();
        glass.animate().alpha(0f).setDuration(CARET_FADE_MS).start();   // the Play pill takes over
        transition(() -> Math.max(slide(rows, rowsDistance()), slide(topBar, -topBar.getHeight())));
    }

    /**
     * Brings the rows and the top bar back, sliding unless animate is false. To the top bar (Up again, as
     * Up from the dock used to go), else back on the tile (Down, Back).
     */
    private void showRows(boolean animate, boolean toTopBar) {
        if (!pictureOnly) return;
        pictureOnly = false;
        cancelHold();
        if (focusAfterSlide >= 0) {   // left before the tile was focused: back on it, unless going up again
            if (!toTopBar) focusTile(focusAfterSlide);
            focusAfterSlide = -1;
        }
        if (!animate) {
            rowsAway = false;
            handler.removeCallbacks(moveMenus);
            handler.removeCallbacks(caretsBack);
            handler.removeCallbacks(nameBack);
            caretsHeld = false;
            rows.animate().cancel();
            topBar.animate().cancel();
            rows.setTranslationY(0f);
            topBar.setTranslationY(barWanted() ? 0f : -topBar.getHeight());
            if (heroShown) heroName.setAlpha(1f);
            if (glassShown) glass.setAlpha(1f);
            updateCarets();
            return;
        }
        grid.playSoundEffect(toTopBar ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        // Focusing the tile above (Down again while the menus were leaving from the top bar) faded its glass
        // in at once, before the rows were back: it waits for them, like the name (showName).
        glass.animate().cancel();
        glass.setAlpha(0f);
        // Set to auto-hide, Up brings only the top bar: the rows stay down until focus comes back to them.
        boolean rowsStay = toTopBar && settings.autoHideBar();
        transition(() -> {
            // The top bar comes back for Up, or when it isn't set to hide; else it stays out of sight.
            float bar = toTopBar || !settings.autoHideBar() ? 0f : -topBar.getHeight();
            long ms = slide(topBar, bar);
            if (rowsStay) rowsAway = true;
            else ms = Math.max(ms, slide(rows, 0f));
            if (toTopBar) {
                View card = getCurrentFocus();
                View above = card != null ? card.focusSearch(View.FOCUS_UP) : null;
                if (above != null) above.requestFocus();   // its highlight comes down with the top bar
            }
            // The app's name and the tile's glass stay where they are; they show once the rows are back.
            if (!rowsStay) handler.postDelayed(nameBack, ms);
            return ms;
        });
    }

    /** The last step of a transition: the carets (and the play button) for the new view. */
    private void menusMoved() {
        if (pictureOnly && focusAfterSlide >= 0) {   // from the top bar: the tile, now out of sight
            focusTile(focusAfterSlide);
            quietHero(focusAfterSlide);
            focusAfterSlide = -1;
            glass.animate().cancel();   // its glass waits for the rows to come back
            glass.setAlpha(0f);
        }
        caretsHeld = false;
        updateCarets();
    }

    /**
     * The dock tile focused out of sight (with the picture alone): its name in place, but hidden until
     * the rows come back. Focusing it starts the name's swap animation, which only sets the new name at
     * its end; stopped here, the old name stayed.
     */
    private void quietHero(int position) {
        heroName.animate().cancel();
        heroName.setText(adapter.labelAt(position));
        heroName.setTranslationX(0f);
        heroName.setAlpha(0f);
    }

    /**
     * The carousel opens on what you've started watching (the last watched first) and says the time left;
     * jump false keeps the picture showing (the slideshow stopped on it).
     */
    private void openCarousel(boolean jump) {
        if (backdropPkg == null) return;
        playInfo.setVisibility(View.GONE);
        backdrops.openCarousel(backdropPkg, jump, false, (pkg, bitmap, logo, title, link) -> {
            if (!pictureOnly || !pkg.equals(backdropPkg)) return;
            if (bitmap != null) {
                backdropLink = link;
                fadeBackdrop(bitmap, logo, title, backdropColor);
            }
            updatePlayInfo();
        });
    }

    /** Focus is back on a tile while the rows were kept down: they come up, then its name and glass. */
    private void bringRowsBack() {
        rowsAway = false;
        // Focusing the tile just faded its glass and name in where the rows will be: they wait for the rows.
        glass.animate().cancel();
        glass.setAlpha(0f);
        quietHero(lastFocused);   // and its name set now: the name's swap animation stopped would leave the old
        handler.postDelayed(nameBack, slide(rows, 0f));
    }

    /** Back from the picture alone, once the rows are in place: the app's name and the tile's glass. */
    private void showName() {
        if (pictureOnly) return;   // gone back to the picture meanwhile
        if (heroShown) heroName.animate().alpha(1f).setDuration(250).start();
        if (glassShown && onDock()) glass.animate().alpha(1f).setDuration(250).start();
    }

    /** The menus' part of a transition: starts their movement and says how long it lasts. */
    private interface MenuMove {
        long start();
    }

    /**
     * Going to or from the picture alone, in three steps: the carets (and the Play pill) fade out first,
     * then the menus move, and only once they're out of the way do the carets for the new view fade in.
     */
    private void transition(MenuMove move) {
        caretsHeld = true;
        updateCarets();   // all out
        handler.removeCallbacks(moveMenus);
        handler.removeCallbacks(caretsBack);
        moveMenus = () -> handler.postDelayed(caretsBack, move.start());
        handler.postDelayed(moveMenus, CARET_FADE_MS);
    }

    /**
     * While the picture is alone: Up brings the rows back and goes to the top bar, Down and Back back to
     * the tile. Left and Right go through the app's pictures, round and back to the first; held, they switch
     * to the next dock app (switchApp). Holding OK fills
     * the Play pill and a long press opens the picture's title, and the remote's Play key the same; other
     * keys (volume, Home) as usual.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);   // volume as usual: it doesn't stop the slideshow, or count as use
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            lastKeyAt = SystemClock.uptimeMillis();
            armIdle();
            if (slideshow) {   // any other key: back to the picture alone, on the picture showing
                swallowUp = event.getKeyCode();
                endSlideshow();
                return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP && event.getKeyCode() == swallowUp) {
            swallowUp = -1;
            return true;
        }
        if (pictureSoon && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() != pictureSoonKey) {
            cancelPictureSoon();   // another key before the picture showed alone: it doesn't
        }
        if (!pictureOnly) return super.dispatchKeyEvent(event);
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    showRows(true, event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                // A press goes to the app's next picture, on release; held, to the next dock app instead.
                int direction = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) sideHeld = false;
                    else if (event.isLongPress() && !sideHeld) {
                        sideHeld = true;
                        switchApp(direction);
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    if (!sideHeld) stepPicture(direction);
                    sideHeld = false;
                }
                return true;
            }
            case KeyEvent.KEYCODE_MENU:
                return true;   // the focused tile is out of sight: no tile menu over the picture
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) startHold();
                    else if (event.isLongPress()) openHeldTitle();
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    releaseHold();
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    /** From the top bar: back on the dock tile Up came from, which the picture and play button go with. */
    private void showPictureFromBar() {
        pictureSoon = false;
        // Focus goes to the tile once the rows are out of sight (menusMoved): focused now, it would be seen
        // growing to its focused size on the way out.
        focusAfterSlide = lastFocused % COLUMNS;
        showPictureOnly(SoundEffectConstants.NAVIGATION_DOWN);
    }

    /** Up from the dock straight to the top bar: the pill above the tile, or the first. */
    private void focusTopBar() {
        View card = getCurrentFocus();
        View above = card != null ? card.focusSearch(View.FOCUS_UP) : null;
        View[] bar = topBarPills();
        if (!isPill(above) && bar.length > 0) above = bar[0];
        if (above == null) return;
        above.requestFocus();
        grid.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
    }

    private void cancelPictureSoon() {
        pictureSoon = false;
        handler.removeCallbacks(showPictureSoon);
        handler.removeCallbacks(showPictureFromBar);
    }

    /**
     * Settings → Reload backdrops: every pool and download goes, and the dock's pools are built again
     * from scratch, each app's first picture first. Until then the focused app shows its glow.
     */
    void reloadBackdrops() {
        backdrops.reload();
        backdropPkg = null;
        backdropLink = null;
        fadeBackdrop(null, null, null, backdropColor);
        refreshPools.run();
    }

    /** Just far enough down for the dock's panel, the rows' top, to leave the screen. */
    private float rowsDistance() {
        // Scrolled down the grid, the rows fill the screen: all the way. Else just past the dock's panel.
        if (grid.computeVerticalScrollOffset() > 0) return rows.getHeight();
        // 2dp more for the panel's edge, a stroke centred on its top, and its anti-aliasing.
        float d = rows.getHeight() - dockPanelTop + 2 * density;
        return d > 0 && dockPanelTop > 0 ? d : rows.getHeight();
    }

    /**
     * Slides the rows or the top bar to this offset. Both move at the same speed (the rows' distance in
     * PICTURE_SLIDE_MS), so the top bar, with less far to go, gets there sooner.
     */
    private long slide(View v, float to) {
        float speed = rowsDistance() / PICTURE_SLIDE_MS;   // px per ms
        long ms = Math.max(150, (long) (Math.abs(to - v.getTranslationY()) / speed));
        v.animate().translationY(to).setDuration(ms).setInterpolator(new AccelerateDecelerateInterpolator())
                .withLayer().start();
        return ms;
    }

    /**
     * The carets that show where the remote goes. With the picture alone, on the screen's edges: up (the
     * top bar), down (back to the apps), and left and right when the app has another picture. Otherwise
     * one right above the dock while on it, and one just under the top bar while on it: both lead to the
     * picture alone. None on the lower rows.
     */
    private void updateCarets() {
        // None while the menus move, in the slideshow, or under a menu or dialog (Home hasn't the focus).
        boolean on = !caretsHeld && !slideshow && hasWindowFocus();
        boolean more = on && pictureOnly && backdropPkg != null && backdrops.stillCount(backdropPkg) > 1;
        showCaret(caretDock, on && !pictureOnly && onDock());
        caretBar.setY(topBar.getHeight() + 4 * density);   // just under the bar
        showCaret(caretBar, on && !pictureOnly && onTopBar());
        showCaret(caretUp, on && pictureOnly);
        showCaret(caretDown, on && pictureOnly);
        showCaret(caretLeft, more);
        showCaret(caretRight, more);
        showPlay(on && pictureOnly);
    }

    private static void showCaret(View caret, boolean show) {
        caret.animate().alpha(show ? CARET_ALPHA : 0f).setDuration(show ? 300 : CARET_FADE_MS).start();
    }

    /**
     * The Play pill with the picture alone (see placePlay for where), sliding in from off the screen's left
     * edge, and back out the same way.
     */
    private void showPlay(boolean show) {
        if (show == playShown) return;
        playShown = show;
        if (show) placePlay();
        // Just off the screen's left edge: from there it slides right into place, and back out the same way.
        // No fade: it's solid all the way.
        float offScreen = -(((ViewGroup.MarginLayoutParams) holdPlay.getLayoutParams()).leftMargin
                + holdPlay.getWidth());
        holdPlay.animate().cancel();
        holdPlay.setAlpha(1f);
        if (show) {
            // From off screen, or from wherever a slide out has got to.
            if (holdPlay.getVisibility() != View.VISIBLE) {
                holdPlay.setTranslationX(offScreen);
                holdPlay.setVisibility(View.VISIBLE);
            }
            holdPlay.animate().translationX(0f).setDuration(PLAY_SLIDE_MS)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        } else {
            // Gone once out: the time left can arrive after the slide started, widening the pill past
            // where it was sent, and its end would stay on screen.
            holdPlay.animate().translationX(offScreen).setDuration(PLAY_SLIDE_MS)
                    .setInterpolator(new AccelerateInterpolator(2f))
                    .withEndAction(() -> holdPlay.setVisibility(View.INVISIBLE)).start();
        }
    }

    /**
     * Where the Play pill goes: its left edge level with the first tile's, its bottom edge 12dp above where
     * the dock's band is when the rows show. Both from the rows' resting place, so it's the same however the
     * picture alone was reached (from the dock, or down from the top bar while the rows were still away).
     */
    private void placePlay() {
        View pill = (View) holdPlayFill.getParent();
        if (dockPanelTop > 0) holdPlay.setY(dockPanelTop - 12 * density - pill.getHeight());
        RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(0);
        if (first instanceof AppAdapter.Holder) {
            View card = ((AppAdapter.Holder) first).card;
            Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
            root.offsetDescendantRectToMyCoords(card, r);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holdPlay.getLayoutParams();
            if (lp.leftMargin != r.left) {
                lp.leftMargin = r.left;
                lp.setMarginStart(r.left);
                holdPlay.setLayoutParams(lp);
            }
        }
    }

    /** Under the Play pill: "45 minutes remaining" when you're part-way through the picture's title. */
    private void updatePlayInfo() {
        long left = backdrops.remainingMs(backdropLink);
        if (left <= 0) {
            playInfo.setVisibility(View.GONE);
            return;
        }
        long minutes = Math.max(1, (left + 59_999) / 60_000);   // rounded up: never "0 minutes"
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        // "1 hour, 20 minutes", "45 minutes", in the language Home is in.
        MeasureFormat format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE);
        Measure hours = new Measure(minutes / 60, MeasureUnit.HOUR), rest = new Measure(minutes % 60, MeasureUnit.MINUTE);
        String time = minutes < 60 ? format.format(rest)
                : minutes % 60 == 0 ? format.format(hours) : format.formatMeasures(hours, rest);
        playInfo.setText(getString(R.string.time_remaining, time));
        playInfo.setVisibility(View.VISIBLE);
    }

    /**
     * Whether the top bar should show: always, unless set to hide (Settings, on by default). Then only
     * while it has focus or the grid below the dock does; not on the dock or with the picture alone.
     */
    private boolean barWanted() {
        if (!settings.autoHideBar()) return true;
        if (pictureOnly || slideshow) return false;
        return onTopBar() || onLowerRows();
    }

    /** Slides the top bar in or out as barWanted says; called as focus moves and when the setting changes. */
    void updateTopBar() {
        if (pictureOnly || caretsHeld) return;   // a transition moves it
        if (topBar.getHeight() == 0) {
            topBar.post(this::updateTopBar);   // not laid out yet
            return;
        }
        float to = barWanted() ? 0f : -topBar.getHeight();
        if (topBar.getTranslationY() == to) return;
        topBar.animate().translationY(to).setDuration(BAR_SLIDE_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator()).withLayer().start();
    }

    private boolean onLowerRows() {
        View focused = getCurrentFocus();
        if (focused == null || focused.getId() != R.id.card) return false;
        RecyclerView.ViewHolder holder = grid.findContainingViewHolder(focused);
        return holder != null && holder.getBindingAdapterPosition() >= COLUMNS;
    }

    private boolean onTopBar() {
        return isPill(getCurrentFocus());
    }

    private static boolean isPill(View v) {
        if (v == null) return false;
        int id = v.getId();
        return id == R.id.btnInputs || id == R.id.btnWifi || id == R.id.btnSettings;
    }

    private boolean onDock() {
        View focused = getCurrentFocus();
        if (focused == null || focused.getId() != R.id.card) return false;
        RecyclerView.ViewHolder holder = grid.findContainingViewHolder(focused);
        int position = holder != null ? holder.getBindingAdapterPosition() : RecyclerView.NO_POSITION;
        return position != RecyclerView.NO_POSITION && position < COLUMNS;
    }

    /**
     * OK pressed with the picture alone: the Play pill fills over Android's long-press time. Held that
     * long, the long press opens the title (see openHeldTitle); let go sooner, it drains.
     */
    private void startHold() {
        if (holdFill != null) return;
        holdOpened = false;
        holdFill = ValueAnimator.ofFloat(0f, 1f);
        holdFill.setDuration(ViewConfiguration.getLongPressTimeout());
        holdFill.setInterpolator(null);   // linear: an even fill shows how long is left
        holdFill.addUpdateListener(a -> setHoldFill((float) a.getAnimatedValue()));
        holdFill.start();
    }

    /**
     * OK held for a long press: the pill fills the rest of the way, then the picture's title opens. The
     * long press can come before the fill has drawn (a remote that sends it at once), so it always shows.
     */
    private void openHeldTitle() {
        if (holdOpened) return;
        holdOpened = true;
        float at = holdFill != null ? (float) holdFill.getAnimatedValue() : 0f;
        if (holdFill != null) {
            holdFill.cancel();
            holdFill = null;
        }
        String link = backdropLink, pkg = backdropPkg;
        ValueAnimator finish = ValueAnimator.ofFloat(at, 1f);
        finish.setDuration((long) (HOLD_FINISH_MS * (1f - at)));
        finish.addUpdateListener(a -> setHoldFill((float) a.getAnimatedValue()));
        finish.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (pictureOnly) openTitle(link, pkg);   // still there: not left with Home meanwhile
            }
        });
        finish.start();
    }

    /**
     * How much of the Play pill shows its focused look, from 0 to 1: revealed from the side the
     * language reads from (the right for Arabic, Hebrew, Persian and Urdu).
     */
    private void setHoldFill(float fill) {
        int w = holdPlayFill.getWidth(), h = holdPlayFill.getHeight();
        int filled = Math.round(w * Math.max(0f, Math.min(1f, fill)));
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        boolean rtl = TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL;
        holdPlayFill.setClipBounds(rtl ? new Rect(w - filled, 0, w, h) : new Rect(0, 0, filled, h));
    }

    /** OK let go before the button is full: it drains, and nothing opens. */
    private void releaseHold() {
        if (holdFill == null) return;
        float at = (float) holdFill.getAnimatedValue();
        holdFill.cancel();
        holdFill = null;
        ValueAnimator drain = ValueAnimator.ofFloat(at, 0f);
        drain.setDuration((long) (250 * at));
        drain.addUpdateListener(a -> setHoldFill((float) a.getAnimatedValue()));
        drain.start();
    }

    private void cancelHold() {
        if (holdFill != null) {
            holdFill.cancel();
            holdFill = null;
        }
        setHoldFill(0f);
    }

    /**
     * Opens the picture's title in the app that was selected (pkg): its card's own link, as the stock
     * launcher opens it, or a YouTube video's address. A link made for another app (YouTube's pictures
     * come from SmartTube's feed) goes to the selected app instead, by its address. Without a link (a wall,
     * Plex, HDHomeRun), or if the selected app can't open it, the app itself opens; never a different one.
     */
    private void openTitle(String link, String pkg) {
        try {
            if (link == null) throw new ActivityNotFoundException();   // no title to open: the app
            Intent i = Intent.parseUri(link, Intent.URI_INTENT_SCHEME);
            if (pkg != null) {
                ComponentName target = i.getComponent();
                String to = target != null ? target.getPackageName() : i.getPackage();
                if (!pkg.equals(to)) {
                    i.setComponent(null);
                    i.setSelector(null);
                    i.setPackage(pkg);
                    if (i.getAction() == null) i.setAction(Intent.ACTION_VIEW);
                }
            }
            if (i.resolveActivity(getPackageManager()) == null) throw new ActivityNotFoundException();
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (URISyntaxException | ActivityNotFoundException | SecurityException e) {
            Intent app = pkg == null ? null : getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (app == null && pkg != null) app = getPackageManager().getLaunchIntentForPackage(pkg);
            if (app != null) open(app);
            else Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        // The pill stays full while the title opens; it empties on the way back (onStart, showRows).
    }

    // ---- the slideshow ---------------------------------------------------------------------------------

    /** Starts (again) the wait for the slideshow. */
    private void armIdle() {
        handler.removeCallbacks(startSlideshow);
        if (started) handler.postDelayed(startSlideshow, IDLE_MS);
    }

    /** The dock's apps: their pools are the slideshow's pictures. */
    private List<String> dockApps() {
        List<String> dock = new ArrayList<>();
        for (int i = 0; i < COLUMNS; i++) {
            String pkg = adapter.packageAt(i);
            if (pkg != null) dock.add(pkg);
        }
        return dock;
    }

    /**
     * Left alone on Home: the rows and the top bar go, as for the picture alone, and the pictures of every
     * dock app follow each other at random with nothing over them. Not while a menu, Settings or a tile's
     * move is open (Home hasn't the window's focus, or a tile is moving): then the wait starts over.
     */
    private void startSlideshow() {
        if (slideshow || !started || !hasWindowFocus() || adapter.isMoving() || dockApps().isEmpty()) {
            armIdle();
            return;
        }
        slideshow = true;
        slidePkg = backdropPkg;
        slideEntry = null;
        glass.animate().alpha(0f).setDuration(CARET_FADE_MS).start();
        showSlideClock(pictureOnly ? 0 : PICTURE_SLIDE_MS);   // once the top bar, with its clock, is away
        if (!pictureOnly) {
            if (!onDock()) focusAfterSlide = lastFocused % COLUMNS;   // the top bar or a lower row
            showPictureOnly(-1);
        } else {
            updateCarets();   // the carets and play button go
        }
        // The screen stays on for it, but only as long as the TV would have left it on anyway.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        long screenOff = Settings.System.getLong(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                30 * 60_000L);
        handler.removeCallbacks(letScreenOff);
        handler.postAtTime(letScreenOff, lastKeyAt + Math.max(IDLE_MS, screenOff));
        handler.postDelayed(nextSlide, PICTURE_SLIDE_MS + CARET_FADE_MS);
    }

    /**
     * The top bar's time and AM/PM, alone on the slideshow: in the same place as on the bar (where the bar
     * is when showing, whatever it's doing now), and not until the TV knows the time, like the bar's. Unless
     * turned off in Settings.
     */
    private void showSlideClock(long delay) {
        if (!settings.slideshowClock()) return;   // Settings > Appearance
        View box = findViewById(R.id.timeBox);
        if (findViewById(R.id.clockRow).getVisibility() != View.VISIBLE || box.getWidth() == 0) return;
        Rect r = new Rect(0, 0, box.getWidth(), box.getHeight());
        root.offsetDescendantRectToMyCoords(box, r);   // layout positions: the bar's slide doesn't count
        slideClock.setX(r.left);
        slideClock.setY(r.top);
        slideClock.animate().cancel();
        slideClock.setAlpha(0f);
        slideClock.setVisibility(View.VISIBLE);
        slideClock.animate().alpha(1f).setStartDelay(delay).setDuration(400).start();
    }

    private void nextSlide() {
        if (!slideshow) return;
        backdrops.randomSlide(dockApps(), slideEntry, (pkg, entry, bitmap, logo) -> {
            if (!slideshow) return;
            slidePkg = pkg;
            slideEntry = entry;
            fadeBackdrop(bitmap, logo, entry.title, backdropColor);
        });
        handler.postDelayed(nextSlide, SLIDE_MS);
    }

    /**
     * A key in the slideshow: it stops on the picture showing, with that picture's app as the one picked,
     * so the Play pill opens it.
     */
    private void endSlideshow() {
        slideshow = false;
        slideClock.animate().cancel();
        slideClock.animate().alpha(0f).setStartDelay(0).setDuration(CARET_FADE_MS)
                .withEndAction(() -> slideClock.setVisibility(View.INVISIBLE)).start();
        handler.removeCallbacks(nextSlide);
        handler.removeCallbacks(letScreenOff);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int at = slidePkg != null ? dockApps().indexOf(slidePkg) : -1;
        if (slideEntry != null && at >= 0 && slidePkg.equals(adapter.packageAt(at))) {
            backdrops.keep(slidePkg, slideEntry);
            backdropPkg = slidePkg;   // already showing: focusing its tile doesn't load another
            backdropLink = slideEntry.link;
            backdropColor = adapter.ambientColorAt(at);
            focusAfterSlide = -1;
            focusTile(at);
            quietHero(at);
            glass.animate().cancel();   // the Play pill, not the glass, with the picture alone
            glass.setAlpha(0f);
        }
        if (pictureOnly && !caretsHeld) updateCarets();
        if (pictureOnly) openCarousel(false);   // the stopped-on app's carousel, and the time left on it
        armIdle();
    }

    /**
     * Left or Right held with the picture alone: the next or previous dock app, going round from the last to
     * the first (the dock itself stops at its ends). Its tile takes focus out of sight, name and all, so the
     * rows come back on it; the carousel opens on what you're watching in it, else this visit's picture.
     */
    private void switchApp(int direction) {
        List<String> dock = dockApps();
        if (dock.size() < 2) return;
        int at = Math.max(0, Math.min(dock.size() - 1, lastFocused % COLUMNS));
        int next = ((at + direction) % dock.size() + dock.size()) % dock.size();
        String pkg = dock.get(next);
        cancelHold();
        backdropPkg = pkg;   // set first: focusing its tile then doesn't load a picture of its own
        backdropColor = adapter.ambientColorAt(next);
        backdropLink = null;
        focusTile(next);
        quietHero(next);
        glass.animate().cancel();
        glass.setAlpha(0f);
        playInfo.setVisibility(View.GONE);
        grid.playSoundEffect(direction > 0
                ? SoundEffectConstants.NAVIGATION_RIGHT : SoundEffectConstants.NAVIGATION_LEFT);
        backdrops.openCarousel(pkg, true, true, (loaded, bitmap, logo, title, link) -> {
            if (!pictureOnly || !loaded.equals(backdropPkg)) return;
            backdropLink = link;
            fadeBackdrop(bitmap, logo, title, backdropColor);
            updatePlayInfo();
            updateCarets();
        });
    }

    /** The focused app's next or previous picture, cross-faded in. */
    private void stepPicture(int direction) {
        if (backdropPkg == null) return;
        backdrops.step(backdropPkg, direction, (loaded, bitmap, logo, title, link) -> {
            if (!pictureOnly || !loaded.equals(backdropPkg)) return;   // back to the rows meanwhile
            cancelHold();
            backdropLink = link;
            fadeBackdrop(bitmap, logo, title, backdropColor);
            updatePlayInfo();
            updateCarets();
        });
        grid.playSoundEffect(direction > 0
                ? SoundEffectConstants.NAVIGATION_RIGHT : SoundEffectConstants.NAVIGATION_LEFT);
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /**
     * TVs without a clock battery start in 1970 until network time arrives, and Home showed that date. The
     * clock and date stay hidden (keeping their space) until the time is plausible, checked once a second.
     */
    private void showClockOnceTimeIsSet(View clockRow) {
        if (System.currentTimeMillis() > TIME_SET_AFTER) {
            clockRow.setVisibility(View.VISIBLE);
            return;
        }
        clockRow.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> showClockOnceTimeIsSet(clockRow), 1000);
    }

    /**
     * Other languages put the weekday, day, month and year in their own order ("2026年10月20日 星期二");
     * English keeps the launcher's own "TUESDAY  20 OCTOBER 2026".
     */
    static void localDate(TextClock date, String skeleton) {
        Locale locale = Locale.getDefault();
        if (locale.getLanguage().equals("en")) return;
        String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
    }

    /**
     * The time box is as wide as the widest time with as many characters as the one showing ("8:08" from 1:00
     * to 9:59, "10:08" from 10:00), with the wider of AM and PM: the digits differ in width, and the date
     * beside it would shift from 1:40 to 1:41. Sized for "10:00" all day, it left a wide gap after 1:11; this
     * way the date moves only when the hour gains or loses a digit (in 24-hour time, never).
     */
    private void sizeTimeBox() {
        TextClock clock = findViewById(R.id.clock), ampm = findViewById(R.id.ampm);
        boolean h24 = DateFormat.is24HourFormat(this);
        // Measuring every minute of the day takes a moment: only again when the format or language changed.
        String measuredFor = h24 + "|" + Locale.getDefault();
        if (measuredFor.equals(timeBoxMeasuredFor)) return;
        timeBoxMeasuredFor = measuredFor;
        CharSequence format = h24 ? clock.getFormat24Hour() : clock.getFormat12Hour();
        float half = 0;
        if (!h24) {
            for (String s : new DateFormatSymbols().getAmPmStrings()) {
                half = Math.max(half, ampm.getPaint().measureText(s.toUpperCase(Locale.getDefault())));
            }
        }
        float extra = ((ViewGroup.MarginLayoutParams) ampm.getLayoutParams()).getMarginStart() + half;
        Calendar time = Calendar.getInstance();
        timeBoxWidths.clear();
        for (int minute = 0; minute < 24 * 60; minute++) {
            time.set(Calendar.HOUR_OF_DAY, minute / 60);
            time.set(Calendar.MINUTE, minute % 60);
            String s = DateFormat.format(format, time).toString();
            int w = (int) Math.ceil(clock.getPaint().measureText(s) + extra);
            Integer known = timeBoxWidths.get(s.length());
            if (known == null || w > known) timeBoxWidths.put(s.length(), w);
        }
        fitTimeBox(clock.getText());
    }

    /** Applies the width sizeTimeBox worked out for times as long as this one. */
    private void fitTimeBox(CharSequence shown) {
        Integer w = timeBoxWidths.get(shown.length());
        View box = findViewById(R.id.timeBox);
        if (w != null && box.getMinimumWidth() != w) box.setMinimumWidth(w);
    }

    /**
     * One line of date sits level with AM. When it wraps onto two (a long network name narrows the space,
     * or a long language), the pair is centred on the clock's digits instead: kept level with AM, the
     * second line pulled the block below the digits' middle.
     */
    private void centreTwoLineDate(TextView clock, TextView date) {
        Layout lines = date.getLayout();
        if (lines == null || lines.getLineCount() < 2) {
            date.setTranslationY(0f);
            return;
        }
        Rect cap = new Rect();
        clock.getPaint().getTextBounds("0", 0, 1, cap);   // top is negative: the digit's height above the baseline
        // The clock sits in the time box, beside the date: offset by the box's own top.
        float digits = ((View) clock.getParent()).getTop() + clock.getTop() + clock.getBaseline() + cap.exactCenterY();
        date.getPaint().getTextBounds("D", 0, 1, cap);
        float firstTop = date.getTop() + date.getTotalPaddingTop() + lines.getLineBaseline(0) + cap.top;
        float lastBaseline = date.getTop() + date.getTotalPaddingTop() + lines.getLineBaseline(lines.getLineCount() - 1);
        date.setTranslationY(digits - (firstTop + lastBaseline) / 2f);
    }

    /**
     * Tiles per row when none was chosen in Settings: 4 on screens under 51", 5 up to 74", 6 from there.
     * config.xml's when the size isn't known.
     */
    private int defaultColumns() {
        double inches = screenInches();
        if (inches == 0) return Math.max(1, getResources().getInteger(R.integer.columns));
        return inches < 51 ? 4 : inches < 74 ? 5 : 6;
    }

    /**
     * The screen's diagonal in inches, from the physical dots per inch the TV reports (a 75" Sony: 29.6),
     * or 0 when that isn't believable. A box over HDMI can't measure the TV and reports a made-up figure
     * (the Shield: 213 dpi, which would make any TV 21").
     */
    @SuppressWarnings("deprecation")
    private double screenInches() {
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        if (m.xdpi < 10 || m.xdpi > 100 || m.ydpi < 10 || m.ydpi > 100) return 0;   // TVs are about 15 to 70
        double w = m.widthPixels / m.xdpi, h = m.heightPixels / m.ydpi;
        double inches = Math.sqrt(w * w + h * h);
        return inches >= 20 && inches <= 150 ? inches : 0;
    }

    /**
     * Granted at install over adb (-g). Installed from a file manager or a store instead, ask once for both:
     * TV listings (the apps' home screen cards, for backdrops) and location (Android only reveals the
     * Wi-Fi network's name with it). A note says why first, since "location" on a TV is puzzling. Refused,
     * the launcher keeps the colour glow and shows "Wi-Fi".
     */
    private boolean askPermissionsOnce() {
        if (getPreferences(MODE_PRIVATE).getBoolean("askedPermissions", false)) return false;
        List<String> missing = new ArrayList<>();
        // Coarse with fine: Android 12+ refuses a request for fine location alone.
        for (String p : new String[]{Backdrops.PERMISSION, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) return false;
        getPreferences(MODE_PRIVATE).edit().putBoolean("askedPermissions", true).apply();
        showNotice(R.string.permissions_title, getString(R.string.permissions_why), R.string.continue_button,
                () -> requestPermissions(missing.toArray(new String[0]), 1), this::askHomeOnce);
        return true;
    }

    /**
     * Opened as an app rather than as Home (installed from a file, no computer): offer once to become the
     * default Home where the TV has a way to (see DefaultHome). The gear menu offers it again later.
     */
    private void askHomeOnce() {
        if (getPreferences(MODE_PRIVATE).getBoolean("askedHome", false) || DefaultHome.isHome(this)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("askedHome", true).apply();
        // The store build, on its first start: set up the Home button now, while the user is here to read it.
        if (BuildConfig.STORE) {
            if (!DefaultHome.homeButtonOn(this)) makeHomeStore();
            return;
        }
        if (DefaultHome.request(this) != null) makeHome();
    }

    /** The system's own screen for choosing the default Home, or a note that this TV has none. */
    private void makeHome() {
        Intent request = DefaultHome.request(this);
        if (request == null) {
            Toast.makeText(this, R.string.home_not_changeable, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            // The Android 10+ dialog answers with a result; Android 9's chooser is plain Home.
            if (Build.VERSION.SDK_INT >= 29) startActivityForResult(request, 2);
            else startActivity(request);
        } catch (ActivityNotFoundException | SecurityException e) {
            // The store build has another way: the Home button service.
            if (BuildConfig.STORE) showHomeKeyDisclosure();
            else Toast.makeText(this, R.string.home_not_changeable, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The store build: Android's own dialog for the default Home first, which needs nothing more, where the TV
     * has it. Without it, or turned down (onActivityResult), the Home button service.
     */
    private void makeHomeStore() {
        // Asked only where it can work: where the stock launcher outranks any choice, straight to the service.
        if (DefaultHome.choiceWins(this) && DefaultHome.request(this) != null) makeHome();
        else showHomeKeyDisclosure();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2 && BuildConfig.STORE && !DefaultHome.isHome(this) && !DefaultHome.homeButtonOn(this)) {
            showHomeKeyDisclosure();
        }
    }

    /**
     * Says what the Home button service sees, and that nothing leaves the TV, before the user is sent to turn
     * it on: app stores ask for this up front for any accessibility service that isn't an accessibility tool.
     */
    private void showHomeKeyDisclosure() {
        // What it does, then what to do in Settings: the user reads it here, before the list opens.
        String text = getString(R.string.home_key_disclosure) + "\n\n"
                + getString(R.string.home_key_steps, getString(R.string.home_key_name));
        showNotice(R.string.home_key_disclosure_title, text, R.string.home_key_agree, this::turnOnHomeButton, null);
    }

    /** A note with OK (named okLabel) and Cancel, in the update dialog's glass. Back counts as Cancel. */
    private void showNotice(int title, CharSequence text, int okLabel, Runnable ok, Runnable cancel) {
        View panel = getLayoutInflater().inflate(R.layout.dialog_update, root, false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(title);
        ((TextView) panel.findViewById(R.id.notes)).setText(text);
        TextView okButton = panel.findViewById(R.id.install);
        okButton.setText(okLabel);
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (560 * getResources().getDisplayMetrics().density), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        boolean[] chose = {false};
        panel.findViewById(R.id.cancelUpdate).setOnClickListener(v -> dialog.dismiss());
        okButton.setOnClickListener(v -> {
            chose[0] = true;
            dialog.dismiss();
            ok.run();
        });
        dialog.setOnDismissListener(d -> {
            if (!chose[0] && cancel != null) cancel.run();
        });
        dialog.show();
        okButton.requestFocus();
    }

    /** The TV's accessibility settings, where the user turns on the Home button. */
    private void turnOnHomeButton() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, getString(R.string.home_key_turn_on, getString(R.string.home_key_name)), Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.home_not_changeable, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        // Show what the new permissions unlock straight away: the network name and this visit's backdrops.
        updateWifiLabel();
        backdrops.reshuffle();
        backdropPkg = null;   // onResume, which follows the permission dialog, loads it again
        rebuildOnResume = true;   // the dialog only paused Home
        askHomeOnce();
    }

    /**
     * Whether any app changed since the last check, by PackageManager's change counter (one binder call): a
     * package broadcast can be missed, e.g. while Home's process was frozen. Moves the counter on.
     */
    private boolean appsChangedUnseen() {
        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);
        if (changed == null) return false;
        packageSequence = changed.getSequenceNumber();
        return true;
    }

    /** Runs the pill refresh just after the next frame is drawn; the Inputs pill only back from an app. */
    private void refreshPillsAfterNextDraw(boolean inputs) {
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame. A post straight from
                // onResume would run first: the frame is only scheduled once onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    if (inputs) updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            // The pill names the physical connection; a VPN on top of it shows in the dropdown.
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) caps = underlying(cm);
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                String ssid = wifiName();
                if (ssid != null) text = ssid;
            }
        } catch (Exception ignored) {
        }
        // Only what changed: setting the same text or icon again lays the top bar out again.
        if (!TextUtils.equals(btnWifi.getText(), text)) btnWifi.setText(text);
        if (icon != wifiIcon) {
            wifiIcon = icon;
            // The style's drawableTint also applies to an icon swapped in here.
            btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        }
    }

    /** The Ethernet (preferred) or Wi-Fi connection under a VPN, or null. */
    private static NetworkCapabilities underlying(ConnectivityManager cm) {
        NetworkCapabilities wifi = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return c;
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = c;
        }
        return wifi;
    }

    /** The connected Wi-Fi network's name, or null when it can't be read. */
    private String wifiName() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        String ssid = info != null ? info.getSSID() : null;
        if (ssid == null) return null;
        ssid = ssid.replace("\"", "").trim();
        return ssid.isEmpty() || ssid.contains("unknown") || ssid.equals("0x") ? null : ssid;
    }

    /** The connected Wi-Fi's link speed in Mbps, or -1 when unknown. */
    private int wifiLinkSpeed() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        return info != null ? info.getLinkSpeed() : -1;
    }

    /**
     * Dropdown under the network pill: each connected interface with its IPv4 address (and Wi-Fi's link
     * speed), then settings.
     */
    private void showNetwork() {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);

        // Which device this is (Settings > About), to tell the TVs and the Shield apart.
        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        name = name == null || name.trim().isEmpty() ? Build.MODEL : name.trim();
        addInfoRow(rows, R.drawable.ic_livetv, name, name.equalsIgnoreCase(Build.MODEL) ? null : Build.MODEL);
        addSeparator(rows);
        int firstNetworkRow = rows.getChildCount();

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
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
                    ip = lp.getLinkAddresses().isEmpty() ? getString(R.string.connected)
                            : lp.getLinkAddresses().get(0).getAddress().getHostAddress();
                }
                if (ip == null) continue;
                String type;
                int icon = R.drawable.ic_wifi;
                // VPN first: Android often also marks a VPN with the Wi-Fi or Ethernet it runs over.
                if (vpn) {
                    type = getString(R.string.vpn_connected);
                    icon = R.drawable.ic_lock;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    type = getString(R.string.ethernet);
                    icon = R.drawable.ic_ethernet;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    String ssid = wifiName();
                    type = ssid != null ? getString(R.string.wifi) + " \u00B7 " + ssid : getString(R.string.wifi);
                    // The rate the Wi-Fi link runs at now. Ethernet's isn't readable by apps, so it shows none.
                    int mbps = wifiLinkSpeed();
                    if (mbps > 0) ip += " \u00B7 " + mbps + " Mbps";
                } else {
                    type = getString(R.string.network);
                }
                String iface = lp.getInterfaceName();
                addInfoRow(rows, icon, iface != null ? type + " (" + iface + ")" : type, ip);
            }
        }
        if (rows.getChildCount() == firstNetworkRow) {
            addInfoRow(rows, R.drawable.ic_wifi, getString(R.string.offline), null);
        }
        addSeparator(rows);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.network_settings), -1,
                () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        showMenu(popup, panel, rows, btnWifi, Gravity.END);
    }

    /** Dropdown under the gear: the launcher's own settings, or the system's. */
    private void showSettingsMenu(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);
        // Usage on top, like the device and addresses in the network menu.
        // CPU only where the device lets apps read the counters (not on Android 8+ TVs like ours).
        CpuInfo cpuInfo = new CpuInfo();
        boolean cpuLive = cpuInfo.canMeasure();
        View cpu = cpuLive ? addMeterRow(rows, getString(R.string.cpu)) : null;
        View ram = addMeterRow(rows, getString(R.string.ram));
        addSeparator(rows);
        // The last check's answer at once, then ask GitHub again: a release out since then replaces it in
        // this row, or shows the row. Settings → General, opened from here, shows the same answer. The row is
        // always there (hidden without an update) so showMenu gives it the menu bar's Left and Right.
        // The store build: a row only while Play has a newer version; it opens Play's update screen.
        if (BuildConfig.STORE && StoreUpdates.available()) {
            addInputRow(rows, popup, R.drawable.ic_update, getString(R.string.store_update), -1,
                    () -> StoreUpdates.open(this));
        }
        if (!BuildConfig.STORE) {
            Updater.Release[] update = {Updater.available(this)};
            View updateRow = addInputRow(rows, popup, R.drawable.ic_update, "", -1,
                    () -> SettingsScreen.startUpdate(this, update[0]));
            Runnable showUpdate = () -> {
                updateRow.setVisibility(update[0] != null ? View.VISIBLE : View.GONE);
                if (update[0] != null) {
                    ((TextView) updateRow.findViewById(R.id.title)).setText(getString(R.string.update_to, update[0].version));
                }
            };
            showUpdate.run();
            // At most every few minutes: GitHub allows 60 unsigned API calls an hour per address, shared by every
            // TV in the house, and this menu is opened often (it shows the memory meter).
            Updater.checkIfOlderThan(this, 5 * 60 * 1000L, (newer, failed) -> {
                showUpdateReady();
                if (newer == null || !popup.isShowing()) return;
                update[0] = newer;
                showUpdate.run();
            });
        }
        addInputRow(rows, popup, R.drawable.ic_all_apps, getString(R.string.vibe_settings), -1,
                this::openSettings);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.android_settings), -1,
                () -> open(new Intent(Settings.ACTION_SETTINGS)));
        if (BuildConfig.STORE && !DefaultHome.isHome(this) && !DefaultHome.homeButtonOn(this)) {
            // The stock launcher stays on: the TV's default-Home dialog where it has one, else HomeKeyService.
            addInputRow(rows, popup, R.drawable.ic_home, getString(R.string.make_home), -1, this::makeHomeStore);
        } else if (!BuildConfig.STORE && !DefaultHome.isHome(this)) {
            addInputRow(rows, popup, R.drawable.ic_home, getString(R.string.make_home), -1, this::makeHome);
        }
        // Live while the menu is open.
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (cpuLive) updateCpu(cpu, cpuInfo.usage());
                updateRam(ram);
                handler.postDelayed(this, 1000);
            }
        };
        tick.run();
        popup.setOnDismissListener(() -> handler.removeCallbacks(tick));
        showMenu(popup, panel, rows, anchor, Gravity.END);
    }

    /** The green dot in the Settings gear: shown while a newer release is waiting to be installed. */
    /** Vibe Launcher settings: their own screen over Home, made ahead (SettingsScreen), fading in. */
    private void openSettings() {
        inSettings = true;
        startActivity(new Intent(this, SettingsActivity.class));
        overridePendingTransition(R.anim.settings_in, R.anim.settings_hold);
    }

    void showUpdateReady() {
        boolean ready = BuildConfig.STORE ? StoreUpdates.available() : Updater.available(this) != null;
        findViewById(R.id.updateDot).setVisibility(ready ? View.VISIBLE : View.GONE);
    }

    /** A read-only row with a usage bar; fill it with the returned view. */
    private View addMeterRow(LinearLayout rows, String label) {
        View row = getLayoutInflater().inflate(R.layout.item_meter, rows, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        row.setFocusable(false);
        rows.addView(row);
        return row;
    }

    private void updateCpu(View row, float usage) {
        if (usage < 0) return;
        ((ProgressBar) row.findViewById(R.id.bar)).setProgress((int) (1000 * usage));
        ((TextView) row.findViewById(R.id.value)).setText(getString(R.string.percent, Math.round(100 * usage)));
    }

    /** Memory in use: total minus what the system says it can hand out without killing apps. */
    private void updateRam(View row) {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
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
        ((TextView) row.findViewById(R.id.value)).setText(getString(R.string.ram_value, used / gb, mi.totalMem / gb));
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Hides the Inputs pill on devices with nothing to switch to (e.g. a Shield: no HDMI-in, no tuner). */
    private void updateInputsButton() {
        boolean any = getLiveTvIntent() != null;
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && !info.isHidden(this)) {   // what the menu would list
                    any = true;
                    break;
                }
            }
        }
        findViewById(R.id.btnInputs).setVisibility(any ? View.VISIBLE : View.GONE);
    }

    private Intent getLiveTvIntent() {
        Intent launch = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (launch == null) return null;
        // Launched plainly, the TV app resumes the last source, often an HDMI input (seen on the
        // XBR-940E). Ask it for the built-in tuner instead, when the TV has one.
        String tuner = builtInTuner();
        return tuner == null ? launch : new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tuner), "vnd.android.cursor.item/channel")
                .setPackage(liveTvPkg);
    }

    /** The TV's own tuner input (a system app, not e.g. an HDHomeRun app), or null. */
    private String builtInTuner() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        String tuner = null;
        if (tim == null) return null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() == TvInputInfo.TYPE_TUNER
                    && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (tuner == null || info.getId().compareTo(tuner) < 0)) {
                tuner = info.getId();
            }
        }
        return tuner;
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = newMenu(panel);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        // Settings → General: leave out HDMI inputs that say nothing is connected. Ones whose state the TV
        // doesn't know stay, and so do analog inputs (see addPassthroughRow), so nothing usable disappears.
        boolean hideInactive = new VibeSettings(this).hideInactiveInputs();
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // A device found over HDMI-CEC is a child input of its port: an Apple TV and a PS5 behind a switch
            // on HDMI 2 are two children of HDMI 2. Picking one has the TV ask over CEC for that device's
            // picture (and wakes it), which also turns a CEC-aware switch or receiver to it. One device shows
            // as "HDMI 2 · Apple TV"; several as an HDMI 2 heading with a row for each. Ordered by id, which
            // follows the device's address, so a switch's inputs come in order.
            Map<String, List<TvInputInfo>> devices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                if (info.getParentId() == null || info.isHidden(this)) continue;
                List<TvInputInfo> known = devices.get(info.getParentId());
                if (known == null) devices.put(info.getParentId(), known = new ArrayList<>());
                known.add(info);
            }
            for (List<TvInputInfo> list : devices.values()) {
                Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
            }
            for (TvInputInfo info : inputs) {
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this) || info.getParentId() != null) continue;
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
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                // The port, then the name given in Settings > Inputs.
                CharSequence custom = info.loadCustomLabel(this);
                if (custom != null && custom.length() > 0 && !custom.toString().equals(label)) label = label + " · " + custom;
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
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
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Right-aligned to the pill so a wider menu grows leftwards instead of off the screen edge.
        showMenu(popup, panel, rows, anchor, Gravity.END);
    }

    /** A glass dropdown (the Inputs look). Rows go in R.id.rows; show it with {@link #showMenu}. */
    private PopupWindow newMenu(View panel) {
        PopupWindow popup = new PopupWindow(panel, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        return popup;
    }

    private void showMenu(PopupWindow popup, View panel, LinearLayout rows, View anchor, int gravity) {
        // Fit the widest row (TVs label inputs with long names like "HDMI 4/ARC" or the connected
        // device), at least as wide as the anchor and capped so the menu stays compact.
        int minWidth = Math.max(anchor.getWidth(), (int) (150 * density));
        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.4f);
        panel.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.setWidth(Math.min(maxWidth, Math.max(minWidth, panel.getMeasuredWidth())));

        // Below the anchor, or above it when it's too close to the bottom of the screen (dock tiles).
        int gap = (int) (10 * density);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int spaceBelow = getResources().getDisplayMetrics().heightPixels - loc[1] - anchor.getHeight();
        boolean above = panel.getMeasuredHeight() + gap > spaceBelow;
        int yOff = above ? -(anchor.getHeight() + panel.getMeasuredHeight() + gap) : gap;

        // Drop in: fade and unfold from the edge nearest the anchor.
        panel.setAlpha(0f);
        panel.setPivotY(above ? panel.getMeasuredHeight() : 0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY((above ? 8 : -8) * density);
        popup.showAsDropDown(anchor, 0, yOff, gravity);
        openMenu = popup;
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (row.isFocusable() && row.getVisibility() == View.VISIBLE) {
                row.requestFocus();
                break;
            }
        }
        // The top bar's menus work as one menu bar: Left and Right go to the neighbouring pill's menu,
        // and stop at the ends (nothing left of Inputs, nothing right of Settings).
        View[] bar = topBarPills();
        int at = Arrays.asList(bar).indexOf(anchor);
        if (at < 0) return;   // a tile's menu: not part of the bar
        View.OnKeyListener sideways = (v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            int to = at + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (to < 0 || to >= bar.length) return true;   // an end of the bar
            popup.dismiss();
            bar[to].requestFocus();
            bar[to].performClick();
            return true;
        };
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i).isFocusable()) rows.getChildAt(i).setOnKeyListener(sideways);
        }
    }

    /** The top bar's pills that are showing, left to right (no Inputs on devices without inputs). */
    private View[] topBarPills() {
        List<View> pills = new ArrayList<>();
        for (int id : new int[]{R.id.btnInputs, R.id.btnWifi, R.id.btnSettings}) {
            View v = findViewById(id);
            if (v.getVisibility() == View.VISIBLE) pills.add(v);
        }
        return pills.toArray(new View[0]);
    }

    // ---- tile menu: move, hide, remove, app info, hidden apps -------------------------------------

    @Override
    public void onTileLongPress(int position, View card) {
        // Holding OK on a remote triggers both the long-press timer and the long-press key repeat;
        // only the first may open the menu, or a second copy stays behind holding focus.
        if (openMenu != null && openMenu.isShowing()) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);
        if (adapter.isApp(position)) {
            // Acted on by app, not by place: an app installed or updated while the menu is open moves the
            // tiles, and the place would then point at a different app.
            String pkg = adapter.packageAt(position);
            addInputRow(rows, popup, R.drawable.ic_move, getString(R.string.move), -1, () -> {
                int at = adapter.positionOf(pkg);
                if (at < 0) return;
                adapter.startMove(at);
                Toast.makeText(this, R.string.move_hint, Toast.LENGTH_LONG).show();
            });
            addInputRow(rows, popup, R.drawable.ic_hide, getString(R.string.hide), -1, () -> {
                int at = adapter.positionOf(pkg);
                if (at < 0) return;
                adapter.hide(at);
                grid.postOnAnimation(() -> focusTile(at));   // the tile sliding into its place
            });
            // Remove uninstalls: only downloaded apps (or updates to built-in ones) can be.
            if (adapter.canUninstall(position)) {
                addInputRow(rows, popup, R.drawable.ic_delete, getString(R.string.remove), -1,
                        () -> adapter.uninstall(adapter.positionOf(pkg)));
            }
            // Some TVs have no App info page at all (upstream: CVTE's settings); then no row that only fails.
            if (AppAdapter.appInfo(this, pkg) != null) {
                addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.app_info), -1,
                        () -> adapter.openAppInfo(adapter.positionOf(pkg)));
            }
        }
        if (!adapter.userHidden().isEmpty()) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_all_apps, getString(R.string.show_hidden), -1,
                    () -> showHidden(card));
        }
        if (rows.getChildCount() == 0) return;
        showMenu(popup, panel, rows, card, Gravity.START);
    }

    /** Lists hidden apps; picking one puts it back at the end of the grid. */
    private void showHidden(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newMenu(panel);
        PackageManager pm = getPackageManager();
        List<String> pkgs = new ArrayList<>(adapter.userHidden());
        Collections.sort(pkgs, (a, b) -> appLabel(pm, a).compareToIgnoreCase(appLabel(pm, b)));
        for (String pkg : pkgs) {
            addInputRow(rows, popup, R.drawable.ic_hide, appLabel(pm, pkg), -1, () -> adapter.unhide(pkg));
        }
        if (rows.getChildCount() == 0) return;
        showMenu(popup, panel, rows, anchor, Gravity.START);
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    @Override
    public void onTileMoved(int position) {
        // The grid has fixed size, so it applies the move on the next animation frame without a layout
        // pass. Queue behind it on that frame, then bring the highlight (and dock title) to the new spot.
        grid.postOnAnimation(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
            if (vh == null) return;
            View card = vh.itemView.findViewById(R.id.card);
            if (!card.hasFocus()) card.requestFocus();
            onTileFocused(position, card);
        });
    }

    /** An input that reports nothing connected (not one whose state the TV doesn't know). */
    private static boolean inactive(TvInputManager tim, String id) {
        return tim.getInputState(id) == TvInputManager.INPUT_STATE_DISCONNECTED;
    }

    /** A device's name as the TV reports it over CEC, or the port's when it has none. */
    private String name(TvInputInfo device, String port) {
        CharSequence name = device.loadLabel(this);
        return name == null || name.length() == 0 ? port : name.toString();
    }

    /**
     * A row that shows an HDMI or AV input (or a device on one). HDMI rows say whether something is plugged
     * in; AV, component and the other analog jacks can't detect it, so TVs report them as always connected
     * or always not (the 940E's Video 1 and 2 say "No device" with a player on them): no state for those.
     */
    private View addPassthroughRow(LinearLayout rows, PopupWindow popup, boolean hdmi, String label, String id,
                                   TvInputManager tim) {
        int state = hdmi ? tim.getInputState(id) : -1;   // -1: neither, so no state is shown
        return addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                () -> openInput(id));
    }

    /**
     * Switches to an input through the TV app first: other apps can claim the same channel address (an IPTV
     * player, a file manager), and with two or more Android shows a chooser instead of the input (upstream,
     * on WhaleOS). Without a TV app, or if it doesn't answer, the address goes out to whichever handles it.
     */
    private void openInput(String id) {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(
                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel");
        if (!liveTvPkg.isEmpty()) {
            try {
                startActivity(new Intent(view).setPackage(liveTvPkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Not the TV app's: as before.
            }
        }
        open(view);
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    /** A read-only row (focus skips it): icon, title and, when given, a green dot with a status. */
    private void addInfoRow(LinearLayout rows, int iconRes, String title, String status) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
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

    private View addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // Only definite states are shown: some TVs report "standby" for ports with nothing plugged in.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
        return row;
    }
}
