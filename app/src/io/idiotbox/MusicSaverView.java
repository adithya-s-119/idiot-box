package io.idiotbox;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The calm music screensaver, dark only, after Klangbild and on its measures (its 960 × 540 dp grid doubled for
 * 1920 × 1080): the cover flow on a black floor, the songs before this one to its left; the column beside it with the
 * time and the app, the title in Inter Light, the artist and the progress, or the song's timed words with the progress
 * as a glowing line along the screen's bottom edge; the faint light the cover throws on the wall. A song change takes
 * Klangbild's second: the words leave, the strip turns over in 1.3 s, the new lines rise one after another. Everything
 * drifts a few pixels over many minutes, for OLED panels.
 */
final class MusicSaverView extends View implements Music.Listener {
    // Klangbild's grid and FlowGeometry at 1920 × 1080 (multiplied by k for the real screen).
    private static final float TOP = MusicArt.CY - MusicArt.CARD / 2, BOTTOM = MusicArt.CY + MusicArt.CARD / 2;
    private static final float COL_W = 1920 - 96 - MusicArt.COLUMN;
    private static final float G = MusicArt.CARD / 300;                 // FlowGeometry's 300 dp card to ours
    private static final float FIRST_X = 166 * G, STEP_X = 35.3f * G, SIDE_Z = 213.3f * G, CAMERA = 1000 * G;
    private static final float SIDE_ANGLE = 60, EDGE = 2.75f;
    private static final int INK = 0xF0EDEDEF, MUTED = 0xA8EDEDEF, QUIET = 0x6BEDEDEF;
    // Motion (Klangbild's Motion): the strip's turn, the text leaving and rising, the light, a pause.
    private static final long TURN_MS = 1300, TEXT_OUT_MS = 220, TEXT_IN_MS = 780, LINE_MS = 380, LINE_STEP_MS = 120;
    private static final long LIGHT_MS = 1100, PAUSE_MS = 600, GLIDE_MS = 500;
    private static final PathInterpolator TURN = new PathInterpolator(0.35f, 0f, 0.35f, 1f);
    /** A side card swapped for another while the song plays: in behind the middle one, then the new one out. */
    private static final long SWAP_MS = 450;
    private static final PathInterpolator DIMMER = new PathInterpolator(0.45f, 0f, 0.25f, 1f);

    /**
     * A card of the strip: its song, and where it goes from and to (positions, 0 = the middle), from when and for how
     * long. A leaving card is gone once it's there (off at its side).
     */
    private static final class Card {
        final Music.Track track;
        final float from, to;
        final long length;
        /** When it sets off; a card whose cover isn't made yet doesn't exist, and sets off once it is. */
        long start;
        final boolean leaving;
        /** Its cover once made (kept here: asking MusicArt every frame builds its key every frame). */
        MusicArt.Scene scene;

        Card(Music.Track track, float from, float to, long start, long length, boolean leaving) {
            this.track = track;
            this.from = from;
            this.to = to;
            this.start = start;
            this.length = length;
            this.leaving = leaving;
        }
    }

    /** One song's words as set: each line laid out for the column (with its own paint), and where it stands in the list. */
    private static final class Words {
        final TextPaint paint;
        final StaticLayout[] lines;
        final float[] top;
        final float[] height;

        Words(TextPaint paint, StaticLayout[] lines, float[] top, float[] height) {
            this.paint = paint;
            this.lines = lines;
            this.top = top;
            this.height = height;
        }
    }

    private final Context context;
    private float k = 1;
    private final List<Card> cards = new ArrayList<>();
    private Music.Track shown, leaving, next;
    private MusicArt.Scene shownScene;
    private long changedAt = -1;      // uptime of the last song change (−1: none to animate)
    private long turnEnd;             // when the strip's last turn is over (a new side card waits for it)
    private long lightAt = -1;
    // The one pending redraw while nothing moves: postInvalidateDelayed from every frame would start a new chain at
    // each outside invalidate() (a song update, a cover), and the chains add up.
    private final Runnable later = this::invalidate;
    private MusicArt.Scene lightBefore;
    private float pause = 0;          // 0 playing … 1 paused, eased
    private long pauseFrom;
    private float pauseStart, pauseTarget;

