package my.rafhan.app.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Hujan atas wallpaper, disalin nilai demi nilai daripada WeatherFxView dalam Glass TV v10
 * (dibaca terus dari bytecode APK): bilangan titis, panjang, laju, kelegapan, ketebalan garis,
 * kemiringan, kitaran 33 ms dan dua kilat dalam ribut.
 *
 * API sama seperti sebelum ini: setMode(OFF / RAIN / STORM) + DRIZZLE dan HEAVY.
 */
public class RainView extends View {

    /** Nombor sama dengan "scene" Glass TV. */
    public static final int OFF = 0, DRIZZLE = 1, RAIN = 2, HEAVY = 3, STORM = 4;

    private static final int MAX = 170;
    private static final int[] COUNT = {0, 40, 80, 128, 160};

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flashPaint = new Paint();
    private final float density;

    private final float[] x = new float[MAX], y = new float[MAX], len = new float[MAX],
            speed = new float[MAX], alpha = new float[MAX];

    private int scene = OFF;
    private int count;
    private boolean reducedMotion;
    private boolean running;
    private boolean wantRun;
    private int laidW, laidH;
    private long lastFrameAt, flashAt = -10000, nextFlashAt;
    private int flashAlpha;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.06f, (now - lastFrameAt) / 1000f);
            lastFrameAt = now;
            int w = getWidth(), hgt = getHeight();
            if (w > 0 && hgt > 0) {
                if (w != laidW || hgt != laidH) layoutDrops(w, hgt);
                float k = scene == DRIZZLE ? 0.1f : 0.2f; // hanyut ke kanan
                for (int i = 0; i < count; i++) {
                    y[i] += speed[i] * dt;
                    x[i] += speed[i] * k * dt;
                    if (y[i] > hgt + 30 * density || x[i] > w + 20 * density) reset(i, w, hgt, false);
                }
                if (scene == STORM) stepFlash(now);
                else flashAlpha = 0;
            }
            invalidate();
            postDelayed(this, 33);
        }
    };

    public RainView(Context c) { this(c, null); }

    public RainView(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(2f * density);
        stroke.setColor(0xFFFFFFFF);
        flashPaint.setStyle(Paint.Style.FILL);
        flashPaint.setColor(0xFFFFFFFF);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** TV 1 GB RAM: separuh bilangan titis (sekurang-kurangnya 14). */
    public void setReducedMotion(boolean on) {
        if (reducedMotion == on) return;
        reducedMotion = on;
        applyScene();
    }

    public void setMode(int s) {
        if (s < OFF || s > STORM) s = OFF;
        if (s == scene) return;
        scene = s;
        applyScene();
    }

    private void applyScene() {
        flashAlpha = 0;
        flashAt = -10000;
        layoutDrops(getWidth(), getHeight());
        if (scene == OFF) {
            stopFrames();
        } else {
            startFrames();
        }
        invalidate();
    }

    private void layoutDrops(int w, int hgt) {
        int n = COUNT[scene];
        if (reducedMotion && n > 0) n = Math.max(14, n / 2);
        count = n;
        laidW = w;
        laidH = hgt;
        if (w > 0 && hgt > 0) {
            for (int i = 0; i < count; i++) reset(i, w, hgt, true);
        }
    }

    private float between(float lo, float hi) {
        return lo + random.nextFloat() * (hi - lo);
    }

    private void reset(int i, int w, int hgt, boolean anywhere) {
        switch (scene) {
            case DRIZZLE:
                len[i] = between(9, 19) * density;
                speed[i] = between(255, 390) * density;
                alpha[i] = between(55, 95);
                break;
            case RAIN:
                len[i] = between(17, 32) * density;
                speed[i] = between(530, 800) * density;
                alpha[i] = between(72, 132);
                break;
            case HEAVY:
                len[i] = between(21, 37) * density;
                speed[i] = between(620, 930) * density;
                alpha[i] = between(80, 146);
                break;
            case STORM:
                len[i] = between(24, 42) * density;
                speed[i] = between(710, 1030) * density;
                alpha[i] = between(88, 160);
                break;
            default:
                return;
        }
        y[i] = anywhere ? random.nextFloat() * hgt : -len[i] - random.nextFloat() * 60 * density;
        x[i] = random.nextFloat() * (w * 1.1f) - w * 0.05f;
    }

    /** Dua kilat: 0->52 dalam 45 ms, 52->0 dalam 50 ms, jeda, tahan 34, pudar 34->0 sehingga 300 ms. */
    private void stepFlash(long now) {
        if (now >= nextFlashAt) {
            flashAt = now;
            nextFlashAt = now + 4000 + random.nextInt(8000);
        }
        long t = now - flashAt;
        int a;
        if (t >= 0 && t < 45) a = (int) (t * 52 / 45f);
        else if (t < 95) a = (int) ((95 - t) * 52 / 50f);
        else if (t < 170) a = 0;
        else if (t < 215) a = 34;
        else if (t < 300) a = (int) ((300 - t) * 34 / 85f);
        else a = 0;
        flashAlpha = Math.max(0, a);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (count == 0 && flashAlpha == 0) return;
        int w = getWidth(), hgt = getHeight();
        if (flashAlpha > 0) {
            flashPaint.setAlpha(flashAlpha);
            c.drawRect(0, 0, w, hgt, flashPaint);
        }
        float sw = scene == STORM ? 2.4f : scene == HEAVY ? 2.25f : scene == RAIN ? 2.1f : 1.9f;
        stroke.setStrokeWidth(sw * density);
        float boost = flashAlpha > 0 ? 1.5f : 1f;
        for (int i = 0; i < count; i++) {
            stroke.setAlpha((int) Math.min(255f, alpha[i] * boost));
            c.drawLine(x[i], y[i], x[i] + 0.18f * len[i], y[i] + len[i], stroke);
        }
    }

    @Override
    protected void onSizeChanged(int w, int hgt, int ow, int oh) {
        super.onSizeChanged(w, hgt, ow, oh);
        layoutDrops(w, hgt);
    }

    // ---- hanya jalan bila Home di depan ----

    private void startFrames() {
        wantRun = scene != OFF;
        if (!wantRun || running || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
        running = true;
        lastFrameAt = SystemClock.uptimeMillis();
        nextFlashAt = lastFrameAt + 2000 + random.nextInt(5000);
        postDelayed(frame, 33);
    }

    private void stopFrames() {
        wantRun = false;
        running = false;
        removeCallbacks(frame);
        flashAlpha = 0;
    }

    private void pauseFrames() {
        running = false;
        removeCallbacks(frame);
        flashAlpha = 0;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (scene != OFF) startFrames();
    }

    @Override protected void onDetachedFromWindow() {
        pauseFrames();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        if (v == VISIBLE) {
            if (scene != OFF) startFrames();
        } else {
            pauseFrames();
        }
    }
}
