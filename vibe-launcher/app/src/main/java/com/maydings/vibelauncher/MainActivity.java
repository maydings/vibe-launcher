package com.maydings.vibelauncher;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.view.ViewTreeObserver;
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
import android.widget.TextClock;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
    /** How many apps the dock holds: a full row, or fewer, centred (Settings > Appearance > Apps in dock). */
    static int DOCK = 4;
    /** How long the rows take to slide out of sight; the top bar moves at the same speed. */
    private static final long PICTURE_SLIDE_MS = 450;
    private static final long CARET_FADE_MS = 150;   // the carets go before the menus move
    private static final float CARET_ALPHA = 0.25f;
    private static final long PLAY_SLIDE_MS = 350;    // the Play pill, in from the left
    private static final long BAR_SLIDE_MS = 250;     // the top bar in or out as focus moves
    private static final long IDLE_MS = 60_000;       // left alone this long, Home shows the slideshow
    private static final long SLIDE_MS = 10_000;      // each picture in the slideshow (a video: to its end)
    private static final long VIDEO_AGAIN_MS = 1_500; // a video ended and nothing took its place: it plays again
    private static final long SLIDE_LOAD_MS = 10_000; // how long an ended video waits for the next slide to load
    private static final long VIDEO_WAIT_MS = 1_000;  // a slide due while a video plays: looked at again this often
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
    String liveTvPkg;

    AppAdapter adapter;
    RecyclerView grid;
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
     * The slideshow: after IDLE_MS without a key on Home, pictures and videos at random, SLIDE_MS each, with
     * nothing else on screen: every dock app's, those of the app or folder selected, or the slideshow's own list
     * (Settings > Appearance > Screensaver backdrops; see nextSlide). Any key brings Home back on the picture
     * showing's app (see endSlideshow).
     */
    private boolean slideshow;
    private String slidePkg;            // the app of the picture showing in the slideshow
    private Backdrops.Entry slideEntry;
    private boolean slideOfList;        // showing one of a list (slideFromList), which may not be the tile's own
    /**
     * When the video showing in the slideshow became its slide, and whether it has reached its end since. A
     * video isn't cut: the next slide waits for its end (nextSlide, WallpaperVideo.Listener.onEnded), and one
     * shorter than SLIDE_MS goes round until an end past that.
     */
    private long slideAt;
    private boolean slideVideoEnded;
    private boolean slideFirst;         // no slide shown yet since the slideshow started
    private final java.util.Random slideRandom = new java.util.Random();
    /** When the slide being loaded was asked for (an app's picture, which may come from the network); 0: none. */
    private long slideLoadingAt;
    private final Runnable videoAgain = () -> {
        if (!slideshow || !this.videoOnScreen) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (slideLoadingAt != 0 && now - slideLoadingAt < SLIDE_LOAD_MS) {
            // The next slide is on its way: the ended video stands on its last frame until it comes.
            this.handler.postDelayed(this.videoAgain, VIDEO_WAIT_MS);
            return;
        }
        slideAt = android.os.SystemClock.uptimeMillis();
        this.wallpaperVideo.resume();   // nothing if another video, or this one again, is playing by now
    };
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
    private View caretDock, caretMore, caretBar, caretUp, caretDown, caretLeft, caretRight;
    private boolean playShown;   // the Play pill is in (or coming in)
    private VibeSettings settings;
    /** Hold OK with the picture alone: the Play pill fills, and a long press opens backdropLink. */
    private View holdPlay, holdPlayFill;
    private View slideClock;   // the slideshow's clock, where the top bar's is
    private TextView playInfo;   // under the Play pill: the time left on something you're watching
    private ValueAnimator holdFill;
    private boolean holdOpened;   // this press already opened the title: its release changes nothing
    private String backdropLink;   // what opens the picture on screen's title; null for none
    /** A picture (not the glow) is behind the dock: without one, the picture alone is the glow, with no Play pill. */
    private boolean stillShown;
    FrameLayout root;
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
    private TextView backdropTitle, backdropMeta, backdropSynopsis, backdropGenres;
    /** The menus the top bar's pills open: the network's, the system's, the inputs'. */
    final TopBarMenus topBarMenus = new TopBarMenus(this);
    /** The menus a tile opens: a long press's, Edit's, Backdrops'. */
    final TileMenus tileMenus = new TileMenus(this);
    /** The dock band behind the first row; its opacity follows Appearance > Dock opacity. */
    private DockDecoration dockDecoration;
    /** The top bar's frost and tint; its opacity follows Appearance > Top bar opacity. */
    private FrostView frost;
    /** A film or show's details as TMDB gave them with its backdrop (Tmdb.info): read from what's kept. */
    private Tmdb titleDetails;
    private String titleShown;   // the title the backdrop's logo and details are for now, or null
    private Bitmap logoShown;
    private final java.util.Set<String> detailsAsked = new java.util.HashSet<>();
    private final java.util.Set<String> detailsPending = new java.util.HashSet<>();   // being asked for now
    private long detailsRetryAt;   // after one couldn't be asked for: none are, until then
    private Backdrops backdrops;
    /** The dock app whose backdrop is showing or loading, or null. */
    private String backdropPkg;
    TextView btnWifi;
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
    PopupWindow openMenu;
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
        if (!heroShown || !pkg.equals(backdropPkg) || !showsContent(this.backdropKey)) return;
        String key = this.backdropKey != null ? this.backdropKey : pkg;
        backdropPkg = null;
        showBackdrop(key, pkg, backdropColor);
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

    final Handler handler = new Handler(Looper.getMainLooper());
    /** Home is covered by Vibe Launcher's own Settings, not an app: coming back is no new visit. */
    boolean inSettings;
    /** The highlight color (Settings), as last applied: the menus' rows take it as they're made. */
    private int highlight = 0xFFFFFFFF;
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
            if (intent.getData() != null) adapter.markUpdated(intent.getData().getSchemeSpecificPart());
            if (!started) {   // behind an app: the tiles are rebuilt once, on the way back
                appsChanged = true;
                return;
            }
            // Several apps at once (an update batch, a clean-up): one rebuild once they stop coming.
            handler.removeCallbacks(appsChangedNow);
            handler.postDelayed(appsChangedNow, 300);
        }
    };
    /** The tiles rebuilt for apps installed, updated or removed while Home is on screen (packageReceiver). */
    private final Runnable appsChangedNow = () -> {
        if (!started) {
            appsChanged = true;
            return;
        }
        adapter.reload();
        if (this.folderView != null) folderChanged();   // "this.": the field is declared further down
        appsChangedUnseen();   // shown already: no rebuild for it on the next return
        ChannelSetup.run(this);   // a new or updated app asked for its home screen cards now
        // Tiles may have moved: once they settle, bring the glass to wherever focus went.
        grid.postOnAnimation(() -> {
            RecyclerView.ItemAnimator animator = grid.getItemAnimator();
            if (animator != null) animator.isRunning(this::refreshFocusedTile);
            else refreshFocusedTile();
        });
    };
    /**
     * Rebuilds the dock apps' backdrop pools that are due (six hours old), then comes back when the next
     * one will be. Only while Home is on screen; the next visit catches up on any that fell due meanwhile.
     */
    private final Runnable refreshPools = new Runnable() {
        @Override
        public void run() {
            List<String> dock = dockApps();
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
        FontInflater.install(this);   // the chosen fonts (Appearance > Font) for the layouts' text
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
        highlight = Highlight.color(this);   // before anything is drawn in it (onResume applies it to the views)
        COLUMNS = settings.columns(defaultColumns());
        DOCK = settings.dockApps(COLUMNS);
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
        backdropMeta = findViewById(R.id.backdropMeta);
        backdropSynopsis = findViewById(R.id.backdropSynopsis);
        backdropGenres = findViewById(R.id.backdropGenres);
        titleDetails = new Tmdb(this);
        Context app = getApplicationContext();
        new Thread(() -> TvControl.offIfLeftOn(app), "DebuggingOff").start();   // after a restart from Settings
        wallpapers = Wallpapers.get(this);
        flightBoard = findViewById(R.id.flightBoard);
        // A video backdrop under the board isn't played for nobody: held still while it's covered.
        flightBoard.onCovering = () -> {
            wallpaperVideo.setCovered(flightBoard.covering());
            // The film's title belongs to the picture that's covered: it goes with it.
            backdropTitleBox.setVisibility(flightBoard.covering() ? View.INVISIBLE : View.VISIBLE);
            clockPlate();
        };
        flights = Flights.get(this);
        wallpaperScrim = findViewById(R.id.wallpaperScrim);
        wallpaperVideo = new WallpaperVideo(findViewById(R.id.wallpaperVideo), new WallpaperVideo.Listener() {
            @Override
            public void onShowing() {
                if (!videoOnScreen) return;   // a picture took over while it loaded: it stops after that fade
                // Its first frame is up: the still over it goes, and the video shows through.
                backdropImage.animate().cancel();
                backdropImage.animate().alpha(0f).setDuration(450).withEndAction(() -> {
                    if (!wallpaperVideo.isShowing()) return;
                    backdropImage.setImageDrawable(null);
                    backdropPrevious.setImageDrawable(null);
                    backdropPrevious.setVisibility(View.GONE);
                }).start();
                backdropTitleBox.animate().alpha(0f).setDuration(200).start();
            }

            @Override
            public void onFailed() {
                wallpaperScrim.setVisibility(View.INVISIBLE);
                if (!videoOnScreen) return;   // something else is showing already
                wallpaperShown = false;
                fadeBackdrop(null, null, null, backdropColor);   // the glow, as without a wallpaper
            }

            @Override
            public void onEnded() {
                if (!slideshow || !videoOnScreen) return;
                // Shorter than a picture's time: round again, to an end past it. A longer one that was near its
                // end when it became the slide (Home's own, as the slideshow starts) isn't played through again.
                int length = wallpaperVideo.duration();
                if ((length == 0 || length < SLIDE_MS) && android.os.SystemClock.uptimeMillis() - slideAt < SLIDE_MS) {
                    wallpaperVideo.resume();
                    return;
                }
                slideVideoEnded = true;
                handler.removeCallbacks(nextSlide);
                nextSlide();
                // Nothing else to show (no other picture anywhere): it plays again rather than stand still.
                handler.removeCallbacks(videoAgain);
                handler.postDelayed(videoAgain, VIDEO_AGAIN_MS);
            }
        });
        backdrops = Backdrops.get(this);
        backdrops.attach(firstStill, watchingChanged);
        if (!askPermissionsOnce()) askHomeOnce();
        // The app's: one made from this activity keeps it (and so every earlier Home) alive on Android 12 and up.
        ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);
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
        weatherView = findViewById(R.id.weather);
        dateBox = findViewById(R.id.dateBox);
        localDate((TextClock) date, "EEEE d MMMM yyyy");
        showClockOnceTimeIsSet(findViewById(R.id.clockRow));
        applyClockHours();
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
        caretMore = findViewById(R.id.caretMore);
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
        adapter = new AppAdapter(this, this, COLUMNS, DOCK);
        // In half tiles, so a dock of fewer tiles than a row can be centred (AppAdapter.spanSize).
        GridLayoutManager layout = new GridLayoutManager(this, COLUMNS * 2) {
            // onTileFocused scrolls the rows itself, for every tile. RecyclerView's own scroll to bring a
            // focused tile on screen measures differently (the tile, not the glass; the dock's row reaches a
            // little past the screen's edge), so the two pulled a row a few dp up and down going sideways.
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                return false;
            }

            // The rows below the dock are laid out (and their pictures loaded) before they scroll in, not
            // during the scroll down from the dock.
            @Override
            protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extra) {
                extra[0] = 0;
                extra[1] = grid.getHeight() / 2;
            }
        };
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.spanSize(position);
            }
        });
        grid.setLayoutManager(layout);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(new TileAnimator());
        dockDecoration = new DockDecoration(adapter, COLUMNS, density);   // its opacity: onResume
        grid.addItemDecoration(dockDecoration);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        grid.setAdapter(adapter);
        // A shorter dock's centring is the first and last tiles' insets (DockDecoration), which RecyclerView
        // keeps per view: moved in or out of those places without a rebind, a tile kept the old ones.
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                grid.post(grid::invalidateItemDecorations);
            }

            @Override
            public void onItemRangeInserted(int start, int count) {
                onChanged();
            }

            @Override
            public void onItemRangeRemoved(int start, int count) {
                onChanged();
            }

            @Override
            public void onItemRangeMoved(int from, int to, int count) {
                onChanged();
            }
        });
        frost = findViewById(R.id.frost);   // its opacity: onResume
        frost.setSource(grid);
        frost.setBackdrop(backdrop);

        // Push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int tileH = AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(
                    (r - l) - grid.getPaddingLeft() - grid.getPaddingRight(), COLUMNS, density));
            // The dock's panel ends DockDecoration.PAD_DP under its tiles, which sit 6dp (DockDecoration) plus 8dp
            // (the item's padding) into their row. Under the panel, half the space between two rows of tiles
            // (an item is its tile plus 44dp): the next row stays below the screen's edge until you go down,
            // and caretMore sits in that space.
            int below = AppAdapter.itemHeightPx(tileH, density) - tileH;
            int want = Math.max(0, h - tileH - Math.round((6 + 8 + DockDecoration.PAD_DP) * density) - below / 2);
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
                // Settle exactly on the tile: the plan can be off when the scroll stopped short at the end
                // of the list, or a new focus cut it short.
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
        btnInputs.setOnClickListener(v -> topBarMenus.showInputs());
        btnWifi.setOnClickListener(v -> topBarMenus.showNetwork());
        btnSettings.setOnClickListener(v -> topBarMenus.showSettingsMenu(btnSettings));
        // The update dot follows the gear wherever the bar's style puts it.
        btnSettings.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeUpdateDot());
        findViewById(R.id.updateDot).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeUpdateDot());

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (settings.autoHideBar()) {   // the bar goes either way: the picture alone at once, no wait
                    cancelPictureSoon();
                    showPictureFromBar();
                    return true;
                }
                if (pictureSoon) {   // Down again straight away: scrolling down, to the dock
                    cancelPictureSoon();
                    // Back to the dock tile Up came from, not whichever tile the focus finder picks.
                    focusDockFromAbove(adapter.dockTileFor(lastFocused));
                    return true;
                }
                // The picture alone, as Up from the dock, after the same wait.
                pictureSoon = true;
                pictureSoonKey = KeyEvent.KEYCODE_DPAD_DOWN;
                handler.postDelayed(showPictureFromBar, PICTURE_DELAY_MS);
                return true;
            }
            // Left and Right only along the bar, to the next pill showing, and nowhere at its ends: left to
            // Android's focus search, Right could reach a dock folder's mini row, which sits below the bar.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                View[] bar = topBarPills();
                int to = Arrays.asList(bar).indexOf(v) + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (to >= 0 && to < bar.length && bar[to] != v) {
                    bar[to].requestFocus();
                    grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                            ? SoundEffectConstants.NAVIGATION_RIGHT : SoundEffectConstants.NAVIGATION_LEFT);
                }
                return true;
            }
            return false;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(focusWatcher);
    }

    /**
     * Hides the glass when focus leaves the tiles, and keeps the top bar and the arrows as focus moves. Added in
     * onCreate and removed in onDestroy: left on the window, it kept every earlier Home alive across recreate().
     */
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusWatcher = (oldF, newF) -> {
        boolean onTile = newF != null && newF.getId() == R.id.card;
        if (!onTile) hideGlass();
        // Arriving on the top bar: the network pill as it is now (it otherwise follows the network
        // callbacks and each return to Home).
        if (isPill(newF) && !isPill(oldF)) updateWifiLabel();
        if (onTile && rowsAway && !pictureOnly) bringRowsBack();   // Down twice from the top bar
        updateTopBar();
        updateCarets();
    };

    /** Keeps the clocks (the top bar's, the slideshow's) moving before Android 12 where the firmware stops ACTION_TIME_TICK. */
    private ClockRefresh clockRefresh;

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (clockRefresh == null) clockRefresh = new ClockRefresh(root);
        clockRefresh.start();   // while Home is on screen, paused or not
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
        if (pressed) closeFolder();
    }

    @Override
    protected void onResume() {
        super.onResume();
        inSettings = false;
        applyHighlight();   // chosen in Settings meanwhile
        applyOpacities();   // and the dock's and the top bar's opacity
        refreshWeather();   // its settings may have changed meanwhile too
        applyWallpaperChange();   // and the custom wallpapers
        inFront = true;
        flightBoard.setPaused(false);
        applyFlights();   // and the flights overhead, asked for while Home is in front
        applyTopBarStyle();   // and the top bar's look
        // A video wallpaper paused with Home (onPause) plays again: Home pressed on Home pauses and resumes it
        // without the window ever losing focus, so onWindowFocusChanged wouldn't.
        if (heroShown && hasWindowFocus()) wallpaperVideo.resume();
        lastKeyAt = SystemClock.uptimeMillis();
        armIdle();
        showUpdateReady();
        if (wakeCheck) wakeUpdateCheck();
        else Updater.checkIfDue(this, (newer, failed) -> showUpdateReady());   // at most once a day
        applyClockHours();   // the 12/24-hour setting (the TV's or ours) may have changed meanwhile
        sizeTimeBox();
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
            // Home pressed with the picture alone up: the rows back first (at once), else the first tile's name came
            // in over the picture, beside its Play button.
            if (pictureOnly) showRows(false, false);
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
                wallpapers.reshuffle();
                backdropPkg = null;
                // Loaded here: the tile usually kept its focus, so no focus change would load it.
                if (heroShown && adapter.inDock(lastFocused)) showBackdropAt(lastFocused);
            }
            // Else back from a side panel, such as the TV's Settings: Home is as it was.
            refreshPools.run();
            return;
        }
        rebuildOnResume = false;
        handler.removeCallbacks(appsChangedNow);   // this rebuild covers a waiting app change too
        appsChangedUnseen();   // caught up: count from here
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        if (grid.getAdapter() == null) grid.setAdapter(adapter);   // let go of in onTrimMemory
        adapter.reload();
        if (folderView != null) folderChanged();   // an app in it may have gone while away
        // After the reload: on a cold start the grid is empty until it, and with no dock apps the next check
        // is six hours away.
        refreshPools.run();
        ChannelSetup.run(this);
        backdropPkg = null;
        backdrop.setAlpha(0f);
        stillShown = false;
        backdrops.reshuffle();
        wallpapers.reshuffle();
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
        wallpaperVideo.pause();
        inFront = false;
        flights.stop();
        flightBoard.setPaused(true);
        handler.removeCallbacks(weatherTick);
        handler.removeCallbacks(refreshPools);
        cancelPictureSoon();
        handler.removeCallbacks(startSlideshow);
        if (slideshow) endSlideshow(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        if (clockRefresh != null) clockRefresh.stop();
        backFromApp = !inSettings;   // Settings is part of Home: no new picture for coming back from it
    }

    /**
     * The check after turning on or waking. At most every few minutes (GitHub's limit on unsigned calls is
     * shared by every TV in the house). If it fails, as when Wi-Fi is still reconnecting after sleep, it
     * runs again once the network is back.
     */
    private void wakeUpdateCheck() {
        if (!wakeCheck || wakeChecking) return;
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
        if (openMenu != null && openMenu.isShowing()) openMenu.dismiss();   // its window would outlive Home
        unregisterReceiver(screenReceiver);
        unregisterReceiver(packageReceiver);
        // The pools go on filling for the next Home; only this one's handlers go.
        backdrops.detach(firstStill, watchingChanged);
        handler.removeCallbacksAndMessages(null);
        // This Home's own threads: a new Home makes its own (else two more idle threads per restart).
        if (wallpaperThread != null) wallpaperThread.shutdownNow();
        if (weatherThread != null) weatherThread.shutdownNow();
        ViewTreeObserver tree = getWindow().getDecorView().getViewTreeObserver();
        if (tree.isAlive()) tree.removeOnGlobalFocusChangeListener(focusWatcher);
        ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);   // as registered
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
        // A video wallpaper plays only while Home is what's in front: not under a menu, a dialog or Settings.
        if (hasFocus && heroShown) wallpaperVideo.resume();
        else wallpaperVideo.pause();
    }

    @Override
    public void onBackPressed() {
        if (folderView != null) {   // an open folder closes first
            closeFolder();
            return;
        }
        if (shelf != null && shelf.hasFocus()) {   // a dock folder's apps: back down to the folder
            focusTile(shelfFolderPos);
            return;
        }
        // Home screen: Back goes nowhere, unless Settings > General > Back button says otherwise.
        switch (settings.backAction()) {
            case VibeSettings.BACK_FIRST_APP:
                focusTile(0);
                break;
            case VibeSettings.BACK_POSTER:
                // From a lower row its own app's picture is showing, not a dock app's: the dock tile first
                // (Back again shows its poster), as Up only shows the picture alone from the dock.
                boolean fromBar = topBar.hasFocus();
                if (!fromBar && !onDock()) {
                    focusTile(adapter.dockTileFor(lastFocused));
                    break;
                }
                // As from the top bar: the dock tile goes with the picture, focused once the rows are away.
                if (fromBar) focusAfterSlide = adapter.dockTileFor(lastFocused);
                showPictureOnly(SoundEffectConstants.NAVIGATION_UP);
                break;
            case VibeSettings.BACK_SCREENSAVER:
                startSlideshow();
                break;
        }
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
        boolean dock = adapter.inDock(position);
        int dy;
        if (dock) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            dy = decoratedTop - grid.getPaddingTop();
        } else {
            // Scroll just enough so the whole glass (not only the tile) is visible.
            int top = item.getTop() - (int) pad * 2, bottom = item.getBottom() + (int) pad;
            int parentTop = grid.getPaddingTop(), parentBottom = grid.getHeight() - grid.getPaddingBottom();
            // The row goes in the dock's place at the bottom of the screen. When the glass and the names under
            // it are taller than that (the dock rests low, with nothing under its tiles), the row comes up just
            // far enough for its names to show: going up into the empty space where the app's name is with
            // the dock, not off the bottom of the screen. One place for the whole row, so going sideways never
            // moves it.
            int slotTop = Math.min(parentTop, parentBottom - (bottom - top));
            dy = top < slotTop ? top - slotTop : (bottom > parentBottom ? bottom - parentBottom : 0);
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
        // Centred just above the dock's panel, which starts DockDecoration.PAD_DP above its tiles.
        dockPanelTop = dockTopInRoot - DockDecoration.PAD_DP * density;
        // 4dp over the panel, as caretMore is under it: the same space from the panel to either chevron.
        caretDock.setY(dockPanelTop - 4 * density - caretDock.getLayoutParams().height);
        // The shelf's app's name while it has focus there; else the tile's.
        String label = shelf != null && shelf.hasFocus() ? String.valueOf(heroName.getText()) : adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        // A folder: its apps in a row above the dock (tvOS's Top Shelf for a folder), its name above them, and
        // one of its apps' pictures behind, another each visit.
        String pkg = adapter.isFolder(position) ? folderApp(position) : adapter.packageAt(position);
        float nameBottom = adapter.isFolder(position) ? showShelf(position) - 18 * density : dockPanelTop - 26 * density;
        if (!adapter.isFolder(position)) hideShelf(false);
        float y = nameBottom - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        // No big name over a dock that doesn't fill its row (Settings > Appearance > Apps in dock).
        heroName.setVisibility(adapter.dockCount() < COLUMNS ? View.INVISIBLE : View.VISIBLE);

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
            // Back on the tile whose name it has, perhaps while a change to another's was fading out: that
            // change is called off, or it would put the other's name here when it ended.
            heroName.animate().cancel();
            heroName.setAlpha(1f);
            heroName.setTranslationX(0f);
            heroName.setY(y);
        }
        showBackdrop(listKeyAt(position), pkg, color);
        showFlights();
    }

    /**
     * What shows behind the dock for a tile, by its key (an app's package or a folder's key), pkg being whose
     * pictures are its own. Nothing changes until a still has loaded, so the screen goes straight from one app's
     * look to the next: the glow is drawn above the stills and would otherwise flash over a picture while the
     * next one loads. What shows is the first thing in the tile's order (tile menu > Edit > Backdrops;
     * Wallpapers.pick) that's enabled and has something: the app's pictures, its own list, the system backdrop,
     * the flights overhead, its colour. The app's pictures are taken to be there until they load with none
     * (showPicture), and then it's whatever comes next.
     */
    private void showBackdrop(String key, String pkg, int color) {
        char kind = pick(key, pkg != null);
        boolean same = java.util.Objects.equals(key, backdropKey) && java.util.Objects.equals(pkg, backdropPkg);
        if (kind != Wallpapers.CONTENT) {
            // Its list's one, showing already. Not when the backdrop's been taken down meanwhile (Home made
            // again, memory given back): a tile with no app and no list of its own matches nothing-showing too.
            if (same && kind == backdropKind && wallpaperShown && backdrop.getAlpha() > 0f) return;
            backdropPkg = pkg;
            backdropKey = key;
            backdropColor = color;
            showKind(kind, key, color);
            return;
        }
        if (same) return;
        backdropPkg = pkg;
        backdropKey = key;
        backdropColor = color;
        backdrops.load(pkg, (loaded, bitmap, logo, title, link) -> {
            if (!heroShown || !loaded.equals(backdropPkg)) return;   // moved on while it loaded
            backdropLink = bitmap != null ? link : null;
            showPicture(bitmap, logo, title, color);
            if (pictureOnly) updateCarets();   // its first pictures may have come in meanwhile
        });
    }

    /** What's behind the dock now, of what a tile can show (Wallpapers.pick): the flights' board goes by it. */
    private char backdropKind = Wallpapers.COLOUR;

    /** What the tile with this key shows now (Wallpapers.pick); content says its app may have pictures. */
    private char pick(String key, boolean content) {
        return wallpapers.pick(key, content, flightsPlace() != null);
    }

    /** Where the flights are looked for: as they last were, else where the weather is for, if that's known. */
    private Weather.Place flightsPlace() {
        Weather.Place place = flights.place();
        return place != null ? place : Weather.lastPlace(this, settings);
    }

    /** Shows what isn't an app's pictures: one of a list (the tile's own, the system backdrop's), the flights, the colour. */
    private void showKind(char kind, String key, int color) {
        backdropKind = kind;
        if (kind == Wallpapers.OWN || kind == Wallpapers.GALLERY) {
            showList(kind == Wallpapers.OWN ? key : Wallpapers.ROTATION, color);
        } else {
            fadeBackdrop(null, null, null, color);   // the flights' board goes over it (showFlights)
        }
    }

    /** Whether the tile with this key shows its app's own pictures, when it has any. */
    private boolean showsContent(String key) {
        return pick(key, true) == Wallpapers.CONTENT;
    }

    /** reportFullyDrawn sent: Home's first picture is up (fadeBackdrop). */
    private boolean reportedDrawn;

    /** Shows a still (cross-fading from the one before, if any), or the glow in this colour. */
    private void fadeBackdrop(Bitmap bitmap, Bitmap logo, String title, int color) {
        stillShown = bitmap != null;
        // A custom wallpaper's picture comes through here too (showWallpaperItem); anything else ends one.
        wallpaperShown = wallpaperPicture;
        wallpaperPicture = false;
        wallpaperOnScreen = null;   // set again by showWallpaperItem when this is its picture
        showFlights();   // over it or not, by what this is
        boolean overVideo = wallpaperVideo.isShowing();
        videoOnScreen = false;   // this picture (or glow) is what shows now, even over a video still loading
        int video = videoGeneration;   // the video this picture (or glow) takes over from
        // Every picture shown or taken away comes through here: with the picture alone up, its Play pill follows
        // at once (a reload, a picture arriving late), so the pill and the picture never disagree.
        if (pictureOnly) handler.post(this::updateCarets);
        if (bitmap == null) {
            backdrop.animate().alpha(0f).setDuration(300).withLayer()
                    .withEndAction(() -> { if (overVideo) stopWallpaperVideo(video); }).start();
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
        if (!reportedDrawn) {
            // Android logs the time from launch to the first picture ("Fully drawn" in logcat): what a cold
            // start costs with the backdrops, for measuring. Once per Home.
            reportedDrawn = true;
            reportFullyDrawn();
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
                if (overVideo) stopWallpaperVideo(video);   // a video was loading under the old picture
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
        backdropImage.setImageBitmap(bitmap);
        backdropTitleBox.animate().cancel();
        backdropTitleBox.setAlpha(1f);
        showBackdropTitle(logo, title);
        if (overVideo) {   // over a video wallpaper: the picture fades in over it, then the video stops
            backdropImage.setAlpha(0f);
            backdrop.animate().alpha(1f).setDuration(200).start();
            backdropImage.animate().alpha(1f).setDuration(450).withEndAction(() -> stopWallpaperVideo(video)).start();
            return;
        }
        backdropImage.setAlpha(1f);
        backdrop.animate().alpha(1f).setDuration(450).withLayer().start();
    }

    // ---- custom wallpapers -------------------------------------------------------------------------------

    Wallpapers wallpapers;
    private WallpaperVideo wallpaperVideo;
    private View wallpaperScrim;
    /** A custom wallpaper is what's behind the dock: no Play pill, no other pictures to go through. */
    private boolean wallpaperShown;
    /** The next fadeBackdrop is a wallpaper's picture. */
    private boolean wallpaperPicture;
    private java.util.concurrent.ExecutorService wallpaperThread;
    /** Counts video wallpapers started: a stop meant for one that's been started again since doesn't run. */
    private int videoGeneration;
    /** The video wallpaper is what should show (not a picture that came in over it while it loaded). */
    private boolean videoOnScreen;
    /** The wallpaper picture in backdropImage now, by id: moving along the dock in Always keeps it, no reload. */
    private String wallpaperOnScreen;
    /** The list the wallpaper showing came from (Wallpapers.ROTATION, an app's or a folder's): Left/Right go along it. */
    private String wallpaperList = Wallpapers.ROTATION;
    /** The list key (an app's package or a folder's) whose backdrop is showing, with backdropPkg. */
    private String backdropKey;

    /** An app's picture as it loaded, or, when it has none, what comes next in its tile's order. */
    private void showPicture(Bitmap bitmap, Bitmap logo, String title, int color) {
        if (bitmap == null) {
            showKind(pick(backdropKey, false), backdropKey, color);
            return;
        }
        backdropKind = Wallpapers.CONTENT;
        fadeBackdrop(bitmap, logo, title, color);
    }

    /** This visit's one from a list (a tile's own, or the System backdrop's), or the glow when it has none. */
    private void showList(String key, int color) {
        Wallpapers.Item item = wallpapers.current(key);
        if (item == null) {
            fadeBackdrop(null, null, null, color);
            return;
        }
        wallpaperList = key;
        showWallpaperItem(item, color);
    }

    /** The list a tile's backdrop comes from first: a folder's key, or the app's package; null for neither. */
    private String listKeyAt(int position) {
        if (adapter.isFolder(position)) {
            String id = adapter.folderIdAt(position);
            return id != null ? Folders.KEY_PREFIX + id : null;
        }
        return adapter.packageAt(position);
    }

    /** The backdrop of the tile at this position: its own list, a folder's app's pictures, or the app's. */
    private void showBackdropAt(int position) {
        String pkg = adapter.isFolder(position) ? folderApp(position) : adapter.packageAt(position);
        showBackdrop(listKeyAt(position), pkg, adapter.ambientColorAt(position));
    }

    /**
     * The key whose order says what this app shows where it's one of many (the slideshow, a folder's row): its
     * own when it has one, else its folder's when that has, else its own (left alone).
     */
    private String choiceKeyFor(String pkg) {
        if (wallpapers.hasOrder(pkg)) return pkg;
        for (Folders.Folder f : adapter.folders.all()) {
            String key = Folders.KEY_PREFIX + f.id;
            if (f.apps.contains(pkg) && wallpapers.hasOrder(key)) return key;
        }
        return pkg;
    }

    /** A wallpaper: a picture through fadeBackdrop (already darkened), or a video playing under the stills. */
    private void showWallpaperItem(Wallpapers.Item item, int color) {
        backdropLink = null;
        java.io.File file = wallpapers.file(item);
        if (item.video) {
            wallpaperShown = true;
            stillShown = false;
            if (pictureOnly) handler.post(this::updateCarets);
            ambient.animate().alpha(0f).setDuration(300).start();
            videoGeneration++;
            videoOnScreen = true;
            wallpaperOnScreen = null;
            wallpaperScrim.setVisibility(View.VISIBLE);
            backdrop.animate().cancel();
            backdrop.animate().alpha(1f).setDuration(450).start();
            wallpaperVideo.play(file);
            if (!hasWindowFocus()) wallpaperVideo.pause();
            showFlights();
            return;
        }
        if (item.id.equals(wallpaperOnScreen) && backdropImage.getDrawable() != null && !wallpaperVideo.isShowing()) {
            // Already the picture up (Always, along the dock): kept, faded back in if Home had faded it out.
            wallpaperShown = true;
            stillShown = true;
            if (pictureOnly) handler.post(this::updateCarets);
            ambient.animate().alpha(0f).setDuration(300).start();
            backdrop.animate().cancel();
            backdrop.animate().alpha(1f).setDuration(450).withLayer().start();
            showFlights();
            return;
        }
        if (isDestroyed()) return;   // a late answer for a Home that's gone: its thread is shut down
        if (wallpaperThread == null) wallpaperThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        String pkg = backdropPkg;
        wallpaperThread.execute(() -> {
            Bitmap bitmap = wallpapers.backdrop(item);   // darkened as backdrops are
            handler.post(() -> {
                if (!heroShown && !slideshow) return;
                // Moved on meanwhile (the All apps tile has no app: none is as good a match as one).
                if (!slideshow && !java.util.Objects.equals(pkg, backdropPkg)) return;
                if (bitmap == null) {
                    fadeBackdrop(null, null, null, color);
                    return;
                }
                wallpaperPicture = true;
                fadeBackdrop(bitmap, null, null, color);
                wallpaperOnScreen = item.id;
            });
        });
    }

    /** The wallpaper setup last shown: which there are, on which lists, and what each tile is set to show. */
    private String wallpaperState = "";

    /**
     * Back from Settings, which leaves Home as it was: if the wallpapers' setup changed there (one added, deleted
     * or ticked, or a tile set to show something else), the focused app's background is shown again by it at
     * once, the wallpaper or the app's own picture, without anything being reloaded (the backdrops stay as they
     * are, under it).
     */
    void applyWallpaperChange() {
        if (wallpapers.pendingAlways() && adapter.dockCount() > 0) {
            // The System backdrop was set to show always (Wallpapers.firstOrders): each dock tile has it first.
            for (int i = 0; i < adapter.dockCount(); i++) {
                String key = listKeyAt(i);
                if (key != null && !wallpapers.hasOrder(key)) wallpapers.setOrder(key, Wallpapers.systemFirst());
            }
            wallpapers.donePendingAlways();
        }
        // Which ones, not how many: one deleted and another added leaves the count as it was.
        StringBuilder ids = new StringBuilder();
        for (Wallpapers.Item item : wallpapers.list()) ids.append('|').append(item.id);
        ids.append('|').append(wallpapers.listsState());   // ticked for a tile or the System backdrop, or an order
        String state = ids.toString();
        if (state.equals(wallpaperState)) return;
        boolean first = wallpaperState.isEmpty();
        wallpaperState = state;
        if (first || !heroShown || lastFocused < 0 || !adapter.inDock(lastFocused)) return;
        wallpapers.reshuffle();
        backdropPkg = null;
        backdropKey = null;
        showBackdropAt(lastFocused);
    }

    /** The video goes, once the picture or glow taking over from it has faded in; not if one was started since. */
    private void stopWallpaperVideo(int generation) {
        if (generation != videoGeneration) return;
        wallpaperVideo.stop();
        wallpaperScrim.setVisibility(View.INVISIBLE);
    }

    /**
     * The film or show's logo, else its name, at the right, level with the app name; nothing otherwise. With
     * its details on (Settings > Backdrops) and some kept for it, it's at the left over the app's name instead,
     * as a streaming app's own screen has it: the logo, its score and year, its synopsis, its genres.
     */
    private void showBackdropTitle(Bitmap logo, String title) {
        boolean detailed = title != null && settings.titleInfo();
        Tmdb.Info info = detailed ? titleDetails.info(title) : null;
        titleShown = title;
        logoShown = logo;
        // Its details aren't kept yet: asked for now (once while Home is up, unless it couldn't ask). Nothing
        // of the title shows meanwhile: it comes up whole when they're in (or, when they can't be had, as it
        // is without them).
        if (detailed && titleDetails.infoWanted(title) && SystemClock.uptimeMillis() >= detailsRetryAt
                && detailsAsked.add(title)) {
            detailsPending.add(title);
            new Thread(() -> {
                boolean asked = titleDetails.fetchInfo(title);
                handler.post(() -> {
                    detailsPending.remove(title);
                    if (!asked) {   // offline, say: asked again when it's next shown, though not at once
                        detailsAsked.remove(title);
                        detailsRetryAt = SystemClock.uptimeMillis() + 60_000;
                    }
                    if (!title.equals(titleShown) || isFinishing()) return;
                    showBackdropTitle(logoShown, title);
                    if (backdropTitleBox.getAlpha() >= 1f) {   // not while it's faded away or fading
                        backdropTitleBox.setAlpha(0f);
                        backdropTitleBox.animate().alpha(1f).setDuration(300).start();
                    }
                });
            }, "TitleDetails").start();
        }
        if (detailed && detailsPending.contains(title)) {
            for (View v : new View[]{backdropLogo, backdropTitle, backdropMeta, backdropSynopsis, backdropGenres}) {
                v.setVisibility(View.GONE);
            }
            return;
        }
        boolean left = info != null;
        LinearLayout box = (LinearLayout) backdropTitleBox;
        FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) box.getLayoutParams();
        int side = (left ? Gravity.START : Gravity.END) | Gravity.TOP;
        if (at.gravity != side) {
            at.gravity = side;
            box.setLayoutParams(at);
        }
        box.setGravity(left ? Gravity.START : Gravity.END);
        backdropLogo.setScaleType(left ? ImageView.ScaleType.FIT_START : ImageView.ScaleType.FIT_END);
        backdropLogo.setMaxHeight(dp(left ? 84 : 110));
        backdropLogo.setImageBitmap(logo);
        backdropLogo.setVisibility(logo != null ? View.VISIBLE : View.GONE);
        backdropTitle.setGravity(left ? Gravity.START : Gravity.END);
        backdropTitle.setText(title);
        backdropTitle.setVisibility(logo == null && title != null ? View.VISIBLE : View.GONE);
        String meta = "", genres = "";
        if (left) {
            if (info.score > 0) meta = "\u2605 " + String.format(java.util.Locale.getDefault(), "%.1f", info.score);
            if (!info.year.isEmpty()) meta += (meta.isEmpty() ? "" : "     ") + info.year;
            genres = android.text.TextUtils.join("  \u2022  ", info.genres);
        }
        backdropMeta.setText(meta);
        backdropMeta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        backdropSynopsis.setText(left ? info.overview : "");
        backdropSynopsis.setVisibility(left && !info.overview.isEmpty() ? View.VISIBLE : View.GONE);
        backdropGenres.setText(genres);
        backdropGenres.setVisibility(genres.isEmpty() ? View.GONE : View.VISIBLE);
        placeBackdropTitle(left);
    }

    /** The title box's height with what's in it now, measured here so it's placed before it's next drawn. */
    private int backdropTitleHeight() {
        View parent = (View) backdropTitleBox.getParent();
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) backdropTitleBox.getLayoutParams();
        int width = Math.max(0, parent.getWidth() - m.getMarginStart() - m.getMarginEnd());
        backdropTitleBox.measure(View.MeasureSpec.makeMeasureSpec(width,
                        parent.getWidth() > 0 ? View.MeasureSpec.AT_MOST : View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return backdropTitleBox.getMeasuredHeight();
    }

    /**
     * The title's place: at the right, its foot level with the app name's; with its details, at the left, its
     * foot over the app's name, and no higher than the top bar's foot: where it doesn't fit under that (a
     * folder's row of apps has the name higher), the synopsis goes.
     */
    private void placeBackdropTitle(boolean left) {
        if (!left) {
            backdropTitleBox.setY(heroName.getY() + heroName.getHeight() - backdropTitleHeight());
            return;
        }
        float top = heroName.getY() - dp(14) - backdropTitleHeight();
        float ceiling = settings.topBarMinimal() ? dp(66) : topBar.getHeight() + dp(12);
        if (top < ceiling && backdropSynopsis.getVisibility() == View.VISIBLE) {
            backdropSynopsis.setVisibility(View.GONE);
            top = heroName.getY() - dp(14) - backdropTitleHeight();
        }
        backdropTitleBox.setY(Math.max(ceiling, top));
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        hideShelf(true);   // off the dock: gone before the rows move, not fading over them
        if (!heroShown) return;
        heroShown = false;
        stillShown = false;
        wallpaperVideo.pause();   // off the dock: kept, and on again when focus comes back
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
        backdrop.animate().alpha(0f).setDuration(300).withLayer().start();
        backdropPkg = null;
        showFlights();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (adapter.isFolder(position) && adapter.inDock(position) && focusShelf()) {
                cancelPictureSoon();
                grid.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                return true;                               // a folder: up into its apps above it
            }
            if (adapter.inDock(position)) {                // dock row: the picture alone, then the top bar
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
            target = adapter.above(position);   // from the row under the dock, the nearest dock tile
        } else {
            target = adapter.below(position);
            if (target == position) return true;           // already on the last row: stay put
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
        if (hasWindowFocus()) wallpaperVideo.resume();   // the picture alone: a video wallpaper plays
        if (shelf != null) shelf.animate().alpha(0f).setDuration(CARET_FADE_MS).start();   // goes with the rows
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
        // The folder's dial comes back last, with the app's name once the rows are in place (showName); with
        // auto-hide, Up brings only the top bar and the rows (and the dial) wait for focus to come back down.
        cancelHold();
        // Down (or Back): onto the dock tile, or the one left before it was focused (focusAfterSlide), unless
        // going up again; a folder's mini row first, as Up came through it on the way to the picture.
        if (!toTopBar) {
            int at = focusAfterSlide >= 0 ? focusAfterSlide : onDock() ? lastFocused : -1;
            if (at >= 0) focusDockFromAbove(at);
        }
        focusAfterSlide = -1;
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
            if (shelf != null && shelfFolder != null) {
                shelf.animate().cancel();
                shelf.setAlpha(1f);
            }
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
                // The pill above the tile, or the first: above a dock folder, Android's focus search found its
                // mini row instead, between the dock and the bar.
                View card = getCurrentFocus();
                View above = card != null ? card.focusSearch(View.FOCUS_UP) : null;
                View[] pills = topBarPills();
                if (!isPill(above)) above = pills.length > 0 ? pills[0] : null;
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
        if (backdropPkg == null || !showsContent(backdropKey)) return;   // set to something else: no app pictures
        playInfo.setVisibility(View.GONE);
        backdrops.openCarousel(backdropPkg, jump, false, (pkg, bitmap, logo, title, link) -> {
            if (!pictureOnly || !pkg.equals(backdropPkg)) return;
            if (bitmap != null) {
                backdropLink = link;
                backdropKind = Wallpapers.CONTENT;   // whatever stood in for it till now (the flights): its own
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
        if (shelf != null && shelfFolder != null) shelf.animate().alpha(1f).setDuration(260).start();
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
        // Channel Up and Down, when Settings says so: nothing at all on Home, not the TV's own TV app.
        if ((code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN)
                && settings != null && settings.ignoreChannelKeys()) {
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            lastKeyAt = SystemClock.uptimeMillis();
            armIdle();
            if (slideshow) {   // any other key: Home as it was, on the picture showing and its app
                swallowUp = event.getKeyCode();
                endSlideshow(true);
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
        focusAfterSlide = adapter.dockTileFor(lastFocused);
        showPictureOnly(SoundEffectConstants.NAVIGATION_DOWN);
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
     * one right above the dock while on it (to the picture alone) and one under it (to the other apps, if
     * there are any), and one just under the top bar while on it (to the picture alone). None on the lower
     * rows, and none at all when they're turned off (Settings > Appearance).
     */
    private void updateCarets() {
        // None while the menus move, in the slideshow, or under a menu or dialog (Home hasn't the focus).
        boolean shown = !caretsHeld && !slideshow && hasWindowFocus();
        boolean on = shown && settings.carets();   // the Play pill isn't an arrow: it stays when they're off
        boolean more = on && pictureOnly && (wallpaperShown ? wallpapers.listOf(wallpaperList).size() > 1
                : backdropPkg != null && showsContent(backdropKey) && backdrops.stillCount(backdropPkg) > 1);
        boolean dock = on && !pictureOnly && onDock();
        showCaret(caretDock, dock);
        showCaret(caretMore, dock && adapter.getItemCount() > adapter.dockCount());
        caretBar.setY(topBar.getHeight() + 4 * density);   // just under the bar
        showCaret(caretBar, on && !pictureOnly && onTopBar());
        showCaret(caretUp, on && pictureOnly);
        showCaret(caretDown, on && pictureOnly);
        showCaret(caretLeft, more);
        showCaret(caretRight, more);
        showPlay(shown && pictureOnly && stillShown && !wallpaperShown);   // nothing to play on the glow or a wallpaper
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
     * Where the Play pill goes: its left edge level with a full dock's first tile's (wherever a shorter dock
     * starts), its bottom edge 12dp above where
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
            // Where a full dock's first tile is: a shorter dock is centred, and its first tile further in.
            float cell = (grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight()) / (float) COLUMNS;
            int left = r.left - Math.round(adapter.dockInset() * cell);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holdPlay.getLayoutParams();
            if (lp.leftMargin != left) {
                lp.leftMargin = left;
                lp.setMarginStart(left);
                holdPlay.setLayoutParams(lp);
            }
        }
    }

    /** Under the Play pill: "45 minutes remaining" when you're part-way through the picture's title. */
    private void updatePlayInfo() {
        long left = backdrops.remainingMs(backdropLink);
        String channel = backdrops.channelLabel(backdropLink);   // HDHomeRun: "2.2 WCBSDT2"
        if (left <= 0) {
            playInfo.setText(channel);
            playInfo.setVisibility(channel != null ? View.VISIBLE : View.GONE);
            return;
        }
        long minutes = Math.max(1, (left + 59_999) / 60_000);   // rounded up: never "0 minutes"
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        // "1 hour, 20 minutes", "45 minutes", in the language Home is in.
        MeasureFormat format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE);
        Measure hours = new Measure(minutes / 60, MeasureUnit.HOUR), rest = new Measure(minutes % 60, MeasureUnit.MINUTE);
        String time = minutes < 60 ? format.format(rest)
                : minutes % 60 == 0 ? format.format(hours) : format.formatMeasures(hours, rest);
        String remaining = getString(R.string.time_remaining, time);
        playInfo.setText(channel != null ? channel + "  ·  " + remaining : remaining);
        playInfo.setVisibility(View.VISIBLE);
    }

    /**
     * Whether the top bar should show: always, unless set to hide (Settings, on by default). Then only
     * while it has focus or the grid below the dock does; not on the dock or with the picture alone.
     */
    private boolean barWanted() {
        if (settings.topBarMinimal()) return !pictureOnly && !slideshow;   // small enough to stay up
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
        return holder != null && holder.getBindingAdapterPosition() >= adapter.dockCount();
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
        return position != RecyclerView.NO_POSITION && adapter.inDock(position);
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
     * How much of the Play pill shows its focused look, from 0 to 1: revealed from the side the screen is
     * laid out from, as everything else on it is (the left, in every language: Vibe Launcher isn't mirrored).
     */
    private void setHoldFill(float fill) {
        int w = holdPlayFill.getWidth(), h = holdPlayFill.getHeight();
        int filled = Math.round(w * Math.max(0f, Math.min(1f, fill)));
        holdPlayFill.setClipBounds(new Rect(0, 0, filled, h));
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
            // No title to open, or an HDHomeRun channel the TV hasn't in its channel list: the app.
            if (link == null || link.startsWith(HdHomeRun.CHANNEL_MARK)) throw new ActivityNotFoundException();
            if (link.startsWith(HdHomeRun.CHANNEL_LINK)) {
                // A TV channel (HDHomeRun's, in the TV's channel list): tuned in the TV's own player, whichever
                // app that is, not handed to the HDHomeRun app (it doesn't open channel links).
                Intent tune = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link));
                if (tune.resolveActivity(getPackageManager()) == null) throw new ActivityNotFoundException();
                tune.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(tune);
                return;
            }
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
        } catch (URISyntaxException | RuntimeException e) {   // any way another app's link can't be opened
            Intent app = pkg == null ? null : getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (app == null && pkg != null) app = getPackageManager().getLaunchIntentForPackage(pkg);
            if (app != null) open(app);
            else showError(this, R.string.not_available);
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
        for (int i = 0; i < adapter.dockCount(); i++) {
            String pkg = adapter.packageAt(i);
            if (pkg != null) dock.add(pkg);
            // A folder in the dock: its apps have pictures, a pool and slides, as dock apps do.
            for (AppAdapter.Entry m : adapter.membersAt(i)) dock.add(m.pkg);
        }
        return dock;
    }

    /**
     * The dock positions holding an app, or a folder showing one: not the All apps tile, which has no pictures. Its own list, as the
     * dock's positions and dockApps' indexes differ once All apps is in the dock.
     */
    private List<Integer> dockAppPositions() {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < adapter.dockCount(); i++) {
            // An app's tile, or a folder's with an app to show (the one picked on its row).
            if (adapter.isFolder(i) ? folderApp(i) != null : adapter.packageAt(i) != null) positions.add(i);
        }
        return positions;
    }

    /**
     * Left alone on Home: the rows and the top bar go, as for the picture alone, and the pictures of every
     * dock app follow each other at random with nothing over them. Not while a menu, Settings or a tile's
     * move is open (Home hasn't the window's focus, or a tile is moving): then the wait starts over.
     */
    private void startSlideshow() {
        if (slideshow || !started || !hasWindowFocus() || adapter.isMoving() || folderView != null
                || dockApps().isEmpty()) {
            armIdle();
            return;
        }
        slideshow = true;
        slidePkg = backdropPkg;
        slideEntry = null;
        slideOfList = false;
        glass.animate().alpha(0f).setDuration(CARET_FADE_MS).start();
        showSlideClock(pictureOnly ? 0 : PICTURE_SLIDE_MS);   // once the top bar, with its clock, is away
        if (!pictureOnly) {
            if (!onDock()) focusAfterSlide = adapter.dockTileFor(lastFocused);   // the top bar or a lower row
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
        // A video wallpaper showing now plays to its end before the first slide, like every video after it.
        wallpaperVideo.setOnce(true);
        slideAt = android.os.SystemClock.uptimeMillis();
        slideVideoEnded = false;
        slideLoadingAt = 0;
        slideFirst = true;
        handler.postDelayed(nextSlide, PICTURE_SLIDE_MS + CARET_FADE_MS);
        showFlights();
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

    /**
     * The next slide, by Settings > Appearance > Screensaver backdrops: the slideshow's own list; what the app or
     * folder selected shows (the one whose backdrop Home shows); or what every dock app shows. Its own list
     * empty, or the selected one with nothing to show: every dock app's. Each tile brings what it's set to show
     * on Home (slideSources).
     */
    private void nextSlide() {
        if (!slideshow) return;
        if (videoOnScreen && wallpaperVideo.isShowing() && !slideVideoEnded) {
            // A video isn't cut: the next slide comes at its end (onEnded). Looked at again meanwhile, in case
            // something else took its place or it never gets there.
            handler.postDelayed(nextSlide, VIDEO_WAIT_MS);
            return;
        }
        slideVideoEnded = false;
        handler.postDelayed(nextSlide, SLIDE_MS);
        int from = settings.slideshowFrom();
        if (from == VibeSettings.SLIDES_OWN && wallpapers.hasList(Wallpapers.SCREENSAVER)) {
            slideFromList(Wallpapers.SCREENSAVER);
            return;
        }
        // What can show: the apps showing pictures of their own, and each list a tile shows instead, once.
        List<String> pools = new ArrayList<>(), lists = new ArrayList<>();
        boolean air = false;   // the flights are among them
        if (from == VibeSettings.SLIDES_SELECTED) air = slideSources(backdropKey, selectedApps(), pools, lists);
        if (pools.isEmpty() && lists.isEmpty() && !air) {   // every app's: asked for, or the selected one has nothing
            for (String pkg : dockApps()) {
                air |= slideSources(choiceKeyFor(pkg), Collections.singletonList(pkg), pools, lists);
            }
        }
        // The slideshow's first: not the still Home is showing, which the slideshow hasn't shown itself.
        Backdrops.Entry showing = slideEntry;
        if (showing == null && slideFirst && stillShown && !wallpaperShown) showing = backdrops.shownOf(backdropPkg);
        slideFirst = false;
        // Each app with pictures, each list and the flights as likely as the next.
        int count = pools.size() + lists.size() + (air ? 1 : 0);
        int pick = count == 0 ? -1 : slideRandom.nextInt(count);
        if (air && pick == count - 1) {   // the flights: their board over the glow
            if (backdropKind == Wallpapers.FLIGHTS && !wallpaperShown && !stillShown) return;   // up already
            slideEntry = null;
            slideOfList = true;
            backdropKind = Wallpapers.FLIGHTS;
            fadeBackdrop(null, null, null, backdropColor);
            return;
        }
        if (pick >= pools.size()) {
            slideFromList(lists.get(pick - pools.size()));
            return;
        }
        if (pick < 0) return;   // nothing anywhere: Home stays as it is
        slideLoadingAt = android.os.SystemClock.uptimeMillis();
        backdrops.randomSlide(pools, showing, (pkg, entry, bitmap, logo) -> {
            slideLoadingAt = 0;
            if (!slideshow) return;
            slidePkg = pkg;
            slideEntry = entry;
            slideOfList = false;
            backdropKind = Wallpapers.CONTENT;
            fadeBackdrop(bitmap, logo, entry.title, backdropColor);
        });
    }

    // ---- flights overhead ------------------------------------------------------------------------------

    private FlightBoard flightBoard;
    private Flights flights;

    /** Home is in front (between onResume and onPause): the aircraft aren't asked for otherwise. */
    private boolean inFront;
    /** The place the flights are looked for is being found (applyFlights). */
    private boolean placeAsked;

    /**
     * Home in front: the board by what shows now (showFlights). With the flights enabled somewhere and no place
     * known for them yet, that's found first (the weather's), and the tile in focus shown again by it.
     */
    private void applyFlights() {
        if (flightsPlace() == null && !placeAsked && (wallpapers.flightsUsed() || settings.flightsOverColour())) {
            placeAsked = true;
            Context app = getApplicationContext();
            new Thread(() -> {
                Weather.Place found = Weather.place(app, settings);
                handler.post(() -> {
                    placeAsked = false;
                    if (found == null || !heroShown || lastFocused < 0 || !adapter.inDock(lastFocused)) return;
                    backdropPkg = null;
                    backdropKey = null;
                    showBackdropAt(lastFocused);
                });
            }).start();
        }
        showFlights();
    }

    /** What the board hears of the aircraft near here, every few seconds while they're asked for. */
    private final Flights.Listener flightsHeard = (near, all) -> {
        flightBoard.setFlights(near, all, flights.place(), settings.flightsKm());
        showFlights();
    };

    /**
     * The board is up while the flights are what shows behind the dock (or as the slideshow's slide): its map at
     * once, and a card for each aircraft in range as they're heard of. Or, where Settings says so, the card
     * alone over a tile showing its colour. The aircraft are asked for only while it's up.
     */
    private void showFlights() {
        if (flightBoard == null) return;   // Home still being made
        Weather.Place place = flightsPlace();
        boolean up = (heroShown || slideshow) && place != null && inFront;
        boolean map = up && backdropKind == Wallpapers.FLIGHTS;
        boolean card = up && backdropKind == Wallpapers.COLOUR && settings.flightsOverColour();
        // Its foot where the app's name has its over a folder's row of apps, whether this is a folder or not:
        // clear of that row, and in the one place on every tile.
        flightBoard.setCeiling(settings.topBarMinimal() ? dp(66) : topBar.getHeight() + dp(12));
        if (dockPanelTop > 0 && grid.getWidth() > 0) {
            flightBoard.setFoot(dockPanelTop - 48 * density - AppAdapter.tileHeightPx(shelfTileWidth()));
        }
        if (map) flightBoard.setHere(place, settings.flightsKm());
        flightBoard.setWanted(map || card, settings.flightsImperial(), map);
        if (map || card) flights.start(flightsHeard);
        else flights.stop();
    }

    /**
     * What a tile brings to the slideshow, by what it shows on Home (key's order) and the apps whose pictures
     * are its own: those of them with pictures (pools), or the list it shows instead (lists, each list once).
     * Its colour: nothing. True when it's the flights it shows.
     */
    private boolean slideSources(String key, List<String> apps, List<String> pools, List<String> lists) {
        boolean stills = false;
        for (String pkg : apps) stills |= backdrops.stillCount(pkg) > 0;
        char kind = pick(key, stills);
        String list = null;
        if (kind == Wallpapers.CONTENT) {
            for (String pkg : apps) {
                if (backdrops.stillCount(pkg) > 0 && !pools.contains(pkg)) pools.add(pkg);
            }
        } else if (kind == Wallpapers.OWN) {
            list = key;
        } else if (kind == Wallpapers.GALLERY) {
            list = Wallpapers.ROTATION;
        }
        if (list != null && wallpapers.hasList(list) && !lists.contains(list)) lists.add(list);
        return kind == Wallpapers.FLIGHTS;
    }

    /** A wallpaper as the slide. A video then plays to its end before the next one (nextSlide), not SLIDE_MS. */
    private void showSlideItem(Wallpapers.Item item) {
        showWallpaperItem(item, backdropColor);
        if (!item.video) return;
        handler.removeCallbacks(videoAgain);
        slideAt = android.os.SystemClock.uptimeMillis();
    }

    /** The slideshow's next from a list of wallpapers: another than the one showing, when there is one. */
    private void slideFromList(String key) {
        Wallpapers.Item item = wallpapers.next(key);
        wallpaperList = key;
        backdropKind = Wallpapers.ROTATION.equals(key) ? Wallpapers.GALLERY : Wallpapers.OWN;
        if (item != null) showSlideItem(item);
        slideEntry = null;
        slideOfList = true;
        showFlights();
    }

    /** The apps whose pictures are the selected tile's: a folder's apps, else the app whose backdrop shows. */
    private List<String> selectedApps() {
        List<String> apps = backdropKey != null && backdropKey.startsWith(Folders.KEY_PREFIX)
                ? appsOf(backdropKey) : new ArrayList<>();
        if (apps.isEmpty() && backdropPkg != null) apps.add(backdropPkg);
        return apps;
    }

    /**
     * The slideshow stops on the picture showing, with that picture's app as the one picked. With toRows (a
     * key pressed), the rows and the top bar come back on its tile: stopped on the picture alone, which looks
     * the same as the slideshow, the key seemed to do nothing. Else (Home going away) the picture alone.
     */
    private void endSlideshow(boolean toRows) {
        slideshow = false;
        slideClock.animate().cancel();
        slideClock.animate().alpha(0f).setStartDelay(0).setDuration(CARET_FADE_MS)
                .withEndAction(() -> slideClock.setVisibility(View.INVISIBLE)).start();
        handler.removeCallbacks(nextSlide);
        handler.removeCallbacks(videoAgain);
        slideLoadingAt = 0;
        handler.removeCallbacks(letScreenOff);
        wallpaperVideo.setOnce(false);   // round and round again, as on Home
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int at = -1;   // the stopped-on app's dock position
        for (int position : dockAppPositions()) {
            if (slidePkg != null && slidePkg.equals(adapter.packageAt(position))) at = position;
        }
        if (at < 0 && slidePkg != null) {   // a folder's app: its folder's tile, showing that app
            at = adapter.dockFolderHolding(slidePkg);
            if (at >= 0) pickFolderApp(at, slidePkg);
        }
        if (slideEntry != null && at >= 0) {
            backdrops.keep(slidePkg, slideEntry);
            backdropPkg = slidePkg;   // already showing: focusing its tile doesn't load another
            backdropKey = listKeyAt(at);   // and it's judged by its own tile's order, not the one focused before
            backdropKind = Wallpapers.CONTENT;
            backdropLink = slideEntry.link;
            backdropColor = adapter.ambientColorAt(at);
            focusAfterSlide = -1;
            focusTile(at);
            quietHero(at);
            glass.animate().cancel();   // the Play pill, not the glass, with the picture alone
            glass.setAlpha(0f);
        } else if (slideOfList) {
            // One of a list that may not be the tile's (the slideshow's own, or the System backdrop's): the tile's
            // own backdrop again, which is the same one where the list is what Home shows for it.
            backdropPkg = null;
            backdropKey = null;
            if (lastFocused >= 0 && adapter.getItemCount() > 0) showBackdropAt(adapter.dockTileFor(lastFocused));
        }
        slideOfList = false;
        if (toRows) {
            showRows(true, false);
        } else {
            if (pictureOnly && !caretsHeld) updateCarets();
            if (pictureOnly) openCarousel(false);   // the stopped-on app's carousel, and the time left on it
        }
        armIdle();
        showFlights();
    }

    /**
     * Left or Right held with the picture alone: the next or previous dock app, going round from the last to
     * the first (the dock itself stops at its ends). Its tile takes focus out of sight, name and all, so the
     * rows come back on it; the carousel opens on what you're watching in it, else this visit's picture.
     */
    private void switchApp(int direction) {
        List<Integer> dock = dockAppPositions();
        if (dock.size() < 2) return;
        int at = Math.max(0, dock.indexOf(adapter.dockTileFor(lastFocused)));
        int next = dock.get(((at + direction) % dock.size() + dock.size()) % dock.size());   // a dock position
        String pkg = adapter.isFolder(next) ? folderApp(next) : adapter.packageAt(next);
        cancelHold();
        backdropPkg = pkg;   // set first: focusing its tile then doesn't load a picture of its own
        String key = listKeyAt(next);
        backdropKey = key;
        backdropColor = adapter.ambientColorAt(next);
        backdropLink = null;
        focusTile(next);
        quietHero(next);
        glass.animate().cancel();
        glass.setAlpha(0f);
        playInfo.setVisibility(View.GONE);
        grid.playSoundEffect(direction > 0
                ? SoundEffectConstants.NAVIGATION_RIGHT : SoundEffectConstants.NAVIGATION_LEFT);
        char kind = pick(key, true);
        if (kind != Wallpapers.CONTENT) {   // something else comes first: no app pictures
            showKind(kind, key, backdropColor);
            updateCarets();
            return;
        }
        backdrops.openCarousel(pkg, true, true, (loaded, bitmap, logo, title, link) -> {
            if (!pictureOnly || !loaded.equals(backdropPkg)) return;
            backdropLink = link;
            showPicture(bitmap, logo, title, backdropColor);
            updatePlayInfo();
            updateCarets();
        });
    }

    /** The focused app's next or previous picture, cross-faded in. */
    private void stepPicture(int direction) {
        if (wallpaperShown) {
            // A wallpaper is what shows (the tile's list, or an app with no picture of its own): the next one.
            Wallpapers.Item item = wallpapers.step(wallpaperList, direction);
            if (item == null) return;
            cancelHold();
            showWallpaperItem(item, backdropColor);
            grid.playSoundEffect(direction > 0
                    ? SoundEffectConstants.NAVIGATION_RIGHT : SoundEffectConstants.NAVIGATION_LEFT);
            return;
        }
        if (backdropPkg == null || !showsContent(backdropKey)) return;
        backdrops.step(backdropPkg, direction, (loaded, bitmap, logo, title, link) -> {
            if (!pictureOnly || !loaded.equals(backdropPkg)) return;   // back to the rows meanwhile
            cancelHold();
            backdropLink = link;
            if (bitmap != null) backdropKind = Wallpapers.CONTENT;
            fadeBackdrop(bitmap, logo, title, backdropColor);
            updatePlayInfo();
            updateCarets();
        });
        grid.playSoundEffect(direction > 0
                ? SoundEffectConstants.NAVIGATION_RIGHT : SoundEffectConstants.NAVIGATION_LEFT);
    }

    void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        // One wait for a scroll at a time, and none once a tile is focused here: a wait left behind would pull
        // focus to its own tile when some later scroll settles.
        if (pendingFocus != null) {
            grid.removeOnScrollListener(pendingFocus);
            pendingFocus = null;
        }
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        RecyclerView.OnScrollListener waiting = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) focusSettled(this, pos);
            }
        };
        pendingFocus = waiting;
        grid.addOnScrollListener(waiting);
        grid.smoothScrollToPosition(pos);
        // Already in place (only not laid out yet): no scroll starts, so no settle comes; focused next frame instead.
        grid.postOnAnimation(() -> {
            if (pendingFocus == waiting && grid.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                focusSettled(waiting, pos);
            }
        });
    }

    /** focusTile's wait for a scroll to settle, if any. */
    private RecyclerView.OnScrollListener pendingFocus;

    private void focusSettled(RecyclerView.OnScrollListener waiting, int pos) {
        grid.removeOnScrollListener(waiting);
        if (pendingFocus == waiting) pendingFocus = null;
        RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
        View target = h != null ? h.itemView.findViewById(R.id.card) : grid.getChildAt(0);
        if (target != null) target.requestFocus();
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
        boolean h24 = use24Hour();
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
        // Each hour once, with every digit as the font's widest: as wide as any minute of that hour gets, in
        // 24 measurements instead of one per minute of the day (1,440, on the main thread at a cold start).
        Calendar time = Calendar.getInstance();
        time.set(Calendar.MINUTE, 0);
        timeBoxWidths.clear();
        for (int hour = 0; hour < 24; hour++) {
            time.set(Calendar.HOUR_OF_DAY, hour);
            String s = DateFormat.format(format, time).toString();
            int w = (int) Math.ceil(clock.getPaint().measureText(widestDigits(s, clock.getPaint())) + extra);
            Integer known = timeBoxWidths.get(s.length());
            if (known == null || w > known) timeBoxWidths.put(s.length(), w);
        }
        fitTimeBox(clock.getText());
    }

    /** 24-hour time: chosen in Settings, else as the TV is set. */
    private boolean use24Hour() {
        int hours = settings.clockHours();
        return hours == 0 ? DateFormat.is24HourFormat(this) : hours == 24;
    }

    /**
     * The clocks' formats (the top bar's and the screensaver's): as the layout has them (TextClock follows the
     * TV's setting), or both set to the format chosen in Settings, so the TV's setting no longer picks one.
     */
    private void applyClockHours() {
        int hours = settings.clockHours();
        String time = hours == 24 ? "HH:mm" : "h:mm", half = hours == 24 ? "" : "a";
        for (int[] ids : new int[][]{{R.id.clock, R.id.ampm}, {R.id.slideTime, R.id.slideAmPm}}) {
            TextClock clock = findViewById(ids[0]), ampm = findViewById(ids[1]);
            clock.setFormat12Hour(hours == 0 ? "h:mm" : time);
            clock.setFormat24Hour(hours == 0 ? "HH:mm" : time);
            ampm.setFormat12Hour(hours == 0 ? "a" : half);
            ampm.setFormat24Hour(hours == 0 ? "" : half);
        }
    }

    /**
     * The text with each digit as the widest of its script's ten (the digits differ in width). Any script:
     * the time can come in Arabic-Indic or Devanagari digits, which a plain [0-9] would miss.
     */
    static String widestDigits(String text, Paint paint) {
        StringBuilder out = new StringBuilder(text.length());
        char zero = 0, widest = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int value = Character.digit(c, 10);
            if (value < 0) {
                out.append(c);
                continue;
            }
            char z = (char) (c - value);
            if (z != zero) {   // this script's widest digit, worked out once
                zero = z;
                widest = z;
                for (char d = z; d < z + 10; d++) {
                    if (paint.measureText(String.valueOf(d)) > paint.measureText(String.valueOf(widest))) widest = d;
                }
            }
            out.append(widest);
        }
        return out.toString();
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
        showNotice(this, R.string.permissions_title, getString(R.string.permissions_why), R.string.continue_button,
                () -> requestPermissions(missing.toArray(new String[0]), 1), this::askHomeOnce);
        return true;
    }

    /**
     * Opened as an app rather than as Home: once, on its first start, Settings > Advanced > Default launcher,
     * which makes it Home through the TV's own ADB and walks through turning that on. Not on the players where
     * another Home stops casting (TvControl.homeUnsupported). The gear menu offers it again later.
     */
    private void askHomeOnce() {
        if (getPreferences(MODE_PRIVATE).getBoolean("askedHome", false) || DefaultHome.isHome(this)
                || TvControl.homeUnsupported()) {
            askPowerOnServiceOnce();
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putBoolean("askedHome", true).apply();
        offerHome();
    }

    /**
     * Home at power-on on, with neither Home nor the Home button service to do it (HomeButtonLink.powerOnNeedsIt):
     * once, the service offered, saying why; Settings > General offers it again whenever the setting is turned on.
     */
    private void askPowerOnServiceOnce() {
        if (HomeButtonLink.askedForPowerOn(this) || !HomeButtonLink.powerOnNeedsIt(this)) return;
        HomeButtonLink.offerForPowerOn(this, null);
    }

    /** Settings > Advanced > Default launcher, already open. */
    void offerHome() {
        inSettings = true;
        startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.ON_DEFAULT_LAUNCHER, true));
        overridePendingTransition(R.anim.settings_in, R.anim.settings_hold);
    }

    /**
     * Whether this screen can't show a window any more: closing, or closed (work that took a while can end
     * after it has, and a window opened on it then fails).
     */
    static boolean gone(Activity activity) {
        return activity == null || activity.isFinishing() || activity.isDestroyed();
    }

    /**
     * Something that went wrong, in a window that stays until OK: a message at the bottom of the screen went before
     * it could be read, or acted on. Progress and hints stay at the bottom.
     */
    static void showError(Activity activity, CharSequence text) {
        if (gone(activity)) return;
        View panel = activity.getLayoutInflater().inflate(R.layout.dialog_update,
                activity.findViewById(android.R.id.content), false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(R.string.app_name);
        ((TextView) panel.findViewById(R.id.notes)).setText(text);
        TextView okButton = panel.findViewById(R.id.install);
        okButton.setText(android.R.string.ok);
        panel.findViewById(R.id.cancelUpdate).setVisibility(View.GONE);
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (560 * activity.getResources().getDisplayMetrics().density),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        okButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        okButton.requestFocus();
    }

    static void showError(Activity activity, int text) {
        if (activity != null) showError(activity, activity.getString(text));
    }

    /** A note with OK (named okLabel) and Cancel, in the update dialog's glass. Back counts as Cancel. */
    static void showNotice(Activity activity, int title, CharSequence text, int okLabel, Runnable ok,
            Runnable cancel) {
        if (gone(activity)) return;
        View panel = activity.getLayoutInflater().inflate(R.layout.dialog_update,
                activity.findViewById(android.R.id.content), false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(title);
        ((TextView) panel.findViewById(R.id.notes)).setText(text);
        TextView okButton = panel.findViewById(R.id.install);
        okButton.setText(okLabel);
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (560 * activity.getResources().getDisplayMetrics().density),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
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
            ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);
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
        // Minimal: the icon alone, its name spoken instead of shown.
        btnWifi.setContentDescription(text);
        if (settings.topBarMinimal()) text = "";
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
    String wifiName() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        String ssid = info != null ? info.getSSID() : null;
        if (ssid == null) return null;
        ssid = ssid.replace("\"", "").trim();
        return ssid.isEmpty() || ssid.contains("unknown") || ssid.equals("0x") ? null : ssid;
    }

    /** The dock band's and the top bar's opacity, as Appearance has them. */
    private void applyOpacities() {
        if (frost != null) frost.setOpacity(settings.topBarOpacity());
        if (topBar != null) minimalGlass();
        if (dockDecoration == null) return;
        dockDecoration.setOpacity(settings.dockOpacity());
        grid.invalidate();
    }

    /** The highlight color on the glass, the top bar's pills and the Play pill's fill (menus take it as made). */
    private void applyHighlight() {
        highlight = Highlight.color(this);
        glass.setTint(highlight);
        for (int id : new int[]{R.id.btnInputs, R.id.btnWifi, R.id.btnSettings}) {
            Highlight.pill(findViewById(id), highlight);
        }
        Highlight.filled((TextView) holdPlayFill, highlight);
        if (topBar != null) minimalGlass();   // whose buttons are filled their own way
    }

    /** Vibe Launcher settings: their own screen over Home, made ahead (SettingsScreen), fading in. */
    void openSettings() {
        inSettings = true;
        startActivity(new Intent(this, SettingsActivity.class));
        overridePendingTransition(R.anim.settings_in, R.anim.settings_hold);
    }

    /**
     * The update dot in the gear's hollow centre, measured from where the gear is drawn (the pill's padding on the
     * gear's side, half the gear, and the middle of its height): right in either top bar style and either direction.
     */
    private void placeUpdateDot() {
        TextView pill = findViewById(R.id.btnSettings);
        View dot = findViewById(R.id.updateDot);
        android.graphics.drawable.Drawable[] d = pill.getCompoundDrawables();   // left and right, as laid out
        boolean onLeft = d[0] != null;
        android.graphics.drawable.Drawable gear = onLeft ? d[0] : d[2];
        if (gear == null || dot.getWidth() == 0) return;   // the dot's own layout brings it here again
        int w = gear.getBounds().width();
        float cx = onLeft ? pill.getPaddingLeft() + w / 2f : pill.getWidth() - pill.getPaddingRight() - w / 2f;
        float cy = pill.getPaddingTop() + (pill.getHeight() - pill.getPaddingTop() - pill.getPaddingBottom()) / 2f;
        dot.setX(pill.getX() + cx - dot.getWidth() / 2f);
        dot.setY(pill.getY() + cy - dot.getHeight() / 2f);
    }

    /** The green dot in the Settings gear: shown while a newer release is waiting to be installed. */
    void showUpdateReady() {
        boolean ready = Updater.available(this) != null;
        findViewById(R.id.updateDot).setVisibility(ready ? View.VISIBLE : View.GONE);
    }

    void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Screens.open(this, i);   // a window where the TV has no such screen
    }

    /** Hides the Inputs pill on devices with nothing to switch to (e.g. a Shield: no HDMI-in, no tuner). */
    private void updateInputsButton() {
        boolean any = topBarMenus.getLiveTvIntent() != null;
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

    /** A glass dropdown (the Inputs look). Rows go in R.id.rows; show it with {@link #showMenu}. */
    PopupWindow newMenu(View panel) {
        PopupWindow popup = new PopupWindow(panel, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        return popup;
    }

    void showMenu(PopupWindow popup, View panel, LinearLayout rows, View anchor, int gravity) {
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
        tileMenus.onTileLongPress(position, card);
    }

    // ---- the weather under the date ---------------------------------------------------------------------

    private TextView weatherView;
    private View dateBox;
    private java.util.concurrent.ExecutorService weatherThread;
    private final Runnable weatherTick = this::refreshWeather;

    /**
     * The weather line (Weather), when it's on in Settings: looked up off the main thread (reused for 20 minutes,
     * so this is cheap), and again every 20 minutes while Home is in front. Off, or not known, it isn't shown.
     */
    void refreshWeather() {
        handler.removeCallbacks(weatherTick);
        if (weatherView == null) return;
        if (!settings.weatherOn()) {
            showWeather(null);
            return;
        }
        if (isDestroyed()) return;
        if (weatherThread == null) weatherThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        Context app = getApplicationContext();
        VibeSettings s = settings;
        boolean fahrenheit = s.weatherFahrenheit();
        weatherThread.execute(() -> {
            Weather.Place place = Weather.place(app, s);
            Weather.Now now = place == null ? null : Weather.now(app, place, fahrenheit);
            handler.post(() -> showWeather(settings.weatherOn() ? now : null));
        });
        handler.postDelayed(weatherTick, 20L * 60 * 1000);
    }

    /** Shows it under the date (the date moves up a line), or hides it (the date back where it always is). */
    private void showWeather(Weather.Now now) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) dateBox.getLayoutParams();
        if (now == null) {
            weatherView.setVisibility(View.GONE);
            lp.bottomMargin = Math.round(17.5f * density);
            dateBox.setLayoutParams(lp);
            return;
        }
        int style = settings.topBarMinimal() ? VibeSettings.WEATHER_TEMP : settings.weatherStyle();   // minimal: a number
        StringBuilder line = new StringBuilder(Weather.degrees(now.temp));
        if (style != VibeSettings.WEATHER_TEMP) line.append("   ").append(getString(Weather.text(now.code)));
        if (style == VibeSettings.WEATHER_HIGH_LOW) {
            line.append("   ·   ").append(getString(R.string.weather_high_low, Weather.degrees(now.high),
                    Weather.degrees(now.low)));
        }
        weatherView.setText(line);
        android.graphics.drawable.Drawable icon = getDrawable(Weather.icon(now.code, now.day));
        if (icon != null) {
            icon = icon.mutate();
            int s = Math.round(17 * density);
            icon.setBounds(0, 0, s, s);
            icon.setTint(weatherView.getCurrentTextColor());
        }
        weatherView.setCompoundDrawablesRelative(icon, null, null, null);
        weatherView.setVisibility(View.VISIBLE);
        lp.bottomMargin = Math.round((settings.topBarMinimal() ? 5 : 11) * density);
        dateBox.setLayoutParams(lp);
    }

    // ---- the top bar's look ------------------------------------------------------------------------------

    /** The top bar's look as last applied (style, clock, date): changed in Settings, applied as Home comes back. */
    private String topBarApplied = "";

    /**
     * The minimal bar's time and weather on a plate like its round buttons' while the flights' map is behind them
     * (they're lost in its lines and names otherwise); bare over a picture, as ever. Drawn around them, past the
     * row's own edges, so nothing moves as it comes and goes.
     */
    private void clockPlate() {
        View row = findViewById(R.id.clockRow);
        boolean on = settings.topBarMinimal() && flightBoard != null && flightBoard.covering();
        if (on == (row.getBackground() != null)) return;
        if (!on) {
            row.setBackground(null);
            return;
        }
        GradientDrawable plate = barTint();
        row.setBackground(new android.graphics.drawable.InsetDrawable(plate, -dp(14), -dp(3), -dp(14), -dp(3)));
        row.setPadding(0, 0, 0, 0);   // which a background's insets would have changed
    }

    /** A round patch of the top bar's own dark tint (Appearance > Top bar opacity), with a faint edge. */
    private GradientDrawable barTint() {
        GradientDrawable tint = new GradientDrawable();
        tint.setColor(Percent.alpha(settings.topBarOpacity()) << 24);
        tint.setStroke(dp(1), 0x24FFFFFF);
        tint.setCornerRadius(dp(21));
        return tint;
    }

    /**
     * The minimal bar has no strip, so its round buttons (and the clock's plate, clockPlate) each carry the bar's
     * tint themselves, as dark as the strip is set to be; the highlight color when focused, as ever. The full bar's pills keep
     * their own glass.
     */
    private void minimalGlass() {
        boolean minimal = settings.topBarMinimal();
        for (int id : new int[]{R.id.btnInputs, R.id.btnWifi, R.id.btnSettings}) {
            View pill = findViewById(id);
            int l = pill.getPaddingLeft(), t = pill.getPaddingTop(), r = pill.getPaddingRight(), b = pill.getPaddingBottom();
            if (minimal) {
                GradientDrawable focused = new GradientDrawable();
                focused.setColor(Highlight.color(this));
                focused.setCornerRadius(dp(21));
                android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
                states.addState(new int[]{android.R.attr.state_focused}, focused);
                states.addState(new int[0], barTint());
                pill.setBackground(states);
                pill.setBackgroundTintList(null);   // its own colours, not the pills' white glass tinted
            } else {
                pill.setBackgroundResource(R.drawable.pill_bg);
                Highlight.pill((TextView) pill, Highlight.color(this));
            }
            pill.setPadding(l, t, r, b);
        }
        findViewById(R.id.clockRow).setBackground(null);   // made again in the tint as it is now
        clockPlate();
    }

    /**
     * The full top bar (a frosted strip: the big clock, the date, the weather, the pills with their names), or the
     * minimal one (Appearance > Top bar): no strip, a small clock and the weather's icon and number at the top left,
     * and the three buttons as small round icons at the top right, over the background, always up. The same views
     * either way, so the buttons open the same menus; their names stay as what's spoken for them.
     */
    void applyTopBarStyle() {
        boolean minimal = settings.topBarMinimal();
        String state = minimal + "|" + settings.showClock() + "|" + settings.showDate();
        if (state.equals(topBarApplied)) return;
        topBarApplied = state;
        // Minimal: frosted only where the apps scroll up under it, not a strip over the pictures.
        frost.setOnlyOverTiles(minimal);
        View inner = (View) findViewById(R.id.leftBlock).getParent();
        ViewGroup.MarginLayoutParams ip = (ViewGroup.MarginLayoutParams) inner.getLayoutParams();
        ip.topMargin = dp(minimal ? 18 : 26);
        ip.bottomMargin = dp(10);
        inner.setLayoutParams(ip);
        // Nothing cut at the bar's edge: the round buttons' focused glow reaches past them.
        ((ViewGroup) topBar).setClipChildren(false);
        ((ViewGroup) inner).setClipChildren(false);
        ((ViewGroup) inner).setClipToPadding(false);
        // Minimal: the left side (time, weather) as tall as the round buttons, centred on them; the buttons from the
        // top. Full: the pills sit level with the bottom of the big clock, as they always have.
        LinearLayout left = findViewById(R.id.leftBlock);
        // Never shorter than the buttons beside it: with the clock, date and weather all off it's empty, and the
        // full bar's pills (aligned to its bottom) were cut off by the bar.
        left.setMinimumHeight(minimal ? dp(36) : dp(42 + 6));
        left.setGravity(minimal ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        TextClock clock = findViewById(R.id.clock), ampm = findViewById(R.id.ampm);
        clock.setTextSize(minimal ? 26 : 52);
        ampm.setTextSize(minimal ? 11 : 15);
        ViewGroup.MarginLayoutParams ap = (ViewGroup.MarginLayoutParams) ampm.getLayoutParams();
        ap.bottomMargin = Math.round((minimal ? 6 : 16.5f) * density);
        ampm.setLayoutParams(ap);
        findViewById(R.id.timeBox).setVisibility(settings.showClock() ? View.VISIBLE : View.GONE);
        findViewById(R.id.date).setVisibility(!minimal && settings.showDate() ? View.VISIBLE : View.GONE);
        ViewGroup.MarginLayoutParams boxLp = (ViewGroup.MarginLayoutParams) dateBox.getLayoutParams();
        boxLp.setMarginStart(settings.showClock() ? dp(minimal ? 14 : 20) : 0);
        dateBox.setLayoutParams(boxLp);
        weatherView.setTextSize(minimal ? 16 : 14);
        weatherView.setLetterSpacing(minimal ? 0.02f : 0.14f);
        int[][] pills = {{R.id.btnInputs, R.string.inputs}, {R.id.btnWifi, R.string.wifi}, {R.id.btnSettings, R.string.settings}};
        for (int[] p : pills) {
            TextView pill = findViewById(p[0]);
            ViewGroup.LayoutParams lp = pill.getLayoutParams();
            lp.height = dp(minimal ? 36 : 42);
            lp.width = minimal ? dp(36) : ViewGroup.LayoutParams.WRAP_CONTENT;
            pill.setLayoutParams(lp);
            if (minimal) pill.setPadding(dp(7), 0, dp(7), 0);
            else pill.setPaddingRelative(dp(16), 0, dp(20), 0);
            pill.setCompoundDrawablePadding(minimal ? 0 : dp(9));
            if (p[0] != R.id.btnWifi) {
                pill.setContentDescription(getString(p[1]));
                pill.setText(minimal ? "" : getString(p[1]));
            }
        }
        View buttons = findViewById(R.id.topButtons);
        android.widget.RelativeLayout.LayoutParams bl = (android.widget.RelativeLayout.LayoutParams) buttons.getLayoutParams();
        if (minimal) {
            bl.removeRule(android.widget.RelativeLayout.ALIGN_BOTTOM);
            bl.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
        } else {
            bl.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
            bl.addRule(android.widget.RelativeLayout.ALIGN_BOTTOM, R.id.leftBlock);
        }
        bl.bottomMargin = dp(minimal ? 0 : 6);
        buttons.setLayoutParams(bl);
        minimalGlass();
        // Minimal: only as wide as what's in it, for the plate it has over the flights' map (clockPlate).
        View clockRow = findViewById(R.id.clockRow);
        ViewGroup.LayoutParams rowLp = clockRow.getLayoutParams();
        rowLp.width = minimal ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT;
        clockRow.setLayoutParams(rowLp);
        left.setClipChildren(false);
        timeBoxMeasuredFor = null;   // the clock's size changed: measured again
        sizeTimeBox();
        updateWifiLabel();
        refreshWeather();
        topBar.requestLayout();
        topBar.post(this::updateTopBar);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---- a folder in the dock: its apps above it, as on tvOS's Top Shelf -------------------------------

    /**
     * A dock folder's apps over the dock, as a dial (ShelfDial): the selected app in the middle under a fixed outline,
     * Left and Right turning the row. Shown while the folder (or the dial) has focus.
     */
    private ShelfDial shelf;
    /** The folder the shelf shows, its dock position, and what's in it (to make the row again when it changes). */
    String shelfFolder, shelfContents;
    private int shelfFolderPos = -1;
    /** Each dock folder's app whose picture is shown, and in the shelf the one in the middle. */
    private final java.util.Map<String, String> folderPicks = new java.util.HashMap<>();

    /**
     * The app selected in the folder at position (in the middle of its dial), whose picture shows: the one
     * left there last time, every visit; the first until the dial has been turned. The selection decides the
     * picture, never the other way round.
     */
    private String folderApp(int position) {
        List<AppAdapter.Entry> apps = adapter.membersAt(position);
        String id = adapter.folderIdAt(position);
        if (apps.isEmpty() || id == null) return null;
        String picked = folderPicks.get(id);
        for (AppAdapter.Entry e : apps) if (e.pkg.equals(picked)) return picked;
        folderPicks.put(id, apps.get(0).pkg);   // none yet, or it left the folder: the first
        return apps.get(0).pkg;
    }

    /** The slideshow stopped on this folder app's picture: the folder shows it. */
    private void pickFolderApp(int position, String pkg) {
        String id = adapter.folderIdAt(position);
        if (id == null) return;
        folderPicks.put(id, pkg);
        shelfFolder = id;   // not a new visit: keep it
    }

    /** The shelf's tiles at 0.62 of a dock tile's width. */
    private int shelfTileWidth() {
        return Math.round(AppAdapter.tileWidthPx(grid.getWidth() - grid.getPaddingLeft()
                - grid.getPaddingRight(), COLUMNS, density) * 0.62f);
    }

    /** Shows the shelf for the folder at this dock position (made again if its apps changed); returns its top. */
    private float showShelf(int position) {
        List<AppAdapter.Entry> apps = adapter.membersAt(position);
        String id = adapter.folderIdAt(position);
        StringBuilder contents = new StringBuilder(id);
        for (AppAdapter.Entry e : apps) contents.append('|').append(e.pkg);
        if (shelf == null) {
            shelf = new ShelfDial(this, shelfHost, shelfTileWidth(), density);
            shelf.setAlpha(0f);
            // Arriving on the folder from a row below, the rows scroll the dock back up: the dial waits for
            // them to stop, then fades in, rather than fading in over icons on the move.
            grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                    if (state != RecyclerView.SCROLL_STATE_IDLE || !shelfWaitsForScroll) return;
                    shelfWaitsForScroll = false;
                    if (shelf != null && shelfFolder != null && !pictureOnly && rows.getTranslationY() == 0f) {
                        shelf.animate().alpha(1f).setDuration(260).start();
                    }
                }
            });
            shelf.selector.setOnClickListener(v -> {
                AppAdapter.Entry app = shelf.centreApp();
                if (app != null) adapter.launchApp(app);
            });
            root.addView(shelf, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        }
        if (!contents.toString().equals(shelfContents)) {
            shelfContents = contents.toString();
            shelf.setApps(new ArrayList<>(apps), folderPicks.get(id));   // the app whose picture shows, in the middle
        } else {
            shelf.setCentre(folderPicks.get(id));
        }
        shelfFolder = id;
        shelfFolderPos = position;
        int tileH = AppAdapter.tileHeightPx(shelfTileWidth());
        float top = dockPanelTop - 30 * density - tileH;
        shelf.setY(top - shelf.tileTop());   // the tiles' top where it was
        shelf.setVisibility(View.VISIBLE);
        if (!pictureOnly && rows.getTranslationY() != 0f) {
            // The rows are down (or coming back up): the dial comes with the app's name once they're in (showName).
            shelfWaitsForScroll = false;
            shelf.animate().cancel();
            shelf.setAlpha(0f);
        } else if (!pictureOnly) {
            if (grid.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {   // rows still moving: shown once they stop
                shelfWaitsForScroll = true;
                shelf.animate().cancel();
                shelf.setAlpha(0f);
            } else {
                shelfWaitsForScroll = false;
                shelf.animate().alpha(1f).setDuration(260).start();
            }
        }
        return top;
    }

    /** Waiting for the rows to stop scrolling before it fades in (showShelf). */
    private boolean shelfWaitsForScroll;

    /** Hides the folder's dial: at once (now: focus left the dock, the rows are about to move), else fading. */
    private void hideShelf(boolean now) {
        if (shelf == null || shelfFolder == null) return;
        if (shelf.hasFocus()) return;   // focus went up into it, not away
        shelfFolder = null;
        shelfWaitsForScroll = false;
        shelf.animate().cancel();
        if (now) {
            shelf.setAlpha(0f);
            shelf.setVisibility(View.INVISIBLE);
            return;
        }
        shelf.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (shelfFolder == null) shelf.setVisibility(View.INVISIBLE);
        }).start();
    }

    /**
     * Down onto the dock from above it (the top bar, the picture alone): a folder's mini row first, which sits
     * between them, the way Up goes through it; any other tile as before. A folder whose row isn't showing
     * (focus never reached it this visit) is focused first, which makes the row, and focus moves on up into it.
     */
    private void focusDockFromAbove(int position) {
        if (adapter.isFolder(position) && adapter.inDock(position)) {
            if (shelfFolder == null || shelfFolderPos != position) focusTile(position);
            if (focusShelf()) return;
        }
        focusTile(position);
    }

    /** Up from a dock folder: the dial, its app whose picture is showing in the middle. False when it has no shelf. */
    private boolean focusShelf() {
        if (shelf == null || shelfFolder == null || shelf.size() == 0) return false;
        shelf.setCentre(folderPicks.get(shelfFolder));
        return shelf.selector.requestFocus();
    }

    /** The dial's side of Home: each tile's picture, the app in the middle, and the keys it leaves to Home. */
    private final ShelfDial.Host shelfHost = new ShelfDial.Host() {
        @Override
        public void fill(FrameLayout card, AppAdapter.Entry app) {
            // Its banner (or icon), as a dock tile, smaller.
            ImageView image = new ImageView(MainActivity.this);
            android.graphics.drawable.Drawable d = app.banner != null ? app.banner : app.icon;
            if (d != null && d.getConstantState() != null) d = d.getConstantState().newDrawable().mutate();
            image.setImageDrawable(d);
            if (app.banner != null) {
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                int s = Math.round(AppAdapter.tileHeightPx(shelfTileWidth()) * 0.5f);
                card.addView(image, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
            }
        }

        @Override
        public void centred(AppAdapter.Entry app) {
            if (shelfFolder != null) folderPicks.put(shelfFolder, app.pkg);
            heroName.setText(app.label);   // the app's name while it's in the middle
            shelf.selector.setContentDescription(app.label);
            // By the app's own order, else its folder's.
            String key = wallpapers.hasOrder(app.pkg) || shelfFolder == null ? app.pkg
                    : Folders.KEY_PREFIX + shelfFolder;
            showBackdrop(key, app.pkg, adapter.ambientColorAt(shelfFolderPos));
        }

        @Override
        public boolean key(int keyCode) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER: {
                    AppAdapter.Entry app = shelf.centreApp();
                    if (app != null) adapter.launchApp(app);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_DOWN:     // back to the folder
                    focusTile(shelfFolderPos);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:       // this app's picture alone (its glow, with none), as Up from a dock app
                    focusAfterSlide = shelfFolderPos;
                    showPictureOnly(SoundEffectConstants.NAVIGATION_UP);
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public int highlight() {
            return highlight;
        }
    };

    // ---- folders --------------------------------------------------------------------------------------

    /** The open folder over Home, or null. */
    FolderView folderView;

    /** An open folder's tiles: Up and Down within it, and their own menu. */
    private final AppAdapter.TileListener folderTiles = new AppAdapter.TileListener() {
        @Override
        public void onTileFocused(int position, View card) {
        }

        @Override
        public void onMoving(boolean moving) {
            if (moving) return;
            // A move in the folder has ended, and its order is kept (after this is told): Home's tile for it,
            // its row over the dock and the app it shows first are made again from the new order.
            handler.post(() -> {
                adapter.folders.reread();
                adapter.reload();
                shelfContents = null;
            });
        }

        @Override
        public boolean onTileVerticalKey(int position, int keyCode) {
            return folderView != null && folderView.verticalKey(position, keyCode == KeyEvent.KEYCODE_DPAD_UP);
        }

        @Override
        public void onTileLongPress(int position, View card) {
            tileMenus.folderTileMenu(position, card);
        }

        @Override
        public void onTileMoved(int position) {
            if (folderView != null) folderView.focus(position);
        }
    };

    /** OK on a folder's tile: the folder opens over Home, and Home's tiles and bar can't take focus meanwhile. */
    @Override
    public void onFolderOpen(String folderId, View card) {
        if (folderView != null) return;
        adapter.folders.reread();
        Folders.Folder f = adapter.folders.get(folderId);
        if (f == null) return;
        cancelPictureSoon();
        handler.removeCallbacks(startSlideshow);
        folderView = new FolderView(this, folderId, f.name, COLUMNS, grid.getWidth(), highlight, folderTiles,
                () -> tileMenus.folderTitleMenu(folderId), this::closeFolder);
        folderView.setElevation(100 * density);   // over the glass and the bar, which have elevation
        root.addView(folderView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setHomeFocusable(false);
        folderView.open();
    }

    /** Closes the open folder, if any, back on its tile (or where it was, if it's gone). */
    void closeFolder() {
        if (folderView == null) return;
        FolderView closing = folderView;
        folderView = null;
        setHomeFocusable(true);
        int at = adapter.positionOfKey(Folders.KEY_PREFIX + closing.folderId);
        focusTile(at >= 0 ? at : lastFocused);
        closing.close(() -> root.removeView(closing));
        armIdle();
    }

    private void setHomeFocusable(boolean focusable) {
        int mode = focusable ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS;
        grid.setDescendantFocusability(mode);
        if (topBar instanceof ViewGroup) ((ViewGroup) topBar).setDescendantFocusability(mode);
        if (shelf != null) shelf.setDescendantFocusability(mode);
    }

    /** Apps came or went, or the folder changed: both lists again; an emptied folder closes. */
    void folderChanged() {
        folderChanged(false);
    }

    /**
     * emptiedByHand: the last app was moved out or hidden here, so the folder goes for good. Otherwise (an app
     * uninstalled, or missing for a moment while it updates) an empty folder only closes, and is kept.
     */
    void folderChanged(boolean emptiedByHand) {
        if (folderView != null) {
            adapter.folders.reread();
            Folders.Folder f = adapter.folders.get(folderView.folderId);
            folderView.adapter.reload();
            if (f != null && folderView.adapter.getItemCount() == 0 && emptiedByHand) {
                adapter.forgetInOrder(Folders.KEY_PREFIX + f.id);
                adapter.folders.delete(f.id);
            }
            if (f == null || folderView.adapter.getItemCount() == 0) {
                adapter.reload();
                closeFolder();
                return;
            }
            folderView.setTitle(f.name);
        }
        adapter.reload();
    }

    /** New folder: made from this app, in its place, named for its kind as tvOS does, and opened. */
    void newFolder(String pkg) {
        adapter.folders.reread();
        Folders.Folder f = adapter.folders.create(Folders.defaultName(this, pkg), pkg);
        List<String> keys = adapter.orderKeys();
        int at = keys.indexOf(pkg);
        if (at >= 0) keys.set(at, Folders.KEY_PREFIX + f.id);
        else keys.add(Folders.KEY_PREFIX + f.id);
        adapter.saveOrder(keys);
        adapter.reload();
        grid.postOnAnimation(() -> onFolderOpen(f.id, null));
    }

    /** The folders with a tile on Home: not one whose apps are all gone or hidden. */
    List<Folders.Folder> shownFolders() {
        adapter.folders.reread();
        List<Folders.Folder> shown = new ArrayList<>();
        for (Folders.Folder f : adapter.folders.all()) {
            if (adapter.positionOfKey(Folders.KEY_PREFIX + f.id) >= 0) shown.add(f);
        }
        return shown;
    }

    /** Remove folder: its apps back on Home in its place, in its order. */
    void removeFolder(String id) {
        adapter.folders.reread();
        Folders.Folder f = adapter.folders.get(id);
        if (f == null) return;
        List<String> keys = adapter.orderKeys();
        int at = keys.indexOf(Folders.KEY_PREFIX + id);
        if (at < 0) at = keys.size();
        else keys.remove(at);
        keys.addAll(at, f.apps);
        adapter.folders.delete(id);
        new TileCustom(this).reset(Folders.KEY_PREFIX + id);   // its tile picture stays among the tile pictures
        wallpapers.forgetList(Folders.KEY_PREFIX + id);
        adapter.saveOrder(keys);
        adapter.reload();
        int focus = at;
        grid.postOnAnimation(() -> focusTile(focus));
    }

    /** Rename: the TV's keyboard on the folder's name. */
    void renameFolder(String id) {
        adapter.folders.reread();
        Folders.Folder f = adapter.folders.get(id);
        if (f == null) return;
        askText(R.string.folder_name, f.name, typed -> {
            adapter.folders.reread();
            adapter.folders.rename(id, typed);
            folderChanged();
        });
    }

    /** The TV's keyboard on a name: done with OK (or the keyboard's Done), with what was typed. */
    void askText(int title, String current, java.util.function.Consumer<String> done) {
        NameDialog.ask(this, title, current, 0, highlight, done);
    }

    // ---- tile menu > Edit: an app tile's picture, icon and name ------------------------------------------

    /** A grid picked from returns here: the tile or list it was for is drawn again (onActivityResult). */
    static final int REQUEST_PICK = 41;

    /** The apps whose pictures are a tile's own, by its key: a folder's apps, or the app itself. */
    private List<String> appsOf(String key) {
        List<String> apps = new ArrayList<>();
        if (key == null) return apps;
        if (!key.startsWith(Folders.KEY_PREFIX)) {
            apps.add(key);
            return apps;
        }
        for (Folders.Folder f : adapter.folders.all()) {
            if (key.equals(Folders.KEY_PREFIX + f.id)) apps.addAll(f.apps);
        }
        return apps;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) return;
        String key = data.getStringExtra(WallpaperBrowserActivity.LIST);
        // A tile picture deleted there may have been on any tile: all of them drawn again. The backdrops follow in
        // onResume (applyWallpaperChange sees the lists changed).
        adapter.markAllUpdated();
        if (folderView != null) folderView.adapter.markAllUpdated();
        adapter.reload();
        if (folderView != null) folderView.adapter.reload();
        shelfContents = null;
    }

    @Override
    public void onTileMoved(int position) {
        followMovedTile(position, 10);
    }

    @Override
    public void onMoving(boolean moving) {
        glass.setMoving(moving);
    }

    /**
     * Brings the highlight (and dock title) to a moved tile's new spot, once the grid has applied the move:
     * on a later frame, as the grid does it on the next one or in the next layout pass. Before that the tile
     * is still laid out where it was, and the highlight would go there (a column off, from the ends of the
     * row under a shorter, centred dock).
     */
    private void followMovedTile(int position, int tries) {
        grid.postOnAnimation(() -> {
            if (adapter.isMoving() && adapter.movingPosition() != position) return;   // moved on since
            if (tries > 0 && (grid.hasPendingAdapterUpdates() || grid.isLayoutRequested())) {
                followMovedTile(position, tries - 1);
                return;
            }
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
            if (vh == null) return;
            View card = vh.itemView.findViewById(R.id.card);
            if (!card.hasFocus()) card.requestFocus();
            onTileFocused(position, card);
        });
    }

    /**
     * Switches to an input through the TV app first: other apps can claim the same channel address (an IPTV
     * player, a file manager), and with two or more Android shows a chooser instead of the input (upstream,
     * on WhaleOS). Without a TV app, or if it doesn't answer, the address goes out to whichever handles it.
     */
    void openInput(String id) {
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

    void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    View addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        Highlight.menuRow(row, highlight);
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
