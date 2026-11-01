package io.idiotbox;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The home screen's background and glass, flat: a gradient from the palette, and translucent rounded fills with a light
 * rim. (Fire OS is Android 9 or 11: no AGSL shaders, so no aurora or refracting glass.) The photo-glass setters are
 * kept as no-ops for the home screen's background transitions.
 */
final class Scene {
    final Shaders.Palette palette = new Shaders.Palette();
    private final Paint flatFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallback = new Paint();
    private int h = 1, w = 1;

    Scene() {
        flatRim.setStyle(Paint.Style.STROKE);
        flatRim.setColor(0x33FFFFFF);
        flatRim.setStrokeWidth(1.5f);
    }

    void setSize(int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        applyPalette();
    }

    /** Kept for the callers: the flat background doesn't move. */
    void setMotion(float speed, float dim) {}

    /** Call after changing `palette`. */
    void applyPalette() {
        fallback.setShader(Ui.gradient(h, palette.color(1), palette.color(0)));
    }

    void advance(float seconds) {}

    void setGlassImage(int[] pixels) {}

    void setGlassImages(int[] from, int[] to) {}

    void setGlassMix(float t) {}

    void clearGlass() {}

    void setFrost(int[] pixels) {}

    void clearFrost() {}

    /** No blurred image behind the glass here: the bottom drawer draws without one. */
    Bitmap frostImage() {
        return null;
    }

    void setOffset(float x, float y) {}

    void drawBackground(Canvas c) {
        c.drawRect(0, 0, w, h, fallback);
    }

    /** Glass in `r`: a translucent fill of `tint` and a light rim (body = 0: just the rim). */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body) {
        glass(c, r, radius, tint, tintAlpha, glow, glowAlpha, lift, body, 1);
    }

    /** Same, with an opacity (0 to 1) for the whole glass. */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body, float alpha) {
        if (alpha <= 0.004f) return;
        if (body > 0) {
            flatFill.setColor(Ui.alpha(tint | 0xFF000000, Math.max(0.35f, tintAlpha)));
            c.drawRoundRect(r, radius, radius, flatFill);
        }
        c.drawRoundRect(r, radius, radius, flatRim);
    }

    /** The Liquid Glass of newer Android: here the same flat glass. */
    void liquid(Canvas c, RectF r, float radius, int tint, float tintAlpha, float lift, float band, float refraction,
                float specular, float alpha) {
        if (alpha <= 0.004f) return;
        glass(c, r, radius, tint, Math.max(0.5f, tintAlpha), 0, 0, lift, 1, alpha);
    }
}
