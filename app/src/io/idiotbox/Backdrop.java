package io.idiotbox;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * A recommendation's pictures, built in the background from its image:
 *  - the card (the photo cropped 16:9, or a song's square cover, already at the focused card's size);
 *  - the background while the full photo isn't ready: the photo heavily blurred (FW × FH) and blended with near-black;
 *  - later, only for the cards around the focus, the full-screen photo at BG_W × BG_H (Recs.photo) and the title's logo.
 * Sizes follow the TV's UI size (1920×1080, or less on a 720p UI): nothing is built bigger than the screen can show.
 * What is only drawn lives in GPU memory (HARDWARE): the process doesn't keep its pixels.
 */
final class Backdrop {
    /** The UI's size against 1920×1080 (at most 1). */
    private static final float UI_SCALE = uiScale();
    static final int W = ui(1920), FW = 192, FH = 108;
    /** The row's cards at their focused size (1.05×). */
    static final int CARD_W = ui(288), CARD_H = ui(162);
    /** The full-screen photo: 1280×720 scaled up looks the same behind the scrims and costs 4 MB, not 8. */
    static final int BG_W = ui(1280), BG_H = ui(720);
    /** The title's logo, sized by height for the hero: 110 tall, up to 900 wide (wide logos come out thinner). */
    static final int LOGO_W = ui(900), LOGO_H = ui(110);
    /** Android 8+ keeps a bitmap on the GPU (the process doesn't hold its pixels); Fire OS 6 has no such config. */
    static final Bitmap.Config HARDWARE = hardwareConfig();

