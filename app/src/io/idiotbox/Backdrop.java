package io.idiotbox;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The home screen background made from the recommendation's image, built once in the background:
 *  - the photo cropped to 16:9 at the TV's UI size (1920×1080; 1280×720 on TVs that draw their UI at 720p, like TCL's
 *    1 GB models, where every bitmap here is 2/3 as wide), with the dark scrims already applied (on the left,
 *    where the hero text sits; at the bottom, behind the shelf; a faint one at the top, behind the buttons); the left
 *    scrim gets stronger when the photo is bright there;
 *  - the frosted version (192×108, heavily blurred) of the same image: the background of the frosted glass and of the
 *    bottom drawer;
 *  - the Liquid Glass version (480×270, lightly blurred): the photo shows through the shelf and the cards;
 *  - with forLight: the card (the photo without scrims, cropped 16:9, already at the focused card size) and the
 *    frosted and glass versions blended with near-black. Later, only for the cards around the focus: the photo itself
 *    at BG_W × BG_H (the full-screen background; Recs.photo) and the title's logo, when TMDB has one.
 * Only what is needed gets built (with forLight, no full photo: the cards are lightweight, so all 9 for the three tabs
 * fit in memory, ready); the other fields stay null.
 * The final image lives only in GPU memory (HARDWARE): drawing it costs one texture, and the process doesn't hold 8 MB.
 */
final class Backdrop {
    /** The UI's size against 1920×1080 (at most 1): nothing is built bigger than the screen can show. */
    private static final float UI_SCALE = uiScale();
    static final int W = ui(1920), H = ui(1080), FW = 192, FH = 108, GW = 480, GH = 270;
    /** The row's cards at their focused size (1.05×). */
    static final int CARD_W = ui(288), CARD_H = ui(162);
    /** The full-screen photo: 1280×720 scaled up looks the same behind the scrims and costs 4 MB, not 8. */
    static final int BG_W = ui(1280), BG_H = ui(720);
    /** The title's logo, sized by height for the hero: 110 tall, up to 900 wide (wide logos come out thinner). */
    static final int LOGO_W = ui(900), LOGO_H = ui(110);

    private static float uiScale() {
        android.util.DisplayMetrics m = android.content.res.Resources.getSystem().getDisplayMetrics();
        int longest = Math.max(m.widthPixels, m.heightPixels);
        return longest > 0 ? Math.min(1f, longest / 1920f) : 1f;
    }

    private static int ui(int px) {
        return Math.round(px * UI_SCALE);
    }
    final Bitmap image;
    final int[] frost;      // heavily blurred (frosted glass, bottom drawer)
    final int[] glass;      // lightly blurred (Liquid Glass: the photo shows through the glass)
    final Bitmap card;                  // the photo on the card
    volatile Bitmap bg;                 // the photo on the whole screen (light design), while it's among the recent
    volatile Bitmap logo;               // the title's logo (TMDB), or null
    final int[] light, lightGlass;      // by day: the frosted and glass versions, lightened
    final Bitmap lightImage;            // by day: the background (the lightened frosted version, scaled up on screen)
    final int[] nightFrost, nightGlass; // at night: frosted (the bottom drawer's frosted glass) and glass, darkened
    final Bitmap nightImage;            // at night: the background (the darkened frosted version)

    private Backdrop(Bitmap image, int[] frost, int[] glass, Bitmap card, int[] light, int[] lightGlass,
                     int[] night, int[] nightGlass) {
        this.image = image;
        this.frost = frost;
        this.glass = glass;
        this.card = card;
        this.light = light;
        this.lightGlass = lightGlass;
        this.nightFrost = night;
        this.nightGlass = nightGlass;
        lightImage = light == null ? null : Bitmap.createBitmap(light, FW, FH, Bitmap.Config.ARGB_8888);
        nightImage = night == null ? null : Bitmap.createBitmap(night, FW, FH, Bitmap.Config.ARGB_8888);
    }

