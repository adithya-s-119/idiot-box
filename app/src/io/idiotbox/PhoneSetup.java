package io.idiotbox;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone page: Home Settings → Set up from your phone shows a QR code of http://the-TV:port/token, and a phone on
 * the same Wi-Fi opens a form there for the TMDB key, the city and Live TV's playlists and guides; saving it saves them
 * on the TV. It listens only while that screen is open (MAX_MS at most), answers only the random token's address, and
 * never sends the saved key back.
 */
final class PhoneSetup {
    private PhoneSetup() {}

    interface Saved {
        void saved();
    }

    private static final int PORT = 8765, MAX_BODY = 64 * 1024, MAX_LINKS = 20, MAX_LINK = 2048;
    private static final long MAX_MS = 15 * 60_000L;
    private static ServerSocket server;

    /** Starts listening; the page's address, or null when the TV has no network address. [saved] runs on the main thread. */
    static synchronized String start(Saved saved) {
        stop();
        String ip = address();
        if (ip == null) return null;
        try {
            ServerSocket s;
            try {
                s = new ServerSocket(PORT);
            } catch (IOException taken) {
                s = new ServerSocket(0);
            }
            server = s;
        } catch (IOException e) {
            AppLog.w("phone setup: " + e.getMessage());
            return null;
        }
        byte[] random = new byte[9];
        new SecureRandom().nextBytes(random);
        String path = "/" + android.util.Base64.encodeToString(random, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        ServerSocket listening = server;
        Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> serve(listening, path, () -> main.post(saved::saved)), "phone-setup");
        t.setDaemon(true);
        t.start();
        main.postDelayed(() -> {
            synchronized (PhoneSetup.class) {
                if (server == listening) stop();
            }
        }, MAX_MS);
        return "http://" + ip + ":" + listening.getLocalPort() + path;
    }

