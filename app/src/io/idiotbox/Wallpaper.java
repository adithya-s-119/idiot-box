package io.idiotbox;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The home screen's daily wallpaper. Three free sources, picked in Home Settings: Bing's photo of the day, NASA's
 * astronomy picture of the day (from the APOD page; its free API as a fallback) and Lorem Picsum (a random photo,
 * seeded by the date). One photo
 * day per source, cached on disk; the home screen cross-fades to it on every row, and the pick's title fades out
 * with it.
 */
final class Wallpaper {
    private Wallpaper() {}

    private static final String BING = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US";
    private static final String NASA = "https://api.nasa.gov/planetary/apod?thumbs=true&api_key=DEMO_KEY";
    /** The APOD page for a day (apYYMMDD.html): its picture is the one the API should give but doesn't always. */
    private static final String NASA_PAGE = "https://apod.nasa.gov/apod/ap";
    private static final Pattern APOD_IMAGE = Pattern.compile(
            "https://assets\\.science\\.nasa\\.gov/[^\"'\\s>]*/apod/apod/[^\"'\\s>]+\\.(?:jpe?g|png|webp)(?:\\?[^\"'\\s>]*)?",
            Pattern.CASE_INSENSITIVE);
    private static final String PICSUM = "https://picsum.photos/seed/idiotbox-";
    /** A failed fetch is tried again after this long. */
    private static final long RETRY_MS = 15 * 60_000;

    private static final ExecutorService QUEUE = Net.serial("wallpaper");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static volatile Bitmap image;
    private static volatile String imageSource = "";   // the source `image` belongs to
    private static volatile String day = "";           // the date `image` is for (yyyyMMdd)
    private static volatile boolean fetching;
    private static volatile String fetchingSource = "";
    private static volatile long triedAt;
    private static volatile String triedSource = "";
    private static volatile Runnable listener;

    /** The photo at the backdrop size, or null until it's loaded. Read on the UI thread. */
    static Bitmap image() {
        return image;
    }

    /** Called on the UI thread when a photo is ready (or the attempt failed): the home screen redraws. */
    static void listen(Runnable r) {
        listener = r;
    }

    /** Loads today's photo for `source` (the cached one at once, the network once a day). Safe to call often. */
    static void start(Context c, String source) {
        if (source == null || "off".equals(source)) return;
        source = valid(source);
        final String src = source;
        final Context app = c.getApplicationContext();
        final String today = today();
        synchronized (LOCK) {
            if (stamp(src, today).equals(day) && image != null && src.equals(imageSource)) return;
            if (!src.equals(triedSource)) {   // another source: its own retry clock
                triedAt = 0;
                triedSource = src;
            }
            if (fetching && src.equals(fetchingSource)) return;
            if (SystemClock.elapsedRealtime() - triedAt < RETRY_MS) return;
            fetching = true;
            fetchingSource = src;
        }
        QUEUE.execute(() -> {
            try {
                load(app, src, today);
            } catch (Exception e) {
                AppLog.w("wallpaper: " + e.getMessage());
            } finally {
                synchronized (LOCK) {
                    fetching = false;
                    triedAt = SystemClock.elapsedRealtime();
                }
                Runnable r = listener;
                if (r != null) MAIN.post(r);
            }
        });
    }

    /** Turns the wallpaper off: the bitmap goes (the files stay, for the next time it's on). */
    static void clear() {
        synchronized (LOCK) {
            image = null;
            imageSource = "";
            day = "";
            triedAt = 0;        // the same source can be tried at once when it's turned back on
            triedSource = "";
        }
    }

