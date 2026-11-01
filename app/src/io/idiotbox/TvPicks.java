package io.idiotbox;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks without a TMDB key: what the TV's own apps publish to Android TV's TV provider. "Watch Next" (continue
 * watching, newest first), then the apps' preview channels. On a Fire TV with CloudStream, that is CloudStream's
 * history and its channels; apps that publish nothing (TizenTube) add nothing. Reading other apps' rows needs
 * READ_EPG_DATA, which the TV asks for once (HomeActivity).
 */
final class TvPicks {
    private TvPicks() {}

    /**
     * READ_EPG_DATA opens the provider; on Fire OS only READ_TV_LISTINGS ("read all TV listings", defined by its TV
     * provider) shows other apps' rows: with EPG_DATA alone every query answers 0 rows.
     */
    static final String[] PERMISSIONS = {"com.android.providers.tv.permission.READ_EPG_DATA", "android.permission.READ_TV_LISTINGS"};
    private static final int MAX_WATCH_NEXT = 12, MAX_PREVIEW = 18;
    /** CloudStream's titles: "S4:E3 The Office" (the episode before the show's name). */
    private static final java.util.regex.Pattern EPISODE = java.util.regex.Pattern.compile("^S(\\d+)\\s*:\\s*E(\\d+)\\s+(.+)$");

    static boolean allowed(Context c) {
        for (String p : PERMISSIONS) if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    static List<Recs.Pick> load(Context c) {
        List<Recs.Pick> out = new ArrayList<>();
        if (!allowed(c)) return out;
        Set<String> seen = new HashSet<>();
        read(c, TvContract.WatchNextPrograms.CONTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC", MAX_WATCH_NEXT, true, seen, out);
        read(c, TvContract.PreviewPrograms.CONTENT_URI, null, MAX_PREVIEW, false, seen, out);
        return out;
    }

    private static void read(Context c, Uri uri, String order, int max, boolean watchNext, Set<String> seen,
                             List<Recs.Pick> out) {
        String[] columns = {
            TvContract.BaseTvColumns._ID, TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI, TvContract.WatchNextPrograms.COLUMN_TYPE,
            TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            // CloudStream puts the TMDB id here in its channels (and in internal_provider_id in Watch Next); Android 9+.
            Build.VERSION.SDK_INT >= 28 ? "content_id" : TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
        };
        PackageManager pm = c.getPackageManager();
        int added = 0, rows = 0;
        try (Cursor cur = c.getContentResolver().query(uri, columns, null, null, order)) {
            if (cur == null) {
                AppLog.w("TV provider " + uri.getLastPathSegment() + ": no answer");
                return;
            }
            rows = cur.getCount();
            while (cur.moveToNext() && added < max) {
                String pkg = cur.getString(1), title = cur.getString(2);
                String image = first(cur.getString(6), cur.getString(7));
                if (pkg == null || pkg.equals(HomeMode.PKG) || title == null || title.trim().isEmpty() || image == null) continue;
                if (HomeApps.launchIntent(pm, pkg) == null) continue;
                if (!seen.add(pkg + "|" + title.trim())) continue;
                Recs.Pick p = new Recs.Pick();
                p.id = (int) cur.getLong(0);
                p.kind = cur.isNull(9) || cur.getInt(9) != TvContract.WatchNextPrograms.TYPE_MOVIE ? "tv" : "movie";
                String season = cur.getString(4), episode = cur.getString(5);
                java.util.regex.Matcher m = EPISODE.matcher(title.trim());
                if (m.matches()) {
                    season = m.group(1);
                    episode = m.group(2);
                    title = m.group(3);
                }
                p.title = title.trim();
                tmdb(p, cur.getString(10));
                if (p.tmdb == 0) tmdb(p, cur.getString(14));
                long duration = cur.isNull(11) ? 0 : cur.getLong(11), position = cur.isNull(12) ? 0 : cur.getLong(12);
                if (duration > 0 && position > 0 && position < duration) {
                    p.progress = (float) position / duration;
                    p.left = duration - position;
                }
                p.meta = meta(p.title, season, episode, cur.getString(3), cur.getString(13), watchNext);
                p.backdrop = image;
                p.pkg = pkg;
                p.app = label(pm, pkg);
                p.link = cur.getString(8);
                p.detailed = true;
                p.resume = watchNext;
                out.add(p);
                added++;
            }
        } catch (RuntimeException e) {
            AppLog.w("TV provider: " + e.getMessage());
        }
        AppLog.w("TV provider " + uri.getLastPathSegment() + ": " + rows + " rows, " + added + " picks");
    }

    /** CloudStream's id column: {"id":2316,"type":"tv",...}, TMDB's number and kind. Anything else is left alone. */
    private static void tmdb(Recs.Pick p, String json) {
        if (json == null || !json.startsWith("{")) return;
        try {
            JSONObject o = new JSONObject(json);
            int id = o.optInt("id");
            String type = o.optString("type");
            if (id <= 0 || !(type.equals("tv") || type.equals("movie"))) return;
            p.tmdb = id;
            p.kind = type;
        } catch (org.json.JSONException ignored) {
        }
    }

    /**
     * "Continue watching · S1 E3 · Episode title" for Watch Next; for a channel's pick, the episode details or what the
     * app says about it ("StreamPlay - 8.6/10.0" reads "StreamPlay · 8.6").
     */
    private static String meta(String title, String season, String episode, String episodeTitle, String description,
                               boolean watchNext) {
        List<String> parts = new ArrayList<>();
        if (watchNext) parts.add(Ui.tr("Continue watching"));
        String lower = title.toLowerCase(java.util.Locale.ROOT);
        if (season != null && episode != null) parts.add(Ui.trf("S{season} E{episode}", "season", season, "episode", episode));
        if (episodeTitle != null && !episodeTitle.trim().isEmpty() && !EPISODE.matcher(episodeTitle.trim()).matches()
                && !lower.contains(episodeTitle.trim().toLowerCase(java.util.Locale.ROOT))) {
            parts.add(episodeTitle.trim());
        }
        if (!watchNext && parts.isEmpty() && description != null && !description.trim().isEmpty()) {
            parts.add(description.trim().replace(" - ", " · ").replaceAll("/10(\\.0)?$", ""));
        }
        return String.join(" · ", parts);
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static String first(String a, String b) {
        return a != null && !a.isEmpty() ? a : b != null && !b.isEmpty() ? b : null;
    }
}