    static synchronized void stop() {
        if (server == null) return;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    /** The TV's address on the home network (Wi-Fi or Ethernet). */
    private static String address() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception e) {
            AppLog.w("phone setup: " + e.getMessage());
        }
        return null;
    }

    private static void serve(ServerSocket s, String path, Runnable saved) {
        while (!s.isClosed()) {
            try (Socket client = s.accept()) {
                client.setSoTimeout(10_000);
                answer(client, path, saved);
            } catch (Exception e) {
                if (!s.isClosed()) AppLog.w("phone setup: " + e.getMessage());
            }
        }
    }

    private static void answer(Socket client, String path, Runnable saved) throws IOException {
        InputStream in = client.getInputStream();
        String head = readHead(in);
        String[] lines = head.split("\r\n");
        String[] request = lines[0].split(" ");
        OutputStream out = client.getOutputStream();
        if (request.length < 2 || !request[1].split("\\?")[0].equals(path)) {
            send(out, "404 Not Found", "Not found");
            return;
        }
        if (request[0].equals("POST")) {
            int length = 0;
            for (String line : lines) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
            }
            if (length < 0 || length > MAX_BODY) {
                send(out, "413 Payload Too Large", "Too large");
                return;
            }
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) break;
                read += n;
            }
            List<String> notes = save(form(new String(body, 0, read, StandardCharsets.UTF_8)));
            saved.run();
            send(out, "200 OK", page(notes));
        } else {
            send(out, "200 OK", page(null));
        }
    }

    /** The request line and headers, up to the empty line (8 KB at most). */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, matched = 0;
        while (matched < 4 && (c = in.read()) >= 0) {
            b.write(c);
            matched = (c == '\r' && (matched == 0 || matched == 2)) || (c == '\n' && (matched == 1 || matched == 3)) ? matched + 1 : 0;
            if (b.size() > 8192) throw new IOException("headers too long");
        }
        return b.toString("UTF-8");
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {
                // a malformed field is left out
            }
        }
        return out;
    }

    /** Saves what the form changed; what couldn't be saved, one line each (empty when all went in). */
    private static List<String> save(Map<String, String> f) {
        List<String> notes = new ArrayList<>();
        String key = f.getOrDefault("key", "").trim();
        if (f.containsKey("clearkey")) {
            Prefs.setTmdbKey("");
        } else if (!key.isEmpty()) {
            if (key.matches("[A-Za-z0-9._-]{16,1024}")) Prefs.setTmdbKey(key);
            else notes.add(Ui.tr("That doesn't look like a TMDB key."));
        }
        String city = f.getOrDefault("city", "").trim();
        if (!city.isEmpty() && !city.equals(Prefs.city())) {
            try {
                List<String[]> found = HomeData.findCity(city);
                if (found.isEmpty()) notes.add(Ui.trf("Couldn't find “{city}”.", "city", city));
                else Prefs.setLocation(found.get(0)[1], found.get(0)[2], found.get(0)[3]);
            } catch (Exception e) {
                notes.add(Ui.trf("Couldn't find “{city}”.", "city", city));
            }
        }
        List<String> playlists = links(f.get("playlists"), notes), guides = links(f.get("guides"), notes);
        if (playlists != null) Prefs.setPlaylists(playlists);
        if (guides != null) Prefs.setGuides(guides);
        return notes;
    }

    /** The links of a box, one per line; null (left as it was) when one isn't a web link. */
    private static List<String> links(String box, List<String> notes) {
        if (box == null) return null;
        List<String> out = new ArrayList<>();
        for (String line : box.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (!(l.startsWith("http://") || l.startsWith("https://")) || l.length() > MAX_LINK || l.contains(" ")) {
                notes.add(Ui.trf("Not a web link: {link}", "link", l.length() > 80 ? l.substring(0, 80) + "…" : l));
                return null;
            }
            if (out.size() < MAX_LINKS) out.add(l);
        }
        return out;
    }

    private static void send(OutputStream out, String status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String type = body.startsWith("<!doctype") ? "text/html; charset=utf-8" : "text/plain; charset=utf-8";
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    /** The form, filled in with what's saved (but the key); [notes]: after a save, what didn't go in. */
    private static String page(List<String> notes) {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>").append(esc(Ui.tr("Idiot Box settings"))).append("</title><style>")
                .append(":root{color-scheme:dark}body{margin:0;padding:24px 16px 48px;background:#101014;color:#f2f2f7;")
                .append("font:16px/1.45 -apple-system,system-ui,Roboto,sans-serif}main{max-width:560px;margin:0 auto}")
                .append("h1{font-size:24px;margin:0 0 20px}label{display:block;font-weight:600;margin:22px 0 6px}")
                .append("input[type=text],input[type=password],textarea{box-sizing:border-box;width:100%;padding:12px;border-radius:10px;")
                .append("border:1px solid #3a3a40;background:#1c1c22;color:inherit;font:inherit}textarea{min-height:96px;font-size:14px}")
                .append("small{display:block;color:#a1a1aa;margin-top:6px}.check{font-weight:400;margin-top:10px}")
                .append("button{margin-top:28px;width:100%;padding:14px;border:0;border-radius:12px;background:#f2f2f7;color:#101014;")
                .append("font:600 17px system-ui,sans-serif}.ok{background:#16351f;color:#b7f5c6}.bad{background:#3d2a12;color:#ffd79a}")
                .append(".note{padding:12px 14px;border-radius:10px;margin-bottom:8px}</style></head><body><main>")
                .append("<h1>").append(esc(Ui.tr("Idiot Box settings"))).append("</h1>");
        if (notes != null && notes.isEmpty()) {
            h.append("<div class='note ok'>").append(esc(Ui.tr("Saved. The TV uses the changes now."))).append("</div>");
        } else if (notes != null) {
            for (String n : notes) h.append("<div class='note bad'>").append(esc(n)).append("</div>");
        }
        boolean hasKey = !Prefs.tmdbKey().isEmpty();
        h.append("<form method=post autocomplete=off>")
                .append("<label for=key>").append(esc(Ui.tr("TMDB key"))).append("</label>")
                .append("<input id=key name=key type=password autocapitalize=off spellcheck=false placeholder='")
                .append(esc(hasKey ? Ui.tr("A key is saved. Leave this empty to keep it.") : "")).append("'>")
                .append("<small>").append(esc(Ui.tr("Free from themoviedb.org → Settings → API."))).append("</small>");
        if (hasKey) {
            h.append("<label class=check><input type=checkbox name=clearkey> ").append(esc(Ui.tr("Remove the saved key"))).append("</label>");
        }
        h.append("<label for=city>").append(esc(Ui.tr("Location"))).append("</label>")
                .append("<input id=city name=city type=text value='").append(esc(Prefs.city())).append("'>")
                .append("<small>").append(esc(Ui.tr("Your city, for the weather."))).append("</small>")
                .append("<label for=playlists>").append(esc(Ui.tr("Live TV channels"))).append("</label>")
                .append("<textarea id=playlists name=playlists autocapitalize=off spellcheck=false>")
                .append(esc(Prefs.ownPlaylists() ? Ui.join("\n", Prefs.playlists()) : "")).append("</textarea>")
                .append("<small>").append(esc(Ui.tr("One M3U link per line. Empty: the built-in channels."))).append("</small>")
                .append("<label for=guides>").append(esc(Ui.tr("TV guide"))).append("</label>")
                .append("<textarea id=guides name=guides autocapitalize=off spellcheck=false>")
                .append(esc(Prefs.ownGuides() ? Ui.join("\n", Prefs.guides()) : "")).append("</textarea>")
                .append("<small>").append(esc(Ui.tr("One XMLTV link per line. Empty: the built-in guide."))).append("</small>")
                .append("<button>").append(esc(Ui.tr("Save"))).append("</button></form></main></body></html>");
        return h.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }
}
