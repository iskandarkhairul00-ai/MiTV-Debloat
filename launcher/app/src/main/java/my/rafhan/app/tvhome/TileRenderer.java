package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Renders each app once into a tile-sized bitmap (banner, or icon + name when the app has no
 * banner) and samples a vivid colour from it for the dock glow. RGB_565 halves the memory.
 */
final class TileRenderer {
    static final int NEUTRAL_GLOW = 0xFF6B7A99;
    private static final int CARD = 0xFF1C1C1E;

    private TileRenderer() {}

    static Bitmap render(Context context, AppEntry e, int w, int h) {
        PackageManager pm = context.getPackageManager();
        Drawable banner = null;
        try {
            banner = pm.getActivityBanner(e.component);
            if (banner == null) banner = pm.getApplicationBanner(e.pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(CARD);
        if (banner != null) {
            banner.setBounds(0, 0, w, h);
            banner.draw(canvas);
        } else {
            Drawable icon;
            try {
                icon = pm.getActivityIcon(e.component);
            } catch (PackageManager.NameNotFoundException ex) {
                icon = pm.getDefaultActivityIcon();
            }
            int size = Math.round(h * 0.46f);
            int left = (w - size) / 2, top = Math.round(h * 0.13f);
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xD9FFFFFF);
            paint.setTypeface(Fonts.outfit(context, 500));
            paint.setTextSize(h * 0.13f);
            paint.setTextAlign(Paint.Align.CENTER);
            CharSequence label = TextUtils.ellipsize(e.label, paint, w * 0.86f, TextUtils.TruncateAt.END);
            canvas.drawText(label, 0, label.length(), w / 2f, top + size + h * 0.2f, paint);
        }
        return bmp;
    }

    /** Hue weighted towards saturated, bright pixels; full brightness so it reads as light. */
    static int sampleGlow(Bitmap bmp) {
        Bitmap small = Bitmap.createScaledBitmap(bmp, 24, 14, true);
        int[] px = new int[24 * 14];
        small.getPixels(px, 0, 24, 0, 0, 24, 14);
        if (small != bmp) small.recycle();
        float[] hsv = new float[3];
        double sx = 0, sy = 0, wsum = 0, ssum = 0;
        for (int p : px) {
            Color.colorToHSV(p, hsv);
            if (hsv[2] < 0.12f) continue;
            double weight = hsv[1] * hsv[2];
            weight *= weight;
            double angle = Math.toRadians(hsv[0]);
            sx += Math.cos(angle) * weight;
            sy += Math.sin(angle) * weight;
            ssum += hsv[1] * weight;
            wsum += weight;
        }
        if (wsum < 3) return NEUTRAL_GLOW;
        float hue = (float) Math.toDegrees(Math.atan2(sy, sx));
        if (hue < 0) hue += 360f;
        float sat = (float) Math.max(0.5, Math.min(0.9, ssum / wsum));
        return Color.HSVToColor(new float[] {hue, sat, 1f});
    }
}
