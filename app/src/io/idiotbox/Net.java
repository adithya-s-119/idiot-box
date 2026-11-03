package io.idiotbox;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** HTTP for the whole app (GET only), and the background queues that run it. */
final class Net {
    private Net() {}

    /** An answer other than 200 (or 304, for `connect`): never tried again. */
    static final class HttpError extends IOException {
        final int code;

        HttpError(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    /**
     * Opens `url` and returns the connection once it has answered, whatever the code (the caller reads and disconnects
     * it). `headers` are name/value pairs. A connection that fails is tried again, up to `tries` times, 2, 4… s apart.
     */
    static HttpURLConnection connect(String url, int connectMs, int readMs, int tries, String... headers) throws IOException {
        for (int i = 1; ; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "IdiotBox");
            for (int h = 0; h + 1 < headers.length; h += 2) if (headers[h + 1] != null) c.setRequestProperty(headers[h], headers[h + 1]);
            try {
                c.getResponseCode();
                return c;
            } catch (IOException e) {
                c.disconnect();
                if (i >= tries) throw e;
                AppLog.w("net: " + url + " try " + i + " failed (" + e.getMessage() + ")");
                try {
                    Thread.sleep(2_000L * i);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Like connect, but only a 200 answer: anything else throws HttpError. */
    static HttpURLConnection open(String url, int connectMs, int readMs, int tries, String... headers) throws IOException {
        HttpURLConnection c = connect(url, connectMs, readMs, tries, headers);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new HttpError(code);
        }
        return c;
    }

    static byte[] get(String url, int connectMs, int readMs, String... headers) throws IOException {
        HttpURLConnection c = open(url, connectMs, readMs, 1, headers);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static String getString(String url, int connectMs, int readMs, String... headers) throws IOException {
        return new String(get(url, connectMs, readMs, headers), StandardCharsets.UTF_8);
    }

    /** Saves `url` to `f` through a .part file of this thread's own, so a cut-off download never looks finished. */
    static void download(String url, File f, int connectMs, int readMs) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + "." + Thread.currentThread().getId() + ".part");
        HttpURLConnection c = open(url, connectMs, readMs, 1);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            copy(in, out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("couldn't save " + f.getName());
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /** One job at a time, in order; its thread ends after 30 s with nothing to do (the stick has little memory). */
    static ExecutorService serial(String name) {
        ThreadPoolExecutor e = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        e.allowCoreThreadTimeOut(true);
        return e;
    }
}
