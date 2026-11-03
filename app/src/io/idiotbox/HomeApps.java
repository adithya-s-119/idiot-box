package io.idiotbox;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What shows up on the home screen and in the "All Apps" sheet, and how to open each thing.
 *
 * On the home shelf, each app is its colored logo on a dark card: the official logo in assets/logos/<key>.png
 * (downloaded from Wikimedia Commons by tools/logos.py) or, without one, the logo lifted from the app's TV art (the
 * Android TV "banner"), saved to disk after the first time. In "All Apps", 16:9 cards with the whole TV art (or the
 * icon on a dark card). Shortcuts (live TV, inputs, All Apps, settings) are icon buttons.
 */
final class HomeApps {
    private HomeApps() {}

    static final int APP = 0, LIVE_TV = 1, INPUT = 2, ALL_APPS = 3, SETTINGS = 4;
    /** The "HDMI Inputs" button: opens the bottom drawer on the inputs (the home screen handles it). */
    static final int INPUTS = 7;
    /** The refresh button at the top right: the selected tab's row fetched again (HomeView does it). */
    static final int REFRESH = 8;
    /** Image size of each card (16:9; on screen it shows up to ~330 px wide). */
    static final int TILE_W = 384, TILE_H = 216;

    static final class Item {
        int kind;
        String key, label, pkg, inputId, number;
        String uri;         // opens this address in the app instead of the app's home (a title's page)
        String symbol;      // icon for the shortcuts and special cards
        Bitmap tile;        // 16:9 card (apps and specials)
        Bitmap logo;        // colored logo with no background (shelf); null = use the card
        boolean connected = true;
        Bitmap icon;        // the app's own square icon (the apps row at rest)
        Bitmap blur;        // its TV card, tiny and blurred: the background while it has focus (scaled up, it stays soft)
        int tint = 0xFF2C2C31;   // the icon's main color (card behind the icon, the background when focused)
        boolean youtube;    // TizenTube: shown as YouTube (its logo and colors)
    }

    /** TizenTube (a YouTube client) wears YouTube's colors on the home screen. */
    static final String TIZENTUBE = "io.gh.reisxd.tizentube";

