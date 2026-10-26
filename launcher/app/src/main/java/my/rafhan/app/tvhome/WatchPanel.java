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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home card with a row of wide tiles, one per app: "Recommend watching" (what each app offers
 * on its Android TV home rows: preview programs) or "Continue watching" (Watch Next programs,
 * with a progress bar). Both come from the system TV provider, the same data the stock Android
 * TV launcher shows.
 *
 * Reading other apps' programs needs com.android.providers.tv.permission.READ_EPG_DATA. Declare
 * it in AndroidManifest.xml and grant it once over ADB:
 *   adb shell pm grant my.rafhan.app com.android.providers.tv.permission.READ_EPG_DATA
 * Without it the card shows a short hint instead of tiles. Which apps publish rows is up to the
 * apps themselves (YouTube, Netflix and Disney+ usually do).
 *
 * Memory: at most MAX_ITEMS tiles, posters decoded at tile size in RGB_565 and kept in a tiny
 * LRU cache; one query every few minutes on a private single thread, never on the main thread.
 */
final class WatchPanel {
    static final int RECOMMEND = 0, CONTINUE = 1;

    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final int MAX_ITEMS = 10, VISIBLE = 2, GAP_DP = 6;
    private static final long MIN_GAP_MS = 3 * 60_000L;
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
        float progress = -1f;
    }

    private final Context ctx;
    private final int mode;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float dp;
    private final TextView countView;
    private final FrameLayout viewport;
    private final LinearLayout row;
    private final List<Item> items = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private final Map<String, String> labels = new java.util.concurrent.ConcurrentHashMap<>();

    private int tileW, tileH;
    private int off;
    private long lastFetch;
    private boolean fetching;
    private String status = "";
    private String signature = "";

    WatchPanel(Context ctx, LinearLayout root, int mode) {
        this.ctx = ctx;
        this.mode = mode;
        dp = ctx.getResources().getDisplayMetrics().density;

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(small(mode == RECOMMEND ? "RECOMMEND WATCHING" : "CONTINUE WATCHING", DIM_HEADER));
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
        int w = (innerWidthPx - px(GAP_DP) * (VISIBLE - 1)) / VISIBLE;
        if (w == tileW && tileHeightPx == tileH) return;
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
        int w = viewport.getWidth() > 0 ? viewport.getWidth() : tileW * VISIBLE + px(GAP_DP);
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
                found = query();
            } catch (SecurityException e) {
                st = "Needs permission READ_EPG_DATA";
            } catch (Exception e) {
                android.util.Log.w("TvHome", "watch query failed", e);
                st = "Not available on this TV";
            }
            List<Item> done = found;
            String doneStatus = st;
            main.post(() -> {
                fetching = false;
                status = doneStatus;
                apply(done);
            });
        });
    }

    private List<Item> query() {
        List<Item> all = new ArrayList<>();
        Uri uri = mode == CONTINUE ? WATCH_NEXT : PREVIEW;
        Cursor c = null;
        try {
            try {
                c = ctx.getContentResolver().query(uri, null, null, null,
                        mode == CONTINUE ? "last_engagement_time_utc_millis DESC" : null);
            } catch (IllegalArgumentException badSort) {
                c = ctx.getContentResolver().query(uri, null, null, null, null);
            }
            if (c == null) return all;
            int iTitle = c.getColumnIndex("title"), iPkg = c.getColumnIndex("package_name"),
                    iIntent = c.getColumnIndex("intent_uri"), iPoster = c.getColumnIndex("poster_art_uri"),
                    iThumb = c.getColumnIndex("thumbnail_uri"), iType = c.getColumnIndex("type"),
                    iGenre = c.getColumnIndex("canonical_genre"), iDur = c.getColumnIndex("duration_millis"),
                    iPos = c.getColumnIndex("last_playback_position_millis"),
                    iSeason = c.getColumnIndex("season_display_number"),
                    iEp = c.getColumnIndex("episode_display_number"),
                    iWn = c.getColumnIndex("watch_next_type");
            boolean anyContinueType = false;
            List<Item> typed = new ArrayList<>();
            List<Boolean> isContinue = new ArrayList<>();
            while (c.moveToNext() && all.size() + typed.size() < 80) {
                String pkg = str(c, iPkg), title = str(c, iTitle);
                if (pkg == null || title == null || pkg.equals(ctx.getPackageName())) continue;
                Item it = new Item();
                it.pkg = pkg;
                it.title = title;
                it.intentUri = str(c, iIntent);
                String thumb = str(c, iThumb);
                it.art = thumb != null ? thumb : str(c, iPoster);
                long dur = lng(c, iDur), pos = lng(c, iPos);
                String app = appLabel(pkg);
                if (mode == CONTINUE) {
                    if (dur > 0 && pos > 0) {
                        it.progress = Math.min(1f, (float) pos / dur);
                        it.meta = app + " · " + left(dur - pos);
                    } else {
                        it.meta = app;
                    }
                    String s = str(c, iSeason), e = str(c, iEp);
                    if (s != null && e != null) it.title = title + " · S" + s + " E" + e;
                    boolean cont = iWn < 0 || c.isNull(iWn) || c.getInt(iWn) == 0;
                    anyContinueType |= cont;
                    isContinue.add(cont);
                    typed.add(it);
                } else {
                    List<String> parts = new ArrayList<>();
                    int type = iType >= 0 && !c.isNull(iType) ? c.getInt(iType) : -1;
                    if (type >= 0 && type < TYPES.length) parts.add(TYPES[type]);
                    String genre = str(c, iGenre);
                    if (genre != null) parts.add(genre.split(",")[0].trim());
                    if (dur > 0) parts.add(length(dur));
                    parts.add(app);
                    it.meta = join(parts);
                    all.add(it);
                }
            }
            if (mode == CONTINUE) {
                for (int i = 0; i < typed.size(); i++) {
                    if (!anyContinueType || isContinue.get(i)) all.add(typed.get(i));
                }
            }
        } finally {
            if (c != null) c.close();
        }
        return onePerApp(all);
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
        StringBuilder sig = new StringBuilder(status);
        for (Item it : found) sig.append(it.pkg).append(it.title).append(it.progress).append('|');
        if (sig.toString().equals(signature)) return;
        signature = sig.toString();
        items.clear();
        items.addAll(found);
        rebuild();
    }

    private void rebuild() {
        row.removeAllViews();
        cards.clear();
        if (tileW == 0) return;
        // Explicit width: the row is wider than the card, the viewport clips it.
        int n = Math.max(items.size(), VISIBLE);
        row.setLayoutParams(new FrameLayout.LayoutParams(
                n * tileW + (n - 1) * px(GAP_DP), ViewGroup.LayoutParams.MATCH_PARENT));
        countView.setText(items.isEmpty() ? "" : items.size() + (items.size() == 1 ? " app" : " items"));
        if (items.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText(!status.isEmpty() ? status : mode == RECOMMEND ? "Nothing recommended yet" : "Nothing to continue");
            t.setTextSize(12f);
            t.setTextColor(DIM_HEADER);
            t.setTypeface(Fonts.outfit(ctx, 400));
            t.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(t, new LinearLayout.LayoutParams(tileW * VISIBLE + px(GAP_DP), tileH));
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
        col.setPadding(px(7), 0, px(7), px(it.progress >= 0 ? 9 : 6));
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

        if (it.progress >= 0) {
            FrameLayout track = new FrameLayout(ctx);
            track.setBackgroundColor(0x55FFFFFF);
            View fill = new View(ctx);
            fill.setBackgroundColor(0xFFE24B4A);
            int w = Math.max(px(3), Math.round(tileW * it.progress));
            track.addView(fill, new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
            card.addView(track, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, px(3), Gravity.BOTTOM));
        }
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

    private static String left(long ms) {
        return length(Math.max(60_000, ms)) + " left";
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
