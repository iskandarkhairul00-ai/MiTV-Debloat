package my.rafhan.app.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Recommend watching": a full-width row of wide tiles. Recommendations are for the streaming
 * apps installed on this TV: Nuvio, LokLok, Viu, CineFlow and CloudStream (found by app name,
 * so no package names to maintain).
 *
 *  1. If one of those apps publishes preview programs to the system TV provider, those come first
 *     (needs com.android.providers.tv.permission.READ_EPG_DATA; without it this step is skipped).
 *  2. The rest are this week's trending titles on TMDB, each handed to one of the installed apps:
 *     Asian-language titles prefer LokLok / Viu / CineFlow, the others Nuvio / CloudStream /
 *     CineFlow. OK on a tile opens that app (CloudStream is opened with a search for the title).
 *
 * Memory: at most MAX_ITEMS tiles, posters decoded at tile size in RGB_565 and kept in a tiny
 * LRU cache; one query every few minutes on a private single thread, never on the main thread.
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
final class WatchPanel {
    /**
     * TMDB (themoviedb.org) API key, free and read-only. It sits in the public repo, so anyone can
     * read it: if it is ever abused, create a new one in TMDB Settings > API and paste it here.
     * This product uses the TMDB API but is not endorsed or certified by TMDB.
     */
    private static final String TMDB_KEY = "252e9b8385bcbbb851d4dfff6a5c8065";
    private static final String TMDB = "https://api.themoviedb.org/3/";
    private static final String TMDB_IMG = "https://image.tmdb.org/t/p/w780";
    private static final String COUNTRY = "MY";

    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final int MAX_ITEMS = 12, GAP_DP = 6, TARGET_TILE_DP = 180;
    /** Apps recommendations are made for (matched against the installed app's name / package). */
    private static final String[] SOURCES = {"nuvio", "loklok", "viu", "cineflow", "cloudstream"};
    private static final Set<String> ASIAN = new HashSet<>(java.util.Arrays.asList(
            "ko", "zh", "cn", "ja", "th", "id", "ms", "tl", "vi", "hi", "ta"));
    private static final long MIN_GAP_MS = 20 * 60_000L; // new picks every 20 min (and on a retry after 1 min if empty)
    private static final int TEXT = 0xFFFFFFFF, DIM = 0xB3FFFFFF, DIM_HEADER = 0x8CFFFFFF, ACCENT = 0xFFFFC857;
    private static final String[] TYPES = {"Movie", "Series", "Season", "Episode", "Clip", "Event",
            "Channel", "Track", "Album", "Artist", "Playlist", "Station", "Game"};

