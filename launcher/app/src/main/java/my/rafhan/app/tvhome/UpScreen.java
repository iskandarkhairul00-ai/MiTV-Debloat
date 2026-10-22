package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * The screen above home (Up from the top bar): three borderless columns. Column 1 lists the
 * running apps with Force close; columns 2 and 3 are reserved. MainActivity owns focus; this
 * builds the views and does the work off the main thread.
 */
final class UpScreen {
    private static final int TEXT = 0xFFFFFFFF, DIM = 0x8CFFFFFF, AMBER = 0xFFFFB340;
    private static final int ROW_H = 64, BUTTON = 34; // dp
    private static final String HINT = "OK to force close  ·  hold OK for options";
    /** What a row is: a running app, a hidden one (shown on request), or the "Hidden apps" toggle. */
    static final int APP = 0, HIDDEN = 1, FOOTER = 2;

    interface Listener {
        /** The list was (re)loaded: focus may need clamping. */
        void onListChanged();
    }

    private final Context ctx;
    private final Executor worker;
    private final Listener listener;
    private final float dp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout list;
    private final ScrollView scroll;
    private final TextView status, empty;
    private final List<View> rows = new ArrayList<>();
    private final List<ImageView> buttons = new ArrayList<>(); // per row; null without a button
    private final List<RunningApps.App> rowApps = new ArrayList<>(); // per row; null for the toggle
    final LinearLayout column2, column3; // reserved for later
    private List<RunningApps.App> all = new ArrayList<>();
    private boolean busy, showHidden;
    private int generation;

