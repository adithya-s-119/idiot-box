package io.idiotbox;

import android.content.ComponentName;
import android.content.Context;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Music tab's engine: the music app picked as the provider (Prefs.musicProvider; with none picked, BitChord TV or
 * Prism when one is on the TV), reached through Android's own media browser (the one apps offer Android Auto). Its rows
 * are the folders at the top of what it shares (or, as Prism does, one grid with a heading over each section); a search
 * goes through media compat (see search); a song plays by its media id, in the background with nothing shown. Only
 * apps that share their music can be picked (findProviders): Spotify, for one, answers other apps with an empty list.
 * Answers are kept for a while, so the Music tab's rows come back at once. Everything here runs on the main thread.
 */
final class MusicEngine {
    private MusicEngine() {}

    /** Prism (a YouTube Music app) lays its Home out its own way: see shelves. */
    private static final String PRISM = "com.prism.music";
    /** Used when no provider was picked, the first one on the TV. */
    private static final String[] KNOWN = {"com.music.bitchord.tv", PRISM};
    /** Stands for the root of what the app shares, whatever it calls it. */
    private static final String ROOT = "\u0000root";
    /** The heading an app puts over each section, when what it shares is one grid (media3's group title). */
    private static final String GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT";
    private static final long KEEP_MS = 30 * 60_000L, EMPTY_KEEP_MS = 60_000L, SEARCH_WAIT_MS = 20_000L;
    /** How long "is the provider on the TV" is trusted: the home screen asks every frame, the package manager is a binder call. */
    private static final long AVAILABLE_MS = 30_000L;
    private static final Handler main = new Handler(Looper.getMainLooper());

    /**
     * One song, or one album, playlist, mix or artist of a shelf: as the engine describes it, and its media id there
     * (what plays a song; what lists the others' songs).
     */
    static final class Result {
        final String title, line, image, videoId, mediaId;
        final boolean playlist;

