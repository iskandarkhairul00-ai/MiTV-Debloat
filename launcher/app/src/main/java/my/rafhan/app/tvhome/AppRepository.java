package my.rafhan.app.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lists TV apps (LEANBACK_LAUNCHER), plus sideloaded phone apps that only have a LAUNCHER entry. */
final class AppRepository {
    private AppRepository() {}

    static List<AppEntry> load(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, AppEntry> byPkg = new LinkedHashMap<>();
        collect(context, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, byPkg, null);
        collect(context, pm, Intent.CATEGORY_LAUNCHER, byPkg,
                Arrays.asList(context.getResources().getStringArray(R.array.extra_system_apps)));
        List<AppEntry> list = new ArrayList<>(byPkg.values());
        Collator collator = Collator.getInstance();
        list.sort((a, b) -> collator.compare(a.label, b.label));
        return list;
    }

    /** {@code systemAllow} non-null: skip system apps unless listed (phone-only entries). */
    private static void collect(Context context, PackageManager pm, String category,
                                Map<String, AppEntry> out, List<String> systemAllow) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || out.containsKey(pkg)) continue;
            boolean system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (systemAllow != null && system && !systemAllow.contains(pkg)) continue;
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            long stamp;
            try {
                stamp = pm.getPackageInfo(pkg, 0).lastUpdateTime;
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            CharSequence label = ri.loadLabel(pm);
            out.put(pkg, new AppEntry(cn, label == null ? pkg : label.toString().trim(), launch, stamp));
        }
    }
}
