package io.idiotbox;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * The Music tab's player: a song chosen on the tab plays in Idiot Box's music engine (MusicEngine), in the background
 * with nothing shown. What comes after it is the engine's (its radio for it, with Prism), and next and previous are its
 * too (Music.next). Searches can be kept as rows.
 */
final class MusicPlayer {
    private static MusicPlayer instance;
    private final Context context;

    private MusicPlayer(Context c) {
        context = c.getApplicationContext();
    }

    static MusicPlayer get(Context c) {
        if (instance == null) instance = new MusicPlayer(c);
        return instance;
    }

    /** Plays [song]; what comes after it is the engine's choice. */
    void play(MusicEngine.Result song) {
        MusicEngine.play(context, song);
    }

    // MARK: - The rows: saved searches

    /** The saved searches, in order: none at first (a held OK on a search saves it). */
    List<String> rows() {
        List<String> out = new ArrayList<>();
        try {
            String saved = prefs().getString("rows", null);
            if (saved == null) return out;
            JSONArray a = new JSONArray(saved);
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Saves [query] as a row, or takes it away; true when it's a row now. */
    boolean toggleRow(String query) {
        List<String> rows = rows();
        boolean now = !rows.remove(query);
        if (now) rows.add(query);
        prefs().edit().putString("rows", new JSONArray(rows).toString()).apply();
        return now;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("music", Context.MODE_PRIVATE);
    }
}