        Result(String title, String line, String image, String videoId, String mediaId, boolean playlist) {
            this.title = title;
            this.line = line;
            this.image = image;
            this.videoId = videoId;
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
            return videoId != null ? Music.youtubePicture(videoId, "hq720") : image;
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

    /** The provider's package: the one picked, else the first known one found on the TV, else "". */
    static String pkg() {
        String picked = Prefs.musicProvider();
        return picked.isEmpty() ? fallback : picked;
    }

    private static boolean available;
    private static long availableAt;
    /** The known provider used while none is picked (found by available). */
    private static String fallback = "";

    /** Is there a provider, still on the TV (with its media browser)? */
    static boolean available(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (availableAt == 0 || now - availableAt > AVAILABLE_MS) {
            availableAt = now;
            if (Prefs.musicProvider().isEmpty()) {
                fallback = "";
                for (String known : KNOWN) {
                    if (service(c, known) != null) {
                        fallback = known;
                        break;
                    }
                }
            }
            available = !pkg().isEmpty() && service(c, pkg()) != null;
        }
        return available;
    }

    /** The provider's name as the TV shows it ("" without one). */
    static String name(Context c) {
        String pkg = pkg();
        return pkg.isEmpty() ? "" : Music.appName(c, pkg);
    }

    /** An app's media browser (Prism has one per launcher icon it offers, one enabled: the one Android finds), or null. */
    private static ComponentName service(Context c, String pkg) {
        List<android.content.pm.ResolveInfo> found = c.getPackageManager().queryIntentServices(
                new android.content.Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0);
        return found.isEmpty() ? null : new ComponentName(pkg, found.get(0).serviceInfo.name);
    }

    /**
     * The apps on the TV that share their music, as {package, name}, to `done` (on the main thread): each one with a
     * media browser is asked for what it shares, and kept if it lists something (Spotify accepts, then lists nothing).
     */
    static void findProviders(Context context, Consumer<List<String[]>> done) {
        Context c = context.getApplicationContext();
        List<String> pkgs = new ArrayList<>();
        for (android.content.pm.ResolveInfo r : c.getPackageManager().queryIntentServices(
                new android.content.Intent("android.media.browse.MediaBrowserService"), 0)) {
            String pkg = r.serviceInfo.packageName;
            if (!pkg.equals(c.getPackageName()) && !pkgs.contains(pkg)) pkgs.add(pkg);
        }
        List<String[]> found = new ArrayList<>();
        int[] left = {pkgs.size()};
        if (pkgs.isEmpty()) {
            done.accept(found);
            return;
        }
        for (String pkg : pkgs) {
            boolean[] over = new boolean[1];
            MediaBrowser[] b = new MediaBrowser[1];
            Consumer<Boolean> answer = shares -> {
                if (over[0]) return;
                over[0] = true;
                if (b[0] != null && b[0].isConnected()) b[0].disconnect();
                if (shares) found.add(new String[] {pkg, Music.appName(c, pkg)});
                if (--left[0] == 0) {
                    java.util.Collections.sort(found, (x, y) -> x[1].compareToIgnoreCase(y[1]));
                    done.accept(found);
                }
            };
            b[0] = new MediaBrowser(c, service(c, pkg), new MediaBrowser.ConnectionCallback() {
                @Override public void onConnected() {
                    b[0].subscribe(b[0].getRoot(), new MediaBrowser.SubscriptionCallback() {
                        @Override public void onChildrenLoaded(String parent, List<MediaBrowser.MediaItem> children) {
                            answer.accept(!children.isEmpty());
                        }

                        @Override public void onError(String parent) {
                            answer.accept(false);
                        }
                    });
                }

                @Override public void onConnectionFailed() {
                    answer.accept(false);
                }
            }, hints());
            b[0].connect();
            main.postDelayed(() -> answer.accept(false), 8_000);   // an app that never answers isn't one
        }
    }

    /** Another provider was picked: what was asked of the last one goes. */
    static void providerChanged() {
        if (browser != null && browser.isConnected()) browser.disconnect();
        browser = null;
        compat = null;
        connecting = false;
        waiting.clear();
        shelves = new ArrayList<>();
        shelvesAt = 0;
        askingShelves = false;
        refreshing = false;
        tiles.clear();
        answers.clear();
        answeredAt.clear();
        availableAt = 0;
    }

    /** A picture's web address: Prism hands out its own content:// ones, each holding the address it stands for. */
    private static String webArt(String uri) {
        if (!uri.startsWith("content://" + PRISM + ".artwork/")) return uri.startsWith("http") ? uri : "";
        String encoded = android.net.Uri.parse(uri).getLastPathSegment();
        try {
            String url = new String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP), java.nio.charset.StandardCharsets.UTF_8);
            return url.startsWith("http") ? url : "";
        } catch (Exception e) {
            return "";
        }
    }

    // MARK: - The connection

    private static MediaBrowser browser;
    private static boolean connecting;
    private static final List<Runnable> waiting = new ArrayList<>();
    /** The engine's media compat messenger (what a search is sent to), and ours it answers on: once connected. */
    private static Messenger compat, replies;

    /** Runs [then] once the engine answers (connecting first, or again after the engine restarted). */
    private static void withBrowser(Context c, Runnable then) {
        if (browser != null && browser.isConnected()) {
            then.run();
            return;
        }
        waiting.add(then);
        if (connecting) return;
        ComponentName service = pkg().isEmpty() ? null : service(c, pkg());
        if (service == null) {
            AppLog.w("music: the engine has no media browser");
            unanswered();
            return;
        }
        connecting = true;
        MediaBrowser[] self = new MediaBrowser[1];
        self[0] = new MediaBrowser(c.getApplicationContext(), service, new MediaBrowser.ConnectionCallback() {
            @Override public void onConnected() {
                connecting = false;
                browser = self[0];
                registerCompat(c);
                List<Runnable> now = new ArrayList<>(waiting);
                waiting.clear();
                for (Runnable r : now) r.run();
            }

            @Override public void onConnectionFailed() {
                connecting = false;
                browser = null;
                AppLog.w("music: the engine didn't answer");
                unanswered();
            }

            @Override public void onConnectionSuspended() {
                browser = null;   // the engine stopped: the next call connects again
                compat = null;
            }
        }, hints());
        self[0].connect();
    }

