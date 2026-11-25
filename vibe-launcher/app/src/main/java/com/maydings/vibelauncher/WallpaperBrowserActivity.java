package com.maydings.vibelauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Picking custom wallpapers on the TV (Settings > Backdrops > System backdrop), as a grid of thumbnails, more
 * coming in as it's scrolled. OK on one downloads it into Wallpapers; a tick marks those already in. Each grid says
 * where its pictures come from, as the services ask, and the focused one's maker where there is one. The sources:
 * <ul>
 * <li>Wallhaven: pictures (safe for work only, 16:9, at least 1920 x 1080; no key).
 * <li>Pexels: videos, with the person's key (Pexels gives no new ones now; those who have one keep it).
 * <li>Pixabay: pictures and videos, with the person's free key. Its replies are kept a day, as Pixabay asks.
 * <li>NASA's Image and Video Library: pictures and videos, no key.
 * <li>Wikimedia Commons: featured pictures, and videos in VP8 only (most of Commons' are VP9, AV1 or Theora,
 * which older TVs can't play or play badly), no key.
 * <li>Apple TV aerials: Apple's screensaver videos in its four groups, one tab each (AppleAerials), no key. Each is
 * hundreds of MB: its size shows on the line at the bottom, and it comes in only with AppleAerials.FREE_KEPT to spare.
 * </ul>
 * The last three have Pictures, Videos and a Search that finds both. A video is taken in its largest version up to
 * 1080p.
 */
public final class WallpaperBrowserActivity extends Activity {

    static final String SOURCE = "source";
    /**
     * The Library (no tabs, no downloads), by KIND: its videos, or its pictures (VIDEOS, PICTURES), or its pictures
     * for a tile, which takes no video (TILES). With LIST, picked for it: a list (a tile's own, Wallpapers.ROTATION
     * or SCREENSAVER) ticks any number, a tile (its key) one; OK picks, holding OK deletes. Without, OK deletes.
     */
    static final String MINE = "mine";
    static final String KIND = "kind", LIST = "list", LABEL = "label";
    static final String PICTURES = "pictures", VIDEOS = "videos", TILES = "tiles";
    private static final int COLUMNS = 4;
    /** The tabs: the first list (Wallhaven's top, Pexels' popular, the others' pictures), the second, a search. */
    private static final int FIRST = 0, SECOND = 1, SEARCH = 2;
    /** Apple TV aerials' tabs: APPLE_GROUP plus the group's index (AppleAerials.GROUPS). */
    private static final int APPLE_GROUP = 10;

    private static final String WALLHAVEN = "https://wallhaven.cc/api/v1/search?purity=100&categories=111"
            + "&atleast=1920x1080&ratios=16x9&page=%d";
    private static final String PEXELS_POPULAR = "https://api.pexels.com/videos/popular?per_page=24&min_width=1280&page=%d";
    private static final String PEXELS_SEARCH = "https://api.pexels.com/videos/search?per_page=24&orientation=landscape"
            + "&query=%s&page=%d";
    private static final int PIXABAY_PAGE = 24;
    private static final String PIXABAY_PICTURES = "https://pixabay.com/api/?key=%s&image_type=photo"
            + "&orientation=horizontal&min_width=1920&safesearch=true&per_page=" + PIXABAY_PAGE + "&page=%d";
    private static final String PIXABAY_VIDEOS = "https://pixabay.com/api/videos/?key=%s&video_type=film"
            + "&min_width=1280&safesearch=true&per_page=" + PIXABAY_PAGE + "&page=%d";
    /** Pixabay's replies are kept this long: it asks for that. */
    private static final long PIXABAY_KEEP_MS = 24L * 60 * 60 * 1000;
    private static final String NASA_SEARCH = "https://images-api.nasa.gov/search?media_type=image,video"
            + "&page_size=24&q=%s&page=%d";
    /** NASA's own lists, without a search: its most looked at, then the newest. */
    private static final String[] NASA_LISTS = {"https://images-assets.nasa.gov/popular.json",
            "https://images-assets.nasa.gov/recent.json"};
    private static final String COMMONS = "https://commons.wikimedia.org/w/api.php?action=query&format=json"
            + "&formatversion=2&generator=search&gsrnamespace=6";
    private static final int COMMONS_PICTURES_PAGE = 24, COMMONS_VIDEOS_PAGE = 50;
    private static final String COMMONS_PICTURES = COMMONS + "&gsrlimit=" + COMMONS_PICTURES_PAGE
            + "&gsroffset=%d&gsrsearch=%s&prop=imageinfo&iiprop=url%%7Csize%%7Cextmetadata"
            + "&iiextmetadatafilter=Artist%%7CLicenseShortName&iiurlwidth=1920";
    private static final String COMMONS_VIDEOS = COMMONS + "&gsrlimit=" + COMMONS_VIDEOS_PAGE
            + "&gsroffset=%d&gsrsearch=%s&prop=videoinfo&viprop=url%%7Csize%%7Cmetadata%%7Cextmetadata"
            + "&viextmetadatafilter=Artist%%7CLicenseShortName&viurlwidth=500";
    private static final String COMMONS_PICTURE_FILTER = " filemime:image/jpeg filew:>1919";
    private static final String COMMONS_VIDEO_FILTER = " filemime:video/webm filew:>1279";
    /** Commons' Videos tab, without a search: it has no list of its best ones, and its newest are mostly talks. */
    private static final String COMMONS_VIDEO_TOPICS = "timelapse OR nature OR landscape OR aerial";
    private static final int MAX_PICTURE = 40 * 1024 * 1024;
    /** Pages in a row that brought nothing to show (Commons' videos, mostly not VP8) before it stops asking. */
    private static final int MAX_EMPTY_PAGES = 8;

    /**
     * One thumbnail: its id there, what to download (a picture, a video, or NASA's list of a video's files), a line
     * under it, and the line about it at the bottom while it has focus (its maker; null: the grid's own). title:
     * what the source calls it, kept as its name in the Library (null when it has no name there). odd (MINE): a
     * picture not made for what's being picked, a tile's for a backdrop or the other way round.
     */
    private static final class Result {
        final String id, thumb, full, credit;
        String label;
        final boolean video;
        String title;
        boolean odd;

        Result named(String title) {
            this.title = title;
            return this;
        }

        Result(String id, String thumb, String full, String label, String credit, boolean video) {
            this.id = id;
            this.thumb = thumb;
            this.full = full;
            this.label = label;
            this.credit = credit;
            this.video = video;
        }
    }

    /** One page's thumbnails, and whether its pictures and its videos go on after it. */
    private static final class Batch {
        final List<Result> results = new ArrayList<>();
        boolean pictures, videos;

        Batch more(boolean both) {
            pictures = both;
            videos = both;
            return this;
        }
    }

    /** Thumbnails from one kind (pictures or videos), and whether it goes on. */
    private static final class Part {
        final List<Result> results = new ArrayList<>();
        boolean more;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pages = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final List<Result> results = new ArrayList<>();

    /** Which of Wallpapers' sources this grid is (MINE for the person's own). */
    private String source;
    private boolean mine;
    /** In MINE: each grid item's wallpaper. */
    private final java.util.Map<String, Wallpapers.Item> items = new java.util.HashMap<>();
    /** In MINE: the bucket (PICTURES, VIDEOS or TILES), and the list or tile it's picked for (null: to delete). */
    private String kind, listKey, listLabel;
    private String pexelsKey, pixabayKey;
    private Wallpapers wallpapers;
    private File apiCache;
    /** What's listed: FIRST, SECOND or SEARCH. */
    private int tab;
    /** The tab the grid's list is of: the one picked, once its list is asked for (reload). */
    private int listTab;
    /** Each tab's own (FIRST, SECOND, SEARCH), in the order they show. */
    private int[] tabIds = new int[0];
    private String query = "";
    private int page, generation, emptyPages;
    /** Whether the pictures and the videos have no more pages (a search finds both; a list one or the other). */
    private boolean picturesEnded, videosEnded;
    private boolean loading, ended;
    private float density;
    private int highlight;

    private RecyclerView grid;
    private Adapter adapter;
    private TextView message, credit;
    private EditText search;
    private final List<TextView> tabs = new ArrayList<>();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        FontInflater.install(this);
        super.onCreate(state);
        source = getIntent().getStringExtra(SOURCE);
        if (source == null) source = Wallpapers.WALLHAVEN;
        mine = MINE.equals(source);
        kind = getIntent().getStringExtra(KIND);
        if (kind == null) kind = PICTURES;
        listKey = getIntent().getStringExtra(LIST);
        listLabel = getIntent().getStringExtra(LABEL);
        VibeSettings settings = new VibeSettings(this);
        pexelsKey = settings.pexelsKey();
        pixabayKey = settings.pixabayKey();
        wallpapers = Wallpapers.get(this);
        apiCache = new File(getCacheDir(), "wallpaper-api");
        density = getResources().getDisplayMetrics().density;
        highlight = Highlight.SETTINGS;   // a Settings screen, as the grids are opened from Settings and Edit

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B10);
        int side = dp(48);
        root.setPadding(side, dp(28), side, dp(18));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(title());
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(UiFont.get(this, R.string.font_regular));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int[] names;
        if (mine) {
            names = new int[0];
        } else if (isPexels()) {
            names = new int[]{R.string.wp_popular, R.string.wp_search};
            tabIds = new int[]{FIRST, SEARCH};
        } else if (isWallhaven()) {
            names = new int[]{R.string.wp_toplist, R.string.wp_latest, R.string.wp_search};
            tabIds = new int[]{FIRST, SECOND, SEARCH};
        } else if (isApple()) {
            names = new int[]{R.string.wp_apple_space, R.string.wp_apple_landscapes, R.string.wp_apple_cities,
                    R.string.wp_apple_underwater};
            tabIds = new int[]{APPLE_GROUP, APPLE_GROUP + 1, APPLE_GROUP + 2, APPLE_GROUP + 3};
        } else {
            names = new int[]{R.string.wp_pictures, R.string.wp_videos, R.string.wp_search};
            tabIds = new int[]{FIRST, SECOND, SEARCH};
        }
        for (int i = 0; i < names.length; i++) {
            int which = tabIds[i];
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(16);
            t.setTypeface(UiFont.get(this, R.string.font_regular));
            t.setPadding(dp(20), dp(8), dp(20), dp(8));
            t.setFocusable(true);
            t.setClickable(true);
            // The pill's own looks: focused (the highlight color, below) and the tab that's showing (selected).
            t.setBackgroundResource(R.drawable.pill_bg);
            Highlight.pill(t, highlight);
            t.setOnClickListener(v -> pickTab(which));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(10));
            top.addView(t, lp);
            tabs.add(t);
        }
        root.addView(top);

        search = new EditText(this);
        search.setHint(R.string.wp_search_hint);
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0x80FFFFFF);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setVisibility(View.GONE);
        search.setOnEditorActionListener((v, actionId, event) -> {
            query = search.getText().toString().trim();
            hideKeyboard();
            if (!query.isEmpty()) reload();
            return true;
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        root.addView(search, slp);

        FrameLayout body = new FrameLayout(this);
        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setClipToPadding(false);
        grid.setPadding(dp(8), dp(16), dp(8), dp(16));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        // No cross-fade when a card changes: the new card would show a row down for a moment, with the focus.
        ((androidx.recyclerview.widget.SimpleItemAnimator) grid.getItemAnimator()).setSupportsChangeAnimations(false);
        body.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        message = new TextView(this);
        message.setTextColor(0xB3FFFFFF);
        message.setTextSize(17);
        message.setGravity(Gravity.CENTER);
        body.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        credit = new TextView(this);
        credit.setTextColor(0x80FFFFFF);
        credit.setTextSize(13);
        credit.setSingleLine(true);
        credit.setEllipsize(TextUtils.TruncateAt.END);
        credit.setText(mine ? mineLine(null) : sourceCredit());
        root.addView(credit);

        setContentView(root);
        if (mine) {
            showMine();
            nameAerials();
            return;
        }
        if (isPexels() && pexelsKey.isEmpty()) {
            message.setText(R.string.wp_pexels_no_key);
            return;
        }
        if (isPixabay() && pixabayKey.isEmpty()) {
            message.setText(R.string.wp_pixabay_no_key);
            return;
        }
        if (isPixabay()) pages.execute(this::dropOldReplies);
        pickTab(isApple() ? APPLE_GROUP : FIRST);
    }

    private boolean isWallhaven() {
        return Wallpapers.WALLHAVEN.equals(source);
    }

    private boolean isPexels() {
        return Wallpapers.PEXELS.equals(source);
    }

    private boolean isPixabay() {
        return Wallpapers.PIXABAY.equals(source);
    }

    private boolean isNasa() {
        return Wallpapers.NASA.equals(source);
    }

    private boolean isApple() {
        return Wallpapers.APPLE.equals(source);
    }

    /** The grid's heading. */
    private String title() {
        if (mine) {
            // Picked for a tile or a list: what it's for. The Library itself: its pictures, or its videos.
            String bucket = getString(listKey == null ? VIDEOS.equals(kind) ? R.string.wp_videos : R.string.wp_pictures
                    : TILES.equals(kind) ? R.string.change_picture
                    : VIDEOS.equals(kind) ? R.string.backdrop_videos : R.string.backdrop_pictures);
            return listLabel != null ? listLabel + " · " + bucket : bucket;
        }
        if (isPexels()) return getString(R.string.wp_pexels_title);
        if (isWallhaven()) return getString(R.string.wp_wallhaven_title);
        if (isApple()) return getString(R.string.wp_apple_title);
        return serviceName(source);
    }

    /** The service's own name, as it's written everywhere. */
    static String serviceName(String source) {
        switch (source) {
            case Wallpapers.WALLHAVEN: return "Wallhaven";
            case Wallpapers.PEXELS: return "Pexels";
            case Wallpapers.PIXABAY: return "Pixabay";
            case Wallpapers.NASA: return "NASA";
            case Wallpapers.COMMONS: return "Wikimedia Commons";
            case Wallpapers.APPLE: return "Apple";
            default: return "";
        }
    }

    /** The line at the bottom saying where the grid's pictures come from. */
    private String sourceCredit() {
        switch (source) {
            case Wallpapers.PEXELS: return getString(R.string.wp_credit_pexels);
            case Wallpapers.PIXABAY: return getString(R.string.wp_credit_pixabay);
            case Wallpapers.NASA: return getString(R.string.wp_credit_nasa);
            case Wallpapers.COMMONS: return getString(R.string.wp_credit_commons);
            case Wallpapers.APPLE: return getString(R.string.wp_credit_apple);
            default: return getString(R.string.wp_credit_wallhaven);
        }
    }

    private void pickTab(int which) {
        tab = which;
        for (int i = 0; i < tabs.size(); i++) tabs.get(i).setSelected(tabIds[i] == which);
        if (which == SEARCH) {
            search.setVisibility(View.VISIBLE);
            search.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) search.post(() -> imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT));
            return;
        }
        search.setVisibility(View.GONE);
        reload();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    /** The list from its first page, for this tab (or search). */
    private void reload() {
        generation++;
        results.clear();
        adapter.notifyDataSetChanged();
        listTab = tab;
        page = 0;
        emptyPages = 0;
        ended = false;
        // A list is only one kind; a search, both.
        picturesEnded = isApple() || tab == SECOND && !isWallhaven() && !isPexels();
        videosEnded = !isApple() && tab == FIRST && !isWallhaven() && !isPexels();
        loading = false;
        message.setText(R.string.wp_loading);
        credit.setText(sourceCredit());
        more();
    }

    /** The next page, unless one is coming already or there are no more. */
    private void more() {
        if (loading || ended) return;
        loading = true;
        // Of the list that's showing, which isn't the tab picked while Search is open and nothing's searched yet.
        int gen = generation, next = page + 1, t = listTab;
        String q = query;
        boolean pictures = !picturesEnded, videos = !videosEnded;
        pages.execute(() -> {
            Batch found = null;
            boolean refused = false;
            try {
                found = fetch(t, q, next, pictures, videos);
            } catch (IOException | RuntimeException | JSONException e) {
                android.util.Log.i("VibeWallpapers", "page failed", e);
                // Pixabay answers a wrong key with 400, Pexels with 401 or 403.
                String why = String.valueOf(e.getMessage());
                refused = why.equals("HTTP 400") || why.equals("HTTP 401") || why.equals("HTTP 403");
            }
            Batch got = found;
            boolean keyRefused = refused && (isPixabay() || isPexels());
            main.post(() -> {
                if (gen != generation || isDestroyed()) return;
                loading = false;
                if (got == null) {
                    if (results.isEmpty() && keyRefused) {
                        message.setText(isPixabay() ? R.string.wp_pixabay_no_key : R.string.wp_pexels_no_key);
                    } else if (results.isEmpty()) {
                        message.setText(getString(R.string.wp_offline, serviceName(source)));
                    }
                    return;
                }
                page = next;
                picturesEnded |= !got.pictures;
                videosEnded |= !got.videos;
                ended = picturesEnded && videosEnded;
                int from = results.size();
                results.addAll(got.results);
                adapter.notifyItemRangeInserted(from, got.results.size());
                // Nothing in it to show (Commons' videos, mostly not VP8): straight on to the next.
                if (got.results.isEmpty() && !ended && ++emptyPages < MAX_EMPTY_PAGES) {
                    more();
                    return;
                }
                if (!got.results.isEmpty()) emptyPages = 0;
                message.setText(results.isEmpty() ? getString(R.string.wp_none) : "");
                if (from == 0 && !results.isEmpty()) grid.post(() -> {
                    RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(0);
                    if (h != null && tab != SEARCH) h.itemView.requestFocus();
                });
            });
        });
    }

    /** Page p of this tab (or of a search for q), from the grid's source. Worker thread. */
    private Batch fetch(int t, String q, int p, boolean pictures, boolean videos) throws IOException, JSONException {
        Batch b = new Batch();
        switch (source) {
            case Wallpapers.PEXELS: {
                String url = t == SEARCH ? String.format(Locale.ROOT, PEXELS_SEARCH, Uri.encode(q), p)
                        : String.format(Locale.ROOT, PEXELS_POPULAR, p);
                b.results.addAll(pexels(url));
                return b.more(!b.results.isEmpty());
            }
            case Wallpapers.PIXABAY: {
                Part pics = pictures ? pixabay(false, t == SEARCH ? q : "", p) : new Part();
                Part vids = videos ? pixabay(true, t == SEARCH ? q : "", p) : new Part();
                return mix(b, pics, vids);
            }
            case Wallpapers.NASA: {
                Part found = nasa(t, q, p);
                b.results.addAll(found.results);
                return b.more(found.more);
            }
            case Wallpapers.COMMONS: {
                Part pics = pictures ? commonsPictures(t == SEARCH ? q : "", p) : new Part();
                Part vids = videos ? commonsVideos(t == SEARCH ? q : "", p) : new Part();
                return mix(b, pics, vids);
            }
            case Wallpapers.APPLE: {   // a group in one page
                if (p == 1) {
                    for (AppleAerials.Video v : AppleAerials.group(this, t - APPLE_GROUP)) {
                        b.results.add(new Result(v.id, "aerial:" + v.id + "|" + v.url, v.url, v.name, null, true)
                                .named(v.name));
                    }
                }
                b.videos = false;
                return b;
            }
            default: {
                String u = String.format(Locale.ROOT, WALLHAVEN, p);
                if (t == FIRST) u += "&sorting=toplist&topRange=1M";
                else if (t == SECOND) u += "&sorting=date_added";
                else u += "&sorting=relevance&q=" + Uri.encode(q);
                b.results.addAll(wallhaven(u));
                return b.more(!b.results.isEmpty());
            }
        }
    }

    /** A search's pictures and videos, taking turns. */
    private static Batch mix(Batch b, Part pictures, Part videos) {
        int n = Math.max(pictures.results.size(), videos.results.size());
        for (int i = 0; i < n; i++) {
            if (i < pictures.results.size()) b.results.add(pictures.results.get(i));
            if (i < videos.results.size()) b.results.add(videos.results.get(i));
        }
        b.pictures = pictures.more;
        b.videos = videos.more;
        return b;
    }

    private static List<Result> wallhaven(String url) throws IOException, JSONException {
        JSONArray data = new JSONObject(Http.getText(url, Collections.emptyMap())).getJSONArray("data");
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            String path = o.optString("path"), thumb = o.optJSONObject("thumbs") != null
                    ? o.getJSONObject("thumbs").optString("large") : "";
            if (path.isEmpty() || thumb.isEmpty()) continue;
            out.add(new Result(o.optString("id"), thumb, path, o.optString("resolution"), null, false));
        }
        return out;
    }

    private List<Result> pexels(String url) throws IOException, JSONException {
        JSONArray videos = new JSONObject(Http.getText(url, Collections.singletonMap("Authorization", pexelsKey)))
                .getJSONArray("videos");
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < videos.length(); i++) {
            JSONObject v = videos.getJSONObject(i);
            String file = bestFile(v.optJSONArray("video_files"));
            if (file == null) continue;
            String maker = v.optJSONObject("user") != null ? v.getJSONObject("user").optString("name") : "";
            out.add(new Result(String.valueOf(v.optLong("id")), v.optString("image"), file,
                    duration(v.optInt("duration")), maker.isEmpty() ? null : getString(R.string.wp_video_by, maker),
                    true));
        }
        return out;
    }

    /** The video's MP4 to download: the widest no wider than 1920 (a TV-sized one), else the smallest there is. */
    private static String bestFile(JSONArray files) throws JSONException {
        String best = null, smallest = null;
        int bestW = 0, smallestW = Integer.MAX_VALUE;
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            if (!"video/mp4".equals(f.optString("file_type"))) continue;
            int w = f.optInt("width");
            if (w <= 1920 && w > bestW) {
                bestW = w;
                best = f.optString("link");
            }
            if (w > 0 && w < smallestW) {
                smallestW = w;
                smallest = f.optString("link");
            }
        }
        return best != null ? best : smallest;
    }

    /**
     * A page of Pixabay's pictures or videos: a search for q, or (q empty) its Editor's Choice, most popular first.
     * A free key gets pictures up to 1280 wide (1920 with full API access); videos are MP4.
     */
    private Part pixabay(boolean video, String q, int p) throws IOException, JSONException {
        String url = String.format(Locale.ROOT, video ? PIXABAY_VIDEOS : PIXABAY_PICTURES, Uri.encode(pixabayKey), p)
                + (q.isEmpty() ? "&editors_choice=true&order=popular"
                : "&q=" + Uri.encode(q.length() > 100 ? q.substring(0, 100) : q));
        JSONObject reply = new JSONObject(keptReply(url));
        JSONArray hits = reply.optJSONArray("hits");
        Part part = new Part();
        part.more = p * PIXABAY_PAGE < reply.optInt("totalHits");
        for (int i = 0; hits != null && i < hits.length(); i++) {
            JSONObject o = hits.getJSONObject(i);
            String id = String.valueOf(o.optLong("id")), user = o.optString("user");
            String by = user.isEmpty() ? null : getString(R.string.wp_by_pixabay, user);
            if (!video) {
                String full = o.optString("fullHDURL");
                if (full.isEmpty()) full = o.optString("largeImageURL");
                String thumb = o.optString("webformatURL");
                if (full.isEmpty() || thumb.isEmpty()) continue;
                part.results.add(new Result("p" + id, thumb, full,
                        o.optInt("imageWidth") + " × " + o.optInt("imageHeight"), by, false));
                continue;
            }
            JSONObject files = o.optJSONObject("videos");
            if (files == null) continue;
            JSONObject best = null;
            for (String size : new String[]{"large", "medium", "small", "tiny"}) {
                JSONObject f = files.optJSONObject(size);
                if (f == null || f.optString("url").isEmpty() || f.optInt("width") > 1920) continue;
                if (best == null || f.optInt("width") > best.optInt("width")) best = f;
            }
            if (best == null || best.optInt("width") < 1280) continue;
            String thumb = best.optString("thumbnail");
            JSONObject medium = files.optJSONObject("medium");
            if (thumb.isEmpty() && medium != null) thumb = medium.optString("thumbnail");
            if (thumb.isEmpty() && !o.optString("picture_id").isEmpty()) {
                thumb = "https://i.vimeocdn.com/video/" + o.optString("picture_id") + "_640x360.jpg";
            }
            if (thumb.isEmpty()) continue;
            part.results.add(new Result("v" + id, thumb, best.optString("url"), duration(o.optInt("duration")), by,
                    true));
        }
        return part;
    }

    /** A reply as Pixabay gave it within the last day, else asked for again (and kept). Worker thread. */
    private String keptReply(String url) throws IOException {
        File file = new File(apiCache, sha1(url) + ".json");
        long age = System.currentTimeMillis() - file.lastModified();
        if (file.exists() && age >= 0 && age < PIXABAY_KEEP_MS) {
            try {
                String cached = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (new JSONObject(cached).has("hits")) return cached;
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (IOException | JSONException e) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();   // corrupt/truncated: refetch
            }
        }
        String text = Http.getText(url, Collections.emptyMap());
        // Kept only if it's an answer (a network's sign-in page comes with a 200 as well): kept as it came,
        // something else would be this page's answer for as long as answers are kept.
        try {
            if (!new JSONObject(text).has("hits")) throw new IOException("not an answer");
        } catch (JSONException e) {
            throw new IOException(e);
        }
        //noinspection ResultOfMethodCallIgnored
        apiCache.mkdirs();
        File tmp = new File(apiCache, file.getName() + "." + Thread.currentThread().getId() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return text;
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return text;
    }

    /** Pixabay's replies older than a day, gone. Worker thread. */
    private void dropOldReplies() {
        File[] old = apiCache.listFiles();
        long now = System.currentTimeMillis();
        for (int i = 0; old != null && i < old.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (now - old[i].lastModified() >= PIXABAY_KEEP_MS) old[i].delete();
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte x : d) hex.append(String.format(Locale.ROOT, "%02x", x));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * A page from NASA's Image and Video Library: its popular then its newest (pictures in Pictures, videos in
     * Videos; two pages in all), or a search for both. Pictures only when wider than tall, 1280 wide or more.
     */
    private Part nasa(int t, String q, int p) throws IOException, JSONException {
        Part part = new Part();
        JSONObject collection;
        if (t == SEARCH) {
            collection = new JSONObject(Http.getText(String.format(Locale.ROOT, NASA_SEARCH, Uri.encode(q), p),
                    Collections.emptyMap())).getJSONObject("collection");
            JSONArray links = collection.optJSONArray("links");
            for (int i = 0; links != null && i < links.length(); i++) {
                if ("next".equals(links.getJSONObject(i).optString("rel"))) part.more = true;
            }
        } else {
            if (p > NASA_LISTS.length) return part;
            collection = new JSONObject(Http.getText(NASA_LISTS[p - 1], Collections.emptyMap()))
                    .getJSONObject("collection");
            part.more = p < NASA_LISTS.length;
        }
        JSONArray found = collection.optJSONArray("items");
        for (int i = 0; found != null && i < found.length(); i++) {
            JSONObject item = found.getJSONObject(i);
            JSONArray data = item.optJSONArray("data");
            if (data == null || data.length() == 0) continue;
            JSONObject d = data.getJSONObject(0);
            boolean video = "video".equals(d.optString("media_type"));
            if (!video && !"image".equals(d.optString("media_type"))) continue;
            if (t == FIRST && video || t == SECOND && !video) continue;
            String id = d.optString("nasa_id"), title = d.optString("title");
            if (id.isEmpty()) continue;
            JSONArray links = item.optJSONArray("links");
            String thumb = "", fit = "", orig = "";
            int fitW = 0, origW = 0;
            long origSize = 0;
            boolean wide = false;
            for (int j = 0; links != null && j < links.length(); j++) {
                JSONObject l = links.getJSONObject(j);
                if (!"image".equals(l.optString("render"))) continue;
                String href = l.optString("href");
                int w = l.optInt("width"), h = l.optInt("height");
                if (w > 0 && h > 0 && w >= h * 1.2) wide = true;
                if ("preview".equals(l.optString("rel")) || thumb.isEmpty()) thumb = href;
                if ("canonical".equals(l.optString("rel"))) {
                    orig = href;
                    origW = w;
                    origSize = l.optLong("size");
                } else if (w <= 2560 && w > fitW) {
                    fitW = w;
                    fit = href;
                }
            }
            if (thumb.isEmpty()) continue;
            String by = title.isEmpty() ? null : title + " · NASA";
            if (video) {
                part.results.add(new Result(id, safeUrl(thumb), safeUrl(item.optString("href")), "▶ " + title, by,
                        true).named(title));
                continue;
            }
            if (!wide) continue;   // a tall one, cut to the screen, would be its middle strip
            String full = fitW >= 1280 ? fit
                    : !orig.isEmpty() && (origW == 0 || origW >= 1280) && origSize <= MAX_PICTURE ? orig : "";
            if (full.isEmpty()) continue;
            part.results.add(new Result(id, safeUrl(thumb), safeUrl(full), title, by, false).named(title));
        }
        return part;
    }

    /** A NASA video's MP4s, from its list of files: the large one first, then smaller (the original before small). */
    private static List<String> nasaVideoFiles(String listUrl) throws IOException, JSONException {
        JSONArray files = new JSONArray(Http.getText(listUrl, Collections.emptyMap()));
        List<String> out = new ArrayList<>();
        for (String end : new String[]{"~large.mp4", "~medium.mp4", "~orig.mp4", "~small.mp4"}) {
            for (int i = 0; i < files.length(); i++) {
                String f = files.optString(i);
                if (f.endsWith(end)) out.add(safeUrl(f));
            }
        }
        return out;
    }

    /**
     * Commons' featured pictures (q empty, newest first) or a search for q: JPEGs 1920 wide or more, wider than
     * tall, each fetched at 1920 wide (the original can be tens of megabytes).
     */
    private Part commonsPictures(String q, int p) throws IOException, JSONException {
        String find = (q.isEmpty() ? "incategory:Featured_pictures_on_Wikimedia_Commons" : q) + COMMONS_PICTURE_FILTER;
        String url = String.format(Locale.ROOT, COMMONS_PICTURES, (p - 1) * COMMONS_PICTURES_PAGE, Uri.encode(find))
                + (q.isEmpty() ? "&gsrsort=create_timestamp_desc" : "");
        Part part = new Part();
        for (JSONObject page : commonsPages(url, part)) {
            JSONArray infos = page.optJSONArray("imageinfo");
            if (infos == null || infos.length() == 0) continue;
            JSONObject ii = infos.getJSONObject(0);
            int w = ii.optInt("width"), h = ii.optInt("height");
            if (w < 1920 || w < h * 1.2) continue;
            String full = !ii.isNull("thumburl") && !ii.optString("thumburl").isEmpty()
                    ? ii.optString("thumburl") : (!ii.isNull("url") ? ii.optString("url") : "");
            int at = full.indexOf('?');
            if (at > 0) full = full.substring(0, at);
            if (full.isEmpty()) continue;
            String thumb = full.replaceFirst("/\\d+px-", "/500px-");
            part.results.add(new Result(String.valueOf(page.optLong("pageid")), thumb, full, w + " × " + h,
                    commonsCredit(ii), false).named(commonsName(page)));
        }
        return part;
    }

    /**
     * Commons' videos (a few kinds of scenery when q is empty, else a search for q): VP8 only, the one codec of
     * Commons' every TV plays well (VP9 and AV1 are left out, and Theora doesn't play at all), 1280 to 1920 wide,
     * 720 or more tall, wider than tall.
     */
    private Part commonsVideos(String q, int p) throws IOException, JSONException {
        String find = (q.isEmpty() ? COMMONS_VIDEO_TOPICS : q) + COMMONS_VIDEO_FILTER;
        String url = String.format(Locale.ROOT, COMMONS_VIDEOS, (p - 1) * COMMONS_VIDEOS_PAGE, Uri.encode(find));
        Part part = new Part();
        for (JSONObject page : commonsPages(url, part)) {
            JSONArray infos = page.optJSONArray("videoinfo");
            if (infos == null || infos.length() == 0) continue;
            JSONObject vi = infos.getJSONObject(0);
            int w = vi.optInt("width"), h = vi.optInt("height");
            if (w < 1280 || w > 1920 || h < 720 || w <= h) continue;
            if (vi.optLong("size") > Wallpapers.MAX_VIDEO) continue;
            JSONObject track = named(named(vi.optJSONArray("metadata")).optJSONArray("video"));
            if (!track.optString("dataformat").toLowerCase(Locale.ROOT).contains("vp8")) continue;
            String full = vi.optString("url"), thumb = vi.optString("thumburl");
            int at = thumb.indexOf('?');
            if (at > 0) thumb = thumb.substring(0, at);
            if (full.isEmpty() || thumb.isEmpty()) continue;
            part.results.add(new Result(String.valueOf(page.optLong("pageid")), thumb, full,
                    duration((int) Math.round(vi.optDouble("duration", 0))), commonsCredit(vi), true)
                    .named(commonsName(page)));
        }
        return part;
    }

    /** A Commons search's files, in the search's order; part.more when there are more. */
    private static List<JSONObject> commonsPages(String url, Part part) throws IOException, JSONException {
        JSONObject reply = new JSONObject(Http.getText(url, Collections.emptyMap()));
        part.more = reply.has("continue");
        List<JSONObject> out = new ArrayList<>();
        JSONObject query = reply.optJSONObject("query");
        JSONArray found = query != null ? query.optJSONArray("pages") : null;
        for (int i = 0; found != null && i < found.length(); i++) out.add(found.getJSONObject(i));
        Collections.sort(out, (a, b) -> Integer.compare(a.optInt("index"), b.optInt("index")));
        return out;
    }

    /** Commons' list of {name, value} pairs (a file's metadata) as one object. */
    private static JSONObject named(JSONArray pairs) throws JSONException {
        JSONObject out = new JSONObject();
        for (int i = 0; pairs != null && i < pairs.length(); i++) {
            JSONObject pair = pairs.optJSONObject(i);
            if (pair != null && pair.has("name")) out.put(pair.optString("name"), pair.opt("value"));
        }
        return out;
    }

    /** "By (its author) · (its licence) · Wikimedia Commons", as far as they're known. */
    /** A Commons page's name as a title: "File:Aurora over Tromsø.jpg" is "Aurora over Tromsø". */
    private static String commonsName(JSONObject page) {
        String name = page.optString("title");
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replace('_', ' ').trim();
    }

    private String commonsCredit(JSONObject info) {
        JSONObject meta = info.optJSONObject("extmetadata");
        String artist = "", licence = "";
        if (meta != null) {
            JSONObject a = meta.optJSONObject("Artist"), l = meta.optJSONObject("LicenseShortName");
            if (a != null) artist = android.text.Html.fromHtml(a.optString("value"),
                    android.text.Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
            if (l != null) licence = l.optString("value").trim();
        }
        if (artist.isEmpty()) artist = "?";
        return getString(R.string.wp_by_commons, artist, licence.isEmpty() ? "?" : licence);
    }

    /**
     * NASA's addresses come with spaces and the like in them: those escaped (a '#' in a name too, else the rest
     * would be taken for an anchor), the rest as it is. Its lists of a video's files give plain http, which Android
     * refuses for this app: those go over https (which the same host serves).
     */
    private static String safeUrl(String url) {
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        return Uri.encode(url, ":/?&=%@!$'()*+,;~-._");
    }

    private static String duration(int seconds) {
        return String.format(Locale.ROOT, "▶ %d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * MINE: the Library's videos, or its pictures, newest first, each by its own picture (a video by a frame of
     * it). Pictures come in two sizes, those cut to the screen and the tile pictures: for a tile the tile pictures
     * first, otherwise those cut to the screen first. The others follow, and picked for something they weren't
     * made for they're odd: dimmed, and the line at the bottom says why.
     */
    private void showMine() {
        generation++;
        results.clear();
        items.clear();
        boolean tiles = TILES.equals(kind), videos = VIDEOS.equals(kind);
        List<Wallpapers.Item> fitting = new ArrayList<>(), others = new ArrayList<>();
        for (Wallpapers.Item item : Wallpapers.sorted(wallpapers.list())) {
            if (item.video != videos) continue;
            (videos || item.small == tiles ? fitting : others).add(item);
        }
        fitting.addAll(others);
        if (tiles && listKey != null && !listKey.startsWith("folder:")) addOwn();
        for (Wallpapers.Item item : fitting) {
            String path = wallpapers.file(item).getPath();
            Result r = new Result(item.id, item.video ? "video:" + item.id : (item.small ? "tile:" : "file:") + path, path,
                    nameOf(item), null, item.video);
            r.odd = listKey != null && !videos && item.small != tiles;
            results.add(r);
            items.put(item.id, item);
        }
        ended = true;
        adapter.notifyDataSetChanged();
        message.setText(results.isEmpty() ? emptyText() : "");
        if (!results.isEmpty()) grid.post(() -> {
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(0);
            if (h != null) h.itemView.requestFocus();
        });
    }

    /** The ids of the two cards before the Library's when picking for an app's tile: its banner and its icon. */
    private static final String OWN_BANNER = "@banner", OWN_ICON = "@icon";
    /** Pre-rendered bitmaps for the app's own banner and icon (rendered on the main thread; Drawables aren't thread-safe). */
    private Bitmap ownBannerBmp, ownIconBmp;

    /** Picking for an app's tile: its own banner (when it has one) and icon, first in the grid. */
    private void addOwn() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(listKey);
        if (launch == null) launch = pm.getLaunchIntentForPackage(listKey);
        android.graphics.drawable.Drawable ownBanner = null, ownIcon = null;
        ownBannerBmp = null;
        ownIconBmp = null;
        try {
            if (launch != null && launch.getComponent() != null) {
                ownBanner = pm.getActivityBanner(launch);
                ownIcon = pm.getActivityIcon(launch);
            } else {
                ownBanner = pm.getApplicationBanner(listKey);
                ownIcon = pm.getApplicationIcon(listKey);
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return;   // gone meanwhile: the Library's alone
        }
        if (ownBanner != null
                && !AppAdapter.isBannerShaped(ownBanner.getIntrinsicWidth(), ownBanner.getIntrinsicHeight())) {
            ownBanner = null;   // as on its tile (AppAdapter)
        }
        if (ownBanner != null) {
            ownBannerBmp = drawn(ownBanner, true);
            if (ownBannerBmp != null) cache.put("own:banner", ownBannerBmp);
            results.add(new Result(OWN_BANNER, "own:banner", "", getString(R.string.use_banner), null, false));
        }
        if (ownIcon != null) {
            ownIconBmp = drawn(ownIcon, false);
            if (ownIconBmp != null) cache.put("own:icon", ownIconBmp);
            results.add(new Result(OWN_ICON, "own:icon", "", getString(R.string.use_icon), null, false));
        }
    }

    /** The app's banner filling a cell, or its icon on a plate as a tile without a banner shows it. */
    private static Bitmap drawn(android.graphics.drawable.Drawable d, boolean fill) {
        if (d == null) return null;
        int w = 480, h = 270;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        if (fill) {
            d.setBounds(0, 0, w, h);
        } else {
            c.drawColor(0xFF2A2A2E);
            int side = Math.round(h * 0.7f), x = (w - side) / 2, y = (h - side) / 2;
            d.setBounds(x, y, x + side, y + side);
        }
        d.draw(c);
        return b;
    }

    /**
     * What a Library item is called: its title, with the service it came from ("Greenland (Apple)"), or the one
     * typed on the phone; without one, what it is and where it's from ("NASA picture", "Uploaded video").
     */
    private String nameOf(Wallpapers.Item item) {
        boolean uploaded = Wallpapers.PHONE.equals(item.source) || Wallpapers.TILE.equals(item.source)
                || item.source.isEmpty();
        if (!item.title.isEmpty()) return uploaded ? item.title : item.title + " (" + serviceName(item.source) + ")";
        if (uploaded) return getString(item.video ? R.string.wp_item_phone_video : R.string.wp_item_phone_picture);
        switch (item.source) {
            case Wallpapers.WALLHAVEN: return getString(R.string.wp_item_wallhaven);
            case Wallpapers.PEXELS: return getString(R.string.wp_item_pexels);
            default:
                return getString(item.video ? R.string.wp_item_video_from : R.string.wp_item_picture_from,
                        serviceName(item.source));
        }
    }

    /** MINE: the line at the bottom for the one focused: the keys, after why it's odd when it is. */
    private String mineLine(Result r) {
        String keys = getString(listKey == null ? R.string.wp_delete_short
                : TILES.equals(kind) ? R.string.tp_pick_short : R.string.media_tick_short);
        if (r == null || !r.odd) return keys;
        return getString(TILES.equals(kind) ? R.string.lib_big_for_tile : R.string.lib_small_for_backdrop)
                + " · " + keys;
    }

    /**
     * Apple's videos kept without a name (their id there is all that's kept): named from Apple's list, which is
     * here already when they came from it. Worker thread, then the grid again.
     */
    private void nameAerials() {
        List<Wallpapers.Item> unnamed = new ArrayList<>();
        for (Wallpapers.Item item : wallpapers.list()) {
            if (Wallpapers.APPLE.equals(item.source) && item.title.isEmpty()) unnamed.add(item);
        }
        if (unnamed.isEmpty()) return;
        pages.execute(() -> {
            boolean named = false;
            for (Wallpapers.Item item : unnamed) {
                String name = AppleAerials.nameOf(this, item.name);
                if (name == null || name.isEmpty()) continue;
                wallpapers.setTitle(item, name);
                named = true;
            }
            if (named) main.post(() -> {
                if (isDestroyed()) return;
                // Update renamed items in place so scrolling and open dialogs stay intact.
                for (int i = 0; i < results.size(); i++) {
                    Result r = results.get(i);
                    Wallpapers.Item updated = wallpapers.item(r.id);
                    if (updated == null) continue;
                    items.put(r.id, updated);
                    String nextLabel = nameOf(updated);
                    if (!nextLabel.equals(r.label)) {
                        r.label = nextLabel;
                        adapter.notifyItemChanged(i);
                    }
                }
            });
        });
    }

    /**
     * MINE: one of the Library's, by its name: Rename (the TV's keyboard), Delete from the TV (the one after it,
     * or before at the end, then takes focus), or Cancel.
     */
    private void askDelete(Result r, int position) {
        Wallpapers.Item item = items.get(r.id);
        if (item == null) return;
        View panel = getLayoutInflater().inflate(R.layout.dialog_update, findViewById(android.R.id.content), false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(r.label);
        panel.findViewById(R.id.notesScroll).setVisibility(View.GONE);   // its name is enough
        TextView go = panel.findViewById(R.id.install), cancel = panel.findViewById(R.id.cancelUpdate);
        TextView rename = panel.findViewById(R.id.extra);
        rename.setText(R.string.rename_folder);
        rename.setVisibility(View.VISIBLE);
        go.setText(R.string.wp_delete);
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setContentView(panel);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setLayout(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        Highlight.pill(go, highlight);
        Highlight.pill(cancel, highlight);
        Highlight.pill(rename, highlight);
        cancel.setOnClickListener(v -> dialog.dismiss());
        rename.setOnClickListener(v -> {
            dialog.dismiss();
            askName(r);
        });
        go.setOnClickListener(v -> {
            dialog.dismiss();
            wallpapers.delete(item);   // off every list and tile that showed it
            tileCustom = null;   // the tiles' pictures were changed behind this copy: read again when next asked
            items.remove(r.id);
            changed();
            int at = results.indexOf(r);
            if (at < 0) return;
            results.remove(at);
            adapter.notifyItemRemoved(at);
            if (results.isEmpty()) {
                message.setText(emptyText());
                return;
            }
            int next = Math.min(at, results.size() - 1);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(next);
                if (h != null) h.itemView.requestFocus();
            });
        });
        dialog.show();
        cancel.requestFocus();   // the safe one first
    }

    /**
     * MINE: the TV's keyboard on what a Library item is called (its title; nothing typed: none, and it's listed
     * by where it came from again). Its card then says the new name.
     */
    private void askName(Result r) {
        Wallpapers.Item item = items.get(r.id);
        if (item == null) return;
        NameDialog.ask(this, R.string.tile_name, item.title, Wallpapers.MAX_TITLE, highlight, typed -> {
            wallpapers.setTitle(item, typed.replaceAll("\\s+", " ").trim());
            Wallpapers.Item now = wallpapers.item(item.id);
            int at = results.indexOf(r);
            if (now == null || at < 0) return;
            items.put(now.id, now);
            Result renamed = new Result(r.id, r.thumb, r.full, nameOf(now), r.credit, r.video);
            renamed.odd = r.odd;
            results.set(at, renamed);
            adapter.notifyItemChanged(at);
        });
    }

    /** Nothing in the bucket: where they're added (the one place). */
    private String emptyText() {
        return getString(R.string.media_empty, getString(R.string.settings) + " > " + getString(R.string.media_title));
    }

    /** Whether the grid marks it: on the list or tile it's picked for, or (browsing) already downloaded. */
    private boolean ticked(Result r) {
        if (!mine) return wallpapers.has(source, r.id);
        if (listKey == null) return false;
        if (TILES.equals(kind)) {
            TileCustom custom = tileCustom();
            String picture = custom.pictureId(listKey);
            // Without a picture of the Library's, the app's own: its icon when chosen (or it has no banner).
            if (OWN_BANNER.equals(r.id)) return picture == null && !custom.useIcon(listKey);
            if (OWN_ICON.equals(r.id)) return picture == null && (custom.useIcon(listKey) || ownBannerBmp == null);
            return r.id.equals(picture);
        }
        return wallpapers.inList(listKey, r.id);
    }

    /** What the tiles show, read once for this grid: every card asks it for its tick. */
    private TileCustom tileCustom;

    private TileCustom tileCustom() {
        if (tileCustom == null) tileCustom = new TileCustom(this);
        return tileCustom;
    }

    /** OK on one, picking for a list or a tile: on or off the list; the tile's picture, or none again. */
    private void pick(Result r) {
        if (TILES.equals(kind)) {   // a tile shows one thing: the app's banner, its icon, or a picture
            TileCustom custom = tileCustom();
            if (OWN_BANNER.equals(r.id) || OWN_ICON.equals(r.id)) {
                custom.setPicture(listKey, null);
                custom.setUseIcon(listKey, OWN_ICON.equals(r.id) && ownBannerBmp != null);
            } else {
                custom.setPicture(listKey, r.id.equals(custom.pictureId(listKey)) ? null : r.id);
            }
        } else {
            wallpapers.setInList(listKey, r.id, !wallpapers.inList(listKey, r.id));
        }
        changed();
        // Only the ticks, on the cards as they are: a full change makes new cards that cross-fade in, laid out for
        // a moment a row down, with the focus.
        adapter.notifyItemRangeChanged(0, results.size(), TICK);
    }

    /** The payload for a tick changed, and nothing else on the card. */
    private static final Object TICK = new Object();

    /** Something picked or deleted here: whoever opened the grid draws again what it shows (MainActivity). */
    private void changed() {
        setResult(RESULT_OK, new android.content.Intent().putExtra(LIST, listKey));
    }

    /** A grid of one bucket, to pick for a list or a tile (listKey, its name as label), or to delete (both null). */
    static Intent mine(Context context, String kind, String listKey, String label) {
        return intent(context, MINE).putExtra(KIND, kind).putExtra(LIST, listKey).putExtra(LABEL, label);
    }

    /** Source and id of each one being downloaded now (main thread). */
    private final java.util.Set<String> fetching = new java.util.HashSet<>();

    /** Downloads it and adds it to the wallpapers, saying so when it's in (or why not). */
    private void take(Result r) {
        if (wallpapers.has(source, r.id)) {
            Toast.makeText(this, R.string.wp_already, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.wp_downloading, Toast.LENGTH_SHORT).show();
        String what = source + ":" + r.id;
        if (!fetching.add(what)) return;   // OK again while it's still coming: once is enough
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        int screenW = Math.max(w, h), screenH = Math.min(w, h);
        boolean nasaVideo = r.video && isNasa();
        boolean apple = isApple();
        String from = source;
        downloads.execute(() -> {
            Wallpapers.Item added = null;
            if (apple) {
                // Its size first: in only with room to spare (AppleAerials.FREE_KEPT), and no limit beyond that.
                long size = AppleAerials.size(r.full), free = wallpapers.free();
                if (size <= 0 || free - size < AppleAerials.FREE_KEPT) {
                    main.post(() -> {
                        fetching.remove(what);
                        if (isDestroyed()) return;
                        MainActivity.showError(this, size <= 0 ? getString(R.string.wp_offline, serviceName(from))
                                : getString(R.string.wp_no_room, Formatter.formatShortFileSize(this, size),
                                Formatter.formatShortFileSize(this, Math.max(0, free))));
                    });
                    return;
                }
            }
            try {
                // NASA's video is a list of its files: the large one, else the next that fits.
                List<String> files = nasaVideo ? nasaVideoFiles(r.full) : Collections.singletonList(r.full);
                for (String file : files) {
                    added = fetch(file, r, from, screenW, screenH);
                    if (added != null) break;
                }
            } catch (IOException | RuntimeException | JSONException e) {
                android.util.Log.i("VibeWallpapers", "download failed", e);
            }
            boolean ok = added != null;
            main.post(() -> {
                fetching.remove(what);
                if (isDestroyed()) return;
                if (ok) {
                    Toast.makeText(this, R.string.wp_added, Toast.LENGTH_SHORT).show();
                    // Its tick: that one cell only, as the same view (a payload), so the focus stays on it. Redrawing
                    // the whole grid left nothing focused and the remote did nothing.
                    int at = results.indexOf(r);
                    if (at >= 0) adapter.notifyItemChanged(at, TICK);   // the tick only: the thumbnail stays
                } else {
                    MainActivity.showError(this, R.string.wp_download_failed);
                }
            });
        });
    }

    /** One file downloaded and added, or null when it didn't come or can't be used. Download thread. */
    private Wallpapers.Item fetch(String url, Result r, String from, int screenW, int screenH) {
        File part = wallpapers.incoming();
        try {
            // A video's size isn't known before it comes: it may only fill what's spare beyond the TV's share.
            long room = !r.video ? MAX_PICTURE : Wallpapers.APPLE.equals(from)
                    ? Math.min(Integer.MAX_VALUE, wallpapers.free() - AppleAerials.FREE_KEPT) : wallpapers.videoRoom();
            if (room <= 0) return null;
            Http.download(url, part, (int) room);
            Wallpapers.Item added = wallpapers.add(part, r.video, from, r.id, r.title, screenW, screenH);
            if (added != null && added.video) {   // the picture the grid showed it by: its thumbnail in the Library
                Bitmap shown = cache.get(r.thumb);
                if (shown == null) shown = thumb(r.thumb);
                if (shown != null) wallpapers.keepThumb(added, shown);
            }
            return added;
        } catch (IOException | RuntimeException e) {
            android.util.Log.i("VibeWallpapers", "download failed: " + url, e);
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pages.shutdownNow();
        thumbs.shutdownNow();
        downloads.shutdown();   // one being saved finishes
        cache.evictAll();
        ownBannerBmp = null;
        ownIconBmp = null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Back from the search field to the grid, rather than closing at once.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && search.hasFocus() && !results.isEmpty()
                && event.getAction() == KeyEvent.ACTION_UP) {
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(0);
            if (h != null) {
                h.itemView.requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView label, tick;
        Result result;

        Holder(FrameLayout v, ImageView image, TextView label, TextView tick) {
            super(v);
            this.image = image;
            this.label = label;
            this.tick = tick;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            int width = (parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()) / COLUMNS;
            int gap = dp(8);
            FrameLayout frame = new FrameLayout(WallpaperBrowserActivity.this);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(width - 2 * gap, (width - 2 * gap) * 9 / 16);
            lp.setMargins(gap, gap, gap, gap);
            frame.setLayoutParams(lp);
            frame.setFocusable(true);
            frame.setClickable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1C1C22);
            bg.setCornerRadius(dp(8));
            frame.setBackground(bg);
            frame.setClipToOutline(true);
            ImageView image = new ImageView(WallpaperBrowserActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            TextView label = new TextView(WallpaperBrowserActivity.this);
            label.setTextColor(Color.WHITE);
            label.setTextSize(12);
            label.setShadowLayer(4, 0, 1, 0xCC000000);
            label.setPadding(dp(8), dp(4), dp(8), dp(6));
            label.setSingleLine(true);   // NASA's titles run long
            label.setEllipsize(TextUtils.TruncateAt.END);
            frame.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
            TextView tick = new TextView(WallpaperBrowserActivity.this);
            tick.setText("✓");
            tick.setTextColor(Color.WHITE);
            tick.setTextSize(18);
            tick.setShadowLayer(4, 0, 1, 0xCC000000);
            tick.setPadding(dp(8), dp(4), dp(10), 0);
            frame.addView(tick, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
            Holder h = new Holder(frame, image, label, tick);
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(0);
            ring.setCornerRadius(dp(8));
            ring.setStroke(dp(3), highlight);
            frame.setOnFocusChangeListener((v, focused) -> {
                frame.setForeground(focused ? ring : null);
                v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(150).start();
                if (focused && h.result != null) {
                    credit.setText(mine ? mineLine(h.result)
                            : h.result.credit != null ? h.result.credit : appleLine(h.result));
                }
                if (focused && !mine && h.getAdapterPosition() >= results.size() - 2 * COLUMNS) more();
            });
            frame.setOnClickListener(v -> {
                if (h.result == null) return;
                if (mine && listKey != null) pick(h.result);
                else if (mine) askDelete(h.result, h.getAdapterPosition());
                else take(h.result);
            });
            frame.setOnLongClickListener(v -> {
                if (h.result == null || !mine || listKey == null) return false;
                askDelete(h.result, h.getAdapterPosition());
                return true;
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
            if (payloads.contains(TICK) && h.result != null) {
                h.tick.setVisibility(ticked(h.result) ? View.VISIBLE : View.GONE);
                return;
            }
            onBindViewHolder(h, position);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Result r = results.get(position);
            h.result = r;
            h.label.setText(r.label);
            h.tick.setVisibility(ticked(r) ? View.VISIBLE : View.GONE);
            h.image.setAlpha(r.odd ? 0.5f : 1f);   // not made for this: it can be picked all the same
            Bitmap cached = cache.get(r.thumb);
            h.image.setImageBitmap(cached);
            h.image.setTag(r.thumb);
            if (cached != null) return;
            int gen = generation;
            thumbs.execute(() -> {
                Bitmap b = thumb(r.thumb);
                if (b == null) return;
                main.post(() -> {
                    if (isDestroyed()) return;
                    cache.put(r.thumb, b);
                    if (gen == generation && r.thumb.equals(h.image.getTag())) {
                        h.image.setImageBitmap(b);
                        if (isApple() && h.itemView.hasFocus()) credit.setText(appleLine(r));   // its size now known
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return results.size();
        }
    }

    /** Apple's: the video's name and size (once known) before the credit; the others', the credit. */
    private String appleLine(Result r) {
        if (!isApple()) return sourceCredit();
        long size = AppleAerials.knownSize(r.full);
        return r.label + (size > 0 ? " · " + Formatter.formatShortFileSize(this, size) : "") + " · "
                + sourceCredit();
    }

    /** A thumbnail, decoded no bigger than a grid cell needs: from the web, a file on the TV, or a video's frame. */
    private Bitmap thumb(String url) {
        if (url.startsWith("aerial:")) {   // "aerial:" id "|" url: a frame read over the network, then kept
            int bar = url.indexOf('|');
            return AppleAerials.thumbnail(this, url.substring(7, bar), url.substring(bar + 1));
        }
        if (url.equals("own:banner")) return ownBannerBmp;
        if (url.equals("own:icon")) return ownIconBmp;
        if (url.startsWith("video:")) return videoThumb(url.substring(6));
        return thumbOf(url);
    }

    /**
     * A Library video's thumbnail ("video:" id): the picture kept beside it, else made now and kept: a frame of
     * the file, or for Apple's (when the device won't read a frame of it) the one Apple's grid shows.
     */
    private Bitmap videoThumb(String id) {
        Wallpapers.Item item = wallpapers.item(id);
        if (item == null) return null;
        Bitmap b = wallpapers.thumb(item);
        if (b != null) return b;
        b = videoFrame(wallpapers.file(item).getPath());
        if (b == null && Wallpapers.APPLE.equals(item.source)) b = AppleAerials.thumbnail(this, item.name);
        if (b != null) wallpapers.keepThumb(item, b);
        return b;
    }

    private static Bitmap thumbOf(String url) {
        try {
            if (url.startsWith("tile:")) return BitmapFactory.decodeFile(url.substring(5));   // 640 x 360 already
            if (url.startsWith("file:")) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;   // a 1920 x 1080 wallpaper, down to a cell's 480 x 270
                return BitmapFactory.decodeFile(url.substring(5), o);
            }
            byte[] bytes = Http.get(url, 4 * 1024 * 1024);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 400) o.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** A frame a second into the video, cell-sized. */
    private static Bitmap videoFrame(String path) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            Bitmap frame = android.os.Build.VERSION.SDK_INT >= 27
                    ? r.getScaledFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                    : r.getFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            int w = 480, h = Math.max(1, frame.getHeight() * w / Math.max(1, frame.getWidth()));
            Bitmap small = frame.getWidth() <= w ? frame : Bitmap.createScaledBitmap(frame, w, h, true);
            if (small != frame) frame.recycle();
            return small;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    static Intent intent(Context context, String source) {
        return new Intent(context, WallpaperBrowserActivity.class).putExtra(SOURCE, source);
    }
}
