package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Material Symbols Rounded icons (Apache 2.0), rendered by tools/make_symbols.py as white PNGs in assets/symbols. Drawn
 * like text: `pointSize` is the size of the font next to the icon, and the icon is centered on that font's cap height.
 */
final class Symbols {
    private Symbols() {}

    /**
     * PNG pixels per em of `pointSize` (a 96 pt × 1.5 px/pt reference scale). tools/make_symbols.py renders a 172 px
     * em, so an icon's em is drawn at ~1.2 × `pointSize`.
     */
    private static final float PX_PER_POINT = 96 * 1.5f;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();

    static Bitmap get(Context c, String name) {
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Bitmap b = null;
            try (InputStream in = c.getAssets().open("symbols/" + name + ".png")) {
                b = BitmapFactory.decodeStream(in);
                if (b != null) {
                    b = b.extractAlpha();
                    b.setHasMipMap(true);
                }
            } catch (IOException ignored) {
            }
            CACHE.put(name, b);
            return b;
        }
    }

    static float width(Context c, String name, float pointSize) {
        Bitmap b = get(c, name);
        return b == null ? 0 : b.getWidth() * pointSize / PX_PER_POINT;
    }

    /** Draws centered at (cx, cy) in the given color (lighter, translucent parts of the symbol stay lighter). */
    static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {
        Bitmap b = get(c, name);
        if (b == null) return;
        synchronized (PAINT) {   // card loading (another thread) also draws symbols
            drawLocked(canvas, b, cx, cy, pointSize, color);
        }
    }

    private static void drawLocked(Canvas canvas, Bitmap b, float cx, float cy, float pointSize, int color) {
        float w = b.getWidth() * pointSize / PX_PER_POINT, h = b.getHeight() * pointSize / PX_PER_POINT;
        PAINT.setColor(color);
        RECT.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        canvas.drawBitmap(b, null, RECT, PAINT);
    }

    private static final int SUN = 0xFFF5C451, MOON = 0xFFE9E2BE, CLOUD = 0xFFEEF2F7, GRAY = 0xFFB9C1CC, RAIN = 0xFF6FB4FF,
            SNOW = 0xFFCFE6FF, BOLT = 0xFFF5C451;
    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final android.graphics.Path BOLT_PATH = new android.graphics.Path();

    /**
     * The weather in two tones, like Apple's multicolor symbols: Material Symbols layered in their own colors (an
     * amber sun behind a white cloud, blue rain and a yellow bolt under it). Centered at (cx, cy).
     */
    static void weatherIcon(Context c, Canvas canvas, int code, boolean day, float cx, float cy, float pointSize) {
        String name = weather(code, day);
        float u = pointSize * 1.2f;   // the symbol's em, roughly
        switch (name) {
            case "sunny": draw(c, canvas, name, cx, cy, pointSize, SUN); return;
            case "clear_night": draw(c, canvas, "bedtime", cx, cy, pointSize, MOON); return;
            case "partly_cloudy_day":
            case "partly_cloudy_night":
                draw(c, canvas, day ? "sunny" : "bedtime", cx + u * 0.16f, cy - u * 0.16f, pointSize * 0.72f, day ? SUN : MOON);
                draw(c, canvas, "cloud", cx - u * 0.06f, cy + u * 0.08f, pointSize * 0.86f, CLOUD);
                return;
            case "cloud": draw(c, canvas, name, cx, cy, pointSize, CLOUD); return;
            case "foggy": draw(c, canvas, name, cx, cy, pointSize, GRAY); return;
            case "snowflake": draw(c, canvas, name, cx, cy, pointSize, SNOW); return;
            default:
                break;
        }
        // Rain and storms: a white cloud, raised, with the rain (three slanted strokes) or the bolt under it.
        draw(c, canvas, "cloud", cx, cy - u * 0.14f, pointSize * 0.86f, CLOUD);
        synchronized (PAINT) {
            if (name.equals("thunderstorm")) {
                float s = u * 0.2f, x = cx, y = cy + u * 0.16f;
                BOLT_PATH.reset();
                BOLT_PATH.moveTo(x + s * 0.3f, y - s * 0.9f);
                BOLT_PATH.lineTo(x - s * 0.55f, y + s * 0.15f);
                BOLT_PATH.lineTo(x + s * 0.05f, y + s * 0.15f);
                BOLT_PATH.lineTo(x - s * 0.3f, y + s * 1.1f);
                BOLT_PATH.lineTo(x + s * 0.6f, y - s * 0.05f);
                BOLT_PATH.lineTo(x, y - s * 0.05f);
                BOLT_PATH.close();
                STROKE.setStyle(Paint.Style.FILL);
                STROKE.setColor(BOLT);
                canvas.drawPath(BOLT_PATH, STROKE);
                return;
            }
            STROKE.setStyle(Paint.Style.STROKE);
            STROKE.setStrokeCap(Paint.Cap.ROUND);
            STROKE.setStrokeWidth(u * 0.075f);
            STROKE.setColor(RAIN);
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * u * 0.2f, y = cy + u * 0.17f;
                canvas.drawLine(x + u * 0.04f, y, x - u * 0.04f, y + u * 0.16f, STROKE);
            }
        }
    }

    /** Weather symbol for an Open-Meteo WMO weather code. */
    static String weather(int code, boolean day) {
        if (code <= 1) return day ? "sunny" : "clear_night";
        if (code == 2) return day ? "partly_cloudy_day" : "partly_cloudy_night";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "rainy";
        if (code == 65 || code == 67 || code == 82) return "rainy";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snowflake";
        if (code >= 95) return "thunderstorm";
        return "rainy";
    }
}
