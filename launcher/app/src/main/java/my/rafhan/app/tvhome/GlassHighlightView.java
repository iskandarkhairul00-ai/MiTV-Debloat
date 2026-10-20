package my.rafhan.app.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.Arrays;

/**
 * The single translucent "glass" focus highlight. It springs (slightly under-damped) towards
 * whatever rect it is given, and only runs frame callbacks while it is moving.
 */
public final class GlassHighlightView extends View implements Choreographer.FrameCallback {
    private static final float STIFFNESS = 340f;
    private static final float DAMPING = 2f * (float) Math.sqrt(STIFFNESS) * 0.72f;

    // left, top, right, bottom, corner radius
    private final float[] cur = new float[5];
    private final float[] vel = new float[5];
    private final float[] tgt = new float[5];
    private final RectF rect = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sheenMatrix = new Matrix();
    private final LinearGradient sheenShader;
    private final float dp;
    private boolean placed, running;
    private long lastFrame;

    public GlassHighlightView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        fill.setColor(0x14FFFFFF);
        sheenShader = new LinearGradient(0, 0, 0, 1,
                new int[] {0x3DFFFFFF, 0x0DFFFFFF, 0x00FFFFFF}, new float[] {0f, 0.45f, 1f},
                Shader.TileMode.CLAMP);
        sheen.setShader(sheenShader);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.5f * dp);
        edge.setColor(0x8CFFFFFF);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(5f * dp);
        halo.setColor(0x14FFFFFF);
        setWillNotDraw(false);
    }

    /** Moves the highlight to {@code r} (in this view's coordinates). */
    // The spring runs in the coordinates of the content it's on ("frame"): the sliding page, or
    // the scrolling grid inside it. offsetY is where that frame currently is on screen, so page
    // slides and grid scrolls carry the highlight rigidly instead of the spring chasing them
    // (which overshot when moving between the dock and the app list).
    private int frame = -1;
    private float offsetY;

    /**
     * Sets the frame the next target is in and its current on-screen offset. Switching frames
     * converts the current position so nothing jumps.
     */
    public void setFrame(int id, float offset) {
        if (id != frame && frame != -1) {
            float d = offsetY - offset;
            cur[1] += d;
            cur[3] += d;
        }
        frame = id;
        if (offset != offsetY) {
            offsetY = offset;
            invalidate();
        }
    }

    /** {@code r} in screen coordinates; stored relative to the current frame. */
    public void moveTo(RectF r, float radius, boolean snap) {
        r = new RectF(r.left, r.top - offsetY, r.right, r.bottom - offsetY);
        float[] next = {r.left, r.top, r.right, r.bottom, radius};
        boolean changed = false;
        for (int i = 0; i < 5; i++) {
            if (Math.abs(next[i] - tgt[i]) > 0.25f) changed = true;
            tgt[i] = next[i];
        }
        if (!placed || snap) {
            System.arraycopy(tgt, 0, cur, 0, 5);
            Arrays.fill(vel, 0f);
            placed = true;
            invalidate();
            return;
        }
        if (changed && !running) {
            running = true;
            lastFrame = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long now) {
        float dt = lastFrame == 0 ? 1f / 60f : Math.min((now - lastFrame) / 1e9f, 1f / 20f);
        lastFrame = now;
        float h = dt / 4f;
        for (int step = 0; step < 4; step++) {
            for (int i = 0; i < 5; i++) {
                float a = STIFFNESS * (tgt[i] - cur[i]) - DAMPING * vel[i];
                vel[i] += a * h;
                cur[i] += vel[i] * h;
            }
        }
        boolean moving = false;
        for (int i = 0; i < 5; i++) {
            if (Math.abs(tgt[i] - cur[i]) > 0.3f || Math.abs(vel[i]) > 2f) moving = true;
        }
        if (moving) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            System.arraycopy(tgt, 0, cur, 0, 5);
            Arrays.fill(vel, 0f);
            running = false;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!placed) return;
        float r = cur[4];
        canvas.save();
        canvas.translate(0, offsetY);
        rect.set(cur[0], cur[1], cur[2], cur[3]);
        rect.inset(-2.5f * dp, -2.5f * dp);
        canvas.drawRoundRect(rect, r + 2.5f * dp, r + 2.5f * dp, halo);
        rect.set(cur[0], cur[1], cur[2], cur[3]);
        canvas.drawRoundRect(rect, r, r, fill);
        sheenMatrix.setScale(1f, rect.height());
        sheenMatrix.postTranslate(0f, rect.top);
        sheenShader.setLocalMatrix(sheenMatrix);
        canvas.drawRoundRect(rect, r, r, sheen);
        rect.inset(0.75f * dp, 0.75f * dp);
        canvas.drawRoundRect(rect, r, r, edge);
        canvas.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this);
        running = false;
        super.onDetachedFromWindow();
    }
}