    // The text of the song shown and of the one leaving.
    private StaticLayout title, titleLeaving;
    private String artist = "", artistLeaving = "", status = "", foot = "", upNext = "", upNextLabel = "";
    /** The words shown (both set together, once laid out), and the ones being laid out. */
    private SongLyrics.Lines lyrics, wantedLyrics;
    private Words words;
    private int wordsGeneration;
    private int lyricIndex = -1;
    // Text drawn every frame, made again only when it changes.
    private String statusLine, positionText = "", endText = "";
    private long statusMinute = -1, positionSecond = -1, endFor = -1;
    private float endWidth, upNextX;
    private float glideFrom, glideTo;
    private long glideAt = -1;

    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final ColorMatrixColorFilter[] lit = new ColorMatrixColorFilter[65];
    private final int[] order = new int[8];
    private final float[] at = new float[8], depth = new float[8];
    private final float[] riseNew = new float[3], alphaNew = new float[3], riseNone = new float[3], alphaOut = new float[3];
    private TextPaint titlePaint, artistPaint, smallPaint, lyricPaint;
    private Shader rise, haze;
    private int shaderAccent;

    MusicSaverView(Context c) {
        super(c);
        context = c;
        setBackgroundColor(0xFF000000);
        for (int i = 0; i <= 64; i++) {
            float l = i / 64f;
            ColorMatrix m = new ColorMatrix(new float[] {l, 0, 0, 0, 0, 0, l, 0, 0, 0, 0, 0, l, 0, 0, 0, 0, 0, 1, 0});
            lit[i] = new ColorMatrixColorFilter(m);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Music.addListener(this);
        pause = pauseTarget = pauseStart = Music.playing() ? 0 : 1;
        musicChanged();
    }

    @Override protected void onDetachedFromWindow() {
        Music.removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        titlePaint = textPaint(Ui.light, 84 * k, INK);
        titlePaint.setLetterSpacing(-0.02f);
        artistPaint = textPaint(Ui.regular, 42 * k, MUTED);
        smallPaint = textPaint(Ui.regular, 30 * k, QUIET);
        lyricPaint = textPaint(Ui.light, 60 * k, INK);
        lyricPaint.setLetterSpacing(-0.01f);
        if (shown != null) setText(shown);
        setNext();
        setLyrics(wantedLyrics);
    }

    private static TextPaint textPaint(android.graphics.Typeface face, float size, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    // MARK: - What's playing

    @Override public void musicChanged() {
        Music.Track t = Music.track();
        if (t != null && (shown == null || !Music.same(t, shown))) change(t);
        else if (t != null) {   // the same song, now with its length, its fuller details or picture, or what comes after it
            boolean words = !t.shownTitle.equals(shown.shownTitle) || !t.shownArtist.equals(shown.shownArtist);
            shown = t;
            shownScene = null;
            if (words && titlePaint != null) setText(t);   // in place: no turn, the cards stay
            refreshSides();
        }
        boolean paused = !Music.playing();
        float target = paused ? 1 : 0;
        if (target != pauseTarget) {
            pauseStart = pause;
            pauseTarget = target;
            pauseFrom = SystemClock.uptimeMillis();
        }
        invalidate();
    }

    /**
     * A new song: the strip turns one card on, the words change, the light follows. The strip is three songs: the one
     * playing, the one before and the one after. Going on, the song that played becomes the one before and the one
     * before goes; the one after comes when the app says it (refreshSides), out from behind. Going back, the same the
     * other way.
     */
    private void change(Music.Track t) {
        long now = SystemClock.uptimeMillis();
        boolean first = shown == null;
        List<Card> strip = new ArrayList<>();
        if (first) {
            strip.add(new Card(t, 0, 0, now - TURN_MS, TURN_MS, false));
            Music.Track heard = Music.before();
            if (!Music.hasQueue() && heard != null) strip.add(new Card(heard, -1, -1, now - TURN_MS, TURN_MS, false));
            turnEnd = now;
        } else {
            int dir = direction(t);
            Card came = find(dir), old = find(0);
            if (came != null && !Music.same(came.track, t)) came = null;   // not the song on that side: in from it
            strip.add(keep(new Card(t, came != null ? position(came, now) : dir, 0, now, TURN_MS, false), came));
            if (old != null) strip.add(keep(new Card(old.track, position(old, now), -dir, now, TURN_MS, false), old));
            for (Card c : cards) {   // the rest goes, off at its side
                if (c.leaving || c == came || c == old) continue;
                float from = position(c, now), side = Math.signum(from);
                strip.add(keep(new Card(c.track, from, (side == 0 ? -dir : side) * EDGE, now, TURN_MS, true), c));
            }
            turnEnd = now + TURN_MS;
        }
        cards.clear();
        cards.addAll(strip);
        Card after = find(1);
        next = after == null ? null : after.track;
        leaving = shown;
        titleLeaving = title;
        artistLeaving = artist;
        MusicArt.Scene old = shown == null ? null : shownScene();
        shown = t;
        shownScene = null;
        changedAt = first ? -1 : now;   // the words and the light (the cards keep their own times)
        lightBefore = old;
        lightAt = first ? -1 : now;
        setLyrics(SongLyrics.get(context, t, (key, lines) -> {
            if (shown != null && shown.key().equals(key)) setLyrics(lines);
        }));
        if (titlePaint != null) {
            setText(t);
            setNext();
        }
        shownScene();
        refreshSides();   // what the app already says of this song's neighbours
    }

    /** The card that is (or is going to be) at [to]: −1 before, 0 playing, 1 after; null if there's none. */
    private Card find(int to) {
        for (Card c : cards) if (!c.leaving && c.to == to) return c;
        return null;
    }

    /** Which way the strip turns to [t]: 1 when it was the card after, −1 when it was the one before. */
    private int direction(Music.Track t) {
        Card after = find(1), before = find(-1);
        if (after != null && Music.same(after.track, t)) return 1;
        if (before != null && Music.same(before.track, t)) return -1;
        int pressed = Music.commandMove();
        if (pressed != 0) return pressed;
        return Music.hasQueue() ? Music.queueMove() : 1;
    }

    /**
     * The songs before and after this one, as the app has them (Idiot Box's music engine sends them as session extras) once it's at this song: a new
     * one slides out from behind the middle card (after the turn); one that changed slides in behind it first. When the
     * app doesn't say, what's there stays.
     */
    private void refreshSides() {
        if (shown == null || !Music.queueIsAt(shown)) return;
        long now = SystemClock.uptimeMillis();
        boolean changed = false;
        for (int side = -1; side <= 1; side += 2) {
            Music.Track wanted = Music.queued(side);
            if (wanted == null) continue;
            Card showing = find(side);
            if (showing != null && Music.same(showing.track, wanted)) continue;
            changed = true;
            long out = Math.max(now, turnEnd);
            if (showing != null) {
                cards.remove(showing);
                cards.add(keep(new Card(showing.track, position(showing, now), 0, now, SWAP_MS, true), showing));
                out = Math.max(out, now + SWAP_MS);
            }
            cards.add(new Card(wanted, 0, side, out, SWAP_MS, false));
        }
        if (!changed) return;
        Card after = find(1);
        next = after == null ? null : after.track;
        setNext();
        invalidate();
    }

    /** [fresh], the same song as [from] in a new place, with the cover [from] already has (null: none to carry). */
    private static Card keep(Card fresh, Card from) {
        if (from != null && fresh.scene == null) fresh.scene = from.scene;
        return fresh;
    }

    /** Where [card] is now on its way (a card that hasn't started is where it starts). */
    private static float position(Card card, long now) {
        float t = card.length <= 0 ? 1 : Math.max(0, Math.min(1, (now - card.start) / (float) card.length));
        return card.from + (card.to - card.from) * TURN.getInterpolation(t);
    }

    private MusicArt.Scene scene(Card card) {
        if (card.scene == null) card.scene = MusicArt.get(context, card.track, k, this::sceneReady);
        return card.scene;
    }

    private MusicArt.Scene shownScene() {
        if (shownScene == null) shownScene = MusicArt.get(context, shown, k, this::sceneReady);
        return shownScene;
    }

    /** A cover made: the song's own light, if it comes after the change's crossfade, fades in rather than appears. */
    private void sceneReady(String key, MusicArt.Scene s) {
        long now = SystemClock.uptimeMillis();
        if (shown != null && shown.key().equals(key) && (lightAt < 0 || now - lightAt >= LIGHT_MS)) {
            lightAt = now;
            lightBefore = null;
        }
        invalidate();
    }

    private void setText(Music.Track t) {
        title = StaticLayout.Builder.obtain(t.shownTitle, 0, t.shownTitle.length(), titlePaint, Math.round(COL_W * k))
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.05f).setIncludePad(false).build();
        artist = TextUtils.ellipsize(t.shownArtist, artistPaint, COL_W * k, TextUtils.TruncateAt.END).toString();
        status = Music.appName(context, t.app);
        statusLine = null;
        foot = TextUtils.ellipsize(t.shownTitle + " · " + t.shownArtist, smallPaint, COL_W * k, TextUtils.TruncateAt.END).toString();
    }

    /** "Up next" and the next song of the queue, a corner radius above the cover's bottom edge (StageGrid). */
    private void setNext() {
        if (smallPaint == null) return;
        upNextLabel = Ui.tr("Up next");
        upNextX = smallPaint.measureText(upNextLabel + "   ");
        upNext = next == null ? "" : TextUtils.ellipsize(next.shownTitle + " · " + next.shownArtist, smallPaint,
                COL_W * k - upNextX, TextUtils.TruncateAt.END).toString();
    }

    /**
     * The words for the song shown (null: none), laid out off the main thread (a song has a hundred lines or so, and
     * they come during the turn); until then, the column.
     */
    private void setLyrics(SongLyrics.Lines l) {
        int generation = ++wordsGeneration;
        wantedLyrics = l;
        lyrics = null;
        words = null;
        lyricIndex = -1;
        if (l == null || lyricPaint == null) return;
        TextPaint paint = new TextPaint(lyricPaint);
        int width = Math.round(COL_W * k);
        new Thread(() -> {
            Words laid = layWords(l, paint, width);
            post(() -> {
                if (generation != wordsGeneration) return;
                lyrics = l;
                words = laid;
                invalidate();
            });
        }, "lyrics-layout").start();
    }

    /** Lyrics.kt: 30 sp lines (60 px), 0.8 em between them, each taking the rows it needs. */
    private static Words layWords(SongLyrics.Lines l, TextPaint paint, int width) {
        int n = l.text.length;
        StaticLayout[] out = new StaticLayout[n];
        float[] top = new float[n], height = new float[n];
        float y = 0, gap = 0.8f * paint.getTextSize();
        for (int i = 0; i < n; i++) {
            String s = l.text[i].isEmpty() ? "♪" : l.text[i];
            out[i] = StaticLayout.Builder.obtain(s, 0, s.length(), paint, width)
                    .setLineSpacing(0, 1.12f).setIncludePad(false).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build();
            top[i] = y;
            height[i] = out[i].getHeight();
            y += height[i] + gap;
        }
        return new Words(paint, out, top, height);
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (titlePaint == null || shown == null) return;
        long now = SystemClock.uptimeMillis();
        boolean moving = false;
        // A pause dims the light, the covers and the words; nothing else moves.
        if (pause != pauseTarget) {
            float t = Math.min(1, (now - pauseFrom) / (float) PAUSE_MS);
            pause = pauseStart + (pauseTarget - pauseStart) * DIMMER.getInterpolation(t);
            if (t >= 1) pause = pauseTarget;
            moving = true;
        }
        int save = c.save();
        drift(c);
        moving |= drawLight(c, now);
        moving |= drawStrip(c, now);
        boolean withWords = words != null && lyrics != null;
        if (withWords) moving |= drawWords(c, now);
        else moving |= drawColumn(c, now);
        c.restoreToCount(save);
        if (withWords) drawEdge(c);
        removeCallbacks(later);
        if (moving) postInvalidateOnAnimation();
        else postDelayed(later, Music.playing() ? 250 : 1000);   // the progress (still while paused) and the clock
    }

    /** Klangbild's OLED care: a slow Lissajous path, ±8 px, at most a pixel every few seconds, whole pixels only. */
    private void drift(Canvas c) {
        double t = System.currentTimeMillis() / 1000.0, a = 8 * k;
        c.translate(Math.round(Math.sin(2 * Math.PI * t / (37 * 60)) * a), Math.round(Math.sin(2 * Math.PI * t / (29 * 60) + 1.3) * a * 0.8));
    }

    private boolean drawLight(Canvas c, long now) {
        MusicArt.Scene s = shownScene();
        float t = lightAt < 0 ? 1 : Math.min(1, (now - lightAt) / (float) LIGHT_MS);
        float dim = 1 - pause * (1 - 0.45f);
        if (lightBefore != null && t < 1) drawMap(c, lightBefore.light, (1 - DIMMER.getInterpolation(t)) * dim);
        if (s != null) drawMap(c, s.light, (t >= 1 ? 1 : DIMMER.getInterpolation(t)) * dim);
        return t < 1;
    }

    private void drawMap(Canvas c, Bitmap map, float alpha) {
        if (alpha <= 0) return;
        image.setColorFilter(null);
        image.setAlpha(Math.round(alpha * 255));
        src.set(0, 0, map.getWidth(), map.getHeight());
        dst.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(map, src, dst, image);
    }

    /** The cards, farthest first, each one textured quad in the one perspective (FlowGeometry). */
    private boolean drawStrip(Canvas c, long now) {
        camera.setLocation(0, 0, -CAMERA * k / 72f);
        boolean moving = false;
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card a = cards.get(i);
            if (a.leaving && now >= a.start + a.length) {   // gone
                cards.remove(i);
            } else if (scene(a) == null) {
                a.start = Math.max(a.start, now);   // no cover yet: it waits where it starts (the cover's arrival redraws)
            } else if (now < a.start + a.length) {
                moving = true;
            }
        }
        int n = Math.min(cards.size(), order.length);
        for (int i = 0; i < n; i++) {
            Card a = cards.get(i);
            // The song's own card ahead of any other at the middle: the swapped ones pass behind it.
            at[i] = position(a, now);
            depth[i] = Math.abs(at[i]) + (a.to == 0 && !a.leaving ? -0.01f : 0);
            order[i] = i;
        }
        // Farthest first, the middle card last (an insertion sort of at most a few cards).
        for (int i = 1; i < n; i++) {
            int o = order[i], j = i - 1;
            while (j >= 0 && depth[order[j]] < depth[o]) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = o;
        }
        for (int i = 0; i < n; i++) {
            Card card = cards.get(order[i]);
            if (now < card.start) continue;   // not set off yet (behind the middle card)
            drawCard(c, card, at[order[i]]);
        }
        return moving;
    }

