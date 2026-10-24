package my.rafhan.app.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * gets a token. The token is kept in the phone's localStorage and sent as X-Token; its SHA-256 is
 * what the TV stores (RemoteDevices), so the prefs file can't be replayed. Images and downloads
 * (which can't set a header) authenticate with an HttpOnly SameSite=Strict cookie instead, so the
 * token never has to appear in a URL.
 *
 * Requests whose Host isn't an IP address are refused (DNS rebinding), and cross-site requests are
 * refused too, so a web page can't drive the remote.
 */
final class RemoteServer {
    static final int PORT = 8486;
    private static final int MAX_BODY = 4096;
    private static final long PIN_MS = 60_000;
    private static final int PIN_TRIES = 5;
    private static final String IP_HOST = "\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?";

    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final PinOverlay overlay;
    private final MessageOverlay messages;
    private final FileApi files;
    private final AdbKeys adbKeys;
    private final RemoteDevices devices;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, byte[]> banners = new HashMap<>();
    private final SleepTimer sleepTimer;
    private final RemoteSettings settings;
    private volatile boolean running;
    private volatile ServerSocket socket;

    // Pairing state, guarded by this.
    private String pin;
    private long pinUntil;
    private int pinTries;
    private long lastPinRequest;

    // App list cache (banner lookups), guarded by this.
    private List<AppEntry> appCache;
    private int appCacheDock;
    private long appCacheAt;

    RemoteServer(Context ctx, SleepTimer sleepTimer) {
        this.ctx = ctx;
        this.sleepTimer = sleepTimer;
        overlay = new PinOverlay(ctx);
        messages = new MessageOverlay(ctx);
        files = new FileApi(ctx);
        adbKeys = AdbKeys.get(ctx);
        devices = new RemoteDevices(ctx);
        settings = new RemoteSettings(ctx);
    }

    void start() {
        running = true;
        Thread t = new Thread(this::acceptLoop, "remote-http");
        t.setDaemon(true);
        t.start();
    }

    /** Drops cached app banners and the app list (rebuilt on the next phone request). */
    void trim() {
        synchronized (banners) {
            banners.clear();
        }
        synchronized (this) {
            appCache = null;
        }
        settings.trim();
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
            // A web page on another site can't drive the remote: its requests either don't carry the
            // cookie (SameSite=Strict) or are flagged here. The IP-only Host check above already
            // stops DNS rebinding.
            String origin = headers.get("origin");
            if ("cross-site".equals(headers.get("sec-fetch-site"))
                    || (origin != null && !origin.matches("(?i)https?://" + IP_HOST))) {
                json(out, 403, err("Cross-site requests aren't allowed."));
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
                if (!authorised(requestToken(headers))) json(out, 401, err("Not paired."));
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
            case "/manifest.json": // lets phones install the remote as a full-screen app
                send(out, 200, "application/manifest+json", ("{\"name\":\"TV Remote\",\"short_name\":\"TV Remote\","
                        + "\"start_url\":\"/\",\"display\":\"fullscreen\",\"orientation\":\"portrait\","
                        + "\"background_color\":\"#000000\",\"theme_color\":\"#000000\","
                        + "\"icons\":[{\"src\":\"/icon.png\",\"sizes\":\"180x180\",\"type\":\"image/png\"}]}")
                        .getBytes(StandardCharsets.UTF_8), "max-age=86400");
                return;
            case "/api/pair":
                if (post(method, out)) json(out, requestPin());
                return;
            case "/api/pair/confirm":
                if (post(method, out)) {
                    Object[] r = confirmPin(body, headers.get("user-agent"));
                    if ((int) r[0] == 200) {
                        // Also hand the phone an HttpOnly cookie, so images and downloads (which
                        // can't set a header) authenticate without a token in the URL.
                        json(out, 200, (JSONObject) r[1], cookie(((JSONObject) r[1]).optString("token")));
                    } else {
                        json(out, r);
                    }
                }
                return;
            default:
                break;
        }

        String token = requestToken(headers);
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
            case "/api/dock":
                if (post(method, out)) json(out, saveDock(body));
                return;
            case "/api/key":
                if (post(method, out)) json(out, key(query.get("k"), query.get("n")));
                return;
            case "/api/files/roots":
                json(out, 200, files.rootsJson());
                return;
            case "/api/files/list": {
                JSONObject list = files.list(query.get("path"));
                if (list == null) json(out, 403, err(FileApi.hasAllFilesAccess()
                        ? "That folder isn't available."
                        : "On Android 11+, run scripts/build-and-install.sh to grant All files access."));
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
            case "/api/dashboard":
                json(out, 200, RemoteDashboard.build(ctx, adbKeys, sleepTimer));
                return;
            case "/api/media":
                if (post(method, out)) json(out, mediaAction(query.get("a")));
                return;
            case "/api/alerts/open":
                if (post(method, out)) json(out, openAlert(query.get("id")));
                return;
            case "/api/alerts/clear":
                if (post(method, out)) {
                    Alerts.get().clear();
                    json(out, 200, ok());
                }
                return;
            case "/api/message":
                if (post(method, out)) json(out, message(body));
                return;
            case "/api/openurl":
                if (post(method, out)) json(out, openUrl(body));
                return;
            case "/api/running":
                json(out, 200, runningJson());
                return;
            case "/api/running/close":
                if (post(method, out)) json(out, closeRunning(query.get("pkg")));
                return;
            case "/api/running/hide":
                if (post(method, out)) {
                    String pkg = query.get("pkg");
                    if (pkg == null) json(out, 400, err("No app given."));
                    else {
                        RunningApps.setHidden(ctx, pkg, !"0".equals(query.get("on")));
                        json(out, 200, ok());
                    }
                }
                return;
            case "/api/power":
                if (post(method, out)) json(out, power(query.get("a")));
                return;
            case "/api/sleeptimer":
                if (post(method, out)) {
                    int min = 0;
                    try {
                        min = Math.max(0, Math.min(240, Integer.parseInt(query.get("min"))));
                    } catch (Exception ignored) {
                    }
                    sleepTimer.set(min);
                    json(out, 200, ok());
                }
                return;
            case "/api/apps/disabled":
                json(out, 200, disabledJson());
                return;
            case "/api/apps/disable":
            case "/api/apps/enable":
                if (post(method, out)) {
                    String pkg = query.get("pkg");
                    String why = path.endsWith("disable") ? AppManager.disable(ctx, pkg) : AppManager.enable(ctx, pkg);
                    if (why == null) synchronized (this) {
                        appCache = null; // the app list changes
                    }
                    json(out, why == null ? 200 : 409, why == null ? ok() : err(why));
                }
                return;
            case "/api/apps/trimcaches":
                if (post(method, out)) {
                    long freed = AppManager.trimCaches(ctx);
                    if (freed < 0) json(out, 409, err("Turn on USB debugging on the TV first."));
                    else json(out, 200, new JSONObjectBuilder().put("ok", true).put("freedMb", freed / 1_000_000).build());
                }
                return;
            case "/api/screenshot":
                screenshot(out);
                return;
            case "/api/settings":
                if ("POST".equals(method)) {
                    String why;
                    try {
                        why = settings.set(new JSONObject(new String(body, StandardCharsets.UTF_8)));
                    } catch (JSONException e) {
                        why = "Bad request.";
                    }
                    if (why != null) json(out, 400, err(why));
                    else json(out, 200, settings.schema());
                } else {
                    json(out, 200, settings.schema());
                }
                return;
            case "/api/settings/zones":
                json(out, 200, settings.zones());
                return;
            case "/api/wallpapers":
                json(out, 200, settings.wallpapers());
                return;
            case "/api/wallpaper/thumb": {
                byte[] jpg = settings.thumb(query.get("id"));
                if (jpg == null) json(out, 404, err("No picture yet."));
                else send(out, 200, "image/jpeg", jpg, "max-age=86400");
                return;
            }
            case "/api/session":
                // Confirms a token that was paired before cookies existed and hands it to this
                // browser as a cookie, so banners/downloads work without a token in the URL.
                if (post(method, out)) json(out, 200, ok(), cookie(token));
                return;
            case "/api/devices":
                json(out, 200, new JSONObjectBuilder().put("ok", true).put("devices", devices.json(token)).build());
                return;
            case "/api/devices/revoke":
                if (post(method, out)) {
                    String id;
                    try {
                        id = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("id");
                    } catch (JSONException e) {
                        json(out, 400, err("Bad request."));
                        return;
                    }
                    if (id.isEmpty() || !devices.remove(id)) json(out, 404, err("That phone isn't paired."));
                    else json(out, 200, ok());
                }
                return;
            case "/api/unpair":
                if (post(method, out)) {
                    devices.removeByToken(token);
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
        overlay.show(pin, lanUrl(ctx), PIN_MS);
        return new Object[] {200, ok()};
    }

    private synchronized Object[] confirmPin(byte[] body, String userAgent) {
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
        String token = randomToken();
        devices.add(token, RemoteDevices.name(userAgent));
        return new Object[] {200, new JSONObjectBuilder().put("ok", true).put("token", token).build()};
    }

    private String randomToken() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private boolean authorised(String token) {
        return devices.find(token) != null;
    }

    /** Settings → Forget all paired phones: every phone has to pair with a PIN again. */
    static void forgetAll(Context ctx) {
        new RemoteDevices(ctx).removeAll();
    }

    /** The token of this request: the API header, or the pairing cookie for images and downloads. */
    private static String requestToken(Map<String, String> headers) {
        String token = headers.get("x-token");
        if (token != null && !token.isEmpty()) return token;
        String cookie = headers.get("cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String c = part.trim();
            if (c.startsWith(COOKIE + "=")) return c.substring(COOKIE.length() + 1);
        }
        return null;
    }

    private static final String COOKIE = "tvtoken";

    /** HttpOnly (JS can't read it) and Strict (never sent from another site). */
    private static String cookie(String token) {
        return COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000";
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
                if (e != null && !ordered.contains(e) && ordered.size() < DockConfig.MAX) ordered.add(e);
            }
            appCacheDock = ordered.size();
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

    /** {"pkgs":[...]}: the new dock, left to right (installed launchable apps only, at most 5). */
    private Object[] saveDock(byte[] body) {
        try {
            JSONArray arr = new JSONObject(new String(body, StandardCharsets.UTF_8)).getJSONArray("pkgs");
            if (arr.length() > DockConfig.MAX) return new Object[] {400, err("The dock holds up to " + DockConfig.MAX + " apps.")};
            List<String> known = new ArrayList<>();
            for (AppEntry e : AppRepository.load(ctx)) known.add(e.pkg);
            List<String> pkgs = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.getString(i);
                if (!known.contains(p)) return new Object[] {400, err("Unknown app: " + p)};
                if (!pkgs.contains(p)) pkgs.add(p);
            }
            if (!DockConfig.save(ctx, pkgs)) return new Object[] {500, err("Couldn't save the dock.")};
            synchronized (this) {
                appCache = null;
            }
            return new Object[] {200, ok()};
        } catch (JSONException e) {
            return new Object[] {400, err("Bad request.")};
        }
    }

    private JSONObject appsJson() {
        JSONArray arr = new JSONArray();
        List<AppEntry> list = apps(true);
        int dock;
        synchronized (this) {
            dock = appCacheDock;
        }
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

    /** TV tab: Play/Pause and Stop for whatever plays, or open the app that's playing. */
    private Object[] mediaAction(String a) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if ("playpause".equals(a)) {
            media(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            return new Object[] {200, ok()};
        }
        if ("stop".equals(a)) {
            media(am, KeyEvent.KEYCODE_MEDIA_STOP);
            return new Object[] {200, ok()};
        }
        if ("open".equals(a)) {
            NowPlayingPanel.Info i = RemoteDashboard.session(ctx);
            if (i == null) return new Object[] {404, err("Nothing is playing.")};
            return open(i.pkg);
        }
        return new Object[] {400, err("Unknown action.")};
    }

    /** TV tab: a bell notification opened on the TV (then cleared, as on the TV). */
    private Object[] openAlert(String id) {
        for (Alerts.Alert a : Alerts.get().list()) {
            if (!a.id.equals(id)) continue;
            Alerts.get().dismiss(a);
            return a.action != null ? start(new Intent(a.action)) : new Object[] {200, ok()};
        }
        return new Object[] {404, err("That notification is gone.")};
    }

    /** TV tab: a short message typed on the phone, in a box over whatever is on the TV until OK. */
    private Object[] message(byte[] body) {
        String t;
        try {
            t = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("text").trim();
        } catch (JSONException e) {
            return new Object[] {400, err("Bad request.")};
        }
        if (t.isEmpty()) return new Object[] {400, err("Type a message first.")};
        if (t.length() > 120) t = t.substring(0, 120);
        if (!messages.canShow()) {
            return new Object[] {503, err("The TV can't show messages: run scripts/build-and-install.sh to grant the overlay permission.")};
        }
        messages.show(t);
        return new Object[] {200, ok()};
    }

    /**
     * TV tab: a link pasted on the phone, opened on the TV. YouTube and Netflix links go straight to
     * their TV apps; anything else needs an app on the TV that opens web links.
     */
    private Object[] openUrl(byte[] body) {
        String url;
        try {
            url = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("url").trim();
        } catch (JSONException e) {
            return new Object[] {400, err("Bad request.")};
        }
        if (url.isEmpty()) return new Object[] {400, err("Paste a link first.")};
        if (!url.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) url = "https://" + url;
        android.net.Uri uri = android.net.Uri.parse(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String app = host.endsWith("youtube.com") || host.equals("youtu.be") ? "com.google.android.youtube.tv"
                : host.endsWith("netflix.com") ? "com.netflix.ninja" : null;
        Intent view = new Intent(Intent.ACTION_VIEW, uri);
        if (app != null) {
            Object[] r = start(new Intent(view).setPackage(app));
            if ((int) r[0] == 200) return r;
        }
        return start(view);
    }

    /** Apps tab: running apps (launchable ones), as on the TV's running-apps screen. Blocking. */
    private JSONObject runningJson() throws JSONException {
        JSONArray arr = new JSONArray();
        for (RunningApps.App a : RunningApps.list(ctx)) {
            arr.put(new JSONObject().put("pkg", a.entry.pkg).put("label", a.entry.label).put("state", a.state)
                    .put("memKb", a.pssKb).put("hidden", a.hidden));
        }
        return new JSONObject().put("ok", true).put("apps", arr)
                .put("adb", adbKeys.state() == AdbKeys.State.READY);
    }

    private Object[] closeRunning(String pkg) {
        if (pkg == null) return new Object[] {400, err("No app given.")};
        for (RunningApps.App a : RunningApps.list(ctx)) {
            if (!a.entry.pkg.equals(pkg)) continue;
            return new Object[] {200, new JSONObjectBuilder().put("ok", true)
                    .put("message", RunningApps.forceStop(ctx, a)).build()};
        }
        return new Object[] {200, new JSONObjectBuilder().put("ok", true).put("message", "It's not running any more.").build()};
    }

    /** Apps the phone remote disabled, with their names, to turn back on. */
    private JSONObject disabledJson() throws JSONException {
        JSONArray arr = new JSONArray();
        PackageManagerHelper pm = new PackageManagerHelper(ctx);
        for (String pkg : AppManager.disabled(ctx)) arr.put(new JSONObject().put("pkg", pkg).put("label", pm.label(pkg)));
        return new JSONObject().put("ok", true).put("apps", arr);
    }

    /** TV tab: Sleep now / Restart (ADB). Restart answers first: the TV drops off the network. */
    private Object[] power(String a) {
        if (adbKeys.state() != AdbKeys.State.READY) return adbReason();
        if ("sleep".equals(a)) {
            AppManager.run(ctx, "input keyevent KEYCODE_SLEEP");
            return new Object[] {200, ok()};
        }
        if ("reboot".equals(a)) {
            new Thread(() -> {
                SystemClock.sleep(800);
                AppManager.run(ctx, "reboot");
            }, "remote-reboot").start();
            return new Object[] {200, ok()};
        }
        return new Object[] {400, err("Unknown action.")};
    }

    private final Object shotLock = new Object();

    /**
     * The TV's screen as a PNG, for the phone's Screenshot button. Apps can't capture other apps,
     * so it runs `screencap` as the shell user over the loopback ADB connection (USB debugging on).
     * It writes to a file and reads it back: on this TV screencap's stdout carries vendor log noise.
     * DRM-protected video (Netflix, etc.) comes out black.
     */
    private void screenshot(OutputStream out) throws IOException {
        if (adbKeys.state() != AdbKeys.State.READY) {
            json(out, adbReason());
            return;
        }
        byte[] png = null;
        synchronized (shotLock) {
            java.io.File f = new java.io.File(android.os.Environment.getExternalStorageDirectory(), ".tvhome-screenshot.png");
            f.delete();
            AdbKeys.Hid h = adbKeys.shell("timeout 15 screencap -p " + f.getPath());
            if (h != null) {
                try {
                    while (h.stream.getSource().readUtf8Line() != null) { /* until screencap exits */ }
                } catch (Exception ignored) {
                } finally {
                    adbKeys.stop(h);
                }
                try {
                    if (f.length() > 0) png = java.nio.file.Files.readAllBytes(f.toPath());
                } catch (IOException ignored) {
                }
                f.delete();
            }
        }
        if (png == null) json(out, 500, err("Couldn't take a screenshot. Try again."));
        else send(out, 200, "image/png", png, "no-store");
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
        // A message from the phone is on screen: the phone's OK or Back closes it (even without
        // USB debugging, when the keys couldn't reach it).
        if (messages.isShowing() && (k.equals("ok") || k.equals("enter") || k.equals("back"))) {
            messages.hide();
            return new Object[] {200, ok()};
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
    static String lanUrl(Context ctx) {
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
        json(out, code, body, null);
    }

    private static void json(OutputStream out, int code, JSONObject body, String setCookie) throws IOException {
        send(out, code, "application/json", body.toString().getBytes(StandardCharsets.UTF_8), "no-store", setCookie);
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String cache) throws IOException {
        send(out, code, type, body, cache, null);
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String cache, String setCookie) throws IOException {
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Cache-Control: ").append(cache).append("\r\n");
        if (setCookie != null) head.append("Set-Cookie: ").append(setCookie).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
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
