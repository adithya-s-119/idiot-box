package io.idiotbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;

/**
 * Live TV: the channels and their guide, from live.json in the app's own folder, which LiveSource fetches (Indian
 * channels) and makes again every REFRESH_MS; read in the background (at first, and after each fetch). Found by first
 * letter (no categories); the person's favourites are kept by stream address.
 * Logos and programme images are kept in storage too (`store`, the least recently used go past IMAGE_BYTES): the
 * favourites' are fetched as soon as the home screen opens, the others when their card is first drawn.
 */
final class Live {
    private Live() {}

    /** Named by LiveSource.VERSION: a build that keeps other channels fetches them again at once. */
    static final String FILE = "live-" + LiveSource.VERSION + ".json";
    private static final long REFRESH_MS = 6 * 3600_000L, RETRY_MS = 60_000L, RETRY_MAX_MS = 30 * 60_000L;
    /** Failed fetches are tried again by themselves this many times (about an hour); then at the next refresh. */
    private static final int RETRIES = 6;
    private static final ExecutorService fetcher = Net.serial("live");
    private static boolean fetching, failed, loading;
    private static long tried, prefetched, retryAfter = RETRY_MS;
    private static int failures;
    private static String triedFor = "";
    private static final long IMAGE_BYTES = 60L << 20, PREFETCH_MS = 10 * 60_000L;

    static final class Show {
        long start, stop;
        String title = "", image = "";
    }

    static final class Channel {
        String name = "", group = "General", logo = "", url = "";
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

    private static volatile List<Channel> channels = Collections.emptyList();
    private static volatile boolean loaded;

    /** The channels, A to Z, as last read from live.json (empty until the first read, in the background, is done). */
    static List<Channel> channels(Context c) {
        if (!loaded) load(c.getApplicationContext(), null);
        return channels;
    }

    /** The player, recreated in a fresh process, can't wait for the background read: it reads the file itself. */
    static List<Channel> channelsNow(Context c) {
        synchronized (Live.class) {
            if (!loaded && !loading) read(c.getApplicationContext());
        }
        return channels;
    }

    /** Reads live.json on the fetcher's thread (once); `done`, if any, runs on the main thread after. */
    private static synchronized void load(Context c, Runnable done) {
        if (loaded || loading) return;
        loading = true;
        Handler main = new Handler(Looper.getMainLooper());
        fetcher.execute(() -> {
            read(c);
            synchronized (Live.class) {
                loading = false;
            }
            if (done != null) main.post(done);
        });
    }

    private static void read(Context c) {
        File f = file(c);
        List<Channel> out = new ArrayList<>();
        if (f.exists()) {
            try (JsonReader r = new JsonReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(f)), StandardCharsets.UTF_8))) {
                r.beginObject();
                while (r.hasNext()) {
                    if (!r.nextName().equals("channels")) {
                        r.skipValue();
                        continue;
                    }
                    r.beginArray();
                    while (r.hasNext()) {
                        Channel ch = channel(r);
                        if (!ch.url.isEmpty()) out.add(ch);
                    }
                    r.endArray();
                }
                r.endObject();
            } catch (IOException | RuntimeException e) {
                AppLog.w("live: " + e.getMessage());
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        AppLog.w("live: " + out.size() + " channels");
        channels = Collections.unmodifiableList(out);
        loaded = true;
    }

    private static Channel channel(JsonReader r) throws IOException {
        Channel ch = new Channel();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "n": ch.name = string(r); break;
                case "g": ch.group = string(r); break;
                case "l": ch.logo = string(r); break;
                case "u": ch.url = string(r); break;
                case "p":
                    r.beginArray();
                    while (r.hasNext()) {
                        Show s = new Show();
                        r.beginArray();
                        s.start = r.nextLong();
                        s.stop = r.nextLong();
                        s.title = string(r);
                        while (r.hasNext()) s.image = string(r);   // older files have a description before it
                        r.endArray();
                        ch.shows.add(s);
                    }
                    r.endArray();
                    break;
                default: r.skipValue();
            }
        }
        r.endObject();
        return ch;
    }

    private static String string(JsonReader r) throws IOException {
        if (r.peek() != JsonToken.NULL) return r.nextString();
        r.nextNull();
        return "";
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /** What older builds fetched. */
    private static void dropOld(Context c) {
        File[] old = c.getFilesDir().listFiles((dir, name) -> name.startsWith("live")
                && (name.endsWith(".json") || name.endsWith(".json.sources")) && !name.startsWith(FILE));
        if (old != null) for (File f : old) f.delete();
    }

    /**
     * Fetches the channels again in the background when there are none yet or they're older than REFRESH_MS; `done`
     * runs on the main thread when the new ones are there (and after the first read of the saved ones). After a
     * failure it tries again by itself, 1, 2, 4… minutes later (RETRIES times), and not sooner when asked.
     */
    static void refresh(Context context, Runnable done) {
        refresh(context, done, false);
    }

    /** The refresh button: the channels and guide fetched again now, however fresh, unless they're being fetched. */
    static void refreshNow(Context context, Runnable done) {
        refresh(context, done, true);
    }

    private static synchronized void refresh(Context context, Runnable done, boolean force) {
        Context c = context.getApplicationContext();
        load(c, done);
        File f = file(c);
        long now = System.currentTimeMillis();
        // Other playlists or guides set on the phone page: fetched again at once.
        String sources = LiveSource.sources();
        boolean changed = !sources.equals(prefs(c).getString("sources", LiveSource.PLAYLIST + "\n\n" + LiveSource.GUIDE));
        boolean retrying = now - tried < retryAfter && sources.equals(triedFor);
        if (fetching || !force && (retrying || !changed && f.exists() && now - f.lastModified() < REFRESH_MS)) {
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
                if (LiveSource.fetch(f) || !loaded) read(c);
                prefs(c).edit().putString("sources", sources).apply();
                ok = true;
            } catch (Exception e) {
                AppLog.w("live: couldn't fetch the channels (" + e.getMessage() + ")");
            }
            long again;
            boolean retry;
            synchronized (Live.class) {
                fetching = false;
                failed = !ok;
                if (ok) prefetched = System.currentTimeMillis();
                failures = ok ? 0 : failures + 1;
                again = retryAfter = Math.min(RETRY_MAX_MS, RETRY_MS << Math.min(5, Math.max(0, failures - 1)));
                retry = !ok && failures <= RETRIES;
            }
            if (ok) main.post(done);
            else if (retry) main.postDelayed(() -> refresh(c, done), again);
            if (ok) prefetchFavourites(c);
        });
    }

    /** The favourites' logos and the images of what's on now and next, into storage (on the fetcher's thread). */
    private static void prefetchFavourites(Context c) {
        Set<String> fav = favourites(c);
        if (fav.isEmpty()) return;
        long now = System.currentTimeMillis();
        int n = 0;
        for (Channel ch : channels) {
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
        try {
            Net.download(url, f, 8000, 15000);
        } catch (IOException | RuntimeException e) {
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