    private static Bitmap.Config hardwareConfig() {
        if (android.os.Build.VERSION.SDK_INT < 26) return null;
        try {
            return (Bitmap.Config) Bitmap.Config.class.getField("HARDWARE").get(null);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static float uiScale() {
        android.util.DisplayMetrics m = android.content.res.Resources.getSystem().getDisplayMetrics();
        int longest = Math.max(m.widthPixels, m.heightPixels);
        return longest > 0 ? Math.min(1f, longest / 1920f) : 1f;
    }

    private static int ui(int px) {
        return Math.round(px * UI_SCALE);
    }

    final Bitmap card;                  // the photo on the card
    final Bitmap nightImage;            // the blurred, darkened photo (scaled up on screen)
    volatile Bitmap bg;                 // the photo on the whole screen, while it's among the recent
    volatile Bitmap logo;               // the title's logo (TMDB), or null

    private Backdrop(Bitmap card, Bitmap nightImage) {
        this.card = card;
        this.nightImage = nightImage;
    }

    /** [squareCard]: the card is the picture's middle square (a song's cover, whole) rather than 16:9. Recycles `src`. */
    static Backdrop make(Bitmap src, boolean squareCard) {
        Bitmap card = squareCard ? card(src, CARD_W, CARD_W) : card(src, CARD_W, CARD_H);
        Bitmap wide = crop(src, 16f / 9);
        int[] night = darken(reduce(wide, FW, FH, 3, 3), 0.76f);
        if (wide != src) wide.recycle();
        src.recycle();
        return new Backdrop(card, Bitmap.createBitmap(night, FW, FH, Bitmap.Config.ARGB_8888));
    }

    /** The center of `full` at the given aspect ratio (`full` itself when it already has it). */
    private static Bitmap crop(Bitmap full, float ratio) {
        int cw = Math.min(full.getWidth(), Math.round(full.getHeight() * ratio));
        int ch = Math.min(full.getHeight(), Math.round(cw / ratio));
        if (cw == full.getWidth() && ch == full.getHeight()) return full;
        return Bitmap.createBitmap(full, (full.getWidth() - cw) / 2, (full.getHeight() - ch) / 2, cw, ch);
    }

    /** The center of the photo at the card's aspect ratio, filtered down (halving while possible) to the exact size. */
    private static Bitmap card(Bitmap full, int w, int h) {
        Bitmap b = crop(full, (float) w / h);
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full && b != out) b.recycle();
        if (out == full) out = full.copy(Bitmap.Config.ARGB_8888, false);   // the caller still needs `full`
        return gpu(out);
    }

    /**
     * The full-screen photo: the image center-cropped to 16:9 at BG_W × BG_H, on the GPU. An image that already is
     * BG_W × BG_H (decoded straight to the GPU, say) is used as it is.
     */
    static Bitmap background(Bitmap src) {
        if (src.getWidth() == BG_W && src.getHeight() == BG_H) return gpu(src);
        Bitmap out = Bitmap.createBitmap(BG_W, BG_H, Bitmap.Config.ARGB_8888);
        float scale = Math.max((float) BG_W / src.getWidth(), (float) BG_H / src.getHeight());
        float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
        new Canvas(out).drawBitmap(src, null, new RectF((BG_W - sw) / 2, (BG_H - sh) / 2, (BG_W + sw) / 2, (BG_H + sh) / 2),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        src.recycle();
        return gpu(out);
    }

    /**
     * The picture without bars around it: a music video's thumbnail is often a square cover in a wide frame, or a
     * wide frame in a 4:3 one. Edges count as bars while every sampled pixel along them is near black; trimmed only
     * when they're at least 3 % of the side (so a dark picture keeps its edges). A song's thumbnail has its square
     * cover between two bars of one flat colour instead (a dark shade of the cover): those go too, when both sides
     * have one about as wide as the other.
     */
    static Bitmap trimBars(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int left = 0, right = w, top = 0, bottom = h;
        while (left < w / 2 && dark(src, left, true)) left++;
        while (right > w / 2 && dark(src, right - 1, true)) right--;
        while (top < h / 2 && dark(src, top, false)) top++;
        while (bottom > h / 2 && dark(src, bottom - 1, false)) bottom--;
        if (left + (w - right) < w * 0.03f) {
            left = 0;
            right = w;
        }
        if (top + (h - bottom) < h * 0.03f) {
            top = 0;
            bottom = h;
        }
        if (left == 0 && right == w) {
            // The bars' colour a little way in: the outermost columns are often a near-black edge.
            int in = Math.round(w * 0.02f), l = 0, r = w;
            int leftColour = src.getPixel(in, h / 2), rightColour = src.getPixel(w - 1 - in, h / 2);
            while (l < w / 2 && (flat(src, l, leftColour) || dark(src, l, true))) l++;
            while (r > w / 2 && (flat(src, r - 1, rightColour) || dark(src, r - 1, true))) r--;
            if (l >= w * 0.05f && w - r >= w * 0.05f && Math.abs(l - (w - r)) <= w * 0.02f) {
                left = l;
                right = r;
            }
        }
        if (left == 0 && right == w && top == 0 && bottom == h) return src;
        Bitmap out = Bitmap.createBitmap(src, left, top, right - left, bottom - top);
        if (out != src) src.recycle();
        return out;
    }

    /** A column (or row) that is near black all along: 24 samples, none brighter than 18 of 255. */
    private static boolean dark(Bitmap b, int at, boolean column) {
        int n = 24, len = column ? b.getHeight() : b.getWidth();
        for (int i = 0; i < n; i++) {
            int along = (int) ((i + 0.5f) * len / n);
            int px = column ? b.getPixel(at, along) : b.getPixel(along, at);
            if (Math.max((px >> 16) & 255, Math.max((px >> 8) & 255, px & 255)) > 18) return false;
        }
        return true;
    }

    /**
     * A song's cover for the full-screen background: blurred a little and darkened (a cover blown up to the whole
     * screen shows its pixels, and a pale one washes out the title over it). Small: the background scales it up.
     */
    static Bitmap soften(Bitmap src) {
        int w = 320, h = Math.max(1, Math.round(320f * src.getHeight() / src.getWidth()));
        int[] px = darken(reduce(src, w, h, 2, 2), 0.22f);
        src.recycle();
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** A column that is [colour] all along: 24 samples, each channel within 14 of it (a JPEG's flat bar isn't exact). */
    private static boolean flat(Bitmap b, int x, int colour) {
        int n = 24, h = b.getHeight();
        for (int i = 0; i < n; i++) {
            int px = b.getPixel(x, (int) ((i + 0.5f) * h / n));
            for (int shift = 0; shift <= 16; shift += 8) {
                if (Math.abs(((px >> shift) & 255) - ((colour >> shift) & 255)) > 14) return false;
            }
        }
        return true;
    }

    /** A title logo fitted into LOGO_W × LOGO_H, keeping its shape (never scaled up past twice its size). */
    static Bitmap logo(Bitmap src) {
        float scale = Math.min(2f, Math.min((float) LOGO_W / src.getWidth(), (float) LOGO_H / src.getHeight()));
        int w = Math.max(1, Math.round(src.getWidth() * scale)), h = Math.max(1, Math.round(src.getHeight() * scale));
        Bitmap out = w == src.getWidth() && h == src.getHeight() ? src : Bitmap.createScaledBitmap(src, w, h, true);
        if (out != src) src.recycle();
        return gpu(out);
    }

    /** Copies it to GPU memory (cheap to draw; the process doesn't keep the pixels). A HARDWARE one is returned as is. */
    static Bitmap gpu(Bitmap b) {
        if (HARDWARE == null || b.getConfig() == HARDWARE) return b;
        Bitmap g = b.copy(HARDWARE, false);
        if (g == null) return b;
        b.recycle();
        return g;
    }

    /** Blends with a near-black (the darkest gray of Apple's dark-mode backgrounds), keeping the photo's tint. */
    private static int[] darken(int[] px, float amount) {
        float keep = 1 - amount, base = 11 * amount;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * keep + base);
            int g = Math.round(((p >> 8) & 255) * keep + base);
            int b = Math.round((p & 255) * keep + base + 2 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    /** `full` at w × h: halved at each step (a true average, no aliasing), then blurred (box passes of radius r). */
    static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap b = full;
        while (b.getWidth() / 2 >= w * 1.5f) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full && b != small) b.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != full) small.recycle();
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        return px;
    }

    /** Moving average of radius r in one direction, repeating the edge pixels. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float k = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            float sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = 0xFF000000 | (Math.round(sr * k) << 16) | (Math.round(sg * k) << 8) | Math.round(sb * k);
                int add = in[base + Math.min(n - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }
}