    /** One card at place [p]; cards going out beyond the ends fade by their place. */
    private void drawCard(Canvas c, Card card, float p) {
        MusicArt.Scene s = scene(card);
        if (s == null) return;
        float a = Math.abs(p);
        if (a >= EDGE) return;
        float x, z, angle, light, alpha = 1;
        if (a <= 1) {
            x = FIRST_X * a;
            z = SIDE_Z * a;
            angle = SIDE_ANGLE * a;
            light = 1 - 0.45f * a;
        } else {
            x = FIRST_X + STEP_X * (a - 1);
            z = SIDE_Z;
            angle = SIDE_ANGLE;
            light = a <= 2 ? 0.55f - 0.15f * (a - 1) : 0.40f;
        }
        if (a > 2) alpha *= 1 - (a - 2) / (EDGE - 2);
        float sign = p < 0 ? -1 : 1;
        camera.save();
        camera.translate(sign * x * k, 0, z * k);
        camera.rotateY(-sign * angle);   // turned toward the middle, the outer edge nearer
        camera.getMatrix(matrix);
        camera.restore();
        float half = s.card.getWidth() / 2f;
        matrix.preTranslate(-half, -(2 + s.side / 2f));
        matrix.postTranslate(MusicArt.CX * k, MusicArt.CY * k);
        float dim = light * (1 - pause * (1 - 0.72f));
        image.setColorFilter(lit[Math.round(Math.max(0, Math.min(1, dim)) * 64)]);
        image.setAlpha(Math.round(alpha * 255));
        c.drawBitmap(s.card, matrix, image);
        image.setColorFilter(null);
    }

