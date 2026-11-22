package com.maydings.vibelauncher;

import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The menus a tile opens (MainActivity's, kept apart from it for their size): the one a long press brings up on
 * an app, a folder or an app in a folder (move, hide, folders, app info), the hidden apps, and what Edit and
 * Backdrops lead to (a tile's picture, icon and name; its backdrops' order). Their windows and rows are Home's
 * own, and what they change is done through Home and its tiles.
 */
final class TileMenus {

    private final MainActivity home;

    TileMenus(MainActivity home) {
        this.home = home;
    }

    void onTileLongPress(int position, View card) {
        // Holding OK on a remote triggers both the long-press timer and the long-press key repeat;
        // only the first may open the menu, or a second copy stays behind holding focus.
        if (home.openMenu != null && home.openMenu.isShowing()) return;
        View panel = home.getLayoutInflater().inflate(R.layout.popup_inputs, home.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = home.newMenu(panel);
        if (home.adapter.isApp(position)) {
            // Acted on by app, not by place: an app installed or updated while the menu is open moves the
            // tiles, and the place would then point at a different app.
            String pkg = home.adapter.packageAt(position);
            home.addInputRow(rows, popup, R.drawable.ic_move, home.getString(R.string.move), -1, () -> {
                int at = home.adapter.positionOf(pkg);
                if (at < 0) return;
                home.adapter.startMove(at);
                Toast.makeText(home, R.string.move_hint, Toast.LENGTH_LONG).show();
            });
            home.addInputRow(rows, popup, R.drawable.ic_edit, home.getString(R.string.edit_tile), -1, () -> editTile(pkg, card, home.adapter));
            home.addInputRow(rows, popup, R.drawable.ic_folder, home.getString(R.string.new_folder), -1, () -> home.newFolder(pkg));
            if (!home.shownFolders().isEmpty()) {
                home.addInputRow(rows, popup, R.drawable.ic_folder, home.getString(R.string.move_to_folder), -1,
                        () -> moveToFolder(pkg, card));
            }
            home.addInputRow(rows, popup, R.drawable.ic_hide, home.getString(R.string.hide), -1, () -> {
                int at = home.adapter.positionOf(pkg);
                if (at < 0) return;
                home.adapter.hide(at);
                home.grid.postOnAnimation(() -> home.focusTile(at));   // the tile sliding into its place
            });
            // Remove uninstalls: only downloaded apps (or updates to built-in ones) can be.
            if (home.adapter.canUninstall(position)) {
                home.addInputRow(rows, popup, R.drawable.ic_delete, home.getString(R.string.remove), -1,
                        () -> home.adapter.uninstall(home.adapter.positionOf(pkg)));
            }
            // Some TVs have no App info page at all (upstream: CVTE's settings); then no row that only fails.
            if (AppAdapter.appInfo(home, pkg) != null) {
                home.addInputRow(rows, popup, R.drawable.ic_settings, home.getString(R.string.app_info), -1,
                        () -> home.adapter.openAppInfo(home.adapter.positionOf(pkg)));
            }
        }
        if (home.adapter.isFolder(position)) {
            String id = home.adapter.folderIdAt(position);
            String key = Folders.KEY_PREFIX + id;
            home.addInputRow(rows, popup, R.drawable.ic_move, home.getString(R.string.move), -1, () -> {
                int at = home.adapter.positionOfKey(key);
                if (at < 0) return;
                home.adapter.startMove(at);
                Toast.makeText(home, R.string.move_hint, Toast.LENGTH_LONG).show();
            });
            home.addInputRow(rows, popup, R.drawable.ic_edit, home.getString(R.string.edit_tile), -1, () -> editFolder(id, card));
            home.addInputRow(rows, popup, R.drawable.ic_delete, home.getString(R.string.remove_folder), -1, () -> home.removeFolder(id));
        }
        if (!home.adapter.userHidden().isEmpty()) {
            if (rows.getChildCount() > 0) home.addSeparator(rows);
            home.addInputRow(rows, popup, R.drawable.ic_all_apps, home.getString(R.string.show_hidden), -1,
                    () -> showHidden(card));
        }
        if (rows.getChildCount() == 0) return;
        home.showMenu(popup, panel, rows, card, Gravity.START);
    }

    /** Lists hidden apps; picking one puts it back at the end of the grid. */
    void showHidden(View anchor) {
        View panel = home.getLayoutInflater().inflate(R.layout.popup_inputs, home.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = home.newMenu(panel);
        PackageManager pm = home.getPackageManager();
        List<String> pkgs = new ArrayList<>(home.adapter.userHidden());
        Collections.sort(pkgs, (a, b) -> appLabel(pm, a).compareToIgnoreCase(appLabel(pm, b)));
        for (String pkg : pkgs) {
            home.addInputRow(rows, popup, R.drawable.ic_hide, appLabel(pm, pkg), -1, () -> home.adapter.unhide(pkg));
        }
        if (rows.getChildCount() == 0) return;
        home.showMenu(popup, panel, rows, anchor, Gravity.START);
    }

    static String appLabel(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Move to folder: the folders by name; the app goes to the end of the one picked. */
    void moveToFolder(String pkg, View anchor) {
        View panel = home.getLayoutInflater().inflate(R.layout.popup_inputs, home.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = home.newMenu(panel);
        for (Folders.Folder f : home.shownFolders()) {
            home.addInputRow(rows, popup, R.drawable.ic_folder, f.name, -1, () -> {
                int was = home.adapter.positionOf(pkg);
                home.adapter.folders.reread();
                home.adapter.folders.add(f.id, pkg);
                List<String> keys = home.adapter.orderKeys();
                keys.remove(pkg);
                home.adapter.saveOrder(keys);
                home.adapter.reload();
                if (was >= 0) home.grid.postOnAnimation(() -> home.focusTile(was));   // the next tile slides into its place
            });
        }
        if (rows.getChildCount() == 0) return;
        home.showMenu(popup, panel, rows, anchor, Gravity.START);
    }

    /**
     * Edit: what the tile shows (Change icon: the app's banner, its icon, or one of the Library's pictures), its
     * backdrops, a name, or Reset to the app's own. On Home or in a folder (tiles: whichever shows it).
     */
    void editTile(String pkg, View anchor, AppAdapter tiles) {
        AppAdapter.Entry e = tiles.entryOf(pkg);
        if (e == null) return;
        TileCustom custom = new TileCustom(home);
        View panel = home.getLayoutInflater().inflate(R.layout.popup_inputs, home.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = home.newMenu(panel);
        home.addInputRow(rows, popup, R.drawable.ic_image, home.getString(R.string.change_picture), -1,
                () -> pickTilePicture(pkg, e.label));
        home.addInputRow(rows, popup, R.drawable.ic_play, home.getString(R.string.tab_backdrops), -1,
                () -> pickBackdrops(pkg, e.label, anchor));
        home.addInputRow(rows, popup, R.drawable.ic_edit, home.getString(R.string.rename_folder), -1,
                () -> home.askText(R.string.tile_name, e.label, typed -> {
                    new TileCustom(home).setName(pkg, typed);   // empty: the app's own name again
                    tileChanged(pkg);
                }));
        if (custom.changed(pkg)) {
            home.addInputRow(rows, popup, R.drawable.ic_restore, home.getString(R.string.reset_tile), -1, () -> {
                new TileCustom(home).reset(pkg);
                tileChanged(pkg);
            });
        }
        home.showMenu(popup, panel, rows, anchor, Gravity.START);
    }

    /** An app's tile changed (Edit): drawn again wherever it shows, in a folder's preview and shelf too. */
    void tileChanged(String pkg) {
        home.adapter.markUpdated(pkg);
        if (home.folderView != null) home.folderView.adapter.markUpdated(pkg);
        home.shelfContents = null;   // the dock folder's row is made again
        home.folderChanged();
    }

    /**
     * Change icon: what the tile shows, in a grid: an app's own banner and icon first, then the Library's pictures
     * (Settings > Library, the one place they're added). OK on one puts it on this tile (an app's package, or a
     * folder's key); OK again on a Library picture takes it off.
     */
    void pickTilePicture(String key, String label) {
        home.inSettings = true;   // the grid is over Home as Settings is: nothing on Home restarts meanwhile
        home.startActivityForResult(WallpaperBrowserActivity.mine(home, WallpaperBrowserActivity.TILES, key, label),
                MainActivity.REQUEST_PICK);
    }

    /**
     * Backdrops: what shows behind this tile, first to last: the first that's enabled and has something to show
     * is it, the rest only when those before have nothing. OK on one goes from enabled to moving (Up and Down
     * carry it) to disabled and round again; its colour is never disabled. Home shows each change at once. Under
     * them, the tile's own list, ticked from the Library's pictures and videos.
     */
    void pickBackdrops(String key, String label, View anchor) {
        View panel = home.getLayoutInflater().inflate(R.layout.popup_inputs, home.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = home.newMenu(panel);
        char[] moving = {0};
        for (char c : home.wallpapers.order(key).toCharArray()) orderRow(rows, popup, key, Character.toLowerCase(c), moving);
        nameOrder(rows, key, moving[0]);
        home.addInputRow(rows, popup, R.drawable.ic_image, home.getString(R.string.backdrop_pictures), -1, () -> {
            home.inSettings = true;
            home.startActivityForResult(WallpaperBrowserActivity.mine(home, WallpaperBrowserActivity.PICTURES, key, label),
                    MainActivity.REQUEST_PICK);
        });
        home.addInputRow(rows, popup, R.drawable.ic_play, home.getString(R.string.backdrop_videos), -1, () -> {
            home.inSettings = true;
            home.startActivityForResult(WallpaperBrowserActivity.mine(home, WallpaperBrowserActivity.VIDEOS, key, label),
                    MainActivity.REQUEST_PICK);
        });
        home.showMenu(popup, panel, rows, anchor, Gravity.START);
    }

    /** One of the things a tile can show, a row of its Backdrops menu (the menu's first rows, in its order). */
    void orderRow(LinearLayout rows, PopupWindow popup, String key, char what, char[] moving) {
        int icon = what == Wallpapers.CONTENT ? R.drawable.ic_livetv : what == Wallpapers.OWN ? R.drawable.ic_edit
                : what == Wallpapers.SYSTEM ? R.drawable.ic_image : what == Wallpapers.FLIGHTS ? R.drawable.ic_move
                : R.drawable.ic_hide;
        View row = home.addInputRow(rows, popup, icon, "", -1, () -> { });
        row.setTag(what);
        row.setOnClickListener(v -> {   // the menu stays
            String order = home.wallpapers.order(key);
            if (moving[0] == what) {
                moving[0] = 0;
                order = Wallpapers.enabled(order, what, false);
            } else if (!Wallpapers.on(order, what)) {
                order = Wallpapers.enabled(order, what, true);
            } else {
                moving[0] = what;
            }
            home.wallpapers.setOrder(key, order);
            nameOrder(rows, key, moving[0]);
            home.applyWallpaperChange();
        });
        row.setOnKeyListener((v, keyCode, event) -> {
            if (moving[0] != what) return false;
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN: {
                    if (!down) return true;
                    int dir = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    String order = home.wallpapers.order(key), to = Wallpapers.moved(order, Wallpapers.at(order, what), dir);
                    if (to.equals(order)) return true;
                    int at = rows.indexOfChild(row);
                    View other = rows.getChildAt(at + dir);
                    rows.removeView(other);   // not the focused one: it keeps focus
                    rows.addView(other, at);
                    home.wallpapers.setOrder(key, to);
                    nameOrder(rows, key, moving[0]);
                    home.applyWallpaperChange();
                    row.playSoundEffect(dir < 0 ? SoundEffectConstants.NAVIGATION_UP
                            : SoundEffectConstants.NAVIGATION_DOWN);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) {   // put down where it is, still enabled
                        moving[0] = 0;
                        nameOrder(rows, key, moving[0]);
                    }
                    return true;
                default:
                    return false;   // OK: the click drops it
            }
        });
    }

    /** The order's rows as they are now: each one's place and name, and whether it's enabled (ticked) or moving. */
    void nameOrder(LinearLayout rows, String key, char moving) {
        String order = home.wallpapers.order(key);
        for (int i = 0; i < order.length() && i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (!(row.getTag() instanceof Character)) continue;
            char what = (Character) row.getTag();
            int name = what == Wallpapers.CONTENT ? R.string.bd_content : what == Wallpapers.OWN ? R.string.bd_own
                    : what == Wallpapers.SYSTEM ? R.string.wp_custom : what == Wallpapers.FLIGHTS
                    ? R.string.flights_title : R.string.bd_colour;
            boolean on = Wallpapers.on(order, what);
            TextView title = row.findViewById(R.id.title);
            String numbered = (i + 1) + "   " + home.getString(name);
            title.setText(numbered);
            title.setAlpha(on ? 1f : 0.4f);
            TextView status = row.findViewById(R.id.status);
            status.setText(what == moving ? "\u2195" : on ? "\u2713" : "");
            status.setTextSize(15);
            status.setVisibility(View.VISIBLE);
        }
    }

    /** A folder's Edit, as an app's: its tile picture, its backdrops, Rename, or Reset to its preview. */
    void editFolder(String id, View anchor) {
        home.adapter.folders.reread();
        Folders.Folder f = home.adapter.folders.get(id);
        if (f == null) return;
        String key = Folders.KEY_PREFIX + id;
        View panel = home.getLayoutInflater().inflate(R.layout.popup_inputs, home.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = home.newMenu(panel);
        home.addInputRow(rows, popup, R.drawable.ic_image, home.getString(R.string.change_picture), -1,
                () -> pickTilePicture(key, f.name));
        home.addInputRow(rows, popup, R.drawable.ic_play, home.getString(R.string.tab_backdrops), -1,
                () -> pickBackdrops(key, f.name, anchor));
        home.addInputRow(rows, popup, R.drawable.ic_edit, home.getString(R.string.rename_folder), -1, () -> home.renameFolder(id));
        if (new TileCustom(home).changed(key)) {
            home.addInputRow(rows, popup, R.drawable.ic_restore, home.getString(R.string.reset_tile), -1, () -> {
                new TileCustom(home).reset(key);
                home.adapter.reload();
                home.shelfContents = null;
            });
        }
        home.showMenu(popup, panel, rows, anchor, Gravity.START);
    }

    /** OK on an open folder's name: Rename, Remove folder, or Close (as Back). */
    void folderTitleMenu(String id) {
        if (home.folderView == null || (home.openMenu != null && home.openMenu.isShowing())) return;
        View panel = home.getLayoutInflater().inflate(R.layout.popup_inputs, home.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = home.newMenu(panel);
        home.addInputRow(rows, popup, R.drawable.ic_edit, home.getString(R.string.edit_tile), -1,
                () -> editFolder(id, home.folderView != null ? home.folderView.title : home.root));
        home.addInputRow(rows, popup, R.drawable.ic_delete, home.getString(R.string.remove_folder), -1, () -> {
            home.closeFolder();
            home.removeFolder(id);   // after closing, so focus ends on the folder's first app, in its place
        });
        home.addInputRow(rows, popup, R.drawable.ic_close, home.getString(R.string.close), -1, home::closeFolder);
        home.showMenu(popup, panel, rows, home.folderView.title, Gravity.START);
    }

    /** Holding OK on an app in an open folder: move it within, out to Home, hide, remove, info; rename the folder. */
    void folderTileMenu(int position, View card) {
        if (home.folderView == null || (home.openMenu != null && home.openMenu.isShowing())) return;
        AppAdapter tiles = home.folderView.adapter;
        String id = home.folderView.folderId;
        String pkg = tiles.packageAt(position);
        if (pkg == null) return;
        View panel = home.getLayoutInflater().inflate(R.layout.popup_inputs, home.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = home.newMenu(panel);
        home.addInputRow(rows, popup, R.drawable.ic_move, home.getString(R.string.move), -1, () -> {
            int at = tiles.positionOf(pkg);
            if (at < 0) return;
            tiles.startMove(at);
            Toast.makeText(home, R.string.move_hint, Toast.LENGTH_LONG).show();
        });
        home.addInputRow(rows, popup, R.drawable.ic_edit, home.getString(R.string.edit_tile), -1, () -> editTile(pkg, card, tiles));
        home.addInputRow(rows, popup, R.drawable.ic_home, home.getString(R.string.move_to_home), -1, () -> {
            home.adapter.folders.reread();
            List<String> keys = home.adapter.orderKeys();
            keys.remove(pkg);
            int at = keys.indexOf(Folders.KEY_PREFIX + id);
            keys.add(at >= 0 ? at + 1 : keys.size(), pkg);   // just after the folder
            home.adapter.folders.remove(pkg);
            home.adapter.saveOrder(keys);
            home.folderChanged(true);
            if (home.folderView != null) home.folderView.focus(position);
        });
        home.addInputRow(rows, popup, R.drawable.ic_hide, home.getString(R.string.hide), -1, () -> {
            int at = tiles.positionOf(pkg);
            if (at < 0) return;
            tiles.hide(at);
            home.folderChanged(true);
            if (home.folderView != null) home.folderView.focus(at);
        });
        if (tiles.canUninstall(position)) {
            home.addInputRow(rows, popup, R.drawable.ic_delete, home.getString(R.string.remove), -1,
                    () -> tiles.uninstall(tiles.positionOf(pkg)));
        }
        if (AppAdapter.appInfo(home, pkg) != null) {
            home.addInputRow(rows, popup, R.drawable.ic_settings, home.getString(R.string.app_info), -1,
                    () -> tiles.openAppInfo(tiles.positionOf(pkg)));
        }
        home.addSeparator(rows);
        home.addInputRow(rows, popup, R.drawable.ic_edit, home.getString(R.string.rename_folder), -1, () -> home.renameFolder(id));
        home.showMenu(popup, panel, rows, card, Gravity.START);
    }
}
