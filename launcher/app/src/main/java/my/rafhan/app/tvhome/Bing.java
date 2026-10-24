package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.FaceDetector;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bing's "photo of the day" as a wallpaper that changes by itself each day. The last seven days'
 * pictures are downloaded from Bing's public HPImageArchive feed and cached in the app's files
 * dir; the newest is shown. Bing's day follows its market (the TV's language: en-US starts a new
 * picture at 07:00 UTC, 15:00 in Malaysia), so the feed is checked again only once the next
 * picture is due, not at local midnight. Nothing is bundled in the APK. Only scenic pictures are
 * kept: ones whose caption names people, or where a face is detected, are skipped (and remembered,
 * so they aren't downloaded again), so the wallpaper has no people or faces.
 */
final class Bing {
    private static final String TAG = "TvHome.Bing";
    private static final String FEED = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=";
    // A browser User-Agent: Bing serves the whole week to a browser, only today to a bare client.
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int KEEP = 7;
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyyMMddHHmm"); // UTC
    private static final long DAY_MS = 24 * 60 * 60_000L;

    // Captions that name people (mirrors glass-tv): the picture is skipped even if no face is found.
    private static final Pattern PEOPLE = Pattern.compile("(?i)\\b(people|persons?|man|men|woman|women"
            + "|girls?|boys?|child|children|kids?|famil(y|ies)|crowds?|festivals?|parades?|carnivals?"
            + "|danc(er|ers|ing)|surf(er|ers|ing)|hik(er|ers|ing)|climb(er|ers)|ski(er|ers)|swimm(er|ers)"
            + "|fisherm[ae]n|monks?|pilgrims?|tourists?|visitors?|weddings?|brides?|processions?"
            + "|performers?|athletes?|runners?|portraits?|selfies?)\\b");

    private Bing() {}

    private static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "bing");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("bing", Context.MODE_PRIVATE);
    }

    /** Cached pictures, newest date first; empty when none. */
    private static List<File> cached(Context ctx) {
        List<File> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles();
        if (files != null) for (File f : files) {
            if (f.isFile() && f.length() > 0 && f.getName().endsWith(".jpg")) out.add(f);
        }
        out.sort((a, b) -> b.getName().compareTo(a.getName())); // yyyyMMdd.jpg sorts by date
        return out;
    }

    /** The newest cached picture (Bing's current one); null when nothing is cached. */
    static File shown(Context ctx) {
        List<File> all = cached(ctx);
        return all.isEmpty() ? null : all.get(0);
    }

    /** The caption (place/title) of the shown picture, for the Settings list; "" if none. */
    static String caption(Context ctx) {
        File pic = shown(ctx);
        if (pic == null) return "";
        File txt = new File(pic.getPath().substring(0, pic.getPath().length() - 4) + ".txt");
        try {
            String s = new String(readAll(new java.io.FileInputStream(txt), 4096), StandardCharsets.UTF_8);
            int nl = s.indexOf('\n');
            return (nl > 0 ? s.substring(0, nl) : s).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * True when nothing is cached, or Bing's next picture should be out: the newest picture in the
     * last feed started at its "fullstartdate" (UTC), and the next one follows 24 h later.
     */
    static boolean due(Context ctx) {
        return cached(ctx).isEmpty() || System.currentTimeMillis() >= prefs(ctx).getLong("next_at", 0);
    }

    /** Downloads the week's scenic pictures, keeps the 7 newest. Blocking: call off the main thread. */
    static synchronized boolean refresh(Context ctx) {
        File d = dir(ctx);
        if (!d.isDirectory() && !d.mkdirs()) return false;
        boolean gotNew = false;
        try {
            String mkt = Locale.getDefault().toLanguageTag();
            JSONObject feed = new JSONObject(new String(
                    get(FEED + URLEncoder.encode(mkt, "UTF-8"), 256 * 1024), StandardCharsets.UTF_8));
            JSONArray images = feed.optJSONArray("images");
            long newestStart = 0;
            Set<String> skipped = prefs(ctx).getStringSet("skipped", new HashSet<>());
            Set<String> stillSkipped = new HashSet<>(); // pruned to what the feed still lists
            for (int i = 0; images != null && i < images.length(); i++) {
                JSONObject img = images.optJSONObject(i);
                if (img == null) continue;
                String date = img.optString("startdate", "");       // "20261023"
                String urlbase = img.optString("urlbase", "");       // "/th?id=OHR.Name_EN-US…"
                String title = img.optString("title", "").trim();
                String copyright = img.optString("copyright", "").trim();
                if (date.length() != 8 || urlbase.isEmpty()) continue;
                try {
                    long start = LocalDateTime.parse(img.optString("fullstartdate", ""), FULL)
                            .toInstant(ZoneOffset.UTC).toEpochMilli();
                    newestStart = Math.max(newestStart, start);
                } catch (Exception ignored) {
                }
                File out = new File(d, date + ".jpg");
                if (out.isFile()) continue;                          // already have this day
                if (skipped.contains(date)) {                       // rejected before: don't download again
                    stillSkipped.add(date);
                    continue;
                }
                if (PEOPLE.matcher(title + " " + copyright).find()) {
                    Log.i(TAG, "skipped, names people: " + title);
                    stillSkipped.add(date);
                    continue;
                }
                byte[] jpg = get("https://www.bing.com" + urlbase + "_1920x1080.jpg", 3 * 1024 * 1024);
                int faces = faces(jpg);
                if (faces != 0) {
                    Log.i(TAG, "skipped, " + (faces < 0 ? "unreadable" : faces + " face(s)") + ": " + title);
                    if (faces > 0) stillSkipped.add(date); // an unreadable download may be fine next time
                    continue;
                }
                write(out, jpg);
                write(new File(d, date + ".txt"),
                        (title.replace('\n', ' ') + "\n" + copyright.replace('\n', ' '))
                                .getBytes(StandardCharsets.UTF_8));
                gotNew = true;
            }
            trim(ctx);
            SharedPreferences.Editor e = prefs(ctx).edit().putStringSet("skipped", stillSkipped);
            // The next picture is due 24 h after the newest one started (unknown: try again in an hour).
            long next = newestStart > 0 ? newestStart + DAY_MS : System.currentTimeMillis() + DAY_MS / 24;
            e.putLong("next_at", next).apply();
            Log.i(TAG, "refreshed (" + (gotNew ? "new picture" : "nothing new") + "); next picture due "
                    + java.time.Instant.ofEpochMilli(next));
            return gotNew;
        } catch (Exception e) {
            Log.w(TAG, "refresh failed: " + e);
            return false;
        }
    }

    /** Keeps the 7 newest pictures (and their .txt); deletes the rest. */
    private static void trim(Context ctx) {
        List<File> all = cached(ctx);
        for (int i = KEEP; i < all.size(); i++) {
            File jpg = all.get(i);
            jpg.delete();
            new File(jpg.getPath().substring(0, jpg.getPath().length() - 4) + ".txt").delete();
        }
    }

    /** 0 = no faces, >0 = number of faces (skip), <0 = couldn't decode (skip). */
    private static int faces(byte[] jpg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565; // what FaceDetector reads
        o.inSampleSize = 2;
        Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        if (b == null) return -1;
        try {
            if (b.getWidth() % 2 != 0) { // the detector needs an even width
                Bitmap even = Bitmap.createBitmap(b, 0, 0, b.getWidth() - 1, b.getHeight());
                b.recycle();
                b = even;
            }
            FaceDetector.Face[] found = new FaceDetector.Face[4];
            return new FaceDetector(b.getWidth(), b.getHeight(), found.length).findFaces(b, found);
        } finally {
            b.recycle();
        }
    }

    private static void write(File out, byte[] data) throws Exception {
        File tmp = new File(out.getPath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(data);
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            throw new IllegalStateException("rename failed: " + out);
        }
    }

    private static byte[] get(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("Accept", "application/json, image/jpeg");
        c.setRequestProperty("User-Agent", UA);
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            return readAll(c.getInputStream(), maxBytes);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws Exception {
        try (InputStream ignored = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            for (int r; (r = in.read(buf)) > 0; ) {
                out.write(buf, 0, r);
                if (out.size() > maxBytes) throw new IllegalStateException("response too large");
            }
            return out.toByteArray();
        }
    }
}