    /** The column: the status line hangs a corner radius below the cover's top; title, artist and bar centred on it. */
    private boolean drawColumn(Canvas c, long now) {
        float left = MusicArt.COLUMN * k, top = TOP * k, r = MusicArt.RADIUS * k;
        float textDim = 1 - pause * 0.3f;
        drawStatus(c, left, top + r, textDim);
        float since = changedAt < 0 ? Float.MAX_VALUE : now - changedAt;
        boolean moving = since < TEXT_IN_MS + 3 * LINE_STEP_MS + LINE_MS;
        if (titleLeaving != null && since < TEXT_OUT_MS) {
            float out = (1 - DIMMER.getInterpolation(since / TEXT_OUT_MS)) * textDim;
            alphaOut[0] = alphaOut[1] = alphaOut[2] = out;
            drawBlockLines(c, titleLeaving, artistLeaving, leaving, left, alphaOut, riseNone);
        }
        for (int i = 0; i < 3; i++) {
            float t = since == Float.MAX_VALUE ? 1 : Ui.clamp((since - TEXT_IN_MS - i * LINE_STEP_MS) / LINE_MS);
            float e = DIMMER.getInterpolation(t);
            alphaNew[i] = e * textDim;
            riseNew[i] = (1 - e) * 14 * k;
        }
        drawBlockLines(c, title, artist, shown, left, alphaNew, riseNew);
        if (!upNext.isEmpty() && alphaNew[2] > 0) {
            float base = BOTTOM * k - r - smallPaint.descent() + riseNew[2];
            smallPaint.setColor(Ui.alpha(MUTED, alphaNew[2]));
            c.drawText(upNextLabel, left, base, smallPaint);
            smallPaint.setColor(Ui.alpha(QUIET, alphaNew[2]));
            c.drawText(upNext, left + upNextX, base, smallPaint);
        }
        return moving;
    }

