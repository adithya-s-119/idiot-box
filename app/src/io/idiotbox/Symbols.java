package io.idiotbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * Material Symbols Rounded icons (Apache 2.0) as vectors, res/drawable/sym_<name>.xml (tools/symbols.html builds
 * them), each cropped to its shape; its dp size is its size in the symbol's 960-unit em. Drawn like text: `pointSize`
 * is the size of the font next to the icon, and an icon's em is drawn at ~1.2 × `pointSize`.
 */
final class Symbols {
    private Symbols() {}

    /** An em's width per point of `pointSize` (as a 172 px em at a 144 px font). */
    private static final float EM_PER_POINT = 172f / 144;
    private static final Map<String, Drawable> CACHE = new HashMap<>();
    private static final Object LOCK = new Object();   // card loading (another thread) also draws symbols

    private static Drawable get(Context c, String name) {
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            int id = c.getResources().getIdentifier("sym_" + name, "drawable", c.getPackageName());
            Drawable d = id == 0 ? null : c.getDrawable(id).mutate();
            CACHE.put(name, d);
            return d;
        }
    }

    /** The icon's size in pixels at `pointSize`: its intrinsic size is in em units (960 to the em) as dp. */
    private static float scale(Context c, float pointSize) {
        return pointSize * EM_PER_POINT / (960 * c.getResources().getDisplayMetrics().density);
    }

    static float width(Context c, String name, float pointSize) {
        Drawable d = get(c, name);
        return d == null ? 0 : d.getIntrinsicWidth() * scale(c, pointSize);
    }

    /** Draws centered at (cx, cy) in the given color. */
    static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {
        Drawable d = get(c, name);
        if (d == null) return;
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        float u = scale(c, pointSize);
        synchronized (LOCK) {
            // At its intrinsic size, scaled by the canvas: placed to the subpixel (it renders at the drawn size).
            d.setTint(color);
            d.setBounds(0, 0, iw, ih);
            int save = canvas.save();
            canvas.translate(cx - iw * u / 2, cy - ih * u / 2);
            canvas.scale(u, u);
            d.draw(canvas);
            canvas.restoreToCount(save);
        }
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
        synchronized (LOCK) {
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
        switch (HomeData.kind(code)) {
            case HomeData.UNKNOWN:
            case HomeData.CLEAR:
            case HomeData.MOSTLY_CLEAR: return day ? "sunny" : "clear_night";
            case HomeData.PARTLY_CLOUDY: return day ? "partly_cloudy_day" : "partly_cloudy_night";
            case HomeData.CLOUDY: return "cloud";
            case HomeData.FOG: return "foggy";
            case HomeData.SNOW: return "snowflake";
            case HomeData.STORM: return "thunderstorm";
            default: return "rainy";
        }
    }
}
