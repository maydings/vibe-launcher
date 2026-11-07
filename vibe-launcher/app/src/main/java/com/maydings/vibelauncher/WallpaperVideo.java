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
 * A file that won't play hides it, and Home shows the app's glow instead (onFailed).
 */
final class WallpaperVideo implements TextureView.SurfaceTextureListener {

    interface Listener {
        /** The first frame is up: it can be faded in. */
        void onShowing();

        /** It couldn't be played. */
        void onFailed();
    }

    private final TextureView view;
    private final Listener listener;
    private MediaPlayer player;
    private Surface surface;
    private File playing, wanted;
    private boolean paused;
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
        start();
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

    /** Held still (Home behind something), and on again. */
    void pause() {
        paused = true;
        if (player != null && prepared && player.isPlaying()) player.pause();
    }

    void resume() {
        paused = false;
        if (player != null && prepared && !player.isPlaying()) player.start();
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
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setOnVideoSizeChangedListener((mp, w, h) -> fill(w, h));
            p.setOnPreparedListener(mp -> {
                if (player != mp) return;
                prepared = true;
                playing = file;
                fill(mp.getVideoWidth(), mp.getVideoHeight());
                if (!paused) mp.start();
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