    private static String valid(String source) {
        return "nasa".equals(source) || "picsum".equals(source) ? source : "bing";
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    /** The stamp says which day the photo is for; NASA's ":page" retires the old logo the API used to give. */
    private static String stamp(String source, String day) {
        return "nasa".equals(source) ? day + ":page" : day;
    }

    private static void load(Context c, String source, String today) throws IOException {
        File dir = c.getFilesDir();
        File file = new File(dir, "wallpaper-" + source + ".jpg");
        File stamp = new File(dir, "wallpaper-" + source + ".date");
        if ("bing".equals(source)) migrateBing(dir, file, stamp);
        if (!stamp(source, today).equals(read(stamp)) || !file.exists()) fetch(source, file, stamp);
        String saved = read(stamp);
        if (image != null && saved.equals(day) && source.equals(imageSource)) return;   // already up
        if (!file.exists()) return;
        Bitmap src = decode(file);
        if (src == null) {
            // A bad download (an error page, a format Android can't read) must not be cached for the day: try again.
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            stamp.delete();
            throw new IOException("unreadable wallpaper");
        }
        Bitmap bg = Backdrop.background(src);   // center-cropped to the backdrop size; recycles src
        synchronized (LOCK) {
            image = bg;
            imageSource = source;
            day = saved;   // not today's when the fetch failed: `start` tries again later
        }
    }

    /** The old single Bing file, from before the source picker. */
    private static void migrateBing(File dir, File file, File stamp) {
        File old = new File(dir, "wallpaper.jpg"), oldStamp = new File(dir, "wallpaper.date");
        if (file.exists() || !old.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        old.renameTo(file);
        if (oldStamp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            oldStamp.renameTo(stamp);
        }
    }

    private static void fetch(String source, File file, File stamp) throws IOException {
        if ("nasa".equals(source)) fetchNasa(file, stamp);
        else if ("picsum".equals(source)) fetchPicsum(file, stamp);
        else fetchBing(file, stamp);
    }

    private static void fetchBing(File file, File stamp) throws IOException {
        String json = Net.getString(BING, 8000, 20000);
        JSONObject o;
        try {
            o = new JSONObject(json).getJSONArray("images").getJSONObject(0);
        } catch (JSONException e) {
            throw new IOException("bad Bing answer");
        }
        String base = o.optString("urlbase");
        if (base.isEmpty()) throw new IOException("no Bing image");
        String url = (base.startsWith("http") ? base : "https://www.bing.com" + base) + "_1920x1080.jpg";
        Net.download(url, file, 8000, 20000);
        write(stamp, today());
        AppLog.w("wallpaper: Bing photo" + (o.optString("title").isEmpty() ? "" : " (" + o.optString("title") + ")"));
    }

    /** NASA APOD: the day's picture from its page (the API sometimes gives the site logo); the API is the fallback. */
    private static void fetchNasa(File file, File stamp) throws IOException {
        String url = apodPageImage(today());
        JSONObject o = null;
        if (url == null) {
            o = parse(Net.getString(NASA, 8000, 20000));
            url = nasaImage(o);
            for (int i = 0; i < 4 && url == null; i++) {
                String date = o.optString("date");
                if (date.isEmpty()) break;
                try {
                    Calendar cal = Calendar.getInstance();
                    cal.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date));
                    cal.add(Calendar.DAY_OF_YEAR, -1);
                    date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
                } catch (ParseException e) {
                    break;
                }
                o = parse(Net.getString(NASA + "&date=" + date, 8000, 20000));
                url = nasaImage(o);
            }
        }
        if (url == null) throw new IOException("no NASA picture");
        downloadNasa(url, o, file);
        write(stamp, stamp("nasa", today()));
        AppLog.w("wallpaper: NASA APOD" + (o != null && !o.optString("title").isEmpty() ? " (" + o.optString("title") + ")" : ""));
    }

    /** The picture in the APOD page for today (or yesterday, when today's isn't up yet), or null. */
    private static String apodPageImage(String day) {
        for (int back = 0; back < 2; back++) {
            try {
                Calendar cal = Calendar.getInstance();
                cal.setTime(new SimpleDateFormat("yyyyMMdd", Locale.US).parse(day));
                cal.add(Calendar.DAY_OF_YEAR, -back);
                String page = Net.getString(NASA_PAGE
                        + new SimpleDateFormat("yyMMdd", Locale.US).format(cal.getTime()) + ".html", 8000, 20000);
                // The APOD page's own hero picture (the last hero block; the header's menu has others). The archive
                // and logo images come first.
                int hero = page.lastIndexOf("media-detail-hero__media");
                if (hero < 0) continue;
                Matcher m = APOD_IMAGE.matcher(page);
                m.region(hero, Math.min(page.length(), hero + 4000));
                if (m.find()) return m.group().replace("&amp;", "&");
            } catch (ParseException | IOException e) {
                AppLog.w("wallpaper: NASA page: " + e.getMessage());
            }
        }
        return null;
    }

    private static JSONObject parse(String json) throws IOException {
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            throw new IOException("bad NASA answer");
        }
    }

    private static String nasaImage(JSONObject o) {
        String type = o.optString("media_type");
        if ("video".equals(type)) {   // thumbs=true: the video's own picture, when NASA has one
            String thumbnail = o.optString("thumbnail_url");
            return thumbnail.isEmpty() ? null : thumbnail;
        }
        if (!"image".equals(type)) return null;
        String url = o.optString("hdurl");
        if (url.isEmpty()) url = o.optString("url");
        return url.isEmpty() ? null : url;
    }

    /** The bigger picture first, the standard one if it can't be fetched or isn't an image (and http is raised to https). */
    private static void downloadNasa(String url, JSONObject o, File file) throws IOException {
        if (o == null) {   // from the APOD page: no standard-URL fallback
            Net.download(https(url), file, 8000, 60000);
            if (!isImage(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                throw new IOException("not an image");
            }
            return;
        }
        IOException first;
        try {
            Net.download(https(url), file, 8000, 60000);
            if (isImage(file)) return;
            first = new IOException("not an image");
        } catch (IOException e) {
            first = e;
        }
        String standard = o.optString("url");
        if (standard.isEmpty() || standard.equals(url)) throw first;
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        Net.download(https(standard), file, 8000, 60000);
        if (!isImage(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw first;
        }
    }

    /** Whether `file` has an image's bounds (not an error page or a format Android cannot decode). */
    private static boolean isImage(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        return bounds.outWidth > 0 && bounds.outHeight > 0;
    }

    private static String https(String url) {
        return url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }

    private static void fetchPicsum(File file, File stamp) throws IOException {
        Net.download(PICSUM + today() + "/1920/1080", file, 8000, 30000);
        write(stamp, today());
        AppLog.w("wallpaper: Picsum photo");
    }

    private static Bitmap decode(File f) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.BG_W) opts.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), opts);
    }

    private static String read(File f) {
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            return Net.read(in).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
