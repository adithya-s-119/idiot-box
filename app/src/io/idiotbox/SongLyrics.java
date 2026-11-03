package io.idiotbox;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A song's timed words from LRCLIB (lrclib.net: free, open, no key), for the music screensaver. Only timed ones count:
 * without times there's nothing to follow, and the screen shows the cover flow instead. Looked up once per song (the
 * answer, words or none, is kept on disk), by title, artist and length, else by a search that must match the length.
 */
final class SongLyrics {
    /** The lines and when each starts (ms), in order. */
    static final class Lines {
        final long[] at;
        final String[] text;

        Lines(long[] at, String[] text) {
            this.at = at;
            this.text = text;
        }

        /** The line sung at [ms], or −1 before the first. */
        int index(long ms) {
            int lo = 0, hi = at.length;   // the first line starting after ms
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (at[mid] <= ms) lo = mid + 1;
                else hi = mid;
            }
            return lo - 1;
        }
    }

    interface Ready {
        void ready(String key, Lines lines);
    }

    private static final ExecutorService io = Net.serial("lyrics");
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** The songs looked up in this process (null: being looked up), the last 50. */
    private static final Map<String, Lines> known = new LinkedHashMap<String, Lines>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Lines> eldest) {
            return size() > 50;
        }
    };
    private static final Lines NONE = new Lines(new long[0], new String[0]);
    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]");

    private SongLyrics() {}

    /** The words for [t], null if there are none (or not yet: [ready] is called once they're found). */
    static Lines get(Context c, Music.Track t, Ready ready) {
        String key = t.key();
        synchronized (known) {
            if (known.containsKey(key)) {
                Lines l = known.get(key);
                return l == NONE ? null : l;
            }
            known.put(key, null);
        }
        File dir = new File(c.getApplicationContext().getCacheDir(), "lyrics");
        io.execute(() -> {
            File f = new File(dir, MusicArt.cacheName(key) + ".lrc");
            String lrc = null;
            try {
                if (f.exists()) {
                    try (InputStream in = new FileInputStream(f)) {
                        lrc = Net.read(in);
                    }
                } else {
                    lrc = fetch(t);
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write((lrc == null ? "" : lrc).getBytes(StandardCharsets.UTF_8));
                    }
                    MusicArt.keepNewest(dir, 300);
                }
            } catch (Exception e) {
                AppLog.w("lyrics: " + e.getMessage());   // not kept on disk: asked again after a restart
            }
            Lines lines = lrc == null || lrc.isEmpty() ? null : parse(lrc);
            synchronized (known) { known.put(key, lines == null ? NONE : lines); }
            if (lines != null) {
                AppLog.w("lyrics: " + lines.at.length + " timed lines for “" + t.shownTitle + "”");
                main.post(() -> ready.ready(key, lines));
            }
        });
        return null;
    }

    /** The timed words as LRC text, or null when LRCLIB has none for this song. */
    private static String fetch(Music.Track t) throws Exception {
        long seconds = t.duration / 1000;
        String exact = "https://lrclib.net/api/get?track_name=" + enc(t.shownTitle) + "&artist_name=" + enc(t.shownArtist)
                + (seconds > 0 ? "&duration=" + seconds : "");
        String body = get(exact);
        if (body != null) {
            String s = new JSONObject(body).optString("syncedLyrics", "");
            if (!s.isEmpty() && !"null".equals(s)) return s;
        }
        body = get("https://lrclib.net/api/search?q=" + enc(t.shownTitle + " " + t.shownArtist));
        if (body == null) return null;
        JSONArray found = new JSONArray(body);
        for (int i = 0; i < found.length(); i++) {
            JSONObject o = found.getJSONObject(i);
            String s = o.optString("syncedLyrics", "");
            boolean sameLength = seconds <= 0 || Math.abs(o.optDouble("duration", 0) - seconds) <= 3;
            if (sameLength && !s.isEmpty() && !"null".equals(s)) return s;
        }
        return null;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    /** The body of a 200 answer, or null (404: LRCLIB doesn't know the song). */
    private static String get(String url) throws IOException {
        try {
            return Net.getString(url, 8000, 15000);
        } catch (Net.HttpError e) {
            return null;
        }
    }

    /** "[01:23.45] words" lines; empty lines (pauses in the singing) are kept, so the words dim between verses. */
    private static Lines parse(String lrc) {
        List<long[]> at = new ArrayList<>();
        List<String> text = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> stamps = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long frac = m.group(3) == null ? 0 : Long.parseLong((m.group(3) + "00").substring(0, 3));
                stamps.add(Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000 + frac);
                end = m.end();
            }
            String words = raw.substring(end).trim();
            for (long s : stamps) {
                at.add(new long[] {s, text.size()});
                text.add(words);
            }
        }
        if (at.isEmpty()) return null;
        at.sort((a, b) -> Long.compare(a[0], b[0]));
        long[] times = new long[at.size()];
        String[] lines = new String[at.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = at.get(i)[0];
            lines[i] = text.get((int) at.get(i)[1]);
        }
        return new Lines(times, lines);
    }
}
