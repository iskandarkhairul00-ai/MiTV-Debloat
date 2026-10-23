package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * Keeps the launcher's proportions on any TV. The layout is designed for a 960 x 540 dp screen
 * (1920 x 1080 at density 320, like this Mi TV); other sets report other sizes (a 720p box at
 * density 160 is 1280 x 720 dp, so everything looks small; a dense screen has fewer dp, so cards
 * overlap). This picks the density that makes the screen 960 x 540 dp, so every dp and sp scales
 * with the screen. It fits the smaller of width and height, so a screen that isn't 16:9 still
 * shows everything. Used by MainActivity and RemoteService (the banners over other apps).
 */
final class UiScale {
    private static final float DESIGN_W = 960f, DESIGN_H = 540f; // dp

    private UiScale() {}

    /** An override configuration with the density (and dp sizes) for this screen. */
    static Configuration config(Context base) {
        DisplayMetrics m = base.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels), h = Math.min(m.widthPixels, m.heightPixels);
        float scale = Math.min(w / DESIGN_W, h / DESIGN_H);
        int dpi = Math.max(1, Math.round(DisplayMetrics.DENSITY_DEFAULT * scale));
        Configuration c = new Configuration(); // unset fields keep the system's values
        c.densityDpi = dpi;
        c.screenWidthDp = Math.round(m.widthPixels * (float) DisplayMetrics.DENSITY_DEFAULT / dpi);
        c.screenHeightDp = Math.round(m.heightPixels * (float) DisplayMetrics.DENSITY_DEFAULT / dpi);
        c.smallestScreenWidthDp = Math.min(c.screenWidthDp, c.screenHeightDp);
        return c;
    }

    /** {@code base} with the launcher's density, for contexts that aren't activities. */
    static Context wrap(Context base) {
        return base.createConfigurationContext(config(base));
    }
}
