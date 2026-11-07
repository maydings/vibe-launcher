package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What's on TV now, for the HDHomeRun app's backdrop: the shows airing on the user's own tuner, from
 * SiliconDust's free guide (what their app uses; no key or account). The tuner is found through
 * SiliconDust's discovery, which lists the tuners on the same internet connection, and gives the
 * token the guide asks for. The guide's images are small (480x270), so Backdrops looks each title
 * up for a sharp backdrop first.
 */
final class HdHomeRun {

    /** A show in the guide: its title, the guide's image, its channel (number, name) and when it's on. */
    static final class Show {
        final String title, image, number, name;
        final long startS, endS;

        Show(String title, String image, String number, String name, long startS, long endS) {
            this.title = title;
            this.image = image;
            this.number = number;
            this.name = name;
            this.startS = startS;
            this.endS = endS;
        }
    }

    /** Android's link for a channel in the TV's channel list (TvContract.buildChannelUri). */
    static final String CHANNEL_LINK = "content://android.media.tv/channel/";
    /**
     * A picture's channel when the TV doesn't have it in its channel list: "hdhomerun:2.2". Only for the Play
     * pill's channel and time left; Play then opens the HDHomeRun app (MainActivity.openTitle).
     */
    static final String CHANNEL_MARK = "hdhomerun:";
    /** The HDHomeRun app's TV input: its channels in the TV's list once it's set up as a channel source. */
    private static final String INPUT = "com.silicondust.view/";

    /** Every channel's shows from the last guide, by channel number: what's on now, for the Play pill. */
    private volatile java.util.Map<String, List<Show>> schedule = Collections.emptyMap();
    /** The channel number each channel link is for (channelLinks). */
    private volatile java.util.Map<String, String> numberOfLink = Collections.emptyMap();

    private static final String DISCOVER = "https://api.hdhomerun.com/discover";
    private static final String GUIDE = "https://api.hdhomerun.com/api/guide.php?DeviceAuth=";
    private static final long GUIDE_MS = 30L * 60 * 1000;
    /** Guide fillers with nothing worth showing. */
    private static final Set<String> SKIPPED = new HashSet<>(Arrays.asList(
            "paid programming", "to be announced", "sign off", "off air"));

    private final SharedPreferences cache;
    /** The last guide as it came, kept for the Play pill's channel and time left after Home restarts (restore). */
    private final java.io.File savedGuide;
    private List<Show> shows = Collections.emptyList();
    private long showsTime;

    HdHomeRun(Context context) {
        cache = context.getSharedPreferences("hdhomerun", Context.MODE_PRIVATE);
        savedGuide = new java.io.File(context.getCacheDir(), "hdhomerun-guide.json");
    }