    // One thread for the provider query and poster downloads, so a slow server never blocks the
    // launcher's shared worker.
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WatchPanel-io");
        t.setDaemon(true);
        return t;
    });
    private static final LruCache<String, Bitmap> POSTERS = new LruCache<>(12);

    private static final class Item {
        String title, meta, pkg, intentUri, art;
        boolean web;      // from the internet (TMDB), not from the app's own programs
        boolean tv, asian; // TMDB candidates: series or movie, Asian-language
        String base = "";  // meta without the app name
        String src = "";
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float dp;
    private final TextView countView;
    private final FrameLayout viewport;
    private final LinearLayout row;
    private final List<Item> items = new ArrayList<>();
    private final List<Item> fetched = new ArrayList<>(); // what the TV provider returned
    private final List<View> cards = new ArrayList<>();
    private final Map<String, String> labels = new java.util.concurrent.ConcurrentHashMap<>();

    private int tileW, tileH, visible = 2;
    private int off;
    private long lastFetch, lastWeb;
    private final List<Item> pool = new ArrayList<>(); // TMDB candidates, reshuffled on every refresh
    private long poolAt;
    private final Set<String> lastShown = new HashSet<>();
    private final List<Item> webCache = new ArrayList<>();
    private boolean fetching;
    private String status = "";
    private String signature = "";

    WatchPanel(Context ctx, LinearLayout root) {
        this.ctx = ctx;
        dp = ctx.getResources().getDisplayMetrics().density;

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(small("RECOMMEND WATCHING", DIM_HEADER));
        countView = small("", ACCENT);
        countView.setGravity(Gravity.END);
        header.addView(countView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        viewport = new FrameLayout(ctx);
        viewport.setClipChildren(true);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(80));
        vlp.topMargin = px(6);
        root.addView(viewport, vlp);

        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        viewport.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Called by MainActivity once the card's inner width and the tile height are known. */
    void setSize(int innerWidthPx, int tileHeightPx) {
        int gap = px(GAP_DP);
        int vis = Math.max(2, (innerWidthPx + gap) / (px(TARGET_TILE_DP) + gap));
        int w = (innerWidthPx - gap * (vis - 1)) / vis;
        if (w == tileW && tileHeightPx == tileH && vis == visible) return;
        visible = vis;
        tileW = w;
        tileH = tileHeightPx;
        ViewGroup.LayoutParams lp = viewport.getLayoutParams();
        lp.height = tileH;
        viewport.setLayoutParams(lp);
        rebuild();
    }

    // ---------------------------------------------------------------- focus API

    int count() {
        return cards.size();
    }

    View cardAt(int i) {
        return i >= 0 && i < cards.size() ? cards.get(i) : null;
    }

    /** Slides the row so tile i is fully inside the card. */
    void scrollTo(int i) {
        if (i < 0 || i >= cards.size() || tileW == 0) return;
        int pitch = tileW + px(GAP_DP);
        int w = viewport.getWidth() > 0 ? viewport.getWidth() : tileW * visible + px(GAP_DP);
        if (i * pitch < off) off = i * pitch;
        else if (i * pitch + tileW > off + w) off = i * pitch + tileW - w;
        row.animate().cancel();
        row.animate().translationX(-off).setDuration(180).start();
    }

    void open(int i) {
        if (i < 0 || i >= items.size()) return;
        Item it = items.get(i);
        try {
            if (it.intentUri != null && !it.intentUri.isEmpty()) {
                Intent in = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
                in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(in);
                return;
            }
        } catch (Exception ignored) {
            // fall through to just opening the app
        }
        if (it.web && it.pkg != null && it.pkg.toLowerCase(Locale.ROOT).contains("cloudstream")) {
            try { // CloudStream's search link; if this build lacks it, the plain launch below runs
                Intent s = new Intent(Intent.ACTION_VIEW, Uri.parse("cloudstreamsearch://" + Uri.encode(it.title)));
                s.setPackage(it.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(s);
                return;
            } catch (Exception ignored) {
                // fall through
            }
        }
        try {
            Intent in = ctx.getPackageManager().getLaunchIntentForPackage(it.pkg);
            if (in == null) in = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg);
            if (in != null) {
                in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(in);
                return;
            }
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // toast below
        }
        Toast.makeText(ctx, "Can't be opened", Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- data

    /** Called every minute (clock tick) and on resume; queries at most every few minutes. */
    void refresh() {
        long now = System.currentTimeMillis();
        if (fetching || now - lastFetch < MIN_GAP_MS) return;
        fetching = true;
        lastFetch = now;
        IO.execute(() -> {
            List<Item> found = new ArrayList<>();
            String st = "";
            try {
                Map<String, String> src = installedSources();
                if (src.isEmpty()) {
                    st = "Install Nuvio, LokLok, Viu, CineFlow or CloudStream to get recommendations";
                } else {
                    try {
                        found.addAll(query(new HashSet<>(src.values())));
                    } catch (Exception e) {
                        android.util.Log.w("TvHome", "preview query failed", e); // no permission: just skip
                    }
                    if (found.size() < MAX_ITEMS) {
                        try {
                            for (Item w : tmdbPicks(src)) {
                                if (found.size() >= MAX_ITEMS) break;
                                found.add(w);
                            }
                        } catch (Exception e) {
                            android.util.Log.w("TvHome", "tmdb failed", e);
                        }
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("TvHome", "recommend failed", e);
            }
            List<Item> done = found;
            String doneStatus = st;
            main.post(() -> {
                fetching = false;
                status = doneStatus;
                if (done.isEmpty()) lastFetch = System.currentTimeMillis() - MIN_GAP_MS + 60_000L; // retry in 1 min
                apply(done);
            });
        });
    }

    /** Installed apps among SOURCES: key -> package, in SOURCES order. */
    private Map<String, String> installedSources() {
        PackageManager pm = ctx.getPackageManager();
        Map<String, String> out = new LinkedHashMap<>();
        List<android.content.pm.ResolveInfo> all = new ArrayList<>();
        all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        for (String key : SOURCES) {
            for (android.content.pm.ResolveInfo ri : all) {
                String pkg = ri.activityInfo.packageName;
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                String p = pkg.toLowerCase(Locale.ROOT);
                boolean hit = label.startsWith(key) || (key.equals("viu") ? p.equals("com.vuclip.viu") : p.contains(key));
                if (hit && !pkg.equals(ctx.getPackageName())) {
                    out.put(key, pkg);
                    break;
                }
            }
        }
        return out;
    }

    /** Preview programs that one of our apps publishes to the system TV provider (often none). */
    private List<Item> query(Set<String> pkgs) {
        List<Item> all = new ArrayList<>();
        if (ctx.checkSelfPermission("com.android.providers.tv.permission.READ_EPG_DATA")
                != PackageManager.PERMISSION_GRANTED) return all;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(PREVIEW, null, null, null, null);
            if (c == null) return all;
            int iTitle = c.getColumnIndex("title"), iPkg = c.getColumnIndex("package_name"),
                    iIntent = c.getColumnIndex("intent_uri"), iPoster = c.getColumnIndex("poster_art_uri"),
                    iThumb = c.getColumnIndex("thumbnail_uri"), iType = c.getColumnIndex("type"),
                    iGenre = c.getColumnIndex("canonical_genre"), iDur = c.getColumnIndex("duration_millis");
            while (c.moveToNext() && all.size() < 80) {
                String pkg = str(c, iPkg), title = str(c, iTitle);
                if (pkg == null || title == null || !pkgs.contains(pkg)) continue;
                Item it = new Item();
                it.pkg = pkg;
                it.title = title;
                it.intentUri = str(c, iIntent);
                String thumb = str(c, iThumb);
                it.art = thumb != null ? thumb : str(c, iPoster);
                List<String> parts = new ArrayList<>();
                int type = iType >= 0 && !c.isNull(iType) ? c.getInt(iType) : -1;
                if (type >= 0 && type < TYPES.length) parts.add(TYPES[type]);
                String genre = str(c, iGenre);
                if (genre != null) parts.add(genre.split(",")[0].trim());
                long dur = lng(c, iDur);
                if (dur > 0) parts.add(length(dur));
                parts.add(appLabel(pkg));
                it.meta = join(parts);
                all.add(it);
            }
        } finally {
            if (c != null) c.close();
        }
        return onePerApp(all);
    }

    // ---------------------------------------------------------------- TMDB

    /** TMDB genre ids (movie and TV) to names; these ids don't change. */
    private static String genreName(int id) {
        switch (id) {
            case 28: return "Action";
            case 12: return "Adventure";
            case 16: return "Animation";
            case 35: return "Comedy";
            case 80: return "Crime";
            case 99: return "Documentary";
            case 18: return "Drama";
            case 10751: return "Family";
            case 14: return "Fantasy";
            case 36: return "History";
            case 27: return "Horror";
            case 10402: return "Music";
            case 9648: return "Mystery";
            case 10749: return "Romance";
            case 878: return "Sci-Fi";
            case 53: return "Thriller";
            case 10752: return "War";
            case 37: return "Western";
            case 10759: return "Action & Adventure";
            case 10762: return "Kids";
            case 10764: return "Reality";
            case 10765: return "Sci-Fi & Fantasy";
            case 10766: return "Soap";
            case 10768: return "War & Politics";
            default: return null;
        }
    }

    /** null when the JSON value is missing, null or empty. */
    private static String text(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String v = o.optString(key, "");
        return v.trim().isEmpty() ? null : v.trim();
    }

    /** Random page 1..max, so each fetch brings different titles. */
    private static int page(int max) {
        return 1 + new java.util.Random().nextInt(max);
    }

    /** Adds the titles of one TMDB list to the pool (no duplicates). */
    private void collect(String path, String defType, List<Item> into, Set<String> seen) {
        try {
            JSONObject root = new JSONObject(httpText(TMDB + path + (path.contains("?") ? "&" : "?")
                    + "api_key=" + TMDB_KEY + "&language=en-US"));
            JSONArray res = root.optJSONArray("results");
            for (int i = 0; res != null && i < res.length(); i++) {
                JSONObject o = res.optJSONObject(i);
                if (o == null) continue;
                String mt = o.optString("media_type", defType);
                boolean tv = mt.equals("tv");
                if (!tv && !mt.equals("movie")) continue;
                String title = text(o, tv ? "name" : "title");
                String art = text(o, "backdrop_path");
                if (art == null) art = text(o, "poster_path");
                if (title == null || art == null || !seen.add(mt + o.optInt("id"))) continue;
                List<String> parts = new ArrayList<>();
                parts.add(tv ? "Series" : "Movie");
                JSONArray ids = o.optJSONArray("genre_ids");
                String genre = ids != null && ids.length() > 0 ? genreName(ids.optInt(0)) : null;
                if (genre != null) parts.add(genre);
                String date = text(o, tv ? "first_air_date" : "release_date");
                if (date != null && date.length() >= 4) parts.add(date.substring(0, 4));
                Item it = new Item();
                it.title = title;
                it.base = join(parts);
                it.art = TMDB_IMG + art;
                it.web = true;
                it.src = "TMDB";
                it.tv = tv;
                it.asian = ASIAN.contains(o.optString("original_language", ""));
                into.add(it);
            }
        } catch (Exception e) {
            android.util.Log.w("TvHome", "tmdb " + path + " failed", e);
        }
    }

    /** A big, mixed pool (trending, popular, top rated; movies and series; random pages); kept 3 hours. */
    private void fillPool() {
        long now = System.currentTimeMillis();
        synchronized (pool) {
            if (!pool.isEmpty() && now - poolAt < 3 * 3600_000L) return;
        }
        List<Item> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collect("trending/all/week?page=" + page(3), "movie", fresh, seen);
        collect("trending/all/day", "movie", fresh, seen);
        collect("movie/popular?page=" + page(8), "movie", fresh, seen);
        collect("tv/popular?page=" + page(8), "tv", fresh, seen);
        collect("movie/top_rated?page=" + page(8), "movie", fresh, seen);
        collect("tv/top_rated?page=" + page(8), "tv", fresh, seen);
        collect("discover/tv?sort_by=popularity.desc&with_original_language=ko&page=" + page(4), "tv", fresh, seen);
        collect("discover/tv?sort_by=popularity.desc&with_original_language=zh&page=" + page(3), "tv", fresh, seen);
        if (fresh.isEmpty()) return;
        synchronized (pool) {
            pool.clear();
            pool.addAll(fresh);
            poolAt = now;
        }
    }

    /**
     * A new random selection from the pool on every call, avoiding what was shown last time, and
     * a mix of movies and series. Each title is given to one of the installed apps (round-robin
     * inside the group that suits it).
     */
    private List<Item> tmdbPicks(Map<String, String> src) throws Exception {
        List<Item> out = new ArrayList<>();
        if (TMDB_KEY.isEmpty()) return out;
        List<String> asianPool = new ArrayList<>(), generalPool = new ArrayList<>();
        for (String k : new String[] {"loklok", "viu", "cineflow"}) if (src.containsKey(k)) asianPool.add(src.get(k));
        for (String k : new String[] {"nuvio", "cloudstream", "cineflow"}) if (src.containsKey(k)) generalPool.add(src.get(k));
        if (asianPool.isEmpty()) asianPool = generalPool;
        if (generalPool.isEmpty()) generalPool = asianPool;
        if (asianPool.isEmpty()) return out;

        fillPool();
        List<Item> cand;
        synchronized (pool) {
            cand = new ArrayList<>(pool);
        }
        java.util.Collections.shuffle(cand);
        List<Item> fresh = new ArrayList<>();
        for (Item c : cand) if (!lastShown.contains(c.title)) fresh.add(c);
        if (fresh.size() >= MAX_ITEMS) cand = fresh; // not enough new ones: repeats are allowed

        int a = 0, g = 0, movies = 0, series = 0;
        List<Item> rest = new ArrayList<>();
        for (Item c : cand) { // first pass keeps movies and series about even
            if (out.size() >= MAX_ITEMS) break;
            if (c.tv ? series > movies + 1 : movies > series + 1) { rest.add(c); continue; }
            if (c.tv) series++; else movies++;
            out.add(c);
        }
        for (Item c : rest) if (out.size() < MAX_ITEMS) out.add(c);

        List<Item> done = new ArrayList<>();
        for (Item c : out) {
            Item it = new Item();
            it.title = c.title;
            it.art = c.art;
            it.web = true;
            it.src = "TMDB";
            it.pkg = c.asian ? asianPool.get(a++ % asianPool.size()) : generalPool.get(g++ % generalPool.size());
            it.meta = c.base + " · " + appLabel(it.pkg);
            done.add(it);
        }
        lastShown.clear();
        for (Item it : done) lastShown.add(it.title);
        return done;
    }

    private static String httpText(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", "MiTV-Launcher/1.0");
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0 && out.size() < (3 << 20); ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** First pass: one tile per app, in provider order. Second pass: top up with each app's next. */
    private static List<Item> onePerApp(List<Item> all) {
        Map<String, List<Item>> byPkg = new LinkedHashMap<>();
        for (Item it : all) {
            List<Item> l = byPkg.get(it.pkg);
            if (l == null) byPkg.put(it.pkg, l = new ArrayList<>());
            l.add(it);
        }
        List<Item> out = new ArrayList<>();
        for (int round = 0; out.size() < MAX_ITEMS && round < 3; round++) {
            boolean added = false;
            for (List<Item> l : byPkg.values()) {
                if (round < l.size() && out.size() < MAX_ITEMS) {
                    out.add(l.get(round));
                    added = true;
                }
            }
            if (!added) break;
        }
        return out;
    }

    private String appLabel(String pkg) {
        String l = labels.get(pkg);
        if (l != null) return l;
        try {
            PackageManager pm = ctx.getPackageManager();
            l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            l = pkg;
        }
        labels.put(pkg, l);
        return l;
    }

    // ---------------------------------------------------------------- display

    private void apply(List<Item> found) {
        fetched.clear();
        fetched.addAll(found);
        show();
    }

    private void show() {
        List<Item> list = new ArrayList<>(fetched);
        StringBuilder sig = new StringBuilder(status);
        for (Item it : list) sig.append(it.pkg).append(it.title).append('|');
        if (sig.toString().equals(signature)) return;
        signature = sig.toString();
        items.clear();
        items.addAll(list);
        rebuild();
    }

    private void rebuild() {
        row.removeAllViews();
        cards.clear();
        if (tileW == 0) return;
        // Explicit width: the row is wider than the card, the viewport clips it.
        int n = Math.max(items.size(), visible);
        row.setLayoutParams(new FrameLayout.LayoutParams(
                n * tileW + (n - 1) * px(GAP_DP), ViewGroup.LayoutParams.MATCH_PARENT));
        countView.setText(items.isEmpty() ? "" : items.get(0).web ? "via " + items.get(0).src
                : items.size() + " items");
        if (items.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText(!status.isEmpty() ? status : "No recommendations yet (needs internet)");
            t.setTextSize(11f);
            t.setTextColor(DIM_HEADER);
            t.setTypeface(Fonts.outfit(ctx, 400));
            t.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(t, new LinearLayout.LayoutParams(tileW * visible + px(GAP_DP) * (visible - 1), tileH));
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            View card = makeCard(items.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            if (i > 0) lp.setMarginStart(px(GAP_DP));
            row.addView(card, lp);
            cards.add(card);
        }
        off = 0;
        row.setTranslationX(0);
    }

    private View makeCard(Item it) {
        FrameLayout card = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF232B32);
        bg.setCornerRadius(px(9));
        card.setBackground(bg);
        card.setClipToOutline(true);

        ImageView poster = new ImageView(ctx);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(poster, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadPoster(poster, it.art);

        View scrim = new View(ctx);
        GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0xE6000000});
        scrim.setBackground(sg);
        card.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(tileH * 0.62f), Gravity.BOTTOM));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(7), 0, px(7), px(6));
        TextView meta = new TextView(ctx);
        meta.setText(it.meta);
        meta.setTextSize(9f);
        meta.setTextColor(DIM);
        meta.setTypeface(Fonts.outfit(ctx, 400));
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setIncludeFontPadding(false);
        col.addView(meta);
        TextView title = new TextView(ctx);
        title.setText(it.title);
        title.setTextSize(13f);
        title.setTextColor(TEXT);
        title.setTypeface(Fonts.outfit(ctx, 500));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = px(1);
        col.addView(title, tlp);
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        return card;
    }

    // ---------------------------------------------------------------- posters

    private void loadPoster(ImageView view, String uri) {
        if (uri == null) return;
        Bitmap hit = POSTERS.get(uri);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setTag(uri);
        int target = Math.max(tileW, 160) * 2;
        IO.execute(() -> {
            Bitmap b = fetch(uri, target);
            if (b == null) return;
            POSTERS.put(uri, b);
            main.post(() -> {
                if (uri.equals(view.getTag())) view.setImageBitmap(b);
            });
        });
    }

    private Bitmap fetch(String uri, int targetW) {
        HttpURLConnection c = null;
        try {
            InputStream in;
            if (uri.startsWith("http")) {
                c = (HttpURLConnection) new URL(uri).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                if (c.getResponseCode() != 200) return null;
                in = c.getInputStream();
            } else {
                in = ctx.getContentResolver().openInputStream(Uri.parse(uri));
                if (in == null) return null;
            }
            byte[] data;
            try (InputStream s = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = s.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 4_000_000) return null;
                }
                data = out.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String str(Cursor c, int i) {
        if (i < 0 || c.isNull(i)) return null;
        String s = c.getString(i);
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static long lng(Cursor c, int i) {
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    private static String length(long ms) {
        long m = ms / 60_000;
        return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private TextView small(String text, int color) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(10.5f);
        t.setTypeface(Fonts.outfit(ctx, 500));
        t.setTextColor(color);
        t.setLetterSpacing(0.08f);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }
}
