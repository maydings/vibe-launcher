package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apple's aerial videos, from the Apple TV's screensaver (Settings > Library > Apple TV aerials): Apple's own list
 * (entries.json, inside resources-16.tar on sylvan.apple.com), each video in four groups (Earth, Landscapes, Cities,
 * Underwater), at 1080p: HEVC where the device plays it, else H.264. Downloaded into the Library like any video, with
 * no size limit but the room the device keeps free (FREE_KEPT). The list is kept a week; each video's thumbnail (a
 * frame of it, read over the network) and size are kept once found. Worker threads, all of it.
 */
final class AppleAerials {

    private static final String LIST = "https://sylvan.apple.com/Aerials/resources-16.tar";
    private static final long LIST_KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    /** Room the device keeps after an aerial video: they run to several hundred MB each. */
    static final long FREE_KEPT = 1024L * 1024 * 1024;
    /** Apple's groups, in the order of the tabs. */
    static final String[] GROUPS = {"AerialCategorySpace", "AerialCategoryLandscapes", "AerialCategoryCities",
            "AerialCategoryUnderwater"};

    /** One video: Apple's id for it, its English name (Apple's), and the file this device downloads. */
    static final class Video {
        final String id, name, url;

        Video(String id, String name, String url) {
            this.id = id;
            this.name = name;
            this.url = url;
        }
    }

    private static final Map<String, Long> sizes = new ConcurrentHashMap<>();
    private static Boolean hevc;

    private AppleAerials() {
    }

    /** The videos of one group (an index of GROUPS), in Apple's order. */
    static List<Video> group(Context context, int group) throws IOException, JSONException {
        JSONObject list = new JSONObject(listText(context));
        String groupId = null;
        JSONArray cats = list.optJSONArray("categories");
        for (int i = 0; cats != null && i < cats.length(); i++) {
            JSONObject c = cats.getJSONObject(i);
            if (GROUPS[group].equals(c.optString("localizedNameKey"))) groupId = c.optString("id");
        }
        List<Video> out = new ArrayList<>();
        JSONArray assets = list.optJSONArray("assets");
        boolean useHevc = playsHevc();
        for (int i = 0; groupId != null && assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            JSONArray in = a.optJSONArray("categories");
            boolean member = false;
            for (int j = 0; in != null && j < in.length(); j++) member |= groupId.equals(in.optString(j));
            if (!member) continue;
            String url = a.optString(useHevc ? "url-1080-SDR" : "url-1080-H264");
            if (url.isEmpty()) url = a.optString("url-1080-H264");
            String name = a.optString("accessibilityLabel");
            if (url.isEmpty() || name.isEmpty()) continue;
            out.add(new Video(a.optString("id"), name, url));
        }
        return out;
    }

