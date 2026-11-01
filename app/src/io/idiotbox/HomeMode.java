package io.idiotbox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Original ⇄ Idiot Box, on Fire TV. Fire OS protects its launcher and its adbd never answers the device itself, so the
 * switch only records the mode, which FireTvService follows: in Idiot Box mode it opens our home screen whenever
 * Amazon's comes to the front. Turning that service on and Amazon's extra apps off is done once from a computer
 * (tools/firetv.sh). The switches run as background jobs (ModeJob) in the main process; the settings screen (the
 * :home process) asks for them and follows the state file.
 */
final class HomeMode {
    private HomeMode() {}

    static final String PKG = "io.idiotbox";
    static final String FREE = "free", ORIGINAL = "original";

    /** One switch at a time (a button press and the watchdog can overlap). */
    private static final Object RUN = new Object();
    private static final Object FILE_LOCK = new Object();

    // MARK: - Requests (from any process)

    /** Asks the main process to switch (the settings screen calls this). */
    static void request(Context c, String mode) {
        ModeJob.schedule(c, ModeJob.APPLY, mode, 0);
    }

    /** Our home screen crashed 3 times in 5 minutes: give the home screen back to Amazon's (asked by :home). */
    static void requestRevertHome(Context c) {
        ModeJob.schedule(c, ModeJob.REVERT, null, 0);
    }

    // MARK: - Switches (main process, from ModeJob)

    static void applyNow(Context c, String mode) {
        synchronized (RUN) {
            boolean free = FREE.equals(mode);
            try {
                JSONObject s = state(c);
                s.put("mode", free ? FREE : ORIGINAL).put("since", System.currentTimeMillis()).put("homeReverted", false);
                save(c, s);
                if (free) Watchdog.clear(c);   // a fresh start: old crashes don't count against it
                notice(c, free ? Ui.tr("Idiot Box is on. On Fire TV, its service is turned on once from a computer (see the README).")
                        : Ui.tr("Original mode: Amazon's home screen is back."));
                done(c, null);
            } catch (JSONException e) {
                done(c, Ui.trf("Couldn't switch: {error}", "error", e.getMessage()));
            }
        }
    }

    /** Safety net: our home screen crashed 3 times in a row. FireTvService reads homeReverted and leaves Amazon's alone. */
    static void revertHomeNow(Context app) {
        synchronized (RUN) {
            JSONObject s = state(app);
            if (!FREE.equals(s.optString("mode")) || s.optBoolean("homeReverted")) return;
            try { s.put("homeReverted", true); } catch (JSONException ignored) {}
            save(app, s);
            notice(app, Ui.tr("Idiot Box's home screen crashed 3 times in a row; I switched back to the original one."));
        }
    }

    // MARK: - State (a file shared by the two processes; atomic writes)

    static JSONObject state(Context c) {
        synchronized (FILE_LOCK) {
            File f = new File(c.getFilesDir(), "home-state.json");
            if (!f.exists()) return defaults();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] data = new byte[(int) f.length()];
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
                return new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            } catch (IOException | JSONException e) {
                return defaults();
            }
        }
    }

    private static JSONObject defaults() {
        try {
            return new JSONObject().put("mode", ORIGINAL);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void save(Context c, JSONObject s) {
        synchronized (FILE_LOCK) {
            File dir = c.getFilesDir();
            File tmp = new File(dir, "home-state.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(s.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (IOException e) {
                AppLog.w("could not save the state: " + e.getMessage());
                return;
            }
            if (!tmp.renameTo(new File(dir, "home-state.json"))) AppLog.w("could not replace the state file");
        }
    }

    private static void done(Context c, String error) {
        JSONObject s = state(c);
        s.remove("busy");
        try { s.put("error", error == null ? "" : error); } catch (JSONException ignored) {}
        save(c, s);
        if (error != null) {
            AppLog.w(error);
            notice(c, error);
        }
    }

    /** A notice for the home screen (a bubble) and the settings screen. Keeps the last 20. */
    static void notice(Context c, String text) {
        AppLog.w("notice: " + text);
        JSONObject s = state(c);
        JSONArray old = s.optJSONArray("notices");
        JSONArray list = new JSONArray();
        try {
            list.put(new JSONObject().put("at", System.currentTimeMillis()).put("text", text));
            if (old != null) for (int i = 0; i < old.length() && list.length() < 20; i++) list.put(old.get(i));
            s.put("notices", list);
        } catch (JSONException ignored) {}
        save(c, s);
    }
}
