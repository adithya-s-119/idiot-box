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

    // MARK: - Widgets

    /** This build: the weather always on, for the city in config.properties (Noida); no setting for it. */
    static boolean weather() { return true; }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Top buttons
}
