package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Free My TV's music engine (io.freemytv.music: SimpMusic's media service, YouTube Music's catalogue and player, with
 * no screens but sign-in), reached through Android's own media browser: its Home shelves, a search (browsing
 * "search/&lt;words&gt;"), and playing a song by its media id. It plays in the background with nothing shown. Answers
 * are kept for a while, so the Music tab's rows come back at once. Everything here runs on the main thread.
 */
final class MusicEngine {
    private MusicEngine() {}

    static final String PKG = "io.freemytv.music";
    private static final ComponentName SERVICE = new ComponentName(PKG, "com.maxrave.media3.service.SimpleMediaService");
    private static final long KEEP_MS = 30 * 60_000L, EMPTY_KEEP_MS = 60_000L;

    /**
     * One song, or one playlist of a shelf (YouTube Music's charts are playlists): as the engine describes it, and its
     * media id there (what plays a song; what lists a playlist's songs).
     */
    static final class Result {
        final String title, line, image, videoId, mediaId;
        final long duration;        // ms, or 0 when not known (the engine's lists don't say)
        final boolean playlist;

        Result(String title, String line, String image, String videoId, long duration, String mediaId) {
            this(title, line, image, videoId, duration, mediaId, false);
        }

        Result(String title, String line, String image, String videoId, long duration, String mediaId, boolean playlist) {
            this.title = title;
            this.line = line;
            this.image = image;
            this.videoId = videoId;
            this.duration = duration;
            this.mediaId = mediaId;
            this.playlist = playlist;
        }

        /** The title as a person writes it ("Fleet Foxes - Mykonos [OFFICIAL VIDEO]" → "Mykonos"). */
        String shownTitle() {
            return playlist ? title : Music.tidy(title, artist())[0];
        }

        String artist() {
            return playlist ? line : Music.tidy(title, line)[1];
        }

        /** The picture for a card: YouTube's wide one for the video (no black bars), else the engine's. */
        String picture() {
            return videoId != null ? "https://i.ytimg.com/vi/" + videoId + "/hq720.jpg" : image;
        }
    }

    /** A Home shelf of YouTube Music ("Quick picks"): its title, and the id to browse for its songs. */
    static final class Shelf {
        final String id, title;

