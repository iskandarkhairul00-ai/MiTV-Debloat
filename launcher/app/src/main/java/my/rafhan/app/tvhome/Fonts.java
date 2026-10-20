package my.rafhan.app.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

/** Outfit (SIL OFL 1.1, assets/fonts/OFL.txt) is a variable font: one file, any weight. */
final class Fonts {
    private static final SparseArray<Typeface> CACHE = new SparseArray<>();

    private Fonts() {}

    static Typeface outfit(Context context, int weight) {
        Typeface tf = CACHE.get(weight);
        if (tf == null) {
            tf = new Typeface.Builder(context.getAssets(), "fonts/Outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
            if (tf == null) tf = Typeface.DEFAULT;
            CACHE.put(weight, tf);
        }
        return tf;
    }
}
