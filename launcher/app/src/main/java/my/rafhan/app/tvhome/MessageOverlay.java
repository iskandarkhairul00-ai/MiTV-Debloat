package my.rafhan.app.tvhome;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/**
 * A message typed on the phone remote, in a box like the pairing PIN, over whatever is on the TV.
 * Unlike the PIN it takes key focus and stays until someone presses OK or Back (on the TV remote,
 * or on the phone), so it can't be missed. Volume and media keys still reach the TV.
 */
final class MessageOverlay {
    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile View view;

    MessageOverlay(Context ctx) {
        this.ctx = ctx;
        wm = ctx.getSystemService(WindowManager.class);
    }

    boolean canShow() {
        return Settings.canDrawOverlays(ctx);
    }

    boolean isShowing() {
        return view != null;
    }

    /** Shows {@code text}, replacing a message still on screen. */
    void show(String text) {
        main.post(() -> {
            hideNow();
            float dp = ctx.getResources().getDisplayMetrics().density;
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setBackgroundResource(R.drawable.pin_bg);
            int px = Math.round(32 * dp), py = Math.round(20 * dp);
            card.setPadding(px, py, px, py);
            String time = DateFormat.getTimeFormat(ctx).format(new Date());
            card.addView(text("MESSAGE FROM PHONE · " + time, 11, 500, 0x8CFFFFFF, 0.12f));
            TextView body = text(text, 26, 400, 0xFFFFFFFF, 0f);
            body.setMaxWidth(Math.round(560 * dp));
            body.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.setMargins(0, Math.round(12 * dp), 0, Math.round(4 * dp));
            card.addView(body, blp);

            card.setFocusable(true);
            card.setOnKeyListener((v, code, e) -> {
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    case KeyEvent.KEYCODE_BACK:
                    case KeyEvent.KEYCODE_ESCAPE:
                        if (e.getAction() == KeyEvent.ACTION_UP) hideNow();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return true; // nothing else to move to; don't let the app behind react
                    default:
                        return false; // volume, mute, media keys
                }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0.5f;
            try {
                wm.addView(card, lp);
                view = card;
                card.requestFocus();
            } catch (RuntimeException e) {
                view = null;
            }
        });
    }

    void hide() {
        main.post(this::hideNow);
    }

    private void hideNow() {
        View v = view;
        if (v != null) {
            try {
                wm.removeView(v);
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
