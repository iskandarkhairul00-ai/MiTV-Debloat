package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * A small glass banner at the top of the screen, over any app, when the connection changes:
 * connected, connected without internet, disconnected. Lives in RemoteService so it works while
 * other apps are on screen. Changes are settled first (Android validates a new network a few
 * seconds after it connects), so a normal reconnect doesn't flash "no internet".
 */
final class NetBanner {
    private static final long SETTLE_MS = 3000, VALIDATE_GRACE_MS = 10_000, SHOW_MS = 4000;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectivityManager cm;
    private final WindowManager wm;
    private int announced = -1;
    private boolean lastWifi;
    private long changedAt;
    private View view;

    private final Runnable evaluate = this::evaluate;
    private final Runnable hide = this::hideNow;

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network n) {
            changed();
        }

        @Override
        public void onLost(@NonNull Network n) {
            changed();
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) {
            changed();
        }
    };

    NetBanner(Context ctx) {
        this.ctx = ctx;
        cm = ctx.getSystemService(ConnectivityManager.class);
        wm = ctx.getSystemService(WindowManager.class);
    }

    void start() {
        NetState s = NetState.read(ctx);
        announced = s.state; // the state at start isn't news
        lastWifi = s.wifi;
        cm.registerDefaultNetworkCallback(callback);
    }

    void stop() {
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (RuntimeException ignored) {
        }
        main.removeCallbacksAndMessages(null);
        hideNow();
    }

    private void changed() {
        main.post(() -> {
            changedAt = System.currentTimeMillis();
            main.removeCallbacks(evaluate);
            main.postDelayed(evaluate, SETTLE_MS);
        });
    }

    private void evaluate() {
        NetState s = NetState.read(ctx);
        if (s.state == announced) return;
        // Just connected and not validated yet: give Android's internet check time to finish.
        if (s.state == NetState.NO_INTERNET && announced == NetState.DISCONNECTED
                && System.currentTimeMillis() - changedAt < VALIDATE_GRACE_MS) {
            main.postDelayed(evaluate, 2000);
            return;
        }
        announced = s.state;
        boolean wasWifi = lastWifi;
        if (s.state != NetState.DISCONNECTED) lastWifi = s.wifi;
        show(s, wasWifi);
    }

    private void show(NetState s, boolean wasWifi) {
        if (!LauncherSettings.flag(ctx, LauncherSettings.NET_BANNER)) return; // Settings → Notifications
        showMessage(s.icon(), s.color(), s.bannerLabel(wasWifi), s.state == NetState.CONNECTED ? SHOW_MS : SHOW_MS * 2);
    }

    /** A glass banner at the top of the screen over any app; also used by AlertSources. */
    void showMessage(int iconRes, int iconColor, String text, long millis) {
        main.post(() -> showNow(iconRes, iconColor, text, millis));
    }

    private void showNow(int iconRes, int iconColor, String text, long millis) {
        if (!Settings.canDrawOverlays(ctx)) return;
        hideNow();
        float dp = ctx.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(ctx);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.pin_bg);
        card.setPadding(Math.round(16 * dp), Math.round(10 * dp), Math.round(20 * dp), Math.round(10 * dp));
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(iconColor));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Math.round(22 * dp), Math.round(22 * dp));
        ilp.setMarginEnd(Math.round(10 * dp));
        card.addView(icon, ilp);
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setSingleLine(true);
        t.setMaxWidth(Math.round(720 * dp));
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextSize(16);
        t.setTextColor(0xFFFFFFFF);
        t.setTypeface(Fonts.outfit(ctx, 500));
        card.addView(t);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(28 * dp);
        lp.windowAnimations = android.R.style.Animation_Toast;
        try {
            wm.addView(card, lp);
            view = card;
            main.postDelayed(hide, millis);
        } catch (RuntimeException ignored) {
        }
    }

    private void hideNow() {
        main.removeCallbacks(hide);
        if (view != null) {
            try {
                wm.removeView(view);
            } catch (RuntimeException ignored) {
            }
            view = null;
        }
    }
}
