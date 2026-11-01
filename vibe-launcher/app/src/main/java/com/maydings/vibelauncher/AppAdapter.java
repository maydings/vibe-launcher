package com.maydings.vibelauncher;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock. The order is the one the
 * user arranged with Move (saved in preferences); before that, the dock_apps favourites then alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Long-press OK on a tile. */
        void onTileLongPress(int position, View card);

        /** A tile in move mode changed position; it keeps focus. */
        void onTileMoved(int position);

        /** OK on a folder's tile. */
        default void onFolderOpen(String folderId, View card) {
        }
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** The fixed last tile that opens the system Apps screen. */
        final boolean allApps;
        /** The app has a banner of its own (Edit offers its icon instead, and back). */
        boolean ownBanner;
        /** A folder's tile: its id and its apps, in order; null for an app. */
        final String folderId;
        final List<Entry> members;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(label, component, banner, icon, false);
        }

        Entry(String label, ComponentName component, Drawable banner, Drawable icon, boolean allApps) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.allApps = allApps;
            this.folderId = null;
            this.members = Collections.emptyList();
        }

        /** A folder's tile, its preview drawn from its apps (FolderDrawable). */
        Entry(Context ctx, Folders.Folder folder, List<Entry> members) {
            this.label = folder.name;
            this.component = new ComponentName(ctx.getPackageName(), Folders.KEY_PREFIX + folder.id);
            this.pkg = ctx.getPackageName();
            this.banner = new FolderDrawable(members);
            this.icon = null;
            this.allApps = false;
            this.folderId = folder.id;
            this.members = members;
        }

        /** Its place in the saved order: the package, or "folder:<id>". */
        String key() {
            return folderId != null ? Folders.KEY_PREFIX + folderId : pkg;
        }

        /** What the tile shows, to tell when it must be drawn again: a folder's name and apps too. */
        String look() {
            if (folderId == null) return label;
            StringBuilder sb = new StringBuilder(label);
            for (Entry m : members) sb.append('|').append(m.pkg);
            return sb.toString();
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    private static final String PREFS = "tiles";
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";
    private static final Object PAYLOAD_STATE = new Object();

    private final Context ctx;
    private final TileListener listener;
    /** Tiles per row, and how many of the first make the dock (as many or fewer, centred). */
    private final int columns, dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    private final SharedPreferences prefs;
    /** Position of the tile being moved, or -1. */
    private int moving = -1;
    /** Folders on Home; in an open folder, the one it shows (no dock, no All apps tile, no folders). */
    final Folders folders;
    private final String openFolder;
    /** Apps installed or updated since the last reload: their tiles are drawn again (a new banner). */
    private final Set<String> updated = new HashSet<>();

    AppAdapter(Context ctx, TileListener listener, int columns, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.columns = columns;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.folders = new Folders(prefs);
        this.openFolder = null;
    }

    /** The tiles of one open folder: its apps only, in rows of columns, moved within it. */
    AppAdapter(Context ctx, TileListener listener, int columns, String folderId) {
        this.ctx = ctx;
        this.listener = listener;
        this.columns = columns;
        this.dockSize = 0;
        this.favourites = Collections.emptyList();
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.folders = new Folders(prefs);
        this.openFolder = folderId;
    }

    @SuppressLint("NotifyDataSetChanged")   // only the first fill; later reloads apply just the changes
    void reload() {
        // A tile part-way through Move (an app updating in the background reloads the list) keeps where it
        // got to: the order so far is saved, else the reload put every tile back.
        if (moving >= 0) stopMove();
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        Set<String> userHidden = userHidden();
        folders.reread();
        // Some system apps declare two Home entries under one name (Sony's Timers & Clock on some
        // BRAVIAs), which would show as two identical tiles: keep the first per package and label.
        Set<String> seen = new HashSet<>();
        TileCustom custom = new TileCustom(ctx);
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || userHidden.contains(pkg)) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            if (!seen.add(pkg + '\n' + label)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            // A small square "banner" (Rockchip's Media Center ships a 64 px one) is a blur cropped over a
            // wide tile: treat it as none, so the tile shows the icon and name instead.
            if (banner != null && !isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight())) banner = null;
            boolean ownBanner = banner != null;
            // Tile menu > Edit: a picture of the user's own, or the icon instead of the banner, and a name.
            File picture = custom.picture(pkg);
            Bitmap chosen = picture != null ? BitmapFactory.decodeFile(picture.getPath()) : null;
            if (chosen != null) banner = new BitmapDrawable(ctx.getResources(), chosen);
            else if (custom.useIcon(pkg)) banner = null;
            String name = custom.name(pkg);
            if (name != null) label = name;
            // The icon only shows on a tile without a banner (and only colours the glow then too).
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            Entry entry = new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon);
            entry.ownBanner = ownBanner;
            fresh.add(entry);
        }
        if (openFolder != null) {
            // An open folder: its apps, in its order.
            Folders.Folder f = folders.get(openFolder);
            List<Entry> inside = new ArrayList<>();
            for (String pkg : f != null ? f.apps : Collections.<String>emptyList()) {
                for (Entry e : fresh) {
                    if (e.pkg.equals(pkg)) {
                        inside.add(e);
                        break;
                    }
                }
            }
            fresh = inside;
        } else {
            fresh = withFolders(fresh);
            // The saved arrangement wins; apps installed since then go at the end, alphabetically.
            String saved = prefs.getString(KEY_ORDER, "");
            List<String> order = saved.isEmpty() ? favourites : Arrays.asList(saved.split(","));
            Collections.sort(fresh, (a, b) -> {
                int fa = order.indexOf(a.key()), fb = order.indexOf(b.key());
                if (fa >= 0 || fb >= 0) {
                    if (fa < 0) return 1;
                    if (fb < 0) return -1;
                    return Integer.compare(fa, fb);
                }
                return a.label.compareToIgnoreCase(b.label);
            });
            fresh.add(new Entry(ctx.getString(R.string.all_apps), new ComponentName(ctx, MainActivity.class),
                    null, ctx.getDrawable(R.drawable.ic_all_apps), true));
        }
        moving = -1;
        List<Entry> shown = fresh;
        if (items.isEmpty()) {
            updated.clear();   // every tile is drawn anew anyway
            items.addAll(shown);
            notifyDataSetChanged();
            return;
        }
        // Apply only what changed (hidden, unhidden, installed, uninstalled), so the rest of the
        // tiles glide into place instead of the grid redrawing.
        List<Entry> old = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return shown.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                return old.get(o).component.equals(shown.get(n).component);
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                // Same name isn't enough: an update can bring a new banner.
                Entry e = shown.get(n);
                if (!old.get(o).look().equals(e.look()) || updated.contains(e.pkg)) return false;
                for (Entry m : e.members) if (updated.contains(m.pkg)) return false;   // a folder's preview
                return true;
            }
        });
        updated.clear();
        items.clear();
        items.addAll(shown);
        diff.dispatchUpdatesTo(this);
        // Tiles that crossed the dock edge show or hide their name.
        notifyItemRangeChanged(0, items.size(), PAYLOAD_STATE);
    }

    /** An app was installed or updated: the next reload draws its tile again, whatever its name. */
    void markUpdated(String pkg) {
        updated.add(pkg);
    }

    /**
     * Home's tiles with each folder's apps taken in under its tile. A folder none of whose apps is here shows
     * no tile, but is kept: an app missing for a moment (being updated, on storage not mounted yet) mustn't
     * lose its folder. Only emptying it by hand deletes it (MainActivity.folderChanged).
     */
    private List<Entry> withFolders(List<Entry> apps) {
        List<Entry> out = new ArrayList<>(apps);
        Set<String> claimed = new HashSet<>();   // in one folder only, even if the saved list says otherwise
        for (Folders.Folder f : folders.all()) {
            List<Entry> members = new ArrayList<>();
            for (String pkg : f.apps) {
                if (!claimed.add(pkg)) continue;
                for (Entry e : apps) {
                    if (e.pkg.equals(pkg)) {
                        members.add(e);
                        break;
                    }
                }
            }
            if (members.isEmpty()) continue;
            out.removeAll(members);
            out.add(new Entry(ctx, f, members));
        }
        return out;
    }

    /** Home's tiles by their keys (package, or "folder:<id>"), as saved: for changing the order around folders. */
    List<String> orderKeys() {
        List<String> keys = new ArrayList<>();
        for (Entry e : items) if (!e.allApps) keys.add(e.key());
        return keys;
    }

    void saveOrder(List<String> keys) {
        prefs.edit().putString(KEY_ORDER, android.text.TextUtils.join(",", keys)).apply();
    }

    boolean isFolder(int position) {
        return position >= 0 && position < items.size() && items.get(position).folderId != null;
    }

    /** A folder's apps as shown (installed, not hidden), in its order; none for any other tile. */
    List<Entry> membersAt(int position) {
        return isFolder(position) ? items.get(position).members : Collections.emptyList();
    }

    /** The dock position of the folder holding pkg, or -1 (not in a folder, or the folder isn't in the dock). */
    int dockFolderHolding(String pkg) {
        for (int i = 0; i < dockCount(); i++) {
            for (Entry m : membersAt(i)) if (m.pkg.equals(pkg)) return i;
        }
        return -1;
    }

    /** Opens the app (a folder's, from the dock's shelf). */
    void launchApp(Entry e) {
        launch(e, null);
    }

    String folderIdAt(int position) {
        return isFolder(position) ? items.get(position).folderId : null;
    }

    /** Where the tile with this key (package, or "folder:<id>") is now, or -1. */
    int positionOfKey(String key) {
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).allApps && items.get(i).key().equals(key)) return i;
        }
        return -1;
    }

    /** Lets go of every tile and its artwork (Home is in the background and memory is short). */
    @SuppressLint("NotifyDataSetChanged")   // everything goes
    void clear() {
        moving = -1;
        items.clear();
        notifyDataSetChanged();
    }

    /** Wide enough to be a banner. A drawable with no intrinsic size (a colour, a shape) still fills the tile. */
    static boolean isBannerShaped(int width, int height) {
        return width <= 0 || height <= 0 || width >= height * 1.4f;
    }

    // ---- hiding ----------------------------------------------------------------------------------

    /** Packages hidden from the long-press menu (on top of config.xml's hidden_apps). */
    Set<String> userHidden() {
        return new HashSet<>(prefs.getStringSet(KEY_HIDDEN, Collections.emptySet()));
    }

    /**
     * The apps that can be tiles right now: installed, enabled and not hidden (by config.xml or from the
     * menu). Backdrops keeps pools only for these. Safe off the main thread.
     */
    static Set<String> tilePackages(Context ctx) {
        Set<String> hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        hidden.addAll(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_HIDDEN, Collections.emptySet()));
        Set<String> apps = new HashSet<>();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(probe, 0)) {   // enabled apps only
            if (ri.activityInfo != null && !hidden.contains(ri.activityInfo.packageName)) apps.add(ri.activityInfo.packageName);
        }
        return apps;
    }

    void hide(int position) {
        if (!isApp(position)) return;
        Set<String> h = userHidden();
        h.add(items.get(position).pkg);
        prefs.edit().putStringSet(KEY_HIDDEN, h).apply();
        reload();
    }

    void unhide(String pkg) {
        Set<String> h = userHidden();
        h.remove(pkg);
        prefs.edit().putStringSet(KEY_HIDDEN, h).apply();
        reload();
    }

    /** Downloaded apps (or updates to built-in ones) can be uninstalled; built-in apps only hidden. */
    boolean canUninstall(int position) {
        if (!isApp(position)) return false;
        try {
            int flags = ctx.getPackageManager().getApplicationInfo(items.get(position).pkg, 0).flags;
            return (flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Opens the system uninstall confirmation; the grid reloads when Home resumes. */
    void uninstall(int position) {
        if (!canUninstall(position)) return;
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + items.get(position).pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** A real app tile (not the All apps tile, nor a folder). */
    boolean isApp(int position) {
        return position >= 0 && position < items.size() && !items.get(position).allApps
                && items.get(position).folderId == null;
    }

    // ---- move mode -------------------------------------------------------------------------------

    boolean isMoving() {
        return moving >= 0;
    }

    void startMove(int position) {
        if (!isApp(position) && !isFolder(position)) return;
        moving = position;
        notifyItemChanged(position, PAYLOAD_STATE);
    }

    void stopMove() {
        if (moving < 0) return;
        int pos = moving;
        moving = -1;
        notifyItemChanged(pos, PAYLOAD_STATE);
        if (openFolder != null) {   // an open folder keeps its own order
            List<String> apps = new ArrayList<>();
            for (Entry e : items) apps.add(e.pkg);
            folders.reread();
            Folders.Folder f = folders.get(openFolder);
            // Its hidden apps (or ones missing for now) stay in it, after the ones shown.
            if (f != null) for (String pkg : f.apps) if (!apps.contains(pkg)) apps.add(pkg);
            folders.setApps(openFolder, apps);
        } else {
            saveOrder(orderKeys());
        }
    }

    /** Handles a D-pad key for the tile being moved. */
    private boolean moveKey(int keyCode) {
        int from = moving, to;
        int last = openFolder != null ? items.size() - 1 : items.size() - 2;   // All apps stays at Home's end
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: to = from - 1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: to = from + 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: to = above(from); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: to = Math.min(below(from), last); break;
            default: return false;
        }
        if (to < 0 || to > last || to == from) return true;
        items.add(to, items.remove(from));
        moving = to;
        notifyItemMoved(from, to);
        // Tiles that crossed the dock edge show or hide their name.
        notifyItemRangeChanged(Math.min(from, to), Math.abs(to - from) + 1, PAYLOAD_STATE);
        listener.onTileMoved(to);
        return true;
    }

    // ---- rows: the dock (dockSize tiles, centred), then rows of columns -------------------------------

    /** The dock's tiles as there are apps: fewer than dockSize when the TV has only a few. */
    int dockCount() {
        return Math.min(dockSize, items.size());
    }

    boolean inDock(int position) {
        return position >= 0 && position < dockCount();
    }

    /** The first position of position's row. */
    int rowStart(int position) {
        int dock = dockCount();
        return position < dock ? 0 : dock + (position - dock) / columns * columns;
    }

    /** The last position of position's row. */
    int rowEnd(int position) {
        int dock = dockCount();
        return position < dock ? dock - 1 : Math.min(rowStart(position) + columns - 1, items.size() - 1);
    }

    /** The empty space before the dock's first tile, and after its last, in tiles (halves when odd). */
    float dockInset() {
        return (columns - dockCount()) / 2f;
    }

    /**
     * The tile above: from the row under the dock, the dock tile nearest above it (the dock is centred, and
     * may be shorter); -1 from the dock.
     */
    int above(int position) {
        int dock = dockCount();
        if (position < dock) return -1;
        int col = position - dock;
        if (col >= columns) return position - columns;
        if (dock == 0) return -1;   // an open folder's first row: nothing above
        return dockOver(col);
    }

    /** The tile below, the nearest under a dock tile; the same position on the last row (nowhere to go). */
    int below(int position) {
        int dock = dockCount(), count = items.size();
        if (position < dock) {
            if (count <= dock) return position;
            return Math.min(count - 1, dock + columnUnder(position));
        }
        if (rowStart(position) + columns >= count) return position;   // the last row
        return Math.min(position + columns, count - 1);
    }

    /** The dock tile a position belongs under: itself in the dock, else the nearest above its column. */
    int dockTileFor(int position) {
        int dock = dockCount();
        if (dock == 0) return 0;
        if (position < dock) return position;
        return dockOver((position - dock) % columns);
    }

    /*
     * A shorter dock is centred, so with an odd difference (4 apps over 5 columns) each dock tile sits half
     * over two columns. Down takes the left one, and Up from a column takes the dock tile over its middle,
     * which is the one Down came from: rounding both ways (half up) moved one column right on every Down
     * and Up, all the way to the end of the row.
     */

    /** The column under dock tile i: the left one of the two it half covers. */
    private int columnUnder(int i) {
        return Math.max(0, Math.min(columns - 1, (int) Math.floor(i + dockInset())));
    }

    /** The dock tile over the middle of column col. */
    private int dockOver(int col) {
        return Math.max(0, Math.min(dockCount() - 1, (int) Math.floor(col + 0.5f - dockInset())));
    }

    /**
     * Spans for the grid, in half tiles (its span count is twice the columns): a tile takes two, and the
     * dock's first and last tiles take the empty space beside them too, which DockDecoration insets, so a
     * shorter dock is centred and the next row starts on a line of its own.
     */
    int spanSize(int position) {
        int dock = dockCount(), extra = columns - dock;   // the empty half tiles on each side
        if (position >= dock) return 2;
        int span = 2;
        if (position == 0) span += extra;
        if (position == dock - 1) span += extra;
        return span;
    }

    /** The TV's App info page for pkg, or null where the firmware has none. */
    static Intent appInfo(Context context, String pkg) {
        Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        return context.getPackageManager().resolveActivity(info, PackageManager.MATCH_DEFAULT_ONLY) != null ? info : null;
    }

    void openAppInfo(int position) {
        if (!isApp(position)) return;
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + items.get(position).pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        // Sized to the column width: 4 per row keeps the design size, 5 and 6 shrink to fit.
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int w = tileWidthPx(width - parent.getPaddingLeft() - parent.getPaddingRight(), columns, density);
        int h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, this, listener);
    }

    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass,
     * capped at the design size (so 4 columns on a 1080p TV are exactly as before).
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int fit = (int) ((innerWidthPx / columns - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name under it and the item's padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(44 * density);
    }

    /** Tile corner radius: 16dp at the design width, proportionally less on smaller tiles. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(h, position);
        } else {
            bindState(h, position);
        }
    }

    /** The parts that change while moving: dock label visibility and the move outline. */
    private void bindState(Holder h, int position) {
        h.name.setVisibility(inDock(position) ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        h.card.setForeground(position == moving ? ctx.getDrawable(R.drawable.move_outline) : null);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        bindState(h, position);
        // Drawn again while it has focus (a new picture, an app update): it stays zoomed with its name shown, as
        // the focus listener left it; otherwise it came back at normal size until focus moved away and back.
        boolean focused = h.card.isFocused();
        h.card.animate().cancel();
        h.name.animate().cancel();
        float density = ctx.getResources().getDisplayMetrics().density;
        h.name.setAlpha(focused ? 1f : 0f);
        h.name.setTranslationY(focused ? density * 4f : -density * 6f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        // An opaque banner covers the whole card (centerCrop), so the card's dark fill under it would be drawn
        // for nothing on every frame; it stays under banners with see-through parts and under icon tiles.
        h.card.setBackground(e.banner != null && e.banner.getOpacity() == PixelFormat.OPAQUE ? null : h.cardBackground);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            // All apps is the grid of squares alone, centred: its name shows above the dock when it's focused.
            h.label.setVisibility(e.allApps ? View.GONE : View.VISIBLE);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) h.icon.getLayoutParams();
            int below = e.allApps ? 0 : Math.round(14 * ctx.getResources().getDisplayMetrics().density);
            if (lp.bottomMargin != below) {
                lp.bottomMargin = below;
                h.icon.setLayoutParams(lp);
            }
        }
        h.card.setOnClickListener(v -> launch(e, v));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && !isMoving()) listener.onTileLongPress(pos, v);
            return true;
        });
    }

    private void launch(Entry e, View card) {
        if (e.folderId != null) {
            listener.onFolderOpen(e.folderId, card);
            return;
        }
        if (e.allApps) {
            openAllApps();
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    /** The system Apps screen (Settings > Apps). */
    private void openAllApps() {
        for (String action : new String[]{Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
                Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS}) {
            try {
                ctx.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    String packageAt(int position) {
        return isApp(position) ? items.get(position).pkg : null;
    }

    /** This app's tile, or null when it isn't here. */
    Entry entryOf(String pkg) {
        int at = positionOf(pkg);
        return at >= 0 ? items.get(at) : null;
    }

    /** Where this app's tile is now, or -1 when it's gone. */
    int positionOf(String pkg) {
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).allApps && items.get(i).pkg.equals(pkg)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) {
            Entry from = e.folderId != null && !e.members.isEmpty() ? e.members.get(0) : e;
            e.ambient = vividColor(from.banner != null ? from.banner : from.icon);
        }
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        /** item_app.xml's tile_bg: behind an icon tile, or a banner with see-through parts. */
        final Drawable cardBackground;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        /** OK ends move mode on key down; swallow the matching key up so it doesn't launch the app. */
        private boolean swallowOkUp;

        Holder(View v, AppAdapter adapter, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            cardBackground = card.getBackground();
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
                if (event.getAction() == KeyEvent.ACTION_UP && ok && swallowOkUp) {
                    swallowOkUp = false;
                    return true;
                }
                // The remote's Menu key, when the TV passes it to Home (a TV that opens its own menu with it
                // never does): the same menu as holding OK.
                if (keyCode == KeyEvent.KEYCODE_MENU && !adapter.isMoving()) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) view.performLongClick();
                    return true;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                // Held OK: remotes send repeats flagged as a long press. Open the menu from that too
                // (cancelling the touch-style timer so it can't fire a second time).
                if (ok && event.isLongPress() && !adapter.isMoving()) {
                    view.cancelLongPress();
                    view.performLongClick();
                    swallowOkUp = true;
                    return true;
                }
                if (adapter.isMoving()) {
                    if (ok || keyCode == KeyEvent.KEYCODE_BACK) {
                        adapter.stopMove();
                        swallowOkUp = ok;
                        return true;
                    }
                    return adapter.moveKey(keyCode);   // other keys (volume etc.) pass through
                }
                int pos = getBindingAdapterPosition();
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // Stay put at a row's ends: Android's focus search would otherwise jump up to the top bar.
                    if (pos == RecyclerView.NO_POSITION) return false;
                    return keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? pos == adapter.rowStart(pos)
                            : pos == adapter.rowEnd(pos);
                }
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .withLayer()   // the rounded banner is drawn once and scaled, not redrawn every frame
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
