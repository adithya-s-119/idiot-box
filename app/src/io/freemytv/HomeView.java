package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
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
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps.
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, the focused pick's hero over its own photo, a row of picks and
    // the shelf. The tab bar is a 68 px capsule; the row holds Recs.ROW 16:9 cards.
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 56, L_TABBAR_H = 64, L_CARDS_TOP = 596, L_CARD_H = 154;
    private static final float L_CARD_GAP = 24, L_BUTTON = 60, L_BUTTON_GAP = 20, L_BUTTON_CY = 88;
    // With focus on the apps the titles shrink to small cards and the apps grow to the titles' size; at rest the apps are
    // small square icons. Both rows sit on the same bottom-up layout (APPS_BOTTOM).
    private static final float L_SMALL_W = 176, L_SMALL_H = 99, L_SMALL_TOP = 690, L_SMALL_GAP = 16;
    private static final float L_APPS_BOTTOM = 1032, L_ICON = 88, L_ICON_GAP = 20;
    private static final float L_LOGO_TOP = 342, L_HERO_META = 536, L_HERO_BAR = 560;
    /** The progress bars (hero and cards). */
    private static final int ACCENT = 0xFFF5C451;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    /** The tabs (Recs.CONTINUE, Recs.EXPLORE, Live TV, Music); the round buttons follow them in topRects and topFocus. */
    private static final int TABS = 4;
    /** The Live TV tab (after Recs.CONTINUE and Recs.EXPLORE): its row comes from Live, filtered by the group pills. */
    private static final int TAB_LIVE = 2;
    /** The Music tab: YouTube Music's shelves and searches from Free My TV's music engine (MusicEngine), played there. */
    private static final int TAB_MUSIC = 3;
    private static final int LIVE_RED = 0xFFFF3B30;
    /** The hero sits this much higher on the Live TV tab, to make room for the group pills under it. */
    // The hero ends as far above the pills (Live TV, Music) as it does above the cards elsewhere: lifted by the
    // distance from the pills to where the cards usually start.
    private static final float L_CHIPS_TOP = 524, L_CHIP_H = 46, LIVE_LIFT = 596 - L_CHIPS_TOP;
    /** The small caps label over every hero: this far above the top of the title (its capitals, logo or wordmark). */
    private static final float HERO_LABEL_GAP = 40;
    /**
     * The Music tab's square covers (MUSIC_CARD px), with the song's title and artist under each, end above the shelf's
     * glass: its hero, pills and row sit MUSIC_RAISE px higher to make room for them.
     */
    private static final float MUSIC_CARD = 196, MUSIC_RAISE = 70;
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_CHIPS = 6;
    /** The now-playing pill at the top right, between the tabs and the round buttons (while music plays). */
    private static final int Z_NOW = 7;

    private final Activity activity;
    private final Scene scene = new Scene();
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
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    // Always night in this build (no light mode): starts at 1 and stays there.
    private boolean nightTarget = true;
    private float night = 1, nightFrom = 1;
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[TABS + HomeApps.MAX_TOP];
    private final RectF[] topRects = new RectF[TABS + HomeApps.MAX_TOP];
    // One per card in the row (grown with it, ensureCards): unscrolled rects; cardRect(i) is where one is on screen.
    private Spring[] cardFocus = new Spring[0];
    private RectF[] cardRects = new RectF[0];
    private float cardLeft, cardTop, cardW, cardH, cardGap, cardWide;
    // The row scrolls sideways (Explore loads more as it does): how far, in px.
    private final Spring rowScroll = new Spring(0.36f, 0.9f, 0);
    private final RectF rowRect = new RectF(), cardBase = new RectF();
    // 0: focus on the titles (big cards, small app icons); 1: focus on the apps (small cards, big app cards).
    private final Spring appsExpand = new Spring(0.42f, 0.92f, 0);
    private final RectF pillsRect = new RectF(), chipRect = new RectF();
    private LinearGradient appSheen, appRim;
    private final Matrix appShaderMatrix = new Matrix();
    private RadialGradient appGlow;
    private int appGlowColor;
    // The hero's title in text (no logo): made again only when the focused pick changes.
    private Recs.Pick heroTextPick;
    private StaticLayout heroTextLayout;
    private Recs.Pick heroMetaPick;
    private Paint topClock, chipText, chipDim, appsHeading, pillText, pillTextOn, liveTag;
    // Live TV: the group pills, the selected one, and the channels' logos (small, loaded once).
    private List<String> liveGroups = new ArrayList<>();
    private int liveGroup;
    // Music: its pills (Search, YouTube Music's home shelves, then the saved searches), what each one lists
    // (musicSources: a shelf's id, or "saved:" and the query), the one selected (the first shelf at first), the search.
    private final List<String> musicChips = new ArrayList<>(), musicSources = new ArrayList<>();
    private int musicRow = 1;
    private String musicQuery;
    private List<MusicEngine.Result> musicResults;
    private static final String SAVED = "saved:";
    private static final int MUSIC_SEARCH = 0;
    // The round refresh pill after Search (focus on it leaves the row as it is), and the shelves and saved searches
    // shown, MUSIC_SHOWN at a time from musicFirst, the window following the selected one.
    private boolean musicRefreshFocus;
    // The song just asked of the engine (the pill shows it, loading, until it plays), the one playing then, and when.
    private Music.Track pendingSong;
    private String pendingFrom;
    private long pendingAt;
    private static final long PENDING_MS = 60_000;
    private float nowTextRoom = -1;
    private int nowSplit;   // where the title ends in nowText (the artist after it is drawn dimmer)
    private final Paint spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        spinner.setStyle(Paint.Style.STROKE);
        spinner.setStrokeCap(Paint.Cap.ROUND);
    }
    private int musicFirst = 1;
    private static final int MUSIC_SHOWN = 5;
    // The pills' spacing on both tabs: word pills CHIP_GAP apart; Live TV's letters closer, one index (A to Z fits).
    private static final float CHIP_GAP = 18, LETTER_GAP = 8;
    private final RectF[] chipRects = new RectF[40];
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
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardArtist, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
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

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
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
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Line icons (24-unit boxes, scaled when drawn): the Music tab's note, the Search pill's magnifier.
    private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path searchPath = new Path(), refreshPath = new Path();
    // The now-playing pill: its place, its focus, the cover as a round thumbnail.
    private final RectF nowRect = new RectF();
    private final Spring nowFocus = new Spring(0.26f, 0.85f, 0);
    private final Matrix nowMatrix = new Matrix();
    private BitmapShader nowShader;
    private Bitmap nowShaderOf;
    private final Paint nowCover = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private String nowText = "";
    private Music.Track nowTrack;
    private final MusicArt.Ready nowReady = this::nowArtReady;
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final android.graphics.Rect srcRect = new android.graphics.Rect();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
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
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
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
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeCap(Paint.Cap.ROUND);
        iconStroke.setStrokeJoin(Paint.Join.ROUND);
        searchPath.addCircle(11, 11, 7, Path.Direction.CW);
        searchPath.moveTo(20, 20);
        searchPath.lineTo(16.2f, 16.2f);
        refreshPath.addArc(new RectF(3, 3, 21, 21), 0, 315);   // ↻: a circle with a gap at the top right, its arrow there
        refreshPath.moveTo(21, 3);
        refreshPath.lineTo(21, 8);
        refreshPath.lineTo(16, 8);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        ensureCards(32);
        setLightButtons(HomeApps.topButtons());
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        Music.addListener(musicChanged);
        data.refreshIfStale();
        recs.refreshIfStale();
        Live.refresh(getContext(), this::liveFetched);   // ready before the Live TV tab is opened
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        if (!again) reload();
        updatePalette();
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
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        lightButtons.addAll(list);
        if (zone == Z_TOP && topIndex >= TABS + lightButtons.size()) {
            topIndex = TABS - 1 + lightButtons.size();
            if (topIndex < TABS && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
            else if (topIndex < TABS) selectTab(topIndex);
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

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(812 * kk));   // down to the shelf's glass: the row's titles and their descenders
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
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
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
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
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
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
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 26 * k, L_INK);
        lCardArtist = Ui.text(Ui.medium, 22 * k, 0xFF98989D);
        topClock = Ui.numbers(Ui.semibold, 30 * k, 0xFFF4F4F6);
        pillText = Ui.text(Ui.medium, 20 * k, 0xCCFFFFFF);
        pillTextOn = Ui.text(Ui.semibold, 20 * k, 0xFF0A0B0F);
        liveTag = Ui.text(Ui.semibold, 15 * k, 0xFFFFFFFF);
        liveTag.setLetterSpacing(0.08f);
        appsHeading = Ui.text(Ui.semibold, 44 * k, 0xFFF4F4F6);
        chipText = Ui.text(Ui.semibold, 21 * k, 0xFFF4F4F6);
        chipDim = Ui.text(Ui.medium, 21 * k, 0xBFFFFFFF);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        appsExpand.target = !shelf.isEmpty() && (zone == Z_SHELF || zone == Z_DRAWER || drawer.target > 0) ? 1 : 0;
        boolean expanding = appsExpand.step(dt);
        if (expanding) {
            layoutShelf();
            tilesDirty = statusDirty = true;
            sky.invalidate();
        }
        boolean topMoving = step(topFocus, dt) | nowFocus.step(dt), cardsMoving = step(cardFocus, dt) | rowScroll.step(dt) | expanding;
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);
        boolean drifting = tickAmbient(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading || bgFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (!picksOn()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || drifting || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
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
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
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
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        Shaders.dream(scene.palette);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
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
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo darkened (dark only in this build). */
    private int[] glassOf(Backdrop b) {
        return b.nightGlass != null ? b.nightGlass : b.lightGlass;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        return b.nightFrost != null ? b.nightFrost : b.frost;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.nightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            scene.setGlassImage(glassOf(bgTo));
            nightPair = false;
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
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

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
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
        platter.invalidate();
    }

    /**
     * The apps row: at rest, small square icons; with focus on it, 16:9 cards the size of the titles' (the apps' TV
     * art fills them). From the left margin, bottom-aligned, in between while the row grows.
     */
    private void layoutShelf() {
        int n = shelf.size();
        float e = appsExpand.value;
        float big = cardRects[0].width() > 0 ? cardRects[0].width() : 273 * k;
        float tw = lerp(L_ICON * k, big, e), th = lerp(L_ICON * k, L_CARD_H * k, e), gap = lerp(L_ICON_GAP * k, L_CARD_GAP * k, e);
        float bottom = L_APPS_BOTTOM * k, x = MARGIN * k;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, bottom - th, x + tw, bottom);
            x += tw + gap;
        }
        platterRect.set(MARGIN * k, bottom - th, x - gap, bottom);
    }

    /** Shelf layer: nothing now (the apps are a row of cards like the titles, with no glass bar). */
    private void drawPlatter(Canvas c) {
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        // At the right margin, under the round buttons: the shelf's glass is narrower and centred with few apps.
        float right = CONTENT_R * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = TABS; i < TABS + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        // One read of the list, so n always matches the coins drawn below.
        int[] coins = HomeData.active();
        int n = coins.length;
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
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
        float e = appsExpand.value, art = smoothstep(0.25f, 0.75f, e);
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + lerp(0.14f, 0.05f, e) * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = lerp(TILE_RADIUS, 18, e) * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * f;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        tmp.set(cell);
        tmp.offset(0, (6 + 12 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.28f + 0.26f * fc));
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        // The card: the app's color.
        fill.setShader(null);
        fill.setColor(blend(it.tint, 0xFF0A0B0F, 0.35f));
        c.drawRoundRect(cell, r, r, fill);
        if (art > 0.01f) {
            if (it.tile != null) {
                bitmapPaint.setAlpha(Math.round(255 * art));
                roundImageCropped(c, it.tile, cell, r);
                bitmapPaint.setAlpha(255);
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

    /** `image` center-cropped to `cell`'s shape (the row's cards change shape while it grows), rounded. */
    private void roundImageCropped(Canvas c, Bitmap image, RectF cell, float r) {
        float iw = image.getWidth(), ih = image.getHeight(), want = cell.width() / cell.height();
        float sw = Math.min(iw, ih * want), sh = sw / want;
        srcRect.set(Math.round((iw - sw) / 2), Math.round((ih - sh) / 2), Math.round((iw + sw) / 2), Math.round((ih + sh) / 2));
        clip.reset();
        clip.addRoundRect(cell, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawBitmap(image, srcRect, cell, bitmapPaint);
        c.restore();
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 8 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
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
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
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

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.weatherIcon(getContext(), c, wx.code, wx.isDay, x + sw / 2, r.top + 132 * k, 52 * k);
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.weatherIcon(getContext(), c, wx.hourCode.get(i), wx.hourDay.get(i), hc, r.top + 142 * k, 30 * k);
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale);
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        if (tilesChanged) {
            heroDirty = statusDirty = true;     // the hero, the background and the frost show the focused app
            sky.invalidate();
        }
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        float nowTarget = !sheetOpen && zone == Z_NOW ? 1 : 0;
        topChanged |= nowFocus.target != nowTarget;
        nowFocus.target = nowTarget;
        if (snap) nowFocus.snap(nowTarget);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
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
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
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
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
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
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons() : null;   // they change with Prefs too
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                if (s != null) setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
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
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
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
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
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
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
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
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < TABS) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - TABS);
            r = topRects[topIndex];
        } else if (zone == Z_CHIPS) {
            if (tab == TAB_MUSIC && musicRefreshFocus) refreshMusic();
            else if (tab == TAB_MUSIC && musicRow == MUSIC_SEARCH) openMusicSearch();
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

    /** Held OK on a channel: in or out of Favourites, and a toast says which. */
    private void toggleFavourite() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
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
        handler.removeCallbacks(holdStart);
        holdArmed = false;
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
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Free My TV › Shelf.")));
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
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
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
        if (j < 0 || j >= shelf.size()) return;
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
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
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
        for (HomeApps.Item it : shelf) order.add(it.pkg);
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
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
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

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
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
        for (int i = picksOn() ? 0 : TABS; i < TABS + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < cards.size(); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i >= TABS) return label(lightButtons, i - TABS);
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", TABS);
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
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
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= TABS + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < TABS ? heroLayer : status).getTranslationY();
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
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
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
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
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
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= TABS + lightButtons.size() || (i < TABS && !picksOn())) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < TABS) selectTab(i);
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

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
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

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale)).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // The tabs: pills inside a glass capsule (6 px inside it, 8 px apart, 28 px either side of the text).
        float pad = 28 * k, inset = 6 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k + inset;
        for (int i = 0; i < TABS; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop + inset, x + tw + 2 * pad, tabBottom - inset);
            x = topRects[i].right + 8 * k;
        }
        pillsRect.set(MARGIN * k, tabTop, x - 8 * k + inset, tabBottom);
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[TABS + i].set(right - b, cy - b / 2, right, cy + b / 2);
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

    /** The row of the current tab (Continue, Explore) and the background: the focused card's photo. */
    private void updateCards() {
        List<Recs.Pick> list = tab == TAB_LIVE ? livePicks() : tab == TAB_MUSIC ? musicPicks() : recs.row(tab);
        ensureCards(list.size());
        if (tab == TAB_LIVE) cards = list;   // made again each time: what's on and how far in change by the minute
        if (!samePicks(list, cards)) {
            cards = list;
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

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        rowShape();
        cardIndex = 0;
        rowScroll.snap(0);
        updateCards();
        heroDirty = true;
    }

    /**
     * Live TV's row: the channels in the selected pill (A to Z), each a card with what's on now (the programme's image,
     * its title, how far in it is) from the guide.
     */
    private void liveFetched() {
        if (tab == TAB_LIVE) updateCards();
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
            p.detailed = true;
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
            p.meta = String.join(" · ", parts);
            out.add(p);
        }
        return out;
    }

    /**
     * The Music tab's row: the selected pill's songs (a search's answer, or a YouTube Music shelf), each a
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
            p.app = "YouTube Music";
            p.backdrop = r.picture();
            p.detailed = true;
            p.song = r;
            String artist = r.artist();
            p.meta = r.duration > 0 ? (artist.isEmpty() ? "" : artist + " · ") + duration(r.duration) : artist;
            out.add(p);
        }
        return out;
    }

    private static String duration(long ms) {
        long sec = ms / 1000;
        return sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
    }

    private void musicFound() {
        if (tab == TAB_MUSIC) updateCards();
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
     * Left and right around the refresh pill, between Search and the shelves: onto it (the row stays), and off it to
     * Search or the first shelf. False when the move isn't one of those (the pills' own move).
     */
    private boolean musicRefreshMove(int dx) {
        if (musicRefreshFocus) {
            int to = dx < 0 ? MUSIC_SEARCH : 1;
            if (to >= chipCount()) return true;   // no shelves yet: stay
            musicRefreshFocus = false;
            if (to != musicRow) {
                musicRow = to;
                cardIndex = 0;
                rowScroll.snap(0);
                updateCards();
            }
            return true;
        }
        if (dx > 0 && musicRow == MUSIC_SEARCH || dx < 0 && musicRow == 1) {
            musicRefreshFocus = true;
            return true;
        }
        return false;
    }

    /** OK on the refresh pill: YouTube Music's shelves and every row asked of the engine again. */
    private void refreshMusic() {
        click.open();
        MusicEngine.refresh();
        cardIndex = 0;
        rowScroll.snap(0);
        updateCards();
    }

    /** The first of the shelves and saved searches shown: the window of MUSIC_SHOWN moved to hold the selected one. */
    private void musicWindow() {
        int last = Math.max(1, chipCount() - MUSIC_SHOWN);
        if (musicRow >= 1 && musicRow < musicFirst) musicFirst = musicRow;
        if (musicRow >= musicFirst + MUSIC_SHOWN) musicFirst = musicRow - MUSIC_SHOWN + 1;
        musicFirst = Math.max(1, Math.min(last, musicFirst));
    }

    /** The Search pill: Free My TV's keyboard; the answer becomes the Search row. */
    private void openMusicSearch() {
        MusicSearchActivity.asked = q -> {
            musicQuery = q;
            musicRow = MUSIC_SEARCH;
            cardIndex = 0;
            rowScroll.snap(0);
            updateCards();
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
        pendingSong = new Music.Track(MusicEngine.PKG, b);
        pendingFrom = playing == null ? null : playing.key();
        pendingAt = SystemClock.uptimeMillis();
        heroLayer.invalidate();
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
        handler.removeCallbacks(holdStart);
        holdArmed = false;
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
        cardIndex = 0;
        rowScroll.snap(0);
        updateCards();
    }

    /** A channel's logo, small, from storage (fetched there first if new; null until then: the hero and cards redraw). */
    private Bitmap liveLogo(String url) {
        if (url == null || url.isEmpty()) return null;
        synchronized (liveLogos) {
            if (liveLogos.containsKey(url)) return liveLogos.get(url);
            liveLogos.put(url, null);
        }
        LOADER.execute(() -> {
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
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(cards.size(), cardRects.length); i++) {
            float d = Math.abs(cardRects[i].centerX() - rowScroll.target * (cardRects[1].left - cardRects[0].left) - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
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
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
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
     * TizenTube), coming in as the apps row grows.
     */
    private void drawAppGlow(Canvas c) {
        float e = appsExpand.value;
        if (e < 0.01f || shelf.isEmpty() || zone != Z_SHELF && zone != Z_DRAWER) return;
        HomeApps.Item it = shelf.get(Math.min(shelfIndex, shelf.size() - 1));
        if (it.blur != null) {
            // Its TV card, tiny and blurred, over the whole screen (filtered up, it stays soft), darkened.
            imagePaint.setAlpha(Math.round(255 * e));
            c.drawBitmap(it.blur, null, screen, imagePaint);
            imagePaint.setAlpha(255);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, 0.35f * e));
            c.drawRect(screen, fill);
            return;
        }
        int color = it.tint;
        if (appGlow == null || appGlowColor != color) {
            appGlowColor = color;
            appGlow = new RadialGradient(w * 0.7f, h * 0.36f, w * 0.75f,
                    new int[] {blend(color, 0xFF0A0B0F, 0.25f), blend(color, 0xFF0A0B0F, 0.7f), 0xFF0A0B0F},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
        }
        fill.setShader(appGlow);
        fill.setColor(0xFF000000);
        fill.setAlpha(Math.round(255 * e));
        c.drawRect(screen, fill);
        fill.setAlpha(255);
        fill.setShader(null);
    }

    /**
     * The photo itself on the whole screen (scaled up from BG_W) over the card's blurred version; a photo that has just
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
        if (bg == null || in < 1) {
            if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
            if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
        }
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
        if (p == null || b != null) showBackdrop(b);
        sky.invalidate();   // a photo that just arrived replaces the blurred one
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
        String app = Recs.serviceName(p);
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
                String t = p.title == null ? "" : p.title;
                heroTextLayout = StaticLayout.Builder.obtain(t, 0, t.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 0.94f).setIncludePad(false).build();
            }
            float top = (L_LOGO_TOP + 150) * k - heroTextLayout.getHeight();
            c.save();
            c.translate(x, top);
            heroTextLayout.draw(c);
            c.restore();
            labelY = labelOverText(top);
        }
        if (!app.isEmpty()) text(c, app.toUpperCase(Ui.locale()), x, labelY, labelPaint, 0xFFFFFFFF, 0.78f);
        if (heroMetaPick != p) {
            heroMetaPick = p;
            heroMeta = p.meta == null ? "" : p.meta;
            if (p.left > 0) {
                String left = Ui.trf("{n} min left", "n", Math.max(1, Math.round(p.left / 60_000f)));
                heroMeta = heroMeta.isEmpty() ? left : heroMeta + " · " + left;
            }
        }
        String meta = heroMeta;
        if (!meta.isEmpty()) {
            fitPaint.set(metaPaint);
            text(c, TextUtils.ellipsize(meta, fitPaint, HERO_TITLE_W * k, TextUtils.TruncateAt.END).toString(), x, L_HERO_META * k,
                    metaPaint, 0xFFFFFFFF, 0.85f);
        }
        if (p.progress > 0) {
            float bw = 440 * k, bh = 6 * k, top = L_HERO_BAR * k;
            tmp.set(x, top, x + bw, top + bh);
            fill.setShader(null);
            fill.setColor(0x38FFFFFF);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            tmp.right = x + bw * Ui.clamp(p.progress);
            fill.setColor(ACCENT);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
        }
    }

    /**
     * Live TV's hero: LIVE and the channel; what's on (its title big); when it runs and what's left; how far in it is
     * and what's next. Without channels yet, that they're on their way (or why not).
     */
    private void drawLiveHero(Canvas c) {
        float x = MARGIN * k, lift = LIVE_LIFT * k;
        if (cards.isEmpty()) {
            boolean none = Live.channels(getContext()).isEmpty(), fetching = none && Live.fetching();
            titlePaint.setColor(0xFFFFFFFF);
            c.drawText(fetching ? Ui.tr("Loading…") : none ? Ui.tr("No channels yet") : Ui.tr("No favourites yet"),
                    x, (L_LOGO_TOP + 132) * k - lift, titlePaint);
            String why = fetching ? Ui.tr("Getting India's channels and their guide.")
                    : none ? Ui.tr("Couldn't get the channels. Trying again in a few minutes.")
                    : Ui.tr("Hold OK on a channel to add it here.");
            text(c, why, x, L_HERO_META * k - lift, metaPaint, 0xFFFFFFFF, 0.85f);
            if (fetching) postInvalidateDelayed(1000);
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
            heroTextLayout = StaticLayout.Builder.obtain(what, 0, what.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 0.94f).setIncludePad(false).build();
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
        text(c, ch.name.toUpperCase(Ui.locale()), tmp.right + 14 * k, base, labelPaint, 0xFFFFFFFF, 0.8f);
        if (p.meta != null && !p.meta.isEmpty()) text(c, p.meta, x, L_HERO_META * k - lift, metaPaint, 0xFFFFFFFF, 0.85f);
        if (show != null) {
            float bw = 440 * k, bh = 6 * k, top = L_HERO_BAR * k - lift;
            tmp.set(x, top, x + bw, top + bh);
            fill.setColor(0x38FFFFFF);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            tmp.right = x + bw * p.progress;
            fill.setColor(LIVE_RED);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            if (next != null) {
                text(c, Ui.trf("Next · {title}, {time}", "title", next.title, "time", Ui.time(next.start)),
                        x + bw + 20 * k, top + bh / 2 + Ui.halfCap(chipDim), chipDim, 0xFFFFFFFF, 0.75f);
            }
        }
    }

    /**
     * The Music tab's hero, lifted like Live TV's for the pills: YOUTUBE MUSIC and the row, the song's title big, the
     * artist and its length. Without songs: why (no engine, nothing found, loading).
     */
    private void drawMusicHero(Canvas c) {
        float x = MARGIN * k, lift = LIVE_LIFT * k + musicRaise();
        String row = musicChips.isEmpty() ? "" : musicChips.get(Math.min(musicRow, musicChips.size() - 1));
        if (cards.isEmpty()) {
            boolean app = MusicEngine.available(getContext()), loading = app && musicResults == null;
            String head = !app ? Ui.tr("Music") : loading ? Ui.tr("Loading…") : musicRow == MUSIC_SEARCH && musicQuery == null ? Ui.tr("Search music")
                    : Ui.tr("Nothing found.");
            String why = !app ? Ui.tr("Install Free My TV Music to play music.") : "";
            titlePaint.setColor(0xFFFFFFFF);
            c.drawText(head, x, (L_LOGO_TOP + 132) * k - lift, titlePaint);
            if (!why.isEmpty()) text(c, why, x, L_HERO_META * k - lift, metaPaint, 0xFFFFFFFF, 0.85f);
            if (loading) postInvalidateDelayed(500);
            return;
        }
        Recs.Pick p = focusedPick();
        if (p == null) return;
        if (heroTextPick != p || heroTextLayout == null) {
            heroTextPick = p;   // one line: the Music hero sits highest, a second would reach the tabs
            String t = p.title == null ? "" : p.title;
            heroTextLayout = StaticLayout.Builder.obtain(t, 0, t.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                    .setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 0.94f).setIncludePad(false).build();
        }
        titlePaint.setColor(0xFFFFFFFF);
        float titleTop = (L_LOGO_TOP + 150) * k - lift - heroTextLayout.getHeight();
        float labelY = labelOverText(titleTop);
        text(c, ("YouTube Music · " + row).toUpperCase(Ui.locale()), x, labelY, labelPaint, 0xFFFFFFFF, 0.78f);
        c.save();
        c.translate(x, titleTop);
        heroTextLayout.draw(c);
        c.restore();
        if (p.meta != null && !p.meta.isEmpty()) text(c, p.meta, x, L_HERO_META * k - lift, metaPaint, 0xFFFFFFFF, 0.85f);
    }

    /**
     * The pills under the hero (Live TV: All, Favourites, then a round one per letter; Music: Search, YouTube Music's
     * shelves and the saved searches): the selected one a faint pill; with focus on them, white and a little bigger.
     */
    private void drawLiveChips(Canvas c) {
        float x = MARGIN * k, top = L_CHIPS_TOP * k - musicRaise(), ch = L_CHIP_H * k, pad = 18 * k;
        boolean focused = zone == Z_CHIPS;
        boolean music = tab == TAB_MUSIC;
        if (music) musicWindow();
        for (int i = 0; i < chipCount() && i < chipRects.length; i++) {
            if (music && i >= 1 && (i < musicFirst || i >= musicFirst + MUSIC_SHOWN)) continue;
            String name = chipLabel(i);
            boolean on = i == chipSelected() && !(music && musicRefreshFocus && focused);
            // As the tabs: focused, solid white with dark text (and a little bigger); selected while the focus is
            // elsewhere, a faint pill with white text.
            boolean lit = on && focused;
            Paint tp = on ? pillTextOn : pillText;
            pillTextOn.setColor(lit ? 0xFF0A0B0F : 0xFFF4F4F6);
            boolean lens = tab == TAB_MUSIC && i == MUSIC_SEARCH;
            float iw = lens ? (ICON + ICON_GAP) * k : 0;
            float wdt = name.length() == 1 ? ch : tp.measureText(name) + iw + 2 * pad;
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
            float nx = cell.centerX() - (tp.measureText(name) + iw) / 2;
            if (lens) icon(c, searchPath, nx, cell.centerY(), tp.getColor());
            c.drawText(name, nx + iw, cell.centerY() + Ui.halfCap(tp), tp);
            boolean letter = name.length() == 1, nextLetter = i + 1 < chipCount() && chipLabel(i + 1).length() == 1;
            x += wdt + (letter && nextLetter ? LETTER_GAP : CHIP_GAP) * k;
            if (music && i == MUSIC_SEARCH) x = drawRefreshChip(c, x, top, ch, focused && musicRefreshFocus);
        }
    }

    /** The Music tab's round refresh pill at [x]; the x after it. */
    private float drawRefreshChip(Canvas c, float x, float top, float ch, boolean on) {
        cell.set(x, top, x + ch, top + ch);
        if (on) {
            float grow = 0.06f;
            cell.inset(-cell.width() * grow / 2, -cell.height() * grow / 2);
            fill.setShader(null);
            fill.setColor(0xFFF4F4F6);
            c.drawRoundRect(cell, cell.height() / 2, cell.height() / 2, fill);
        } else {
            frosted(c, cell, cell.height() / 2, 1);
        }
        icon(c, refreshPath, cell.centerX() - ICON * k / 2, cell.centerY(), (on ? pillTextOn : pillText).getColor());
        return x + ch + CHIP_GAP * k;
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
            topIndex = tab;
            retarget(false);
        }
        heroLayer.invalidate();
    }

    private void nowArtReady(String key, MusicArt.Scene scene) {
        heroLayer.invalidate();
    }

    /** The now-playing pill shows while a song plays, and while one asked for on the Music tab is starting. */
    private boolean nowShown() {
        return picksOn() && (pendingSong() != null || Music.track() != null && Music.playing());
    }

    /**
     * What's playing, small, at the top right: the cover in a circle, moving bars while it plays, title · artist. OK on
     * it opens the music screensaver (the now-playing screen).
     */
    private float drawNowPill(Canvas c, float right, float cy) {
        Music.Track t = pendingSong();
        boolean loading = t != null;
        if (!loading) t = Music.track();
        if (!nowShown() || t == null) {
            nowRect.setEmpty();
            return right;
        }
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
        }
        String text = nowText;
        float width = 6 * k + disc + 14 * k + bars + 12 * k + chipText.measureText(text) + pad;
        nowRect.set(right - width, cy - h / 2, right, cy + h / 2);
        // Focused, it turns white with dark text and bars, as the tabs do.
        float f = Ui.clamp(nowFocus.value);
        int ink = blend(0xFFF4F4F6, 0xFF0A0B0F, f);
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
        MusicArt.Scene sc = MusicArt.get(getContext(), t, k, nowReady);
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
        if (playing) heroLayer.postInvalidateDelayed(66);   // the bars, at 15 frames a second
        if (loading) heroLayer.postInvalidateOnAnimation();   // the spinner, smooth
        return nowRect.left - 22 * k;
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
        text(c, it.label.toUpperCase(Ui.locale()), x, top - HERO_LABEL_GAP * k, labelPaint, 0xFFFFFFFF, 0.78f);
        String line = it.youtube ? Ui.tr("YouTube, without the ads")
                : "com.lagradost.cloudstream3".equals(it.pkg) && !recs.row(Recs.CONTINUE).isEmpty()
                ? Ui.plural("plural|one:{n} in Continue watching|other:{n} in Continue watching", recs.row(Recs.CONTINUE).size()) : "";
        if (!line.isEmpty()) text(c, line, x, L_HERO_META * k, metaPaint, 0xFFFFFFFF, 0.85f);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (!picksOn()) {
            drawClock(c);
            if (HomeData.coinCount() > 0) drawQuotes(c);
            return;
        }
        // The tabs: pills in a frosted capsule; the selected one is a faint pill, white with focus on it (and it grows a
        // little and lifts).
        // While the apps have focus they fade out and an "Apps" heading takes their place.
        float e = appsExpand.value, pillsA = 1 - smoothstep(0, 0.5f, e), headA = smoothstep(0.5f, 1, e);
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
        if (HomeData.coinCount() > 0) drawQuotes(c);
    }

    private void drawPills(Canvas c, float alpha) {
        boolean faded = alpha < 0.99f;
        if (faded) c.saveLayerAlpha(pillsRect.left - 20 * k, pillsRect.top - 10 * k, pillsRect.right + 20 * k, pillsRect.bottom + 20 * k, Math.round(255 * alpha));
        float pr = pillsRect.height() / 2;
        frosted(c, pillsRect, pr, 1);
        float focus = Ui.clamp(topFocus[tab].value), dim = zone == Z_SHELF ? 0.6f : 1;
        for (int i = 0; i < TABS; i++) {
            RectF r = topRects[i];
            if (i == tab) {
                float s = 1 + 0.06f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 6 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, 0.4f * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                // Focused: solid white, dark text (and grown, lifted). Selected while the focus is elsewhere: a faint
                // pill, white text. One turns into the other as the focus comes and goes.
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFF4F4F6, lerp(0.2f, 1, focus)));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            p.setColor(i == tab ? blend(0xFFF4F4F6, 0xFF0A0B0F, focus) : Ui.alpha(0xFFFFFFFF, 0.8f * dim));
            float tw = p.measureText(tabs()[i]);
            c.drawText(tabs()[i], r.centerX() - tw / 2, r.centerY() + Ui.halfCap(p), p);
        }
        if (faded) c.restore();
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
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale((float) w / image.getWidth(), (float) h / image.getHeight());
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        imageFill.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(r, radius, radius, imageFill);
        imageFill.setShader(null);
        imageFill.setAlpha(255);
    }

    /** Right of the tabs, before the round buttons: the weather in a glass chip (two-tone symbol, degrees, city), the time. */
    private void drawTopRight(Canvas c) {
        float right = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[TABS].left) - 22 * k;
        float cy = (L_TABBAR_TOP + L_TABBAR_H / 2f) * k;
        topClock.setColor(0xFFF4F4F6);
        float cw = topClock.measureText(clockText);
        c.drawText(clockText, right - cw, cy + Ui.halfCap(topClock), topClock);
        right -= cw + 22 * k;
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) {
            drawNowPill(c, right, cy);
            return;
        }
        String deg = HomeData.degrees(wx.temp), city = Config.CITY;
        float icon = 24 * k, chipH = 56 * k, padX = 22 * k;
        float w1 = chipText.measureText(deg), w2 = chipDim.measureText(city);
        float width = padX + icon + 12 * k + w1 + 12 * k + w2 + padX;
        chipRect.set(right - width, cy - chipH / 2, right, cy + chipH / 2);
        frosted(c, chipRect, chipH / 2, 1);
        float x = chipRect.left + padX;
        Symbols.weatherIcon(getContext(), c, wx.code, wx.isDay, x + icon / 2, cy, icon * 0.85f);
        x += icon + 12 * k;
        c.drawText(deg, x, cy + Ui.halfCap(chipText), chipText);
        x += w1 + 12 * k;
        c.drawText(city, x, cy + Ui.halfCap(chipDim), chipDim);
        drawNowPill(c, chipRect.left - 14 * k, cy);
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
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
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : b.card;
        if (img != null) {
            roundImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
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
            fill.setShader(null);
            fill.setColor(0x59FFFFFF);
            c.drawRoundRect(tmp, barH / 2, barH / 2, fill);
            tmp.right = tmp.left + tmp.width() * Ui.clamp(p.progress);
            fill.setColor(p.channel != null ? LIVE_RED : ACCENT);
            c.drawRoundRect(tmp, barH / 2, barH / 2, fill);
        }
        if (e > 0.98f) return;   // small cards (focus on the apps) have no titles
        fitPaint.set(lCardTitle);
        int ink = lCardTitle.getColor();
        lCardTitle.setColor(Ui.alpha(ink, (1 - e) * lerp(0.62f, 1, f)));   // the focused card's title stands out
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 38 * k, lCardTitle);
        lCardTitle.setColor(ink);
        String artist = p.song == null ? "" : p.song.artist();
        if (!artist.isEmpty()) {   // a song's card: its artist under the title, dimmer
            fitPaint.set(lCardArtist);
            lCardArtist.setAlpha(Math.round(255 * (1 - e)));
            c.drawText(TextUtils.ellipsize(artist, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 68 * k, lCardArtist);
            lCardArtist.setAlpha(255);
        }
    }

    /**
     * The round buttons (Live TV, HDMI Inputs and Settings by default): icon-only glass circles (light by day, smoked at
     * night); the focused one turns white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[TABS + i];
            float f = Ui.clamp(topFocus[TABS + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            if (f < 0.99f) frosted(c, cell, rad, 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
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
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false;
                if (cards.isEmpty() && chipsTab() && chipCount() > 0) {
                    zone = Z_CHIPS;
                    musicRefreshFocus = false;
                } else if (cards.isEmpty()) {
                    if (!picksOn() && lightButtons.isEmpty()) return true;   // no tabs and no buttons above
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : TABS;
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
                        musicRefreshFocus = false;
                    } else {
                        zone = Z_TOP;
                        topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                    }
                } else {
                    zone = Z_SHELF;
                    // The app in the same column once the row has grown (card i is at column i - scroll).
                    if (!shelf.isEmpty()) shelfIndex = Math.max(0, Math.min(shelf.size() - 1, Math.round(cardIndex - rowScroll.target)));
                }
                break;
            case Z_TOP:
                if (dx != 0 && nowShown() && (dx > 0 && topIndex == TABS - 1 || dx < 0 && topIndex == TABS)) {
                    zone = Z_NOW;
                    break;
                }
                if (dx != 0) {
                    int next = Math.max(picksOn() ? 0 : TABS, Math.min(TABS - 1 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < TABS) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (chipsTab() && topIndex < TABS && chipCount() > 0) {
                        zone = Z_CHIPS;
                        musicRefreshFocus = false;
                    } else if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearestCard(topRects[topIndex].centerX());
                        cardMoved();
                    }
                } else if (topIndex < TABS && !lightButtons.isEmpty()) {
                    topIndex = TABS;   // up from the tabs reaches the corner buttons without walking the row
                } else {
                    return true;
                }
                break;
            case Z_NOW:
                if (dx < 0) {
                    zone = Z_TOP;
                    topIndex = TABS - 1;
                    selectTab(topIndex);
                } else if (dx > 0) {
                    if (lightButtons.isEmpty()) return true;
                    zone = Z_TOP;
                    topIndex = TABS;
                } else if (dy > 0) {
                    zone = Z_TOP;
                    topIndex = tab;
                    return moveLight(0, 1);   // down as from the selected tab
                } else {
                    return true;
                }
                break;
            case Z_CHIPS:
                if (dx != 0 && tab == TAB_MUSIC && musicRefreshMove(dx)) {
                    heroDirty = true;
                    break;
                }
                musicRefreshFocus = false;
                if (dx != 0) {
                    int next = Math.max(0, Math.min(chipCount() - 1, chipSelected() + dx));
                    if (next == chipSelected()) return true;
                    selectChip(next);     // the row below changes with it
                    cardIndex = 0;
                    rowScroll.snap(0);
                    updateCards();
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;
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

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
