package my.rafhan.app.tvhome;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home card "Last watching": one wide, colour-tinted tile per streaming app you used recently,
 * newest first, each with how long ago it was used and (when known) what you were watching.
 *
 * Which apps: the names in {@link #APPS}, matched against the installed apps' labels (so it
 * works for sideloaded apps whose package name isn't known). Edit that list to add or drop apps.
 *
 * When: from usage access (the same permission that orders the dock), so it needs nothing new.
 *
 * What you were watching, first found wins:
 *  1. the TV's Watch Next row (title and season/episode), if READ_EPG_DATA is granted and the app publishes;
 *  2. the last title the app showed in its media session (remembered by {@link #record}, called from
 *     NowPlayingPanel whenever something plays);
 *  3. nothing: the tile just says "Last used".
 *
 * Same focus API as the old WatchPanel: setSize, count, cardAt, scrollTo, open, refresh.
 */
final class LastWatchPanel {
    /** Matched against the installed apps' labels with spaces and punctuation removed, lower case. */
    private static final String[] APPS = {"cineflow", "cloudstream", "idlix", "loktv", "nuvio", "rtmklik", "viu",
            "youtube", "netflix", "bilibili", "perfecttv", "gvision", "unifi"};
    /** Labels containing one of these are skipped (YouTube Music, YouTube Kids ...). */
    private static final String[] SKIP = {"music", "kids"};

    private static final int MAX_ITEMS = 10, VISIBLE = 4, GAP_DP = 8;
    private static final long MIN_GAP_MS = 60_000L, WINDOW_MS = 30L * 24 * 3600 * 1000;
    private static final int TEXT = 0xFFFFFFFF, DIM = 0xCCFFFFFF, DIM_HEADER = 0x8CFFFFFF, ACCENT = 0xFFFFC857;
    private static final int FALLBACK_COLOR = 0xFF3A4A6B;
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LastWatch-io");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, Integer> ACCENTS = new ConcurrentHashMap<>();
    /** pkg to "title\nartist", so record() only writes when the title changes. */
    private static final Map<String, String> RECORDED = new ConcurrentHashMap<>();
    private static final String PREFS = "last_watching";

    private static final class Item {
        String pkg, app, kind = "Last used", title = "", intentUri;
        long when;
        int color = FALLBACK_COLOR;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float dp;
    private final TextView countView;
    private final FrameLayout viewport;
    private final LinearLayout row;
    private final List<Item> items = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();

    private int tileW, tileH, off;
    private long lastFetch;
    private boolean fetching;
    private String status = "";
    private volatile String loadStatus = "";
    private String signature = "";

    LastWatchPanel(Context ctx, LinearLayout root) {
        this.ctx = ctx;
        dp = ctx.getResources().getDisplayMetrics().density;

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(small("LAST WATCHING", DIM_HEADER));
        countView = small("", ACCENT);
        countView.setGravity(Gravity.END);
        header.addView(countView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        viewport = new FrameLayout(ctx);
        viewport.setClipChildren(true);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(80));
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
        int w = viewport.getWidth() > 0 ? viewport.getWidth() : tileW * VISIBLE + px(GAP_DP) * (VISIBLE - 1);
        if (i * pitch < off) off = i * pitch;
        else if (i * pitch + tileW > off + w) off = i * pitch + tileW - w;
        row.animate().cancel();
        row.animate().translationX(-off).setDuration(180).start();
    }

    /** Opens the app (at the title, when the app gave a link for it). */
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
            PackageManager pm = ctx.getPackageManager();
            Intent in = pm.getLeanbackLaunchIntentForPackage(it.pkg);
            if (in == null) in = pm.getLaunchIntentForPackage(it.pkg);
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

    // ---------------------------------------------------------------- remembering what played

    /** Called when an app's media session shows a title. Remembered per app for the "what" line. */
    static void record(Context ctx, String pkg, String title, String artist) {
        if (pkg == null || TextUtils.isEmpty(title) || pkg.equals(ctx.getPackageName())) return;
        String v = title + "\n" + (artist == null ? "" : artist);
        if (v.equals(RECORDED.get(pkg))) return;
        RECORDED.put(pkg, v);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("t:" + pkg, title)
                .putString("a:" + pkg, artist == null ? "" : artist)
                .putLong("w:" + pkg, System.currentTimeMillis())
                .apply();
    }

    // ---------------------------------------------------------------- data

    /** Called every minute (clock tick) and on resume; works at most once a minute, off the main thread. */
    void refresh() {
        long now = System.currentTimeMillis();
        if (fetching || now - lastFetch < MIN_GAP_MS) return;
        fetching = true;
        lastFetch = now;
        IO.execute(() -> {
            List<Item> found = new ArrayList<>();
            String st = "";
            try {
                found = load();
            } catch (RuntimeException e) {
                android.util.Log.w("TvHome", "last watching failed", e);
                st = "Not available";
            }
            List<Item> done = found;
            String doneStatus = st.isEmpty() ? loadStatus : st;
            main.post(() -> {
                fetching = false;
                status = doneStatus;
                apply(done);
            });
        });
    }

    private List<Item> load() {
        PackageManager pm = ctx.getPackageManager();
        loadStatus = "";
        Map<String, String> apps = matchedApps(pm);
        if (apps.isEmpty()) {
            loadStatus = "None of your streaming apps were found";
            return new ArrayList<>();
        }
        Map<String, Long> used = lastUsed(apps.keySet());
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, Item> provider = watchNext(apps.keySet());

        List<Item> out = new ArrayList<>();
        for (Map.Entry<String, String> e : apps.entrySet()) {
            String pkg = e.getKey();
            Long u = used.get(pkg);
            long played = sp.getLong("w:" + pkg, 0);
            long when = Math.max(u == null ? 0 : u, played);
            if (when <= 0) continue; // never opened since the window began
            Item it = new Item();
            it.pkg = pkg;
            it.app = e.getValue();
            it.when = when;
            it.color = accentOf(pm, pkg);
            Item p = provider.get(pkg);
            String t = sp.getString("t:" + pkg, "");
            if (p != null) {
                it.kind = p.kind;
                it.title = p.title;
                it.intentUri = p.intentUri;
            } else if (!t.isEmpty()) {
                String a = sp.getString("a:" + pkg, "");
                it.title = t;
                it.kind = a.isEmpty() ? "Last played" : a;
            }
            out.add(it);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.when, a.when));
        if (out.size() > MAX_ITEMS) out = new ArrayList<>(out.subList(0, MAX_ITEMS));
        loadStatus = out.isEmpty() ? "Open one of your apps and it will show up here" : "";
        return out;
    }

    /** pkg to label, for the installed launchable apps whose label matches {@link #APPS}. */
    private Map<String, String> matchedApps(PackageManager pm) {
        Map<String, String> out = new LinkedHashMap<>();
        List<ResolveInfo> all = new ArrayList<>();
        all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        for (ResolveInfo ri : all) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || out.containsKey(pkg)) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            String norm = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            boolean skip = false;
            for (String s : SKIP) if (norm.contains(s)) skip = true;
            if (skip) continue;
            for (String key : APPS) {
                if (norm.startsWith(key)) {
                    out.put(pkg, label);
                    break;
                }
            }
        }
        return out;
    }

    /** Latest time each of these apps was in front or left the front (needs usage access). */
    private Map<String, Long> lastUsed(java.util.Set<String> pkgs) {
        Map<String, Long> out = new HashMap<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return out;
            long now = System.currentTimeMillis();
            UsageEvents ev = usm.queryEvents(now - WINDOW_MS, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev != null && ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int type = e.getEventType();
                if (type != UsageEvents.Event.MOVE_TO_FOREGROUND && type != UsageEvents.Event.MOVE_TO_BACKGROUND) continue;
                String pkg = e.getPackageName();
                if (!pkgs.contains(pkg)) continue;
                Long old = out.get(pkg);
                if (old == null || e.getTimeStamp() > old) out.put(pkg, e.getTimeStamp());
            }
        } catch (RuntimeException ignored) {
            // no usage access: tiles fall back to what media sessions recorded
        }
        return out;
    }

    /** Watch Next rows for these apps (newest per app). Empty without READ_EPG_DATA or when apps publish nothing. */
    private Map<String, Item> watchNext(java.util.Set<String> pkgs) {
        Map<String, Item> out = new HashMap<>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(WATCH_NEXT, null, null, null, "last_engagement_time_utc_millis DESC");
            if (c == null) return out;
            int iTitle = c.getColumnIndex("title"), iPkg = c.getColumnIndex("package_name"),
                    iIntent = c.getColumnIndex("intent_uri"), iType = c.getColumnIndex("type"),
                    iSeason = c.getColumnIndex("season_display_number"), iEp = c.getColumnIndex("episode_display_number");
            while (c.moveToNext()) {
                String pkg = str(c, iPkg), title = str(c, iTitle);
                if (pkg == null || title == null || !pkgs.contains(pkg) || out.containsKey(pkg)) continue;
                Item it = new Item();
                it.title = title;
                it.intentUri = str(c, iIntent);
                String s = str(c, iSeason), ep = str(c, iEp);
                int type = iType >= 0 && !c.isNull(iType) ? c.getInt(iType) : -1;
                if (s != null && ep != null) it.kind = "Continue \u00b7 S" + s + " \u00b7 E" + ep;
                else if (type == 0) it.kind = "Film";
                else if (type == 4) it.kind = "Video";
                else if (type == 7) it.kind = "Music";
                else it.kind = "Continue";
                out.put(pkg, it);
            }
        } catch (RuntimeException ignored) {
            // SecurityException (no permission) or a TV without the provider
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** The most colourful part of the app's banner or icon, used to tint its tile. */
    private int accentOf(PackageManager pm, String pkg) {
        Integer cached = ACCENTS.get(pkg);
        if (cached != null) return cached;
        int color = FALLBACK_COLOR;
        Bitmap b = null;
        try {
            Drawable d = null;
            try {
                d = pm.getApplicationBanner(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
                // fall back to the icon
            }
            if (d == null) d = pm.getApplicationIcon(pkg);
            final int w = 32, h = 18;
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, w, h);
            d.draw(cv);
            float[] hsv = new float[3];
            double r = 0, g = 0, bl = 0, sum = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 200) continue;
                    Color.colorToHSV(p, hsv);
                    if (hsv[1] < 0.35f || hsv[2] < 0.3f) continue; // skip white, grey and black
                    double wgt = hsv[1] * hsv[2];
                    wgt *= wgt;
                    r += Color.red(p) * wgt;
                    g += Color.green(p) * wgt;
                    bl += Color.blue(p) * wgt;
                    sum += wgt;
                }
            }
            if (sum > 0) color = Color.rgb((int) (r / sum), (int) (g / sum), (int) (bl / sum));
        } catch (Exception | OutOfMemoryError ignored) {
            // keep the fallback colour
        } finally {
            if (b != null) b.recycle();
        }
        ACCENTS.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- display

    private void apply(List<Item> found) {
        StringBuilder sig = new StringBuilder(status);
        long now = System.currentTimeMillis();
        for (Item it : found) {
            sig.append(it.pkg).append(it.kind).append(it.title).append(ago(now - it.when)).append('|');
        }
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
        countView.setText(items.isEmpty() ? "" : items.size() + (items.size() == 1 ? " app" : " apps"));
        if (items.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText(!status.isEmpty() ? status : "Open one of your apps and it will show up here");
            t.setTextSize(12f);
            t.setTextColor(DIM_HEADER);
            t.setTypeface(Fonts.outfit(ctx, 400));
            t.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(t, new LinearLayout.LayoutParams(tileW * VISIBLE + px(GAP_DP) * (VISIBLE - 1), tileH));
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < items.size(); i++) {
            View card = makeCard(items.get(i), now);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            if (i > 0) lp.setMarginStart(px(GAP_DP));
            row.addView(card, lp);
            cards.add(card);
        }
        off = 0;
        row.setTranslationX(0);
    }

    private View makeCard(Item it, long now) {
        FrameLayout card = new FrameLayout(ctx);
        float[] hsv = new float[3];
        Color.colorToHSV(it.color, hsv);
        float v = hsv[2];
        hsv[2] = Math.min(1f, v * 0.9f);
        int top = Color.HSVToColor(hsv);
        hsv[2] = v * 0.32f;
        int bottom = Color.HSVToColor(hsv);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
        bg.setCornerRadius(px(11));
        card.setBackground(bg);
        card.setClipToOutline(true);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(9), px(8), px(9), px(8));
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top: app name left, "12 min ago" chip right.
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView app = new TextView(ctx);
        app.setText(it.app);
        app.setTextSize(13f);
        app.setTextColor(TEXT);
        app.setTypeface(Fonts.outfit(ctx, 600));
        app.setSingleLine(true);
        app.setEllipsize(TextUtils.TruncateAt.END);
        app.setIncludeFontPadding(false);
        head.addView(app, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chip = new TextView(ctx);
        chip.setText(ago(now - it.when));
        chip.setTextSize(8.5f);
        chip.setTextColor(TEXT);
        chip.setTypeface(Fonts.outfit(ctx, 500));
        chip.setSingleLine(true);
        chip.setIncludeFontPadding(false);
        chip.setPadding(px(6), px(2), px(6), px(3));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(0xFF000000);
        cb.setCornerRadius(px(9));
        chip.setBackground(cb);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMarginStart(px(4));
        head.addView(chip, clp);
        col.addView(head);

        View spacer = new View(ctx);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        // Bottom: what it was (small), then the title.
        TextView kind = new TextView(ctx);
        kind.setText(it.kind);
        kind.setTextSize(9f);
        kind.setTextColor(DIM);
        kind.setTypeface(Fonts.outfit(ctx, 400));
        kind.setSingleLine(true);
        kind.setEllipsize(TextUtils.TruncateAt.END);
        kind.setIncludeFontPadding(false);
        col.addView(kind);
        if (!it.title.isEmpty()) {
            TextView title = new TextView(ctx);
            title.setText(it.title);
            title.setTextSize(13f);
            title.setTextColor(TEXT);
            title.setTypeface(Fonts.outfit(ctx, 600));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setIncludeFontPadding(false);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = px(1);
            col.addView(title, tlp);
        }
        return card;
    }

    // ---------------------------------------------------------------- helpers

    private static String ago(long ms) {
        long min = Math.max(0, ms / 60_000);
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        long h = min / 60;
        if (h < 24) return h + " h ago";
        long d = h / 24;
        return d == 1 ? "Yesterday" : d + " d ago";
    }

    private static String str(Cursor c, int i) {
        if (i < 0 || c.isNull(i)) return null;
        String s = c.getString(i);
        return s == null || s.trim().isEmpty() ? null : s.trim();
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
