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
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Picking custom wallpapers on the TV (Settings > Backdrops > Custom wallpapers): pictures from Wallhaven's public
 * API (safe for work only, 16:9, at least 1920 x 1080; no key) or videos from Pexels' API (with the person's free
 * key, Settings > API keys), as a grid of thumbnails, more coming in as it's scrolled. OK on one downloads it into
 * Wallpapers (a Pexels video in its largest version up to 1080p); a tick marks those already in. Each grid says
 * where its pictures come from, as the services ask; Pexels also names the video's maker.
 */
public final class WallpaperBrowserActivity extends Activity {

    static final String SOURCE = "source";
    /** The person's own wallpapers (Wallpapers), to see and delete: no tabs, no downloads. */
    static final String MINE = "mine";
    private static final int COLUMNS = 4;
    private static final String WALLHAVEN = "https://wallhaven.cc/api/v1/search?purity=100&categories=111"
            + "&atleast=1920x1080&ratios=16x9&page=%d";
    private static final String PEXELS_POPULAR = "https://api.pexels.com/videos/popular?per_page=24&min_width=1280&page=%d";
    private static final String PEXELS_SEARCH = "https://api.pexels.com/videos/search?per_page=24&orientation=landscape"
            + "&query=%s&page=%d";
    private static final int MAX_PICTURE = 40 * 1024 * 1024;

    /** One thumbnail: what to download (a picture or a video), its id there, a line under it, and its maker. */
    private static final class Result {
        final String id, thumb, full, label, maker;

        Result(String id, String thumb, String full, String label, String maker) {
            this.id = id;
            this.thumb = thumb;
            this.full = full;
            this.label = label;
            this.maker = maker;
        }
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

    private boolean pexels, mine;
    /** In MINE: each grid item's wallpaper. */
    private final java.util.Map<String, Wallpapers.Item> items = new java.util.HashMap<>();
    private String key;
    private Wallpapers wallpapers;
    /** What's listed: 0 the first tab (Wallhaven's top, Pexels' popular), 1 Wallhaven's latest, 2 a search. */
    private int tab;
    private String query = "";
    private int page, generation;
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
        pexels = Wallpapers.PEXELS.equals(getIntent().getStringExtra(SOURCE));
        mine = MINE.equals(getIntent().getStringExtra(SOURCE));
        key = new VibeSettings(this).pexelsKey();
        wallpapers = Wallpapers.get(this);
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
        title.setText(mine ? R.string.wp_mine : pexels ? R.string.wp_pexels_title : R.string.wp_wallhaven_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(UiFont.get(this, R.string.font_regular));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int[] names = mine ? new int[0] : pexels ? new int[]{R.string.wp_popular, R.string.wp_search}
                : new int[]{R.string.wp_toplist, R.string.wp_latest, R.string.wp_search};
        for (int i = 0; i < names.length; i++) {
            int which = pexels && i == 1 ? 2 : i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(16);
            t.setTypeface(UiFont.get(this, R.string.font_regular));
            t.setPadding(dp(20), dp(8), dp(20), dp(8));
            t.setFocusable(true);
            t.setClickable(true);
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
        credit.setText(mine ? R.string.wp_delete_short : pexels ? R.string.wp_credit_pexels : R.string.wp_credit_wallhaven);
        root.addView(credit);

        setContentView(root);
        if (mine) {
            showMine();
            return;
        }
        if (pexels && key.isEmpty()) {
            message.setText(R.string.wp_pexels_no_key);
            return;
        }
        pickTab(0);
    }

    private void pickTab(int which) {
        tab = which;
        for (int i = 0; i < tabs.size(); i++) {
            int t = pexels && i == 1 ? 2 : i;
            tabs.get(i).setSelected(t == which);
        }
        if (which == 2) {
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
        ended = false;
        loading = false;
        message.setText(R.string.wp_loading);
        more();
    }

    /** The next page, unless one is coming already or there are no more. */
    private void more() {
        if (loading || ended) return;
        loading = true;
        int gen = generation, next = page + 1;
        String url = url(next);
        pages.execute(() -> {
            List<Result> found = null;
            try {
                found = pexels ? pexels(url) : wallhaven(url);
            } catch (IOException | RuntimeException | org.json.JSONException e) {
                android.util.Log.i("VibeWallpapers", "page failed", e);
            }
            List<Result> got = found;
            main.post(() -> {
                if (gen != generation || isDestroyed()) return;
                loading = false;
                if (got == null) {
                    if (results.isEmpty()) {
                        message.setText(getString(R.string.wp_offline, pexels ? "Pexels" : "Wallhaven"));
                    }
                    return;
                }
                page = next;
                if (got.isEmpty()) ended = true;
                int from = results.size();
                results.addAll(got);
                adapter.notifyItemRangeInserted(from, got.size());
                message.setText(results.isEmpty() ? getString(R.string.wp_none) : "");
                if (from == 0 && !results.isEmpty()) grid.post(() -> {
                    RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(0);
                    if (h != null && tab != 2) h.itemView.requestFocus();
                });
            });
        });
    }

    private String url(int p) {
        if (pexels) {
            return tab == 2 ? String.format(Locale.ROOT, PEXELS_SEARCH, Uri.encode(query), p)
                    : String.format(Locale.ROOT, PEXELS_POPULAR, p);
        }
        String u = String.format(Locale.ROOT, WALLHAVEN, p);
        if (tab == 0) return u + "&sorting=toplist&topRange=1M";
        if (tab == 1) return u + "&sorting=date_added";
        return u + "&sorting=relevance&q=" + Uri.encode(query);
    }

    private static List<Result> wallhaven(String url) throws IOException, org.json.JSONException {
        JSONArray data = new JSONObject(Http.getText(url, Collections.emptyMap())).getJSONArray("data");
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            String path = o.optString("path"), thumb = o.optJSONObject("thumbs") != null
                    ? o.getJSONObject("thumbs").optString("large") : "";
            if (path.isEmpty() || thumb.isEmpty()) continue;
            out.add(new Result(o.optString("id"), thumb, path, o.optString("resolution"), null));
        }
        return out;
    }

    private List<Result> pexels(String url) throws IOException, org.json.JSONException {
        JSONArray videos = new JSONObject(Http.getText(url, Collections.singletonMap("Authorization", key)))
                .getJSONArray("videos");
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < videos.length(); i++) {
            JSONObject v = videos.getJSONObject(i);
            String file = bestFile(v.optJSONArray("video_files"));
            if (file == null) continue;
            int seconds = v.optInt("duration");
            String maker = v.optJSONObject("user") != null ? v.getJSONObject("user").optString("name") : null;
            out.add(new Result(String.valueOf(v.optLong("id")), v.optString("image"), file,
                    String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60), maker));
        }
        return out;
    }

