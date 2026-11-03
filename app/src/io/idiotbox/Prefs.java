package io.idiotbox;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Idiot Box > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    /** The app's context in this process (null before App.onCreate). */
    static Context context() {
        return app;
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto" or a language code as picked ("pt", "fr", "zh-CN"…); Ui.applyLanguage finds its catalogue. */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v;
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    // MARK: - City, for the weather (Home Settings → Location; config.properties' until one is set there)

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setLocation(String city, String latitude, String longitude) {
        sp().edit().putString("city", city).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    // MARK: - TMDB key and Live TV's sources (Home Settings, from the phone page; config.properties' key until then)

    static String tmdbKey() { return sp().getString("tmdb_key", Config.TMDB_KEY).trim(); }
    static void setTmdbKey(String key) { put("tmdb_key", key.trim()); }

    /**
     * The home screen's tabs shown, in order: 0 Continue watching, 1 Explore, 2 Live TV, 3 Music (Settings > Tabs).
     * All four until changed; none when all were taken off.
     */
    static int[] tabs() {
        String v = sp().getString("tabs", null);
        if (v == null) return new int[] {0, 1, 2, 3};
        List<Integer> out = new ArrayList<>();
        for (String s : v.split(",")) {
            try {
                int t = Integer.parseInt(s.trim());
                if (t >= 0 && t <= 3 && !out.contains(t)) out.add(t);
            } catch (NumberFormatException ignored) {
            }
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    static void setTabs(List<Integer> tabs) {
        StringBuilder b = new StringBuilder();
        for (int t : tabs) b.append(b.length() == 0 ? "" : ",").append(t);
        put("tabs", b.toString());
    }


    /** Live TV's M3U playlists: the ones set on the phone page, else the built-in one. */
    static List<String> playlists() { return lines("playlists", LiveSource.PLAYLIST); }
    /** Live TV's XMLTV guides: the ones set on the phone page, else the built-in one. */
    static List<String> guides() { return lines("guides", LiveSource.GUIDE); }
    /** Were the playlists (or guides) set on the phone page, not the built-in ones? */
    static boolean ownPlaylists() { return !sp().getString("playlists", "").trim().isEmpty(); }
    static boolean ownGuides() { return !sp().getString("guides", "").trim().isEmpty(); }
    /** One link per line; an empty list goes back to the built-in one. */
    static void setPlaylists(List<String> urls) { put("playlists", String.join("\n", urls)); }
    static void setGuides(List<String> urls) { put("guides", String.join("\n", urls)); }

    private static List<String> lines(String key, String builtIn) {
        List<String> out = new ArrayList<>();
        for (String line : sp().getString(key, "").split("\n")) if (!line.trim().isEmpty()) out.add(line.trim());
        if (out.isEmpty()) out.add(builtIn);
        return out;
    }

    /** The home screen's soft click when moving and opening. */
    static boolean sounds() { return sp().getBoolean("sounds", true); }
    static void setSounds(boolean on) { put("sounds", on); }

    /** The home wallpaper's source: "off", "bing", "nasa" or "picsum" (see Wallpaper). */
    static String wallpaperSource() {
        String v = sp().getString("wallpaper_source", null);
        if (v != null) return v;
        return sp().getBoolean("daily_wallpaper", false) ? "bing" : "off";   // the old on/off setting
    }

    static void setWallpaperSource(String source) {
        put("wallpaper_source", source);
        sp().edit().remove("daily_wallpaper").apply();
    }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** The app the Music tab browses and searches (its package), or "" when none was picked (see MusicEngine.pkg). */
    static String musicProvider() { return sp().getString("music_provider", "").trim(); }
    static void setMusicProvider(String pkg) { put("music_provider", pkg); }

    static void setShelf(List<String> packages) { put("shelf", String.join(",", packages)); }
}
