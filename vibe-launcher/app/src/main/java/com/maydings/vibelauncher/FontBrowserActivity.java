package com.maydings.vibelauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fonts to add (Settings > Library > Fonts > Browse fonts): Google Fonts, as Fontsource lists them (no
 * key; each font with the scripts it covers), those for the language shown now first, then all, and a search. Each
 * name is shown in its own font as it's focused (small fonts only: Chinese, Japanese and Korean ones are megabytes).
 * OK downloads it whole from Google Fonts (its regular and a medium weight, as TTF), into Fonts. With MINE, the
 * fonts on the device instead, where OK deletes one.
 */
public final class FontBrowserActivity extends Activity {

    static final String MINE = "mine";
    private static final String CATALOGUE = "https://api.fontsource.org/v1/fonts";
    private static final String CSS = "https://fonts.googleapis.com/css2?family=%s:wght@%s";
    /** The catalogue is kept a day. */
    private static final long KEEP_MS = 24L * 60 * 60 * 1000;
    private static final int FOR_LANGUAGE = 0, ALL = 1, SEARCH = 2;
    private static final String[] LARGE = {"japanese", "korean", "chinese-simplified", "chinese-traditional",
            "chinese-hongkong"};

    /** One font in the catalogue (or, in MINE, on the device: id is its Fonts id). */
    private static final class Font {
        final String id, family, category;
        final List<String> subsets;
        final List<Integer> weights;

        Font(String id, String family, String category, List<String> subsets, List<Integer> weights) {
            this.id = id;
            this.family = family;
            this.category = category;
            this.subsets = subsets;
            this.weights = weights;
        }