        Shelf(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    interface Ready {
        void ready();
    }

    /** Is the engine on the TV? */
    static boolean available(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // MARK: - The connection

    private static MediaBrowser browser;
    private static boolean connecting;
    private static final List<Runnable> waiting = new ArrayList<>();

    /** Runs [then] once the engine answers (connecting first, or again after the engine restarted). */
    private static void withBrowser(Context c, Runnable then) {
        if (browser != null && browser.isConnected()) {
            then.run();
            return;
        }
        waiting.add(then);
        if (connecting) return;
        connecting = true;
        MediaBrowser[] self = new MediaBrowser[1];
        self[0] = new MediaBrowser(c.getApplicationContext(), SERVICE, new MediaBrowser.ConnectionCallback() {
            @Override public void onConnected() {
                connecting = false;
                browser = self[0];
                List<Runnable> now = new ArrayList<>(waiting);
                waiting.clear();
                for (Runnable r : now) r.run();
            }

            @Override public void onConnectionFailed() {
                connecting = false;
                browser = null;
                waiting.clear();
                AppLog.w("music: the engine didn't answer");
            }

            @Override public void onConnectionSuspended() {
                browser = null;   // the engine stopped: the next call connects again
            }
        }, hints());
        self[0].connect();
    }

    /** The home shelves follow Free My TV's region (the engine takes it from here). */
    private static Bundle hints() {
        Bundle b = new Bundle();
        b.putString("region", Recs.REGION);
        return b;
    }

    /** The engine's list under [id], or null when it couldn't answer. */
    private static void browse(Context c, String id, Consumer<List<MediaBrowser.MediaItem>> done) {
        withBrowser(c, () -> browser.subscribe(id, new MediaBrowser.SubscriptionCallback() {
            @Override public void onChildrenLoaded(String parentId, List<MediaBrowser.MediaItem> children) {
                if (browser != null) browser.unsubscribe(parentId);
                done.accept(children);
            }

            @Override public void onError(String parentId) {
                if (browser != null) browser.unsubscribe(parentId);
                done.accept(null);
            }
        }));
    }

    // MARK: - Home shelves

    private static List<Shelf> shelves = new ArrayList<>();
    private static long shelvesAt;
    private static boolean askingShelves;
    /** Asked again (refresh): a shelf's songs wait for the new shelves, which the engine has then asked YouTube for. */
    private static boolean refreshing;

    /** Are the Home shelves being asked for (the first time, or again)? */
    static boolean shelvesLoading() {
        return askingShelves || refreshing;
    }

    /** Everything asked again: the shelves first, then each row as it's shown. */
    static void refresh() {
        answers.clear();
        answeredAt.clear();
        shelvesAt = 0;
        refreshing = true;
        AppLog.w("music: refreshing from the engine");
    }

    /** YouTube Music's Home shelves as known now (empty at first); asked for when stale, [ready] when they come. */
    static List<Shelf> shelves(Context c, Ready ready) {
        if (!askingShelves && (shelvesAt == 0 || SystemClock.elapsedRealtime() - shelvesAt > KEEP_MS) && available(c)) {
            askingShelves = true;
            browse(c, "home", items -> {
                askingShelves = false;
                boolean was = refreshing;
                refreshing = false;
                if (items == null) {
                    if (was) ready.ready();   // the rows wait no more
                    return;
                }
                List<Shelf> found = new ArrayList<>();
                for (MediaBrowser.MediaItem m : items) {
                    CharSequence t = m.getDescription().getTitle();
                    if (m.isBrowsable() && t != null) found.add(new Shelf(m.getMediaId(), t.toString()));
                }
                shelves = found;
                shelvesAt = SystemClock.elapsedRealtime();
                StringBuilder titles = new StringBuilder();
                for (Shelf shelf : found) titles.append(titles.length() == 0 ? "" : ", ").append(shelf.title);
                AppLog.w("music: " + found.size() + " home shelves from the engine: " + titles);
                ready.ready();
            });
        }
        return shelves;
    }

    // MARK: - Songs: a shelf or a search

    private static final Map<String, List<Result>> answers = new HashMap<>();
    private static final Map<String, Long> answeredAt = new HashMap<>();

    static String searchId(String query) {
        return "search/" + query;
    }

    /** The songs under [id] (a shelf, or searchId) if known and fresh, else null (asked for, and [ready] called). */
    static List<Result> get(Context c, String id, Ready ready) {
        if (refreshing && id.startsWith("home/")) return null;   // after the new shelves (shelves calls ready)
        Long at = answeredAt.get(id);
        List<Result> known = answers.get(id);
        if (known != null && at != null && SystemClock.elapsedRealtime() - at < KEEP_MS) return known;
        if (answeredAt.containsKey(id) && known == null) return null;   // being asked
        answeredAt.put(id, SystemClock.elapsedRealtime());
        browse(c, id, items -> {
            if (items == null) {
                answeredAt.remove(id);   // ask again next time
                answers.remove(id);
                ready.ready();
                return;
            }
            List<Result> found = new ArrayList<>();
            for (MediaBrowser.MediaItem m : items) {
                CharSequence t = m.getDescription().getTitle(), s = m.getDescription().getSubtitle();
                String mediaId = m.getMediaId();
                if (t == null || mediaId == null) continue;
                if (m.isBrowsable()) {   // a shelf's playlist: played from its first song
                    if (!mediaId.contains("/playlist/")) continue;
                    android.net.Uri icon = m.getDescription().getIconUri();
                    found.add(new Result(t.toString(), s == null ? "" : s.toString(), icon == null ? "" : icon.toString(), null, 0, mediaId, true));
                    continue;
                }
                if (!m.isPlayable()) continue;
                String videoId = mediaId.substring(mediaId.lastIndexOf('/') + 1);
                android.net.Uri icon = m.getDescription().getIconUri();
                found.add(new Result(t.toString(), s == null ? "" : s.toString(), icon == null ? "" : icon.toString(),
                        videoId.matches("[A-Za-z0-9_-]{11}") ? videoId : null, 0, mediaId));
            }
            answers.put(id, found);
            if (found.isEmpty()) {
                // Asked again in a minute, not half an hour; an empty shelf may be gone from Home: its list too.
                answeredAt.put(id, SystemClock.elapsedRealtime() - KEEP_MS + EMPTY_KEEP_MS);
                if (id.startsWith("home/")) shelvesAt = 0;
            }
            AppLog.w("music: " + id + ": " + found.size() + " songs");
            ready.ready();
        });
        return known;
    }

    // MARK: - Playing

    /**
     * Plays [r] in the engine, in the background: a song, then YouTube Music's radio for it; a playlist, from its first
     * song (its songs come from the engine first).
     */
    static void play(Context c, Result r) {
        if (!r.playlist) {
            playId(c, r.mediaId, r.title);
            return;
        }
        AppLog.w("music: \u201c" + r.title + "\u201d: asking the engine for its songs");
        browse(c, r.mediaId, items -> {
            if (items == null) {
                AppLog.w("music: the engine couldn't list \u201c" + r.title + "\u201d");
                return;
            }
            for (MediaBrowser.MediaItem m : items) {
                if (m.isPlayable() && m.getMediaId() != null) {
                    playId(c, m.getMediaId(), r.title);
                    return;
                }
            }
            AppLog.w("music: “" + r.title + "” has no songs");
        });
    }

    private static void playId(Context c, String mediaId, String title) {
        withBrowser(c, () -> {
            new MediaController(c.getApplicationContext(), browser.getSessionToken()).getTransportControls()
                    .playFromMediaId(mediaId, null);
            AppLog.w("music: “" + title + "” to the engine");
        });
    }
}
