package my.rafhan.app.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen. Focus is a small state machine (section + index) driven from
 * dispatchKeyEvent rather than Android's focus search, so every D-pad press has exactly one
 * outcome: Up/Down move one row, Up from the dock goes to the top bar, row and bar ends stop.
 */
public class MainActivity extends Activity {
    private static final int TOP = 0, DOCK = 1, GRID = 2, MENU = 3, NOW = 4;
    private static final float NOW_SCALE = 1.12f;
    private static final int COLS = 5, DOCK_SIZE = 5;
    private static final float TILE_SCALE = 1.1f, PILL_SCALE = 1.06f;
    // dp
    private static final int TILE_W = 152, TILE_H = 86, COL_W = 172, ROW_PITCH = 132;
    // The grid runs to the top of the screen (GRID_TOP 0) with its first row 70dp down, so the
    // row above scrolls up under the top edge (shaded by gridTopFade) instead of vanishing at a
    // hard edge 56dp down and leaving a blank band.
    private static final int GRID_TOP = 0, GRID_PAD_TOP = 70, GRID_PAD_BOTTOM = 48;
    // PEEK: how much of the grid's first row shows under the dock on the home screen.
    // Focused dock app's name above the dock: line height (layout: 54dp) and gap to the dock.
    private static final int HERO_H = 54, HERO_GAP = 6;
    private static final float PEEK_ALPHA = 0.55f;
    private static final int PEEK = 15, BAND_GAP = 18, BAND_PAD_V = 15, TILE_RADIUS = 11;
    private static final Object PAYLOAD_TILE = new Object();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, AppEntry> rendered = new HashMap<>();
    private final List<ImageView> dockTiles = new ArrayList<>();
    private final int[] loc = new int[2];

    private float dp;
    private int screenW, screenH;
    private SharedPreferences prefs;

    private FrameLayout page;
    private ImageView wallpaper;
    private GlassHighlightView highlight;
    private TextView clock, date, hijri, hero;
    private View glow;
    private View[] homeViews;
    private SystemPanel system;
    private NowPlayingPanel nowPlaying;
    private PrayerPanel prayer;
    private UsagePanel usage;
    private Map<String, Long> usageScores = new HashMap<>();
    private final GradientDrawable glowDrawable = new GradientDrawable();
    private int glowColor = Color.TRANSPARENT;
    private ValueAnimator glowAnim;
    private TextView[] pills;
    private LinearLayout dockRow;
    private RecyclerView grid;
    private final GridAdapter adapter = new GridAdapter();
    private LinearLayout menu;
    private List<TvInputs.Item> inputs = new ArrayList<>();

    private List<AppEntry> dock = new ArrayList<>();
    private List<AppEntry> apps = new ArrayList<>();

    private int section = DOCK, topIdx, dockIdx, gridIdx, menuIdx, nowIdx;
    // Where the grid's scroll is heading (scrollGridToRow / return home); the highlight is placed
    // against this, so it goes straight to its final spot while the list scrolls under it.
    private int gridScrollTarget;
    private int armedKey = KeyEvent.KEYCODE_UNKNOWN;
    private boolean resumed;
    // Cards that refresh (System, Now Playing) run only while home is resumed AND has window
    // focus: the screensaver (a daydream window) covers home without pausing it.
    private boolean live;
    private boolean appsLoaded;
    private boolean tilesDropped;
    private long wallpaperStamp = -1;
    private ConnectivityManager.NetworkCallback netCallback;

    private final Runnable reloadApps = this::loadApps;

    // Phone remote D-pad keys, attached only while the home screen is on the TV.
    private final RemoteBridge.Launcher remoteKeys = k -> main.post(() -> {
        switch (k) {
            case "up": onUp(); break;
            case "down": onDown(); break;
            case "left": onSide(-1); break;
            case "right": onSide(1); break;
            case "ok": onSelect(); break;
            case "back": onBack(); break;
            default: break;
        }
    });
    // Saved shortly after every move: on a 1 GB TV the launcher can be killed without onPause.
    private final Runnable saveFocus = this::saveFocus;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            main.removeCallbacks(reloadApps);
            main.postDelayed(reloadApps, 600);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        dp = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        prefs = getSharedPreferences("home", MODE_PRIVATE);

