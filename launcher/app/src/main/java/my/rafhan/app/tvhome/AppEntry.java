package my.rafhan.app.tvhome;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;

/** One launchable app. {@code tile} and {@code glow} arrive later from the render thread. */
final class AppEntry {
    final String pkg;
    final ComponentName component;
    final String label;
    final Intent launch;
    final String cacheKey;
    Bitmap tile;
    int glow = TileRenderer.NEUTRAL_GLOW;

    AppEntry(ComponentName component, String label, Intent launch, long stamp) {
        this.pkg = component.getPackageName();
        this.component = component;
        this.label = label;
        this.launch = launch;
        this.cacheKey = component.flattenToShortString() + "@" + stamp;
    }
}
