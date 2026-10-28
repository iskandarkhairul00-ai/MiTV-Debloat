package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Susunan manual app di grid bawah dock. Kalau tiada susunan disimpan, grid ikut penggunaan
 * (AppOrder) seperti biasa. App baru yang belum ada dalam susunan diletak di hujung grid.
 */
final class GridOrder {
    private static final String PREFS = "grid_order";
    private static final String KEY = "pkgs";

    private GridOrder() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<String> load(Context ctx) {
        List<String> out = new ArrayList<>();
        String s = prefs(ctx).getString(KEY, "");
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split("\n")) {
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    static boolean has(Context ctx) {
        return !load(ctx).isEmpty();
    }

    static void save(Context ctx, List<String> pkgs) {
        prefs(ctx).edit().putString(KEY, TextUtils.join("\n", pkgs)).apply();
    }

    static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY).apply();
    }

    /** `list` sudah disusun ikut penggunaan: app yang disimpan diletak dahulu mengikut susunan simpanan. */
    static void apply(Context ctx, List<AppEntry> list) {
        List<String> saved = load(ctx);
        if (saved.isEmpty()) return;
        Map<String, AppEntry> left = new HashMap<>();
        for (AppEntry e : list) left.put(e.pkg, e);
        List<AppEntry> out = new ArrayList<>(list.size());
        for (String p : saved) {
            AppEntry e = left.remove(p);
            if (e != null) out.add(e);
        }
        for (AppEntry e : list) {
            if (left.containsKey(e.pkg)) out.add(e); // app baru: kekal ikut penggunaan, di hujung
        }
        list.clear();
        list.addAll(out);
    }
}