    /** The name of the video with this id, from the list kept here (asked of Apple when it isn't); null if unknown. */
    static String nameOf(Context context, String id) {
        try {
            JSONArray assets = new JSONObject(listText(context)).optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (id.equals(a.optString("id"))) return a.optString("accessibilityLabel");
            }
        } catch (IOException | JSONException | RuntimeException e) {
            // not known now
        }
        return null;
    }

    /** The thumbnail of the video with this id: the one kept, else read from Apple; null when neither. */
    static Bitmap thumbnail(Context context, String id) {
        try {
            JSONArray assets = new JSONObject(listText(context)).optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (!id.equals(a.optString("id"))) continue;
                String url = a.optString(playsHevc() ? "url-1080-SDR" : "url-1080-H264");
                if (url.isEmpty()) url = a.optString("url-1080-H264");
                return url.isEmpty() ? null : thumbnail(context, id, url);
            }
        } catch (IOException | JSONException | RuntimeException e) {
            // not known now
        }
        return null;
    }

    /** Whether this device decodes 1080p HEVC (most since 2016); else the H.264 copies, about 1.7 times the size. */
    static synchronized boolean playsHevc() {
        if (hevc == null) {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080);
            hevc = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null;
        }
        return hevc;
    }

    /** The kept copy has been read as JSON since this process started (a cut-short one is thrown away). */
    private static volatile boolean keptRead;

    /** Apple's list, from the copy kept here when it's under a week old, else from Apple (then kept). */
    private static synchronized String listText(Context context) throws IOException {
        File kept = new File(context.getCacheDir(), "apple-aerials.json");
        long now = System.currentTimeMillis();
        // As read before, while the kept copy is the same one and still does: it's asked for with every video.
        boolean does = now - kept.lastModified() < LIST_KEEP_MS || now < askAgainAt;
        if (heldText != null && kept.lastModified() == heldOf && does) return heldText;
        String old = keptText(kept);
        if (old != null && does) return hold(old, kept);
        try {
            String text = fromTar(Http.get(LIST, 16 * 1024 * 1024), "entries.json");
            new JSONObject(text);   // only a whole list is kept
            // Written beside it, then moved over it: never a half-written list where the next read finds it.
            File part = new File(kept.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(kept)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
            keptRead = true;
            return hold(text, kept);
        } catch (IOException | JSONException e) {
            // Apple didn't answer (or not with a list): an older copy beats none, and it's not asked again
            // with the next video but in ten minutes.
            askAgainAt = now + 10 * 60_000;
            if (old != null) return hold(old, kept);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /** The list as last read, the kept copy it's of (by its time), and when Apple may be asked again after not answering. */
    private static String heldText;
    private static long heldOf, askAgainAt;

    private static String hold(String text, File kept) {
        heldText = text;
        heldOf = kept.lastModified();
        return text;
    }

    /** The kept copy's text, or null when there's none or it isn't a whole list (then it's deleted). */
    private static String keptText(File kept) {
        if (kept.length() == 0) return null;
        try {
            String text = new String(Files.readAllBytes(kept.toPath()), StandardCharsets.UTF_8);
            if (!keptRead) {
                new JSONObject(text);
                keptRead = true;
            }
            return text;
        } catch (IOException | JSONException e) {
            //noinspection ResultOfMethodCallIgnored
            kept.delete();
            return null;
        }
    }

    /** A file from a tar archive (its 512-byte headers: the name, then the size in octal). */
    static String fromTar(byte[] tar, String name) throws IOException {
        int at = 0;
        while (at + 512 <= tar.length) {
            String entry = cString(tar, at, 100);
            if (entry.isEmpty()) break;   // the end's empty blocks
            String octal = cString(tar, at + 124, 12).trim();
            long size;
            try {
                size = octal.isEmpty() ? 0 : Long.parseLong(octal, 8);
            } catch (NumberFormatException e) {
                throw new IOException("not a tar archive", e);
            }
            int data = at + 512;
            if (size < 0 || size > tar.length - data) throw new IOException("bad tar size");
            if (entry.equals(name) || entry.endsWith("/" + name)) {
                return new String(tar, data, (int) size, StandardCharsets.UTF_8);
            }
            long next = data + ((size + 511L) / 512L) * 512L;
            if (next <= at || next > tar.length) break;
            at = (int) next;
        }
        throw new IOException(name + " not in the list");
    }

    private static String cString(byte[] b, int from, int max) {
        int end = from;
        while (end < from + max && end < b.length && b[end] != 0) end++;
        return new String(b, from, end - from, StandardCharsets.US_ASCII);
    }

    /** The video's size in bytes, as Apple says (asked once), or 0 when it doesn't answer. */
    static long size(String url) {
        Long known = sizes.get(url);
        if (known != null) return known;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                long n = c.getResponseCode() == 200 ? c.getContentLengthLong() : 0;
                if (n > 0) sizes.put(url, n);
                return Math.max(0, n);
            } finally {
                c.disconnect();
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** The size when it's been asked already, else 0 (for the line under the grid, without waiting). */
    static long knownSize(String url) {
        Long known = sizes.get(url);
        return known != null ? known : 0;
    }

    /**
     * A frame a few seconds in, cell-sized, kept in the cache folder: read over the network (Android's own player
     * asks Apple only for the parts it needs, the index at the end and one frame). Its size is asked for too.
     */
    static Bitmap thumbnail(Context context, String id, String url) {
        size(url);
        File dir = new File(context.getCacheDir(), "apple-aerials");
        String safe = id == null ? "" : id.replaceAll("[^A-Za-z0-9-]", "");
        if (safe.isEmpty()) safe = Integer.toHexString(url.hashCode());
        File kept = new File(dir, safe + ".jpg");
        if (kept.length() > 0) {
            try {
                Bitmap b = BitmapFactory.decodeFile(kept.getPath());
                if (b != null) return b;
            } catch (OutOfMemoryError ignored) {
                return null;
            }
        }
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(url, new java.util.HashMap<>());
            Bitmap frame = android.os.Build.VERSION.SDK_INT >= 27
                    ? r.getScaledFrameAtTime(4_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                    : r.getFrameAtTime(4_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            int w = 480, h = Math.max(1, frame.getHeight() * w / Math.max(1, frame.getWidth()));
            Bitmap small = frame.getWidth() <= w ? frame : Bitmap.createScaledBitmap(frame, w, h, true);
            if (small != frame) frame.recycle();
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File tmp = new File(dir, safe + "." + Thread.currentThread().getId() + ".part");
            boolean ok = false;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                ok = small.compress(Bitmap.CompressFormat.JPEG, 85, out);
            } catch (IOException ignored) {
                // shown anyway, made again next time
            }
            if (!ok || !tmp.renameTo(kept)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
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
}
