package io.idiotbox;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * A live channel full screen, in Android's own player (as Amazon's home screen plays video): no other app. A strip at
 * the bottom (logo, what's on, how far in, what's next, the channels above and below) shows for STRIP_MS after a
 * channel change or any key. Up and Down change channel within the row it was opened from; Back goes home.
 */
public class LivePlayerActivity extends Activity implements SurfaceHolder.Callback {
    static final String EXTRA_URLS = "urls", EXTRA_INDEX = "index";
    private static final long STRIP_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService logos = Net.serial("live-logos");
    /** Logos at their drawn size, the least recently drawn dropped past LOGOS (the channels around stay). */
    private final Map<String, Bitmap> logoCache = new LinkedHashMap<>(16, 0.75f, true);
    private static final int LOGOS = 8;
    private final List<Live.Channel> channels = new ArrayList<>();
    private SurfaceView video;
    private Strip strip;
    private MediaPlayer player;
    private SurfaceHolder holder;
    private int index;
    private boolean loading, failed;
    private final Runnable hideStrip = () -> strip.setVisibility(View.INVISIBLE);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ArrayList<String> urls = getIntent().getStringArrayListExtra(EXTRA_URLS);
        List<Live.Channel> all = Live.channelsNow(this);
        if (urls != null) {
            Map<String, Live.Channel> byUrl = new HashMap<>();
            for (Live.Channel ch : all) byUrl.put(ch.url, ch);
            for (String u : urls) if (byUrl.containsKey(u)) channels.add(byUrl.get(u));
        }
        if (channels.isEmpty()) {
            finish();
            return;
        }
        index = Math.max(0, Math.min(channels.size() - 1, getIntent().getIntExtra(EXTRA_INDEX, 0)));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        video = new SurfaceView(this);
        video.getHolder().addCallback(this);
        root.addView(video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        strip = new Strip(this);
        root.addView(strip, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        play();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {}

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        holder = null;
        release();
    }

    /** Plays channels[index] (with sound) and shows the strip. */
    private void play() {
        release();
        if (holder == null) return;
        Live.Channel ch = channels.get(index);
        loading = true;
        failed = false;
        showStrip();
        try {
            MediaPlayer m = new MediaPlayer();
            player = m;
            m.setDisplay(holder);
            m.setDataSource(ch.url);
            m.setOnPreparedListener(MediaPlayer::start);
            m.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && mp == player) {
                    loading = false;
                    strip.invalidate();
                }
                return false;
            });
            m.setOnErrorListener((mp, what, extra) -> {
                if (mp != player) return true;
                AppLog.w("live: " + ch.name + " didn't play (" + what + "/" + extra + ")");
                loading = false;
                failed = true;
                showStrip();
                return true;
            });
            m.prepareAsync();
        } catch (Exception e) {
            AppLog.w("live: " + ch.name + ": " + e.getMessage());
            loading = false;
            failed = true;
            showStrip();
        }
    }

    private void release() {
        if (player == null) return;
        try {
            player.release();
        } catch (RuntimeException ignored) {
        }
        player = null;
    }

    private void showStrip() {
        strip.setVisibility(View.VISIBLE);
        strip.invalidate();
        handler.removeCallbacks(hideStrip);
        if (!failed) handler.postDelayed(hideStrip, STRIP_MS);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                index = (index - 1 + channels.size()) % channels.size();
                play();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                index = (index + 1) % channels.size();
                play();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                showStrip();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    /**
     * Gone from the screen (Home, another app): back to the home screen next time. Not on pause: Fire TV's overlays
     * (Alexa's media check when playback starts) pause the activity for a moment, and that isn't leaving.
     */
    @Override protected void onStop() {
        super.onStop();
        release();
        finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        logos.shutdownNow();
        super.onDestroy();
    }

    /**
     * The channel's logo, from storage (fetched there first if new), loaded once in the background at about the size
     * it's drawn (null until then).
     */
    private Bitmap logo(String url) {
        if (url == null || url.isEmpty()) return null;
        synchronized (logoCache) {
            if (logoCache.containsKey(url)) return logoCache.get(url);
            logoCache.put(url, null);
            while (logoCache.size() > LOGOS) logoCache.remove(logoCache.keySet().iterator().next());
        }
        logos.execute(() -> {
            Bitmap b = null;
            try {
                java.io.File f = Live.store(this, url);
                if (f != null) b = small(f.getPath(), 160, 100);
            } catch (RuntimeException | OutOfMemoryError ignored) {
            }
            final Bitmap ready = b;
            synchronized (logoCache) { logoCache.put(url, ready); }
            handler.post(() -> strip.invalidate());
        });
        return null;
    }

    /** The image fitted into w × h (never larger than it is). */
    private static Bitmap small(String path, int w, int h) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
        Bitmap src = BitmapFactory.decodeFile(path, opts);
        if (src == null) return null;
        float sc = Math.min(1f, Math.min((float) w / src.getWidth(), (float) h / src.getHeight()));
        if (sc >= 1f) return src;
        Bitmap out = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * sc)), Math.max(1, Math.round(src.getHeight() * sc)), true);
        if (out != src) src.recycle();
        return out;
    }

    /** The strip at the bottom. */
    private final class Strip extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Paint label, meta, dim, tag;
        private TextPaint title;
        private LinearGradient shade;
        private final RectF r = new RectF(), logoBox = new RectF();
        private float k;
        // Text made once per channel, programme or width (not on every frame).
        private Live.Channel textFor;
        private String channelText, upText, downText;
        private String whatFor, whatText;
        private float whatWidth;
        private final Runnable later = this::invalidate;   // one pending redraw (see MusicSaverView.later)
        private Live.Show timesFor, nextFor;
        private String fromText, toText, nextText;

        Strip(Context c) {
            super(c);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            label = Ui.text(Ui.semibold, 18 * k, 0xCCFFFFFF);
            label.setLetterSpacing(0.12f);
            tag = Ui.text(Ui.semibold, 15 * k, 0xFFFFFFFF);
            tag.setLetterSpacing(0.08f);
            title = new TextPaint(Ui.text(Ui.semibold, 52 * k, 0xFFF4F4F6));
            whatFor = null;
            meta = Ui.text(Ui.medium, 20 * k, 0xBFFFFFFF);
            dim = Ui.text(Ui.medium, 18 * k, 0x99FFFFFF);
            shade = new LinearGradient(0, h - 420 * k, 0, h, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas c) {
            if (title == null) return;
            int w = getWidth(), h = getHeight();
            fill.setShader(shade);
            c.drawRect(0, h - 420 * k, w, h, fill);
            fill.setShader(null);
            Live.Channel ch = channels.get(index);
            long now = System.currentTimeMillis();
            Live.Show show = ch.now(now), next = ch.next(now);
            if (ch != textFor) {
                textFor = ch;
                channelText = ch.name.toUpperCase(Ui.locale());
                upText = "▲ " + channels.get((index - 1 + channels.size()) % channels.size()).name;
                downText = "▼ " + channels.get((index + 1) % channels.size()).name;
            }
            float left = 96 * k, bottom = h - 64 * k;
            // Logo box.
            r.set(left, bottom - 96 * k, left + 150 * k, bottom);
            fill.setColor(0x1AFFFFFF);
            c.drawRoundRect(r, 16 * k, 16 * k, fill);
            Bitmap logo = logo(ch.logo);
            if (logo != null) {
                float s = Math.min(r.width() * 0.8f / logo.getWidth(), r.height() * 0.7f / logo.getHeight());
                float lw = logo.getWidth() * s, lh = logo.getHeight() * s;
                logoBox.set(r.centerX() - lw / 2, r.centerY() - lh / 2, r.centerX() + lw / 2, r.centerY() + lh / 2);
                c.drawBitmap(logo, null, logoBox, image);
            }
            float x = r.right + 32 * k;
            // LIVE · CHANNEL
            String live = Ui.tr("LIVE");
            float tw = tag.measureText(live);
            r.set(x, bottom - 96 * k - 26 * k, x + tw + 18 * k, bottom - 96 * k);
            fill.setColor(0xFFFF3B30);
            c.drawRoundRect(r, 6 * k, 6 * k, fill);
            c.drawText(live, x + 9 * k, r.centerY() + Ui.halfCap(tag), tag);
            c.drawText(channelText, r.right + 12 * k, r.centerY() + Ui.halfCap(label), label);
            String what = failed ? Ui.tr("Couldn't play this channel") : show != null ? show.title : ch.name;
            float maxW = w - x - 380 * k;
            if (!what.equals(whatFor) || maxW != whatWidth) {
                whatFor = what;
                whatWidth = maxW;
                whatText = TextUtils.ellipsize(what, title, maxW, TextUtils.TruncateAt.END).toString();
            }
            c.drawText(whatText, x, bottom - 40 * k, title);
            if (show != null && !failed) {
                if (show != timesFor) {
                    timesFor = show;
                    fromText = Ui.time(show.start);
                    toText = Ui.time(show.stop);
                }
                if (next != nextFor) {
                    nextFor = next;
                    nextText = next == null ? null : Ui.trf("Next · {title}", "title", next.title);
                }
                String from = fromText, to = toText;
                float y = bottom - 4 * k, fw = meta.measureText(from);
                c.drawText(from, x, y, meta);
                float bx = x + fw + 18 * k, bw = 620 * k;
                r.set(bx, y - 12 * k, bx + bw, y - 6 * k);
                fill.setColor(0x38FFFFFF);
                c.drawRoundRect(r, 3 * k, 3 * k, fill);
                float done = Ui.clamp((now - show.start) / (float) Math.max(1, show.stop - show.start));
                r.right = bx + bw * done;
                fill.setColor(0xFFFF3B30);
                c.drawRoundRect(r, 3 * k, 3 * k, fill);
                c.drawText(to, bx + bw + 18 * k, y, meta);
                if (nextText != null) c.drawText(nextText, bx + bw + 40 * k + meta.measureText(to), y, dim);
            } else if (loading) {
                c.drawText(Ui.tr("Loading…"), x, bottom - 4 * k, meta);
            }
            // The channels above and below.
            if (channels.size() > 1) {
                float rx = w - 96 * k;
                c.drawText(upText, rx - dim.measureText(upText), bottom - 40 * k, dim);
                c.drawText(downText, rx - dim.measureText(downText), bottom - 8 * k, dim);
            }
            removeCallbacks(later);
            if (loading || show != null) postDelayed(later, loading ? 500 : 30_000);
        }
    }
}
