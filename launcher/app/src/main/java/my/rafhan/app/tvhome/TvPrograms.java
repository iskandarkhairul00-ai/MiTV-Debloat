package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What apps publish to the TV's home screen: Watch Next (continue watching) and the
 * recommended programs of their channels, one entry per app. Read from the TV provider; when the
 * provider is empty or refuses (no READ_EPG_DATA), the lists are simply empty. Call off the main thread.
 */
final class TvPrograms {
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");

    private TvPrograms() {}

    static List<TileCard.Item> watchNext(Context c) {
        return query(c, WATCH_NEXT, true);
    }

    static List<TileCard.Item> recommended(Context c) {
        return query(c, PREVIEW, false);
    }

    private static final String[] BASE_WN = {"title", "poster_art_uri", "thumbnail_uri", "package_name", "intent_uri",
            "last_engagement_time_utc_millis"};
    private static final String[] BASE_PV = {"title", "poster_art_uri", "thumbnail_uri", "package_name", "intent_uri",
            "browsable"};
    private static final String[] EXTRA = {"type", "genre", "release_date", "duration_millis", "content_rating"};

    private static List<TileCard.Item> query(Context c, Uri uri, boolean watchNext) {
        String[] base = watchNext ? BASE_WN : BASE_PV;
        String[] full = new String[base.length + EXTRA.length];
        System.arraycopy(base, 0, full, 0, base.length);
        System.arraycopy(EXTRA, 0, full, base.length, EXTRA.length);
        List<Row> rows;
        try {
            rows = read(c, uri, full, watchNext);
        } catch (SecurityException e) {
            return new ArrayList<>(); // access refused
        } catch (RuntimeException e) {
            try {
                rows = read(c, uri, base, watchNext); // this TV lacks a detail column: no details
            } catch (RuntimeException e2) {
                return new ArrayList<>();
            }
        }
        if (watchNext) Collections.sort(rows, (a, b) -> Long.compare(b.rank, a.rank));

        PackageManager pm = c.getPackageManager();
        Set<String> seen = new HashSet<>();
        List<TileCard.Item> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.pkg == null || !seen.add(r.pkg)) continue; // one per app
            TileCard.Item it = new TileCard.Item();
            it.key = "tv:" + r.pkg; // not an app package: setBitmap(pkg) must not touch it
            it.label = r.title;
            String app;
            try {
                app = pm.getApplicationLabel(pm.getApplicationInfo(r.pkg, 0)).toString();
            } catch (Exception e) {
                app = "";
            }
            it.note = app;
            it.detail = detail(r, app);
            it.intent = intentFor(pm, r);
            if (it.intent == null) continue;
            it.bitmap = loadBitmap(c, r.art);
            out.add(it);
            if (out.size() >= TileCard.MAX) break;
        }
        return out;
    }

    private static List<Row> read(Context c, Uri uri, String[] cols, boolean watchNext) {
        List<Row> rows = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(uri, cols, null, null, null)) {
            if (cur == null) return rows;
            boolean extra = cols.length > 6;
            while (cur.moveToNext()) {
                Row r = new Row();
                r.title = cur.getString(0);
                if (r.title == null || r.title.isEmpty()) continue;
                r.art = cur.getString(1) != null ? cur.getString(1) : cur.getString(2); // poster first
                r.pkg = cur.getString(3);
                r.intentUri = cur.getString(4);
                r.rank = cur.getLong(5);
                if (!watchNext && r.rank == 0) continue; // not browsable
                if (extra) {
                    r.type = cur.isNull(6) ? -1 : cur.getInt(6);
                    r.genre = cur.getString(7);
                    r.release = cur.getString(8);
                    r.durationMs = cur.getLong(9);
                    r.rating = cur.getString(10);
                }
                rows.add(r);
            }
        }
        return rows;
    }

    private static final class Row {
        String title, art, pkg, intentUri, genre, release, rating;
        long rank, durationMs;
        int type = -1;
    }

    private static final String[] TYPES = {"Movie", "Series", "Season", "Episode", "Clip", "Live event", "Channel",
            "Track", "Album", "Artist", "Playlist", "Station", "Game"};

    /** "Series · Mystery · 2024 · 1h 45m · TV-MA · Prime Video", skipping what the app didn't provide. */
    private static String detail(Row r, String app) {
        List<String> parts = new ArrayList<>();
        if (r.type >= 0 && r.type < TYPES.length) parts.add(TYPES[r.type]);
        if (r.genre != null && !r.genre.isEmpty()) {
            String[] g = r.genre.split(",");
            for (int i = 0; i < g.length && i < 2; i++) parts.add(pretty(g[i]));
        }
        if (r.release != null && r.release.length() >= 4) parts.add(r.release.substring(0, 4));
        if (r.durationMs > 0) {
            long m = r.durationMs / 60000;
            parts.add(m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m");
        }
        if (r.rating != null && !r.rating.isEmpty()) parts.add(r.rating);
        if (!app.isEmpty()) parts.add(app);
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(" \u00b7 ");
            sb.append(s);
        }
        return sb.toString();
    }

    private static String pretty(String genre) {
        String g = genre.trim().replace('_', ' ').toLowerCase();
        return g.isEmpty() ? g : Character.toUpperCase(g.charAt(0)) + g.substring(1);
    }

    private static Intent intentFor(PackageManager pm, Row r) {
        try {
            if (r.intentUri != null) {
                Intent i = Intent.parseUri(r.intentUri, Intent.URI_INTENT_SCHEME);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return i;
            }
        } catch (Exception ignored) {
            // fall through to the app's launcher intent
        }
        return pm.getLaunchIntentForPackage(r.pkg);
    }

    private static Bitmap loadBitmap(Context c, String art) {
        if (art == null) return null;
        try {
            Uri uri = Uri.parse(art);
            float dp = c.getResources().getDisplayMetrics().density;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = open(c, uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int target = Math.round(240 * dp), s = 1;
            while (o.outWidth / (s * 2) >= target) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            try (InputStream in = open(c, uri)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static InputStream open(Context c, Uri uri) throws Exception {
        String scheme = uri.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            HttpURLConnection con = (HttpURLConnection) new URL(uri.toString()).openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            return con.getInputStream();
        }
        return c.getContentResolver().openInputStream(uri);
    }
}
