package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * What the TV's music apps are playing, as NowPlaying hears it from their media sessions (in the :home process, beside
 * the home screen and the music screensaver): the song, whether it plays and where it is, the song before it (for the
 * cover flow), and the controls. Everything here is touched on the main thread.
 */
final class Music {
    private Music() {}

    /** One song as an app describes it, and as the screen shows it (YouTube titles made tidy). */
    static final class Track {
        final String app, title, artist, mediaId, artUri;
        final long duration;
        final Bitmap art;
        /** As shown: "FLEET FOXES - Sun It Rises (Official Audio)" by DrGaldino → "Sun It Rises" by "Fleet Foxes". */
        final String shownTitle, shownArtist;

        Track(String app, MediaMetadata m) {
            this.app = app;
            title = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            artist = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            mediaId = text(m, MediaMetadata.METADATA_KEY_MEDIA_ID);
            artUri = text(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            duration = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            art = b;
            String[] shown = tidy(title, artist);
            shownTitle = shown[0];
            shownArtist = shown[1];
        }

        /** A song of the app's queue. */
        Track(String app, MediaDescription d) {
            this.app = app;
            title = d.getTitle() == null ? "" : d.getTitle().toString().trim();
            artist = d.getSubtitle() == null ? "" : d.getSubtitle().toString().trim();
            mediaId = d.getMediaId() == null ? "" : d.getMediaId();
            artUri = d.getIconUri() == null ? "" : d.getIconUri().toString();
            duration = 0;
            art = null;
            String[] shown = tidy(title, artist);
            shownTitle = shown[0];
            shownArtist = shown[1];
        }

        /** A song beside the playing one, as Free My TV's music engine sends it (session extras "queue_prev"/"queue_next"). */
        Track(String app, Bundle b) {
            this.app = app;
            title = b.getString("title", "");
            artist = b.getString("artist", "");
            mediaId = b.getString("mediaId", "");
            artUri = b.getString("artUri", "");
            duration = 0;
            art = null;
            String[] shown = tidy(title, artist);
            shownTitle = shown[0];
            shownArtist = shown[1];
        }

        /** The same art, text and length, another length. */
        private Track(Track t, long duration) {
            app = t.app;
            title = t.title;
            artist = t.artist;
            mediaId = t.mediaId;
            artUri = t.artUri;
            this.duration = duration;
            art = t.art;
            shownTitle = t.shownTitle;
            shownArtist = t.shownArtist;
        }

        /**
         * Which song it is: the app's media id (the engine's YouTube video) when it gives one, else title and artist.
         * The text of a song can change while it plays (fuller details once the song has loaded); the
         * song doesn't.
         */
        String key() {
            return mediaId.isEmpty() ? app + "|" + title + "|" + artist : app + "|" + mediaId;
        }

        /** The same song, as shown differently (other words, or the picture that came later). */
        boolean looksDifferent(Track t) {
            return !shownTitle.equals(t.shownTitle) || !shownArtist.equals(t.shownArtist) || !artUri.equals(t.artUri)
                    || art == null && t.art != null;
        }

        /** A YouTube video id (the engine's media id), or null. */
        String youtubeId() {
            return mediaId.matches("[A-Za-z0-9_-]{11}") ? mediaId : null;
        }
    }

    interface Listener {
        /** The song, the playing state or the position changed. */
        void musicChanged();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static MediaController controller;
    private static Track track;
    private static boolean playing;
    private static long position, positionAt;
    private static float speed = 1f;
    /** When it last stopped playing (uptime), or 0 while it plays. */
    private static long stoppedAt;
    /** The song heard before this one (the cover flow's left side when the app shares no queue). */
    private static final List<Track> before = new ArrayList<>();
    private static final int BEFORE = 1;   // the song before, nothing more
    /** Where the song is in the app's queue, and which way the last change went there (1 on, −1 back). */
    private static int queueAt = -1, queueMove = 1;
    /** The last song's length (ms), while the app may still report it for the new one; 0 once it can't. */
    private static long staleDuration;

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    static Track track() {
        return track;
    }

    static boolean playing() {
        return playing && track != null;
    }

    /** How long it hasn't played (ms), or 0 while it plays. */
    static long stoppedFor() {
        return playing() || stoppedAt == 0 ? 0 : SystemClock.uptimeMillis() - stoppedAt;
    }

