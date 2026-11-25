package com.maydings.vibelauncher;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiles that change place (Move, or closing the gap after Remove) glide there on the same spring as
 * the glass highlight, so rearranging feels like navigating. Fades for removals and additions come
 * from {@link DefaultItemAnimator}.
 */
class TileAnimator extends DefaultItemAnimator {

    /** Shared with the glass highlight so tiles and highlight move as one. */
    static final float STIFFNESS = 260f;
    static final float DAMPING = 0.58f;

    private final List<RecyclerView.ViewHolder> pendingMoves = new ArrayList<>();
    private final Map<RecyclerView.ViewHolder, SpringAnimation[]> running = new HashMap<>();

    TileAnimator() {
        setSupportsChangeAnimations(false);   // payload rebinds (dock labels, move outline) shouldn't fade
        setRemoveDuration(160);
        setAddDuration(220);
    }

    @Override
    public boolean animateMove(RecyclerView.ViewHolder holder, int fromX, int fromY, int toX, int toY) {
        View v = holder.itemView;
        // Continue from wherever an interrupted spring left the tile.
        fromX += (int) v.getTranslationX();
        fromY += (int) v.getTranslationY();
        if (pendingMoves.remove(holder)) dispatchMoveFinished(holder);
        cancel(holder);
        super.endAnimation(holder);
        int dx = toX - fromX, dy = toY - fromY;
        if (dx == 0 && dy == 0) {
            // Laid out where it's drawn now (sent back before its spring was through): that's its place, and
            // what the spring left it shifted by goes, or it would stay drawn that far off it.
            v.setTranslationX(0f);
            v.setTranslationY(0f);
            dispatchMoveFinished(holder);
            return false;
        }
        v.setTranslationX(-dx);
        v.setTranslationY(-dy);
        pendingMoves.add(holder);
        return true;
    }

    @Override
    public void runPendingAnimations() {
        super.runPendingAnimations();
        if (pendingMoves.isEmpty()) return;
        List<RecyclerView.ViewHolder> moves = new ArrayList<>(pendingMoves);
        pendingMoves.clear();
        for (RecyclerView.ViewHolder holder : moves) {
            View v = holder.itemView;
            dispatchMoveStarting(holder);
            SpringAnimation x = spring(v, DynamicAnimation.TRANSLATION_X);
            SpringAnimation y = spring(v, DynamicAnimation.TRANSLATION_Y);
            SpringAnimation[] pair = {x, y};
            running.put(holder, pair);
            DynamicAnimation.OnAnimationEndListener end = (anim, canceled, value, velocity) -> {
                if (running.get(holder) != pair || x.isRunning() || y.isRunning()) return;
                running.remove(holder);
                dispatchMoveFinished(holder);
                if (!isRunning()) dispatchAnimationsFinished();
            };
            x.addEndListener(end);
            y.addEndListener(end);
            x.animateToFinalPosition(0f);
            y.animateToFinalPosition(0f);
        }
    }

    private static SpringAnimation spring(View v, DynamicAnimation.ViewProperty property) {
        SpringAnimation s = new SpringAnimation(v, property);
        s.setSpring(new SpringForce(0f).setStiffness(STIFFNESS).setDampingRatio(DAMPING));
        return s;
    }

    /** Stops a tile's springs where they are (the caller decides what happens next). */
    private void cancel(RecyclerView.ViewHolder holder) {
        SpringAnimation[] pair = running.remove(holder);
        if (pair != null) {
            for (SpringAnimation s : pair) s.cancel();
            dispatchMoveFinished(holder);
        }
    }

    @Override
    public void endAnimation(@NonNull RecyclerView.ViewHolder holder) {
        if (pendingMoves.remove(holder)) dispatchMoveFinished(holder);
        cancel(holder);
        holder.itemView.setTranslationX(0f);
        holder.itemView.setTranslationY(0f);
        super.endAnimation(holder);
    }

    @Override
    public void endAnimations() {
        for (RecyclerView.ViewHolder holder : new ArrayList<>(pendingMoves)) endAnimation(holder);
        for (RecyclerView.ViewHolder holder : new ArrayList<>(running.keySet())) endAnimation(holder);
        super.endAnimations();
    }

    @Override
    public boolean isRunning() {
        return !pendingMoves.isEmpty() || !running.isEmpty() || super.isRunning();
    }
}