    /** [squareCard]: the card is the picture's middle square (a song's cover, whole) rather than 16:9 of the screen. */
    static Backdrop make(Bitmap src, boolean forLight, boolean squareCard) {
        Bitmap square = forLight && squareCard ? card(src, CARD_W, CARD_W) : null;
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        float scale = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
        float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
        c.drawBitmap(src, null, new RectF((W - sw) / 2, (H - sh) / 2, (W + sw) / 2, (H + sh) / 2), photo);
        src.recycle();
        if (forLight) {
            // Light design: everything comes from the photo without scrims.
            Bitmap card = square != null ? square : card(out, CARD_W, CARD_H);
            // Dark only in this build: no lightened copies (less memory and work per photo).
            int[] frost = reduce(out, FW, FH, 3, 3);
            int[] glass = reduce(out, GW, GH, 2, 2);
            int[] night = darken(frost.clone(), 0.76f);
            int[] nightGlass = darken(glass, 0.56f);
            out.recycle();
            return new Backdrop(null, frost, null, card, null, null, night, nightGlass);
        }

        float boost = Math.max(0, Math.min(0.3f, (luminance(out, 0, 0.22f, 0.5f, 0.62f) - 0.22f) * 1.1f));
        Paint scrim = new Paint(Paint.DITHER_FLAG);
        scrim.setShader(new LinearGradient(0, 0, W * 0.68f, 0,
                new int[] {black(0.66f + boost), black(0.52f + boost * 0.8f), black(0.26f + boost * 0.4f), black(0.07f), 0},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, scrim);
        // At the bottom, a faint scrim: the photo must show through the glass shelf (the glass provides the contrast).
        scrim.setShader(new LinearGradient(0, H, 0, H * 0.4f,
                new int[] {black(0.38f), black(0.3f), black(0.18f), black(0.05f), 0},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, H * 0.4f, W, H, scrim);
        // Above the shelf, on the right, a localized scrim.
        scrim.setShader(new android.graphics.RadialGradient(W * 0.8f, H * 0.65f, W * 0.24f,
                new int[] {black(0.42f), black(0.18f), 0}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 0.32f, W * 0.8f, H * 0.65f);
        c.drawCircle(W * 0.8f, H * 0.65f, W * 0.24f, scrim);
        c.restore();
        scrim.setShader(new LinearGradient(0, 0, 0, H * 0.24f, new int[] {black(0.34f), black(0.1f), 0},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H * 0.24f, scrim);

        int[] frost = reduce(out, FW, FH, 3, 3);
        int[] glass = reduce(out, GW, GH, 2, 2);
        return new Backdrop(gpu(out), frost, glass, null, null, null, null, null);
    }

    /** The center of the photo at the card's aspect ratio, filtered down (halving while possible) to the exact size. */
    private static Bitmap card(Bitmap full, int w, int h) {
        float ratio = (float) w / h;
        int cw = Math.min(full.getWidth(), Math.round(full.getHeight() * ratio));
        int ch = Math.min(full.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(full, (full.getWidth() - cw) / 2, (full.getHeight() - ch) / 2, cw, ch);
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full && b != out) b.recycle();
        return gpu(out);
    }

    /** The full-screen photo: the image center-cropped to 16:9 at BG_W × BG_H, on the GPU. */
    static Bitmap background(Bitmap src) {
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

    /** Copies it to GPU memory (cheap to draw; the process doesn't keep the pixels). */
    static Bitmap gpu(Bitmap b) {
        Bitmap g = b.copy(Bitmap.Config.HARDWARE, false);
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

    /** Halved at each step (a true average, no aliasing), then blurred (box passes of radius r). */
    private static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap b = full;
        while (b.getWidth() / 2 >= w * 1.5f) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full) b.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
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

    /** Average luminance (0 to 1) of a rectangle given as fractions of the image, by sampling. */
    private static float luminance(Bitmap b, float x0, float y0, float x1, float y1) {
        float sum = 0;
        int count = 0;
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 32; i++) {
                int x = Math.round((x0 + (x1 - x0) * (i + 0.5f) / 32) * (b.getWidth() - 1));
                int y = Math.round((y0 + (y1 - y0) * (j + 0.5f) / 24) * (b.getHeight() - 1));
                int p = b.getPixel(x, y);
                sum += (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
                count++;
            }
        }
        return sum / count;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }
}
