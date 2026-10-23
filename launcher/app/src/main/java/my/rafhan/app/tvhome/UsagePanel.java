package my.rafhan.app.tvhome;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Process;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Most used" card: the top apps this week (Monday or Sunday start, Settings), from UsageScores.
 *
 * Needs Usage access: build-and-install.sh grants it with
 * {@code appops set my.rafhan.app.tvhome GET_USAGE_STATS allow}.
 */
final class UsagePanel {
    private static final int TOP = 5;
    private static final int TEXT = 0xFFFFFFFF, DIM = 0x8CFFFFFF, BAR = 0xB3FFFFFF;

    private final Context ctx;
    private final float dp;
    private final UsageStatsManager usm;
    private final TextView total, message;
    private final LinearLayout row;
    private final TextView[] times = new TextView[TOP], names = new TextView[TOP];
    private final Bar[] bars = new Bar[TOP];
    private final View[] cells = new View[TOP];

    UsagePanel(Context ctx, LinearLayout root) {
        this.ctx = ctx;
        dp = ctx.getResources().getDisplayMetrics().density;
        usm = ctx.getSystemService(UsageStatsManager.class);

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_chart);
        icon.setImageTintList(ColorStateList.valueOf(DIM));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(12), px(12));
        ilp.setMarginEnd(px(5));
        header.addView(icon, ilp);
        header.addView(small("MOST USED · THIS WEEK", DIM, 10.5f));
        total = small("", DIM, 10.5f);
        total.setGravity(Gravity.END);
        header.addView(total, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // The card's end padding is 4dp (cells carry their own); +8dp matches the prayer card's 12dp.
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMarginEnd(px(8));
        root.addView(header, hlp);

        message = small("", DIM, 10f);
        message.setLetterSpacing(0f);
        message.setSingleLine(false);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = px(8);
        root.addView(message, mlp);

        row = new LinearLayout(ctx);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = px(8);
        root.addView(row, rlp);
        for (int i = 0; i < TOP; i++) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(0, 0, px(10), 0);
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cells[i] = cell;
            times[i] = new TextView(ctx);
            times[i].setTextSize(15f);
            times[i].setTypeface(Fonts.outfit(ctx, 500));
            times[i].setTextColor(TEXT);
            times[i].setIncludeFontPadding(false);
            times[i].setSingleLine(true);
            cell.addView(times[i], new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            names[i] = small("", DIM, 9.5f);
            names[i].setLetterSpacing(0.02f);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = px(2);
            cell.addView(names[i], nlp);
            bars[i] = new Bar(ctx);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3));
            blp.topMargin = px(5);
            cell.addView(bars[i], blp);
        }
    }

    boolean granted() {
        return UsageScores.granted(ctx);
    }

    Map<String, Long> weekScores() {
        return UsageScores.week(ctx);
    }

    /** Shows the top apps among {@code all} (the launchable apps, dock included). */
    void render(List<AppEntry> all, Map<String, Long> scores) {
        if (!granted()) {
            showMessage("Needs Usage access. Run: adb shell appops set " + ctx.getPackageName()
                    + " GET_USAGE_STATS allow");
            return;
        }
        List<AppEntry> ranked = new ArrayList<>();
        long sum = 0;
        for (AppEntry e : all) {
            long s = score(scores, e.pkg);
            sum += s;
            if (s >= 60_000) ranked.add(e);
        }
        if (ranked.isEmpty()) {
            showMessage("No app use recorded this week yet.");
            return;
        }
        ranked.sort((a, b) -> Long.compare(score(scores, b.pkg), score(scores, a.pkg)));
        message.setVisibility(View.GONE);
        row.setVisibility(View.VISIBLE);
        total.setText(duration(sum));
        long max = score(scores, ranked.get(0).pkg);
        for (int i = 0; i < TOP; i++) {
            if (i >= ranked.size()) {
                cells[i].setVisibility(View.INVISIBLE);
                continue;
            }
            AppEntry e = ranked.get(i);
            long s = score(scores, e.pkg);
            cells[i].setVisibility(View.VISIBLE);
            times[i].setText(duration(s));
            names[i].setText(e.label);
            bars[i].set(max > 0 ? s / (float) max : 0f);
        }
    }

    static long score(Map<String, Long> scores, String pkg) {
        return UsageScores.score(scores, pkg);
    }

    private void showMessage(String text) {
        total.setText("");
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        row.setVisibility(View.GONE);
    }

    private static String duration(long ms) {
        long m = ms / 60_000, h = m / 60;
        if (m < 1) return "<1m";
        return h > 0 ? h + "h " + (m % 60) + "m" : m + "m";
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private TextView small(String text, int color, float size) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Fonts.outfit(ctx, 500));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float fraction;

        Bar(Context c) {
            super(c);
        }

        void set(float f) {
            fraction = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight(), rad = h / 2f;
            r.set(0, 0, getWidth(), h);
            paint.setColor(0x26FFFFFF);
            canvas.drawRoundRect(r, rad, rad, paint);
            r.right = getWidth() * fraction;
            paint.setColor(BAR);
            canvas.drawRoundRect(r, rad, rad, paint);
        }
    }
}
