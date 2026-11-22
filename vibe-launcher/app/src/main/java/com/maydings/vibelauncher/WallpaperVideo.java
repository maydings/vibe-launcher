package com.maydings.vibelauncher;

import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import java.io.File;

/**
 * A video wallpaper on Home (Wallpapers): muted, round and round, filling the screen (its edges cut off, as a
 * picture's are). On a TextureView, which fades and sits under the pictures like any view (a SurfaceView would
 * be a window of its own). Paused while Home isn't in front or a menu is over it, to spare the TV's processor.
 * A file that won't play hides it, and Home shows the app's glow instead (onFailed). In the slideshow it plays
 * once to its end and says so (setOnce, onEnded), so the next slide waits for it.
 */
final class WallpaperVideo implements TextureView.SurfaceTextureListener {

    interface Listener {
        /** The first frame is up: it can be faded in. */
        void onShowing();

        /** It couldn't be played. */
        void onFailed();

        /** Set to play once (setOnce), it reached its end and stands on its last frame: resume plays it again. */
        void onEnded();
    }

    private final TextureView view;
    private final Listener listener;
    private MediaPlayer player;
    private Surface surface;
    private File playing, wanted;
    private boolean paused;
    /** Something covers it whole (the flights' board): held still as when paused, whatever pause and resume say. */
    private boolean covered;
    private boolean once;   // to its end and no further (the slideshow's), instead of round and round
    /**
     * The player has the video loaded: only then may it be started or paused (before, Android calls it an error,
     * -38, and the video was taken for one that won't play). Until then pause and resume only say what's wanted.
     */
    private boolean prepared;

    WallpaperVideo(TextureView view, Listener listener) {
        this.view = view;
        this.listener = listener;
        view.setSurfaceTextureListener(this);
        view.setOpaque(false);
    }

    /** Plays this video (from the start, unless it's already the one playing). */
    void play(File file) {
        view.setVisibility(View.VISIBLE);
        if (file.equals(wanted) && player != null && !prepared) {   // still loading this one: it starts when ready
            paused = false;
            return;
        }
        wanted = file;
        if (file.equals(playing) && player != null) {
            resume();
            listener.onShowing();
            return;
        }
        paused = false;   // asked to play: a pause from before (off the dock) was for the one before
        start();
    }

    /**
     * Once to its end, which is then said (onEnded), or round and round again: for the one playing now and those
     * after it. One that had ended goes round again.
     */
    void setOnce(boolean once) {
        this.once = once;
        if (player == null || !prepared) return;   // not loaded yet: start() sets it
        try {
            player.setLooping(!once);
            if (!once && !paused && !covered && !player.isPlaying()) player.start();
        } catch (RuntimeException ignored) {
            // a player in no state for it: the next one is set as it starts
        }
    }

    /** Stops and lets the video go (another picture shows now). */
    void stop() {
        wanted = null;
        release();
        view.setVisibility(View.INVISIBLE);
    }

    boolean isShowing() {
        return wanted != null;
    }

    /** How long the video playing is, in milliseconds; 0 while it isn't known. */
    int duration() {
        try {
            return player != null && prepared ? Math.max(0, player.getDuration()) : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    /** Held still (Home behind something), and on again. */
    void pause() {
        paused = true;
        if (player != null && prepared && player.isPlaying()) player.pause();
    }

    void resume() {
        paused = false;
        if (!covered && player != null && prepared && !player.isPlaying()) player.start();
    }

    /**
     * Covered whole by something over it, or in sight again: nothing is decoded for a video nobody sees, and it
     * goes on from where it stood once it's uncovered (unless it's paused as well).
     */
    void setCovered(boolean covered) {
        if (this.covered == covered) return;
        this.covered = covered;
        if (player == null || !prepared) return;   // not loaded yet: it starts, or doesn't, as it's ready
        try {
            if (covered && player.isPlaying()) player.pause();
            else if (!covered && !paused && !player.isPlaying()) player.start();
        } catch (RuntimeException ignored) {
            // a player in no state for it: the next one starts by what's so then
        }
    }

    private void start() {
        release();
        if (wanted == null || surface == null) return;   // the surface isn't there yet: onSurfaceTextureAvailable
        File file = wanted;
        MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setDataSource(file.getPath());
            p.setSurface(surface);
            p.setLooping(!once);
            p.setOnCompletionListener(mp -> {
                if (player != mp) return;
                if (once) listener.onEnded();
                else if (!paused && !covered) mp.start();   // set to go round as it ended
            });
            p.setVolume(0f, 0f);
            p.setOnVideoSizeChangedListener((mp, w, h) -> fill(w, h));
            p.setOnPreparedListener(mp -> {
                if (player != mp) return;
                prepared = true;
                playing = file;
                mp.setLooping(!once);   // as asked for while it loaded
                fill(mp.getVideoWidth(), mp.getVideoHeight());
                if (!paused && !covered) mp.start();
            });
            p.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && player == mp) listener.onShowing();
                return false;
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (player == mp) {
                    stop();
                    listener.onFailed();
                }
                return true;
            });
            p.prepareAsync();
        } catch (java.io.IOException | RuntimeException e) {
            stop();
            listener.onFailed();
        }
    }

    private void release() {
        prepared = false;
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
            player = null;
        }
        playing = null;
    }

    /** Scaled to cover the view, centred: what doesn't fit is cut off at the sides or the top and bottom. */
    private void fill(int videoW, int videoH) {
        int w = view.getWidth(), h = view.getHeight();
        if (videoW <= 0 || videoH <= 0 || w == 0 || h == 0) return;
        float scale = Math.max(w / (float) videoW, h / (float) videoH);
        float sx = videoW * scale / w, sy = videoH * scale / h;   // the view stretches it to fill first
        Matrix m = new Matrix();
        m.setScale(sx, sy, w / 2f, h / 2f);
        view.setTransform(m);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        surface = new Surface(texture);
        if (wanted != null) start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        if (player != null) fill(player.getVideoWidth(), player.getVideoHeight());
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        release();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }
}
