package io.idiotbox;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen, styled after the Apple TV app:
 *  - at the top left, the tabs (Continue watching, Explore, Live TV, Music: Settings > Tabs) in a frosted capsule; at
 *    the top right, the weather, the time, what's playing and the round buttons (Refresh, Settings);
 *  - the focused title over its own photo (the hero), and the tab's row of cards under it (Live TV and Music add a row
 *    of pills between them);
 *  - at the bottom, the apps: small icons that grow into cards when they have focus (the titles shrink to make room);
 *  - ↓ on the apps: everything moves up and the bottom drawer (the inputs) appears; ↑ or Back closes it;
 *  - All Apps: a sheet over everything.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into views so a change only records
 * again the one it touches.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides.
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    private static final float TILE_RADIUS = 22;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final long TOAST_MS = 6000, FADE_MS = 800, GLOW_FADE_MS = 260;
    /** Blur radii of the shadows (Shadows). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, NOW = 2, STATUS = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7;
    private static final int Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Channel logos: their downloads don't hold up the lists (LOADER). */
    private static final ExecutorService LOGO_LOADER = Executors.newSingleThreadExecutor();
    // The tab bar (a 64 px capsule), the round buttons and the row of picks (Recs.ROW 16:9 cards).
    private static final float L_TABBAR_TOP = 56, L_TABBAR_H = 64, L_CARDS_TOP = 596, L_CARD_H = 154;
    private static final float L_CARD_GAP = 24, L_BUTTON = 60, L_BUTTON_GAP = 20, L_BUTTON_CY = 88;
    // With focus on the apps the titles shrink to small cards and the apps grow to the titles' size; at rest the apps are
    // small square icons. Both rows sit on the same bottom-up layout (APPS_BOTTOM).
    private static final float L_SMALL_W = 176, L_SMALL_H = 99, L_SMALL_TOP = 690, L_SMALL_GAP = 16;
    private static final float L_APPS_BOTTOM = 1032, L_ICON = 88, L_ICON_GAP = 20;
    private static final float L_LOGO_TOP = 342, L_HERO_META = 536, L_HERO_BAR = 560;
    /** The progress bars (hero and cards). */
    private static final int ACCENT = 0xFFF5C451;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626;
    private static final int L_INK = 0xFF1D1D1F;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    /**
     * The tabs (Recs.CONTINUE, Recs.EXPLORE, Live TV, Music): `tab` is the one selected, by that id. Which are shown, and
     * in what order, is Settings > Tabs (tabOrder); the tab bar and topIndex count positions in it, and the round
     * buttons follow the tabs in topRects and topFocus.
     */
    private static final int MAX_TABS = 4;
    /** The Live TV tab (after Recs.CONTINUE and Recs.EXPLORE): its row comes from Live, filtered by the group pills. */
    private static final int TAB_LIVE = 2;
    /** The Music tab: the music provider's rows and searches (MusicEngine), played there; without one, what's playing. */
    private static final int TAB_MUSIC = 3;
    private static final int LIVE_RED = 0xFFFF3B30;
    // The hero ends as far above the pills (Live TV, Music) as it does above the cards elsewhere: lifted by the
    // distance from the pills to where the cards usually start.
    private static final float L_CHIPS_TOP = 524, L_CHIP_H = 46, LIVE_LIFT = 596 - L_CHIPS_TOP;
    /** The small caps label over every hero: this far above the top of the title (its capitals, logo or wordmark). */
    private static final float HERO_LABEL_GAP = 40;
    /**
     * The Music tab's square covers (MUSIC_CARD px), with the song's title and artist under each, end above the apps:
     * its hero, pills and row sit MUSIC_RAISE px higher to make room for them.
     */
    private static final float MUSIC_CARD = 196, MUSIC_RAISE = 70;
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_CHIPS = 6;
    /** The now-playing pill at the top right, between the tabs and the round buttons (while music plays). */
    private static final int Z_NOW = 7;

    private final Activity activity;
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return (id - 1) / 1000 == GRID && canToggleShelf((id - 1) % 1000); }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, nowLayer, status, tiles, drawerLayer, sheetLayer, toastLayer;
    private int zone = Z_SHELF;

    private Shader skyGradient;   // no photo: a dark gradient
    // The drawer's glass: smoked, with a light rim.
    private final Paint glassFill = new Paint(Paint.ANTI_ALIAS_FLAG), glassRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    // picksOn(): whether there's a TMDB key (or TV picks), read again when a setting changes.
    private int keyVersion = Integer.MIN_VALUE;
    private boolean hasKey;

    // The selected tab, the focused item at the top (the tabs, then the round buttons) and the focused card.
    private int tab, topIndex, cardIndex;
    private List<Recs.Pick> cards = new ArrayList<>();
    // The tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[MAX_TABS + HomeApps.MAX_TOP];
    private final RectF[] topRects = new RectF[MAX_TABS + HomeApps.MAX_TOP];
    private int[] tabOrder = {0, 1, 2, 3};
    private int tabOrderVersion = -1;
    // One per card in the row (grown with it, ensureCards): unscrolled rects; cardRect(i) is where one is on screen.
    private Spring[] cardFocus = new Spring[0];
    private RectF[] cardRects = new RectF[0];
    // Each card's title and artist cut to its width, made again when the pick, its title or the width changes.
    private Recs.Pick[] cardTextPick = new Recs.Pick[0];
    private String[] cardTextSource = new String[0], cardTitleText = new String[0], cardArtistText = new String[0];
    private float[] cardTextW = new float[0];
    private float cardLeft, cardTop, cardW, cardH, cardGap, cardWide;
    // The row scrolls sideways (Explore loads more as it does): how far, in px.
    private final Spring rowScroll = new Spring(0.36f, 0.9f, 0);
    private final RectF rowRect = new RectF(), cardBase = new RectF();
    // 0: focus on the titles (big cards, small app icons); 1: focus on the apps (small cards, big app cards).
    private final Spring appsExpand = new Spring(0.42f, 0.92f, 0);
    // The apps row scrolls sideways too when it has focus and its cards no longer fit (many pinned apps).
    private final Spring shelfScroll = new Spring(0.34f, 1f, 0);
    private final RectF pillsRect = new RectF(), chipRect = new RectF();
    private LinearGradient appSheen, appRim;
    private final Matrix appShaderMatrix = new Matrix();
    private final Map<Integer, RadialGradient> appGlows = new LinkedHashMap<>();
    // The apps row's glow cross-fades when the focused app changes (the whole screen is its color).
    private HomeApps.Item glowItem, glowFrom;
    private long glowAt;
    private boolean glowing;
    // The hero's title in text (no logo): made again only when the focused pick changes.
    private Recs.Pick heroTextPick;
    private StaticLayout heroTextLayout;
    // The hero's text made once per pick (and language): its label, its details cut to fit.
    private Recs.Pick heroMetaPick;
    private String heroMetaSource, heroLabel = "";
    private int heroMetaGeneration = -1;
    private String upperSource, upperText = "";
    private int upperGeneration = -1;
    private String musicLabelName, musicLabelRow, musicLabel = "";
    private int musicLabelGeneration = -1;
    private Live.Show liveNext;
    private String liveNextText = "";
    private int liveNextGeneration = -1;
    private HomeApps.Item appLineItem;
    private int appLineCount = -1, appLineGeneration = -1;
    private String appLine = "";
    private Paint topClock, chipText, chipDim, appsHeading, pillText, pillTextOn, liveTag;
    // Live TV: the group pills, the selected one, and the channels' logos (small, loaded once).
    private List<String> liveGroups = new ArrayList<>();
    private int liveGroup;
    // Music: its pills (Search, the provider's rows, then the saved searches), what each one lists
    // (musicSources: a shelf's id, or "saved:" and the query), the one selected (the first shelf at first), the search.
    private final List<String> musicChips = new ArrayList<>(), musicSources = new ArrayList<>();
    private int musicRow = 1;
    private String musicQuery;
    private List<MusicEngine.Result> musicResults;
    private static final String SAVED = "saved:";
    private static final int MUSIC_SEARCH = 0;
    // The song just asked of the engine (the pill shows it, loading, until it plays), the one playing then, and when.
    private Music.Track pendingSong;
    private String pendingFrom;
    private long pendingAt;
    private static final long PENDING_MS = 60_000;
    private float nowTextRoom = -1, nowTextW;
    private int nowSplit;   // where the title ends in nowText (the artist after it is drawn dimmer)
    // Where the hero layer left room for the pill (its right edge and center line); drawn in its own small view.
    private float nowRight, nowCy;
    private final Paint spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        spinner.setStyle(Paint.Style.STROKE);
        spinner.setStrokeCap(Paint.Cap.ROUND);
    }
    // The shelves and saved searches shown, MUSIC_SHOWN at a time from musicFirst (the window follows the selected one).
    private int musicFirst = 1;
    private static final int MUSIC_SHOWN = 5;
    // The pills' spacing on both tabs: word pills CHIP_GAP apart; Live TV's letters closer, one index (A to Z fits).
    private static final float CHIP_GAP = 18, LETTER_GAP = 8;
    private final RectF[] chipRects = new RectF[40];
    // Each pill's label and text width, measured again when its label or selection changes.
    private final String[] chipLabels = new String[40];
    private final boolean[] chipOn = new boolean[40];
    private final float[] chipTextW = new float[40];
    // Channel logos in memory, the least recently drawn dropped past LOGOS (they stay in storage: Live.store).
    private static final int LOGOS = 64;
    private final Map<String, Bitmap> liveLogos = new LinkedHashMap<String, Bitmap>(LOGOS, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > LOGOS;
        }
    };
    // Ambient: after AMBIENT_DELAY on a title its backdrop starts to drift (a slow zoom and pan) and every SLIDE_MS
    // cross-fades to the next of the title's other backdrops (Recs.alternates), each with its own drift.
    private static final long AMBIENT_DELAY = 2500, SLIDE_MS = 9000, SLIDE_FADE_MS = 1600, EXIT_FADE_MS = 450;
    private static final float DRIFT_ZOOM = 0.08f;
    private Recs.Pick ambientPick;
    private boolean ambient;
    private List<String> ambientPaths;
    private int slideCount;                 // slides shown so far (picks the next path and the drift's direction)
    private Bitmap slideNow, slidePrev, slideExit;
    private long slideAt, slidePrevAt, exitAt;
    private int slidePrevN;
    private final Matrix driftMatrix = new Matrix(), exitMatrix = new Matrix();
    private final Runnable ambientStart = this::startAmbient;
    // The full-screen photo that last arrived and when, for its fade-in over the blurred version.
    private Bitmap photoShown;
    private long photoAt;
    private static final float PHOTO_FADE_MS = 420, LOGO_MIN_H = 64;
    private String heroMeta = "";
    private LinearGradient scrimLeft, scrimBottom;
    private float scrimW;
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTab, lTabOn, lCardTitle, lCardArtist, lTip, lDate, lClock, lWeather;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private int shelfIndex;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Background
    private long bgFadeAt;
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    // Each button's name, cut and sized for it at rest (drawn scaled with the button).
    private String[] drawerText = new String[0];
    private float[] drawerTextSize = new float[0];
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean idle;
    private long lastNanos, resumedAt;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    // The weather chip's degrees and widths, made again when the weather or a setting changes.
    private String chipDegrees = "";
    private long chipDegreesAt = -1;
    private int chipDegreesPrefs = -1;
    private float chipDegreesW, chipCityW;
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint;
    private Paint rowTitle, drawerLabel;
    private Paint sheetTitle, sheetHint;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Shadows shadows = new Shadows();
    // A shader per image, the last SHADERS used (more than a screen holds). Weak ones were collected at every GC and
    // made again on the next frame, whose native memory set off the next GC: the moves stuttered. A shader holds its
    // bitmap, so the cap is what lets old images go.
    private static final int SHADERS = 64;
    private final Map<Bitmap, BitmapShader> imageShaders = new LinkedHashMap<Bitmap, BitmapShader>(SHADERS, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Bitmap, BitmapShader> eldest) {
            return size() > SHADERS;
        }
    };
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Line icons (24-unit boxes, scaled when drawn): the Music tab's note, the Search pill's magnifier.
    private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path searchPath = new Path();
    // The now-playing pill: its place, its focus, the cover as a round thumbnail.
    private final RectF nowRect = new RectF();
    private final Spring nowFocus = new Spring(0.26f, 0.85f, 0);
    private final Matrix nowMatrix = new Matrix();
    private BitmapShader nowShader;
    private Bitmap nowShaderOf;
    private MusicArt.Scene nowScene;
    private Music.Track nowSceneOf;   // the track nowScene was asked for (null: ask again)
    private final Paint nowCover = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private String nowText = "";
    private Music.Track nowTrack;
    private final MusicArt.Ready nowReady = this::nowArtReady;
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient cardSheen;
    private float cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];

    /** One layer of the screen. It draws in full-screen coordinates: the frosted glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case NOW: drawNowPill(c); break;
                case STATUS: drawLightButtons(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                default: drawToast(c); break;
            }
        }
    }

    private final Runnable reloadSoon = this::reload;
    private final Runnable barsLater = this::barsFrame;   // one pending frame of the bars (see MusicSaverView.later)
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePicks();
            if (updateClock()) heroDirty = true;   // the time in the top bar
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        nowLayer = new Layer(a, NOW);
        status = new Layer(a, STATUS);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        // No GPU layers: on these GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every frame
        // cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame).
        for (View v : new View[] {sky, heroLayer, nowLayer, status, tiles, drawerLayer, sheetLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Idiot Box");
        drawerLayer.setVisibility(INVISIBLE);
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeCap(Paint.Cap.ROUND);
        iconStroke.setStrokeJoin(Paint.Join.ROUND);
        searchPath.addCircle(11, 11, 7, Path.Direction.CW);
        searchPath.moveTo(20, 20);
        searchPath.lineTo(16.2f, 16.2f);
        watermark.setStyle(Paint.Style.STROKE);
        watermark.setStrokeCap(Paint.Cap.ROUND);
        watermark.setColor(Ui.alpha(0xFFF5C451, 0.6f));
        glassFill.setColor(Ui.alpha(SMOKE, 0.4f));
        glassRim.setStyle(Paint.Style.STROKE);
        glassRim.setColor(0x33FFFFFF);
        glassRim.setStrokeWidth(1.5f);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        ensureCards(32);
        setLightButtons(HomeApps.topButtons());
        sheetLayer.setVisibility(INVISIBLE);
        // First time: right away (a cold start opens with the apps already there); missing logos and the drawer's
        // inputs come from reload().
        setShelf(HomeApps.shelf(a, false));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        keyVersion = Integer.MIN_VALUE;   // a TV picks permission may have come while away
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        data.addListener(this);
        recs.addListener(this);
        Music.addListener(musicChanged);
        data.refreshIfStale();
        recs.refreshIfStale();
        readTabs();   // Settings > Tabs may have changed while away
        Live.refresh(getContext(), this::liveFetched);   // ready before the Live TV tab is opened
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        if (!again) reload();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        Music.removeListener(musicChanged);
        handler.removeCallbacks(ambientStart);
        ambientPick = null;
        ambient = false;
        slideNow = slidePrev = slideExit = null;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Refresh, Settings), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        lightButtons.addAll(list);
        if (zone == Z_TOP && topIndex >= tabCount() + lightButtons.size()) {
            topIndex = tabCount() - 1 + lightButtons.size();
            if (topIndex < tabCount() && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
            else if (topIndex < tabCount()) selectTab(topIndex);
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("Continue watching"), Ui.tr("Explore"), Ui.tr("Live TV"), Ui.tr("Music")};
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    private int tabCount() {
        return tabOrder.length;
    }

    /** The selected tab's position in the bar. */
    private int tabPos() {
        for (int i = 0; i < tabOrder.length; i++) if (tabOrder[i] == tab) return i;
        return 0;
    }

    /** Settings > Tabs, read again when a setting changed: the selected tab stays if it's still shown. */
    private void readTabs() {
        if (tabOrderVersion == Prefs.version) return;
        tabOrderVersion = Prefs.version;
        int[] order = Prefs.tabs();
        if (java.util.Arrays.equals(order, tabOrder)) return;
        tabOrder = order;
        boolean shown = false;
        for (int t : order) shown |= t == tab;
        if (!shown && order.length > 0) {
            tab = order[0];
            rowShape();
            cardIndex = 0;
            rowScroll.snap(0);
        }
        if (zone == Z_TOP || zone == Z_CHIPS || zone == Z_NOW) zone = Z_SHELF;
        layoutLight();
        if (order.length > 0) updateCards();
        retarget(true);
    }

    @Override public void onData() {
        wake();
        heroDirty = statusDirty = drawerDirty = true;
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, nowLayer, status, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(812 * kk));   // down to the apps: the row's titles and their descenders
        measure(nowLayer, width, Math.round(150 * kk));    // the top bar, where the now-playing pill is
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the grown tiles and their shadows).
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(nowLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        makePaints();
        appGlows.clear();   // their gradients were made at the old size
        glowing = false;
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutDrawer();
        layoutLight();
        nowSceneOf = null;   // its cover is made at the UI's size
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTab = Ui.text(Ui.medium, 30 * k, 0xFF8E8E93);
        lTabOn = Ui.text(Ui.semibold, 30 * k, WHITE);
        lCardTitle = Ui.text(Ui.semibold, 26 * k, WHITE);
        lCardTitle.setLetterSpacing(0.012f);
        lCardArtist = Ui.text(Ui.medium, 22 * k, 0xFF98989D);
        topClock = Ui.numbers(Ui.semibold, 30 * k, 0xFFF4F4F6);
        pillText = Ui.text(Ui.medium, 20 * k, 0xCCFFFFFF);
        pillTextOn = Ui.text(Ui.semibold, 20 * k, 0xFF0A0B0F);
        liveTag = Ui.text(Ui.semibold, 15 * k, 0xFFFFFFFF);
        liveTag.setLetterSpacing(0.08f);
        appsHeading = Ui.text(Ui.semibold, 44 * k, 0xFFF4F4F6);
        chipText = Ui.text(Ui.semibold, 21 * k, 0xFFF4F4F6);
        chipDim = Ui.text(Ui.medium, 21 * k, 0xBFFFFFFF);
        lTip = Ui.text(Ui.semibold, 25 * k, WHITE);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, 0xFF98989D);
        lClock = Ui.numbers(Ui.light, 200 * k, WHITE);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, 0xFF98989D);
        skyGradient = null;
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        sheetHint = Ui.text(Ui.regular, 22 * k, 0x8CFFFFFF);
        sheetHint.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
        watermark.setStrokeWidth(4.5f * k);
        // Text measured with the old paints.
        chipDegreesAt = -1;
        java.util.Arrays.fill(chipLabels, null);
        nowTextRoom = -1;
        heroTextPick = null;
        heroMetaPick = null;
        cardTextPick = new Recs.Pick[cardTextPick.length];
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        appsExpand.target = !shelf.isEmpty() && (zone == Z_SHELF || zone == Z_DRAWER || drawer.target > 0) ? 1 : 0;
        boolean expanding = appsExpand.step(dt);
        boolean shelfMoving = shelfScroll.step(dt);
        tilesMoving |= shelfMoving;
        if (expanding || shelfMoving) {
            layoutShelf();
            tilesDirty = statusDirty = true;
            sky.invalidate();
        }
        boolean topMoving = step(topFocus, dt) | nowFocus.step(dt), cardsMoving = step(cardFocus, dt) | rowScroll.step(dt) | expanding;
        if (topMoving) status.invalidate();
        boolean heroMoving = topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean drifting = tickAmbient(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the views: their content isn't recorded again (redrawing on every
        // frame made the TV drop frames). The top buttons fade out as they rise; the drawer is drawn resting in place.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || bgFading) invalidateHero();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) heroLayer.invalidate();   // the time at the top right, or the big clock without picks

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving || sheetMoving
                || toastFading || bgFading || drifting || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    /** The hero layer and the now-playing pill over it (its glass shows what's behind, which the hero moves with). */
    private void invalidateHero() {
        heroLayer.invalidate();
        nowLayer.invalidate();
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - Ui.smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(nowLayer, top, lift);
        show(status, top, lift);
        show(tiles, home, lift);
        float da = home * Ui.smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || labelPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    // MARK: - The row and the background

    /** The row and the background (when Recs notifies, and every 15 s: Live TV's guide moves on), once sized. */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        if (fadeEase(bgFadeAt, up) >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
        }
        return true;
    }

    private static float fadeEase(long start, long up) {
        return Ui.smoothstep(0, FADE_MS, up - start);
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
        drawWatermark(c);
    }

    private final Paint watermark = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** The logo's flat amber mouth, small and faint in the bottom right corner, under everything else. */
    private void drawWatermark(Canvas c) {
        float len = 22 * k, right = w - 32 * k, y = h - 28 * k;
        c.drawLine(right - len, y, right, y, watermark);
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
    }

    /**
     * The apps row: at rest, small square icons; with focus on it, 16:9 cards the size of the titles' (the apps' TV
     * art fills them). From the left margin, bottom-aligned, in between while the row grows; scrolled sideways when
     * the grown cards no longer fit (many pinned apps), so the focused one stays on screen.
     */
    private void layoutShelf() {
        int n = shelf.size();
        float e = appsExpand.value;
        float big = cardWide > 0 ? cardWide : 273 * k;
        float tw = lerp(L_ICON * k, big, e), th = lerp(L_ICON * k, L_CARD_H * k, e), gap = lerp(L_ICON_GAP * k, L_CARD_GAP * k, e);
        float step = tw + gap;
        // The row's cards no longer fit when it grows: scroll so the focused app stays on screen (one in from the left,
        // as the cards row does), and back to the margin when they fit again.
        int shown = Math.max(1, (int) (((CONTENT_R - MARGIN) * k + gap) / step));
        int last = Math.max(0, n - shown);
        shelfScroll.target = Math.min(Math.max(0, Math.min(shelfIndex, n - 1) - 1), last);
        float bottom = L_APPS_BOTTOM * k, x = MARGIN * k - shelfScroll.value * step;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, bottom - th, x + tw, bottom);
            x += step;
        }
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
    }

    /**
     * An app in the apps row. At rest: its icon on a card of its own color. With focus on the row: its TV art filling a
     * 16:9 card like the titles' (TizenTube: YouTube's red with the wordmark). The focused one grows and gets the rim.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;   // lifted to be moved
        float e = appsExpand.value, art = Ui.smoothstep(0.25f, 0.75f, e);
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + lerp(0.14f, 0.05f, e) * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float r0 = lerp(TILE_RADIUS, 18, e) * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * f;
        scaleAround(cell, base, s, cx, cy);
        float r = r0 * s;
        dropShadow(c, base, s, cx, cy, r0, (6 + 12 * f + 6 * l) * k, DEEP_BLUR * k, Ui.alpha(0xFF000000, 0.28f + 0.26f * fc));
        // The card: the app's color.
        fill.setShader(null);
        fill.setColor(Ui.blend(it.tint, 0xFF0A0B0F, 0.35f));
        c.drawRoundRect(cell, r, r, fill);
        if (art > 0.01f) {
            if (it.tile != null) {
                roundImage(c, it.tile, cell, r, true, art);
            } else if (it.logo != null) {
                float sc = Math.min(cell.width() * 0.6f / it.logo.getWidth(), cell.height() * 0.45f / it.logo.getHeight());
                float lw = it.logo.getWidth() * sc, lh = it.logo.getHeight() * sc;
                tmp.set(cell.centerX() - lw / 2, cell.centerY() - lh / 2, cell.centerX() + lw / 2, cell.centerY() + lh / 2);
                bitmapPaint.setAlpha(Math.round(255 * art));
                c.drawBitmap(it.logo, null, tmp, bitmapPaint);
                bitmapPaint.setAlpha(255);
            }
        }
        if (art < 0.99f) {
            float size = Math.min(cell.width(), cell.height()) * 0.66f, a = 1 - art;
            if (it.icon != null) {
                tmp.set(cell.centerX() - size / 2, cell.centerY() - size / 2, cell.centerX() + size / 2, cell.centerY() + size / 2);
                bitmapPaint.setAlpha(Math.round(255 * a));
                c.drawBitmap(it.icon, null, tmp, bitmapPaint);
                bitmapPaint.setAlpha(255);
            } else if (it.symbol != null) {
                Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), size * 0.5f, Ui.alpha(0xFFFFFFFF, a));
            }
        }
        if (fc > 0.01f) {
            // Focused: the title cards' light, a sheen from the top-left corner and a rim brighter at the top. The
            // gradients are made once for a 1×1 card and stretched over this one (it changes size as the row grows).
            if (appSheen == null) {
                appSheen = new LinearGradient(0, 0, 0.5f, 1, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                appRim = new LinearGradient(0, 0, 0, 1, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
            }
            appShaderMatrix.setScale(cell.width(), cell.height());
            appShaderMatrix.postTranslate(cell.left, cell.top);
            appSheen.setLocalMatrix(appShaderMatrix);
            appRim.setLocalMatrix(appShaderMatrix);
            int alpha = Math.round(255 * fc);
            sheen.setShader(appSheen);
            sheen.setAlpha(alpha);
            c.drawRoundRect(cell, r, r, sheen);
            sheen.setAlpha(255);
            rim.setShader(appRim);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setAlpha(255);
            rim.setShader(null);
        }
    }

    /** `b` scaled to fit inside w × h, centered on (cx, cy). */
    private void drawFitted(Canvas c, Bitmap b, float cx, float cy, float w, float h) {
        float sc = Math.min(w / b.getWidth(), h / b.getHeight());
        float bw = b.getWidth() * sc, bh = b.getHeight() * sc;
        tmp.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
        bitmapPaint.setAlpha(255);
        c.drawBitmap(b, null, tmp, bitmapPaint);
    }

    /** `dst`: `src` grown by s around (cx, cy). */
    private static void scaleAround(RectF dst, RectF src, float s, float cx, float cy) {
        float hw = src.width() * s / 2, hh = src.height() * s / 2;
        dst.set(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    /**
     * The shadow of `base` grown by s around (cx, cy) and dropped by `drop`: drawn at the base size on a scaled canvas,
     * so the corner radius stays the same through a focus animation and Shadows makes its mask once.
     */
    private void dropShadow(Canvas c, RectF base, float s, float cx, float cy, float radius, float drop, float blur, int color) {
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        float hw = base.width() / 2, hh = base.height() / 2, dy = drop / s;
        tmp.set(-hw, -hh + dy, hw, hh + dy);
        shadows.draw(c, tmp, radius, blur, color);
        c.restore();
    }

    /**
     * `image` over `cell` with corners of radius `r`, stretched or (`crop`) center-cropped to its shape. The image is the
     * shader of a rounded rectangle: an anti-aliased clip costs these GPUs a mask on every frame of a focus animation
     * (measured on a Mi Box 3: most of a 49 ms frame).
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r, boolean crop, float alpha) {
        float iw = image.getWidth(), ih = image.getHeight();
        float sx = cell.width() / iw, sy = cell.height() / ih;
        if (crop) sx = sy = Math.max(sx, sy);
        imageMatrix.setScale(sx, sy);
        imageMatrix.postTranslate(cell.centerX() - iw * sx / 2, cell.centerY() - ih * sy / 2);
        drawShaded(c, image, r, cell, alpha);
    }

    /** `image`, already placed by imageMatrix, through the rounded rectangle `r`. */
    private void drawShaded(Canvas c, Bitmap image, float radius, RectF r, float alpha) {
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        imageFill.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(r, radius, radius, imageFill);
        imageFill.setShader(null);
        imageFill.setAlpha(255);
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        return HomeApps.allInputs(c);   // with the weather; All Apps is on the shelf, Settings at the top
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        drawerText = new String[n];
        drawerTextSize = new float[n];
        drawerDirty = true;
    }

    /** The drawer: the inputs, on a TV that has them. */
    private boolean hasDrawer() {
        return !drawerItems.isEmpty();
    }

    private void openDrawer() {
        if (!hasDrawer()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Smoked glass for a drawer button, with a light rim. */
    private void drawerGlass(Canvas c, RectF r, float radius) {
        c.drawRoundRect(r, radius, radius, glassFill);
        c.drawRoundRect(r, radius, radius, glassRim);
    }

    private void drawDrawer(Canvas c) {
        if (!hasDrawer()) return;
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        scaleAround(cell, base, s, cx, cy);
        float r0 = 20 * k, r = r0 * s;
        if (f > 0.01f) dropShadow(c, base, s, cx, cy, r0, 12 * k * f, SHADOW_BLUR * k, Ui.alpha(0xFF000000, 0.45f * f));
        if (f < 0.99f) drawerGlass(c, cell, r);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = Ui.blend(off ? 0x6BFFFFFF : 0xF2FFFFFF, INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        if (drawerText[i] == null) {
            // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
            float room = base.width() - 16 * k, size = 23 * k;
            drawerLabel.setTextSize(size);
            float wide = drawerLabel.measureText(it.label);
            if (wide > room) size = Math.max(18 * k, size * room / wide);
            fitPaint.set(drawerLabel);
            fitPaint.setTextSize(size);
            drawerText[i] = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
            drawerTextSize[i] = size;
        }
        // The name at its resting size, scaled with the button.
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(drawerTextSize[i]);
        c.save();
        c.scale(s, s, cx, cy);
        c.drawText(drawerText[i], cx, cy + base.height() * 0.3f, drawerLabel);
        c.restore();
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the top buttons).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        if (tilesChanged) {
            heroDirty = statusDirty = true;     // the hero, the background and the frost show the focused app
            sky.invalidate();
        }
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        float nowTarget = !sheetOpen && zone == Z_NOW ? 1 : 0;
        topChanged |= nowFocus.target != nowTarget;
        nowFocus.target = nowTarget;
        if (snap) nowFocus.snap(nowTarget);
        boolean heroChanged = aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || topChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        layoutShelf();   // the apps row scrolls sideways to keep the focused app on screen
        spoken.focusMoved();
        ambientCheck();
    }

    /** The title in focus changed (or focus left the row): stop the drift, and start the new title's after a moment. */
    private void ambientCheck() {
        Recs.Pick p = running && zone == Z_CARDS && !sheetOpen && !drawerOpen ? focusedPick() : null;
        if (p == ambientPick) return;
        ambientPick = p;
        handler.removeCallbacks(ambientStart);
        stopAmbient();
        if (p != null) handler.postDelayed(ambientStart, AMBIENT_DELAY);
    }

    private void startAmbient() {
        Recs.Pick p = ambientPick;
        if (p == null || p != focusedPick() || zone != Z_CARDS) return;
        Backdrop b = recs.backdrop(p);
        Bitmap first = b == null ? null : b.bg;
        if (first == null || b != bgTo || bgFadeAt != 0) {
            handler.postDelayed(ambientStart, 500);   // its full-screen photo isn't showing yet
            return;
        }
        ambient = true;
        slideNow = first;
        slideAt = SystemClock.uptimeMillis();
        slideCount = 0;
        slidePrev = null;
        ambientPaths = recs.alternates(p);
        if (ambientPaths != null && !ambientPaths.isEmpty()) recs.slide(ambientPaths.get(0));
        wake();
    }

    /** Back to the still backdrop: the drifting frame fades out over it, where it was. */
    private void stopAmbient() {
        if (!ambient) return;
        ambient = false;
        if (slideNow != null) {
            slideExit = slideNow;
            exitMatrix.set(drift(slideNow, slideAt, slideCount, SystemClock.uptimeMillis()));
            exitAt = SystemClock.uptimeMillis();
        }
        slideNow = slidePrev = null;
        sky.invalidate();
        wake();
    }

    /** Advances the slides; true while it runs (the background is redrawn on every frame). */
    private boolean tickAmbient(long up) {
        boolean exiting = slideExit != null;
        if (exiting && up - exitAt >= EXIT_FADE_MS) slideExit = null;
        if (!ambient) {
            if (exiting) sky.invalidate();
            return exiting;
        }
        if (ambientPaths == null) ambientPaths = recs.alternates(ambientPick);
        if (slidePrev != null && up - slideAt >= SLIDE_FADE_MS) slidePrev = null;
        if (up - slideAt >= SLIDE_MS && ambientPaths != null && !ambientPaths.isEmpty()) {
            String path = ambientPaths.get(slideCount % ambientPaths.size());
            Bitmap next = recs.slide(path);
            if (next != null) {
                slidePrev = slideNow;
                slidePrevAt = slideAt;
                slidePrevN = slideCount;
                slideNow = next;
                slideAt = up;
                slideCount++;
                recs.slide(ambientPaths.get(slideCount % ambientPaths.size()));   // the one after, ready in time
            }
        }
        sky.invalidate();
        return true;
    }

    /**
     * Where a slide is at time `up`: filling the screen, zooming in by DRIFT_ZOOM over its life while it pans toward a
     * corner (each slide a different one), never showing an edge.
     */
    private Matrix drift(Bitmap b, long start, int n, long up) {
        float u = Ui.clamp((up - start) / (float) (SLIDE_MS + SLIDE_FADE_MS));
        float scale = 1 + DRIFT_ZOOM * u;
        float dirX = n % 2 == 0 ? -1 : 1, dirY = n % 3 == 0 ? -1 : 1;
        float tx = dirX * 0.03f * w * u, ty = dirY * 0.02f * h * u;
        driftMatrix.setScale(w * scale / b.getWidth(), h * scale / b.getHeight());
        driftMatrix.postTranslate(-(w * scale - w) / 2 + tx, -(h * scale - h) / 2 + ty);
        return driftMatrix;
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        c.drawText(Ui.tr("Hold OK: on or off the shelf · ☰ Menu: uninstall"), w / 2f, 166 * k - shift, sheetHint);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        for (int i = 0; i < n; i++) {
            gridCellRect(i, scroll.value, gridCell);
            if (gridCell.top > h || gridCell.top + GRID_ROW * k < 0) continue;
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(Ui.blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** Where the sheet's cell i is, with the grid scrolled by `rows`. */
    private void gridCellRect(int i, float rows, RectF out) {
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
        float y = (GRID_TOP + (i / COLUMNS - rows) * GRID_ROW) * k;
        out.set(x, y, x + GRID_W * k, y + GRID_H * k);
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        scaleAround(cell, base, s, cx, cy);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) dropShadow(c, base, s, cx, cy, radius, 16 * k * f, SHADOW_BLUR * k, Ui.alpha(0xFF000000, 0.55f * fc));
        if (image != null) roundImage(c, image, cell, r, false, 1);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        // Under the tab bar (L_TABBAR_TOP + L_TABBAR_H), clear of it and the now-playing chip, above the hero's text.
        float toastTop = (L_TABBAR_TOP + L_TABBAR_H + 24) * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        // Mid-fade through one small layer, as the tab bar.
        int layer = a < 0.99f ? c.saveLayerAlpha(tmp.left, tmp.top, tmp.right, tmp.bottom, Math.round(255 * a)) : -1;
        if (layer >= 0) a = 1;
        float r = Math.min(tmp.height() / 2, 32 * k);
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF000000, a));   // solid black: readable over any photo
        c.drawRoundRect(tmp, r, r, fill);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, Ui.alpha(0xEBFFFFFF, a));
        int save = c.save();
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toastPaint.setColor(Ui.alpha(0xF2FFFFFF, a));
        toast.draw(c);
        c.restoreToCount(save);
        if (layer >= 0) c.restoreToCount(layer);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons() : null;   // they change with Prefs too
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                if (s != null) setShelf(s);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appRemoved(pkg); appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        recs.appsChanged();   // the rows' installed apps are asked again
        if (running) reloadSoon();
    }

    // MARK: - Keys: the arrows, OK, Back, and Menu in All Apps (other keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            disarmHold();      // another key while OK is held: no move mode
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf. A timer, since
                // not every remote repeats a held key, or the long-press repeat, whichever comes first; the release
                // after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder() || canFavourite() || canSaveRow();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    disarmHold();
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                // As on Amazon's home screen: Menu on an app offers to uninstall it.
                if (!sheetOpen) return false;
                if (down && e.getRepeatCount() == 0) uninstallFocused();
                return true;
            default:
                return false;
        }
    }

    /**
     * Asks Android to uninstall the focused app in All Apps: its own dialog confirms (or not). Apps that came with the
     * TV can't be: a toast says so. The grid loses the app as soon as it's gone (onPackageRemoved).
     */
    private void uninstallFocused() {
        if (!canToggleShelf(gridIndex)) return;   // an app (not Settings or an input)
        HomeApps.Item it = others.get(gridIndex);
        try {
            android.content.pm.ApplicationInfo info = getContext().getPackageManager().getApplicationInfo(it.pkg, 0);
            if ((info.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                showToast(Ui.trf("{app} came with the TV and can't be uninstalled.", "app", it.label));
                return;
            }
            getContext().startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", it.pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            click.open();
        } catch (Exception e) {
            AppLog.w("couldn't uninstall " + it.pkg + ": " + e.getMessage());
            showToast(Ui.trf("Couldn't uninstall {app}.", "app", it.label));
        }
    }

    /** An app went: out of All Apps at once, if it's open (the rest of the screen reloads as usual). */
    private void appRemoved(String pkg) {
        if (!sheetOpen) return;
        List<HomeApps.Item> left = new ArrayList<>();
        for (HomeApps.Item it : others) if (!pkg.equals(it.pkg)) left.add(it);
        if (left.size() != others.size()) {
            setOthers(left);
            shelfEdited = true;   // the shelf reloads on close, without it
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else {   // down (moveLight takes up)
                    if (!hasDrawer()) return;
                    openDrawer();
                }
                break;
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        return nearest(rects, rects.length, 0, x);
    }

    /** Of the first `count` rects, shifted left by `shift`, the one whose center is nearest to x. */
    private static int nearest(RectF[] rects, int count, float shift, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - shift - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < tabCount()) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - tabCount());
            r = topRects[topIndex];
        } else if (zone == Z_CHIPS) {
            if (tab == TAB_MUSIC && musicRow == MUSIC_SEARCH) openMusicSearch();
            return;
        } else if (zone == Z_NOW) {
            click.open();
            activity.startActivity(new Intent(activity, MusicSaverActivity.class));
            return;
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p != null && p.song != null) {
                playMusic();
                return;
            }
            if (p != null && p.channel != null) {
                if (playInJustPlayer(p.channel)) return;
                // Live TV without Just Player: full screen in our own player; Up and Down there walk this row.
                ArrayList<String> urls = new ArrayList<>();
                for (Recs.Pick q : cards) if (q.channel != null) urls.add(q.channel.url);
                Intent play = new Intent(activity, LivePlayerActivity.class)
                        .putStringArrayListExtra(LivePlayerActivity.EXTRA_URLS, urls)
                        .putExtra(LivePlayerActivity.EXTRA_INDEX, urls.indexOf(p.channel.url));
                activity.startActivity(play);
                return;
            }
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = new RectF(cardRect(cardIndex));
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.REFRESH) {
            refreshTab();
            return;
        }
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move

    private void hold() {
        if (sheetOpen) toggleShelf();
        else if (canFavourite()) toggleFavourite();
        else if (canSaveRow()) toggleRow();
        else startReorder();
    }

    /** Just Player (open source, ExoPlayer): plays live streams in their best quality, where Android's own player won't. */
    private static final String JUST_PLAYER = "com.brouken.player";

    /** The channel in Just Player, when it's on the TV; false otherwise (then our own player plays it). */
    private boolean playInJustPlayer(Live.Channel ch) {
        Intent play = new Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(ch.url), "video/*")
                .setPackage(JUST_PLAYER).putExtra("title", ch.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (play.resolveActivity(activity.getPackageManager()) == null) return false;
        try {
            activity.startActivity(play);
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            AppLog.w("live: Just Player didn't open " + ch.name + ": " + e.getMessage());
            return false;
        }
    }

    private boolean canFavourite() {
        Recs.Pick p = !sheetOpen && zone == Z_CARDS && tab == TAB_LIVE ? focusedPick() : null;
        return p != null && p.channel != null;
    }

    /** The hold timer and its arming are over (the hold happened, or another key came). */
    private void disarmHold() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
    }

    /** Held OK on a channel: in or out of Favourites, and a toast says which. */
    private void toggleFavourite() {
        disarmHold();
        okHeld = true;   // the release opens nothing
        Recs.Pick p = focusedPick();
        if (p == null || p.channel == null) return;
        boolean now = Live.toggleFavourite(getContext(), p.channel);
        showToast(now ? Ui.trf("{name} added to Favourites", "name", p.channel.name)
                : Ui.trf("{name} removed from Favourites", "name", p.channel.name));
        if (Live.FAVOURITES.equals(liveGroups.isEmpty() ? null : liveGroups.get(liveGroup))) updateCards();
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        disarmHold();
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= HomeApps.MAX_SHELF) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= HomeApps.MAX_SHELF) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Idiot Box › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 3) return false;   // two apps and All Apps
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        disarmHold();
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size() || shelf.get(j).kind != HomeApps.APP) return;   // All Apps stays last
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) if (it.kind == HomeApps.APP) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** WIDGETS: the weather chip at the top right, read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = picksOn() ? 0 : tabCount(); i < tabCount() + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        if (picksOn() && !Double.isNaN(data.weather.temp)) out.add(a11yId(WIDGETS, 0));
        for (int i = 0; i < cards.size(); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i >= tabCount()) return label(lightButtons, i - tabCount());
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[tabOrder[i]], "i", i + 1, "n", tabCount());
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return weatherText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID:
                if (!sheetOpen || i >= others.size()) return false;
                gridCellRect(i, scroll.target, a11yRect);
                dy = 0;
                break;
            case Z_TOP:
                if (i >= tabCount() + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < tabCount() ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= cards.size()) return false;
                a11yRect.set(cardRect(i));
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (chipRect.isEmpty()) return false;
                a11yRect.set(chipRect);
                dy = heroLayer.getTranslationY();
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            default: return -1;
        }
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case Z_TOP:
                if (i >= tabCount() + lightButtons.size() || (i < tabCount() && !picksOn())) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < tabCount()) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                cardMoved();
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather as TalkBack reads it, in the same words and units as on screen. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < hours; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    // MARK: - Tabs, hero, row and round buttons

    private void layoutLight() {
        if (lTab == null) return;
        // The tabs: pills inside a glass capsule (6 px inside it, 8 px apart, 28 px either side of the text).
        float pad = 28 * k, inset = 6 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k + inset;
        for (int i = 0; i < tabCount(); i++) {
            String name = tabs()[tabOrder[i]];
            float tw = Math.max(lTabOn.measureText(name), lTab.measureText(name));
            topRects[i].set(x, tabTop + inset, x + tw + 2 * pad, tabBottom - inset);
            x = topRects[i].right + 8 * k;
        }
        pillsRect.set(MARGIN * k, tabTop, x - 8 * k + inset, tabBottom);
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[tabCount() + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        float g = L_CARD_GAP * k, left = MARGIN * k;
        cardWide = ((CONTENT_R - MARGIN) * k - (Recs.ROW - 1) * g) / Recs.ROW;
        cardLeft = left;
        cardGap = g;
        rowShape();
        for (int i = 0; i < cardRects.length; i++) placeCard(i);
        scrollRow(true);
        heroDirty = statusDirty = true;
    }

    /** The row of the current tab and the background: the focused card's photo. */
    private void updateCards() {
        List<Recs.Pick> list = tab == TAB_LIVE ? livePicks() : tab == TAB_MUSIC ? musicPicks() : recs.row(tab);
        ensureCards(list.size());
        boolean same = samePicks(list, cards);
        if (!same || tab == TAB_LIVE) cards = list;   // Live TV's are made each time: what's on changes by the minute
        if (!same) {
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        preloadCards();
        scrollRow(false);
        showFocusedBackdrop();
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    /** The row from its first card (another tab, pill or search). */
    private void resetRow() {
        cardIndex = 0;
        rowScroll.snap(0);
        updateCards();
    }

    /** The tab at position `pos` in the bar becomes the selected one. */
    private void selectTab(int pos) {
        if (pos < 0 || pos >= tabCount() || tabOrder[pos] == tab) return;
        tab = tabOrder[pos];
        rowShape();
        resetRow();
    }

    /**
     * Live TV's row: the channels in the selected pill (A to Z), each a card with what's on now (the programme's image,
     * its title, how far in it is) from the guide.
     */
    private void liveFetched() {
        if (tab != TAB_LIVE) return;
        updateCards();
        wake();
    }

    private List<Recs.Pick> livePicks() {
        Live.refresh(getContext(), this::liveFetched);
        List<Live.Channel> all = Live.channels(getContext());
        liveGroups = Live.groups(all);
        liveGroup = Math.min(liveGroup, Math.max(0, liveGroups.size() - 1));
        List<Recs.Pick> out = new ArrayList<>();
        if (all.isEmpty()) return out;
        long now = System.currentTimeMillis();
        for (Live.Channel ch : Live.in(getContext(), all, liveGroups.get(liveGroup))) {
            Live.Show show = ch.now(now), next = ch.next(now);
            Recs.Pick p = new Recs.Pick();
            p.kind = "live";
            p.id = ch.url.hashCode();
            p.title = ch.name;
            p.app = ch.group;
            p.pkg = HomeMode.PKG;
            p.link = ch.url;
            p.channel = ch;
            p.backdrop = show != null && show.image != null && !show.image.isEmpty() ? show.image : null;
            if (show != null) {
                p.progress = Ui.clamp((now - show.start) / (float) Math.max(1, show.stop - show.start));
                p.left = show.stop - now;
            }
            List<String> parts = new ArrayList<>();
            if (show != null) {
                parts.add(Ui.time(show.start) + " – " + Ui.time(show.stop));
                parts.add(Ui.trf("{n} min left", "n", Math.max(1, Math.round(p.left / 60_000f))));
            }
            p.meta = Ui.join(" · ", parts);
            out.add(p);
        }
        return out;
    }

    /**
     * The Music tab's row: the selected pill's songs (a search's answer, or one of the provider's rows), each a
     * card with its picture; asked for in the background, the row filled in when the answer comes.
     */
    private List<Recs.Pick> musicPicks() {
        MusicPlayer player = MusicPlayer.get(getContext());
        musicChips.clear();
        musicSources.clear();
        musicChips.add(musicQuery == null ? Ui.tr("Search") : "\u201c" + musicQuery + "\u201d");
        musicSources.add("");
        for (MusicEngine.Shelf shelf : MusicEngine.shelves(getContext(), this::musicFound)) {
            musicChips.add(shelf.title);
            musicSources.add(shelf.id);
        }
        for (String query : player.rows()) {
            musicChips.add(query);
            musicSources.add(SAVED + query);
        }
        // Until the shelves come there's only Search: the first shelf stays the one chosen, unless another was picked.
        if (musicChips.size() > 1) musicRow = Math.max(0, Math.min(musicChips.size() - 1, musicRow));
        int row = Math.min(musicRow, musicChips.size() - 1);
        List<Recs.Pick> out = new ArrayList<>();
        if (!MusicEngine.available(getContext())) {
            musicResults = new ArrayList<>();
            return out;
        }
        if (row != musicRow) {   // the shelf chosen isn't there (yet): loading while the engine is asked
            musicResults = MusicEngine.shelvesLoading() ? null : new ArrayList<>();
            return out;
        }
        String source = musicSources.get(row);
        if (row == MUSIC_SEARCH) {
            musicResults = musicQuery == null ? new ArrayList<>() : MusicEngine.get(getContext(), MusicEngine.searchId(musicQuery), this::musicFound);
        } else if (source.startsWith(SAVED)) {
            musicResults = MusicEngine.get(getContext(), MusicEngine.searchId(source.substring(SAVED.length())), this::musicFound);
        } else {
            musicResults = MusicEngine.get(getContext(), source, this::musicFound);
        }
        if (musicResults == null) return out;
        for (MusicEngine.Result r : musicResults) {
            Recs.Pick p = new Recs.Pick();
            p.kind = "music";
            p.id = r.mediaId.hashCode();
            p.title = r.shownTitle();
            p.app = MusicEngine.name(getContext());
            p.backdrop = r.picture();
            p.song = r;
            p.meta = r.artist();
            out.add(p);
        }
        return out;
    }

    private void musicFound() {
        if (tab != TAB_MUSIC) return;
        updateCards();
        wake();
    }

    /** The pills between the hero and the row: Live TV's groups or the Music tab's searches. */
    private boolean chipsTab() {
        return tab == TAB_LIVE || tab == TAB_MUSIC;
    }

    private int chipCount() {
        return tab == TAB_MUSIC ? musicChips.size() : liveGroups.size();
    }

    private int chipSelected() {
        return tab == TAB_MUSIC ? musicRow : liveGroup;
    }

    private String chipLabel(int i) {
        return tab == TAB_MUSIC ? musicChips.get(i) : chipName(liveGroups.get(i));
    }

    private void selectChip(int i) {
        if (tab == TAB_MUSIC) musicRow = i;
        else liveGroup = i;
    }

    /**
     * The refresh button (at the top right): the selected tab's row fetched again now. Music asks the provider for its
     * shelves again; the others show what's fetched when it's in.
     */
    private void refreshTab() {
        if (tab == TAB_MUSIC) {
            MusicEngine.refresh();
            resetRow();
        } else if (tab == TAB_LIVE) {
            Live.refreshNow(getContext(), this::liveFetched);
        } else {
            recs.refreshNow(tab);
        }
        showToast(Ui.tr("Refreshing…"));
    }

    /** The first of the shelves and saved searches shown: the window of MUSIC_SHOWN moved to hold the selected one. */
    private void musicWindow() {
        int last = Math.max(1, chipCount() - MUSIC_SHOWN);
        if (musicRow >= 1 && musicRow < musicFirst) musicFirst = musicRow;
        if (musicRow >= musicFirst + MUSIC_SHOWN) musicFirst = musicRow - MUSIC_SHOWN + 1;
        musicFirst = Math.max(1, Math.min(last, musicFirst));
    }

    /** The Search pill: Idiot Box's keyboard; the answer becomes the Search row. */
    private void openMusicSearch() {
        MusicSearchActivity.asked = q -> {
            musicQuery = q;
            musicRow = MUSIC_SEARCH;
            resetRow();
        };
        activity.startActivity(new Intent(activity, MusicSearchActivity.class).putExtra(MusicSearchActivity.EXTRA_QUERY, musicQuery));
    }

    /** OK on a song: it plays in the engine, which chooses what comes after it (its radio; a row's songs are often the same song). */
    private void playMusic() {
        if (musicResults == null || musicResults.isEmpty()) return;
        click.open();
        MusicEngine.Result r = musicResults.get(Math.min(cardIndex, musicResults.size() - 1));
        MusicPlayer.get(getContext()).play(r);
        // The pill shows the song at once, loading, until it plays (the engine takes a while to start one).
        android.os.Bundle b = new android.os.Bundle();
        b.putString("title", r.title);
        b.putString("artist", r.line);
        b.putString("mediaId", r.videoId == null ? "" : r.videoId);
        b.putString("artUri", r.picture());
        Music.Track playing = Music.track();
        pendingSong = new Music.Track(MusicEngine.pkg(), b);
        if (!r.playlist) Music.asked(pendingSong);   // what the screens show if the app doesn't name it
        pendingFrom = playing == null ? null : playing.key();
        pendingAt = SystemClock.uptimeMillis();
        nowLayer.invalidate();
    }

    /**
     * The song asked of the engine that hasn't started yet, or null: it has once the engine plays it (that song, or any
     * other than the one playing when it was asked: a playlist's first song), or a minute on (it didn't).
     */
    private Music.Track pendingSong() {
        if (pendingSong == null) return null;
        Music.Track t = Music.track();
        boolean started = Music.playing() && t != null
                && (t.mediaId.equals(pendingSong.mediaId) || !t.key().equals(pendingFrom));
        if (started || SystemClock.uptimeMillis() - pendingAt > PENDING_MS) pendingSong = null;
        return pendingSong;
    }

    /** Hold OK on the Search pill saves the search as a row; on a saved row's pill, takes it away. */
    private boolean canSaveRow() {
        if (sheetOpen || zone != Z_CHIPS || tab != TAB_MUSIC) return false;
        return musicRow == MUSIC_SEARCH ? musicQuery != null : musicRow < musicSources.size() && musicSources.get(musicRow).startsWith(SAVED);
    }

    private void toggleRow() {
        disarmHold();
        okHeld = true;   // the release opens nothing
        String query = musicRow == MUSIC_SEARCH ? musicQuery : musicSources.get(musicRow).substring(SAVED.length());
        if (query == null) return;
        boolean now = MusicPlayer.get(getContext()).toggleRow(query);
        showToast(now ? Ui.trf("Saved as a row: {name}", "name", query) : Ui.trf("Row removed: {name}", "name", query));
        if (now) {
            musicRow = musicChips.size();   // the new row, at the end
        } else {
            musicRow = Math.max(MUSIC_SEARCH, musicRow - 1);
        }
        resetRow();
    }

    /** A channel's logo, small, from storage (fetched there first if new; null until then: the hero and cards redraw). */
    private Bitmap liveLogo(String url) {
        if (url == null || url.isEmpty()) return null;
        synchronized (liveLogos) {
            if (liveLogos.containsKey(url)) return liveLogos.get(url);
            liveLogos.put(url, null);
        }
        LOGO_LOADER.execute(() -> {
            Bitmap b = null;
            try {
                java.io.File f = Live.store(getContext(), url);
                Bitmap src = f == null ? null : android.graphics.BitmapFactory.decodeFile(f.getPath());
                if (src != null) {
                    float sc = Math.min(1f, Math.min(160f / src.getWidth(), 100f / src.getHeight()));
                    b = sc < 1 ? Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * sc)), Math.max(1, Math.round(src.getHeight() * sc)), true) : src;
                }
            } catch (RuntimeException | OutOfMemoryError ignored) {
            }
            final Bitmap ready = b;
            handler.post(() -> {
                synchronized (liveLogos) { liveLogos.put(url, ready); }
                heroDirty = true;
                wake();
            });
        });
        return null;
    }

    /** At least n cards' focus springs and rects (doubling, so a long row grows in a few steps). */
    private void ensureCards(int n) {
        if (n <= cardRects.length) return;
        int size = Math.max(n, cardRects.length * 2);
        Spring[] focus = java.util.Arrays.copyOf(cardFocus, size);
        RectF[] rects = java.util.Arrays.copyOf(cardRects, size);
        for (int i = cardRects.length; i < size; i++) {
            focus[i] = new Spring(0.32f, 0.78f, 0);
            rects[i] = new RectF();
        }
        int old = cardRects.length;
        cardFocus = focus;
        cardRects = rects;
        cardTextPick = java.util.Arrays.copyOf(cardTextPick, size);
        cardTextSource = java.util.Arrays.copyOf(cardTextSource, size);
        cardTitleText = java.util.Arrays.copyOf(cardTitleText, size);
        cardArtistText = java.util.Arrays.copyOf(cardArtistText, size);
        cardTextW = java.util.Arrays.copyOf(cardTextW, size);
        for (int i = old; i < size; i++) placeCard(i);
    }

    /**
     * The row's cards for this tab: square covers on the Music tab (a cover isn't cropped), a little higher up; 16:9
     * pictures elsewhere.
     */
    private void rowShape() {
        boolean music = tab == TAB_MUSIC;
        cardW = music ? MUSIC_CARD * k : cardWide;
        cardH = music ? MUSIC_CARD * k : L_CARD_H * k;
        cardTop = L_CARDS_TOP * k - musicRaise();
        for (int i = 0; i < cardRects.length; i++) placeCard(i);
    }

    /** How much higher the Music tab's hero, pills and row sit (0 on the other tabs). */
    private float musicRaise() {
        return tab == TAB_MUSIC ? MUSIC_RAISE * k : 0;
    }

    /** How many of the row's cards are on screen at once. */
    private int rowShown() {
        return cardW <= 0 ? Recs.ROW : Math.max(1, (int) (((CONTENT_R - MARGIN) * k + cardGap) / (cardW + cardGap)));
    }

    private void placeCard(int i) {
        float x = cardLeft + i * (cardW + cardGap);
        cardRects[i].set(x, cardTop, x + cardW, cardTop + cardH);
    }

    /** Where card i is on screen now: the row scrolled, big or small as the apps row has focus. Same RectF each call. */
    private RectF cardRect(int i) {
        float e = appsExpand.value;
        float small = tab == TAB_MUSIC ? L_SMALL_H : L_SMALL_W;
        float cw = lerp(cardRects[0].width(), small * k, e), ch = lerp(cardH, L_SMALL_H * k, e);
        float top = lerp(cardTop, L_SMALL_TOP * k, e), step = cw + lerp(L_CARD_GAP * k, L_SMALL_GAP * k, e);
        float left = MARGIN * k + (i - rowScroll.value) * step;
        rowRect.set(left, top, left + cw, top + ch);
        return rowRect;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Scrolls so the focused card shows, one card in from the left once the row moves (as on Apple TV). In cards. */
    private void scrollRow(boolean snap) {
        int last = Math.max(0, cards.size() - rowShown());
        float target = Math.min(Math.max(0, cardIndex - 1), last);
        if (snap) rowScroll.snap(target);
        else rowScroll.target = target;
    }

    /** The focused card changed: scroll, swap the background, get the next cards ready, load more near the end. */
    private void cardMoved() {
        ambientCheck();
        scrollRow(false);
        showFocusedBackdrop();
        preloadCards();
        if (tab == Recs.EXPLORE && cardIndex >= cards.size() - Recs.ROW) recs.moreExplore();
        heroDirty = true;
    }

    /**
     * The cards around the focus, nearest first (each one's art and image are made in that order), then the other tab's
     * first ones, so switching shows them at once.
     */
    private void preloadCards() {
        for (int d = 0; d <= rowShown() + 2; d++) {
            if (cardIndex + d < cards.size()) recs.backdrop(cards.get(cardIndex + d));
            if (d > 0 && d <= 2 && cardIndex - d >= 0) recs.backdrop(cards.get(cardIndex - d));
        }
        List<Recs.Pick> other = recs.row(tab == Recs.CONTINUE ? Recs.EXPLORE : Recs.CONTINUE);
        for (int i = 0; i < Math.min(Recs.ROW, other.size()); i++) recs.backdrop(other.get(i));
    }

    /** The card nearest to x on screen (the row as it will rest). */
    private int nearestCard(float x) {
        return nearest(cardRects, cards.size(), rowScroll.target * (cardRects[1].left - cardRects[0].left), x);
    }

    /** Background: the focused card's photo (its blurred version until the photo comes); without one, a dark gradient. */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        if (ambient && slideNow != null) {
            // Drifting: the slide before (while the new one fades in over it) and the current one, drawn straight to
            // the screen with their alpha (no offscreen layer: the stick can't afford one per frame).
            if (slidePrev != null) {
                imagePaint.setAlpha(255);
                c.drawBitmap(slidePrev, drift(slidePrev, slidePrevAt, slidePrevN, up), imagePaint);
            }
            float in = slidePrev == null ? 1 : Ui.clamp((up - slideAt) / (float) SLIDE_FADE_MS);
            imagePaint.setAlpha(Math.round(255 * in * in * (3 - 2 * in)));
            c.drawBitmap(slideNow, drift(slideNow, slideAt, slideCount, up), imagePaint);
            imagePaint.setAlpha(255);
            drawScrims(c);
            return;
        }
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        if (bgTo == null || (fading && bgFrom == null)) {
            if (skyGradient == null) skyGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setShader(skyGradient);
            fill.setColor(0xFF000000);
            c.drawRect(screen, fill);
            fill.setShader(null);
        }
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        float veil = 0.42f * Ui.clamp(drawer.value);   // the drawer open: the background darkens
        if (veil > 0.002f) {
            fill.setColor(Ui.alpha(0xFF000000, veil));
            c.drawRect(screen, fill);
        }
        if (slideExit != null) {
            float out = 1 - Ui.clamp((up - exitAt) / (float) EXIT_FADE_MS);
            imagePaint.setAlpha(Math.round(255 * out * out * (3 - 2 * out)));
            c.drawBitmap(slideExit, exitMatrix, imagePaint);
            imagePaint.setAlpha(255);
        }
        drawAppGlow(c);
        if (bgTo != null || bgFrom != null || appsExpand.value > 0.01f) drawScrims(c);
    }

    /**
     * With focus on the apps: the focused app's colors, a soft blurred glow over the photo (YouTube's red for
     * TizenTube), coming in as the apps row grows and cross-fading when the focus moves to another app.
     */
    private void drawAppGlow(Canvas c) {
        float e = appsExpand.value;
        if (e < 0.01f || shelf.isEmpty() || zone != Z_SHELF && zone != Z_DRAWER) {
            glowing = false;
            return;
        }
        HomeApps.Item it = shelf.get(Math.min(shelfIndex, shelf.size() - 1));
        long up = SystemClock.uptimeMillis();
        if (!glowing) {
            glowing = true;
            glowItem = it;
            glowFrom = null;
            glowAt = 0;
        } else if (it != glowItem) {
            glowFrom = glowItem;
            glowItem = it;
            glowAt = up;
        }
        float f = glowAt == 0 ? 1 : Ui.smoothstep(0, GLOW_FADE_MS, up - glowAt);
        if (f < 1) sky.postInvalidateOnAnimation();
        drawGlow(c, glowFrom, e);
        drawGlow(c, it, f >= 1 ? e : e * f);
    }

    /** One app's glow at [alpha]: its blurred TV card, or a gradient of its color. */
    private void drawGlow(Canvas c, HomeApps.Item it, float alpha) {
        if (it == null || alpha <= 0.002f) return;
        if (it.blur != null) {
            // Its TV card, tiny and blurred, over the whole screen (filtered up, it stays soft), darkened.
            imagePaint.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(it.blur, null, screen, imagePaint);
            imagePaint.setAlpha(255);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, 0.35f * alpha));
            c.drawRect(screen, fill);
            return;
        }
        int color = it.tint;
        RadialGradient glow = appGlows.get(color);
        if (glow == null) {
            glow = new RadialGradient(w * 0.7f, h * 0.36f, w * 0.75f,
                    new int[] {Ui.blend(color, 0xFF0A0B0F, 0.25f), Ui.blend(color, 0xFF0A0B0F, 0.7f), 0xFF0A0B0F},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            appGlows.put(color, glow);
        }
        fill.setShader(glow);
        fill.setColor(0xFF000000);
        fill.setAlpha(Math.round(255 * alpha));
        c.drawRect(screen, fill);
        fill.setAlpha(255);
        fill.setShader(null);
    }

    /**
     * The photo itself on the whole screen over the card's blurred version; a photo that has just
     * arrived fades in over the blur instead of popping in.
     */
    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        Bitmap bg = b.bg;   // Recs may drop it from another thread
        float in = 1;
        if (bg != null && b == bgTo) {
            long up = SystemClock.uptimeMillis();
            if (bg != photoShown) {
                photoShown = bg;
                photoAt = up;
            }
            in = Ui.clamp((up - photoAt) / PHOTO_FADE_MS);
            if (in < 1) sky.postInvalidateOnAnimation();
        }
        if (bg == null || in < 1) drawImage(c, b.nightImage, alpha);
        if (bg != null) drawImage(c, bg, alpha * in * in * (3 - 2 * in));
    }

    /** Dark on the left (the hero's text) and at the bottom (the row and the shelf), a little at the top (the tabs). */
    private void drawScrims(Canvas c) {
        if (scrimLeft == null || scrimW != w) {
            scrimW = w;
            scrimLeft = new LinearGradient(0, 0, w * 0.78f, 0, new int[] {0xF50A0B0F, 0xE00A0B0F, 0xA60A0B0F, 0x4D0A0B0F, 0x000A0B0F},
                    new float[] {0, 0.3f, 0.55f, 0.78f, 1}, Shader.TileMode.CLAMP);
            scrimBottom = new LinearGradient(0, 0, 0, h, new int[] {0x800A0B0F, 0x000A0B0F, 0x000A0B0F, 0xB30A0B0F, 0xF20A0B0F},
                    new float[] {0, 0.2f, 0.42f, 0.66f, 1}, Shader.TileMode.CLAMP);
        }
        fill.setColor(0xFF000000);
        fill.setShader(scrimLeft);
        c.drawRect(screen, fill);
        fill.setShader(scrimBottom);
        c.drawRect(screen, fill);
        fill.setShader(null);
    }

    private Recs.Pick focusedPick() {
        return cards.isEmpty() ? null : cards.get(Math.min(cardIndex, cards.size() - 1));
    }

    /**
     * The background follows the focused card (it stays on the last one while focus is on the tabs or the shelf). Its
     * full-screen photo and the neighbors' are asked for; until it's ready, the card's blurred version shows.
     */
    private void showFocusedBackdrop() {
        Recs.Pick p = focusedPick();
        Backdrop b = p == null ? null : recs.backdrop(p);
        if (p != null) {
            recs.photo(p);
            int i = cards.indexOf(p);
            if (i > 0) recs.photo(cards.get(i - 1));
            if (i >= 0 && i + 1 < cards.size()) recs.photo(cards.get(i + 1));
        }
        Backdrop before = bgTo;
        if (p == null || b != null) showBackdrop(b);
        // Redrawn when it changed, or when its photo just arrived (it replaces the blurred one).
        if (bgTo != before || bgTo != null && bgTo.bg != null && bgTo.bg != photoShown) sky.invalidate();
    }

    /**
     * The focused pick over its photo: where it's from, its logo (or its title in text), what's next and how much is
     * left, with the progress bar.
     */
    private void drawLightHero(Canvas c) {
        if (zone == Z_SHELF && shelfIndex < shelf.size()) {
            drawAppHero(c, shelf.get(shelfIndex));
            return;
        }
        if (tab == TAB_LIVE) {
            drawLiveHero(c);
            return;
        }
        if (tab == TAB_MUSIC) {
            drawMusicHero(c);
            return;
        }
        Recs.Pick p = focusedPick();
        if (p == null) return;
        float x = MARGIN * k, labelY;
        Backdrop b = recs.backdrop(p);
        Bitmap logo = b == null ? null : b.logo;
        // A very wide logo comes out too thin to read (a 17:1 one is 53 px tall at 900 wide): the title in text instead.
        if (logo != null && logo.getHeight() * 1920f / Backdrop.W < LOGO_MIN_H) logo = null;
        if (logo != null) {
            float scale = k * 1920f / Backdrop.W;   // built at the UI's size
            float lw = logo.getWidth() * scale, lh = logo.getHeight() * scale;
            float top = (L_LOGO_TOP + 150) * k - lh;   // on the box's bottom line, like the text
            tmp.set(x, top, x + lw, top + lh);
            imagePaint.setAlpha(255);
            c.drawBitmap(logo, null, tmp, imagePaint);
            labelY = top - HERO_LABEL_GAP * k;
        } else {
            if (heroTextPick != p || heroTextLayout == null) {
                heroTextPick = p;
                heroTextLayout = titleLayout(p.title, 2);
            }
            float top = (L_LOGO_TOP + 150) * k - heroTextLayout.getHeight();
            c.save();
            c.translate(x, top);
            heroTextLayout.draw(c);
            c.restore();
            labelY = labelOverText(top);
        }
        if (heroMetaPick != p || heroMetaSource != p.meta || heroMetaGeneration != Ui.generation) {
            heroMetaPick = p;
            heroMetaSource = p.meta;
            heroMetaGeneration = Ui.generation;
            String meta = p.meta == null ? "" : p.meta;
            if (p.left > 0) {
                String left = Ui.trf("{n} min left", "n", Math.max(1, Math.round(p.left / 60_000f)));
                meta = meta.isEmpty() ? left : meta + " · " + left;
            }
            fitPaint.set(metaPaint);
            heroMeta = TextUtils.ellipsize(meta, fitPaint, HERO_TITLE_W * k, TextUtils.TruncateAt.END).toString();
            heroLabel = Recs.serviceName(p).toUpperCase(Ui.locale());
        }
        text(c, heroLabel, x, labelY, labelPaint, 0xFFFFFFFF, 0.78f);
        text(c, heroMeta, x, L_HERO_META * k, metaPaint, 0xFFFFFFFF, 0.85f);
        if (p.progress > 0) {
            tmp.set(x, L_HERO_BAR * k, x + 440 * k, (L_HERO_BAR + 6) * k);
            progressBar(c, tmp, p.progress, 0x38FFFFFF, ACCENT);
        }
    }

    /** A hero title in text, at most `lines` lines, cut with an ellipsis. */
    private StaticLayout titleLayout(String title, int lines) {
        String t = title == null ? "" : title;
        return StaticLayout.Builder.obtain(t, 0, t.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 0.94f).setIncludePad(false).build();
    }

    /** A progress bar in `r` (changed): the track, then `fraction` of it in `color`. */
    private void progressBar(Canvas c, RectF r, float fraction, int track, int color) {
        float rr = r.height() / 2;
        fill.setShader(null);
        fill.setColor(track);
        c.drawRoundRect(r, rr, rr, fill);
        r.right = r.left + r.width() * Ui.clamp(fraction);
        fill.setColor(color);
        c.drawRoundRect(r, rr, rr, fill);
    }

    /** `s` in capitals (a hero's label), made again only when it changes. */
    private String upper(String s) {
        if (s != upperSource || upperGeneration != Ui.generation) {
            upperSource = s;
            upperGeneration = Ui.generation;
            upperText = s == null ? "" : s.toUpperCase(Ui.locale());
        }
        return upperText;
    }

    /**
     * Live TV's hero: LIVE and the channel; what's on (its title big); when it runs and what's left; how far in it is
     * and what's next. Without channels yet, that they're on their way (or why not).
     */
    private void drawLiveHero(Canvas c) {
        float x = MARGIN * k, lift = LIVE_LIFT * k;
        if (cards.isEmpty()) {
            // No channels: still being read or fetched (liveFetched redraws), or the fetch failed.
            boolean none = Live.channels(getContext()).isEmpty(), loading = none && (Live.fetching() || !Live.failed());
            titlePaint.setColor(0xFFFFFFFF);
            c.drawText(loading ? Ui.tr("Loading…") : none ? Ui.tr("No channels yet") : Ui.tr("No favourites yet"),
                    x, (L_LOGO_TOP + 132) * k - lift, titlePaint);
            String why = loading ? Ui.tr("Getting India's channels and their guide.")
                    : none ? Ui.tr("Couldn't get the channels. Trying again in a few minutes.")
                    : Ui.tr("Hold OK on a channel to add it here.");
            text(c, why, x, L_HERO_META * k - lift, metaPaint, 0xFFFFFFFF, 0.85f);
            return;
        }
        Recs.Pick p = focusedPick();
        Live.Channel ch = p.channel;
        long now = System.currentTimeMillis();
        Live.Show show = ch.now(now), next = ch.next(now);
        // What's on, big (the channel's name when the guide has nothing).
        String what = show != null && !show.title.isEmpty() ? show.title : ch.name;
        if (heroTextPick == null || !what.equals(heroTextPick.title) || heroTextLayout == null || heroTextPick.kind == null || !heroTextPick.kind.equals("liveHero")) {
            heroTextPick = new Recs.Pick();
            heroTextPick.kind = "liveHero";
            heroTextPick.title = what;
            heroTextLayout = titleLayout(what, 2);
        }
        titlePaint.setColor(0xFFFFFFFF);
        float titleTop = (L_LOGO_TOP + 150) * k - lift - heroTextLayout.getHeight();
        c.save();
        c.translate(x, titleTop);
        heroTextLayout.draw(c);
        c.restore();
        // LIVE · CHANNEL, the label over the title
        String live = Ui.tr("LIVE");
        float tw = liveTag.measureText(live), base = labelOverText(titleTop);
        tmp.set(x, base - Ui.cap(labelPaint) - 7 * k, x + tw + 18 * k, base + 7 * k);
        fill.setShader(null);
        fill.setColor(LIVE_RED);
        c.drawRoundRect(tmp, 6 * k, 6 * k, fill);
        c.drawText(live, x + 9 * k, tmp.centerY() + Ui.halfCap(liveTag), liveTag);
        text(c, upper(ch.name), tmp.right + 14 * k, base, labelPaint, 0xFFFFFFFF, 0.8f);
        text(c, p.meta, x, L_HERO_META * k - lift, metaPaint, 0xFFFFFFFF, 0.85f);
        if (show != null) {
            float bw = 440 * k, bh = 6 * k, top = L_HERO_BAR * k - lift;
            tmp.set(x, top, x + bw, top + bh);
            progressBar(c, tmp, p.progress, 0x38FFFFFF, LIVE_RED);
            if (next != null) {
                if (next != liveNext || liveNextGeneration != Ui.generation) {
                    liveNext = next;
                    liveNextGeneration = Ui.generation;
                    liveNextText = Ui.trf("Next · {title}, {time}", "title", next.title, "time", Ui.time(next.start));
                }
                text(c, liveNextText, x + bw + 20 * k, top + bh / 2 + Ui.halfCap(chipDim), chipDim, 0xFFFFFFFF, 0.75f);
            }
        }
    }

    /**
     * The Music tab's hero, lifted like Live TV's for the pills: the provider and the row, the song's title big, the
     * artist and its length. Without songs: why (nothing found, loading); without a provider, what's playing in any app.
     */
    private void drawMusicHero(Canvas c) {
        float x = MARGIN * k, lift = LIVE_LIFT * k + musicRaise();
        String row = musicChips.isEmpty() ? "" : musicChips.get(Math.min(musicRow, musicChips.size() - 1));
        if (cards.isEmpty()) {
            boolean app = MusicEngine.available(getContext()), loading = app && musicResults == null;
            Music.Track now = app ? null : Music.track();
            String head = now != null ? now.shownTitle : !app ? Ui.tr("Music") : loading ? Ui.tr("Loading…")
                    : musicRow == MUSIC_SEARCH && musicQuery == null ? Ui.tr("Search music") : Ui.tr("Nothing found.");
            String why = now != null ? (now.shownArtist.isEmpty() ? "" : now.shownArtist + " · ") + Music.appName(getContext(), now.app)
                    : !app ? Ui.tr("Pick a music app in Home Settings.") : "";
            titlePaint.setColor(0xFFFFFFFF);
            c.drawText(head, x, (L_LOGO_TOP + 132) * k - lift, titlePaint);
            text(c, why, x, L_HERO_META * k - lift, metaPaint, 0xFFFFFFFF, 0.85f);
            return;
        }
        Recs.Pick p = focusedPick();
        if (p == null) return;
        if (heroTextPick != p || heroTextLayout == null) {
            heroTextPick = p;   // one line: the Music hero sits highest, a second would reach the tabs
            heroTextLayout = titleLayout(p.title, 1);
        }
        titlePaint.setColor(0xFFFFFFFF);
        float titleTop = (L_LOGO_TOP + 150) * k - lift - heroTextLayout.getHeight();
        float labelY = labelOverText(titleTop);
        String name = MusicEngine.name(getContext());
        if (name != musicLabelName || row != musicLabelRow || musicLabelGeneration != Ui.generation) {
            musicLabelName = name;
            musicLabelRow = row;
            musicLabelGeneration = Ui.generation;
            musicLabel = (name + " · " + row).toUpperCase(Ui.locale());
        }
        text(c, musicLabel, x, labelY, labelPaint, 0xFFFFFFFF, 0.78f);
        c.save();
        c.translate(x, titleTop);
        heroTextLayout.draw(c);
        c.restore();
        text(c, p.meta, x, L_HERO_META * k - lift, metaPaint, 0xFFFFFFFF, 0.85f);
    }

    /**
     * The pills under the hero (Live TV: All, Favourites, then a round one per letter; Music: Search, the provider's
     * shelves and the saved searches): the selected one a faint pill; with focus on them, white and a little bigger.
     */
    private void drawLiveChips(Canvas c) {
        float x = MARGIN * k, top = L_CHIPS_TOP * k - musicRaise(), ch = L_CHIP_H * k, pad = 18 * k;
        boolean focused = zone == Z_CHIPS;
        boolean music = tab == TAB_MUSIC;
        if (music) musicWindow();
        int n = Math.min(chipCount(), chipRects.length), selected = chipSelected();
        String name = n > 0 ? chipLabel(0) : null;
        for (int i = 0; i < n; i++) {
            String next = i + 1 < n ? chipLabel(i + 1) : null;
            if (music && i >= 1 && (i < musicFirst || i >= musicFirst + MUSIC_SHOWN)) {
                name = next;
                continue;
            }
            boolean on = i == selected;
            // As the tabs: focused, solid white with dark text (and a little bigger); selected while the focus is
            // elsewhere, a faint pill with white text.
            boolean lit = on && focused;
            Paint tp = on ? pillTextOn : pillText;
            pillTextOn.setColor(lit ? 0xFF0A0B0F : 0xFFF4F4F6);
            if (chipLabels[i] != name || chipOn[i] != on) {
                chipLabels[i] = name;
                chipOn[i] = on;
                chipTextW[i] = tp.measureText(name);
            }
            boolean lens = music && i == MUSIC_SEARCH;
            float iw = lens ? (ICON + ICON_GAP) * k : 0, textW = chipTextW[i];
            float wdt = name.length() == 1 ? ch : textW + iw + 2 * pad;
            if (chipRects[i] == null) chipRects[i] = new RectF();
            chipRects[i].set(x, top, x + wdt, top + ch);
            if (chipRects[i].left > w) break;
            cell.set(chipRects[i]);
            if (lit) {
                float grow = 0.06f;
                cell.inset(-cell.width() * grow / 2, -cell.height() * grow / 2);
                fill.setShader(null);
                fill.setColor(0xFFF4F4F6);
                c.drawRoundRect(cell, cell.height() / 2, cell.height() / 2, fill);
            } else {
                frosted(c, cell, cell.height() / 2, 1);
                if (on) {
                    fill.setShader(null);
                    fill.setColor(0x33F4F4F6);
                    c.drawRoundRect(cell, cell.height() / 2, cell.height() / 2, fill);
                }
            }
            float nx = cell.centerX() - (textW + iw) / 2;
            if (lens) icon(c, searchPath, nx, cell.centerY(), tp.getColor());
            c.drawText(name, nx + iw, cell.centerY() + Ui.halfCap(tp), tp);
            boolean letter = name.length() == 1, nextLetter = next != null && next.length() == 1;
            x += wdt + (letter && nextLetter ? LETTER_GAP : CHIP_GAP) * k;
            name = next;
        }
    }

    private static final float ICON = 22, ICON_GAP = 10;

    /** A 24-unit line icon, ICON px tall, its left edge at [x], centred on [cy]. */
    private void icon(Canvas c, Path path, float x, float cy, int color) {
        float s = ICON * k / 24f;
        iconStroke.setColor(color);
        iconStroke.setStrokeWidth(2f);
        c.save();
        c.translate(x, cy - ICON * k / 2);
        c.scale(s, s);
        c.drawPath(path, iconStroke);
        c.restore();
    }

    /** A song started or stopped: the pill comes or goes (and the focus leaves it when it goes). */
    private final Music.Listener musicChanged = this::onMusic;

    private void onMusic() {
        if (zone == Z_NOW && !nowShown()) {
            zone = Z_TOP;
            topIndex = tabPos();
            retarget(false);
        }
        invalidateHero();
    }

    private void nowArtReady(String key, MusicArt.Scene scene) {
        nowSceneOf = null;   // ask again: it's there now
        nowLayer.invalidate();
    }

    /** The now-playing pill shows while a song plays, and while one asked for on the Music tab is starting. */
    private boolean nowShown() {
        return picksOn() && (pendingSong() != null || Music.track() != null && Music.playing());
    }

    /**
     * What's playing, small, at the top right, in its own view (its moving bars don't redraw the hero): the cover in a
     * circle, moving bars while it plays, title · artist. OK on it opens the music screensaver (the now-playing screen).
     */
    private void drawNowPill(Canvas c) {
        Music.Track t = pendingSong();
        boolean loading = t != null;
        if (!loading && Music.playing()) t = Music.track();
        if (t == null || !picksOn() || nowRight <= 0) {
            nowRect.setEmpty();
            return;
        }
        float right = nowRight, cy = nowCy;
        float h = 56 * k, pad = 20 * k, disc = 42 * k, bars = 22 * k;
        // The text at most 380 px, and no wider than the room left between the tabs and what's right of the pill.
        float fixed = 6 * k + disc + 14 * k + bars + 12 * k + pad;
        float room = Math.max(0, Math.min(380 * k, right - pillsRect.right - 24 * k - fixed));
        if (t != nowTrack || room != nowTextRoom) {   // set once per song, change of its details or room: not per frame
            nowTrack = t;
            nowTextRoom = room;
            fitPaint.set(chipText);
            nowText = TextUtils.ellipsize(t.shownTitle + " · " + t.shownArtist, fitPaint, room, TextUtils.TruncateAt.END).toString();
            nowSplit = nowText.startsWith(t.shownTitle) ? t.shownTitle.length() : nowText.length();
            nowTextW = chipText.measureText(nowText);
        }
        String text = nowText;
        nowRect.set(right - fixed - nowTextW, cy - h / 2, right, cy + h / 2);
        // Focused, it turns white with dark text and bars, as the tabs do.
        float f = Ui.clamp(nowFocus.value);
        int ink = Ui.blend(0xFFF4F4F6, 0xFF0A0B0F, f);
        cell.set(nowRect);
        if (f > 0.01f) {   // grows as much on every side (by its height: a long pill would reach the weather chip)
            float grow = cell.height() * 0.06f * f / 2;
            cell.inset(-grow, -grow);
        }
        frosted(c, cell, cell.height() / 2, 1);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFF4F4F6, f));
            c.drawRoundRect(cell, cell.height() / 2, cell.height() / 2, fill);
        }
        float x = cell.left + 6 * k, mid = cell.centerY();
        if (t != nowSceneOf) {
            nowSceneOf = t;
            nowScene = MusicArt.get(getContext(), t, k, nowReady);
        }
        MusicArt.Scene sc = nowScene;
        if (sc != null) {
            if (nowShaderOf != sc.card) {
                nowShader = new BitmapShader(sc.card, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                nowShaderOf = sc.card;
            }
            float scale = disc / sc.side;
            nowMatrix.setTranslate(-2, -2);   // the card's border
            nowMatrix.postScale(scale, scale);
            nowMatrix.postTranslate(x, mid - disc / 2);
            nowShader.setLocalMatrix(nowMatrix);
            nowCover.setShader(nowShader);
            c.drawCircle(x + disc / 2, mid, disc / 2, nowCover);
        } else {
            fill.setShader(null);
            fill.setColor(0x33FFFFFF);
            c.drawCircle(x + disc / 2, mid, disc / 2, fill);
        }
        x += disc + 14 * k;
        // Four bars, moving while it plays, resting low while it's paused; a spinner in their place while it's starting.
        boolean playing = !loading && Music.playing();
        double now = SystemClock.uptimeMillis() / 1000.0;
        if (loading) {
            float sr = 9 * k;
            tmp.set(x + bars / 2 - sr, mid - sr, x + bars / 2 + sr, mid + sr);
            spinner.setStrokeWidth(3 * k);
            spinner.setColor(Ui.alpha(ink, 0.25f));
            c.drawOval(tmp, spinner);
            spinner.setColor(ink);
            c.drawArc(tmp, (float) (now * 400 % 360), 100, false, spinner);
        } else {
            fill.setShader(null);
            fill.setColor(ink);
            float bw = 3.5f * k, gap = (bars - 4 * bw) / 3, top = mid - 9 * k, bottom = mid + 9 * k;
            for (int i = 0; i < 4; i++) {
                double phase = now * (1.6 + 0.37 * i) * Math.PI;
                float v = playing ? 0.35f + 0.65f * (float) (0.5 + 0.5 * Math.sin(phase + i * 1.7)) : 0.3f;
                float bx = x + i * (bw + gap);
                tmp.set(bx, bottom - (bottom - top) * v, bx + bw, bottom);
                c.drawRoundRect(tmp, bw / 2, bw / 2, fill);
            }
        }
        x += bars + 12 * k;
        // The title, then " · artist" dimmer (as the weather chip's city).
        int split = Math.min(nowSplit, text.length());
        chipText.setColor(ink);
        c.drawText(text, 0, split, x, mid + Ui.halfCap(chipText), chipText);
        if (split < text.length()) {
            float tx = x + chipText.measureText(text, 0, split);
            chipText.setColor(Ui.alpha(ink, 0.6f));
            c.drawText(text, split, text.length(), tx, mid + Ui.halfCap(chipText), chipText);
        }
        chipText.setColor(0xFFF4F4F6);
        nowLayer.removeCallbacks(barsLater);
        if (playing) nowLayer.postDelayed(barsLater, 66);   // the bars, at 15 frames a second
        if (loading) nowLayer.postInvalidateOnAnimation();   // the spinner, smooth
    }

    private void barsFrame() {
        nowLayer.invalidate();
    }

    /** Where the now-playing pill goes, from the top bar's layout; its view is drawn again when that moved. */
    private void placeNowPill(float right, float cy) {
        if (right == nowRight && cy == nowCy) return;
        nowRight = right;
        nowCy = cy;
        nowLayer.invalidate();
    }

    /** "All" and "Favourites" in the person's language; letters as they are. */
    private static String chipName(String group) {
        if (Live.ALL.equals(group)) return Ui.tr("live|All");
        if (Live.FAVOURITES.equals(group)) return Ui.tr("Favourites");
        return group;
    }

    /** The label's baseline over a text title laid out from [layoutTop]: HERO_LABEL_GAP above its capitals. */
    private float labelOverText(float layoutTop) {
        return layoutTop + (-titlePaint.ascent() - Ui.cap(titlePaint)) - HERO_LABEL_GAP * k;
    }

    /** The focused app in the hero: its name above, its wordmark (YouTube's) or its name big, one line about it. */
    private void drawAppHero(Canvas c, HomeApps.Item it) {
        float x = MARGIN * k, bottom = (L_LOGO_TOP + 150) * k, top;
        if (it.youtube && it.logo != null) {   // YouTube's wordmark, as the titles' logos sit
            float h = 92 * k, lw = it.logo.getWidth() * h / it.logo.getHeight();
            top = bottom - h;
            tmp.set(x, top, x + lw, bottom);
            imagePaint.setAlpha(255);
            c.drawBitmap(it.logo, null, tmp, imagePaint);
        } else {
            titlePaint.setColor(0xFFFFFFFF);
            c.drawText(it.label, x, bottom - 18 * k, titlePaint);
            top = bottom - 18 * k - Ui.cap(titlePaint);
        }
        text(c, upper(it.label), x, top - HERO_LABEL_GAP * k, labelPaint, 0xFFFFFFFF, 0.78f);
        int count = "com.lagradost.cloudstream3".equals(it.pkg) ? recs.row(Recs.CONTINUE).size() : 0;
        if (it != appLineItem || count != appLineCount || appLineGeneration != Ui.generation) {
            appLineItem = it;
            appLineCount = count;
            appLineGeneration = Ui.generation;
            appLine = it.youtube ? Ui.tr("YouTube, without the ads")
                    : count > 0 ? Ui.plural("plural|one:{n} in Continue watching|other:{n} in Continue watching", count) : "";
        }
        text(c, appLine, x, L_HERO_META * k, metaPaint, 0xFFFFFFFF, 0.85f);
    }

    private void drawLightTop(Canvas c) {
        if (!picksOn()) {
            chipRect.setEmpty();
            drawClock(c);
            return;
        }
        // The tabs: pills in a frosted capsule; the selected one is a faint pill, white with focus on it (and it grows a
        // little and lifts).
        // While the apps have focus they fade out and an "Apps" heading takes their place.
        float e = appsExpand.value, pillsA = 1 - Ui.smoothstep(0, 0.5f, e), headA = Ui.smoothstep(0.5f, 1, e);
        if (headA > 0.01f) {
            appsHeading.setAlpha(Math.round(255 * headA));
            String apps = Ui.tr("Apps");
            c.drawText(apps, MARGIN * k - Ui.leftBearing(appsHeading, apps), pillsRect.centerY() + Ui.halfCap(appsHeading), appsHeading);
        }
        if (pillsA > 0.01f) drawPills(c, pillsA);
        if (chipsTab() && zone != Z_SHELF) drawLiveChips(c);
        drawTopRight(c);
        drawLightHero(c);
        // Only the cards on screen (and one either side, for the scroll).
        int first = Math.max(0, (int) rowScroll.value - 1), end = Math.min(cards.size(), first + rowShown() + 5);
        int top = -1;
        for (int i = first; i < end; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = first; i < end; i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
    }

    /** The tab bar, at `alpha`: mid-fade through one small layer (pieces faded one by one stack, and it pops). */
    private void drawPills(Canvas c, float alpha) {
        int layer = -1;
        if (alpha > 0.01f && alpha < 0.99f) {
            float m = 40 * k;   // the focused pill's shadow
            layer = c.saveLayerAlpha(pillsRect.left - m, pillsRect.top - m, pillsRect.right + m, pillsRect.bottom + m,
                    Math.round(255 * alpha));
            alpha = 1;
        }
        float pr = pillsRect.height() / 2;
        frosted(c, pillsRect, pr, alpha);
        float focus = Ui.clamp(topFocus[tabPos()].value), dim = zone == Z_SHELF ? 0.6f : 1;
        for (int i = 0; i < tabCount(); i++) {
            RectF r = topRects[i];
            boolean on = tabOrder[i] == tab;
            if (on) {
                float s = 1 + 0.06f * focus, cx = r.centerX(), cy = r.centerY();
                scaleAround(cell, r, s, cx, cy);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    dropShadow(c, r, s, cx, cy, r.height() / 2, 6 * k * focus, SHADOW_BLUR * k, Ui.alpha(0xFF000000, 0.4f * focus * alpha));
                }
                // Focused: solid white, dark text (and grown, lifted). Selected while the focus is elsewhere: a faint
                // pill, white text. One turns into the other as the focus comes and goes.
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFF4F4F6, lerp(0.2f, 1, focus) * alpha));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = on ? lTabOn : lTab;
            p.setColor(Ui.alpha(on ? Ui.blend(0xFFF4F4F6, 0xFF0A0B0F, focus) : Ui.alpha(0xFFFFFFFF, 0.8f * dim), alpha));
            String name = tabs()[tabOrder[i]];
            float tw = p.measureText(name);
            c.drawText(name, r.centerX() - tw / 2, r.centerY() + Ui.halfCap(p), p);
        }
        if (layer >= 0) c.restoreToCount(layer);
    }

    /**
     * Frosted glass: the background as it is behind `r`, blurred (the photo's frosted copy, or the focused app's blurred
     * card while the apps have focus), a white veil and a hairline edge. A shader, not a clip (cheaper on old GPUs).
     */
    private void frosted(Canvas c, RectF r, float radius, float alpha) {
        float e = appsExpand.value;
        HomeApps.Item app = shelf.isEmpty() || (zone != Z_SHELF && zone != Z_DRAWER) ? null : shelf.get(Math.min(shelfIndex, shelf.size() - 1));
        Bitmap appBlur = app == null ? null : app.blur;
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF1A1B20, alpha));
        c.drawRoundRect(r, radius, radius, fill);
        // The photo's frost, cross-fading exactly as the background behind it does (drawLightSky): the old one under,
        // the new one coming in on top.
        float photoA = alpha * (appBlur != null ? 1 - e : 1);
        boolean fading = bgFadeAt != 0;
        float fe = fading ? fadeEase(bgFadeAt, SystemClock.uptimeMillis()) : 1;
        Bitmap to = bgTo != null ? bgTo.nightImage : null, from = fading && bgFrom != null ? bgFrom.nightImage : null;
        if (from != null) frostImage(c, r, radius, from, photoA * (to == null ? 1 - fe : 1));
        if (to != null) frostImage(c, r, radius, to, photoA * (fading ? fe : 1));
        if (appBlur != null) frostImage(c, r, radius, appBlur, alpha * e);
        fill.setColor(Ui.alpha(0xFFFFFFFF, 0.08f * alpha));
        c.drawRoundRect(r, radius, radius, fill);
        border.setShader(null);
        border.setColor(Ui.alpha(0xFFFFFFFF, 0.12f * alpha));
        border.setStrokeWidth(Math.max(1, k));
        c.drawRoundRect(r, radius, radius, border);
        border.setStrokeWidth(2 * k);
    }

    /** `image` (a blurred copy of the whole screen) shown through the rounded rectangle `r`, where it really is. */
    private void frostImage(Canvas c, RectF r, float radius, Bitmap image, float alpha) {
        if (alpha <= 0.004f) return;
        imageMatrix.setScale((float) w / image.getWidth(), (float) h / image.getHeight());
        drawShaded(c, image, radius, r, alpha);
    }

    /** Right of the tabs, before the round buttons: the weather in a glass chip (two-tone symbol, degrees, city), the time. */
    private void drawTopRight(Canvas c) {
        float right = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[tabCount()].left) - 22 * k;
        float cy = (L_TABBAR_TOP + L_TABBAR_H / 2f) * k;
        topClock.setColor(0xFFF4F4F6);
        float cw = topClock.measureText(clockText);
        c.drawText(clockText, right - cw, cy + Ui.halfCap(topClock), topClock);
        right -= cw + 22 * k;
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) {
            chipRect.setEmpty();
            placeNowPill(right, cy);
            return;
        }
        if (data.weatherAt != chipDegreesAt || Prefs.version != chipDegreesPrefs) {   // the unit is a setting
            chipDegreesAt = data.weatherAt;
            chipDegreesPrefs = Prefs.version;
            chipDegrees = HomeData.degrees(wx.temp);
            chipDegreesW = chipText.measureText(chipDegrees);
            chipCityW = chipDim.measureText(Config.CITY);
        }
        String deg = chipDegrees, city = Config.CITY;
        float icon = 24 * k, chipH = 56 * k, padX = 22 * k;
        float w1 = chipDegreesW, w2 = chipCityW;
        float width = padX + icon + 12 * k + w1 + 12 * k + w2 + padX;
        chipRect.set(right - width, cy - chipH / 2, right, cy + chipH / 2);
        frosted(c, chipRect, chipH / 2, 1);
        float x = chipRect.left + padX;
        Symbols.weatherIcon(getContext(), c, wx.code, wx.isDay, x + icon / 2, cy, icon * 0.85f);
        x += icon + 12 * k;
        c.drawText(deg, x, cy + Ui.halfCap(chipText), chipText);
        x += w1 + 12 * k;
        c.drawText(city, x, cy + Ui.halfCap(chipDim), chipDim);
        placeNowPill(chipRect.left - 14 * k, cy);
    }

    /** The tabs and their rows: with a TMDB key (or TV picks) and at least one tab shown (Settings > Tabs). */
    private boolean picksOn() {
        if (keyVersion != Prefs.version) {
            keyVersion = Prefs.version;
            hasKey = Recs.hasKey(getContext());
        }
        return tabCount() > 0 && hasKey;
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.weatherIcon(getContext(), c, wx.code, wx.isDay, x + sw / 2, base - Ui.halfCap(lWeather), size);
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        cardBase.set(cardRect(i));
        RectF base = cardBase;
        if (base.right < 0 || base.left > w) return;
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v, e = appsExpand.value;
        float bw = base.width(), bh = base.height(), r0 = lerp(18, 12, e) * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        scaleAround(cell, base, s, cx, cy);
        float r = r0 * s;
        dropShadow(c, base, s, cx, cy, r0, (10 + 14 * f) * k * s, DEEP_BLUR * k, Ui.alpha(0xFF000000, 0.32f + 0.28f * f));
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : b.card;
        if (img != null) {
            roundImage(c, img, cell, r, false, 1);
        } else {
            fill.setShader(null);
            fill.setColor(0xFF2C2C2E);
            c.drawRoundRect(cell, r, r, fill);
        }
        if (p != null && p.channel != null) {
            // Live TV: the channel's logo, big in the middle when there's no programme image, else in a corner badge.
            Bitmap logo = liveLogo(p.channel.logo);
            if (img == null && logo != null) {
                drawFitted(c, logo, cell.centerX(), cell.centerY(), cell.width() * 0.55f, cell.height() * 0.5f);
            } else if (logo != null) {
                float bwd = 64 * k * s, bht = 40 * k * s;
                tmp.set(cell.left + 12 * k * s, cell.bottom - 20 * k * s - bht, cell.left + 12 * k * s + bwd, cell.bottom - 20 * k * s);
                fill.setColor(0xBF0A0B0F);
                c.drawRoundRect(tmp, 8 * k * s, 8 * k * s, fill);
                drawFitted(c, logo, tmp.centerX(), tmp.centerY(), tmp.width() * 0.8f, tmp.height() * 0.7f);
            }
        }
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
                lightShaderW = new float[cardRects.length];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xFFFFFFFF, 0xA6FFFFFF, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(3 * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        if (p.progress > 0) {
            // Watched so far: a thin bar along the card's bottom, inside its corners.
            float inset = 14 * k * s, barH = 5 * k * s, y = cell.bottom - 12 * k * s - barH;
            tmp.set(cell.left + inset, y, cell.right - inset, y + barH);
            progressBar(c, tmp, p.progress, 0x59FFFFFF, p.channel != null ? LIVE_RED : ACCENT);
        }
        if (e > 0.98f) return;   // small cards (focus on the apps) have no titles
        if (cardTextPick[i] != p || cardTextSource[i] != p.title || cardTextW[i] != bw) {
            cardTextPick[i] = p;
            cardTextSource[i] = p.title;
            cardTextW[i] = bw;
            fitPaint.set(lCardTitle);
            cardTitleText[i] = TextUtils.ellipsize(p.title == null ? "" : p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString();
            String artist = p.song == null ? "" : p.song.artist();   // a song's card: its artist under the title
            fitPaint.set(lCardArtist);
            cardArtistText[i] = artist.isEmpty() ? "" : TextUtils.ellipsize(artist, fitPaint, bw, TextUtils.TruncateAt.END).toString();
        }
        lCardTitle.setColor(Ui.alpha(WHITE, (1 - e) * lerp(0.62f, 1, f)));   // the focused card's title stands out
        c.drawText(cardTitleText[i], base.left, base.bottom + 38 * k, lCardTitle);
        if (!cardArtistText[i].isEmpty()) {
            lCardArtist.setAlpha(Math.round(255 * (1 - e)));
            c.drawText(cardArtistText[i], base.left, base.bottom + 68 * k, lCardArtist);
            lCardArtist.setAlpha(255);
        }
    }

    /**
     * The round buttons (Refresh and Settings): icon-only frosted circles; the focused one turns white with a dark icon
     * (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[tabCount() + i];
            float f = Ui.clamp(topFocus[tabCount() + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            scaleAround(cell, r, s, r.centerX(), r.centerY());
            dropShadow(c, r, s, r.centerX(), r.centerY(), r.width() / 2, (6 + 8 * f) * k, SHADOW_BLUR * k,
                    Ui.alpha(0xFF000000, 0.3f + 0.2f * f));
            if (f < 0.99f) frosted(c, cell, rad, 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), 27 * k * s, Ui.blend(0xEBFFFFFF, L_INK, f));
            if (f > 0.01f) {
                String label = it.label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                if (dy >= 0) return false;   // every move up from the apps is here, from an empty shelf too
                if (cards.isEmpty() && chipsTab() && chipCount() > 0) {
                    zone = Z_CHIPS;
                } else if (cards.isEmpty()) {
                    if (!picksOn() && lightButtons.isEmpty()) return true;   // no tabs and no buttons above
                    zone = Z_TOP;
                    topIndex = picksOn() ? tabPos() : tabCount();
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearestCard(shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                    cardMoved();
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                    cardMoved();
                } else if (dy < 0) {
                    if (chipsTab()) {
                        zone = Z_CHIPS;   // Live TV and Music: the pills are between the row and the tabs
                    } else {
                        zone = Z_TOP;
                        topIndex = tabPos();     // as on Apple TV: moving up to the tabs focuses the selected tab
                    }
                } else {
                    zone = Z_SHELF;
                    // The app in the same column once the row has grown (card i is at column i - scroll).
                    if (!shelf.isEmpty()) shelfIndex = Math.max(0, Math.min(shelf.size() - 1, Math.round(cardIndex - rowScroll.target)));
                }
                break;
            case Z_TOP:
                if (dx != 0 && nowShown() && (dx > 0 && topIndex == tabCount() - 1 || dx < 0 && topIndex == tabCount())) {
                    zone = Z_NOW;
                    break;
                }
                if (dx != 0) {
                    int next = Math.max(picksOn() ? 0 : tabCount(), Math.min(tabCount() - 1 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < tabCount()) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (chipsTab() && topIndex < tabCount() && chipCount() > 0) {
                        zone = Z_CHIPS;
                    } else if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearestCard(topRects[topIndex].centerX());
                        cardMoved();
                    }
                } else if (topIndex < tabCount() && !lightButtons.isEmpty()) {
                    topIndex = tabCount();   // up from the tabs reaches the corner buttons without walking the row
                } else {
                    return true;
                }
                break;
            case Z_NOW:
                if (dx < 0) {
                    zone = Z_TOP;
                    topIndex = tabCount() - 1;
                    selectTab(topIndex);
                } else if (dx > 0) {
                    if (lightButtons.isEmpty()) return true;
                    zone = Z_TOP;
                    topIndex = tabCount();
                } else if (dy > 0) {
                    zone = Z_TOP;
                    topIndex = tabPos();
                    return moveLight(0, 1);   // down as from the selected tab
                } else {
                    return true;
                }
                break;
            case Z_CHIPS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(chipCount() - 1, chipSelected() + dx));
                    if (next == chipSelected()) return true;
                    selectChip(next);     // the row below changes with it
                    resetRow();
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tabPos();
                } else if (!cards.isEmpty()) {
                    zone = Z_CARDS;
                    cardMoved();
                } else {
                    zone = Z_SHELF;
                }
                heroDirty = true;
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }
}