    UpScreen(Context ctx, FrameLayout root, Executor worker, Listener listener) {
        this.ctx = ctx;
        this.worker = worker;
        this.listener = listener;
        dp = ctx.getResources().getDisplayMetrics().density;

        LinearLayout columns = new LinearLayout(ctx);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        clp.setMargins(px(64), px(56), px(64), 0); // lists run to the bottom edge
        root.addView(columns, clp);

        // Column 1: running apps.
        LinearLayout col1 = column(columns);
        TextView title = text(30, 300, TEXT);
        title.setText("Running apps");
        col1.addView(title);
        status = text(13, 400, DIM);
        status.setText(HINT);
        status.setSingleLine(false);
        LinearLayout.LayoutParams slp = wrap();
        slp.topMargin = px(4);
        slp.bottomMargin = px(14);
        col1.addView(status, slp);
        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false); // clips rows to the list area (the highlight is a separate overlay)
        // Rows fade out at the edges instead of being cut off (top: under the header).
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(28));
        // Room under the last row so it can scroll clear of the bottom fade.
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, px(24));
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col1.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        empty = text(16, 400, DIM);
        empty.setText("Loading…");
        list.addView(empty);

        column2 = column(columns);
        column3 = column(columns);
    }

    private LinearLayout column(LinearLayout parent) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (parent.getChildCount() > 0) lp.setMarginStart(px(32));
        parent.addView(c, lp);
        return c;
    }

    int count() {
        return rows.size();
    }

    int kind(int i) {
        RunningApps.App a = app(i);
        return a == null ? FOOTER : a.hidden ? HIDDEN : APP;
    }

    /** The app on row {@code i}; null for the "Hidden apps" toggle or out of range. */
    RunningApps.App app(int i) {
        return i >= 0 && i < rowApps.size() ? rowApps.get(i) : null;
    }

    View row(int i) {
        return i >= 0 && i < rows.size() ? rows.get(i) : null;
    }

    /** The focused row's Force close button turns amber. */
    void setFocused(int i) {
        for (int n = 0; n < buttons.size(); n++) {
            ImageView b = buttons.get(n);
            if (b == null) continue;
            boolean on = n == i;
            b.setColorFilter(on ? 0xFF000000 : TEXT);
            b.setBackgroundResource(on ? R.drawable.circle_amber : R.drawable.np_button);
        }
        View r = row(i);
        if (r != null) scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, r.getTop() - px(ROW_H))));
    }

    void reload() {
        int gen = ++generation;
        worker.execute(() -> {
            List<RunningApps.App> found = RunningApps.list(ctx);
            main.post(() -> {
                if (gen == generation) show(found);
            });
        });
    }

    /** Shows or hides the hidden apps under the "Hidden apps" row. */
    void toggleHidden() {
        showHidden = !showHidden;
        show(all);
    }

    /** Hold OK → Hide from this list / Show in list again. */
    void setHidden(int i, boolean hide) {
        RunningApps.App app = app(i);
        if (app == null) return;
        RunningApps.setHidden(ctx, app.entry.pkg, hide);
        app.hidden = hide;
        status.setText(app.entry.label + (hide ? " moved to Hidden apps" : " is listed again"));
        status.setTextColor(DIM);
        show(all);
    }

    void forceStop(int i) {
        RunningApps.App app = app(i);
        if (busy || app == null || app.hidden) return;
        busy = true;
        status.setText("Closing " + app.entry.label + "…");
        status.setTextColor(AMBER);
        worker.execute(() -> {
            String msg = RunningApps.forceStop(ctx, app);
            main.post(() -> {
                busy = false;
                status.setText(msg);
                main.postDelayed(this::reload, 800);
            });
        });
    }

    /** Back to the hint when the screen is left. */
    void resetStatus() {
        status.setText(HINT);
        status.setTextColor(DIM);
        showHidden = false;
    }

    private void show(List<RunningApps.App> found) {
        all = found;
        list.removeAllViews();
        rows.clear();
        buttons.clear();
        rowApps.clear();
        List<RunningApps.App> hidden = new ArrayList<>();
        for (RunningApps.App a : found) {
            if (a.hidden) hidden.add(a);
            else addRow(a);
        }
        if (rows.isEmpty()) {
            empty.setText("No other apps running");
            list.addView(empty);
        }
        if (!hidden.isEmpty()) {
            addToggle(hidden.size());
            if (showHidden) for (RunningApps.App a : hidden) addRow(a);
        }
        listener.onListChanged();
    }

    private void addRow(RunningApps.App a) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(12), 0, px(12), 0);
        ImageView icon = new ImageView(ctx);
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(a.entry.pkg);
            icon.setImageDrawable(d);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(36), px(36));
        ilp.setMarginEnd(px(14));
        row.addView(icon, ilp);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(16, 500, TEXT);
        name.setText(a.entry.label);
        texts.addView(name);
        TextView meta = text(12, 400, DIM);
        meta.setText((a.pssKb > 0 ? mb(a.pssKb) + "  ·  " : "") + (a.hidden ? "Hidden" : a.state));
        LinearLayout.LayoutParams mlp = wrap();
        mlp.topMargin = px(3);
        texts.addView(meta, mlp);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView close = null;
        if (a.hidden) {
            icon.setAlpha(0.45f);
            texts.setAlpha(0.6f);
        } else {
            close = new ImageView(ctx);
            close.setImageResource(R.drawable.ic_close);
            close.setScaleType(ImageView.ScaleType.CENTER);
            close.setColorFilter(TEXT);
            close.setBackgroundResource(R.drawable.np_button);
            close.setContentDescription("Force close " + a.entry.label);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(BUTTON), px(BUTTON));
            blp.setMarginStart(px(10));
            row.addView(close, blp);
        }
        add(row, a, close);
    }

    /** "Hidden apps (n)": OK shows / hides them below it. */
    private void addToggle(int n) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(12), 0, px(12), 0);
        TextView name = text(16, 500, DIM);
        name.setText("Hidden apps (" + n + ")");
        row.addView(name);
        TextView meta = text(12, 400, DIM);
        meta.setText(showHidden ? "OK to collapse" : "OK to show");
        LinearLayout.LayoutParams mlp = wrap();
        mlp.topMargin = px(3);
        row.addView(meta, mlp);
        add(row, null, null);
    }

    private void add(View row, RunningApps.App app, ImageView button) {
        list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(ROW_H)));
        rows.add(row);
        rowApps.add(app);
        buttons.add(button);
    }

    private static String mb(long kb) {
        return kb >= 1024 * 1024 ? String.format(Locale.US, "%.1f GB", kb / 1048576.0) : (kb / 1024) + " MB";
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Fonts.outfit(ctx, weight));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }
}
