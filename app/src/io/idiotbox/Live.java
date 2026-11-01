package io.idiotbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live TV: the channels and their guide, from live.json in the app's own folder, which LiveSource fetches (Indian
 * channels) and makes again every REFRESH_MS. Read again when the file changes. Found by first letter (no
 * categories); the person's favourites are kept by stream address.
 * Logos and programme images are kept in storage too (`store`, the least recently used go past IMAGE_BYTES): the
 * favourites' are fetched as soon as the home screen opens, the others when their card is first drawn.
 */
final class Live {
    private Live() {}

    /** Named by LiveSource.VERSION: a build that keeps other channels fetches them again at once. */
    static final String FILE = "live-" + LiveSource.VERSION + ".json";
    private static final long REFRESH_MS = 6 * 3600_000L, RETRY_MS = 10 * 60_000L;
    private static final ExecutorService fetcher = Executors.newSingleThreadExecutor();
    private static boolean fetching, failed;
    private static long tried, prefetched;
    private static String triedFor = "";
    private static final long IMAGE_BYTES = 60L << 20, PREFETCH_MS = 10 * 60_000L;

    static final class Show {
        long start, stop;
        String title, desc, image;
    }

    static final class Channel {
        String name, group, logo, url;
        final List<Show> shows = new ArrayList<>();

        /** What's on at `t`, or null (no guide for this channel, or a gap in it). */
        Show now(long t) {
            for (Show s : shows) if (s.start <= t && t < s.stop) return s;
            return null;
        }

        /** What comes after what's on at `t`, or null. */
        Show next(long t) {
            for (Show s : shows) if (s.start >= t) return s;
            return null;
        }
    }

    private static List<Channel> channels = new ArrayList<>();
    private static long loadedFrom = -1;

