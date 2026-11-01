package io.idiotbox;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The pictures of the music screensaver for one song, made off the main thread (after Klangbild): the cover as a card
 * with its mirror image fading into the floor (FlowCards), the faint light it throws on the wall above the cover flow
 * (FlowLight: at most about L* 8, in the colour of the cover's top edge) and that colour at full strength, for the
 * progress line. Covers come from YouTube at their largest for YouTube songs, else from the app's own picture, and
 * are kept on disk.
 */
final class MusicArt {
    /** Everything the screen needs for one song. */
    static final class Scene {
        final Bitmap card;        // the cover and its mirror image: side × (side + gap + reflection)
        final int side;
        final Bitmap light;       // the wall light over the whole screen, at a quarter of its size
        final int accent;         // the light's colour at full strength (sRGB)

        Scene(Bitmap card, int side, Bitmap light, int accent) {
            this.card = card;
            this.side = side;
            this.light = light;
            this.accent = accent;
        }
    }

    interface Ready {
        void ready(String key, Scene scene);
    }

    // Klangbild's measures at 1920 × 1080 (its 960 × 540 dp doubled).
    static final float CARD = 600, CX = 606, CY = 540, COLUMN = 1196;
    static final float RADIUS = 13, GAP = 8, REFLECTION = 0.3f;
    private static final float LIGHT_SIGMA_X = 360, LIGHT_SIGMA_Y = 180, LIGHT_ABOVE = 40, PEAK = 0.0088f, DARK_SHARE = 0.35f;
    private static final int MAP_W = 480, MAP_H = 270;

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, Scene> scenes = new LinkedHashMap<String, Scene>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Scene> eldest) {
            return size() > 5;
        }
    };

    private MusicArt() {}

    /** The scene for [t] if it's made, else null (it's made and [ready] called on the main thread). */
    static Scene get(Context c, Music.Track t, float k, Ready ready) {
        String key = t.key() + "@" + k;
        synchronized (scenes) {
            if (scenes.containsKey(key)) return scenes.get(key);
            scenes.put(key, null);
        }
        Context app = c.getApplicationContext();
        io.execute(() -> {
            Scene s = null;
            try {
                Bitmap cover = cover(app, t);
                if (cover != null) s = make(cover, k);
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("music art: " + e.getMessage());
            }
            final Scene done = s;
            synchronized (scenes) { scenes.put(key, done); }
            if (done != null) main.post(() -> ready.ready(t.key(), done));
        });
        return null;
    }

    // MARK: - The cover

    private static Bitmap cover(Context c, Music.Track t) {
        String id = t.youtubeId();
        if (id != null) {
            for (String size : new String[] {"maxresdefault", "hq720", "sddefault"}) {
                Bitmap b = load(c, "https://i.ytimg.com/vi/" + id + "/" + size + ".jpg");
                if (b != null) return b;
            }
        }
        if (t.artUri.startsWith("http")) {
            Bitmap b = load(c, t.artUri.replaceFirst("^http:", "https:"));
            if (b != null) return b;
        }
        return t.art;
    }

    /** A picture from the web, kept in the cache folder; null if it's missing or YouTube's tiny "no picture". */
    private static Bitmap load(Context c, String url) {
        File dir = new File(c.getCacheDir(), "music");
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.length());
        if (!f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File part = new File(f.getPath() + ".part");
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(8000);
                h.setReadTimeout(15000);
                if (h.getResponseCode() != 200) {
                    h.disconnect();
                    return null;
                }
                try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    h.disconnect();
                }
                if (!part.renameTo(f)) return null;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                return null;
            }
        }
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null || b.getWidth() < 300) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        return b;
    }

    // MARK: - The scene

    private static Scene make(Bitmap cover, float k) {
        int side = Math.round(CARD * k);
        Bitmap square = square(cover, side);
        Bitmap card = card(square, k);
        float[] edge = topEdge(square);
        float[] lab = Oklab.fromLinear(edge[0], edge[1], edge[2]);
        float chroma = (float) Math.hypot(lab[1], lab[2]);
        float[] lch = chroma < 0.02f ? new float[] {0.70f, 0.045f, LAMP_HUE}
                : new float[] {0.62f, Math.min(chroma * 1.2f, 0.12f), (float) Math.atan2(lab[2], lab[1])};
        // A little warmed by the lamp, as Klangbild's LampLight.lightTint.
        float a = (float) (lch[1] * Math.cos(lch[2])), b = (float) (lch[1] * Math.sin(lch[2]));
        float la = (float) (0.045 * Math.cos(LAMP_HUE)), lb = (float) (0.045 * Math.sin(LAMP_HUE));
        a += (la - a) * 0.35f;
        b += (lb - b) * 0.35f;
        float[] lin = Oklab.toLinear(lch[0], a, b);
        float peak = PEAK * (DARK_SHARE + (1 - DARK_SHARE) * smoothstep(0.05f, 0.35f, lab[0]));
        Bitmap light = light(lin, peak, k);
        int accent = Oklab.argb(lin[0], lin[1], lin[2]);
        if (square != cover) square.recycle();
        return new Scene(card, side, light, accent);
    }

    /** The middle square of the picture (a video's thumbnail is wide; a square cover inside it stands in its middle). */
    private static Bitmap square(Bitmap src, int side) {
        int s = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - s) / 2, y = (src.getHeight() - s) / 2;
        Bitmap out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(src, new android.graphics.Rect(x, y, x + s, y + s), new RectF(0, 0, side, side), p);
        return out;
    }

    /** FlowCards: the print with softly rounded corners and, 4 dp below it, its mirror image fading out (30 % at the card). */
    private static Bitmap card(Bitmap square, float k) {
        int side = square.getWidth(), gap = Math.round(GAP * k), refl = Math.round(side * REFLECTION), pad = 2;
        Bitmap out = Bitmap.createBitmap(side + 2 * pad, side + gap + refl + 2 * pad, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        BitmapShader shader = new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Paint print = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        print.setShader(shader);
        float r = RADIUS * k;
        Matrix m = new Matrix();
        m.setTranslate(pad, pad);
        shader.setLocalMatrix(m);
        c.drawRoundRect(new RectF(pad, pad, pad + side, pad + side), r, r, print);
        edgeLight(c, pad, side, r, k);
        // The mirror image, set pixel by pixel (no layer or blend mode to go wrong): the bottom rows upside down, as
        // strong as FlowCards' fade (30 % at the card, a third of that 35 % down, a twelfth at 70 %, none at the end),
        // its top corners rounded as the card's bottom ones.
        int top = pad + side + gap;
        int[] row = new int[side];
        for (int ry = 0; ry < refl; ry++) {
            float t = (ry + 0.5f) / refl;
            float a = t < 0.35f ? lerp(0.3f, 0.105f, t / 0.35f) : t < 0.7f ? lerp(0.105f, 0.024f, (t - 0.35f) / 0.35f)
                    : lerp(0.024f, 0, (t - 0.7f) / 0.3f);
            square.getPixels(row, 0, side, 0, side - 1 - ry, side, 1);
            for (int x = 0; x < side; x++) {
                int p = row[x];
                int al = Math.round(((p >>> 24) & 255) * a * corner(x, ry, side, r));
                row[x] = (al << 24) | (p & 0xFFFFFF);
            }
            out.setPixels(row, 0, side, pad, top + ry, side, 1);
        }
        out.prepareToDraw();
        return out;
    }

    /**
     * An extremely faint light on the print's edges, so a black cover still shows on the black screen: a soft glow just
     * inside them (about 8 % white) and a hairline brightest at the top left, where the lamp is (15 % to 4 %).
     */
    private static void edgeLight(Canvas c, int pad, int side, float r, float k) {
        RectF print = new RectF(pad, pad, pad + side, pad + side);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(0x14FFFFFF);
        glow.setMaskFilter(new BlurMaskFilter(10 * k, BlurMaskFilter.Blur.INNER));
        c.drawRoundRect(print, r, r, glow);
        float line = Math.max(1, 1.5f * k);
        Paint hairline = new Paint(Paint.ANTI_ALIAS_FLAG);
        hairline.setStyle(Paint.Style.STROKE);
        hairline.setStrokeWidth(line);
        hairline.setShader(new LinearGradient(print.left, print.top, print.right, print.bottom, 0x26FFFFFF, 0x0AFFFFFF,
                Shader.TileMode.CLAMP));
        print.inset(line / 2, line / 2);
        c.drawRoundRect(print, r - line / 2, r - line / 2, hairline);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * Math.max(0, Math.min(1, t));
    }

    /** How much of pixel ([x], [y]) the rounded corners of a [side] square with radius [r] cover, at its top edge. */
    private static float corner(int x, int y, int side, float r) {
        if (y >= r) return 1;
        float cx = x < r ? r : x >= side - r ? side - r : -1;
        if (cx < 0) return 1;
        float dx = x + 0.5f - cx, dy = y + 0.5f - r;
        return Math.max(0, Math.min(1, r - (float) Math.hypot(dx, dy) + 0.5f));
    }

    /** The mean colour (linear) of the top sixteenth of the cover: the light it throws upward. */
    private static float[] topEdge(Bitmap square) {
        Bitmap small = Bitmap.createScaledBitmap(square, 64, 64, true);
        int rows = 4;
        int[] px = new int[64 * rows];
        small.getPixels(px, 0, 64, 0, 0, 64, rows);
        small.recycle();
        float r = 0, g = 0, b = 0;
        for (int p : px) {
            r += Oklab.toLin((p >> 16) & 255);
            g += Oklab.toLin((p >> 8) & 255);
            b += Oklab.toLin(p & 255);
        }
        return new float[] {r / px.length, g / px.length, b / px.length};
    }

    /**
     * FlowLight: an oval (sigma 180 × 90 dp) centred 20 dp above the middle cover, gone by the floor (whole down to 35 %
     * of the cover), faded toward the column (whole 220 dp before it, gone 30 dp before) and the screen's edges;
     * dithered so its few levels don't band.
     */
    private static Bitmap light(float[] tint, float peak, float k) {
        float y = 0.2126f * tint[0] + 0.7152f * tint[1] + 0.0722f * tint[2];
        float scale = y > 0 ? peak / y : 0;
        float w = 1920, h = 1080, sx = w / MAP_W, sy = h / MAP_H;
        float cx = CX, cy = CY - CARD / 2 - LIGHT_ABOVE, top = CY - CARD / 2, floor = top + CARD;
        int[] px = new int[MAP_W * MAP_H];
        Random dither = new Random(19);
        for (int j = 0; j < MAP_H; j++) {
            float py = (j + 0.5f) * sy;
            float wall = smoothstep(floor, top + 0.35f * CARD, py);
            float edgeY = smoothstep(72, 192, Math.min(py, h - py));
            for (int i = 0; i < MAP_W; i++) {
                float pxl = (i + 0.5f) * sx;
                float u = (pxl - cx) / LIGHT_SIGMA_X, v = (py - cy) / LIGHT_SIGMA_Y;
                float column = 1 - smoothstep(COLUMN - 440, COLUMN - 60, pxl);
                float fade = smoothstep(72, 312, Math.min(pxl, w - pxl)) * edgeY;
                float value = (float) Math.exp(-0.5f * (u * u + v * v)) * fade * wall * column * scale;
                float d = dither.nextFloat() - 0.5f;
                px[j * MAP_W + i] = 0xFF000000 | (Oklab.toByte(tint[0] * value, d) << 16) | (Oklab.toByte(tint[1] * value, d) << 8)
                        | Oklab.toByte(tint[2] * value, d);
            }
        }
        Bitmap out = Bitmap.createBitmap(px, MAP_W, MAP_H, Bitmap.Config.ARGB_8888);
        out.prepareToDraw();
        return out;
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /** A warm 2700 to 3000 K bulb's hue (Klangbild's lamp), for covers whose edge has no colour. */
    private static final float LAMP_HUE = (float) Math.toRadians(70);

    /** Oklab, linear sRGB and back (Björn Ottosson's). */
    static final class Oklab {
        static float toLin(int c) {
            float v = c / 255f;
            return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
        }

        static float toSrgb(float v) {
            v = Math.max(0, Math.min(1, v));
            return v <= 0.0031308f ? 12.92f * v : (float) (1.055 * Math.pow(v, 1 / 2.4) - 0.055);
        }

        /** A linear value to an sRGB byte, with [d] (−0.5…0.5) of a step of dither. */
        static int toByte(float linear, float d) {
            return Math.max(0, Math.min(255, Math.round(toSrgb(linear) * 255 + d)));
        }

        static int argb(float r, float g, float b) {
            return 0xFF000000 | (toByte(r, 0) << 16) | (toByte(g, 0) << 8) | toByte(b, 0);
        }

        static float[] fromLinear(float r, float g, float b) {
            double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
            double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
            double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
            return new float[] {(float) (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s),
                    (float) (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s),
                    (float) (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)};
        }

        static float[] toLinear(float L, float a, float b) {
            double l = L + 0.3963377774 * a + 0.2158037573 * b, m = L - 0.1055613458 * a - 0.0638541728 * b,
                    s = L - 0.0894841775 * a - 1.2914855480 * b;
            l = l * l * l;
            m = m * m * m;
            s = s * s * s;
            return new float[] {(float) Math.max(0, 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
                    (float) Math.max(0, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
                    (float) Math.max(0, -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)};
        }
    }
}
