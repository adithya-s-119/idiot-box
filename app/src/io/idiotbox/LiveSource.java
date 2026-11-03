package io.idiotbox;

import android.util.JsonWriter;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where Live TV's channels come from: the M3U playlists and XMLTV guides set on the phone page (Prefs), else iptv-org's
 * playlist of Indian channels and iptv-epg.org's guide for India, fetched by the TV and made into live.json (what Live
 * reads). From the built-in playlist it keeps the channels marked 720p or better; from one of the person's, every
 * channel. http ones too (Just Player plays them; images still go over https, which Android needs here), one per
 * channel (the highest resolution), matched to the guides by id or else by name, with the programmes from two hours
 * ago to HOURS ahead.
 */
final class LiveSource {
    private LiveSource() {}

    /** Raised when what's kept changes (2: http streams too). */
    static final int VERSION = 2;
    static final String PLAYLIST = "https://iptv-org.github.io/iptv/countries/in.m3u";
    /** The same file elsewhere (GitHub Pages' branch through jsDelivr's CDN, and GitHub itself), for when it doesn't answer. */
    private static final String[] PLAYLIST_MIRRORS = {
        "https://cdn.jsdelivr.net/gh/iptv-org/iptv@gh-pages/countries/in.m3u",
        "https://raw.githubusercontent.com/iptv-org/iptv/gh-pages/countries/in.m3u",
    };
    /** Each download is tried this many times, a few seconds apart: some home connections drop one now and then. */
    private static final int TRIES = 3;
    private static final int CONNECT_MS = 15_000, READ_MS = 30_000;
    static final String GUIDE = "https://iptv-epg.org/files/epg-in.xml";
    private static final int MIN_HEIGHT = 720;
    private static final long HOURS = 26;
    /**
     * Unchanged sources aren't fetched again (the guide is tens of MB), but the file is built again after this long
     * anyway: it only holds HOURS of programmes from when it was built.
     */
    private static final long REBUILD_MS = 12 * 3600_000L;

    private static final Pattern ATTR = Pattern.compile("([a-z-]+)=\"([^\"]*)\"");
    private static final Pattern HEIGHT = Pattern.compile("\\((\\d{3,4})p\\)");
    private static final Pattern PREFIX = Pattern.compile("^\\s*in\\s*[-:|]\\s*"), BRACKETS = Pattern.compile("\\(.*?\\)|\\[.*?\\]"),
            QUALITY = Pattern.compile("\\b(hd|sd|fhd|uhd|4k|india|tv)\\b"), OTHER = Pattern.compile("[^a-z0-9]"),
            SPACES = Pattern.compile("\\s+");

    private static final class Entry {
        String name, group, logo, url, id, key;
        int height;
        final List<Live.Show> shows = new ArrayList<>();
    }

    /** The playlists and guides as one string: live.json is made again when it changes (Live). */
    static String sources() {
        return Ui.join("\n", Prefs.playlists()) + "\n\n" + Ui.join("\n", Prefs.guides());
    }