    /** The channels, A to Z (read again when the file changed). */
    static synchronized List<Channel> channels(Context c) {
        File f = file(c);
        long modified = f.exists() ? f.lastModified() : 0;
        if (modified == loadedFrom) return channels;
        loadedFrom = modified;
        List<Channel> out = new ArrayList<>();
        if (modified != 0) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] bytes = new byte[(int) f.length()];
                int read = 0;
                while (read < bytes.length) {
                    int n = in.read(bytes, read, bytes.length - read);
                    if (n < 0) break;
                    read += n;
                }
                JSONArray list = new JSONObject(new String(bytes, 0, read, StandardCharsets.UTF_8)).getJSONArray("channels");
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.getJSONObject(i);
                    Channel ch = new Channel();
                    ch.name = o.optString("n");
                    ch.group = o.optString("g", "General");
                    ch.logo = o.optString("l");
                    ch.url = o.optString("u");
                    JSONArray p = o.optJSONArray("p");
                    for (int j = 0; p != null && j < p.length(); j++) {
                        JSONArray a = p.getJSONArray(j);
                        Show s = new Show();
                        s.start = a.getLong(0);
                        s.stop = a.getLong(1);
                        s.title = a.optString(2);
                        s.desc = a.optString(3);
                        s.image = a.optString(4);
                        ch.shows.add(s);
                    }
                    if (!ch.url.isEmpty()) out.add(ch);
                }
            } catch (Exception e) {
                AppLog.w("live: " + e.getMessage());
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        AppLog.w("live: " + out.size() + " channels");
        channels = out;
        return out;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /** What older builds fetched. */
    private static void dropOld(Context c) {
        File[] old = c.getFilesDir().listFiles((dir, name) -> name.startsWith("live") && name.endsWith(".json") && !name.equals(FILE));
        if (old != null) for (File f : old) f.delete();
    }

    /**
     * Fetches the channels again in the background when there are none yet or they're older than REFRESH_MS (after a
     * failure, not before RETRY_MS); `done` runs on the main thread when the new ones are there.
     */
    static synchronized void refresh(Context context, Runnable done) {
        Context c = context.getApplicationContext();
        File f = file(c);
        long now = System.currentTimeMillis();
        // Other playlists or guides set on the phone page: fetched again at once.
        String sources = LiveSource.sources();
        boolean changed = !sources.equals(c.getSharedPreferences("live", Context.MODE_PRIVATE).getString("sources", LiveSource.PLAYLIST + "\n\n" + LiveSource.GUIDE));
        boolean retrying = now - tried < RETRY_MS && sources.equals(triedFor);
        if (fetching || retrying || !changed && f.exists() && now - f.lastModified() < REFRESH_MS) {
            if (!fetching && f.exists() && now - prefetched >= PREFETCH_MS) {
                prefetched = now;
                fetcher.execute(() -> prefetchFavourites(c));
            }
            return;
        }
        fetching = true;
        tried = now;
        triedFor = sources;
        Handler main = new Handler(Looper.getMainLooper());
        fetcher.execute(() -> {
            boolean ok = false;
            dropOld(c);
            try {
                LiveSource.fetch(f);
                c.getSharedPreferences("live", Context.MODE_PRIVATE).edit().putString("sources", sources).apply();
                ok = true;
            } catch (Exception e) {
                AppLog.w("live: couldn't fetch the channels (" + e.getMessage() + ")");
            }
            synchronized (Live.class) {
                fetching = false;
                failed = !ok;
                if (ok) prefetched = System.currentTimeMillis();
            }
            if (ok) main.post(done);
            if (ok) prefetchFavourites(c);
        });
    }

    /** The favourites' logos and the images of what's on now and next, into storage (on the fetcher's thread). */
    private static void prefetchFavourites(Context c) {
        Set<String> fav = favourites(c);
        if (fav.isEmpty()) return;
        long now = System.currentTimeMillis();
        int n = 0;
        for (Channel ch : channels(c)) {
            if (!fav.contains(ch.url)) continue;
            n += prefetch(c, ch, now);
        }
        if (n > 0) AppLog.w("live: " + n + " favourite images fetched");
    }

    private static int prefetch(Context c, Channel ch, long now) {
        int n = 0;
        Show show = ch.now(now), next = ch.next(now);
        for (String url : new String[] {ch.logo, show == null ? null : show.image, next == null ? null : next.image}) {
            if (url == null || url.isEmpty()) continue;
            boolean had = imageFile(c, url).exists();
            if (store(c, url) != null && !had) n++;
        }
        return n;
    }

    /** Where an image (logo or programme) is kept, by its address. */
    static File imageFile(Context c, String url) {
        return new File(new File(c.getFilesDir(), "live-images"), Integer.toHexString(url.hashCode()) + "-" + url.length());
    }

    /**
     * The image in storage, fetched first if it isn't there (null when that fails). Called off the main thread. Using
     * one marks it as just used, so the ones looked at least recently go first.
     */
    static File store(Context c, String url) {
        if (url == null || !url.startsWith("https://")) return null;   // Android blocks plain http here
        File f = imageFile(c, url);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + "." + Thread.currentThread().getId() + ".part");
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(15000);
            try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            } finally {
                h.disconnect();
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save");
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return null;
        }
        prune(f.getParentFile());
        return f;
    }

    private static synchronized void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0, stale = System.currentTimeMillis() - 10 * 60_000L;
        for (File f : files) total += f.length();
        if (total <= IMAGE_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= IMAGE_BYTES * 9 / 10) break;
            if (f.getName().endsWith(".part") && f.lastModified() > stale) continue;   // still downloading
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** Fetching the channels right now (the Live TV tab says so while there are none). */
    static synchronized boolean fetching() {
        return fetching;
    }

    /** The last fetch failed (no network, the source down). */
    static synchronized boolean failed() {
        return failed;
    }

    /** The filter pills: All, Favourites, then the first letters the channels start with (# for digits and the rest). */
    static List<String> groups(List<Channel> list) {
        Set<String> letters = new TreeSet<>();
        for (Channel ch : list) letters.add(letter(ch));
        List<String> out = new ArrayList<>();
        out.add(ALL);
        out.add(FAVOURITES);
        if (letters.remove(OTHER)) out.add(OTHER);
        out.addAll(letters);
        return out;
    }

    static final String ALL = "All", FAVOURITES = "Favourites", OTHER = "#";

    /** "A" to "Z" by the name's first character, or "#". */
    static String letter(Channel ch) {
        char first = ch.name.isEmpty() ? '#' : Character.toUpperCase(ch.name.charAt(0));
        return first >= 'A' && first <= 'Z' ? String.valueOf(first) : OTHER;
    }

    /** The channels in a pill: everything, the favourites, or those starting with one letter. */
    static List<Channel> in(Context c, List<Channel> list, String group) {
        if (ALL.equals(group)) return list;
        List<Channel> out = new ArrayList<>();
        Set<String> fav = group.equals(FAVOURITES) ? favourites(c) : null;
        for (Channel ch : list) if (fav != null ? fav.contains(ch.url) : letter(ch).equals(group)) out.add(ch);
        return out;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("live", Context.MODE_PRIVATE);
    }

    static Set<String> favourites(Context c) {
        return new HashSet<>(prefs(c).getStringSet("favourites", Collections.emptySet()));
    }

    /** Adds or removes a favourite; true when it's a favourite now. */
    static boolean toggleFavourite(Context c, Channel ch) {
        Set<String> fav = favourites(c);
        boolean now = !fav.remove(ch.url);
        if (now) fav.add(ch.url);
        prefs(c).edit().putStringSet("favourites", fav).apply();
        Context app = c.getApplicationContext();
        if (now) fetcher.execute(() -> prefetch(app, ch, System.currentTimeMillis()));
        return now;
    }
}
