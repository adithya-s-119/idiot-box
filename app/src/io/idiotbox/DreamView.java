package io.idiotbox;

import android.content.Context;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.Choreographer;
import android.view.View;

import java.util.Calendar;

/**
 * The screensaver: a flip clock in the style of Fliqlo. Two dark tiles on black, the hours and the minutes in huge
 * condensed bold digits (Roboto Condensed, which Android ships), a hinge across the middle and, in 12-hour time, AM or
 * PM in the hours tile's corner. When a tile's number changes, its top half folds down over the hinge and the new
 * bottom half lands, in 0.6 s. Nothing moves in between: the view draws once a minute, plus the flip.
 * Every 10 minutes the clock shifts by a few pixels, so the tiles never sit on the same pixels for hours.
 * Any key exits (the system's DreamService does that).
 */
final class DreamView extends View implements Choreographer.FrameCallback {
    /** Tiles and digits on a 1920×1080 screen (scaled to the real one). */
    private static final float TILE_W = 880, TILE_H = 760, GAP = 40, RADIUS = 44, HINGE = 6;
    private static final float DIGITS = 760, TRACKING = -0.02f, LABEL = 44, DRIFT = 12;
    private static final long FLIP_MS = 600;
    private static final int TOP = 0xFF141414, BOTTOM = 0xFF111111, INK = 0xFFBDBDBD;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] tiles = {new RectF(), new RectF()};
    private final Rect bounds = new Rect();
    private final String[] shown = {"", ""}, previous = {"", ""};
    private final long[] flipAt = {0, 0};
    private String suffix = "";
    private float k = 1, dx, dy, inkOffset;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            update(true);
            // The next minute, a little after it starts (the clock never shows the old minute late).
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    DreamView(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        digits.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        digits.setColor(INK);
        digits.setTextAlign(Paint.Align.CENTER);
        digits.setLetterSpacing(TRACKING);
        label.setTypeface(digits.getTypeface());
        label.setColor(INK);
        shade.setColor(0xFF000000);
    }

    void start() {
        if (running) return;
        running = true;
        update(false);
        handler.post(tick);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = Math.min(w / 1920f, h / 1080f);
        digits.setTextSize(DIGITS * k);
        label.setTextSize(LABEL * k);
        // The digits' ink centered in the tile (the em box isn't): measured once on the tallest digits.
        digits.getTextBounds("08", 0, 2, bounds);
        inkOffset = -bounds.exactCenterY();
        layoutTiles();
    }

    private void layoutTiles() {
        float w = TILE_W * k, h = TILE_H * k, gap = GAP * k;
        float left = (getWidth() - 2 * w - gap) / 2 + dx, top = (getHeight() - h) / 2 + dy;
        tiles[0].set(left, top, left + w, top + h);
        tiles[1].set(left + w + gap, top, left + 2 * w + gap, top + h);
    }

    /** Reads the time; a tile whose number changed flips (`animate`) or just shows it. */
    private void update(boolean animate) {
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        int hour = now.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!h24 && hour == 0) hour = 12;
        String[] next = {Integer.toString(hour), String.format(java.util.Locale.ROOT, "%02d", now.get(Calendar.MINUTE))};
        suffix = h24 ? "" : now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
        // A few pixels elsewhere every 10 minutes (a slow circle of radius DRIFT).
        double angle = (now.get(Calendar.HOUR_OF_DAY) * 6 + now.get(Calendar.MINUTE) / 10) * Math.PI / 3.7;
        dx = (float) (Math.cos(angle) * DRIFT * k);
        dy = (float) (Math.sin(angle) * DRIFT * k);
        layoutTiles();
        long up = SystemClock.uptimeMillis();
        boolean flipping = false;
        for (int i = 0; i < 2; i++) {
            if (next[i].equals(shown[i])) continue;
            previous[i] = shown[i].isEmpty() || !animate ? next[i] : shown[i];
            shown[i] = next[i];
            flipAt[i] = animate && !previous[i].equals(next[i]) ? up : 0;
            flipping |= flipAt[i] != 0;
        }
        invalidate();
        if (flipping) Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        invalidate();
        long up = SystemClock.uptimeMillis();
        boolean more = false;
        for (int i = 0; i < 2; i++) {
            if (flipAt[i] != 0 && up - flipAt[i] >= FLIP_MS) flipAt[i] = 0;
            more |= flipAt[i] != 0;
        }
        if (more) Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDraw(Canvas c) {
        long up = SystemClock.uptimeMillis();
        for (int i = 0; i < 2; i++) {
            RectF r = tiles[i];
            float t = flipAt[i] == 0 ? 1 : Math.min(1, (up - flipAt[i]) / (float) FLIP_MS);
            if (t >= 1) {
                half(c, r, shown[i], true);
                half(c, r, shown[i], false);
            } else {
                // Underneath: the new top half and the old bottom half; the flap goes over them.
                half(c, r, shown[i], true);
                half(c, r, previous[i], false);
                float e = t * t * (3 - 2 * t);
                if (e < 0.5f) {
                    flap(c, r, previous[i], true, e * 180);           // the old top half folds down to the hinge
                } else {
                    flap(c, r, shown[i], false, 180 - e * 180);       // the new bottom half lands
                }
            }
            if (i == 0 && !suffix.isEmpty()) c.drawText(suffix, r.left + 40 * k, r.bottom - 34 * k, label);
        }
        // The hinge: a black line across both tiles, over everything.
        float hinge = tiles[0].centerY();
        c.drawRect(tiles[0].left, hinge - HINGE * k / 2, tiles[1].right, hinge + HINGE * k / 2, shade);
    }

    /** One half of a tile (its own shade of gray) with its half of the number. */
    private void half(Canvas c, RectF r, String text, boolean top) {
        float hinge = r.centerY(), radius = RADIUS * k;
        c.save();
        c.clipRect(r.left, top ? r.top : hinge, r.right, top ? hinge : r.bottom);
        tile.setColor(top ? TOP : BOTTOM);
        c.drawRoundRect(r, radius, radius, tile);
        c.drawText(text, r.centerX(), r.centerY() + inkOffset, digits);
        c.restore();
    }

    /** A half turned `degrees` away from flat around the hinge (90: edge-on), darker the more it's turned. */
    private void flap(Canvas c, RectF r, String text, boolean top, float degrees) {
        float hinge = r.centerY();
        camera.save();
        camera.setLocation(0, 0, -60);
        camera.rotateX(top ? -degrees : degrees);
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-r.centerX(), -hinge);
        matrix.postTranslate(r.centerX(), hinge);
        c.save();
        c.concat(matrix);
        half(c, r, text, top);
        shade.setAlpha(Math.round(150 * degrees / 90f));
        c.drawRect(r.left, top ? r.top : hinge, r.right, top ? hinge : r.bottom, shade);
        shade.setAlpha(255);
        c.restore();
    }
}
