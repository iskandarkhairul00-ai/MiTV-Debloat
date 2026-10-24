package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;

/** App names for packages that may be disabled (getApplicationInfo needs MATCH_DISABLED_COMPONENTS then). */
final class PackageManagerHelper {
    private final PackageManager pm;

    PackageManagerHelper(Context ctx) {
        pm = ctx.getPackageManager();
    }

    String label(String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