        boolean large() {
            for (String s : LARGE) if (subsets.contains(s)) return true;
            return false;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newFixedThreadPool(3);
    private final LruCache<String, Typeface> previews = new LruCache<>(60);
    private final java.util.Set<String> fetching = new java.util.HashSet<>();
    private final List<Font> all = new ArrayList<>(), shown = new ArrayList<>();
    private final List<TextView> tabs = new ArrayList<>();
    private Fonts fonts;
    private boolean mine;
    private int tab = FOR_LANGUAGE;
    private String script;
    private float density;
    private int highlight;
    private RecyclerView list;
    private Adapter adapter;
    private TextView message;
    private EditText search;
    private Runnable preview;

    static Intent intent(Context context, boolean mine) {
        return new Intent(context, FontBrowserActivity.class).putExtra(MINE, mine);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        FontInflater.install(this);
        super.onCreate(state);
        mine = getIntent().getBooleanExtra(MINE, false);
        fonts = new Fonts(this);
        density = getResources().getDisplayMetrics().density;
        highlight = Highlight.SETTINGS;
        script = script(Locale.getDefault());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B10);
        root.setPadding(dp(48), dp(28), dp(48), dp(18));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(mine ? getString(R.string.fonts) + " · " + getString(R.string.media_on_device)
                : getString(R.string.fonts_browse));
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(UiFont.get(this, R.string.font_regular));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!mine) {
            String[] names = {getString(R.string.font_for, UiFont.languageName(this)), getString(R.string.font_all),
                    getString(R.string.wp_search)};
            for (int i = 0; i < names.length; i++) {
                int which = i;
                TextView t = new TextView(this);
                t.setText(names[i]);
                t.setTextSize(16);
                t.setTypeface(UiFont.get(this, R.string.font_regular));
                t.setPadding(dp(20), dp(8), dp(20), dp(8));
                t.setFocusable(true);
                t.setClickable(true);
                t.setBackgroundResource(R.drawable.pill_bg);
                Highlight.pill(t, highlight);
                t.setOnClickListener(v -> pickTab(which));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMarginStart(dp(10));
                top.addView(t, lp);
                tabs.add(t);
            }
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
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
            filter();
            return true;
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        root.addView(search, slp);

        FrameLayout body = new FrameLayout(this);
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setClipToPadding(false);
        list.setPadding(dp(8), dp(16), dp(8), dp(16));
        adapter = new Adapter();
        list.setAdapter(adapter);
        // A row redrawn (its preview, its tick) stays put, with the focus: no cross-fade from a new row.
        ((androidx.recyclerview.widget.SimpleItemAnimator) list.getItemAnimator()).setSupportsChangeAnimations(false);
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        message = new TextView(this);
        message.setTextColor(0xB3FFFFFF);
        message.setTextSize(17);
        message.setGravity(Gravity.CENTER);
        body.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView credit = new TextView(this);
        credit.setTextColor(0x80FFFFFF);
        credit.setTextSize(13);
        credit.setSingleLine(true);
        credit.setEllipsize(TextUtils.TruncateAt.END);
        credit.setText(mine ? R.string.wp_delete_short : R.string.font_credit);
        root.addView(credit);
        setContentView(root);

        if (mine) {
            showMine();
            return;
        }
        message.setText(R.string.wp_loading);
        work.execute(() -> {
            List<Font> loaded = catalogue();
            main.post(() -> {
                if (isFinishing()) return;
                if (loaded == null) {
                    message.setText(getString(R.string.wp_offline, "Fontsource"));
                    return;
                }
                all.addAll(loaded);
                pickTab(FOR_LANGUAGE);
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---- the catalogue -------------------------------------------------------------------------------

    /** Fontsource's list of Google's fonts (kept a day), or null when it can't be had. Worker thread. */
    private List<Font> catalogue() {
        File cache = new File(getCacheDir(), "fontsource.json");
        String json = null;
        try {
            if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < KEEP_MS) {
                json = new String(java.nio.file.Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8);
            } else {
                byte[] bytes = Http.get(CATALOGUE, 8 * 1024 * 1024);
                json = new String(bytes, StandardCharsets.UTF_8);
                try (FileOutputStream out = new FileOutputStream(cache)) {
                    out.write(bytes);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (cache.exists()) {   // stale, but better than nothing offline
                try {
                    json = new String(java.nio.file.Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                }
            }
        }
        if (json == null) return null;
        List<Font> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!"google".equals(o.optString("type"))) continue;   // only those Google serves whole
                List<String> subsets = new ArrayList<>();
                JSONArray s = o.optJSONArray("subsets");
                for (int j = 0; s != null && j < s.length(); j++) subsets.add(s.getString(j));
                List<Integer> weights = new ArrayList<>();
                JSONArray w = o.optJSONArray("weights");
                for (int j = 0; w != null && j < w.length(); j++) weights.add(w.getInt(j));
                JSONArray styles = o.optJSONArray("styles");
                if (weights.isEmpty() || (styles != null && !styles.toString().contains("normal"))) continue;
                out.add(new Font(o.getString("id"), o.getString("family"), o.optString("category"), subsets, weights));
            }
        } catch (JSONException e) {
            return null;
        }
        return out;
    }

    /** The Fontsource subset a language's letters are in. */
    static String script(Locale locale) {
        String l = locale.getLanguage(), c = locale.getCountry();
        switch (l) {
            case "ar": case "fa": case "ur": return "arabic";
            case "iw": case "he": return "hebrew";
            case "hi": case "mr": case "ne": return "devanagari";
            case "bn": return "bengali";
            case "ta": return "tamil";
            case "te": return "telugu";
            case "kn": return "kannada";
            case "ml": return "malayalam";
            case "gu": return "gujarati";
            case "pa": return "gurmukhi";
            case "th": return "thai";
            case "km": return "khmer";
            case "my": return "myanmar";
            case "si": return "sinhala";
            case "am": return "ethiopic";
            case "ja": return "japanese";
            case "ko": return "korean";
            case "zh": return "TW".equals(c) || "HK".equals(c) ? "chinese-traditional" : "chinese-simplified";
            case "ru": case "uk": case "bg": return "cyrillic";
            case "el": return "greek";
            case "vi": return "vietnamese";
            case "cs": case "pl": case "ro": case "tr": case "hu": case "hr": case "sk": case "da": case "fi":
            case "nb": case "sv": case "sw": case "tl": case "in": case "id": case "ms": case "de":
                return "latin-ext";
            default: return "latin";
        }
    }

    private void pickTab(int which) {
        tab = which;
        for (int i = 0; i < tabs.size(); i++) tabs.get(i).setSelected(i == which);
        search.setVisibility(which == SEARCH ? View.VISIBLE : View.GONE);
        if (which == SEARCH) {
            search.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        }
        filter();
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")   // a new list: every row is another font
    private void filter() {
        shown.clear();
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (Font f : all) {
            if (tab == FOR_LANGUAGE && !f.subsets.contains(script)) continue;
            if (tab == SEARCH && (q.isEmpty() || !f.family.toLowerCase(Locale.ROOT).contains(q))) continue;
            shown.add(f);
        }
        adapter.notifyDataSetChanged();
        message.setText(shown.isEmpty() && (tab != SEARCH || !q.isEmpty()) ? getString(R.string.wp_none) : "");
        list.scrollToPosition(0);
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private void showMine() {
        shown.clear();
        List<Fonts.Item> items = fonts.list();
        Collections.reverse(items);   // newest first
        for (Fonts.Item item : items) {
            shown.add(new Font(item.id, item.family, item.source, Collections.emptyList(), Collections.emptyList()));
        }
        adapter.notifyDataSetChanged();
        message.setText(shown.isEmpty() ? getString(R.string.media_empty,
                getString(R.string.settings) + " > " + getString(R.string.media_title)) : "");
        if (!shown.isEmpty()) list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(0);
            if (h != null) h.itemView.requestFocus();
        });
    }

    // ---- downloading -------------------------------------------------------------------------------

    /** The weights to take: the regular one (nearest 400) and a medium one for headings (500 or 600), or none. */
    private static int[] weights(Font f) {
        int regular = f.weights.get(0);
        for (int w : f.weights) if (Math.abs(w - 400) < Math.abs(regular - 400)) regular = w;
        int medium = f.weights.contains(500) ? 500 : f.weights.contains(600) ? 600 : 0;
        return medium != 0 && medium != regular ? new int[]{regular, medium} : new int[]{regular};
    }

    /** The TTF addresses Google Fonts gives for these weights of the family, in order; null when it can't. */
    private static List<String> urls(Font f, int[] weights) throws IOException {
        StringBuilder w = new StringBuilder();
        for (int x : weights) w.append(w.length() > 0 ? ";" : "").append(x);
        // A plain client's name: Google then gives whole TTF files, not a browser's split WOFF2 ones.
        String css = new String(Http.get(String.format(Locale.ROOT, CSS, f.family.replace(' ', '+'), w),
                Collections.singletonMap("User-Agent", "VibeLauncher"), 64 * 1024), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("url\\((https://[^)]+\\.ttf)\\)").matcher(css);
        while (m.find()) out.add(m.group(1));
        return out.size() == weights.length ? out : null;
    }

    private static boolean download(String url, File to, long max) {
        try (FileOutputStream out = new FileOutputStream(to)) {
            out.write(Http.get(url, (int) Math.min(Integer.MAX_VALUE, max)));
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();
            return false;
        }
    }

    /** OK on a font: downloaded whole into Fonts, saying so when it's in (or why not). */
    private void take(Font f) {
        if (fonts.has(Fonts.GOOGLE, f.family)) {
            Toast.makeText(this, R.string.wp_already, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!fetching.add(f.id)) return;
        Toast.makeText(this, R.string.wp_downloading, Toast.LENGTH_SHORT).show();
        work.execute(() -> {
            Fonts.Item item = null;
            try {
                int[] weights = weights(f);
                List<String> urls = urls(f, weights);
                if (urls != null) {
                    File regular = fonts.incoming(), medium = urls.size() > 1 ? fonts.incoming() : null;
                    if (download(urls.get(0), regular, Fonts.MAX_FILE)
                            && (medium == null || download(urls.get(1), medium, Fonts.MAX_FILE))) {
                        item = fonts.add(regular, medium, f.family, Fonts.GOOGLE);
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        regular.delete();
                        if (medium != null) //noinspection ResultOfMethodCallIgnored
                            medium.delete();
                    }
                }
            } catch (IOException | RuntimeException e) {
                item = null;
            }
            Fonts.Item added = item;
            main.post(() -> {
                fetching.remove(f.id);
                if (isFinishing()) return;
                Toast.makeText(this, added != null ? R.string.font_added : R.string.font_failed,
                        Toast.LENGTH_SHORT).show();
                int at = shown.indexOf(f);
                if (at >= 0) adapter.notifyItemChanged(at);
            });
        });
    }

    /** The focused font's name in its own font: its regular file, kept in the cache (small fonts only). */
    private void loadPreview(Font f) {
        if (mine || f.large() || previews.get(f.id) != null || fetching.contains("p:" + f.id)) return;
        fetching.add("p:" + f.id);
        work.execute(() -> {
            Typeface t = null;
            File dir = new File(getCacheDir(), "fontpreview");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File file = new File(dir, f.id + ".ttf");
            try {
                if (!file.exists()) {
                    List<String> urls = urls(f, new int[]{weights(f)[0]});
                    if (urls != null) download(urls.get(0), file, 2L * 1024 * 1024);
                }
                if (file.exists()) t = Typeface.createFromFile(file);
            } catch (IOException | RuntimeException e) {
                t = null;
            }
            Typeface loaded = t;
            main.post(() -> {
                fetching.remove("p:" + f.id);
                if (loaded == null || isFinishing()) return;
                previews.put(f.id, loaded);
                int at = shown.indexOf(f);
                if (at >= 0) adapter.notifyItemChanged(at);
            });
        });
    }

    /** MINE: asks, then deletes it from the device (a choice that was it goes back to Outfit). */
    private void askDelete(Font f) {
        View panel = getLayoutInflater().inflate(R.layout.dialog_update, findViewById(android.R.id.content), false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(R.string.media_delete_title);
        panel.findViewById(R.id.notesScroll).setVisibility(View.GONE);
        TextView go = panel.findViewById(R.id.install), cancel = panel.findViewById(R.id.cancelUpdate);
        go.setText(R.string.wp_delete);
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setContentView(panel);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        Highlight.pill(go, highlight);
        Highlight.pill(cancel, highlight);
        cancel.setOnClickListener(v -> dialog.dismiss());
        go.setOnClickListener(v -> {
            dialog.dismiss();
            fonts.delete(this, f.id);
            setResult(RESULT_OK);
            int at = shown.indexOf(f);
            if (at < 0) return;
            shown.remove(at);
            adapter.notifyItemRemoved(at);
            if (shown.isEmpty()) {
                showMine();
                return;
            }
            int next = Math.min(at, shown.size() - 1);
            list.post(() -> {
                RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(next);
                if (h != null) h.itemView.requestFocus();
            });
        });
        dialog.show();
        cancel.requestFocus();   // the safe one first
    }

    // ---- the list ----------------------------------------------------------------------------------

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView name, detail;
        Font font;

        Holder(View v, TextView name, TextView detail) {
            super(v);
            this.name = name;
            this.detail = detail;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private final Map<String, Typeface> mineFaces = new HashMap<>();

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout row = new LinearLayout(FontBrowserActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(24), dp(14), dp(24), dp(14));
            row.setFocusable(true);
            row.setClickable(true);
            GradientDrawable plate = new GradientDrawable();
            plate.setCornerRadius(dp(10));
            plate.setColor(0xFF1C1C22);
            row.setBackground(plate);
            TextView name = new TextView(FontBrowserActivity.this);
            name.setTextColor(Color.WHITE);
            name.setTextSize(24);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView detail = new TextView(FontBrowserActivity.this);
            detail.setTextColor(0x99FFFFFF);
            detail.setTextSize(15);
            detail.setTypeface(UiFont.get(FontBrowserActivity.this, R.string.font_regular));
            row.addView(detail);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);
            Holder h = new Holder(row, name, detail);
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(0);
            ring.setCornerRadius(dp(10));
            ring.setStroke(dp(3), highlight);
            row.setOnFocusChangeListener((v, focused) -> {
                row.setForeground(focused ? ring : null);
                main.removeCallbacks(preview);
                if (focused && h.font != null) {
                    Font f = h.font;
                    preview = () -> loadPreview(f);
                    main.postDelayed(preview, 350);   // not for each one passed on the way
                }
            });
            row.setOnClickListener(v -> {
                if (h.font == null) return;
                if (mine) askDelete(h.font);
                else take(h.font);
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Font f = shown.get(position);
            h.font = f;
            h.name.setText(f.family);
            Typeface face;
            if (mine) {
                face = mineFaces.get(f.id);
                if (face == null) {
                    face = Fonts.typeface(FontBrowserActivity.this, f.id, false);
                    if (face != null) mineFaces.put(f.id, face);
                }
            } else {
                face = previews.get(f.id);
            }
            h.name.setTypeface(face != null ? face : UiFont.get(FontBrowserActivity.this, R.string.font_regular));
            if (mine) {
                h.detail.setText("");
                return;
            }
            StringBuilder d = new StringBuilder(f.category);
            if (f.large()) d.append(" · ").append(getString(R.string.font_large));
            if (fonts.has(Fonts.GOOGLE, f.family)) d.append("  ✓");
            h.detail.setText(d);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}