        page = findViewById(R.id.page);
        wallpaper = findViewById(R.id.wallpaper);
        highlight = findViewById(R.id.highlight);
        hijri = findViewById(R.id.hijri);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        hero = findViewById(R.id.hero);
        glow = findViewById(R.id.glow);
        dockRow = findViewById(R.id.dockRow);
        grid = findViewById(R.id.grid);
        menu = findViewById(R.id.menu);
        pills = new TextView[] {findViewById(R.id.pillInputs), findViewById(R.id.pillWifi),
                findViewById(R.id.pillSettings)};
        homeViews = new View[] {findViewById(R.id.clockBlock), findViewById(R.id.pills), findViewById(R.id.dockBand),
                findViewById(R.id.sysPanel), findViewById(R.id.prayerPanel), findViewById(R.id.usagePanel),
                findViewById(R.id.peekFade), findViewById(R.id.nowPlaying)};
        system = new SystemPanel(this, findViewById(R.id.sysPanel), worker);
        nowPlaying = new NowPlayingPanel(this, findViewById(R.id.nowPlaying), worker, shown -> {
            // Playback ended while its buttons had focus: back to the dock.
            if (!shown && section == NOW && !dock.isEmpty()) moveFocus(DOCK, dockIdx);
        });
        prayer = new PrayerPanel(this, findViewById(R.id.prayerPanel), hijri, worker);
        usage = new UsagePanel(this, findViewById(R.id.usagePanel));
        alignSystemPanel();
        centreDatesOnClock();

        hijri.setTypeface(Fonts.outfit(this, 400));
        clock.setTypeface(Fonts.outfit(this, 200));
        date.setTypeface(Fonts.outfit(this, 400));
        hero.setTypeface(Fonts.outfit(this, 300));
        int[] icons = {R.drawable.ic_input, R.drawable.ic_wifi, R.drawable.ic_settings};
        for (int i = 0; i < pills.length; i++) {
            pills[i].setTypeface(Fonts.outfit(this, 500));
            Drawable icon = getDrawable(icons[i]);
            pills[i].setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
        }

        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(px(410));
        glowDrawable.setColors(new int[] {Color.TRANSPARENT, Color.TRANSPARENT});
        glow.setBackground(glowDrawable);

        layoutPage();

        grid.setLayoutManager(new GridLayoutManager(this, COLS));
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setAdapter(adapter);

        // The highlight re-reads its target whenever anything is about to draw, so it follows
        // page slides, grid scrolls and tile scale animations without any polling.
        findViewById(R.id.root).getViewTreeObserver().addOnPreDrawListener(() -> {
            updateHighlight(false);
            return true;
        });

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        ContextCompat.registerReceiver(this, packageReceiver, pf, ContextCompat.RECEIVER_EXPORTED);