    private void drawStatus(Canvas c, float left, float hang, float alpha) {
        long now = System.currentTimeMillis(), minute = now / 60_000;
        if (statusLine == null || minute != statusMinute) {
            statusMinute = minute;
            statusLine = Ui.clock(now) + (status.isEmpty() ? "" : " · " + status);
        }
        smallPaint.setColor(Ui.alpha(QUIET, alpha));
        c.drawText(statusLine, left, hang + Ui.cap(smallPaint), smallPaint);
    }

    /** Title, artist, then the bar and its times, centred on the cover; each with its own alpha and rise (a change). */
    private void drawBlockLines(Canvas c, StaticLayout t, String a, Music.Track track, float left, float[] alpha, float[] rise) {
        if (t == null || track == null) return;
        float barGap = 44 * k, barH = 6 * k, labelGap = 16 * k;
        float artistCap = Ui.cap(artistPaint), smallCap = Ui.cap(smallPaint);
        float height = t.getHeight() + 18 * k + artistPaint.getFontSpacing() + barGap + barH + labelGap + smallCap;
        float y = MusicArt.CY * k - height / 2;
        if (alpha[0] > 0) {
            c.save();
            c.translate(left, y + rise[0]);
            titlePaint.setColor(Ui.alpha(INK, alpha[0]));
            t.draw(c);
            c.restore();
        }
        y += t.getHeight() + 18 * k;
        if (alpha[1] > 0) {
            artistPaint.setColor(Ui.alpha(MUTED, alpha[1]));
            c.drawText(a, left, y + artistCap + (artistPaint.getFontSpacing() - artistCap) / 2 + rise[1], artistPaint);
        }
        y += artistPaint.getFontSpacing() + barGap;
        if (alpha[2] > 0 && track == shown) {
            long position = Music.position();
            float w = COL_W * k, done = track.duration > 0 ? Ui.clamp(position / (float) track.duration) : 0;
            float by = y + rise[2];
            fill.setShader(null);
            fill.setColor(Ui.alpha(0x29EDEDEF, alpha[2]));
            c.drawRoundRect(left, by, left + w, by + barH, barH / 2, barH / 2, fill);
            fill.setColor(Ui.alpha(MUTED, alpha[2]));
            c.drawRoundRect(left, by, left + w * done, by + barH, barH / 2, barH / 2, fill);
            smallPaint.setColor(Ui.alpha(QUIET, alpha[2]));
            float base = by + barH + labelGap + smallCap;
            if (position / 1000 != positionSecond) {
                positionSecond = position / 1000;
                positionText = time(position);
            }
            if (track.duration != endFor) {
                endFor = track.duration;
                endText = time(track.duration);
                endWidth = smallPaint.measureText(endText);
            }
            c.drawText(positionText, left, base, smallPaint);
            c.drawText(endText, left + w - endWidth, base, smallPaint);
        }
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    /**
     * The words (Lyrics.kt): the line sung now level with the cover's centre in the title's ink, the others quieter
     * with distance; the list glides a step as the song moves on. Title and artist where "Up next" stands.
     */
    private boolean drawWords(Canvas c, long now) {
        float left = MusicArt.COLUMN * k, top = TOP * k, bottom = BOTTOM * k, r = MusicArt.RADIUS * k;
        float textDim = 1 - pause * 0.3f;
        drawStatus(c, left, top + r, textDim);
        smallPaint.setColor(Ui.alpha(MUTED, textDim));
        c.drawText(foot, left, bottom - r - smallPaint.descent(), smallPaint);
        int i = Math.max(0, lyrics.index(Music.position() + 250));   // a quarter second early: the eye reads ahead
        if (i != lyricIndex) {
            glideFrom = lyricIndex < 0 ? i : currentGlide(now);
            glideTo = i;
            glideAt = lyricIndex < 0 ? -1 : now;
            lyricIndex = i;
        }
        float g = currentGlide(now);
        int lo = (int) Math.floor(g), hi = Math.min(words.lines.length - 1, lo + 1);
        float f = g - lo;
        float center = words.top[lo] + words.height[lo] / 2 + f * (words.top[hi] + words.height[hi] / 2 - words.top[lo] - words.height[lo] / 2);
        float origin = MusicArt.CY * k - center;
        float clipTop = top + r + 28 * k, clipBottom = bottom - r - 40 * k, fade = 36 * k;
        c.save();
        c.clipRect(left - 8 * k, clipTop, left + COL_W * k + 8 * k, clipBottom);
        for (int n = 0; n < words.lines.length; n++) {
            float y = origin + words.top[n], h = words.height[n];
            if (y + h < clipTop || y > clipBottom) continue;
            float d = Math.abs(n - g);
            float a = d < 1 ? 0.94f - 0.52f * d : d < 2 ? 0.42f - 0.18f * (d - 1) : d < 3 ? 0.24f - 0.16f * (d - 2) : Math.max(0, 0.08f - 0.08f * (d - 3));
            // Gone by the time any of it reaches the edge: a line is never shown cut through its letters.
            a *= Ui.smoothstep(clipTop, clipTop + fade, y) * (1 - Ui.smoothstep(clipBottom - fade, clipBottom, y + h));
            if (a <= 0) continue;
            words.paint.setColor(Ui.alpha(0xFFEDEDEF, a * textDim));
            c.save();
            c.translate(left, y);
            words.lines[n].draw(c);
            c.restore();
        }
        c.restore();
        return glideAt >= 0 && now - glideAt < GLIDE_MS;
    }

    private float currentGlide(long now) {
        if (glideAt < 0) return glideTo;
        float t = Math.min(1, (now - glideAt) / (float) GLIDE_MS);
        return glideFrom + (glideTo - glideFrom) * DIMMER.getInterpolation(t);
    }

    /** With the words, the progress runs along the screen's bottom edge in the light's colour, glowing softly. */
    private void drawEdge(Canvas c) {
        MusicArt.Scene s = shownScene();
        int accent = s == null ? 0xFFAC7A4B : s.accent;
        float w = getWidth(), h = getHeight(), line = 4 * k;
        float done = shown.duration > 0 ? Ui.clamp(Music.position() / (float) shown.duration) : 0;
        float dim = 1 - pause * 0.5f;
        if (rise == null || shaderAccent != accent) {
            int rgb = accent & 0xFFFFFF;
            rise = new LinearGradient(0, h, 0, h - 110 * k, new int[] {0x1A000000 | rgb, 0x09000000 | rgb, rgb}, new float[] {0, 0.35f, 1}, Shader.TileMode.CLAMP);
            haze = new LinearGradient(0, h - line / 2, 0, h - line / 2 - 14 * k, new int[] {0x59000000 | rgb, rgb}, null, Shader.TileMode.CLAMP);
            shaderAccent = accent;
        }
        // The glow eases out toward the tip and spills a little past it, as light would, instead of ending in an edge:
        // whole up to TIP_FADE before the tip, gone TIP_SPILL after it, in slices (no layer, nothing made per frame).
        float tip = w * done, from = Math.max(0, tip - TIP_FADE * k), to = Math.min(w, tip + TIP_SPILL * k);
        glow(c, 0, from, dim, h, line);
        for (int i = 0; i < TIP_SLICES; i++) {
            float x0 = from + (to - from) * i / TIP_SLICES, x1 = from + (to - from) * (i + 1) / TIP_SLICES;
            glow(c, x0, x1, dim * (1 - Ui.smoothstep(0, 1, (i + 0.5f) / TIP_SLICES)), h, line);
        }
        fill.setShader(null);
        fill.setColor(Ui.alpha(accent, 0.16f * dim));
        c.drawRect(0, h - line, w, h, fill);
        fill.setColor(Ui.alpha(accent, dim));
        c.drawRect(0, h - line, w * done, h, fill);
    }

    private static final float TIP_FADE = 220, TIP_SPILL = 40;
    private static final int TIP_SLICES = 24;

    /** The light rising off the line and the haze around it, between [x0] and [x1], at [alpha]. */
    private void glow(Canvas c, float x0, float x1, float alpha, float h, float line) {
        if (x1 <= x0 || alpha <= 0) return;
        fill.setShader(rise);
        fill.setAlpha(Math.round(255 * alpha));
        c.drawRect(x0, h - 110 * k, x1, h, fill);
        fill.setShader(haze);
        c.drawRect(x0, h - line - 14 * k, x1, h, fill);
    }
}
