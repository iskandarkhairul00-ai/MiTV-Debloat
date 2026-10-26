package my.rafhan.app.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;

/**
 * Weather card: the forecast for 8 AM, 11 AM and 2 PM from Open-Meteo (no API key), refreshed at
 * most every 30 minutes and cached in the app's files dir so it still shows offline. The current
 * weather code also drives the "Weather" wallpaper mode (see Wallpapers.rotating).
 *
 * Memory: one small JSON file (~3 KB) and a handful of views; no polling of its own, it rides on
 * the clock's minute tick like PrayerPanel.
 */
final class WeatherPanel {
    // Selangor. Change these two numbers for another place.
    private static final double LAT = 3.07, LON = 101.52;
    private static final String API = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT
            + "&longitude=" + LON + "&current=temperature_2m,weather_code"
            + "&hourly=temperature_2m,weather_code&forecast_days=2&timezone=auto";
    private static final long MAX_AGE_MS = 30 * 60_000L;
    private static final int[] HOURS = {8, 11, 14};
    private static final int TEXT = 0xFFFFFFFF, DIM = 0x8CFFFFFF, NEXT = 0xFFFFC857;

    // "clear", "cloud", "rain", "storm" for the wallpaper; -1 = unknown.
    private static volatile int lastCode = -1;

    private final Context ctx;
    private final Executor worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float dp;
    private final TextView nowView;
    private final TextView[] labels = new TextView[HOURS.length];
    private final TextView[] temps = new TextView[HOURS.length];
    private final ImageView[] icons = new ImageView[HOURS.length];

    private JSONObject data;
    private boolean fetching;
    private long lastAttempt;

    WeatherPanel(Context ctx, LinearLayout root, Executor worker) {
        this.ctx = ctx;
        this.worker = worker;
        dp = ctx.getResources().getDisplayMetrics().density;

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(small("WEATHER", DIM, 10.5f));
        nowView = small("", NEXT, 10.5f);
        nowView.setGravity(Gravity.END);
        header.addView(nowView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        LinearLayout row = new LinearLayout(ctx);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = px(8);
        root.addView(row, rlp);
        for (int i = 0; i < HOURS.length; i++) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            icons[i] = new ImageView(ctx);
            cell.addView(icons[i], new LinearLayout.LayoutParams(px(26), px(26)));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMarginStart(px(6));
            cell.addView(col, clp);

            temps[i] = new TextView(ctx);
            temps[i].setTextSize(17f);
            temps[i].setTypeface(Fonts.outfit(ctx, 500));
            temps[i].setTextColor(TEXT);
            temps[i].setIncludeFontPadding(false);
            temps[i].setText("--°");
            col.addView(temps[i]);
            labels[i] = small("", DIM, 9f);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = px(3);
            col.addView(labels[i], llp);
        }
    }

    /** Called every minute (clock tick) and on resume. */
    void refresh() {
        if (data == null) data = readCache();
        long age = cacheAge();
        if ((data == null || age > MAX_AGE_MS) && !fetching
                && System.currentTimeMillis() - lastAttempt > 60_000) {
            fetch();
        }
        render();
    }

    /** Network came back: retry a stale or failed fetch (at most once a minute). */
    void onNetworkAvailable() {
        if ((data == null || cacheAge() > MAX_AGE_MS) && !fetching
                && System.currentTimeMillis() - lastAttempt > 60_000) {
            fetch();
        }
    }

    /** "clear", "cloud", "rain" or "storm" for the current weather, or null if unknown. */
    static String category(Context ctx) {
        int code = lastCode;
        if (code < 0) {
            JSONObject d = read(new File(ctx.getFilesDir(), "weather.json"));
            if (d != null) code = d.optJSONObject("current") == null ? -1
                    : d.optJSONObject("current").optInt("weather_code", -1);
            lastCode = code;
        }
        return code < 0 ? null : categoryOf(code);
    }

    private static String categoryOf(int code) {
        if (code >= 95) return "storm";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "rain";
        if (code == 0 || code == 1) return "clear";
        return "cloud"; // 2, 3, fog, snow: no dedicated pictures
    }

