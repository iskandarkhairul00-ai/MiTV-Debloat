package my.rafhan.app.tvhome;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The pairing PIN, shown over whatever is on the TV (TYPE_APPLICATION_OVERLAY, not focusable,
 * so the physical remote keeps working). Needs SYSTEM_ALERT_WINDOW, granted by
 * build-and-install.sh.
 */
final class PinOverlay {
    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autoHide = this::hideNow;
    private View view;

    PinOverlay(Context ctx) {
        this.ctx = ctx;
        wm = ctx.getSystemService(WindowManager.class);
    }

    boolean canShow() {
        return Settings.canDrawOverlays(ctx);
    }

    void show(String pin, String url, long millis) {
        main.post(() -> {
            hideNow();
            float dp = ctx.getResources().getDisplayMetrics().density;
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setBackgroundResource(R.drawable.pin_bg);
            int px = Math.round(28 * dp), py = Math.round(16 * dp);
            card.setPadding(px, py, px, py);
            card.addView(text("PHONE REMOTE · ENTER THIS PIN", 11, 500, 0x8CFFFFFF, 0.12f));
            card.addView(text(pin, 48, 300, 0xFFFFFFFF, 0.35f));
            card.addView(text(url, 14, 400, 0xB3FFFFFF, 0f));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            try {
                wm.addView(card, lp);
                view = card;
                main.postDelayed(autoHide, millis);
            } catch (RuntimeException e) {
                view = null;
            }
        });
    }

    void hide() {
        main.post(this::hideNow);
    }

    private void hideNow() {
        main.removeCallbacks(autoHide);
        if (view != null) {
            try {
                wm.removeView(view);
            } catch (RuntimeException ignored) {
            }
            view = null;
        }
    }

    private TextView text(String s, float sp, int weight, int color, float spacing) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Fonts.outfit(ctx, weight));
        t.setLetterSpacing(spacing);
        t.setGravity(Gravity.CENTER);
        return t;
    }
}