    /** Where the song is now (ms), carried on from the app's last report. */
    static long position() {
        if (!playing) return position;
        long p = position + (long) ((SystemClock.elapsedRealtime() - positionAt) * speed);
        return track != null && track.duration > 0 ? Math.min(p, track.duration) : p;
    }

    static List<Track> before() {
        return before;
    }

    /** The app's name, for the status line. */
    static String appName(android.content.Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** From NowPlaying: the session to follow now (the one playing, else the last one heard), or null when none is left. */
    static void update(MediaController c) {
        controller = c;
        MediaMetadata m = c == null ? null : c.getMetadata();
        PlaybackState s = c == null ? null : c.getPlaybackState();
        Track t = m == null ? null : new Track(c.getPackageName(), m);
        if (t != null && t.title.isEmpty()) t = null;   // a song without a title yet: no change until the app says
        // A new song comes with the last one's length until the app's player has loaded it (a report or two): not known.
        if (t != null && track != null && !t.key().equals(track.key()) && track.duration > 0) staleDuration = track.duration;
        if (t != null && t.duration > 0 && t.duration == staleDuration) t = new Track(t, 0);
        else if (t != null && t.duration > 0) staleDuration = 0;   // its own length: the old one won't come again
        if (t != null && track != null && t.key().equals(track.key()) && t.duration <= 0 && track.duration > 0) {
            t = new Track(t, track.duration);   // the same song while the app reloads: its length is still known
        }
        if (t != null && (track == null || !t.key().equals(track.key()))) {
            int at = queueIndex();
            queueMove = at >= 0 && queueAt >= 0 && at < queueAt ? -1 : 1;
            queueAt = at;
            if (track != null) {
                before.add(0, track);
                while (before.size() > BEFORE) before.remove(before.size() - 1);
            }
            track = t;
            AppLog.w("music: \u201c" + t.shownTitle + "\u201d by " + t.shownArtist + " in " + t.app + ", " + t.duration / 1000 + " s");
        } else if (t != null && (t.duration != track.duration || track.looksDifferent(t))) {
            // The same song, now with its length (known once the player has loaded), its fuller details or
            // its picture: the song stays, only what's shown of it changes.
            if (t.duration != track.duration) AppLog.w("music: \u201c" + t.shownTitle + "\u201d is " + t.duration / 1000 + " s");
            track = t;
        }
        logNeighbours(c);
        boolean was = playing;
        playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        if (s != null) {
            position = Math.max(0, s.getPosition());
            positionAt = s.getLastPositionUpdateTime() > 0 ? s.getLastPositionUpdateTime() : SystemClock.elapsedRealtime();
            speed = s.getPlaybackSpeed() > 0 ? s.getPlaybackSpeed() : 1f;
        }
        if (was && !playing) stoppedAt = SystemClock.uptimeMillis();
        if (playing) stoppedAt = 0;
        for (Listener l : listeners) l.musicChanged();
    }

    static void playPause() {
        if (controller == null) return;
        if (playing) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    /** The playing app's own queue, as its session shares it, or null. */
    private static List<MediaSession.QueueItem> queue() {
        List<MediaSession.QueueItem> q = controller == null ? null : controller.getQueue();
        return q == null || q.isEmpty() ? null : q;
    }

    private static int queueIndex() {
        List<MediaSession.QueueItem> q = queue();
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        if (q == null || s == null) return -1;
        for (int i = 0; i < q.size(); i++) if (q.get(i).getQueueId() == s.getActiveQueueItemId()) return i;
        return -1;
    }

    /**
     * Free My TV's music engine keeps its queue to itself (SimpMusic hands its player one song at a time) and sends the
     * songs either side of the playing one as session extras instead, with the playing song's id.
     */
    private static Bundle neighbours() {
        Bundle e = controller == null ? null : controller.getExtras();
        return e != null && e.containsKey("queue_current") ? e : null;
    }

    private static String neighboursLogged = "";

    /** What the engine says is either side of which song, once per change (the screensaver's sides come from it). */
    private static void logNeighbours(MediaController c) {
        if (c == null || !MusicEngine.PKG.equals(c.getPackageName())) return;
        Bundle e = c.getExtras();
        Bundle prev = e == null ? null : e.getBundle("queue_prev"), next = e == null ? null : e.getBundle("queue_next");
        String line = e == null ? "no extras" : "at " + e.getString("queue_current") + " (playing "
                + (track == null ? null : track.mediaId) + "), before " + (prev == null ? "none" : "“" + prev.getString("title") + "”")
                + ", after " + (next == null ? "none" : "“" + next.getString("title") + "” " + next.getString("artUri"));
        if (line.equals(neighboursLogged)) return;
        neighboursLogged = line;
        AppLog.w("music: the engine's queue: " + line);
    }

    /** Is the app's queue at [t] (the song playing), not still at the one before it? */
    static boolean queueIsAt(Track t) {
        Bundle n = queue() == null ? neighbours() : null;
        if (n != null) return t != null && t.mediaId.equals(n.getString("queue_current"));
        List<MediaSession.QueueItem> q = queue();
        int at = queueIndex();
        if (q == null || at < 0 || t == null) return false;
        return new Track(controller.getPackageName(), q.get(at).getDescription()).key().equals(t.key());
    }

    /** Does the app share its queue (with the song in it)? */
    static boolean hasQueue() {
        return queueIndex() >= 0 || neighbours() != null;
    }

    /**
     * Up to [n] songs of the app's queue on one side of the playing one, the nearest first: [side] −1 before it, 1 after it.
     */
    static List<Track> queued(int side, int n) {
        List<Track> out = new ArrayList<>();
        List<MediaSession.QueueItem> q = queue();
        Bundle extras = q == null ? neighbours() : null;
        if (extras != null) {   // the engine: one song on each side
            Bundle b = extras.getBundle(side < 0 ? "queue_prev" : "queue_next");
            if (b != null && n > 0) out.add(new Track(controller.getPackageName(), b));
            return out;
        }
        int at = queueIndex();
        if (q == null || at < 0) return out;
        for (int i = at + side; i >= 0 && i < q.size() && out.size() < n; i += side) {
            out.add(new Track(controller.getPackageName(), q.get(i).getDescription()));
        }
        return out;
    }

    /** Which way the last song change went in the app's queue: 1 on to the next song, −1 back. */
    static int queueMove() {
        return queueMove;
    }

    static void next() {
        command(1);
        if (controller != null) controller.getTransportControls().skipToNext();
    }

    static void previous() {
        command(-1);
        if (controller != null) controller.getTransportControls().skipToPrevious();
    }

    /** The last next (1) or previous (−1) asked of the app, and when (uptime). */
    private static int commanded;
    private static long commandedAt;

    private static void command(int dir) {
        commanded = dir;
        commandedAt = SystemClock.uptimeMillis();
    }

    /** The way the last key asked to go, if it was a few seconds ago (the song it brings), else 0. */
    static int commandMove() {
        return SystemClock.uptimeMillis() - commandedAt < 8000 ? commanded : 0;
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString().trim();
        }
        return "";
    }

    // YouTube's dressing: "(Official Video)", "[4K]", "| Lyrics" and the like.
    private static final Pattern DRESSING = Pattern.compile(
            "\\s*[(\\[][^)\\]]*(official|video|audio|lyric|visuali[sz]er|\\bhd\\b|\\b4k\\b|\\bmv\\b|m/v|full song)[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BAR = Pattern.compile("\\s+[|｜].*$");

    /** Title and artist as a person would write them. */
    static String[] tidy(String title, String artist) {
        String a = artist.replaceAll("\\s*-\\s*Topic$", "").replaceAll("VEVO$", "").trim();
        String t = BAR.matcher(DRESSING.matcher(title).replaceAll("")).replaceAll("").trim();
        int dash = t.indexOf(" - ") >= 0 ? t.indexOf(" - ") : t.indexOf(" – ");
        if (dash > 0) {
            String left = t.substring(0, dash).trim(), right = t.substring(dash + 3).trim();
            if (!right.isEmpty()) {
                // "Artist - Song", uploaded by the artist or by someone else: the artist is the left part.
                a = left;
                t = right;
            }
        }
        return new String[] {t.isEmpty() ? title : t, shout(a)};
    }

    /** "FLEET FOXES" → "Fleet Foxes" (a name written in capitals, as uploads often are). */
    private static String shout(String s) {
        if (s.length() < 4 || !s.equals(s.toUpperCase(Locale.ROOT)) || s.equals(s.toLowerCase(Locale.ROOT))) return s;
        StringBuilder b = new StringBuilder();
        boolean start = true;
        for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) {
            b.append(start ? Character.toUpperCase(ch) : ch);
            start = !Character.isLetterOrDigit(ch) && ch != '\'';
        }
        return b.toString();
    }
}