    private static int iconOf(int code) {
        switch (categoryOf(code)) {
            case "storm": return R.drawable.ic_weather_storm;
            case "rain": return R.drawable.ic_weather_rain;
            case "clear": return R.drawable.ic_weather_clear;
            default: return R.drawable.ic_weather_cloud;
        }
    }

    // ---------------------------------------------------------------- display

    private void render() {
        if (data == null) {
            nowView.setText(fetching ? "Loading…" : "");
            return;
        }
        JSONObject cur = data.optJSONObject("current");
        if (cur != null) {
            nowView.setText("Now " + Math.round(cur.optDouble("temperature_2m")) + "°");
            lastCode = cur.optInt("weather_code", -1);
        }
        JSONObject h = data.optJSONObject("hourly");
        if (h == null) return;
        JSONArray time = h.optJSONArray("time");
        JSONArray temp = h.optJSONArray("temperature_2m");
        JSONArray code = h.optJSONArray("weather_code");
        if (time == null || temp == null || code == null) return;

        LocalDateTime now = LocalDateTime.now();
        boolean h24 = LauncherSettings.use24h(ctx);
        for (int i = 0; i < HOURS.length; i++) {
            // A slot that has already passed today shows tomorrow's (dimmed).
            boolean tomorrow = now.getHour() > HOURS[i];
            LocalDate d = tomorrow ? now.toLocalDate().plusDays(1) : now.toLocalDate();
            String key = d + String.format(java.util.Locale.US, "T%02d:00", HOURS[i]);
            int idx = -1;
            for (int k = 0; k < time.length(); k++) {
                if (key.equals(time.optString(k))) {
                    idx = k;
                    break;
                }
            }
            labels[i].setText(h24 ? String.format(java.util.Locale.US, "%02d:00", HOURS[i])
                    : (HOURS[i] > 12 ? HOURS[i] - 12 : HOURS[i]) + (HOURS[i] >= 12 ? " PM" : " AM"));
            if (idx < 0) {
                temps[i].setText("--°");
                icons[i].setImageDrawable(null);
                continue;
            }
            temps[i].setText(Math.round(temp.optDouble(idx)) + "°");
            temps[i].setTextColor(tomorrow ? DIM : TEXT);
            icons[i].setImageResource(iconOf(code.optInt(idx)));
            icons[i].setAlpha(tomorrow ? 0.55f : 1f);
        }
    }

    // ---------------------------------------------------------------- data

    private File cacheFile() {
        return new File(ctx.getFilesDir(), "weather.json");
    }

    private long cacheAge() {
        File f = cacheFile();
        return f.isFile() ? System.currentTimeMillis() - f.lastModified() : Long.MAX_VALUE;
    }

    private JSONObject readCache() {
        return read(cacheFile());
    }

    private static JSONObject read(File f) {
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            for (String line; (line = r.readLine()) != null; ) sb.append(line);
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private void fetch() {
        fetching = true;
        lastAttempt = System.currentTimeMillis();
        worker.execute(() -> {
            JSONObject result = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(15_000);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code != 200) android.util.Log.w("TvHome", "weather fetch HTTP " + code);
                if (code == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String line; (line = r.readLine()) != null; ) sb.append(line);
                    }
                    JSONObject o = new JSONObject(sb.toString());
                    if (o.optJSONObject("current") != null && o.optJSONObject("hourly") != null) {
                        result = o;
                        try (FileOutputStream out = new FileOutputStream(cacheFile())) {
                            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("TvHome", "weather fetch failed", e);
            } finally {
                if (c != null) c.disconnect();
            }
            JSONObject done = result;
            main.post(() -> {
                fetching = false;
                if (done != null) data = done;
                render();
            });
        });
    }

    // ---------------------------------------------------------------- helpers

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private TextView small(String text, int color, float sp) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sp);
        t.setTypeface(Fonts.outfit(ctx, 500));
        t.setTextColor(color);
        t.setLetterSpacing(0.08f);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }
}
