package io.freemytv;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where Live TV's channels come from: iptv-org's playlist of Indian channels and iptv-epg.org's guide for India, fetched
 * by the TV and made into live.json (what Live reads). Keeps the channels marked 720p or better, http ones too (Just
 * Player plays them; images still go over https, which Android needs here), one per channel (the highest resolution),
 * matched to the guide by id or else by name, with the programmes from two hours ago to HOURS ahead.
 */
final class LiveSource {
    private LiveSource() {}

    /** Raised when what's kept changes (2: http streams too). */
    static final int VERSION = 2;
    static final String PLAYLIST = "https://iptv-org.github.io/iptv/countries/in.m3u";
    static final String GUIDE = "https://iptv-epg.org/files/epg-in.xml";
    private static final int MIN_HEIGHT = 720;
    private static final long HOURS = 26;

    private static final Pattern ATTR = Pattern.compile("([a-z-]+)=\"([^\"]*)\"");
    private static final Pattern HEIGHT = Pattern.compile("\\((\\d{3,4})p\\)");

    private static final class Entry {
        String name, group, logo, url, id, key;
        int height;
        final List<Object[]> shows = new ArrayList<>();
    }

    /** Fetches both and writes `out` (through a temporary file, so a reader never sees half of it). */
    static void fetch(File out) throws Exception {
        List<Entry> channels = playlist();
        if (channels.isEmpty()) throw new IllegalStateException("the playlist has no channels");
        try {
            guide(channels);
        } catch (Exception e) {
            AppLog.w("live: no guide (" + e.getMessage() + ")");   // the channels still play
        }
        JSONArray list = new JSONArray();
        int guided = 0;
        for (Entry ch : channels) {
            JSONArray shows = new JSONArray();
            ch.shows.sort((a, b) -> Long.compare((long) a[0], (long) b[0]));
            for (Object[] s : ch.shows) shows.put(new JSONArray().put(s[0]).put(s[1]).put(s[2]).put(s[3]).put(s[4]));
            if (shows.length() > 0) guided++;
            list.put(new JSONObject().put("n", ch.name).put("g", ch.group).put("l", ch.logo).put("u", ch.url).put("p", shows));
        }
        File tmp = new File(out.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(new JSONObject().put("made", System.currentTimeMillis()).put("channels", list).toString()
                    .getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(out)) throw new IllegalStateException("couldn't save " + out.getName());
        AppLog.w("live: fetched " + channels.size() + " channels (" + guided + " with the guide)");
    }

    private static List<Entry> playlist() throws Exception {
        Map<String, Entry> best = new LinkedHashMap<>();
        HttpURLConnection c = open(PLAYLIST);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String info = null, line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF:")) {
                    info = line;
                } else if (!line.isEmpty() && !line.startsWith("#") && info != null) {
                    add(best, info, line);
                    info = null;
                }
            }
        } finally {
            c.disconnect();
        }
        return new ArrayList<>(best.values());
    }

    private static void add(Map<String, Entry> best, String info, String url) {
        String name = info.substring(info.lastIndexOf(',') + 1).trim();
        Matcher h = HEIGHT.matcher(name);
        int height = h.find() ? Integer.parseInt(h.group(1)) : 0;
        if (height < MIN_HEIGHT || !url.startsWith("https://") && !url.startsWith("http://")) return;
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

    /** Streams the guide (tens of MB unpacked), keeping only the programmes of channels in the playlist. */
    private static void guide(List<Entry> channels) throws Exception {
        Map<String, List<Entry>> byId = new HashMap<>(), byName = new HashMap<>();
        for (Entry ch : channels) {
            if (!ch.id.isEmpty()) byId.computeIfAbsent(ch.id, x -> new ArrayList<>()).add(ch);
            byName.computeIfAbsent(ch.key, x -> new ArrayList<>()).add(ch);
        }
        long now = System.currentTimeMillis(), lo = now - 2 * 3600_000L, hi = now + HOURS * 3600_000L;
        SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
        Map<String, List<Entry>> matched = new HashMap<>();
        HttpURLConnection c = open(GUIDE);
        try (InputStream in = c.getInputStream()) {
            XmlPullParser x = Xml.newPullParser();
            x.setInput(in, "UTF-8");
            String channelId = null, programme = null, field = null;
            long start = 0, stop = 0;
            String title = "", desc = "", image = "";
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
                        title = desc = image = "";
                    } else if ("icon".equals(tag) && programme != null) {
                        String src = x.getAttributeValue(null, "src");
                        if (src != null) image = secure(src);
                    } else if (("title".equals(tag) || "desc".equals(tag)) && programme != null
                            || "display-name".equals(tag) && channelId != null) {
                        field = tag;
                    }
                } else if (ev == XmlPullParser.TEXT && field != null) {
                    String t = x.getText().trim();
                    if ("title".equals(field)) title = t;
                    else if ("desc".equals(field)) desc = t.length() > 160 ? t.substring(0, 160) : t;
                    else if (!matched.containsKey(channelId)) {
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
                        Object[] show = {start, stop, title, desc, image};
                        for (Entry ch : matched.get(programme)) ch.shows.add(show);
                        programme = null;
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Follows the guide's redirect (to a dated copy); gzip is asked for and unpacked by the connection itself. */
    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IllegalStateException(url + " answered " + code);
        }
        return c;
    }

    /** Images over https: Android blocks plain http, and the image hosts (Jio's, imgur) serve both. */
    private static String secure(String url) {
        return url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }

    /** 'IN - Aaj Tak HD (1080p) [Geo-blocked]' → 'aajtak': for matching playlist and guide names. */
    static String norm(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceFirst("^\\s*in\\s*[-:|]\\s*", "");
        n = n.replaceAll("\\(.*?\\)|\\[.*?\\]", "").replaceAll("\\b(hd|sd|fhd|uhd|4k|india|tv)\\b", "");
        return n.replaceAll("[^a-z0-9]", "");
    }

    /** 'Aaj Tak HD (1080p) [Geo-blocked]' → 'Aaj Tak HD'. */
    private static String clean(String name) {
        return name.replaceAll("\\(.*?\\)|\\[.*?\\]", "").replaceAll("\\s+", " ").trim();
    }
}