    /**
     * Fetches them all and writes `out` (through a temporary file, so a reader never sees half of it). False when no
     * source changed since `out` was built: then `out` is only marked as fresh.
     */
    static boolean fetch(File out) throws Exception {
        File remembered = new File(out.getPath() + ".sources");
        if (unchanged(out, read(remembered))) {
            //noinspection ResultOfMethodCallIgnored
            out.setLastModified(System.currentTimeMillis());
            AppLog.w("live: the playlists and guides haven't changed");
            return false;
        }
        Properties answers = new Properties();
        Map<String, Entry> best = new LinkedHashMap<>();
        Exception failed = null;
        for (String url : Prefs.playlists()) {
            boolean builtIn = url.equals(PLAYLIST);
            List<String> copies = new ArrayList<>();
            copies.add(url);
            if (builtIn) copies.addAll(Arrays.asList(PLAYLIST_MIRRORS));
            for (String copy : copies) {
                try {
                    playlist(copy, builtIn, best, copy.equals(url) ? answers : null);
                    failed = null;
                    break;
                } catch (Exception e) {
                    failed = e;
                    AppLog.w("live: no playlist " + copy + " (" + e.getMessage() + ")");   // the others still count
                }
            }
        }
        List<Entry> channels = new ArrayList<>(best.values());
        if (channels.isEmpty()) {
            if (failed != null) throw failed;
            throw new IllegalStateException("the playlists have no channels");
        }
        for (String url : Prefs.guides()) {
            try {
                guide(url, channels, answers);
            } catch (Exception e) {
                AppLog.w("live: no guide " + url + " (" + e.getMessage() + ")");   // the channels still play
            }
        }
        int guided = 0;
        File tmp = new File(out.getPath() + ".tmp");
        try (JsonWriter w = new JsonWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)))) {
            w.beginObject().name("channels").beginArray();
            for (Entry ch : channels) {
                ch.shows.sort((a, b) -> Long.compare(a.start, b.start));
                if (!ch.shows.isEmpty()) guided++;
                w.beginObject().name("n").value(ch.name).name("g").value(ch.group).name("l").value(ch.logo).name("u").value(ch.url);
                w.name("p").beginArray();
                for (Live.Show s : ch.shows) w.beginArray().value(s.start).value(s.stop).value(s.title).value(s.image).endArray();
                w.endArray().endObject();
            }
            w.endArray().endObject();
        }
        if (!tmp.renameTo(out)) throw new IllegalStateException("couldn't save " + out.getName());
        answers.setProperty("sources", sources());
        answers.setProperty("built", String.valueOf(System.currentTimeMillis()));
        try (FileOutputStream o = new FileOutputStream(remembered)) {
            answers.store(o, null);
        }
        AppLog.w("live: fetched " + channels.size() + " channels (" + guided + " with the guide)");
        return true;
    }

    private static Properties read(File f) {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (Exception ignored) {
            // never fetched (or by an older build): everything is fetched
        }
        return p;
    }

    /** `out` was built from the same sources not long ago, and each of them answers that it hasn't changed since. */
    private static boolean unchanged(File out, Properties before) {
        long age;
        try {
            age = System.currentTimeMillis() - Long.parseLong(before.getProperty("built", "0"));
        } catch (NumberFormatException e) {
            return false;
        }
        if (!out.exists() || !sources().equals(before.getProperty("sources")) || age < 0 || age >= REBUILD_MS) return false;
        List<String> all = new ArrayList<>(Prefs.playlists());
        all.addAll(Prefs.guides());
        for (String url : all) {
            String tag = before.getProperty("etag " + url), modified = before.getProperty("modified " + url);
            if (tag == null && modified == null) return false;
            try {
                HttpURLConnection c = Net.connect(url, CONNECT_MS, READ_MS, TRIES, "If-None-Match", tag, "If-Modified-Since", modified);
                try {
                    if (c.getResponseCode() != HttpURLConnection.HTTP_NOT_MODIFIED) return false;
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                return false;   // the full fetch says what went wrong
            }
        }
        return true;
    }

    /** What `url` answered, to ask next time whether it changed (into `answers`, when not null). */
    private static void remember(Properties answers, String url, HttpURLConnection c) {
        if (answers == null) return;
        String tag = c.getHeaderField("ETag"), modified = c.getHeaderField("Last-Modified");
        if (tag != null) answers.setProperty("etag " + url, tag);
        if (modified != null) answers.setProperty("modified " + url, modified);
    }

    /** The channels of the playlist at [url], into [best] (by name, the highest resolution kept). */
    private static void playlist(String url, boolean builtIn, Map<String, Entry> best, Properties answers) throws Exception {
        HttpURLConnection c = Net.open(url, CONNECT_MS, READ_MS, TRIES);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String info = null, line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF:")) {
                    info = line;
                } else if (!line.isEmpty() && !line.startsWith("#") && info != null) {
                    add(best, info, line, builtIn);
                    info = null;
                }
            }
            remember(answers, url, c);
        } finally {
            c.disconnect();
        }
    }

    /** One channel; from the built-in playlist only those marked MIN_HEIGHT or better. */
    private static void add(Map<String, Entry> best, String info, String url, boolean builtIn) {
        String name = info.substring(info.lastIndexOf(',') + 1).trim();
        Matcher h = HEIGHT.matcher(name);
        int height = h.find() ? Integer.parseInt(h.group(1)) : 0;
        if (builtIn && height < MIN_HEIGHT || !url.startsWith("https://") && !url.startsWith("http://")) return;
        String key = norm(name);
        if (key.isEmpty()) return;
        Map<String, String> attrs = new HashMap<>();
        Matcher a = ATTR.matcher(info.substring(0, info.lastIndexOf(',') + 1));
        while (a.find()) attrs.put(a.group(1), a.group(2));
        Entry old = best.get(key);
        if (old != null && old.height >= height) return;
        Entry e = new Entry();
        e.name = clean(name);
        String group = attrs.get("group-title");
        e.group = group == null || group.isEmpty() ? "General" : group.split(";")[0];
        e.logo = secure(attrs.getOrDefault("tvg-logo", ""));
        e.url = url;
        String id = attrs.getOrDefault("tvg-id", "");
        e.id = (id.contains("@") ? id.substring(0, id.indexOf('@')) : id).toLowerCase(Locale.ROOT);
        e.key = key;
        e.height = height;
        best.put(key, e);
    }

    /** Streams the guide at [url] (tens of MB unpacked), keeping only the programmes of channels in the playlists. */
    private static void guide(String url, List<Entry> channels, Properties answers) throws Exception {
        Map<String, List<Entry>> byId = new HashMap<>(), byName = new HashMap<>();
        for (Entry ch : channels) {
            if (!ch.id.isEmpty()) byId.computeIfAbsent(ch.id, x -> new ArrayList<>()).add(ch);
            byName.computeIfAbsent(ch.key, x -> new ArrayList<>()).add(ch);
        }
        // A channel an earlier guide already filled keeps that one's programmes (two guides would show each twice).
        Set<Entry> guided = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Entry ch : channels) if (!ch.shows.isEmpty()) guided.add(ch);
        long now = System.currentTimeMillis(), lo = now - 2 * 3600_000L, hi = now + HOURS * 3600_000L;
        SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
        Map<String, List<Entry>> matched = new HashMap<>();
        // Follows the guide's redirect (to a dated copy); gzip is asked for and unpacked by the connection itself.
        HttpURLConnection c = Net.open(url, CONNECT_MS, READ_MS, TRIES);
        try (InputStream in = c.getInputStream()) {
            XmlPullParser x = Xml.newPullParser();
            x.setInput(in, "UTF-8");
            String channelId = null, programme = null, field = null;
            long start = 0, stop = 0;
            String title = "", image = "";
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = x.getName();
                    if ("channel".equals(tag)) {
                        channelId = x.getAttributeValue(null, "id");
                        List<Entry> hit = channelId == null ? null : byId.get(channelId.toLowerCase(Locale.ROOT));
                        if (hit != null) matched.put(channelId, hit);
                    } else if ("programme".equals(tag)) {
                        programme = null;
                        List<Entry> to = matched.get(x.getAttributeValue(null, "channel"));
                        if (to == null) continue;
                        try {
                            start = stamp.parse(x.getAttributeValue(null, "start").trim()).getTime();
                            stop = stamp.parse(x.getAttributeValue(null, "stop").trim()).getTime();
                        } catch (ParseException | NullPointerException e) {
                            continue;
                        }
                        if (stop <= lo || start >= hi) continue;
                        programme = x.getAttributeValue(null, "channel");
                        title = image = "";
                    } else if ("icon".equals(tag) && programme != null) {
                        String src = x.getAttributeValue(null, "src");
                        if (src != null) image = secure(src);
                    } else if ("title".equals(tag) && programme != null || "display-name".equals(tag) && channelId != null) {
                        field = tag;
                    }
                } else if (ev == XmlPullParser.TEXT && field != null) {
                    String t = x.getText().trim();
                    if ("title".equals(field)) {
                        title = t;
                    } else if (!matched.containsKey(channelId)) {
                        List<Entry> hit = byName.get(norm(t));   // no id in common: by name
                        if (hit != null) matched.put(channelId, hit);
                    }
                    field = null;
                } else if (ev == XmlPullParser.END_TAG) {
                    String tag = x.getName();
                    field = null;
                    if ("channel".equals(tag)) {
                        channelId = null;
                    } else if ("programme".equals(tag) && programme != null) {
                        Live.Show show = new Live.Show();
                        show.start = start;
                        show.stop = stop;
                        show.title = title;
                        show.image = image;
                        for (Entry ch : matched.get(programme)) if (!guided.contains(ch)) ch.shows.add(show);
                        programme = null;
                    }
                }
            }
            remember(answers, url, c);
        } finally {
            c.disconnect();
        }
    }

    /** Images over https: Android blocks plain http, and the image hosts (Jio's, imgur) serve both. */
    private static String secure(String url) {
        return url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }

    /** 'IN - Aaj Tak HD (1080p) [Geo-blocked]' → 'aajtak': for matching playlist and guide names. */
    private static String norm(String name) {
        String n = PREFIX.matcher(name.toLowerCase(Locale.ROOT)).replaceFirst("");
        n = QUALITY.matcher(BRACKETS.matcher(n).replaceAll("")).replaceAll("");
        return OTHER.matcher(n).replaceAll("");
    }

    /** 'Aaj Tak HD (1080p) [Geo-blocked]' → 'Aaj Tak HD'. */
    private static String clean(String name) {
        return SPACES.matcher(BRACKETS.matcher(name).replaceAll("")).replaceAll(" ").trim();
    }
}
