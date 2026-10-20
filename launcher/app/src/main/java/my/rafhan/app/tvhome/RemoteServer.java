package my.rafhan.app.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone remote's HTTP server: serves assets/remote.html and a small JSON API on port 8486.
 *
 * No ADB: D-pad keys only reach the launcher (RemoteBridge), Back/Home go through
 * RemoteAccessibilityService, media keys and volume through AudioManager, apps and inputs are
 * plain intents (SYSTEM_ALERT_WINDOW exempts us from Android 10's background-start block).
 *
 * Pairing: the phone asks for a PIN, the TV shows it (PinOverlay), the phone sends it back and
 * gets a token, kept in SharedPreferences "remote". Every other API call needs X-Token.
 * Requests whose Host isn't an IP address are refused (DNS rebinding).
 */
final class RemoteServer {
    static final int PORT = 8486;
    private static final int MAX_BODY = 4096;
    private static final long PIN_MS = 60_000;
    private static final int PIN_TRIES = 5;
    private static final String IP_HOST = "\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?";

    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final SharedPreferences prefs;
    private final PinOverlay overlay;
    private final FileApi files;
    private final AdbKeys adbKeys;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, byte[]> banners = new HashMap<>();
    private volatile boolean running;
    private volatile ServerSocket socket;

    // Pairing state, guarded by this.
    private String pin;
    private long pinUntil;
    private int pinTries;
    private long lastPinRequest;

    // App list cache (banner lookups), guarded by this.
    private List<AppEntry> appCache;
    private long appCacheAt;

    RemoteServer(Context ctx) {
        this.ctx = ctx;
        prefs = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE);
        overlay = new PinOverlay(ctx);
        files = new FileApi(ctx);
        adbKeys = new AdbKeys(ctx);
    }

    void start() {
        running = true;
        Thread t = new Thread(this::acceptLoop, "remote-http");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        ServerSocket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
        overlay.hide();
        adbKeys.close();
    }

    private void acceptLoop() {
        while (running) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                socket = s;
                while (running) {
                    Socket c = s.accept();
                    pool.execute(() -> handle(c));
                }
            } catch (IOException e) {
                if (running) SystemClock.sleep(5000); // port busy or network change: retry
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(Socket c) {
        try (Socket s = c) {
            s.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], target = parts[1];

            Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < 64; i++) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            String host = headers.get("host");
            if (host == null || !host.matches(IP_HOST)) {
                json(out, 403, err("Open the remote with the TV's IP address."));
                return;
            }
            long length = headers.containsKey("content-length") ? -1 : 0;
            try {
                length = Long.parseLong(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }
            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            Map<String, String> query = parseQuery(q < 0 ? "" : target.substring(q + 1));

            // Uploads are the one route with a big body: stream it straight to disk.
            if (path.equals("/api/files/upload")) {
                String token = headers.get("x-token");
                if (token == null) token = query.get("t");
                if (!authorised(token)) json(out, 401, err("Not paired."));
                else if (post(method, out)) {
                    s.setSoTimeout(30_000);
                    json(out, files.upload(query.get("dir"), query.get("name"), length, in));
                }
                return;
            }

            if (length < 0 || length > MAX_BODY) {
                json(out, 413, err("Request too large."));
                return;
            }
            byte[] body = new byte[(int) length];
            for (int read = 0; read < length; ) {
                int n = in.read(body, read, (int) length - read);
                if (n < 0) break;
                read += n;
            }
            route(method, path, query, headers, body, out);
        } catch (IOException | RuntimeException | JSONException ignored) {
        }
    }

    private void route(String method, String path, Map<String, String> query, Map<String, String> headers,
                       byte[] body, OutputStream out) throws IOException, JSONException {
        switch (path) {
            case "/":
            case "/index.html":
                send(out, 200, "text/html; charset=utf-8", asset("remote.html"), "no-cache");
                return;
            case "/font":
                send(out, 200, "font/ttf", asset("fonts/Outfit.ttf"), "max-age=86400");
                return;
            case "/icon.png":
                send(out, 200, "image/png", icon(), "max-age=86400");
                return;
            case "/api/pair":
                if (post(method, out)) json(out, requestPin());
                return;
            case "/api/pair/confirm":
                if (post(method, out)) json(out, confirmPin(body));
                return;
            default:
                break;
        }

        String token = headers.get("x-token");
        if (token == null) token = query.get("t");
        if (!authorised(token)) {
            json(out, 401, err("Not paired."));
            return;
        }
        switch (path) {
            case "/api/state": {
                // A phone using the remote is the moment to (re)connect ADB, so the TV's
                // "Allow USB debugging?" prompt shows while someone is looking at it.
                adbKeys.ensure();
                AdbKeys.State a = adbKeys.state();
                json(out, 200, new JSONObjectBuilder()
                        .put("ok", true)
                        .put("home", RemoteBridge.isHome())
                        .put("back", RemoteAccessibilityService.isOn())
                        .put("adb", a.name().toLowerCase(Locale.ROOT))
                        .put("adbError", a == AdbKeys.State.ERROR ? adbKeys.error() : null)
                        .put("name", Build.MODEL).build());
                return;
            }
            case "/api/text":
                if (post(method, out)) json(out, text(body));
                return;
            case "/api/apps":
                json(out, 200, appsJson());
                return;
            case "/api/banner": {
                byte[] png = banner(query.get("pkg"));
                if (png == null) json(out, 404, err("No such app."));
                else send(out, 200, "image/png", png, "max-age=3600");
                return;
            }
            case "/api/inputs":
                json(out, 200, inputsJson());
                return;
            case "/api/open":
                if (post(method, out)) json(out, open(query.get("pkg")));
                return;
            case "/api/input":
                if (post(method, out)) json(out, input(query.get("i")));
                return;
            case "/api/key":
                if (post(method, out)) json(out, key(query.get("k"), query.get("n")));
                return;
            case "/api/files/roots":
                json(out, 200, files.rootsJson());
                return;
            case "/api/files/list": {
                JSONObject list = files.list(query.get("path"));
                if (list == null) json(out, 403, err("That folder isn't available."));
                else json(out, 200, list);
                return;
            }
            case "/api/files/raw": {
                java.io.File f = files.file(query.get("path"));
                if (f == null) json(out, 404, err("No such file."));
                else files.download(f, out);
                return;
            }
            case "/api/files/open":
                if (post(method, out)) {
                    java.io.File f = files.file(query.get("path"));
                    Intent view = f == null ? null : files.openIntent(f);
                    if (f == null) json(out, 404, err("No such file."));
                    else if (view == null) json(out, 415, err("The TV doesn't know how to open this file type."));
                    else json(out, start(view));
                }
                return;
            case "/api/unpair":
                if (post(method, out)) {
                    unpair(token);
                    json(out, 200, ok());
                }
                return;
            default:
                json(out, 404, err("Not found."));
        }
    }

    // ---------------------------------------------------------------- pairing

    private synchronized Object[] requestPin() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPinRequest < 3000) return new Object[] {429, err("Wait a moment and try again.")};
        if (!overlay.canShow()) {
            return new Object[] {503, err("The TV can't show the PIN: run scripts/build-and-install.sh to grant the overlay permission.")};
        }
        lastPinRequest = now;
        pin = String.format(Locale.ROOT, "%04d", random.nextInt(10000));
        pinUntil = now + PIN_MS;
        pinTries = 0;
        overlay.show(pin, lanUrl(), PIN_MS);
        return new Object[] {200, ok()};
    }

    private synchronized Object[] confirmPin(byte[] body) {
        String given;
        try {
            given = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("pin");
        } catch (JSONException e) {
            return new Object[] {400, err("Bad request.")};
        }
        if (pin == null || SystemClock.elapsedRealtime() > pinUntil) {
            pin = null;
            return new Object[] {410, err("No PIN on the TV. Tap \"Show PIN on TV\" again.")};
        }
        if (!pin.equals(given)) {
            if (++pinTries >= PIN_TRIES) {
                pin = null;
                overlay.hide();
                return new Object[] {403, err("Too many wrong tries. Show a new PIN on the TV.")};
            }
            return new Object[] {403, err("Wrong PIN (" + (PIN_TRIES - pinTries) + " tries left).")};
        }
        pin = null;
        overlay.hide();
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        String token = sb.toString();
        Set<String> tokens = new HashSet<>(prefs.getStringSet("tokens", new HashSet<>()));
        tokens.add(token);
        prefs.edit().putStringSet("tokens", tokens).apply();
        return new Object[] {200, new JSONObjectBuilder().put("ok", true).put("token", token).build()};
    }

    private boolean authorised(String token) {
        return token != null && !token.isEmpty() && prefs.getStringSet("tokens", new HashSet<>()).contains(token);
    }

    private void unpair(String token) {
        Set<String> tokens = new HashSet<>(prefs.getStringSet("tokens", new HashSet<>()));
        tokens.remove(token);
        prefs.edit().putStringSet("tokens", tokens).apply();
    }

    // ---------------------------------------------------------------- actions

    /** Dock first (launcher order), then the rest most-used first, like the grid. */
    private synchronized List<AppEntry> apps(boolean fresh) {
        long now = SystemClock.elapsedRealtime();
        if (fresh || appCache == null || now - appCacheAt > 30_000) {
            List<AppEntry> all = AppRepository.load(ctx);
            Map<String, AppEntry> byPkg = new HashMap<>();
            for (AppEntry e : all) byPkg.put(e.pkg, e);
            List<AppEntry> ordered = new ArrayList<>();
            for (String pkg : DockConfig.load(ctx)) {
                AppEntry e = byPkg.get(pkg);
                if (e != null && !ordered.contains(e) && ordered.size() < 5) ordered.add(e);
            }
            List<AppEntry> rest = new ArrayList<>(all);
            rest.removeAll(ordered);
            UsageScores.sort(rest, UsageScores.week(ctx));
            ordered.addAll(rest);
            appCache = ordered;
            appCacheAt = now;
        }
        return appCache;
    }

    private AppEntry find(String pkg) {
        if (pkg == null) return null;
        for (AppEntry e : apps(false)) if (e.pkg.equals(pkg)) return e;
        return null;
    }

    private JSONObject appsJson() {
        JSONArray arr = new JSONArray();
        List<AppEntry> list = apps(true);
        int dock = Math.min(5, DockConfig.load(ctx).size());
        for (int i = 0; i < list.size(); i++) {
            AppEntry e = list.get(i);
            arr.put(new JSONObjectBuilder().put("pkg", e.pkg).put("label", e.label).put("dock", i < dock).build());
        }
        return new JSONObjectBuilder().put("ok", true).put("apps", arr).build();
    }

    private byte[] banner(String pkg) {
        AppEntry e = find(pkg);
        if (e == null) return null;
        synchronized (banners) {
            byte[] cached = banners.get(e.cacheKey);
            if (cached != null) return cached;
        }
        Bitmap bmp = TileRenderer.render(ctx, e, 320, 180);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
        bmp.recycle();
        byte[] bytes = png.toByteArray();
        synchronized (banners) {
            banners.put(e.cacheKey, bytes);
        }
        return bytes;
    }

    private Object[] open(String pkg) {
        AppEntry e = find(pkg);
        if (e == null) return new Object[] {404, err("No such app.")};
        return start(e.launch);
    }

    private JSONObject inputsJson() {
        JSONArray arr = new JSONArray();
        List<TvInputs.Item> items = TvInputs.load(ctx);
        for (int i = 0; i < items.size(); i++) {
            arr.put(new JSONObjectBuilder().put("i", i).put("label", items.get(i).label).build());
        }
        return new JSONObjectBuilder().put("ok", true).put("inputs", arr).build();
    }

    private Object[] input(String index) {
        List<TvInputs.Item> items = TvInputs.load(ctx);
        try {
            int i = Integer.parseInt(index);
            if (i >= 0 && i < items.size()) return start(new Intent(items.get(i).intent));
        } catch (NumberFormatException ignored) {
        }
        return new Object[] {404, err("No such input.")};
    }

    /** Why a key that needs ADB can't be sent right now. */
    private Object[] adbReason() {
        AdbKeys.State a = adbKeys.state();
        String reason = a == AdbKeys.State.OFF ? "home-only"
                : a == AdbKeys.State.ALLOW ? "adb-allow"
                : a == AdbKeys.State.ERROR ? "adb-error" : "adb-connecting";
        adbKeys.ensure();
        return new Object[] {200, new JSONObjectBuilder().put("ok", false).put("reason", reason)
                .put("error", adbKeys.error()).build()};
    }

    private Object[] text(byte[] body) {
        String t;
        try {
            t = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("text");
        } catch (JSONException e) {
            return new Object[] {400, err("Bad request.")};
        }
        if (t.length() > 200) t = t.substring(0, 200);
        if (adbKeys.state() != AdbKeys.State.READY) return adbReason();
        return adbKeys.type(t) ? new Object[] {200, ok()} : adbReason();
    }

    private Object[] key(String k, String count) {
        if (k == null) return new Object[] {400, err("Missing key.")};
        int n = 1;
        try {
            n = Math.max(1, Math.min(50, Integer.parseInt(count)));
        } catch (NumberFormatException ignored) {
        }
        AudioManager am = ctx.getSystemService(AudioManager.class);
        switch (k) {
            case "up":
            case "down":
            case "left":
            case "right":
            case "ok":
                // Home screen: the launcher's own focus logic (instant). Elsewhere: ADB keypad.
                if (RemoteBridge.isHome()) {
                    for (int i = 0; i < n; i++) RemoteBridge.key(k);
                    return new Object[] {200, ok()};
                }
                if (adbKeys.state() == AdbKeys.State.READY && adbKeys.key(k, n)) return new Object[] {200, ok()};
                return adbReason();
            case "enter":
            case "backspace":
                if (adbKeys.state() == AdbKeys.State.READY && adbKeys.key(k, n)) return new Object[] {200, ok()};
                return adbReason();
            case "back":
                if (RemoteBridge.key("back")) return new Object[] {200, ok()};
                if (RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_BACK)) return new Object[] {200, ok()};
                return new Object[] {200, new JSONObjectBuilder().put("ok", false).put("reason", "no-accessibility").build()};
            case "home":
                if (RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_HOME)) return new Object[] {200, ok()};
                return start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
            case "playpause":
                media(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return new Object[] {200, ok()};
            case "next":
                media(am, KeyEvent.KEYCODE_MEDIA_NEXT);
                return new Object[] {200, ok()};
            case "prev":
                media(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                return new Object[] {200, ok()};
            case "volup":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return new Object[] {200, ok()};
            case "voldown":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return new Object[] {200, ok()};
            case "mute":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                return new Object[] {200, ok()};
            default:
                return new Object[] {400, err("Unknown key.")};
        }
    }

    private static void media(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private Object[] start(Intent intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return new Object[] {200, ok()};
        } catch (ActivityNotFoundException | SecurityException e) {
            return new Object[] {500, err("No app on the TV can open it.")};
        }
    }

    // ---------------------------------------------------------------- helpers

    /** e.g. http://192.168.0.44:8486, for the PIN card. */
    private String lanUrl() {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        Network net = cm.getActiveNetwork();
        LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
        if (lp != null) {
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                    return "http://" + la.getAddress().getHostAddress() + ":" + PORT;
                }
            }
        }
        return "port " + PORT;
    }

    private byte[] icon() {
        Bitmap bmp = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.BLACK);
        Drawable d = ctx.getDrawable(R.drawable.ic_home);
        if (d != null) {
            d.setBounds(30, 30, 150, 150);
            d.draw(c);
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
        return png.toByteArray();
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }

    private static boolean post(String method, OutputStream out) throws IOException {
        if ("POST".equals(method)) return true;
        json(out, 405, err("Use POST."));
        return false;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int b; (b = in.read()) != -1; ) {
            if (b == '\n') return sb.toString();
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<>();
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                map.put(k, v);
            } catch (IOException | IllegalArgumentException ignored) {
            }
        }
        return map;
    }

    private static JSONObject ok() {
        return new JSONObjectBuilder().put("ok", true).build();
    }

    private static JSONObject err(String message) {
        return new JSONObjectBuilder().put("ok", false).put("error", message).build();
    }

    private static void json(OutputStream out, Object[] codeAndBody) throws IOException {
        json(out, (Integer) codeAndBody[0], (JSONObject) codeAndBody[1]);
    }

    private static void json(OutputStream out, int code, JSONObject body) throws IOException {
        send(out, code, "application/json", body.toString().getBytes(StandardCharsets.UTF_8), "no-store");
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String cache) throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: " + cache + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 410: return "Gone";
            case 411: return "Length Required";
            case 415: return "Unsupported Media Type";
            case 500: return "Internal Server Error";
            case 507: return "Insufficient Storage";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    /** JSONObject.put throws a checked exception for non-finite numbers only; hide it. */
    private static final class JSONObjectBuilder {
        private final JSONObject o = new JSONObject();

        JSONObjectBuilder put(String k, Object v) {
            try {
                o.put(k, v);
            } catch (JSONException ignored) {
            }
            return this;
        }

        JSONObject build() {
            return o;
        }
    }
}