    /** The video's MP4 to download: the widest no wider than 1920 (a TV-sized one), else the smallest there is. */
    private static String bestFile(JSONArray files) throws org.json.JSONException {
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

    /** MINE: the wallpapers there are, newest first, each by its own picture (a video by a frame of it). */
    private void showMine() {
        generation++;
        results.clear();
        items.clear();
        for (Wallpapers.Item item : Wallpapers.sorted(wallpapers.list())) {
            int name = item.source.equals(Wallpapers.WALLHAVEN) ? R.string.wp_item_wallhaven
                    : item.source.equals(Wallpapers.PEXELS) ? R.string.wp_item_pexels
                    : item.video ? R.string.wp_item_phone_video : R.string.wp_item_phone_picture;
            String path = wallpapers.file(item).getPath();
            results.add(new Result(item.id, (item.video ? "video:" : "file:") + path, path, getString(name), null));
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
        String source = pexels ? Wallpapers.PEXELS : Wallpapers.WALLHAVEN;
        if (wallpapers.has(source, r.id)) {
            Toast.makeText(this, R.string.wp_already, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.wp_downloading, Toast.LENGTH_SHORT).show();
        if (!fetching.add(source + ":" + r.id)) return;   // OK again while it's still coming: once is enough
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        int screenW = Math.max(w, h), screenH = Math.min(w, h);
        downloads.execute(() -> {
            File part = wallpapers.incoming();
            Wallpapers.Item added = null;
            try {
                // A video's size isn't known before it comes: it may only fill what's spare beyond the TV's share.
                long room = pexels ? wallpapers.videoRoom() : MAX_PICTURE;
                if (room > 0) {
                    Http.download(r.full, part, (int) room);
                    added = wallpapers.add(part, pexels, source, r.id, screenW, screenH);
                }
            } catch (IOException | RuntimeException e) {
                android.util.Log.i("VibeWallpapers", "download failed", e);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
            boolean ok = added != null;
            main.post(() -> {
                fetching.remove(source + ":" + r.id);
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
            frame.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
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
                if (focused && h.result != null && pexels) {
                    credit.setText(h.result.maker != null && !h.result.maker.isEmpty()
                            ? getString(R.string.wp_video_by, h.result.maker) : getString(R.string.wp_credit_pexels));
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
            h.tick.setVisibility(!mine && wallpapers.has(pexels ? Wallpapers.PEXELS : Wallpapers.WALLHAVEN, r.id)
                    ? View.VISIBLE : View.GONE);
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