    /** Nothing waiting will be answered (no engine to ask): what's being asked for is asked again next time. */
    private static void unanswered() {
        waiting.clear();
        askingShelves = false;
        answeredAt.keySet().removeIf(id -> !answers.containsKey(id));
    }

    /** Idiot Box speaks media compat, the engine is told: it answers with its messenger. */
    private static Bundle hints() {
        Bundle b = new Bundle();
        b.putInt("extra_client_version", 1);
        b.putInt("extra_calling_pid", Process.myPid());
        return b;
    }

    /** Idiot Box's messenger, registered with the engine's (as media compat's browser does once it's connected). */
    private static void registerCompat(Context c) {
        Bundle extras = browser.getExtras();
        IBinder to = extras == null ? null : extras.getBinder("extra_messenger");
        compat = to == null ? null : new Messenger(to);
        if (compat == null) {
            AppLog.w("music: the engine didn't say how to search it");
            return;
        }
        if (replies == null) replies = new Messenger(main);
        Bundle data = new Bundle();
        data.putString("data_package_name", c.getPackageName());
        data.putInt("data_calling_pid", Process.myPid());
        data.putBundle("data_root_hints", hints());
        sendCompat(6, data);   // CLIENT_MSG_REGISTER_CALLBACK_MESSENGER
    }

    private static boolean sendCompat(int what, Bundle data) {
        Message m = Message.obtain();
        m.what = what;
        m.arg1 = 1;   // CLIENT_VERSION_CURRENT
        m.setData(data);
        m.replyTo = replies;
        try {
            compat.send(m);
            return true;
        } catch (Exception e) {
            AppLog.w("music: the engine's messenger: " + e);
            compat = null;
            return false;
        }
    }

    /**
     * The engine's songs for [query], or null when it couldn't answer: media compat's search (which Android's own media
     * browser hasn't), its answer coming back through a ResultReceiver.
     */
    private static void search(Context c, String query, Consumer<List<MediaBrowser.MediaItem>> done) {
        withBrowser(c, () -> {
            boolean[] answered = new boolean[1];
            Consumer<List<MediaBrowser.MediaItem>> once = items -> {
                if (answered[0]) return;
                answered[0] = true;
                done.accept(items);
            };
            Bundle data = new Bundle();
            data.putString("data_search_query", query);
            data.putBundle("data_search_extras", new Bundle());
            data.putParcelable("data_result_receiver", new android.support.v4.os.ResultReceiver((code, result) ->
                    once.accept(code == 0 && result != null ? searchResults(result) : null)));
            if (compat == null || !sendCompat(8, data)) {   // CLIENT_MSG_SEARCH
                once.accept(null);
                return;
            }
            main.postDelayed(() -> {
                if (!answered[0]) AppLog.w("music: the engine didn't answer the search for “" + query + "”");
                once.accept(null);
            }, SEARCH_WAIT_MS);
        });
    }

    /** A search's answer: media compat's items (by either name the library has had), as Android's own. */
    private static List<MediaBrowser.MediaItem> searchResults(Bundle result) {
        result.setClassLoader(MusicEngine.class.getClassLoader());
        List<Parcelable> found = new ArrayList<>();
        try {
            Parcelable[] array = result.getParcelableArray("search_results");
            if (array != null) java.util.Collections.addAll(found, array);
            else {
                List<Parcelable> list = result.getParcelableArrayList("search_results");
                if (list != null) found.addAll(list);
            }
        } catch (Exception e) {
            AppLog.w("music: the engine's search answer: " + e);
            return null;
        }
        List<MediaBrowser.MediaItem> items = new ArrayList<>();
        for (Parcelable p : found) {
            if (p instanceof android.support.v4.media.MediaBrowserCompat.MediaItem) {
                items.add(((android.support.v4.media.MediaBrowserCompat.MediaItem) p).item);
            } else if (p instanceof androidx.media3.session.legacy.MediaBrowserCompat.MediaItem) {
                items.add(((androidx.media3.session.legacy.MediaBrowserCompat.MediaItem) p).item);
            }
        }
        return items;
    }

