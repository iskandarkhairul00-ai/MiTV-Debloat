package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every launcher option in one place (prefs "settings"), edited from the Settings page (gear
 * pill). Defaults are what the launcher did before there were settings, so nothing changes until
 * an option is edited. Listeners hear about changes on the main thread, like DockConfig.
 */
final class LauncherSettings {
    // Keys
    static final String ZONE = "prayer_zone", SHOW_PRAYER = "show_prayer", SHOW_USAGE = "show_usage",
            SHOW_SYSTEM = "show_system", SHOW_NOW = "show_now_playing", CLOCK = "clock",
            USAGE_EXCLUDED = "usage_excluded", WALL_DIM = "wallpaper_dim",
            ALERT_PLAY = "alert_play", ALERT_HDMI = "alert_hdmi", ALERT_BT = "alert_bluetooth",
            ALERT_MEMORY = "alert_memory", MEMORY_LEVEL = "memory_level", NET_BANNER = "net_banner",
            REMOTE = "remote_on";

    /** Clock format: follow the TV, or force one. */
    static final int CLOCK_AUTO = 0, CLOCK_12 = 1, CLOCK_24 = 2;
    /** Wallpaper dim levels (alpha of the black scrim) for Off / Low / Medium / High. */
    static final int[] DIM_ALPHA = {0, 70, 115, 160};

    interface Listener {
        void onSettingChanged(String key);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private LauncherSettings() {}

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void changed(String key) {
        main.post(() -> {
            for (Listener l : listeners) l.onSettingChanged(key);
        });
    }

    static boolean flag(Context ctx, String key) {
        return prefs(ctx).getBoolean(key, true); // every switch starts on
    }

    static void setFlag(Context ctx, String key, boolean on) {
        prefs(ctx).edit().putBoolean(key, on).apply();
        changed(key);
    }

    static int choice(Context ctx, String key) {
        return prefs(ctx).getInt(key, defaultChoice(key));
    }

    static void setChoice(Context ctx, String key, int value) {
        prefs(ctx).edit().putInt(key, value).apply();
        changed(key);
    }

    private static int defaultChoice(String key) {
        switch (key) {
            case WALL_DIM: return 2;      // Medium: the old fixed scrim
            case MEMORY_LEVEL: return 85; // %
            default: return 0;
        }
    }

    // ---------------------------------------------------------------- typed helpers

    static String zone(Context ctx) {
        return prefs(ctx).getString(ZONE, ctx.getString(R.string.prayer_zone));
    }

    static void setZone(Context ctx, String zone) {
        prefs(ctx).edit().putString(ZONE, zone).apply();
        changed(ZONE);
    }

    static boolean use24h(Context ctx) {
        int c = choice(ctx, CLOCK);
        return c == CLOCK_AUTO ? DateFormat.is24HourFormat(ctx) : c == CLOCK_24;
    }

    /** Apps left out of Most used (Live TV by default). */
    static Set<String> usageExcluded(Context ctx) {
        Set<String> s = prefs(ctx).getStringSet(USAGE_EXCLUDED, null);
        return s != null ? new HashSet<>(s)
                : new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.usage_excluded)));
    }

    static void setUsageExcluded(Context ctx, String pkg, boolean excluded) {
        Set<String> s = usageExcluded(ctx);
        if (excluded) s.add(pkg);
        else s.remove(pkg);
        prefs(ctx).edit().putStringSet(USAGE_EXCLUDED, s).apply();
        changed(USAGE_EXCLUDED);
    }
}