    /**
     * Home started again with the HDHomeRun pictures read back from disk: the guide and channel list they came
     * with are read back too, so the Play pill knows their channel and the time left. Worker thread.
     */
    void restore(android.content.ContentResolver resolver) {
        if (numberOfLink.isEmpty()) channelLinks(resolver);
        if (!schedule.isEmpty() || !savedGuide.exists()) return;
        try {
            byte[] bytes = new byte[(int) savedGuide.length()];
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(savedGuide))) {
                in.readFully(bytes);
            }
            java.util.Map<String, List<Show>> all = new java.util.HashMap<>();
            parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), System.currentTimeMillis() / 1000, all);
            synchronized (this) {
                if (schedule.isEmpty()) schedule = all;
            }
        } catch (IOException | JSONException | RuntimeException e) {
            // Unreadable: the next guide fills it.
        }
    }

    /** The shows on now across the tuner's channels; empty without a tuner or offline. Worker thread. */
    synchronized List<Show> onNow() {
        long now = System.currentTimeMillis();
        if (now - showsTime < GUIDE_MS && !shows.isEmpty()) return shows;
        try {
            String auth = deviceAuth();
            if (auth == null) return shows;
            String text = Http.getText(GUIDE + Uri.encode(auth), Collections.emptyMap());
            java.util.Map<String, List<Show>> all = new java.util.HashMap<>();
            List<Show> found = parse(text, now / 1000, all);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(savedGuide)) {
                out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                // Not kept: only the pill's channel after a restart is lost.
            }
            schedule = all;
            shows = found;
            showsTime = now;
        } catch (IOException | JSONException | RuntimeException e) {
            // Offline, or the tuner or guide is unreachable: the last list, if any.
        }
        return shows;
    }

    /**
     * Reads a guide: every channel's shows into all (by channel number), and returns the shows on now, one per
     * title, with a picture.
     */
    private static List<Show> parse(String text, long nowS, java.util.Map<String, List<Show>> all) throws JSONException {
        JSONArray channels = new JSONArray(text);
        List<Show> found = new ArrayList<>();
        Set<String> titles = new HashSet<>();
        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            String number = channel.optString("GuideNumber"), name = channel.optString("GuideName");
            JSONArray guide = channel.optJSONArray("Guide");
            List<Show> onChannel = new ArrayList<>();
            for (int j = 0; guide != null && j < guide.length(); j++) {
                JSONObject p = guide.getJSONObject(j);
                String title = p.optString("Title").trim(), image = p.optString("ImageURL");
                Show show = new Show(title, image, number, name, p.optLong("StartTime"), p.optLong("EndTime"));
                onChannel.add(show);
                // On now only: the picture's link is its channel, and Play tunes what's on it now.
                if (show.startS > nowS || show.endS <= nowS) continue;
                // One per title: the same show often runs on a station's sister channels too.
                if (title.isEmpty() || !image.startsWith("https://") || SKIPPED.contains(title.toLowerCase(Locale.ROOT))
                        || !titles.add(title)) continue;
                found.add(show);
            }
            if (!number.isEmpty()) all.put(number, onChannel);
        }
        return found;
    }

    /**
     * The TV's own links to the HDHomeRun channels, by channel number: in the TV's channel list only once the
     * HDHomeRun app is set up as a channel source on the TV (the TV's channel setup); empty until then. Opening
     * one tunes it in the TV's own player. Worker thread.
     */
    java.util.Map<String, String> channelLinks(android.content.ContentResolver resolver) {
        java.util.Map<String, String> byNumber = new java.util.HashMap<>(), numbers = new java.util.HashMap<>();
        String[] columns = {"_id", "input_id", "display_number"};
        try (android.database.Cursor c = resolver.query(Uri.parse(CHANNEL_LINK.substring(0, CHANNEL_LINK.length() - 1)),
                columns, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String input = c.getString(1), number = c.getString(2);
                if (input == null || !input.startsWith(INPUT) || number == null) continue;
                String link = CHANNEL_LINK + c.getLong(0);
                byNumber.put(number.trim(), link);
                numbers.put(link, number.trim());
            }
        } catch (RuntimeException e) {
            // No permission, or no TV provider: no channels to open.
        }
        numberOfLink = numbers;
        return byNumber;
    }

    /** "2.2 WCBSDT2" for a channel link, or null when it isn't one of the tuner's. */
    String channelLabel(String link) {
        String number = number(link);
        if (number == null) return null;
        List<Show> shows = schedule.get(number);
        String name = shows == null || shows.isEmpty() ? "" : shows.get(0).name;
        return name.isEmpty() ? number : number + " " + name;
    }

    /** The time left on what this channel link's channel is showing now, from the guide; 0 when not known. */
    long remainingMs(String link) {
        String number = number(link);
        List<Show> shows = number == null ? null : schedule.get(number);
        if (shows == null) return 0;
        long nowS = System.currentTimeMillis() / 1000;
        for (Show s : shows) if (s.startS <= nowS && nowS < s.endS) return (s.endS - nowS) * 1000;
        return 0;
    }

    /** The channel number a picture's link is for: a TV channel link, or the mark; null for anything else. */
    private String number(String link) {
        if (link == null) return null;
        if (link.startsWith(CHANNEL_MARK)) return link.substring(CHANNEL_MARK.length());
        return numberOfLink.get(link);
    }

    /** Whether this link is a channel's (a TV channel link, or the mark). */
    static boolean isChannel(String link) {
        return link != null && (link.startsWith(CHANNEL_LINK) || link.startsWith(CHANNEL_MARK));
    }

    /** The tuner's guide token (it changes, so asked for each time), or null without a tuner. */
    private String deviceAuth() {
        String ip = cache.getString("ip", "");
        String auth = ip.isEmpty() ? null : authFrom(ip);
        if (auth != null) return auth;
        try {
            // The tuner may have a new address: ask SiliconDust's discovery for the tuners here.
            JSONArray tuners = new JSONArray(Http.getText(DISCOVER, Collections.emptyMap()));
            for (int i = 0; i < tuners.length(); i++) {
                String found = tuners.getJSONObject(i).optString("LocalIP");
                if (found.isEmpty() || (auth = authFrom(found)) == null) continue;
                cache.edit().putString("ip", found).apply();
                return auth;
            }
        } catch (IOException | JSONException | RuntimeException e) {
            // Offline.
        }
        return null;
    }

    /** The token from the tuner at this home network address, or null when it isn't there. */
    private static String authFrom(String ip) {
        try {
            String auth = new JSONObject(Http.getLan(ip, "/discover.json")).optString("DeviceAuth");
            return auth.isEmpty() ? null : auth;
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }
}