    /** A 64×36 copy of the card, box-blurred: drawn over the whole screen it reads as the card's colors, out of focus. */
    private static Bitmap blurred(Bitmap tile) {
        try {
            return Bitmap.createBitmap(Backdrop.reduce(tile, 64, 36, 3, 3), 64, 36, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** An app's icon and its color, made again only when the app is updated. */
    private static final class Icon {
        long updated;
        Bitmap bitmap;
        int tint;
    }

    private static final Map<String, Icon> ICONS = new HashMap<>();

    /** The app's icon drawn at 160 px, and its main color (the most saturated common color of its opaque pixels). */
    private static void loadIcon(PackageManager pm, Item it) {
        long updated;
        try {
            updated = pm.getPackageInfo(it.pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        synchronized (ICONS) {
            Icon known = ICONS.get(it.pkg);
            if (known != null && known.updated == updated) {
                it.icon = known.bitmap;
                it.tint = known.tint;
                return;
            }
        }
        try {
            android.graphics.drawable.Drawable d = pm.getApplicationIcon(it.pkg);
            int size = 160;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            it.icon = b;
            float[] hsv = new float[3];
            float best = -1;
            long r = 0, g = 0, bl = 0, n = 0;
            int[] px = new int[size * size];
            b.getPixels(px, 0, size, 0, 0, size, size);
            for (int i = 0; i < px.length; i += 7) {
                int p = px[i];
                if ((p >>> 24) < 200) continue;
                android.graphics.Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                bl += p & 255;
                n++;
            }
            if (n > 20) it.tint = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (bl / n);
            Icon made = new Icon();
            made.updated = updated;
            made.bitmap = it.icon;
            made.tint = it.tint;
            synchronized (ICONS) { ICONS.put(it.pkg, made); }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
    }

    /**
     * Apps on the home shelf, in this order, when installed (up to MAX_SHELF): key, name, package, brand color (for
     * its logo on glass) and how to lift the logo from the app's TV art: "tint" = a white logo takes the art's
     * background color (Prime Video, Globoplay); "play" = the white triangle inside the red button stays white
     * (YouTube); empty = the art's colors as they are. Edit freely.
     */
    private static final String[][] SHELF = {
        {"netflix", "Netflix", "com.netflix.ninja", "E50914", ""},
        {"youtube", "YouTube", "com.amazon.firetv.youtube", "FF0033", "play"},
        {"prime", "Prime Video", "com.amazon.firebat", "1A98FF", "tint"},
        {"prime", "Prime Video", "com.amazon.amazonvideo.livingroom", "1A98FF", "tint"},
        {"disney", "Disney+", "com.disney.disneyplus", "1838C8", ""},
        {"max", "Max", "com.wbd.stream", "6C3BE0", ""},
        {"max", "HBO Max", "com.hbo.hbonow", "6C3BE0", ""},
        {"appletv", "Apple TV", "com.apple.atve.amazon.appletv", "B8BCC6", ""},
        {"hulu", "Hulu", "com.hulu.livingroomplus", "1CE783", ""},
        {"paramount", "Paramount+", "com.cbs.ott", "0064FF", ""},
        {"peacock", "Peacock", "com.peacocktv.peacockandroid", "6E55DC", ""},
        {"globoplay", "Globoplay", "com.globo.globotv", "FF5A1F", "tint"},
        {"spotify", "Spotify", "com.spotify.tv.android", "1DB954", ""},
    };
    static final int MAX_SHELF = 8;
    /** Kept out of "All Apps" (only hidden, still installed). Add the ones your TV shows that you never use. */
    private static final Set<String> HIDDEN = new HashSet<>(java.util.Arrays.asList(
            "com.amazon.audiohome"));               // Music & More (Amazon's audio hub; the Music tab covers it)

    private static final Map<String, Bitmap> TILES = new HashMap<>();   // key → ready card (on the GPU)
    private static final Map<String, Bitmap> BLURS = new HashMap<>();   // key → its card blurred (blurred())
    private static final Map<String, Bitmap> LOGOS = new HashMap<>();   // key → colored logo with no background

    /**
     * Home screen apps, then All Apps. `extract`: also lifts the logos that are not on disk yet (slow: background only); without it,
     * those apps keep their TV art until the next load.
     */
    static List<Item> shelf(Context c, boolean extract) {
        List<Item> items = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String pkg : shelfPackages(c)) {
            if (items.size() >= MAX_SHELF) break;
            Intent launch = launchIntent(pm, pkg);
            if (launch == null) continue;
            String[] d = known(pkg);
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            if (d == null) {
                // An app picked in Settings > Shelf that isn't in SHELF: its own name and TV art.
                ResolveInfo ri = pm.resolveActivity(launch, 0);
                it.key = pkg;
                it.label = ri != null ? String.valueOf(ri.loadLabel(pm)) : pkg;
                it.tile = appTile(c, null, pkg, 0);
            } else {
                it.key = d[0];
                it.label = d[1];
                it.logo = shelfLogo(c, d[0], d[2], d[4], extract);
                if (it.logo == null) it.tile = appTile(c, d[0], d[2], 0xFF000000 | Integer.parseInt(d[3], 16));
            }
            it.youtube = pkg.startsWith(TIZENTUBE);
            if (it.youtube) {
                // TizenTube wears YouTube's official logo: assets/logos/youtube.png is Wikimedia Commons' "YouTube
                // 2024.svg" (its text made white for the dark cards), youtube-icon.png its play button.
                it.logo = ownLogoCached(c, "youtube");
                if (it.logo != null) it.tile = null;   // the logo on YouTube's card, not TizenTube's own art
                it.icon = square(ownLogoCached(c, "youtube-icon"));
                it.tint = 0xFFC4302B;
            } else {
                if (it.tile != null) it.blur = blurOf(it.tile);
                loadIcon(pm, it);
            }
            items.add(it);
        }
        // Last, after the pinned apps: All Apps (not an app: never moved, saved or counted toward MAX_SHELF).
        items.add(special(c, ALL_APPS, "all", Ui.tr("All Apps"), "apps"));
        return items;
    }

    private static String[] known(String pkg) {
        for (String[] d : SHELF) if (d[2].equals(pkg)) return d;
        return null;
    }

    /** The shelf's packages in order: the user's list (Settings > Shelf), or the built-in one. */
    static List<String> shelfPackages(Context c) {
        List<String> custom = Prefs.shelf();
        if (custom != null) return custom;
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String[] d : SHELF) if (out.size() < MAX_SHELF && launchIntent(pm, d[2]) != null) out.add(d[2]);
        return out;
    }

    /** Every app the shelf could hold, as {package, name}: the ones from SHELF first, then the other TV apps by name. */
    static List<String[]> shelfCandidates(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] d : SHELF) if (launchIntent(pm, d[2]) != null && seen.add(d[2])) out.add(new String[] {d[2], d[1]});
        List<String[]> more = new ArrayList<>();
        for (String[] app : launchable(c)) {
            if (app[0].equals(c.getPackageName()) || HIDDEN.contains(app[0]) || !seen.add(app[0])) continue;
            more.add(app);
        }
        Collections.sort(more, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        out.addAll(more);
        return out;
    }

    // MARK: - Top buttons (the round ones at the top right)

    static final int MAX_TOP = 2;

    /** The buttons at the top right: Refresh and Settings. */
    static List<Item> topButtons() {
        List<Item> items = new ArrayList<>();
        items.add(shortcut(REFRESH, "refresh", Ui.tr("Refresh"), "refresh"));
        items.add(shortcut(SETTINGS, "settings", Ui.tr("light|Settings"), "settings"));
        return items;
    }

    /** Live TV, the HDMI inputs with a device sending a signal, and All Apps. */
    static List<Item> shortcuts(Context c) {
        List<Item> items = new ArrayList<>();
        if (liveTv(c) != null) {
            items.add(shortcut(LIVE_TV, "live", Ui.tr("Live TV"), "tv"));
        }
        items.addAll(inputs(c, false));
        items.add(shortcut(ALL_APPS, "all", Ui.tr("All Apps"), "apps"));
        return items;
    }

    private static Item shortcut(int kind, String key, String label, String symbol) {
        Item it = new Item();
        it.kind = kind;
        it.key = key;
        it.label = label;
        it.symbol = symbol;
        return it;
    }

    /** Idiot Box settings, in the corner of the home screen. */
    static Item settings() {
        return shortcut(SETTINGS, "settings", Ui.tr("Settings"), "settings");
    }

    /** A streaming service's app (the "Watch" button of the featured pick). */
    static Item app(String pkg, String label, String uri) {
        Item it = app(pkg, label);
        it.uri = uri;
        return it;
    }

    static Item app(String pkg, String label) {
        Item it = new Item();
        it.kind = APP;
        it.key = pkg;
        it.pkg = pkg;
        it.label = label;
        return it;
    }

    /** For the bottom drawer: live TV and every HDMI input (the ones with no device are shown dimmed). */
    static List<Item> allInputs(Context c) {
        List<Item> items = new ArrayList<>();
        if (liveTv(c) != null) items.add(shortcut(LIVE_TV, "live", Ui.tr("Live TV"), "tv"));
        items.addAll(inputs(c, true));
        return items;
    }

    /**
     * HDMI inputs: only the ones with a device sending a signal (or all of them, with `all`). A name you gave the input
     * in the TV's settings is used as the label; without one, the name the device reports over HDMI-CEC ("PlayStation 5").
     * The icon follows what that name says the device is (see deviceSymbol).
     */
    private static List<Item> inputs(Context c, boolean all) {
        List<Item> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> list = new ArrayList<>();
        // A device that talks HDMI-CEC gets its own input, a child of the port's, named after the device: port input id
        // -> that name.
        Map<String, String> devices = new HashMap<>();
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() == null) {
                    list.add(info);
                } else {
                    devices.put(info.getParentId(), String.valueOf(info.loadLabel(c)).trim());
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
        for (int i = 0; i < list.size(); i++) {
            TvInputInfo info = list.get(i);
            int number = i + 1;
            Matcher m = DIGIT.matcher(String.valueOf(info.loadLabel(c)));
            if (m.find()) number = Integer.parseInt(m.group(1));
            // TCL TVs report "connected standby" for every port, with or without a device; only truly "connected"
            // (a device that is on and sending a signal) makes the input show up.
            int state = TvInputManager.INPUT_STATE_DISCONNECTED;
            try {
                state = tim.getInputState(info.getId());
            } catch (RuntimeException ignored) {
            }
            if (!all && state != TvInputManager.INPUT_STATE_CONNECTED) continue;
            CharSequence custom = null;
            try {
                custom = info.loadCustomLabel(c);
            } catch (RuntimeException ignored) {
            }
            String reported = devices.containsKey(info.getId()) ? devices.get(info.getId()) : "";
            String label = custom != null && custom.length() > 0 ? custom.toString()
                    : !reported.isEmpty() && state == TvInputManager.INPUT_STATE_CONNECTED ? reported : "HDMI " + number;
            String symbol = state == TvInputManager.INPUT_STATE_CONNECTED || custom != null
                    ? deviceSymbol(label) : "hdmi";
            Item it = shortcut(INPUT, "hdmi" + number, label, symbol);
            it.inputId = info.getId();
            it.number = String.valueOf(number);
            it.connected = state == TvInputManager.INPUT_STATE_CONNECTED;
            out.add(it);
        }
        Collections.sort(out, (a, b) -> a.number.compareTo(b.number));
        return out;
    }

    private static final Pattern DIGIT = Pattern.compile("(\\d)");

    /** Words in a device's name, by the icon they pick: a game console, a streaming box, a sound system, a TV box. */
    private static final String[][] DEVICE_WORDS = {
        {"gamepad", "playstation", "ps5", "ps4", "ps3", "xbox", "nintendo", "switch", "steam deck", "console", "game"},
        {"play", "apple tv", "chromecast", "fire tv", "firetv", "roku", "shield", "mi box", "tv box", "tvbox", "stick", "streaming", "google tv", "android tv"},
        {"speaker", "soundbar", "sound bar", "receiver", "denon", "yamaha", "marantz", "onkyo", "bose", "sonos", "jbl", "audio", "home theater"},
        {"tv", "cable", "set-top", "decoder", "decodificador", "tuner", "sky", "claro", "vivo"},
    };

    /** The icon for an HDMI device, from the words in its name; a name that says nothing (or a plain port) is the HDMI plug. */
    static String deviceSymbol(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String[] group : DEVICE_WORDS) {
            for (int i = 1; i < group.length; i++) if (n.contains(group[i])) return group[0];
        }
        return "hdmi";
    }

    /** Installed, enabled TV apps that are not on the home screen (the TV's Settings is among them), plus our own. */
    static List<Item> others(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new HashSet<>();
        // A built-in app is skipped only while it is on the shelf: one the person took off must still be listed here.
        List<String> onShelf = shelfPackages(c);
        for (String[] d : SHELF) if (onShelf.contains(d[2])) skip.add(d[2]);
        skip.add(c.getPackageName());
        skip.addAll(HIDDEN);
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] app : launchable(c)) {
            String pkg = app[0];
            if (skip.contains(pkg) || !seen.add(pkg)) continue;
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            it.key = pkg;
            it.label = app[1];
            it.tile = appTile(c, null, pkg, 0);
            items.add(it);
        }
        Collections.sort(items, (a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        synchronized (HomeApps.class) {
            TILES.keySet().removeIf(k -> k.contains(".") && !seen.contains(k));
            BLURS.keySet().retainAll(TILES.keySet());
        }
        Item own = shortcut(SETTINGS, "idiotbox-settings", "Idiot Box", "sparkles");
        own.tile = ownTile(c);
        items.add(own);
        return items;
    }

    /** Idiot Box's own card in All Apps: its TV banner (res/drawable-nodpi/banner.png, from tools/art.html). */
    private static synchronized Bitmap ownTile(Context c) {
        Bitmap cached = TILES.get("idiotbox-settings");
        if (cached != null) return cached;
        Bitmap banner = BitmapFactory.decodeResource(c.getResources(), R.drawable.banner);
        if (banner == null) return symbolTile(c, "idiotbox-settings", "sparkles");
        Bitmap b = Bitmap.createScaledBitmap(banner, TILE_W, TILE_H, true);
        if (b != banner) banner.recycle();
        b.setHasMipMap(true);   // drawn smaller than it is while the row grows: mipmaps (a GPU copy has none)
        TILES.put("idiotbox-settings", b);
        return b;
    }

    private static Item special(Context c, int kind, String key, String label, String symbol) {
        Item it = shortcut(kind, key, label, symbol);
        it.tile = symbolTile(c, key, symbol);
        return it;
    }

    /** Opens the item with the system zoom transition growing out of the card (the app starts opening right away). */
    static boolean open(Activity a, Item it, View from, int x, int y, int w, int h) {
        Intent intent, plain = null;   // plain: the app's own home, when it won't take the title's address
        switch (it.kind) {
            case APP:
                intent = launchIntent(a.getPackageManager(), it.pkg);
                plain = intent;
                if (plain != null) plain.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (intent != null && it.uri != null && it.uri.startsWith("intent:")) {
                    // TvPicks: Watch Next and preview rows keep a whole intent; kept to its own app.
                    try {
                        intent = Intent.parseUri(it.uri, Intent.URI_INTENT_SCHEME).setPackage(it.pkg);
                        intent.setSelector(null);
                    } catch (java.net.URISyntaxException e) {
                        AppLog.w("couldn't read " + it.label + "'s link: " + e.getMessage());
                    }
                } else if (intent != null && it.uri != null) {
                    intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it.uri)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(it.pkg);
                }
                break;
            case LIVE_TV:
                intent = liveTv(a);
                break;
            case INPUT:
                intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.inputId));
                break;
            case SETTINGS:
                intent = new Intent().setComponent(new ComponentName(a, SettingsActivity.class));
                break;
            default:
                return false;
        }
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            // Settings slides in as a sidebar by itself; everything else grows out of its tile.
            ActivityOptions options = it.kind == SETTINGS ? ActivityOptions.makeCustomAnimation(a, 0, 0)
                    : ActivityOptions.makeScaleUpAnimation(from, x, y, Math.max(w, 1), Math.max(h, 1));
            a.startActivity(intent, options.toBundle());
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + it.label + ": " + e.getMessage());
            if (plain == null || plain == intent) return false;
            plain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                a.startActivity(plain);
                return true;
            } catch (ActivityNotFoundException | SecurityException e2) {
                return false;
            }
        }
    }

    /**
     * The TV's settings. The standard settings request goes through a stop in Amazon's home screen app (its
     * SettingsActivity), and the Home redirect pulled it back to this home screen; Amazon's own request opens the
     * Settings page straight away (its MainSettingsActivity).
     */
    static Intent tvSettings(Activity a) {
        Intent amazon = new Intent("amazon.intent.action.MAINSETTINGS").addCategory("android.intent.category.MAINSETTINGS")
                .setComponent(new ComponentName(FireTv.LAUNCHER, FireTv.LAUNCHER + ".ui.MainSettingsActivity"));
        if (amazon.resolveActivity(a.getPackageManager()) != null) return amazon;
        return new Intent(Settings.ACTION_SETTINGS);
    }

    /** Live TV: the channel list of the antenna input (Fire TV Edition TVs have one; a stick has none). */
    static Intent liveTv(Context c) {
        String tuner = tunerInput(c);
        if (tuner == null) return null;
        Intent channels = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tuner));
        return channels.resolveActivity(c.getPackageManager()) != null ? channels : null;
    }

    /**
     * The TV's antenna (tuner) input, or null. The TV maker's hardware tuner comes first: system apps also declare
     * tuner inputs, and Android names the hardware ones "…/HW<id>".
     */
    static String tunerInput(Context c) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        String system = null, fallback = null;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_TUNER || info.isPassthroughInput()) continue;
                if (info.getServiceInfo() != null && info.getServiceInfo().applicationInfo != null
                        && (info.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                    if (info.getId().contains("/HW")) return info.getId();
                    if (system == null) system = info.getId();
                }
                if (fallback == null) fallback = info.getId();
            }
        } catch (RuntimeException ignored) {
        }
        return system != null ? system : fallback;
    }

    static List<String[]> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(tv, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (seen.add(pkg)) out.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
        }
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(phone, 0)) {
            String pkg = ri.activityInfo.packageName;
            if ((ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || !seen.add(pkg)) continue;
            out.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
        }
        return out;
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    // MARK: - Colored logos (shelf)

    /** Bump when the way logos are lifted changes (the ones saved on disk are redone). */
    private static final int LOGO_VERSION = 2;

    private static Bitmap shelfLogo(Context c, String key, String pkg, String mode, boolean extract) {
        synchronized (LOGOS) {
            if (LOGOS.containsKey(key)) return LOGOS.get(key);
        }
        Bitmap logo = ownLogo(c, key);
        File file = logoFile(c, key, pkg), none = new File(file.getPath() + ".none");
        if (logo == null && file.exists()) {
            logo = BitmapFactory.decodeFile(file.getPath());
            if (logo != null) logo = fit(logo, 512);   // older builds saved them up to 768 wide
        }
        if (logo == null) {
            if (none.exists()) {
                logo = null;
            } else if (!extract) {
                return null;
            } else {
                logo = bannerLogo(c, pkg, mode.equals("tint"), mode.equals("play"));
                saveLogo(logo, file, none);
            }
        }
        if (logo != null) logo.setHasMipMap(true);
        synchronized (LOGOS) {
            LOGOS.put(key, logo);
        }
        return logo;
    }

    /** File for the logo lifted from the TV art: it changes when the app is updated (the art may have changed). */
    private static File logoFile(Context c, String key, String pkg) {
        long updated = 0;
        try {
            updated = c.getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return new File(new File(c.getCacheDir(), "logos"), key + "-" + updated + "-v" + LOGO_VERSION + ".png");
    }

    private static void saveLogo(Bitmap logo, File file, File none) {
        File dir = file.getParentFile();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String key = file.getName().substring(0, file.getName().lastIndexOf('-', file.getName().lastIndexOf("-v") - 1));
        File[] old = dir.listFiles((d, name) -> name.matches(java.util.regex.Pattern.quote(key) + "-\\d+-v\\d+\\.png(\\.none)?"));
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        try (FileOutputStream out = new FileOutputStream(logo != null ? file : none)) {
            if (logo != null) logo.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            AppLog.w("logo not saved: " + e.getMessage());
        }
    }

    /**
     * Logo lifted from the app's TV art: the background is removed (estimated from the edges, gradients are fine), the
     * brand colors stay and black turns white (the card is dark). With `tint`, a white logo on a colored background
     * takes the background's color; with `holes`, holes inside colored shapes (the play triangle) turn white. The
     * edges are "decontaminated" (no halo from the background).
     */
    private static Bitmap bannerLogo(Context c, String pkg, boolean tint, boolean holes) {
        Drawable d = bannerDrawable(c, pkg);
        if (d == null) return null;
        final int w = 768, h = 432;
        Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(src);
        canvas.drawColor(0xFF15161C);
        d.setBounds(0, 0, w, h);
        d.draw(canvas);
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        src.recycle();
        // Edge profiles (a 3% band), smoothed: the background at any point is the "patch" spanning the four sides.
        int band = Math.round(h * 0.03f);
        float[][] left = new float[h][3], right = new float[h][3], top = new float[w][3], bottom = new float[w][3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < band; x++) {
                add(left[y], px[y * w + x], 1f / band);
                add(right[y], px[y * w + w - 1 - x], 1f / band);
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < band; y++) {
                add(top[x], px[y * w + x], 1f / band);
                add(bottom[x], px[(h - 1 - y) * w + x], 1f / band);
            }
        }
        smooth(left, 25);
        smooth(right, 25);
        smooth(top, 41);
        smooth(bottom, 41);
        float[] p00 = top[0], p10 = top[w - 1], p01 = bottom[0], p11 = bottom[w - 1];
        float[] mean = new float[3];
        for (int i = 0; i < 3; i++) mean[i] = (p00[i] + p10[i] + p01[i] + p11[i] + top[w / 2][i] + bottom[w / 2][i] + left[h / 2][i] + right[h / 2][i]) / 8;
        float bgLum = lum(mean), bgSat = sat(mean);
        int[] out = new int[w * h];
        float[] bg = new float[3], col = new float[3];
        int opaque = 0;
        for (int y = 0; y < h; y++) {
            float v = (float) y / (h - 1);
            for (int x = 0; x < w; x++) {
                float u = (float) x / (w - 1);
                for (int i = 0; i < 3; i++) {
                    bg[i] = (1 - v) * top[x][i] + v * bottom[x][i] + (1 - u) * left[y][i] + u * right[y][i]
                            - ((1 - u) * (1 - v) * p00[i] + u * (1 - v) * p10[i] + (1 - u) * v * p01[i] + u * v * p11[i]);
                }
                int p = px[y * w + x];
                col[0] = ((p >> 16) & 255) / 255f;
                col[1] = ((p >> 8) & 255) / 255f;
                col[2] = (p & 255) / 255f;
                float dist = (float) Math.sqrt(((col[0] - bg[0]) * (col[0] - bg[0]) + (col[1] - bg[1]) * (col[1] - bg[1])
                        + (col[2] - bg[2]) * (col[2] - bg[2])) / 3);
                float a = Ui.smoothstep(0.07f, 0.22f, dist);
                if (a <= 0.004f) continue;
                for (int i = 0; i < 3; i++) col[i] = Ui.clamp((col[i] - (1 - a) * bg[i]) / a);   // no background halo
                float l = lum(col), s = sat(col);
                if (bgLum > 0.6f && l < 0.42f && s < 0.3f) {
                    col[0] = col[1] = col[2] = 1f;                  // black text on a light background → white
                } else if (tint && bgLum <= 0.6f && bgLum > 0.1f && bgSat > 0.35f && l > 0.7f && s < 0.25f) {
                    vivid(bg, col);                                 // white logo on brand color → that color, vivid
                }
                out[y * w + x] = (Math.round(a * 255) << 24) | (Math.round(col[0] * 255) << 16) | (Math.round(col[1] * 255) << 8) | Math.round(col[2] * 255);
                if (a > 0.5f) opaque++;
            }
        }
        float area = (float) opaque / (w * h);
        if (area < 0.01f || area > 0.6f) return null;    // couldn't split the logo from the background: keep the card
        if (holes) fillHoles(out, w, h);
        Bitmap logo = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
        return fit(trimLogo(logo), 512);
    }

    /** The app's TV art (the Android TV banner), at the highest density the app has; null without one. */
    private static Drawable bannerDrawable(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            Intent launch = launchIntent(pm, pkg);
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                int res = ai.getBannerResource();
                if (res != 0) {
                    Drawable d = pm.getResourcesForApplication(ai.applicationInfo).getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                    if (d != null) return d;
                }
            }
            return pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** `b` scaled down to at most `max` pixels on its longest side. */
    private static Bitmap fit(Bitmap b, int max) {
        float scale = Math.min(1f, (float) max / Math.max(b.getWidth(), b.getHeight()));
        if (scale >= 1f) return b;
        Bitmap out = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)), Math.max(1, Math.round(b.getHeight() * scale)), true);
        if (out != b) b.recycle();
        return out;
    }

    /** Closed holes inside colored shapes (the play triangle) turn white; the ones in letters don't. */
    private static void fillHoles(int[] px, int w, int h) {
        byte[] mark = new byte[w * h];   // 1 = outside (reached from the border), 2 = hole already visited
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) {
            for (int y : new int[] {0, h - 1}) {
                int i = y * w + x;
                if (mark[i] == 0 && (px[i] >>> 24) < 128) { mark[i] = 1; queue[tail++] = i; }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x : new int[] {0, w - 1}) {
                int i = y * w + x;
                if (mark[i] == 0 && (px[i] >>> 24) < 128) { mark[i] = 1; queue[tail++] = i; }
            }
        }
        while (head < tail) {
            int i = queue[head++], x = i % w, y = i / w;
            int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
            for (int n : next) {
                if (n >= 0 && mark[n] == 0 && (px[n] >>> 24) < 128) { mark[n] = 1; queue[tail++] = n; }
            }
        }
        for (int start = 0; start < w * h; start++) {
            if (mark[start] != 0 || (px[start] >>> 24) >= 128) continue;
            head = 0;
            tail = 0;
            queue[tail++] = start;
            mark[start] = 2;
            float satSum = 0;
            int border = 0;
            while (head < tail) {
                int i = queue[head++], x = i % w, y = i / w;
                int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
                for (int n : next) {
                    if (n < 0) continue;
                    if ((px[n] >>> 24) >= 128) {
                        float[] c = {((px[n] >> 16) & 255) / 255f, ((px[n] >> 8) & 255) / 255f, (px[n] & 255) / 255f};
                        satSum += sat(c);
                        border++;
                    } else if (mark[n] == 0) {
                        mark[n] = 2;
                        queue[tail++] = n;
                    }
                }
            }
            if (border > 0 && satSum / border > 0.45f && tail < w * h / 20) {
                for (int k = 0; k < tail; k++) px[queue[k]] = 0xFFFFFFFF;
            }
        }
    }

    private static Bitmap trimLogo(Bitmap b) {
        Bitmap t = trim(b);
        int pad = Math.max(2, Math.round(Math.max(t.getWidth(), t.getHeight()) * 0.02f));
        Bitmap out = Bitmap.createBitmap(t.getWidth() + 2 * pad, t.getHeight() + 2 * pad, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(t, pad, pad, null);
        return out;
    }

    private static void add(float[] acc, int p, float k) {
        acc[0] += ((p >> 16) & 255) / 255f * k;
        acc[1] += ((p >> 8) & 255) / 255f * k;
        acc[2] += (p & 255) / 255f * k;
    }

    /** Moving average (odd window) on each channel of the profile. */
    private static void smooth(float[][] profile, int window) {
        int n = profile.length, r = window / 2;
        float[][] copy = new float[n][];
        for (int i = 0; i < n; i++) copy[i] = profile[i].clone();
        for (int i = 0; i < n; i++) {
            float[] sum = new float[3];
            int count = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(n - 1, i + r); j++) {
                for (int k = 0; k < 3; k++) sum[k] += copy[j][k];
                count++;
            }
            for (int k = 0; k < 3; k++) profile[i][k] = sum[k] / count;
        }
    }

    private static float lum(float[] c) {
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    }

    private static float sat(float[] c) {
        float max = Math.max(c[0], Math.max(c[1], c[2])), min = Math.min(c[0], Math.min(c[1], c[2]));
        return max <= 0 ? 0 : (max - min) / max;
    }

    /** The local background color (gradients included), vivid and bright: the logo stands out on the dark card. */
    private static void vivid(float[] bg, float[] out) {
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV(Math.round(bg[0] * 255), Math.round(bg[1] * 255), Math.round(bg[2] * 255), hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = 1f;
        int c = android.graphics.Color.HSVToColor(hsv);
        out[0] = ((c >> 16) & 255) / 255f;
        out[1] = ((c >> 8) & 255) / 255f;
        out[2] = (c & 255) / 255f;
    }

    // MARK: - Cards

    /** The app's card, made once (on the GPU: it's only drawn), with its blurred copy for blurOf. */
    private static synchronized Bitmap appTile(Context c, String key, String pkg, int brand) {
        String cacheKey = key != null ? key : pkg;
        if (TILES.containsKey(cacheKey)) return TILES.get(cacheKey);
        Bitmap logo = key == null ? null : ownLogo(c, key);
        Bitmap tile = logo != null ? logoTile(logo, brand) : bannerTile(c, pkg);
        if (tile == null) tile = iconTile(c, pkg);
        BLURS.put(cacheKey, blurred(tile));
        tile.setHasMipMap(true);   // drawn smaller than it is while the row grows: mipmaps (a GPU copy has none)
        TILES.put(cacheKey, tile);
        return tile;
    }

    /** The blurred copy of a card appTile made, or null. */
    private static synchronized Bitmap blurOf(Bitmap tile) {
        for (Map.Entry<String, Bitmap> e : TILES.entrySet()) if (e.getValue() == tile) return BLURS.get(e.getKey());
        return null;
    }

    /** The app's official TV art (the Android TV banner) on a card. */
    private static Bitmap bannerTile(Context c, String pkg) {
        Drawable d = bannerDrawable(c, pkg);
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        canvas.drawColor(0xFF15161C);   // banners with transparency sit on a dark background
        // In its own shape: a banner that isn't 16:9 (PLAYFy TV's) is fitted inside, not stretched (its lettering
        // came out wide); one within 6% of 16:9 fills the card.
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        float ratio = iw > 0 && ih > 0 ? (float) iw / ih : (float) TILE_W / TILE_H, card = (float) TILE_W / TILE_H;
        if (Math.abs(ratio / card - 1) < 0.06f) {
            d.setBounds(0, 0, TILE_W, TILE_H);
        } else {
            float scale = Math.min((float) TILE_W / iw, (float) TILE_H / ih);
            int w = Math.round(iw * scale), h = Math.round(ih * scale);
            d.setBounds((TILE_W - w) / 2, (TILE_H - h) / 2, (TILE_W + w) / 2, (TILE_H + h) / 2);
        }
        d.draw(canvas);
        return b;
    }

    /** No banner: the app's icon (in its proper shape, with some margin) on a dark card. */
    private static Bitmap iconTile(Context c, String pkg) {
        Bitmap b = darkTile();
        Canvas canvas = new Canvas(b);
        Drawable icon = null;
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.icon != 0) {
                try {
                    icon = pm.getResourcesForApplication(ai).getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
            if (icon == null) icon = pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (icon == null) return b;
        int size = Math.round(TILE_H * 0.56f);
        float left = (TILE_W - size) / 2f, top = (TILE_H - size) / 2f;
        Drawable[] layers = adaptiveLayers(icon);
        if (layers != null) {
            // Adaptive icon (Android 8+): the layers clipped to a squircle, like an Apple icon.
            Path clip = new Path();
            clip.addRoundRect(new RectF(left, top, left + size, top + size), size * 0.2237f, size * 0.2237f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            int pad = size / 4;
            for (Drawable layer : layers) {
                if (layer == null) continue;
                layer.setBounds(Math.round(left) - pad, Math.round(top) - pad, Math.round(left) + size + pad, Math.round(top) + size + pad);
                layer.draw(canvas);
            }
            canvas.restore();
        } else {
            // Legacy icon (Fire OS 6, or a free-form drawing with transparency): smaller, no frame.
            int s = Math.round(TILE_H * 0.46f);
            icon.setBounds((TILE_W - s) / 2, (TILE_H - s) / 2, (TILE_W + s) / 2, (TILE_H + s) / 2);
            icon.draw(canvas);
        }
        return b;
    }

    /** An adaptive icon's background and foreground, or null on Fire OS 6, where Android has no adaptive icons. */
    private static Drawable[] adaptiveLayers(Drawable icon) {
        try {
            Class<?> type = Class.forName("android.graphics.drawable.AdaptiveIconDrawable");
            if (!type.isInstance(icon)) return null;
            return new Drawable[] {
                    (Drawable) type.getMethod("getBackground").invoke(icon),
                    (Drawable) type.getMethod("getForeground").invoke(icon),
            };
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** Settings and the like: the icon in white on a dark card. */
    private static synchronized Bitmap symbolTile(Context c, String key, String symbol) {
        if (TILES.containsKey(key)) return TILES.get(key);
        Bitmap b = darkTile();
        Symbols.draw(c, new Canvas(b), symbol, TILE_W / 2f, TILE_H / 2f, TILE_H * 0.36f, 0xF2FFFFFF);
        b.setHasMipMap(true);
        TILES.put(key, b);
        return b;
    }

    /** The official logo (no background) on dark glass with a faint brand tint coming from below. */
    private static Bitmap logoTile(Bitmap logo, int brand) {
        Bitmap b = darkTile();
        Canvas canvas = new Canvas(b);
        Paint glow = new Paint();
        glow.setShader(new RadialGradient(TILE_W / 2f, TILE_H * 1.15f, TILE_W * 0.75f, Ui.alpha(brand, 0.38f), 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, TILE_W, TILE_H, glow);
        boolean wide = logo.getWidth() > logo.getHeight() * 1.35f;
        float maxW = TILE_W * (wide ? 0.62f : 0.4f), maxH = TILE_H * (wide ? 0.42f : 0.56f);
        float scale = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
        float w = logo.getWidth() * scale, h = logo.getHeight() * scale;
        canvas.drawBitmap(logo, null, new RectF((TILE_W - w) / 2, (TILE_H - h) / 2, (TILE_W + w) / 2, (TILE_H + h) / 2),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return b;
    }

    /** [b] in the middle of a transparent square (the apps row draws icons square). */
    private static Bitmap square(Bitmap b) {
        if (b == null || b.getWidth() == b.getHeight()) return b;
        int side = Math.max(b.getWidth(), b.getHeight());
        Bitmap out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(b, (side - b.getWidth()) / 2f, (side - b.getHeight()) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static Bitmap darkTile() {
        Bitmap b = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, 0, TILE_H, 0xFF2B2E3A, 0xFF17191F, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, TILE_W, TILE_H, bg);
        return b;
    }

    /** The bundled logo, read once per process: reading it again on every load made a new image and filled caches. */
    private static Bitmap ownLogoCached(Context c, String key) {
        synchronized (LOGOS) {
            if (LOGOS.containsKey("own:" + key)) return LOGOS.get("own:" + key);
        }
        Bitmap b = ownLogo(c, key);
        synchronized (LOGOS) {
            LOGOS.put("own:" + key, b);
        }
        return b;
    }

    /** Official logo (tools/logos.py): trims the transparent margins and scales it down to at most 512 px. */
    private static Bitmap ownLogo(Context c, String key) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getAssets().open("logos/" + key + ".png")) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / (opts.inSampleSize * 2) >= 640) opts.inSampleSize *= 2;
            Bitmap b;
            try (InputStream in = c.getAssets().open("logos/" + key + ".png")) {
                b = BitmapFactory.decodeStream(in, null, opts);
            }
            if (b == null) return null;
            return fit(trim(b), 512);
        } catch (IOException e) {
            return null;
        }
    }

    private static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        int top = h, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > 10) {
                    if (y < top) top = y;
                    bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        if (bottom < 0) return b;
        return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
    }
}