    /** The engine's list under [id] (searchId: a search's answer), or null when it couldn't answer. */
    private static void browse(Context c, String id, Consumer<List<MediaBrowser.MediaItem>> done) {
        if (id.startsWith("search/")) {
            search(c, id.substring("search/".length()), done);
            return;
        }
        withBrowser(c, () -> browser.subscribe(ROOT.equals(id) ? browser.getRoot() : id, new MediaBrowser.SubscriptionCallback() {
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

    /**
     * The provider's rows as known now (empty at first); asked for when stale, [ready] when they come. Prism: its Home's
     * sections (sections), then its Made for you mixes and Recently played. Any other app: the sections of its root when
     * that's one grid, else each folder at its root.
     */
    static List<Shelf> shelves(Context c, Ready ready) {
        if (!askingShelves && (shelvesAt == 0 || SystemClock.elapsedRealtime() - shelvesAt > KEEP_MS) && available(c)) {
            askingShelves = true;
            Consumer<List<Shelf>> found = list -> {
                askingShelves = false;
                boolean was = refreshing;
                refreshing = false;
                if (list == null) {
                    if (was) ready.ready();   // the rows wait no more
                    return;
                }
                shelves = list;
                shelvesAt = SystemClock.elapsedRealtime();
                StringBuilder titles = new StringBuilder();
                for (Shelf shelf : list) titles.append(titles.length() == 0 ? "" : ", ").append(shelf.title);
                AppLog.w("music: " + list.size() + " home shelves from the engine: " + titles);
                ready.ready();
            };
            if (!PRISM.equals(pkg())) {
                browse(c, ROOT, items -> {
                    if (items == null) {
                        found.accept(null);
                        return;
                    }
                    List<Shelf> list = sections(items);
                    if (list.isEmpty()) {
                        for (MediaBrowser.MediaItem m : items) {
                            CharSequence t = m.getDescription().getTitle();
                            if (m.isBrowsable() && m.getMediaId() != null && t != null) list.add(new Shelf(m.getMediaId(), t.toString()));
                        }
                    }
                    found.accept(list);
                });
                return shelves;
            }
            browse(c, "home", items -> {
                if (items == null) {
                    found.accept(null);
                    return;
                }
                List<Shelf> list = sections(items);
                browse(c, "root", top -> {
                    for (MediaBrowser.MediaItem m : top == null ? new ArrayList<MediaBrowser.MediaItem>() : top) {
                        CharSequence t = m.getDescription().getTitle();
                        if (t != null && ("foryou".equals(m.getMediaId()) || "recent".equals(m.getMediaId()))) list.add(new Shelf(m.getMediaId(), t.toString()));
                    }
                    found.accept(list);
                });
            });
        }
        return shelves;
    }

    /** Each of Home's grid sections' tiles (when Home is one grid), by the shelf id given the section. */
    private static final Map<String, List<MediaBrowser.MediaItem>> tiles = new HashMap<>();

    /**
     * Prism's Home sections: its folders (shelf:0, shelf:1…) or, when its Home is one grid of tiles (as it is unless
     * that's turned off), a shelf for each heading over them, the n-th being shelf:n (see get).
     */
    private static List<Shelf> sections(List<MediaBrowser.MediaItem> items) {
        tiles.clear();
        List<Shelf> found = new ArrayList<>();
        for (MediaBrowser.MediaItem m : items) {
            String id = m.getMediaId();
            CharSequence t = m.getDescription().getTitle();
            if (m.isBrowsable() && id != null && id.startsWith("shelf:") && t != null) {
                found.add(new Shelf(id, t.toString()));
                continue;
            }
            Bundle extras = m.getDescription().getExtras();
            String heading = extras == null ? null : extras.getString(GROUP_TITLE);
            if (heading == null) continue;
            Shelf last = found.isEmpty() ? null : found.get(found.size() - 1);
            if (last == null || !last.title.equals(heading)) {
                last = new Shelf("shelf:" + found.size(), heading);
                found.add(last);
                tiles.put(last.id, new ArrayList<>());
            }
            tiles.get(last.id).add(m);
        }
        return found;
    }

    /** Prism's "Shuffle play" and "Play as a DJ set" over a list: playable, but not songs. */
    private static boolean isListAction(String id) {
        return id.startsWith("shuffle:") || id.startsWith("dj:");
    }

    // MARK: - Songs: a shelf or a search

    private static final Map<String, List<Result>> answers = new HashMap<>();
    private static final Map<String, Long> answeredAt = new HashMap<>();

    static String searchId(String query) {
        return "search/" + query;
    }

    private static boolean isShelf(String id) {
        for (Shelf shelf : shelves) if (shelf.id.equals(id)) return true;
        return false;
    }

    /** The songs under [id] (a shelf, or searchId) if known and fresh, else null (asked for, and [ready] called). */
    static List<Result> get(Context c, String id, Ready ready) {
        if (refreshing && isShelf(id)) return null;   // after the new shelves (shelves calls ready)
        Long at = answeredAt.get(id);
        List<Result> known = answers.get(id);
        if (known != null && at != null && SystemClock.elapsedRealtime() - at < KEEP_MS) return known;
        if (answeredAt.containsKey(id) && known == null) return null;   // being asked
        answeredAt.put(id, SystemClock.elapsedRealtime());
        browse(c, id, answer -> {
            if (answer == null) {
                answeredAt.remove(id);   // ask again next time
                answers.remove(id);
                ready.ready();
                return;
            }
            List<MediaBrowser.MediaItem> items = answer;
            // Sections are counted from Home: one it showed no tiles of shifts the rest. Its heading's own tiles then.
            List<MediaBrowser.MediaItem> own = tiles.get(id);
            if (own != null && !own.isEmpty() && (items.isEmpty() || !own.get(0).getMediaId().equals(items.get(0).getMediaId()))) items = own;
            List<Result> found = new ArrayList<>();
            for (MediaBrowser.MediaItem m : items) {
                CharSequence t = m.getDescription().getTitle(), s = m.getDescription().getSubtitle();
                String mediaId = m.getMediaId();
                if (t == null || mediaId == null) continue;
                android.net.Uri icon = m.getDescription().getIconUri();
                String image = icon == null ? "" : webArt(icon.toString());
                if (m.isBrowsable()) {   // an album, playlist, mix or artist: played from its first song
                    found.add(new Result(t.toString(), s == null ? "" : s.toString(), image, null, mediaId, true));
                    continue;
                }
                if (!m.isPlayable() || isListAction(mediaId)) continue;
                String videoId = mediaId.substring(mediaId.lastIndexOf('/') + 1);
                found.add(new Result(t.toString(), s == null ? "" : s.toString(), image,
                        Music.isVideoId(videoId) ? videoId : null, mediaId, false));
            }
            answers.put(id, found);
            if (found.isEmpty()) {
                // Asked again in a minute, not half an hour; an empty shelf may be gone from Home: its list too.
                answeredAt.put(id, SystemClock.elapsedRealtime() - KEEP_MS + EMPTY_KEEP_MS);
                if (isShelf(id)) shelvesAt = 0;
            }
            AppLog.w("music: " + id + ": " + found.size() + " songs");
            ready.ready();
        });
        return known;
    }

    // MARK: - Playing

    /**
     * Plays [r] in the engine, in the background: a song (the engine chooses what comes after it); an album, playlist,
     * mix or artist from its first song (its songs come from the engine first), the rest of it after.
     */
    static void play(Context c, Result r) {
        if (!r.playlist) {
            playId(c, r.mediaId, r.title);
            return;
        }
        AppLog.w("music: “" + r.title + "”: asking the engine for its songs");
        browse(c, r.mediaId, items -> {
            if (items == null) {
                AppLog.w("music: the engine couldn't list “" + r.title + "”");
                return;
            }
            for (MediaBrowser.MediaItem m : items) {
                if (m.isPlayable() && m.getMediaId() != null && !isListAction(m.getMediaId())) {
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
