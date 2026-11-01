package com.maydings.vibelauncher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * An open folder, over Home, as on tvOS: Home dimmed behind, the folder's name large at the top, and its
 * apps in rows of the same tiles. Made each time a folder opens and dropped when it closes (MainActivity).
 */
@SuppressLint("ViewConstructor")
final class FolderView extends FrameLayout {

    final String folderId;
    final AppAdapter adapter;
    final RecyclerView grid;
    final TextView title;
    private final int columns;
    /** The tile focus was on before going up to the name: Down goes back to it. */
    private int lastTile;

    /**
     * titlePressed: OK on the folder's name, which is focusable as on tvOS (Up from the first row); the name
     * shows in a pill of the highlight color while it has focus.
     */
    FolderView(Context context, String folderId, String name, int columns, int gridWidth, int highlight,
               AppAdapter.TileListener listener, Runnable titlePressed) {
        super(context);
        this.folderId = folderId;
        this.columns = columns;
        float density = getResources().getDisplayMetrics().density;
        setClickable(true);   // nothing reaches Home underneath
        setBackgroundColor(0xEB060608);   // Home shows through only faintly: the folder is what you look at

        title = new TextView(context);
        title.setText(name);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        title.setTypeface(UiFont.get(context, R.string.font_regular));
        title.setLetterSpacing(-0.01f);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(Math.round(28 * density), Math.round(6 * density), Math.round(28 * density),
                Math.round(8 * density));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(100 * density);
        pill.setColor(highlight);
        pill.setAlpha(0);
        title.setBackground(pill);
        title.setFocusable(true);
        title.setOnFocusChangeListener((v, focused) -> {
            pill.setAlpha(focused ? 255 : 0);
            title.setTextColor(focused ? Highlight.on(highlight) : 0xFFFFFFFF);
            float zoom = focused ? 1.06f : 1f;
            title.animate().scaleX(zoom).scaleY(zoom).setDuration(180).start();
        });
        title.setOnClickListener(v -> titlePressed.run());
        title.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focus(lastTile);
                return true;
            }
            // Nothing above, and nothing beside: the name keeps focus.
            return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        LayoutParams titleLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        titleLp.topMargin = Math.round(90 * density);
        addView(title, titleLp);

        grid = new RecyclerView(context);
        int side = Math.round(14 * density);   // as Home's grid, so the tiles come out the same size
        grid.setPadding(side, Math.round(10 * density), side, Math.round(40 * density));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(new TileAnimator());
        GridLayoutManager layout = new GridLayoutManager(context, columns * 2);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return 2;
            }
        });
        grid.setLayoutManager(layout);
        adapter = new AppAdapter(context, listener, columns, folderId);
        adapter.reload();
        grid.setAdapter(adapter);
        LayoutParams gridLp = new LayoutParams(gridWidth, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL);
        gridLp.topMargin = Math.round(170 * density);
        addView(grid, gridLp);
    }

    void setTitle(String name) {
        title.setText(name);
    }

    /** Fades in with the tiles rising a little, then focuses the first app. */
    void open() {
        setAlpha(0f);
        grid.setTranslationY(24 * getResources().getDisplayMetrics().density);
        animate().alpha(1f).setDuration(220).start();
        grid.animate().translationY(0f).setDuration(320).setInterpolator(new DecelerateInterpolator(1.8f)).start();
        focus(0);
    }

    void close(Runnable gone) {
        animate().alpha(0f).setDuration(160).withEndAction(gone).start();
    }

    /** Focuses the tile at position (kept in range), once it's laid out. */
    void focus(int position) {
        if (adapter.getItemCount() == 0) return;
        int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        grid.scrollToPosition(pos);
        grid.post(() -> {
            if (!focusTile(pos)) {   // not laid out yet (just opened): once it is
                grid.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                        if (focusTile(pos)) grid.removeOnLayoutChangeListener(this);
                    }
                });
            }
        });
    }

    private boolean focusTile(int pos) {
        RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
        return h != null && h.itemView.findViewById(R.id.card).requestFocus();
    }

    /** Up and Down between the folder's rows: up from the first to the name, and the last row stays put. */
    boolean verticalKey(int position, boolean up) {
        if (up && position < columns) {
            lastTile = position;
            title.requestFocus();
            return true;
        }
        int target = up ? position - columns : adapter.below(position);
        if (target != position) focus(target);
        return true;
    }
}