        loadApps(); // restores focus when the list arrives
        startForegroundService(new Intent(this, RemoteService.class));
    }

    // ---------------------------------------------------------------- layout

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private void place(View v, int top, int height) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.topMargin = top;
        if (height != 0) lp.height = height;
        v.setLayoutParams(lp);
    }

    /** Grid's top edge on the home screen: its first row peeks PEEK dp above the screen bottom. */
    private int gridTop() {
        return screenH - px(PEEK + GRID_PAD_TOP);
    }

    /** How far the page slides up so the grid starts at GRID_TOP. */
    private int gridShift() {
        return gridTop() - px(GRID_TOP);
    }

    private void layoutPage() {
        place(page, 0, gridTop() + screenH - px(GRID_TOP));
        int bandH = px(TILE_H + 2 * BAND_PAD_V);
        int bandTop = screenH - px(PEEK + BAND_GAP) - bandH;
        place(findViewById(R.id.dockBand), bandTop, 0);
        int heroTop = bandTop - px(HERO_GAP + HERO_H);
        place(hero, heroTop, 0);
        place(glow, heroTop + px(HERO_H / 2) - px(410), 0);
        place(grid, gridTop(), screenH - px(GRID_TOP));
        // Shadow from just under the dock band to the screen edge, over the peek.
        View fade = findViewById(R.id.peekFade);
        ViewGroup.LayoutParams flp = fade.getLayoutParams();
        flp.height = px(PEEK + BAND_GAP - 4);
        fade.setLayoutParams(flp);
        int side = (screenW - px(COLS * COL_W)) / 2;
        grid.setPadding(side, px(GRID_PAD_TOP), side, px(GRID_PAD_BOTTOM));
    }

    /**
     * Puts the middle of the two date lines on the middle of the clock's digits. The digits sit
     * between the baseline and cap height, not in the middle of the TextView (which also holds
     * the descent), so the offset is measured from the glyphs.
     */
    private void centreDatesOnClock() {
        View col = findViewById(R.id.dateCol);
        android.graphics.Rect digits = new android.graphics.Rect();
        col.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            clock.getPaint().getTextBounds("0", 0, 1, digits);
            float digitsMid = clock.getTop() + clock.getBaseline() + digits.exactCenterY();
            float colMid = t + (b - t) / 2f;
            col.setTranslationY(Math.round(digitsMid - colMid));
        });
    }

    /** The stats card starts under the Inputs pill and ends with the pill row, whatever the Wi-Fi name. */
    private void alignSystemPanel() {
        View row = findViewById(R.id.pills), card = findViewById(R.id.sysPanel);
        row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int left = l + pills[0].getLeft();
            int right = r - row.getPaddingEnd();
            int width = Math.max(px(380), right - left);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
            int end = screenW - right;
            if (lp.width != width || lp.getMarginEnd() != end) {
                lp.width = width;
                lp.setMarginEnd(end);
                card.post(() -> card.setLayoutParams(lp));
            }
        });
        // Now Playing sits under the system card, same width and edges.
        View np = findViewById(R.id.nowPlaying);
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) np.getLayoutParams();
            ViewGroup.MarginLayoutParams sp = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
            int top = b + px(10);
            if (lp.width != r - l || lp.getMarginEnd() != sp.getMarginEnd() || lp.topMargin != top) {
                lp.width = r - l;
                lp.setMarginEnd(sp.getMarginEnd());
                lp.topMargin = top;
                np.post(() -> np.setLayoutParams(lp));
            }
        });

    }

    private ImageView newDockTile() {
        FrameLayout column = new FrameLayout(this);
        column.setClipChildren(false);
        ImageView tile = new ImageView(this);
        tile.setBackgroundResource(R.drawable.tile_bg);
        tile.setClipToOutline(true);
        tile.setScaleType(ImageView.ScaleType.FIT_XY);
        column.addView(tile, new FrameLayout.LayoutParams(px(TILE_W), px(TILE_H), Gravity.CENTER));
        dockRow.addView(column, new LinearLayout.LayoutParams(px(COL_W), px(TILE_H)));
        return tile;
    }

    // ---------------------------------------------------------------- apps

    /**
     * App list, dock and usage ranking are read on the worker (PackageManager and usage-stats
     * queries take a few hundred ms on this TV), so the home screen draws first and fills in.
     */
    private void loadApps() {
        worker.execute(() -> {
            List<AppEntry> all = AppRepository.load(this);
            List<String> dockPkgs = DockConfig.load(this);
            Map<String, Long> scores = UsageScores.week(this);
            main.post(() -> applyApps(all, dockPkgs, scores));
        });
    }

    private void applyApps(List<AppEntry> all, List<String> dockPkgs, Map<String, Long> scores) {
        String focusedPkg = focusedPackage();
        Map<String, AppEntry> byPkg = new HashMap<>();
        for (AppEntry e : all) byPkg.put(e.pkg, e);

        List<AppEntry> newDock = new ArrayList<>();
        for (String pkg : dockPkgs) {
            AppEntry e = byPkg.get(pkg);
            if (e != null && !newDock.contains(e) && newDock.size() < DOCK_SIZE) newDock.add(e);
        }
        List<AppEntry> newApps = new ArrayList<>(all);
        newApps.removeAll(newDock);
        usageScores = scores;
        sortByUsage(newApps);

        List<AppEntry> toRender = new ArrayList<>();
        for (AppEntry e : all) {
            AppEntry done = rendered.get(e.cacheKey);
            if (done != null) {
                e.tile = done.tile;
                e.glow = done.glow;
            } else {
                toRender.add(e);
            }
        }
        dock = newDock;
        apps = newApps;

        dockRow.removeAllViews();
        dockTiles.clear();
        for (AppEntry e : dock) {
            ImageView tile = newDockTile();
            tile.setImageBitmap(e.tile);
            dockTiles.add(tile);
        }
        adapter.notifyDataSetChanged();

        // Keep focus on the same app across reloads (installs, updates).
        if (focusedPkg != null) {
            int d = indexOf(dock, focusedPkg), g = indexOf(apps, focusedPkg);
            if (section == DOCK && d >= 0) dockIdx = d;
            if (section == GRID && g >= 0) gridIdx = g;
        }
        dockIdx = clamp(dockIdx, dock.size());
        gridIdx = clamp(gridIdx, apps.size());
        if (section == GRID && apps.isEmpty()) section = DOCK;
        if (section == DOCK && dock.isEmpty()) section = TOP;
        usage.render(new ArrayList<AppEntry>() {{ addAll(dock); addAll(apps); }}, usageScores);
        if (!appsLoaded) {
            appsLoaded = true;
            restoreFocus(); // needs the app list; first load only
        } else {
            refreshFocusLook();
        }

        // Dock first so the home row fills in before the grid below it.
        toRender.sort((a, b) -> Boolean.compare(!dock.contains(a), !dock.contains(b)));
        int w = px(TILE_W), h = px(TILE_H);
        for (AppEntry e : toRender) {
            worker.execute(() -> {
                Bitmap drawn = TileRenderer.render(this, e, w, h);
                int color = TileRenderer.sampleGlow(drawn);
                // Hardware bitmap: the pixels live once, in GPU memory, instead of a heap copy
                // plus the GPU texture.
                Bitmap hw = drawn.copy(Bitmap.Config.HARDWARE, false);
                Bitmap bmp = hw != null ? hw : drawn;
                if (hw != null) drawn.recycle();
                main.post(() -> {
                    e.tile = bmp;
                    e.glow = color;
                    rendered.put(e.cacheKey, e);
                    onTileReady(e);
                });
            });
        }
    }

    /** Most used first (last 7 days); apps with no recorded use stay A-Z. List.sort is stable. */
    private void sortByUsage(List<AppEntry> list) {
        UsageScores.sort(list, usageScores);
    }

    /** On every return home: re-rank the grid and refresh the card, keeping focus on the same app. */
    private void refreshUsage() {
        if (!appsLoaded) return; // the first loadApps() ranks them
        worker.execute(() -> {
            Map<String, Long> scores = UsageScores.week(this);
            main.post(() -> applyUsage(scores));
        });
    }

    private void applyUsage(Map<String, Long> scores) {
        usageScores = scores;
        List<AppEntry> sorted = new ArrayList<>(apps);
        sortByUsage(sorted);
        if (!sorted.equals(apps)) {
            String focusedPkg = section == GRID ? focusedPackage() : null;
            apps = sorted;
            if (focusedPkg != null) gridIdx = Math.max(0, indexOf(apps, focusedPkg));
            adapter.notifyDataSetChanged();
            if (section == GRID) grid.post(() -> scrollGridToRow(gridIdx / COLS, false));
        }
        List<AppEntry> all = new ArrayList<>(dock);
        all.addAll(apps);
        usage.render(all, usageScores);
    }

    private void onTileReady(AppEntry e) {
        int d = dock.indexOf(e);
        if (d >= 0) {
            dockTiles.get(d).setImageBitmap(e.tile);
            if (d == dockIdx) setGlow(e.glow, true);
        }
        int g = apps.indexOf(e);
        if (g >= 0) adapter.notifyItemChanged(g, PAYLOAD_TILE);
    }

    private static int indexOf(List<AppEntry> list, String pkg) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    private static int clamp(int i, int size) {
        return Math.max(0, Math.min(i, size - 1));
    }

    private String focusedPackage() {
        if (section == GRID && gridIdx < apps.size()) return apps.get(gridIdx).pkg;
        if (dockIdx < dock.size()) return dock.get(dockIdx).pkg;
        return null;
    }

    private void launch(AppEntry e) {
        try {
            startActivity(e.launch);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, e.label + " can't be opened", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- focus

    private void restoreFocus() {
        String pkg = prefs.getString("pkg", null);
        int g = indexOf(apps, pkg), d = indexOf(dock, pkg);
        if (prefs.getInt("section", DOCK) == GRID && g >= 0) {
            section = GRID;
            gridIdx = g;
            page.setTranslationY(-gridShift());
            grid.post(() -> scrollGridToRow(gridIdx / COLS, false));
        } else if (!dock.isEmpty()) {
            section = DOCK;
            dockIdx = Math.max(0, d);
        } else {
            section = TOP;
        }
        refreshFocusLook();
    }

    private void saveFocus() {
        String pkg = focusedPackage();
        prefs.edit().putInt("section", section == GRID ? GRID : DOCK).putString("pkg", pkg).apply();
    }

    private int indexIn(int sec) {
        switch (sec) {
            case TOP: return topIdx;
            case DOCK: return dockIdx;
            case GRID: return gridIdx;
            case NOW: return nowIdx;
            default: return menuIdx;
        }
    }

    private void moveFocus(int newSection, int newIndex) {
        int oldSection = section;
        applyLook(section, indexIn(section), false, true);
        section = newSection;
        switch (newSection) {
            case TOP: topIdx = newIndex; break;
            case DOCK: dockIdx = newIndex; break;
            case GRID: gridIdx = newIndex; break;
            case NOW: nowIdx = newIndex; break;
            default: menuIdx = newIndex; break;
        }
        applyLook(section, newIndex, true, true);
        if ((oldSection == GRID) != (newSection == GRID)) {
            page.animate().translationY(newSection == GRID ? -gridShift() : 0)
                    .setDuration(420).setInterpolator(new DecelerateInterpolator(2f)).start();
            setHomeVisible(newSection != GRID, true);
            // Back on the home screen the peek shows the first row again.
            int scrolled = gridScroll();
            if (newSection != GRID && scrolled != 0) {
                gridScrollTarget = 0;
                grid.smoothScrollBy(0, -scrolled, new DecelerateInterpolator(2f));
            }
            system.setVisible(newSection != GRID);
            nowPlaying.setVisible(newSection != GRID);
        }
        if (newSection == GRID) scrollGridToRow(newIndex / COLS, true);
        updateHero(true);
        page.invalidate();
        main.removeCallbacks(saveFocus);
        main.postDelayed(saveFocus, 1000);
    }

    /** Re-applies every focus visual without animation (after reloads / restore). */
    private void refreshFocusLook() {
        for (int i = 0; i < pills.length; i++) applyLook(TOP, i, section == TOP && i == topIdx, false);
        for (int i = 0; i < dockTiles.size(); i++) applyLook(DOCK, i, section == DOCK && i == dockIdx, false);
        for (int i = 0; i < NowPlayingPanel.BUTTONS; i++) applyLook(NOW, i, section == NOW && i == nowIdx, false);
        adapter.notifyDataSetChanged();
        setHomeVisible(section != GRID, false);
        system.setVisible(section != GRID);
        nowPlaying.setVisible(section != GRID);
        updateHero(false);
        page.invalidate();
    }

    /** The clock, pills and dock fade out while the grid has the screen; the peek is dimmed on home. */
    private void setHomeVisible(boolean visible, boolean animate) {
        View topFade = findViewById(R.id.gridTopFade);
        topFade.animate().cancel();
        if (animate) topFade.animate().alpha(visible ? 0f : 1f).setDuration(visible ? 220 : 320).start();
        else topFade.setAlpha(visible ? 0f : 1f);
        grid.animate().cancel();
        if (animate) grid.animate().alpha(visible ? PEEK_ALPHA : 1f).setDuration(visible ? 320 : 220).start();
        else grid.setAlpha(visible ? PEEK_ALPHA : 1f);
        for (View v : homeViews) {
            v.animate().cancel();
            if (animate) v.animate().alpha(visible ? 1f : 0f).setDuration(visible ? 320 : 220).start();
            else v.setAlpha(visible ? 1f : 0f);
        }
    }

    private void applyLook(int sec, int idx, boolean on, boolean animate) {
        View v = null;
        View label = null;
        float scale = on ? TILE_SCALE : 1f;
        if (sec == TOP && idx < pills.length) {
            v = pills[idx];
            scale = on ? PILL_SCALE : 1f;
        } else if (sec == DOCK && idx < dockTiles.size()) {
            v = dockTiles.get(idx);
        } else if (sec == NOW && idx < NowPlayingPanel.BUTTONS) {
            v = nowPlaying.button(idx);
            scale = on ? NOW_SCALE : 1f;
        } else if (sec == GRID) {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(idx);
            if (vh instanceof Holder) {
                v = ((Holder) vh).tile;
                label = ((Holder) vh).name;
            }
        } else if (sec == MENU && idx < menu.getChildCount()) {
            ((TextView) menu.getChildAt(idx)).setTextColor(on ? 0xFFFFFFFF : 0xB3FFFFFF);
            return;
        }
        if (v == null) return;
        v.animate().cancel();
        if (animate) {
            v.animate().scaleX(scale).scaleY(scale).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            v.setScaleX(scale);
            v.setScaleY(scale);
        }
        if (label != null) {
            label.animate().cancel();
            if (animate) label.animate().alpha(on ? 1f : 0f).setDuration(180).start();
            else label.setAlpha(on ? 1f : 0f);
        }
    }

    /**
     * How far the grid is scrolled, read from the first laid-out row (all rows are the same
     * height). Not tracked from onScrolled: that count drifted after data reloads (by whole rows),
     * which left a gap at the top and cut off the bottom row.
     */
    private int gridScroll() {
        if (grid.getChildCount() == 0) return 0;
        View first = grid.getChildAt(0);
        int pos = grid.getChildAdapterPosition(first);
        if (pos == RecyclerView.NO_POSITION) return 0;
        return (pos / COLS) * first.getHeight() + grid.getPaddingTop() - first.getTop();
    }

    private void scrollGridToRow(int row, boolean smooth) {
        int rows = (apps.size() + COLS - 1) / COLS;
        int content = rows * px(ROW_PITCH) + px(GRID_PAD_TOP + GRID_PAD_BOTTOM);
        int max = Math.max(0, content - grid.getHeight());
        // Keep one row of context above the focused row.
        int target = Math.max(0, Math.min(max, (row - 1) * px(ROW_PITCH)));
        gridScrollTarget = target;
        int dy = target - gridScroll();
        if (dy == 0) return;
        if (smooth) grid.smoothScrollBy(0, dy, new DecelerateInterpolator(1.5f));
        else grid.scrollBy(0, dy);
    }

    private void updateHighlight(boolean snap) {
        View v = null;
        float scale = 1f, radius = px(TILE_RADIUS);
        switch (section) {
            case TOP:
                v = pills[topIdx];
                scale = PILL_SCALE;
                radius = v.getHeight() * scale / 2f;
                break;
            case DOCK:
                if (dockIdx < dockTiles.size()) v = dockTiles.get(dockIdx);
                scale = TILE_SCALE;
                radius *= scale;
                break;
            case GRID:
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(gridIdx);
                if (vh instanceof Holder) v = ((Holder) vh).tile;
                scale = TILE_SCALE;
                radius *= scale;
                break;
            case MENU:
                v = menu.getChildAt(menuIdx);
                break;
            case NOW:
                v = nowPlaying.button(nowIdx);
                scale = NOW_SCALE;
                radius = v.getHeight() * scale / 2f;
                break;
        }
        if (v == null || v.getWidth() == 0) return;
        // Frame the highlight rides with: the menu (fixed), the grid (page slide + list scroll),
        // or the rest of the page (page slide).
        if (section == MENU) highlight.setFrame(0, 0);
        else if (section == GRID) highlight.setFrame(2 + gridScrollTarget, page.getTranslationY() - gridScrollTarget);
        else highlight.setFrame(1, page.getTranslationY());
        highlight.getLocationInWindow(loc);
        int ox = loc[0], oy = loc[1];
        v.getLocationInWindow(loc);
        float cs = v.getScaleX();
        float cx = loc[0] - ox + v.getWidth() * cs / 2f;
        float cy = loc[1] - oy + v.getHeight() * cs / 2f;
        // Grid: where the tile will be once the scroll in progress finishes.
        if (section == GRID) cy += gridScroll() - gridScrollTarget;
        float hw = v.getWidth() * scale / 2f, hh = v.getHeight() * scale / 2f;
        highlight.moveTo(new RectF(cx - hw, cy - hh, cx + hw, cy + hh), radius, snap);
    }

    private void updateHero(boolean animate) {
        boolean show = section == DOCK && !dock.isEmpty();
        if (dockIdx < dock.size()) {
            AppEntry e = dock.get(dockIdx);
            hero.setText(e.label);
            setGlow(e.glow, animate);
        }
        float a = show ? 1f : 0f;
        if (animate) {
            hero.animate().alpha(a).setDuration(220).start();
            glow.animate().alpha(a).setDuration(320).start();
        } else {
            hero.setAlpha(a);
            glow.setAlpha(a);
        }
    }

    private void setGlow(int color, boolean animate) {
        if (glowAnim != null) glowAnim.cancel();
        if (!animate) {
            applyGlow(color);
            return;
        }
        glowAnim = ValueAnimator.ofArgb(glowColor, color);
        glowAnim.setDuration(350);
        glowAnim.addUpdateListener(a -> applyGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void applyGlow(int color) {
        glowColor = color;
        int rgb = color & 0x00FFFFFF;
        glowDrawable.setColors(new int[] {0x70000000 | rgb, 0x2A000000 | rgb, rgb});
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) onUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) onDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) onSide(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) onSide(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                // Act on release, and only for presses that started here, so the OK/Back
                // release from an app that just closed doesn't trigger anything.
                if (down) {
                    if (e.getRepeatCount() == 0) armedKey = code;
                } else if (armedKey == code) {
                    armedKey = KeyEvent.KEYCODE_UNKNOWN;
                    if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) onBack();
                    else onSelect();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void onUp() {
        switch (section) {
            case DOCK:
                if (nowPlaying.isShown()) moveFocus(NOW, NowPlayingPanel.PLAY_PAUSE);
                else moveFocus(TOP, topIdx);
                break;
            case NOW:
                moveFocus(TOP, topIdx);
                break;
            case GRID:
                if (gridIdx >= COLS) moveFocus(GRID, gridIdx - COLS);
                else if (!dock.isEmpty()) moveFocus(DOCK, Math.min(gridIdx, dock.size() - 1));
                else moveFocus(TOP, topIdx);
                break;
            case MENU:
                if (menuIdx > 0) moveFocus(MENU, menuIdx - 1);
                else closeMenu();
                break;
            default:
                break;
        }
    }

    private void onDown() {
        int n = apps.size();
        switch (section) {
            case TOP:
                if (nowPlaying.isShown()) moveFocus(NOW, NowPlayingPanel.PLAY_PAUSE);
                else if (!dock.isEmpty()) moveFocus(DOCK, dockIdx);
                else if (n > 0) moveFocus(GRID, 0);
                break;
            case NOW:
                if (!dock.isEmpty()) moveFocus(DOCK, dockIdx);
                break;
            case DOCK:
                if (n > 0) moveFocus(GRID, Math.min(dockIdx, Math.min(COLS, n) - 1));
                break;
            case GRID:
                int next = gridIdx + COLS;
                if (next < n) moveFocus(GRID, next);
                else if (gridIdx / COLS < (n - 1) / COLS) moveFocus(GRID, n - 1);
                break;
            case MENU:
                if (menuIdx < menu.getChildCount() - 1) moveFocus(MENU, menuIdx + 1);
                break;
        }
    }

    private void onSide(int dir) {
        switch (section) {
            case TOP: {
                int i = topIdx + dir;
                if (i >= 0 && i < pills.length) moveFocus(TOP, i);
                break;
            }
            case DOCK: {
                int i = dockIdx + dir;
                if (i >= 0 && i < dock.size()) moveFocus(DOCK, i);
                break;
            }
            case NOW: {
                int i = nowIdx + dir;
                if (i >= 0 && i < NowPlayingPanel.BUTTONS) moveFocus(NOW, i);
                break;
            }
            case GRID: {
                int col = gridIdx % COLS, i = gridIdx + dir;
                if (dir < 0 ? col > 0 : col < COLS - 1 && i < apps.size()) moveFocus(GRID, i);
                break;
            }
            default:
                break;
        }
    }

    private void onSelect() {
        switch (section) {
            case TOP:
                if (topIdx == 0) openMenu();
                else startSafely(new Intent(topIdx == 1 ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_SETTINGS));
                break;
            case DOCK:
                if (dockIdx < dock.size()) launch(dock.get(dockIdx));
                break;
            case GRID:
                if (gridIdx < apps.size()) launch(apps.get(gridIdx));
                break;
            case NOW:
                nowPlaying.press(nowIdx);
                break;
            case MENU:
                if (menuIdx < inputs.size()) {
                    Intent intent = inputs.get(menuIdx).intent;
                    closeMenu();
                    startSafely(intent);
                }
                break;
        }
    }

    private void onBack() {
        if (section == MENU) closeMenu();
        else if (section == GRID || section == TOP || section == NOW) {
            if (!dock.isEmpty()) moveFocus(DOCK, dockIdx);
        }
    }

    private void startSafely(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- inputs

    private void openMenu() {
        inputs = TvInputs.load(this);
        menu.removeAllViews();
        for (TvInputs.Item item : inputs) {
            TextView row = new TextView(this);
            row.setText(item.label);
            row.setTextSize(16);
            row.setTypeface(Fonts.outfit(this, 400));
            row.setTextColor(0xB3FFFFFF);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(16), 0, px(16), 0);
            menu.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(46)));
        }
        if (inputs.isEmpty()) return;
        View pill = pills[0];
        pill.getLocationInWindow(loc);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        lp.leftMargin = Math.min(loc[0], screenW - px(240 + 24));
        lp.topMargin = loc[1] + Math.round(pill.getHeight() * PILL_SCALE) + px(10);
        menu.setLayoutParams(lp);
        menu.setVisibility(View.VISIBLE);
        menu.setAlpha(0f);
        menu.setTranslationY(-px(8));
        menu.animate().alpha(1f).translationY(0).setDuration(160).start();
        moveFocus(MENU, 0);
    }

    private void closeMenu() {
        menu.animate().alpha(0f).translationY(-px(8)).setDuration(120)
                .withEndAction(() -> menu.setVisibility(View.GONE)).start();
        moveFocus(TOP, 0);
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar c = Calendar.getInstance();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", c));
        date.setText(DateFormat.format("EEEE, d MMMM yyyy", c));
        prayer.refresh();
    }

    private int netShown = -1;
    // Network callbacks fire while Android still reports the old network (e.g. onLost before the
    // active network is cleared), so look again shortly after the last one.
    private final Runnable netRecheck = this::updateNetwork;

    /** Wi-Fi pill: network name, icon and colour for connected / no internet / disconnected. */
    private void updateNetwork() {
        NetState s = NetState.read(this);
        String label = s.pillLabel();
        if (!label.contentEquals(pills[1].getText())) pills[1].setText(label);
        if (s.state != netShown) {
            netShown = s.state;
            Drawable icon = getDrawable(s.icon()).mutate();
            // Connected keeps the pill's normal white; problems are coloured.
            int c = s.state == NetState.CONNECTED ? 0xE6FFFFFF : s.color();
            icon.setTint(s.state == NetState.CONNECTED ? NetState.GREEN : c);
            pills[1].setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
            pills[1].setTextColor(c);
        }
        system.refreshNetwork();
        prayer.onNetworkAvailable();
    }

    private void loadWallpaper() {
        File dir = getExternalFilesDir(null);
        File found = null;
        if (dir != null) {
            for (String name : new String[] {"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) {
                File f = new File(dir, name);
                if (f.isFile()) {
                    found = f;
                    break;
                }
            }
        }
        long stamp = found == null ? 0 : found.lastModified() ^ found.length();
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        if (found == null) {
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            return;
        }
        File file = found;
        worker.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= screenW && o.outHeight / (o.inSampleSize * 2) >= screenH) {
                o.inSampleSize *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), o);
            main.post(() -> {
                if (bmp == null) return;
                wallpaper.setImageBitmap(bmp);
                // Dim it so text and tiles stay readable.
                wallpaper.setColorFilter(Color.argb(115, 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
                wallpaper.setVisibility(View.VISIBLE);
            });
        });
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        main.removeCallbacks(dropTiles);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                main.post(MainActivity.this::updateNetwork);
                main.removeCallbacks(netRecheck);
                main.postDelayed(netRecheck, 1500);
            }

            @Override
            public void onLost(@NonNull Network network) {
                main.post(MainActivity.this::updateNetwork);
                main.removeCallbacks(netRecheck);
                main.postDelayed(netRecheck, 1500);
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
                main.post(MainActivity.this::updateNetwork);
            }

            @Override
            public void onLinkPropertiesChanged(@NonNull Network network, @NonNull android.net.LinkProperties lp) {
                main.post(MainActivity.this::updateNetwork);
            }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        ContextCompat.registerReceiver(this, timeReceiver, tf, ContextCompat.RECEIVER_EXPORTED);
        updateClock();
        updateNetwork();
        loadWallpaper();
        updateLive();
        if (tilesDropped) {
            tilesDropped = false;
            loadApps(); // re-renders the tiles dropped by onTrimMemory
        }
        // Back from an app (e.g. after "Open app"): the dock, not the card's buttons.
        if (section == NOW && !dock.isEmpty()) moveFocus(DOCK, dockIdx);
        refreshUsage();
        RemoteBridge.attach(remoteKeys);
    }

    /**
     * Another app has been in front for a while: drop the grid's tile images (the 5 dock tiles
     * stay, so home looks the same on return). They're re-rendered off the main thread when home
     * comes back. Run from onStop + delay: Android doesn't send this process TRIM_MEMORY_UI_HIDDEN
     * because RemoteService keeps it in the foreground-service state.
     */
    private final Runnable dropTiles = this::dropGridTiles;

    private void dropGridTiles() {
        if (resumed || !appsLoaded || tilesDropped) return;
        // Detach the images from every grid view first (on screen and in the recycle pool), then
        // free them now rather than whenever the GC gets round to it.
        for (int i = 0; i < grid.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = grid.getChildViewHolder(grid.getChildAt(i));
            if (vh instanceof Holder) ((Holder) vh).tile.setImageDrawable(null);
        }
        grid.getRecycledViewPool().clear();
        java.util.Set<Bitmap> free = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (AppEntry e : apps) {
            if (dock.contains(e) || e.tile == null) continue;
            free.add(e.tile);
            e.tile = null;
            AppEntry r = rendered.remove(e.cacheKey);
            if (r != null) r.tile = null;
        }
        for (Bitmap b : free) b.recycle();
        tilesDropped = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        updateLive();
    }

    private void updateLive() {
        boolean want = resumed && hasWindowFocus();
        if (want == live) return;
        live = want;
        if (want) {
            system.onResume();
            nowPlaying.start();
        } else {
            system.onPause();
            nowPlaying.stop();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close the menu and go back to the dock.
        if (resumed) {
            if (section == MENU) closeMenu();
            if (section != DOCK && !dock.isEmpty()) moveFocus(DOCK, dockIdx);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        RemoteBridge.detach(remoteKeys);
        unregisterReceiver(timeReceiver);
        updateLive();
        saveFocus();
        super.onPause();
    }

    @Override
    protected void onStop() {
        main.postDelayed(dropTiles, 30_000);
        if (netCallback != null) {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
            netCallback = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- grid adapter

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView tile;
        final TextView name;

        Holder(View v) {
            super(v);
            tile = v.findViewById(R.id.tile);
            name = v.findViewById(R.id.name);
            tile.setClipToOutline(true);
        }
    }

    private final class GridAdapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Holder h = new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tile, parent, false));
            h.name.setTypeface(Fonts.outfit(MainActivity.this, 500));
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
            if (payloads.contains(PAYLOAD_TILE)) {
                h.tile.setImageBitmap(apps.get(position).tile);
            } else {
                onBindViewHolder(h, position);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            AppEntry e = apps.get(position);
            h.tile.setImageBitmap(e.tile);
            h.name.setText(e.label);
            boolean on = section == GRID && position == gridIdx;
            h.tile.animate().cancel();
            h.tile.setScaleX(on ? TILE_SCALE : 1f);
            h.tile.setScaleY(on ? TILE_SCALE : 1f);
            h.name.animate().cancel();
            h.name.setAlpha(on ? 1f : 0f);
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }
}
