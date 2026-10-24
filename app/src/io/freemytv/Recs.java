package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String link;            // opens the title in its app (looked up with Watchmode), or null: the app's home
        boolean detailed;
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
    /** The backdrops: the light design shows them at most 1280 wide (Backdrop.BG_W), so TMDB's 1280 copy is enough. */
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
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 12;  // 4: TvPicks (Fire TV edition); 6: TMDB art for TvPicks; 12: Continue and Explore
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"okko", "okko", "ru.more.play"},
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("oneplay", "Oneplay");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
    /** The errors the settings say in words: TMDB lists none of the TV's apps in REGION, or the TV has none we know. */
    static final String NO_APPS = "none of the TV's streaming services are on TMDB";
    static final String NO_KNOWN_APPS = "none of the TV's streaming apps can give picks";

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    // Built and ready to draw, least recently used first (a look refreshes a card, so what's on screen stays).
    private final Map<String, Backdrop> images = new LinkedHashMap<>(32, 0.75f, true);
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    // The few whose full-screen photo is built (oldest first): each costs BG_W × BG_H on the GPU, the cards almost nothing.
    private final Map<String, Backdrop> photos = new LinkedHashMap<>(8, 0.75f, true);
    private final java.util.Set<String> pendingPhotos = new java.util.HashSet<>();
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
    // Explore's pages and the focused title's art: apart from the downloads, so they don't wait behind them.
    private final ExecutorService artIo = Executors.newSingleThreadExecutor();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts
    /** The picks come from the TV's own apps (TvPicks), with or without a TMDB key: then the key only adds the art. */
    private static volatile boolean apps;

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        apps = TvPicks.allowed(c);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            String trending = cache.getString("explore", null);
            if (trending != null) explore = parse(new JSONArray(trending));
            // Saved before their art was looked up: what was found since is put back on them (logo, calmer backdrop).
            for (Pick x : explore) savedArt(x);
            explorePage = explore.isEmpty() ? 0 : cache.getInt("explorePage", 0);
            exploreAt = cache.getLong("exploreAt", 0);
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Picks are on: with a TMDB key, or without one from the TV's own apps (TvPicks, once the TV allowed it). */
    static boolean hasKey(Context c) {
        return tmdb() || TvPicks.allowed(c);
    }

    private static boolean tmdb() {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Without TMDB the list is the apps' own (Watch Next changes as you watch): looked at again every 15 minutes. */
    private static final long TV_EVERY = 15 * 60_000L;

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        if (apps) return Ui.tr("FROM YOUR APPS");
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /**
     * A tab's row: Continue, the apps' Watch Next (newest first); Explore, this week's trending titles on TMDB (more as
     * it's scrolled: moreExplore), or without a key the apps' channels. Only titles whose app is on the TV.
     */
    List<Pick> row(int tab) {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        if (tab == EXPLORE && tmdb()) {
            if (installed(CLOUDSTREAM)) out.addAll(explore);
            return out;
        }
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if (p.resume != (tab == CONTINUE) || p.pkg == null || !have.computeIfAbsent(p.pkg, this::installed)) continue;
            out.add(p);
        }
        return out;
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        if (p == null || p.app == null) return "";
        String name = SERVICE_NAME.get(p.app);
        return name != null ? name : p.app;     // TvPicks: the app's own name ("CloudStream")
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
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
                        AppLog.w("art \u201c" + p.title + "\u201d: " + e.getMessage());
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

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context)) return;
        long now = System.currentTimeMillis();
        if (tmdb() && (explore.isEmpty() || now - exploreAt > LIST_EVERY || now < exploreAt)) fetchExplore(true);
        if (fetching) return;
        apps = TvPicks.allowed(context);
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > (apps ? TV_EVERY : LIST_EVERY) || now < listAt
                || picks.isEmpty() || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick) || needsLink(pick) || needsArt(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) {
                    if (!apps) {
                        picks = new ArrayList<>();   // the TV hasn't allowed reading Watch Next: Continue stays empty
                    } else {
                        List<Pick> fresh = TvPicks.load(context);
                        for (Pick x : fresh) savedArt(x);   // looked up before: in place before anyone sees the list
                        picks = fresh;
                        period = p;
                        listAt = System.currentTimeMillis();
                        listLanguage = Ui.language;
                    }
                }
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        art(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    findLink(pick);
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /** What the background refresh prepares (art, images): Continue's row. Explore's cards load as they show. */
    List<Pick> wanted() {
        return row(CONTINUE);
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // None of the services we know is on the TV (or hide_services hides them all): no region would change that.
        boolean any = false;
        for (String[] s : SERVICES) any |= installed(s[2]);
        if (!any) throw new IOException(NO_KNOWN_APPS);
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException(NO_APPS);
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")
                + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 24);
        alternate(list, seen, newMovies, newShows, 24);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    private static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (Config.HIDE_ANIMATION && animated(o)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        list.add(p);
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images&include_image_language=null," + Ui.language.replaceAll("-.*", "") + ",en");
        p.title = title(p, o);
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) parts.add(minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes));
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) parts.add(Ui.plural("plural|one:{n} season|other:{n} seasons", seasons));
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
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

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        for (String[] s : SERVICES) {
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /** Services named in hide_services (config.properties), trimmed and lowercased: never a source of picks. */
    private static final Set<String> HIDDEN = new HashSet<>();
    static {
        for (String s : Config.HIDE_SERVICES.split(",")) {
            String key = s.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) HIDDEN.add(key);
        }
    }

    /** The service's app is on the TV, and the service isn't hidden (an app the TV ships without a subscription, say). */
    private boolean installed(String pkg) {
        if (!HIDDEN.isEmpty()) for (String[] s : SERVICES) if (s[2].equals(pkg) && HIDDEN.contains(s[1])) return false;
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        long stamp = ((Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode()) * 31L + (Config.HIDE_ANIMATION ? 1 : 0)) * 31L + (apps ? 1 : 0);
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
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
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (Pick p : next) seen.add(key(p));
                String label = appLabel(CLOUDSTREAM);
                for (int i = 0; i < results.length(); i++) {
                    Pick p = trending(results.optJSONObject(i), label);
                    if (p == null || !seen.add(key(p))) continue;
                    savedArt(p);
                    next.add(p);
                }
                explore = next;
                explorePage = page;
                if (fresh) exploreAt = System.currentTimeMillis();
                cache.edit().putString("explore", serialize(next).toString()).putInt("explorePage", page).putLong("exploreAt", exploreAt).apply();
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
        p.meta = String.join(" · ", parts);
        p.detailed = true;
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

    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "CloudStream";
        }
    }

    // MARK: - TMDB art for the apps' picks (wide backdrop and logo, by the TMDB number the app gives)

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
        String lang = Ui.language.replaceAll("-.*", "");
        JSONObject images = get("/" + p.kind + "/" + p.tmdb + "/images?include_image_language=" + lang + ",en,null");
        String backdrop = calmBackdrop(images), logo = bestLogo(images, lang);
        cache.edit().putString(artKey(p), new JSONObject().put("b", backdrop == null ? "" : backdrop)
                .put("l", logo == null ? "" : logo).toString()).putString(altKey(p), slideList(images)).apply();
        savedArt(p);
    }

    // MARK: - Slides (while a title stays in focus, its backdrop drifts and cycles through its other ones)

    /** How many of the title's other backdrops the home screen cycles through. */
    private static final int SLIDES = 9;
    /** Full-screen photos kept for the slides (the one showing, the next and the last). */
    private static final int SLIDES_KEPT = 3;
    private final Map<String, Bitmap> slides = new LinkedHashMap<>(4, 0.75f, true);
    private final java.util.Set<String> pendingSlides = new java.util.HashSet<>();

    private static String altKey(Pick p) {
        return "alt:" + p.kind + p.tmdb;
    }

    /** The title's textless backdrops (1280+ wide), best rated first, as a JSON array of TMDB paths. */
    private static String slideList(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        List<JSONObject> textless = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b != null && b.isNull("iso_639_1") && !b.optString("file_path", "").isEmpty() && b.optInt("width") >= 1280) textless.add(b);
        }
        java.util.Collections.sort(textless, (a, b) -> Double.compare(b.optDouble("vote_average", 0), a.optDouble("vote_average", 0)));
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
            } catch (org.json.JSONException ignored) {
            }
            return out;
        }
        synchronized (images) {
            if (!pendingSlides.add(key)) return null;
        }
        artIo.execute(() -> {
            try {
                JSONObject images = get("/" + p.kind + "/" + p.tmdb + "/images?include_image_language=null");
                cache.edit().putString(key, slideList(images)).apply();
                notifyListeners();
            } catch (Exception e) {
                AppLog.w("slides \u201c" + p.title + "\u201d: " + e.getMessage());
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
        io.execute(() -> {
            File f = new File(new File(context.getCacheDir(), "recs"), path.replace("/", ""));
            try {
                if (!f.exists()) fetch(IMAGES + path, f);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), bounds);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.BG_W) opts.inSampleSize *= 2;
                Bitmap src = BitmapFactory.decodeFile(f.getPath(), opts);
                if (src == null) throw new IOException("unreadable image");
                Bitmap bg = Backdrop.background(src);
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

    /** Applies the art looked up before, if any; false when it hasn't been looked up yet. */
    private boolean savedArt(Pick p) {
        String saved = p.tmdb <= 0 || !tmdb() ? null : cache.getString(artKey(p), null);
        if (saved == null) return false;
        try {
            JSONObject o = new JSONObject(saved);
            if (!o.optString("b").isEmpty()) p.backdrop = o.optString("b");
            p.logo = o.optString("l").isEmpty() ? null : o.optString("l");
        } catch (org.json.JSONException ignored) {
        }
        return true;
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
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        List<JSONObject> textless = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b != null && b.isNull("iso_639_1") && !b.optString("file_path", "").isEmpty() && b.optInt("width") >= 1280) textless.add(b);
        }
        if (textless.size() < 2) return fallback;
        java.util.Collections.sort(textless, (a, b) -> Double.compare(b.optDouble("vote_average", 0), a.optDouble("vote_average", 0)));
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
        HttpURLConnection c = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/w300" + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        Bitmap b;
        try (InputStream in = c.getInputStream()) {
            b = BitmapFactory.decodeStream(in);
        } finally {
            c.disconnect();
        }
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

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private static String linkKey(Pick p) {
        return "wm:" + p.kind + p.id + p.app;
    }

    private boolean needsLink(Pick p) {
        return linksOn() && p.pkg != null && p.link == null
                && cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt;
    }

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = Config.WATCHMODE_KEY.trim();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private void findLink(Pick p) {
        if (!linksOn() || p.pkg == null || p.link != null) return;
        String saved = cache.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        try {
            String link = "";
            JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
                for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    boolean same = want.equals("max") ? name.equals("max") : name.contains(want);
                    if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
                }
            }
            cache.edit().putString(linkKey(p), link).apply();
            p.link = link.isEmpty() ? null : link;
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", Config.WATCHMODE_KEY.trim().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
        }
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        if ("live".equals(p.kind)) {
            if (Live.store(context, p.backdrop) == null) throw new IOException("no programme image");
            return;
        }
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        // TvPicks: the app's own image, a web address or a content:// one (from the app itself).
        if (p.backdrop.startsWith("content:") || p.backdrop.startsWith("android.resource:")) {
            try (InputStream in = context.getContentResolver().openInputStream(android.net.Uri.parse(p.backdrop));
                 FileOutputStream out = new FileOutputStream(part)) {
                if (in == null) throw new IOException("no image");
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            } catch (IOException | RuntimeException e) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException(e.getMessage());
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save the image");
            prune(f.getParentFile());
            return;
        }
        String address = p.backdrop.startsWith("/") ? IMAGES + p.backdrop : p.backdrop;
        fetch(address, f);
    }

    /** Downloads a web image to f (through f.part, so a cut-off download never looks finished). */
    private static void fetch(String address, File f) throws IOException {
        if (!address.startsWith("https://") && !address.startsWith("http://")) throw new IOException("unsupported image address");
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            if ("music".equals(p.kind)) b = Backdrop.trimBars(b);   // a video's thumbnail: the picture without its black bars
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight, "music".equals(p.kind));   // a song's card: its square cover
            if (forLight != light) return;   // the design changed midway: this one is stale
            if (p.logo != null) {
                try {
                    backdrop.logo = loadLogo(p.logo);
                } catch (IOException | RuntimeException e) {
                    AppLog.w("logo \u201c" + p.title + "\u201d: " + e.getMessage());   // the title in text instead
                }
            }
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                // Least recently looked at goes first; photos are kept apart (photo()).
                while (images.size() > (forLight ? CARDS : 4)) images.remove(images.keySet().iterator().next());
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
        io.execute(() -> {
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
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.BG_W) opts.inSampleSize *= 2;
            Bitmap src = BitmapFactory.decodeFile(f.getPath(), opts);
            if (src == null) throw new IOException("unreadable image");
            if ("music".equals(p.kind)) src = Backdrop.soften(Backdrop.trimBars(src));   // a song's cover, softened
            b.bg = Backdrop.background(src);
            synchronized (images) {
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
            AppLog.w("full-screen photo \u201c" + p.title + "\u201d: " + e.getMessage());
        }
    }

    /** The logo from disk (downloaded once), fitted into Backdrop.LOGO_W × LOGO_H. */
    private android.graphics.Bitmap loadLogo(String path) throws IOException {
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
        java.util.Set<String> keep = new java.util.HashSet<>();
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
            SharedPreferences.Editor e = cache.edit().putString("picks", serialize(picks).toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : picks) keep.add(linkKey(p));
            for (List<Pick> list : java.util.Arrays.asList(picks, explore)) {
                for (Pick p : list) {
                    if (p.tmdb <= 0) continue;
                    keep.add(artKey(p));
                    keep.add(altKey(p));
                }
            }
            for (String k : cache.getAll().keySet()) {
                if ((k.startsWith("wm:") && !k.equals("wm:refused") || k.startsWith("art:") || k.startsWith("alt:")) && !keep.contains(k)) e.remove(k);
            }
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONArray serialize(List<Pick> list) throws org.json.JSONException {
        JSONArray a = new JSONArray();
        for (Pick p : list) {
            a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop).put("link", p.link == null ? "" : p.link)
                    .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                    .put("detailed", p.detailed).put("tmdb", p.tmdb).put("logo", p.logo == null ? "" : p.logo)
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
            p.detailed = o.optBoolean("detailed");
            p.tmdb = o.optInt("tmdb");
            p.logo = o.optString("logo").isEmpty() ? null : o.optString("logo");
            p.progress = (float) o.optDouble("progress", 0);
            p.left = o.optLong("left");
            p.resume = o.optBoolean("resume");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
