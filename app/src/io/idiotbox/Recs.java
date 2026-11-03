package io.idiotbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * The home screen's rows. Continue: what the TV's apps publish to Android TV's Watch Next (TvPicks), with TMDB's art
 * (wide backdrop and logo) when there is a TMDB key. Explore: this week's trending titles on TMDB, opened in CloudStream.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", or the phone page). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie", "tv", "live" or "music"
        String title, backdrop, meta = "", app, pkg;
        String link;            // opens the title in its app (the app's own intent, a CloudStream link, a stream), or null
        int tmdb;               // TvPicks: the app's TMDB number for the title (0: none), for the wide art
        String logo;            // the title's logo on TMDB ("/abc.png"), or null: the title in text
        float progress;         // Watch Next: how much has been watched (0 to 1)
        long left;              // and what's left, in ms
        boolean resume;         // TvPicks: from Watch Next (the Continue tab), not from a channel
        Live.Channel channel;   // Live TV: the channel this card is (not saved: made again from live.json)
        MusicEngine.Result song; // Music: the song this card is (made again from the engine's answer)
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    /** The backdrops: shown at most 1280 wide (Backdrop.BG_W), so TMDB's 1280 copy is enough. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/w1280";
    /** Title logos: fitted into 620×150 on screen. */
    private static final String LOGOS = "https://image.tmdb.org/t/p/w500";
    /** How many cards the home screen's row shows at once. */
    static final int ROW = 6;
    /** The home screen's two tabs. */
    static final int CONTINUE = 0, EXPLORE = 1;
    /**
     * Explore's pages of trending titles (20 each), fetched as the row is scrolled. Rows have no other limit: only the
     * cards near the focus get their images, and the caches drop the least recently used.
     */
    private static final int EXPLORE_PAGES = 50;
    /** Explore's titles open in CloudStream, through its StreamPlay source (which takes TMDB numbers). */
    private static final String CLOUDSTREAM = "com.lagradost.cloudstream3", STREAMPLAY = "StreamPlay";
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    /** Explore is fetched again after this long. */
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** The apps' list (Watch Next changes as you watch) is read again after this long. */
    private static final long TV_EVERY = 15 * 60_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 12;  // 4: TvPicks (Fire TV edition); 6: TMDB art for TvPicks; 12: Continue and Explore
    /** The streaming apps that config.properties' hide_services can name (key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "com.netflix.ninja"},
        {"prime", "com.amazon.firebat"},
        {"prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "com.disney.disneyplus"},
        {"hulu", "com.hulu.livingroomplus"},
        {"paramount", "com.cbs.ott"},
        {"peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "com.globo.globotv"},
        {"max", "com.wbd.stream"},
        {"max", "com.hbo.hbonow"},
        {"appletv", "com.apple.atve.amazon.appletv"},
        {"hotstar", "in.startv.hotstar"},
        {"okko", "ru.more.play"},
        {"crunchyroll", "com.crunchyroll.crunchyroid"},
        {"oneplay", "net.cme.voyo.cz.tvapp"},
    };
    /** Services named in hide_services, trimmed and lowercased: never a source of picks. */
    private static final Set<String> HIDDEN = new HashSet<>();
    static {
        for (String s : Config.HIDE_SERVICES.split(",")) {
            String key = s.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) HIDDEN.add(key);
        }
    }

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache, exploreCache;
    // Continue's list, art and cards (one at a time: they change the same picks).
    private final ExecutorService io = Net.serial("recs");
    // Explore's pages, the slides and the full-screen photos: apart from the downloads, so they don't wait behind them.
    private final ExecutorService artIo = Net.serial("recs-art");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    // Built and ready to draw, least recently used first (a look refreshes a card, so what's on screen stays).
    private final Map<String, Backdrop> images = new LinkedHashMap<>(32, 0.75f, true);
    private final Set<String> pending = new HashSet<>();
    // The few whose full-screen photo is built (oldest first): each costs BG_W × BG_H on the GPU, the cards almost nothing.
    private final Map<String, Backdrop> photos = new LinkedHashMap<>(8, 0.75f, true);
    private final Set<String> pendingPhotos = new HashSet<>();
    private final Map<String, Long> photoFailed = new HashMap<>();
    /** Full-screen photos kept: the focused card's, its neighbors' and a couple just left. */
    private static final int PHOTOS = 6;
    /** Cards kept: a screenful of both tabs, plus the stretch around the focus. */
    private static final int CARDS = 32;
    private volatile List<Pick> picks = new ArrayList<>();
    // Explore: this week's trending titles on TMDB, a page at a time as the row is scrolled.
    private volatile List<Pick> explore = new ArrayList<>();
    private volatile int explorePage;
    private volatile long exploreAt;
    private volatile boolean exploreLoading;
    private volatile boolean loaded, fetching;
    private volatile long listAt;
    private volatile String error = "";
    private volatile long failedAt, keyStamp;
    private volatile String listLanguage = "";   // language used for the saved titles and texts
    /** The TV allowed reading its apps' rows (TvPicks): asked again at most every APPS_EVERY. */
    private static volatile boolean apps;
    private static volatile long appsAt;
    private static final long APPS_EVERY = 15_000L;
    // Which apps are on the TV, by package: asked once, until apps change (appsChanged) or a minute has passed.
    private final Map<String, Boolean> installed = new HashMap<>();
    private long installedAt;
    private int installedVersion;
    private static final long INSTALLED_EVERY = 60_000L;
    private final Row[] rows = {new Row(), new Row()};

    /** A tab's row as last made, and what it was made from. */
    private static final class Row {
        List<Pick> source, list;
        int version;
        boolean trending;
    }

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        exploreCache = c.getSharedPreferences("recs-explore", Context.MODE_PRIVATE);
        io.execute(this::load);
    }

    /** The saved lists, read in the background; then the first refresh. */
    private void load() {
        try {
            if (cache.getInt("version", 0) != CACHE_VERSION) {
                cache.edit().clear().putInt("version", CACHE_VERSION).apply();
                exploreCache.edit().clear().apply();
            }
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            if (cache.contains("explore")) {
                // Older builds kept Explore with the picks, where each page rewrote the art and the picks too.
                exploreCache.edit().putString("explore", cache.getString("explore", null))
                        .putInt("explorePage", cache.getInt("explorePage", 0)).putLong("exploreAt", cache.getLong("exploreAt", 0)).commit();
                cache.edit().remove("explore").remove("explorePage").remove("exploreAt").apply();
            }
            String trending = exploreCache.getString("explore", null);
            if (trending != null) explore = parse(new JSONArray(trending));
            // Saved before their art was looked up: what was found since is put back on them (logo, calmer backdrop).
            for (Pick x : explore) savedArt(x);
            explorePage = explore.isEmpty() ? 0 : exploreCache.getInt("explorePage", 0);
            exploreAt = exploreCache.getLong("exploreAt", 0);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", "");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception e) {
            AppLog.w("recommendations: saved list unreadable (" + e.getMessage() + ")");
        } finally {
            loaded = true;
            main.post(this::refreshIfStale);
            notifyListeners();
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Picks are on: with a TMDB key, or without one from the TV's own apps (TvPicks, once the TV allowed it). */
    static boolean hasKey(Context c) {
        return tmdb() || allowed(c);
    }

    private static boolean tmdb() {
        return !Prefs.tmdbKey().isEmpty();
    }

    /** TvPicks.allowed (two calls to the system), remembered for APPS_EVERY. */
    private static boolean allowed(Context c) {
        long now = SystemClock.uptimeMillis();
        if (appsAt == 0 || now - appsAt >= APPS_EVERY) {
            apps = TvPicks.allowed(c);
            appsAt = now;
        }
        return apps;
    }

    /** Apps were installed, removed or changed (or the TV's permission may have): the rows look again. */
    void appsChanged() {
        synchronized (installed) {
            installed.clear();
            installedAt = SystemClock.uptimeMillis();
            installedVersion++;
        }
        appsAt = 0;
    }

    private static long statusModified;
    private static JSONObject statusRead;

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static synchronized JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        long modified = f.lastModified();   // 0: no file
        if (modified == 0 || !hasKey(c)) return new JSONObject();
        if (modified != statusModified) {
            try (FileInputStream in = new FileInputStream(f)) {
                statusRead = new JSONObject(Net.read(in));
            } catch (Exception e) {
                statusRead = new JSONObject();
            }
            statusModified = modified;
        }
        return statusRead;
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    static String periodLabel() {
        if (apps) return Ui.tr("FROM YOUR APPS");
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? Ui.tr("THIS MORNING") : hour >= 12 && hour < 18 ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    /**
     * A tab's row: Continue, the apps' Watch Next (newest first); Explore, this week's trending titles on TMDB (more as
     * it's scrolled: moreExplore), or without a key the apps' channels. Only titles whose app is on the TV. Made again
     * only when its list or the installed apps change (the home screen asks while drawing). Not to be modified.
     */
    List<Pick> row(int tab) {
        if (!hasKey(context)) return Collections.emptyList();
        boolean trending = tab == EXPLORE && tmdb();
        List<Pick> source = trending ? explore : picks;
        Row r = rows[tab == CONTINUE ? 0 : 1];
        synchronized (r) {
            int version = installedVersion();
            if (r.list != null && r.source == source && r.version == version && r.trending == trending) return r.list;
            List<Pick> out = new ArrayList<>();
            if (trending) {
                if (installed(CLOUDSTREAM)) out.addAll(source);
            } else {
                for (Pick p : source) if (p.resume == (tab == CONTINUE) && p.pkg != null && installed(p.pkg)) out.add(p);
            }
            r.source = source;
            r.version = version;
            r.trending = trending;
            r.list = Collections.unmodifiableList(out);
            return r.list;
        }
    }

    /** Name of the app the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : p.app;
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the art may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                if (needsArt(p)) {
                    try {
                        art(p);   // TMDB's art for the title first: the card shows its final picture from the start
                    } catch (Exception e) {
                        AppLog.w("art “" + p.title + "”: " + e.getMessage());
                    }
                } else if (p.logo == null) {
                    savedArt(p);   // looked up before (another copy of the list): the logo and backdrop it found
                }
                loadImage(p);   // notifies when it's ready
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        if ("live".equals(p.kind)) return Live.imageFile(context, p.backdrop);   // kept with the channels' images
        // TMDB paths ("/abc.jpg") as they are; TvPicks' full addresses by their hash.
        String name = p.backdrop.startsWith("/") ? p.backdrop.replace("/", "") : "tv" + Integer.toHexString(p.backdrop.hashCode());
        return new File(new File(context.getCacheDir(), "recs"), name);
    }

    /** Reads the apps' list again when it's stale or the key or apps changed; then the art and images it lacks. */
    void refreshIfStale() {
        if (!loaded || !hasKey(context)) return;
        long now = System.currentTimeMillis();
        if (tmdb() && (explore.isEmpty() || now - exploreAt > LIST_EVERY || now < exploreAt)) fetchExplore(true);
        if (fetching) return;
        boolean allowed = allowed(context);
        long stamp = sourceStamp(allowed);   // a new key, the TV's permission or the installed streaming apps refetch
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        // Without the TV's permission there is no list to read: it stays empty until the key or the permission changes.
        boolean stale = newKey || allowed && (now - listAt > TV_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language));
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !hasImage(pick) || needsArt(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            boolean quiet = false;   // nothing new: nothing to save or tell
            try {
                if (stale) {
                    List<Pick> fresh = allowed ? TvPicks.load(context) : new ArrayList<>();
                    for (Pick x : fresh) savedArt(x);   // looked up before: in place before anyone sees the list
                    quiet = !newKey && fresh.isEmpty() && picks.isEmpty();
                    picks = fresh;
                    listAt = System.currentTimeMillis();
                    listLanguage = Ui.language;
                }
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        art(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                if (!quiet) save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                quiet = false;
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                if (!quiet) {
                    writeStatus();
                    notifyListeners();
                }
            }
        });
    }

    /** The refresh button: `tab`'s list fetched again now (Explore from its first page), not when it's stale. */
    void refreshNow(int tab) {
        if (tab == EXPLORE) {
            fetchExplore(true);
            return;
        }
        listAt = 0;
        failedAt = 0;
        refreshIfStale();
    }

    /** What the background refresh prepares (art, images): Continue's row. Explore's cards load as they show. */
    private List<Pick> wanted() {
        return row(CONTINUE);
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…). */
    private static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /** The app is on the TV, and isn't a service hidden in hide_services (an app the TV ships without a subscription). */
    private boolean installed(String pkg) {
        synchronized (installed) {
            installedVersion();
            Boolean on = installed.get(pkg);
            if (on == null) {
                on = HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
                for (String[] s : SERVICES) if (s[1].equals(pkg) && HIDDEN.contains(s[0])) on = false;
                installed.put(pkg, on);
            }
            return on;
        }
    }

    /** The installed apps' version: a new one once INSTALLED_EVERY has passed (an app change the home screen missed). */
    private int installedVersion() {
        synchronized (installed) {
            long now = SystemClock.uptimeMillis();
            if (installedAt == 0 || now - installedAt >= INSTALLED_EVERY) {
                installed.clear();
                installedAt = now;
                installedVersion++;
            }
            return installedVersion;
        }
    }

    private long sourceStamp(boolean allowed) {
        long stamp = Prefs.tmdbKey().hashCode() * 31L + (allowed ? 1 : 0);
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[1]) ? service[1].hashCode() : 0);
        return stamp;
    }

    // MARK: - Explore (trending on TMDB, opened in CloudStream)

    /** Explore's next page, when the row is scrolled near its end. */
    void moreExplore() {
        fetchExplore(false);
    }

    /** This week's trending movies and shows: page 1 again (`fresh`) or the next one, up to EXPLORE_PAGES. */
    private void fetchExplore(boolean fresh) {
        if (!tmdb() || exploreLoading || !installed(CLOUDSTREAM)) return;
        if (!fresh && (explorePage == 0 || explorePage >= EXPLORE_PAGES)) return;
        exploreLoading = true;
        artIo.execute(() -> {
            try {
                int page = fresh ? 1 : explorePage + 1;
                JSONArray results = get("/trending/all/week?language=" + lang() + "&page=" + page).getJSONArray("results");
                List<Pick> next = fresh ? new ArrayList<>() : new ArrayList<>(explore);
                Set<String> seen = new HashSet<>();
                for (Pick p : next) seen.add(key(p));
                String label = TvPicks.label(context.getPackageManager(), CLOUDSTREAM, "CloudStream");
                for (int i = 0; i < results.length(); i++) {
                    Pick p = trending(results.optJSONObject(i), label);
                    if (p == null || !seen.add(key(p))) continue;
                    savedArt(p);
                    next.add(p);
                }
                explore = next;
                explorePage = page;
                if (fresh) exploreAt = System.currentTimeMillis();
                exploreCache.edit().putString("explore", serialize(next).toString()).putInt("explorePage", page)
                        .putLong("exploreAt", exploreAt).apply();
            } catch (Exception e) {
                AppLog.w("explore: " + e.getMessage());
            } finally {
                exploreLoading = false;
                notifyListeners();
            }
        });
    }

    /** A trending result as a pick that opens in CloudStream; null when it isn't a movie or show, or has no backdrop. */
    private static Pick trending(JSONObject o, String label) {
        if (o == null) return null;
        String kind = o.optString("media_type"), backdrop = o.optString("backdrop_path", "");
        if (!(kind.equals("movie") || kind.equals("tv")) || backdrop.isEmpty() || "null".equals(backdrop)) return null;
        if (Config.HIDE_ANIMATION && animated(o)) return null;
        Pick p = new Pick();
        p.id = p.tmdb = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        p.pkg = CLOUDSTREAM;
        p.app = label;
        p.link = cloudStreamLink(kind, p.id);
        String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date", "");
        List<String> parts = new ArrayList<>();
        parts.add(Ui.tr("Trending"));
        parts.add(kind.equals("movie") ? Ui.tr("Movie") : Ui.tr("Series"));
        if (date.length() >= 4) parts.add(date.substring(0, 4));
        p.meta = Ui.join(" · ", parts);
        return p;
    }

    /** CloudStream's own share link for a TMDB title through StreamPlay ("csshare:" + base64 source ? base64 id). */
    private static String cloudStreamLink(String kind, int id) {
        String json = "{\"id\":" + id + ",\"type\":\"" + kind + "\",\"aniId\":null,\"malId\":null}";
        return "csshare:" + base64(STREAMPLAY) + "?" + base64(json);
    }

    private static String base64(String s) {
        return android.util.Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    }

    // MARK: - TMDB art (wide backdrop and logo, by the TMDB number the app gives)

    private static String artKey(Pick p) {
        return "art:" + p.kind + p.tmdb;
    }

    private boolean needsArt(Pick p) {
        return tmdb() && p.tmdb > 0 && (p.logo == null || p.backdrop == null || !p.backdrop.startsWith("/"))
                && cache.getString(artKey(p), null) == null;
    }

    /** Swaps the app's portrait poster for TMDB's wide backdrop and adds the logo: once per title, then from the cache. */
    private void art(Pick p) throws Exception {
        if (!tmdb() || p.tmdb <= 0 || savedArt(p)) return;
        String lang = Ui.language;
        if (lang.indexOf('-') > 0) lang = lang.substring(0, lang.indexOf('-'));   // TMDB's image languages have no country
        JSONObject images = get("/" + p.kind + "/" + p.tmdb + "/images?include_image_language=" + lang + ",en,null");
        String backdrop = calmBackdrop(images), logo = bestLogo(images, lang);
        cache.edit().putString(artKey(p), new JSONObject().put("b", backdrop == null ? "" : backdrop)
                .put("l", logo == null ? "" : logo).toString()).putString(altKey(p), slideList(images)).apply();
        savedArt(p);
    }

    /** Applies the art looked up before, if any; false when it hasn't been looked up yet. */
    private boolean savedArt(Pick p) {
        String saved = p.tmdb <= 0 || !tmdb() ? null : cache.getString(artKey(p), null);
        if (saved == null) return false;
        try {
            JSONObject o = new JSONObject(saved);
            if (!o.optString("b").isEmpty()) p.backdrop = o.optString("b");
            p.logo = o.optString("l").isEmpty() ? null : o.optString("l");
        } catch (JSONException ignored) {
        }
        return true;
    }

    /** The title's textless backdrops 1280+ wide (our own text goes on top), best rated first. */
    private static List<JSONObject> textless(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b != null && b.isNull("iso_639_1") && !b.optString("file_path", "").isEmpty() && b.optInt("width") >= 1280) out.add(b);
        }
        Collections.sort(out, (a, b) -> Double.compare(b.optDouble("vote_average", 0), a.optDouble("vote_average", 0)));
        return out;
    }

    /**
     * The title's best backdrop: no text written over it, the closest to 1920 wide (larger only costs download, disk and
     * decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** How many of a title's best-rated textless backdrops are compared (one small preview each, once per title). */
    private static final int CALM_CANDIDATES = 8;

    /**
     * Of the title's 8 best-rated textless backdrops, the one that suits the hero best: calm and not too bright where
     * the logo and the text go, with its subject on the right and above the row. Weights fitted to hand-picked
     * backdrops for 13 titles (10 of 13 chosen the same; the rating order alone, 3). Falls back to bestBackdrop.
     */
    private static String calmBackdrop(JSONObject images) {
        String fallback = bestBackdrop(images);
        List<JSONObject> textless = textless(images);
        if (textless.size() < 2) return fallback;
        String best = fallback;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < Math.min(CALM_CANDIDATES, textless.size()); i++) {
            String path = textless.get(i).optString("file_path");
            try {
                double score = heroFit(path) + 2 * i;   // two steps per place in the rating order
                if (score < bestScore) {
                    bestScore = score;
                    best = path;
                }
            } catch (IOException | RuntimeException e) {
                // a preview that doesn't load just isn't compared
            }
        }
        return best;
    }

    /**
     * How badly a backdrop suits the hero, on a 300-wide preview (lower is better): detail behind the text minus detail
     * on the right (the subject there is good), counted twice; brightness behind the text, more so past a light gray;
     * less the right half's detail in its top two thirds over its bottom third (the row and the shelf cover that). The
     * text area is the hero's place on screen: the left half, 26% to 54% down.
     */
    private static double heroFit(String path) throws IOException {
        byte[] data = Net.get("https://image.tmdb.org/t/p/w300" + path, 8000, 10000);
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (b == null) throw new IOException("unreadable preview");
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        double[] text = detail(px, w, h, 0, 0.5f, 0.26f, 0.54f), right = detail(px, w, h, 0.5f, 1, 0.1f, 0.9f);
        double[] upper = detail(px, w, h, 0.5f, 1, 0, 0.67f), lower = detail(px, w, h, 0.5f, 1, 0.67f, 1);
        return 2 * (text[0] - right[0]) + 0.05 * text[1] + 0.15 * Math.max(0, text[1] - 170) - (upper[0] - lower[0]);
    }

    /** {average luminance edge, average luminance} over a region given as fractions of the image. */
    private static double[] detail(int[] px, int w, int h, float x0, float x1, float y0, float y1) {
        double edges = 0, light = 0;
        int n = 0;
        for (int y = Math.round(h * y0); y < Math.min(Math.round(h * y1), h - 1); y++) {
            for (int x = Math.round(w * x0); x < Math.min(Math.round(w * x1), w - 1); x++) {
                float l = luma(px[y * w + x]);
                edges += Math.abs(l - luma(px[y * w + x + 1])) + Math.abs(l - luma(px[(y + 1) * w + x]));
                light += l;
                n++;
            }
        }
        return n == 0 ? new double[] {0, 0} : new double[] {edges / n, light / n};
    }

    private static float luma(int p) {
        return 0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255);
    }

    /** The logo in the TV's language, else in English, else without language; PNG only (Android draws no SVG). */
    private static String bestLogo(JSONObject images, String lang) {
        JSONArray list = images == null ? null : images.optJSONArray("logos");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject l = list.optJSONObject(i);
            String path = l == null ? "" : l.optString("file_path", "");
            if (!path.endsWith(".png")) continue;
            String iso = l.isNull("iso_639_1") ? "" : l.optString("iso_639_1");
            double score = (iso.equals(lang) ? 3e3 : iso.equals("en") ? 2e3 : iso.isEmpty() ? 1e3 : 0) + l.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = path;
            }
        }
        return best;
    }

    // MARK: - Slides (while a title stays in focus, its backdrop drifts and cycles through its other ones)

    /** How many of the title's other backdrops the home screen cycles through. */
    private static final int SLIDES = 9;
    /** Full-screen photos kept for the slides (the one showing, the next and the last). */
    private static final int SLIDES_KEPT = 3;
    private final Map<String, Bitmap> slides = new LinkedHashMap<>(4, 0.75f, true);
    private final Set<String> pendingSlides = new HashSet<>();

    private static String altKey(Pick p) {
        return "alt:" + p.kind + p.tmdb;
    }

    /** The title's textless backdrops, best rated first, as a JSON array of TMDB paths. */
    private static String slideList(JSONObject images) {
        List<JSONObject> textless = textless(images);
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(SLIDES + 1, textless.size()); i++) out.put(textless.get(i).optString("file_path"));
        return out.toString();
    }

    /**
     * The title's other backdrops (up to SLIDES, best rated first, without the one it shows), or null: then they're
     * looked up in the background (once per title) and the listeners hear when they're known.
     */
    List<String> alternates(Pick p) {
        if (p == null || !tmdb() || p.tmdb <= 0) return new ArrayList<>();
        final String key = altKey(p);
        String saved = cache.getString(key, null);
        if (saved != null) {
            List<String> out = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(saved);
                for (int i = 0; i < a.length() && out.size() < SLIDES; i++) if (!a.optString(i).equals(p.backdrop)) out.add(a.optString(i));
            } catch (JSONException ignored) {
            }
            return out;
        }
        synchronized (images) {
            // The card's art is being looked up: it saves these too, and its card tells the listeners.
            if (pending.contains(p.backdrop) && needsArt(p)) return null;
            if (!pendingSlides.add(key)) return null;
        }
        artIo.execute(() -> {
            try {
                JSONObject images = get("/" + p.kind + "/" + p.tmdb + "/images?include_image_language=null");
                cache.edit().putString(key, slideList(images)).apply();
                notifyListeners();
            } catch (Exception e) {
                AppLog.w("slides “" + p.title + "”: " + e.getMessage());
            } finally {
                synchronized (images) { pendingSlides.remove(key); }
            }
        });
        return null;
    }

    /**
     * A backdrop's full-screen photo for the slides (BG_W × BG_H), or null: then it's made in the background
     * (downloaded once) and the listeners hear when it's ready. Only the last SLIDES_KEPT stay.
     */
    Bitmap slide(String path) {
        if (path == null || !path.startsWith("/")) return null;
        synchronized (images) {
            Bitmap b = slides.get(path);
            if (b != null) return b;
            if (!pendingSlides.add(path)) return null;
        }
        io.execute(() -> {   // behind the cards: a full-screen picture's GPU copy mid-move stutters it
            File f = new File(new File(context.getCacheDir(), "recs"), path.replace("/", ""));
            try {
                if (!f.exists()) fetch(IMAGES + path, f);
                Bitmap bg = Backdrop.background(decode(f, Backdrop.BG_W, true));
                synchronized (images) {
                    slides.put(path, bg);
                    while (slides.size() > SLIDES_KEPT) slides.remove(slides.keySet().iterator().next());
                }
                notifyListeners();
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                AppLog.w("slide: " + e.getMessage());
            } finally {
                synchronized (images) { pendingSlides.remove(path); }
            }
        });
        return null;
    }

    // MARK: - TMDB requests

    private static JSONObject get(String path) throws IOException, JSONException {
        String key = Prefs.tmdbKey();
        if (key.isEmpty()) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        try {
            return new JSONObject(Net.getString(url, 8000, 8000, "Accept", "application/json",
                    "Authorization", bearer ? "Bearer " + key : null));
        } catch (Net.HttpError e) {
            throw new IOException(e.code == 401 ? "TMDB rejected the key" : "TMDB returned HTTP " + e.code);
        }
    }

    // MARK: - Images (originals on disk, up to DISK; the cards near the focus kept ready in memory)

    private void download(Pick p) throws IOException {
        if ("live".equals(p.kind)) {
            if (Live.store(context, p.backdrop) == null) throw new IOException("no programme image");
            return;
        }
        File f = imageFile(p);
        if (f.exists()) return;
        // TvPicks: the app's own image, a web address or a content:// one (from the app itself).
        if (p.backdrop.startsWith("content:") || p.backdrop.startsWith("android.resource:")) {
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            File part = new File(f.getPath() + "." + Thread.currentThread().getId() + ".part");
            try (InputStream in = context.getContentResolver().openInputStream(android.net.Uri.parse(p.backdrop));
                 FileOutputStream out = new FileOutputStream(part)) {
                if (in == null) throw new IOException("no image");
                Net.copy(in, out);
            } catch (IOException | RuntimeException e) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException(e.getMessage());
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save the image");
            prune(f.getParentFile());
            return;
        }
        fetch(p.backdrop.startsWith("/") ? IMAGES + p.backdrop : p.backdrop, f);
    }

    /** Downloads a web image to f. */
    private static void fetch(String address, File f) throws IOException {
        if (!address.startsWith("https://") && !address.startsWith("http://")) throw new IOException("unsupported image address");
        Net.download(address, f, 8000, 20000);
        prune(f.getParentFile());
    }

    /**
     * Decodes `f` at the largest power-of-two reduction still at least `minWidth` wide. With `gpu`, an image that is
     * already BG_W × BG_H goes straight to GPU memory: it's only drawn.
     */
    private static Bitmap decode(File f, int minWidth, boolean gpu) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= minWidth) opts.inSampleSize *= 2;
        if (gpu && Backdrop.HARDWARE != null && opts.inSampleSize == 1
                && bounds.outWidth == Backdrop.BG_W && bounds.outHeight == Backdrop.BG_H) {
            opts.inPreferredConfig = Backdrop.HARDWARE;
        }
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
        if (b == null) throw new IOException("unreadable image");
        return b;
    }

    /** Reads the image (twice the card's width is plenty) and builds the card and its blurred background. */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            boolean music = "music".equals(p.kind);
            Bitmap b = decode(f, 2 * Backdrop.CARD_W, false);
            AppLog.w("backdrop “" + p.title + "”: " + b.getWidth() + "×" + b.getHeight());
            if (music) b = Backdrop.trimBars(b);   // a video's thumbnail: the picture without its black bars
            Backdrop backdrop = Backdrop.make(b, music);   // a song's card: its square cover
            if (p.logo != null) {
                try {
                    backdrop.logo = loadLogo(p.logo);
                } catch (IOException | RuntimeException e) {
                    AppLog.w("logo “" + p.title + "”: " + e.getMessage());   // the title in text instead
                }
            }
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                // Least recently looked at goes first; photos are kept apart (photo()).
                while (images.size() > CARDS) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    /**
     * The pick's full-screen photo, or null: then it's made in the background from the image on disk (already there
     * once its card shows) and the listeners hear when it's ready. Only the last few are kept.
     */
    Bitmap photo(Pick p) {
        if (needsArt(p)) {
            backdrop(p);   // the art may swap the image (backdrop() looks it up first): the photo waits for it
            return null;
        }
        Backdrop b = backdrop(p);
        if (b == null) return null;
        final String key = p.backdrop;
        synchronized (images) {
            if (b.bg != null) {
                photos.remove(key);     // most recent last
                photos.put(key, b);
                return b.bg;
            }
            Long failed = photoFailed.get(key);
            if (failed != null && System.currentTimeMillis() - failed < 60_000L) return null;   // not again right away
            if (!pendingPhotos.add(key)) return null;
        }
        io.execute(() -> {   // behind the cards: a full-screen picture's GPU copy mid-move stutters it
            try {
                makePhoto(p, key, b);
            } finally {
                synchronized (images) { pendingPhotos.remove(key); }
            }
        });
        return null;
    }

    private void makePhoto(Pick p, String key, Backdrop b) {
        if (!key.equals(p.backdrop)) return;   // the art changed midway
        File f = imageFile(p);
        try {
            download(p);   // the card may outlive its file on disk (prune keeps the newest DISK)
            boolean music = "music".equals(p.kind);
            Bitmap src = decode(f, Backdrop.BG_W, !music);
            if (music) src = Backdrop.soften(Backdrop.trimBars(src));   // a song's cover, softened
            b.bg = Backdrop.background(src);
            synchronized (images) {
                photoFailed.remove(key);
                photos.put(key, b);
                while (photos.size() > PHOTOS) {
                    String oldest = photos.keySet().iterator().next();
                    photos.remove(oldest).bg = null;
                }
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();   // a broken file: downloaded again next time
            synchronized (images) { photoFailed.put(key, System.currentTimeMillis()); }
            AppLog.w("full-screen photo “" + p.title + "”: " + e.getMessage());
        }
    }

    /** The logo from disk (downloaded once), fitted into Backdrop.LOGO_W × LOGO_H. */
    private Bitmap loadLogo(String path) throws IOException {
        File f = new File(new File(context.getCacheDir(), "logos"), path.replace("/", ""));
        if (!f.exists()) fetch(LOGOS + path, f);
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw new IOException("unreadable logo");
        }
        return Backdrop.logo(b);
    }

    /**
     * The home screen left the screen (or memory is short): the full-screen photos and the slides go (on the GPU, the
     * biggest part), and only each tab's first cards stay, so they're never gray when you come back. A Fire TV Stick
     * has ~900 MB, and the app opened next (CloudStream) needs it.
     */
    void trim() {
        Set<String> keep = new HashSet<>();
        for (int tab : new int[] {CONTINUE, EXPLORE}) {
            List<Pick> row = row(tab);
            for (int i = 0; i < Math.min(ROW, row.size()); i++) if (row.get(i).backdrop != null) keep.add(row.get(i).backdrop);
        }
        synchronized (images) {
            images.keySet().retainAll(keep);
            for (Backdrop b : photos.values()) b.bg = null;
            photos.clear();
            slides.clear();
        }
    }

    void trimSlides() {
        synchronized (images) {
            slides.clear();
        }
    }

    /**
     * Covered by one of our own screens (the music screensaver, Live TV): Android sends no trim for that, as the
     * process still shows something. The slides and the photos go but the last one shown, so coming back looks the same.
     */
    void trimCovered() {
        synchronized (images) {
            slides.clear();
            Iterator<Backdrop> it = photos.values().iterator();
            for (int n = photos.size(); n > 1; n--) {
                it.next().bg = null;
                it.remove();
            }
        }
    }

    /** Images kept on disk per folder: enough for Continue and a good stretch of Explore. */
    private static final int DISK = 80;

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= DISK) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - DISK; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            SharedPreferences.Editor e = cache.edit().putString("picks", serialize(picks).toString()).putLong("listAt", listAt)
                    .putString("language", listLanguage).putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            Set<String> keep = new HashSet<>();
            for (List<Pick> list : Arrays.asList(picks, explore)) {
                for (Pick p : list) {
                    if (p.tmdb <= 0) continue;
                    keep.add(artKey(p));
                    keep.add(altKey(p));
                }
            }
            // The art of titles no longer listed goes, and what older builds kept (Watchmode's links, the period).
            for (String k : cache.getAll().keySet()) {
                if (k.startsWith("wm:") || k.equals("period") || k.equals("english")
                        || (k.startsWith("art:") || k.startsWith("alt:")) && !keep.contains(k)) e.remove(k);
            }
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONArray serialize(List<Pick> list) throws JSONException {
        JSONArray a = new JSONArray();
        for (Pick p : list) {
            a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop).put("link", p.link == null ? "" : p.link)
                    .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                    .put("tmdb", p.tmdb).put("logo", p.logo == null ? "" : p.logo)
                    .put("progress", p.progress).put("left", p.left).put("resume", p.resume));
        }
        return a;
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.tmdb = o.optInt("tmdb");
            p.logo = o.optString("logo").isEmpty() ? null : o.optString("logo");
            p.progress = (float) o.optDouble("progress", 0);
            p.left = o.optLong("left");
            p.resume = o.optBoolean("resume");
            out.add(p);
        }
        return out;
    }
}
