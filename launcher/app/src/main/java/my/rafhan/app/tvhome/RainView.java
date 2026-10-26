package my.rafhan.app.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Falling rain (and, in storm mode, lightning flashes) drawn over the wallpaper, under the cards.
 *
 * Built for a 2 GB TV: two float arrays of drop positions, no bitmaps, and two drawLines calls
 * per frame at about 30 fps. It only animates while it is shown AND the launcher window is on
 * screen (another app in front stops it), so it costs nothing in other apps.
 */
public final class RainView extends View {
    public static final int OFF = 0, RAIN = 1, STORM = 2;

    private static final int FAR = 0, NEAR = 1; // two depth layers
    private static final long FRAME_MS = 33;

    private final Random rnd = new Random();
    private final Paint[] paint = new Paint[2];
    private final float[][] drop = new float[2][]; // x, y, length, speed per drop (4 floats)
    private final float[][] line = new float[2][]; // x1, y1, x2, y2 per drop
    private int[] count = {0, 0};
    private int mode = OFF;
    private long last;
    private boolean running;

    // Lightning (storm only)
    private long nextFlashAt, flashStart;
    private int flashPulses;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            if (now - last >= FRAME_MS) {
                invalidate();
            }
            postOnAnimation(this);
        }
    };

    public RainView(Context c) {
        this(c, null);
    }

    public RainView(Context c, AttributeSet a) {
        super(c, a);
        float d = c.getResources().getDisplayMetrics().density;
        for (int i = 0; i < 2; i++) {
            paint[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint[i].setStrokeCap(Paint.Cap.ROUND);
        }
        paint[FAR].setStrokeWidth(1.2f * d);
        paint[FAR].setColor(Color.argb(70, 200, 220, 245));
        paint[NEAR].setStrokeWidth(2.0f * d);
        paint[NEAR].setColor(Color.argb(120, 220, 235, 255));
        setWillNotDraw(false);
        setVisibility(GONE);
    }

    /** OFF hides the view (and stops all work); RAIN and STORM show it. */
    public void setMode(int m) {
        if (m == mode) return;
        mode = m;
        if (m == OFF) {
            setVisibility(GONE);
            stop();
            return;
        }
        int far = m == STORM ? 90 : 70, near = m == STORM ? 45 : 30;
        count[FAR] = far;
        count[NEAR] = near;
        for (int i = 0; i < 2; i++) {
            drop[i] = new float[count[i] * 4];
            line[i] = new float[count[i] * 4];
        }
        if (getWidth() > 0) seed();
        nextFlashAt = System.currentTimeMillis() + 3000 + rnd.nextInt(5000);
        flashPulses = 0;
        setVisibility(VISIBLE);
        if (getWindowVisibility() == VISIBLE) start();
    }

    private void seed() {
        int w = getWidth(), h = getHeight();
        for (int g = 0; g < 2; g++) {
            for (int i = 0; i < count[g]; i++) respawn(g, i, w, h, true);
        }
    }

    private void respawn(int g, int i, int w, int h, boolean anywhere) {
        float[] d = drop[g];
        int o = i * 4;
        boolean near = g == NEAR;
        float len = h * (near ? 0.05f : 0.028f) * (0.7f + rnd.nextFloat() * 0.6f);
        float speed = h * (near ? 1.25f : 0.8f) * (0.8f + rnd.nextFloat() * 0.4f); // px per second
        d[o] = rnd.nextFloat() * (w + h * 0.25f);
        d[o + 1] = anywhere ? rnd.nextFloat() * h : -len - rnd.nextFloat() * h * 0.3f;
        d[o + 2] = len;
        d[o + 3] = speed;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (mode != OFF && w > 0) seed();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mode == OFF) return;
        long now = System.currentTimeMillis();
        float dt = last == 0 ? 0.033f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        int w = getWidth(), h = getHeight();
        final float slant = 0.18f; // sideways per unit down: rain falls slightly to the left
        for (int g = 0; g < 2; g++) {
            float[] d = drop[g], l = line[g];
            for (int i = 0; i < count[g]; i++) {
                int o = i * 4;
                d[o + 1] += d[o + 3] * dt;
                d[o] -= d[o + 3] * slant * dt;
                if (d[o + 1] > h || d[o] < -40) respawn(g, i, w, h, false);
                l[o] = d[o];
                l[o + 1] = d[o + 1];
                l[o + 2] = d[o] - d[o + 2] * slant;
                l[o + 3] = d[o + 1] + d[o + 2];
            }
            canvas.drawLines(l, 0, count[g] * 4, paint[g]);
        }
        if (mode == STORM) lightning(canvas, now);
    }

    /** A flash every few seconds: two quick pulses that fade out. */
    private void lightning(Canvas canvas, long now) {
        if (flashPulses == 0 && now >= nextFlashAt) {
            flashPulses = 2;
            flashStart = now;
        }
        if (flashPulses == 0) return;
        long t = now - flashStart;
        float a;
        if (t < 90) a = 0.45f * (1 - t / 90f);
        else if (t < 160) a = 0f;
        else if (t < 520) a = 0.6f * (1 - (t - 160) / 360f);
        else {
            flashPulses = 0;
            nextFlashAt = now + 4000 + rnd.nextInt(8000);
            return;
        }
        if (a > 0.01f) canvas.drawColor(Color.argb((int) (a * 255), 215, 225, 255));
    }

    private void start() {
        if (running || mode == OFF) return;
        running = true;
        last = 0;
        postOnAnimation(frame);
    }

    private void stop() {
        running = false;
        removeCallbacks(frame);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) start();
        else stop();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getWindowVisibility() == VISIBLE) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
