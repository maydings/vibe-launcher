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
 * Picking custom wallpapers on the TV (Settings > Backdrops > Custom wallpapers), as a grid of thumbnails, more
 * coming in as it's scrolled. OK on one downloads it into Wallpapers; a tick marks those already in. Each grid says
 * where its pictures come from, as the services ask, and the focused one's maker where there is one. The sources:
 * <ul>
 * <li>Wallhaven: pictures (safe for work only, 16:9, at least 1920 x 1080; no key).
 * <li>Pexels: videos, with the person's key (Pexels gives no new ones now; those who have one keep it).
 * <li>Pixabay: pictures and videos, with the person's free key. Its replies are kept a day, as Pixabay asks.
 * <li>NASA's Image and Video Library: pictures and videos, no key.
 * <li>Wikimedia Commons: featured pictures, and videos in VP8 only (most of Commons' are VP9, AV1 or Theora,
 * which older TVs can't play or play badly), no key.
 * </ul>
 * The last three have Pictures, Videos and a Search that finds both. A video is taken in its largest version up to
 * 1080p.
 */
public final class WallpaperBrowserActivity extends Activity {

    static final String SOURCE = "source";
    /** The person's own wallpapers (Wallpapers), to see and delete: no tabs, no downloads. */
    static final String MINE = "mine";
    private static final int COLUMNS = 4;
    /** The tabs: the first list (Wallhaven's top, Pexels' popular, the others' pictures), the second, a search. */
    private static final int FIRST = 0, SECOND = 1, SEARCH = 2;

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
     * under it, and the line about it at the bottom while it has focus (its maker; null: the grid's own).
     */
    private static final class Result {
        final String id, thumb, full, label, credit;
        final boolean video;

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
    private String pexelsKey, pixabayKey;
    private Wallpapers wallpapers;
    private File apiCache;
    /** What's listed: FIRST, SECOND or SEARCH. */
    private int tab;
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
        super.onCreate(state);
        source = getIntent().getStringExtra(SOURCE);
        if (source == null) source = Wallpapers.WALLHAVEN;
        mine = MINE.equals(source);
        VibeSettings settings = new VibeSettings(this);
        pexelsKey = settings.pexelsKey();
        pixabayKey = settings.pixabayKey();
        wallpapers = Wallpapers.get(this);
        apiCache = new File(getCacheDir(), "wallpaper-api");
        density = getResources().getDisplayMetrics().density;
        highlight = Highlight.color(this);

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
        credit.setText(mine ? getString(R.string.wp_delete_short) : sourceCredit());
        root.addView(credit);

        setContentView(root);
        if (mine) {
            showMine();
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
        pickTab(FIRST);
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

    /** The grid's heading. */
    private String title() {
        if (mine) return getString(R.string.wp_mine);
        if (isPexels()) return getString(R.string.wp_pexels_title);
        if (isWallhaven()) return getString(R.string.wp_wallhaven_title);
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
        page = 0;
        emptyPages = 0;
        ended = false;
        // A list is only one kind; a search, both.
        picturesEnded = tab == SECOND && !isWallhaven() && !isPexels();
        videosEnded = tab == FIRST && !isWallhaven() && !isPexels();
        loading = false;
        message.setText(R.string.wp_loading);
        credit.setText(sourceCredit());
        more();
    }

    /** The next page, unless one is coming already or there are no more. */
    private void more() {
        if (loading || ended) return;
        loading = true;
        int gen = generation, next = page + 1, t = tab;
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
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < PIXABAY_KEEP_MS) {
            try {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                // unreadable: asked for again
            }
        }
        String text = Http.getText(url, Collections.emptyMap());
        //noinspection ResultOfMethodCallIgnored
        apiCache.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // not kept: asked for again next time
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
                        true));
                continue;
            }
            if (!wide) continue;   // a tall one, cut to the screen, would be its middle strip
            String full = fitW >= 1280 ? fit
                    : !orig.isEmpty() && (origW == 0 || origW >= 1280) && origSize <= MAX_PICTURE ? orig : "";
            if (full.isEmpty()) continue;
            part.results.add(new Result(id, safeUrl(thumb), safeUrl(full), title, by, false));
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
            String full = ii.optString("thumburl", ii.optString("url"));
            int at = full.indexOf('?');
            if (at > 0) full = full.substring(0, at);
            if (full.isEmpty()) continue;
            String thumb = full.replaceFirst("/\\d+px-", "/500px-");
            part.results.add(new Result(String.valueOf(page.optLong("pageid")), thumb, full, w + " × " + h,
                    commonsCredit(ii), false));
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
                    duration((int) Math.round(vi.optDouble("duration", 0))), commonsCredit(vi), true));
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

    /** MINE: the wallpapers there are, newest first, each by its own picture (a video by a frame of it). */
    private void showMine() {
        generation++;
        results.clear();
        items.clear();
        for (Wallpapers.Item item : Wallpapers.sorted(wallpapers.list())) {
            String name;
            switch (item.source) {
                case Wallpapers.WALLHAVEN: name = getString(R.string.wp_item_wallhaven); break;
                case Wallpapers.PEXELS: name = getString(R.string.wp_item_pexels); break;
                case Wallpapers.PIXABAY:
                case Wallpapers.NASA:
                case Wallpapers.COMMONS:
                    name = getString(item.video ? R.string.wp_item_video_from : R.string.wp_item_picture_from,
                            serviceName(item.source));
                    break;
                default:
                    name = getString(item.video ? R.string.wp_item_phone_video : R.string.wp_item_phone_picture);
            }
            String path = wallpapers.file(item).getPath();
            results.add(new Result(item.id, (item.video ? "video:" : "file:") + path, path, name, null, item.video));
            items.put(item.id, item);
        }
        ended = true;
        adapter.notifyDataSetChanged();
        message.setText(results.isEmpty() ? getString(R.string.wp_mine_empty) : "");
        if (!results.isEmpty()) grid.post(() -> {
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(0);
            if (h != null) h.itemView.requestFocus();
        });
    }

    /** MINE: asks, then deletes it from the TV; the one after it (or before, at the end) takes focus. */
    private void askDelete(Result r, int position) {
        Wallpapers.Item item = items.get(r.id);
        if (item == null) return;
        View panel = getLayoutInflater().inflate(R.layout.dialog_update, findViewById(android.R.id.content), false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(R.string.wp_delete_title);
        panel.findViewById(R.id.notesScroll).setVisibility(View.GONE);   // the question is enough
        TextView go = panel.findViewById(R.id.install), cancel = panel.findViewById(R.id.cancelUpdate);
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
        cancel.setOnClickListener(v -> dialog.dismiss());
        go.setOnClickListener(v -> {
            dialog.dismiss();
            wallpapers.delete(item);
            items.remove(r.id);
            int at = results.indexOf(r);
            if (at < 0) return;
            results.remove(at);
            adapter.notifyItemRemoved(at);
            if (results.isEmpty()) {
                message.setText(R.string.wp_mine_empty);
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
        String from = source;
        downloads.execute(() -> {
            Wallpapers.Item added = null;
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
                    if (at >= 0) adapter.notifyItemChanged(at, "tick");
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
            long room = r.video ? wallpapers.videoRoom() : MAX_PICTURE;
            if (room <= 0) return null;
            Http.download(url, part, (int) room);
            return wallpapers.add(part, r.video, from, r.id, screenW, screenH);
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
                if (focused && h.result != null && !mine) {
                    credit.setText(h.result.credit != null ? h.result.credit : sourceCredit());
                }
                if (focused && !mine && h.getAdapterPosition() >= results.size() - 2 * COLUMNS) more();
            });
            frame.setOnClickListener(v -> {
                if (h.result == null) return;
                if (mine) askDelete(h.result, h.getAdapterPosition());
                else take(h.result);
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Result r = results.get(position);
            h.result = r;
            h.label.setText(r.label);
            h.tick.setVisibility(!mine && wallpapers.has(source, r.id) ? View.VISIBLE : View.GONE);
            Bitmap cached = cache.get(r.thumb);
            h.image.setImageBitmap(cached);
            h.image.setTag(r.thumb);
            if (cached != null) return;
            int gen = generation;
            thumbs.execute(() -> {
                Bitmap b = thumb(r.thumb);
                if (b == null) return;
                main.post(() -> {
                    cache.put(r.thumb, b);
                    if (gen == generation && r.thumb.equals(h.image.getTag())) h.image.setImageBitmap(b);
                });
            });
        }

        @Override
        public int getItemCount() {
            return results.size();
        }
    }

    /** A thumbnail, decoded no bigger than a grid cell needs: from the web, a file on the TV, or a video's frame. */
    private static Bitmap thumb(String url) {
        if (url.startsWith("video:")) return videoFrame(url.substring(6));
        if (url.startsWith("file:")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;   // a 1920 x 1080 wallpaper, down to a cell's 480 x 270
            return BitmapFactory.decodeFile(url.substring(5), o);
        }
        try {
            byte[] bytes = Http.get(url, 4 * 1024 * 1024);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 400) o.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A frame a second into the video, cell-sized. */
    private static Bitmap videoFrame(String path) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            Bitmap frame = r.getFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            int w = 480, h = Math.max(1, frame.getHeight() * w / Math.max(1, frame.getWidth()));
            Bitmap small = Bitmap.createScaledBitmap(frame, w, h, true);
            if (small != frame) frame.recycle();
            return small;
        } catch (RuntimeException e) {
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
