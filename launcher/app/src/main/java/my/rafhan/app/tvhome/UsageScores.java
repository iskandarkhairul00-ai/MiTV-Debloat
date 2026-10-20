package my.rafhan.app.tvhome;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-app use over the last 7 days, shared by the Most used card, the grid order and the phone
 * remote's app list.
 *
 * Per day, an app's time is the larger of its visible time and its foreground-service time, so
 * an app that keeps playing with the screen elsewhere (RadioStream) counts its playback. Visible,
 * not "foreground": Live TV showing HDMI is visible but barely counts as foreground. Service time
 * only counts on days the app was on screen: on this TV a group of always-on services (Live TV,
 * MiLink, overlays) log identical whole-day service times that aren't use.
 */
final class UsageScores {
    static final long WINDOW_MS = 7L * 24 * 60 * 60 * 1000;

    private UsageScores() {}

    static boolean granted(Context ctx) {
        AppOpsManager ops = ctx.getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                ctx.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** Package -> milliseconds in use over the last 7 days. Empty without Usage access. */
    static Map<String, Long> week(Context ctx) {
        if (!granted(ctx)) return Collections.emptyMap();
        List<String> excluded = Arrays.asList(ctx.getResources().getStringArray(R.array.usage_excluded));
        long end = System.currentTimeMillis(), begin = end - WINDOW_MS;
        Map<String, Long> scores = new HashMap<>();
        List<UsageStats> stats = ctx.getSystemService(UsageStatsManager.class)
                .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end);
        if (stats == null) return scores;
        for (UsageStats u : stats) {
            long first = u.getFirstTimeStamp(), last = u.getLastTimeStamp();
            if (last <= begin) continue;
            long visible = u.getTotalTimeVisible();
            // Background playback only counts on days the app was also on screen: always-on
            // services (overlays, system helpers) otherwise rack up whole days of "use".
            long playback = visible > 0 ? u.getTotalTimeForegroundServiceUsed() : 0;
            long day = Math.max(visible, playback);
            // The day that straddles the start of the window only counts its share inside it.
            if (first < begin && last > first) day = day * (last - begin) / (last - first);
            if (day > 0 && !excluded.contains(u.getPackageName())) scores.merge(u.getPackageName(), day, Long::sum);
        }
        return scores;
    }

    static long score(Map<String, Long> scores, String pkg) {
        Long s = scores.get(pkg);
        return s == null ? 0 : s;
    }

    /** Most used first; apps with no recorded use keep their order (A-Z). List.sort is stable. */
    static void sort(List<AppEntry> list, Map<String, Long> scores) {
        list.sort((a, b) -> Long.compare(score(scores, b.pkg), score(scores, a.pkg)));
    }
}
