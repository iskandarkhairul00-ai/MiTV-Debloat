package my.rafhan.app.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.session.PlaybackState;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: jam besar di tengah, kad Now Playing di kiri bawah, latar gambar harian Bing
 * (yang sudah dimuat turun launcher) bertukar perlahan dan digelapkan.
 * Anti burn-in: elemen beralih sedikit setiap 2 minit, malap lagi selepas 10 minit.
 * Keluar: tekan mana-mana butang atau sentuh.
 */
public class ScreensaverActivity extends Activity {

    public static final String EXTRA_TITLE = "ss_title";
    public static final String EXTRA_ARTIST = "ss_artist";
    public static final String EXTRA_LIVE = "ss_live";
    public static final String EXTRA_HIJRI = "ss_hijri";

    /** Pilihan: MainActivity boleh set cover art sebelum startActivity. */
    public static Drawable cover;

    private static final int[][] SHIFTS = {{0, 0}, {3, 2}, {-3, -2}, {2, -3}};

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView clock;
    private View centerBox;
    private View card;
    private View dimOverlay;
    private int shiftIdx = 0;

    // ---- Latar: gambar Bing yang bertukar, dua lapisan untuk fade silang ----
    private ImageView bgA, bgB;
    private View scrim;
    private boolean frontA = true;
    private int picIdx;
    private long lastBingTry;
    private static final long BG_MS = 45_000, SCRIM_MS = 1200;
    private static final float SCRIM_NORMAL = 0.62f;
    private final ExecutorService bgWorker = Executors.newSingleThreadExecutor();
    private long created;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("EEEE, d MMMM yyyy", new Locale("ms", "MY"));

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(timeFmt.format(new Date()));
            h.postDelayed(this, 1000);
        }
    };

    private final Runnable shiftTask = new Runnable() {
        @Override public void run() {
            shiftIdx = (shiftIdx + 1) % SHIFTS.length;
            int dx = dp(SHIFTS[shiftIdx][0]);
            int dy = dp(SHIFTS[shiftIdx][1]);
            centerBox.animate().translationX(dx).translationY(dy).setDuration(800).start();
            if (card.getVisibility() == View.VISIBLE) {
                card.animate().translationX(dx).translationY(dy).setDuration(800).start();
            }
            h.postDelayed(this, 120_000);
        }
    };

    private final Runnable bgTask = new Runnable() {
        @Override public void run() {
            nextBackground();
            h.postDelayed(this, BG_MS);
        }
    };

    private final Runnable dimTask = new Runnable() {
        @Override public void run() {
            dimOverlay.animate().alpha(0.45f).setDuration(2000).start();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        created = SystemClock.uptimeMillis();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0F14"));

        // ---- Latar: gambar Bing, dua lapisan untuk fade silang ----
        bgA = new ImageView(this);
        bgB = new ImageView(this);
        for (ImageView v : new ImageView[] {bgA, bgB}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            root.addView(v, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        // Gelap supaya jam jelas dan skrin tak terlalu terang pada waktu malam.
        scrim = new View(this);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(SCRIM_NORMAL);
        root.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ---- Tengah: jam + tarikh (+ Hijri kalau dihantar) ----
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        clock = new TextView(this);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(120);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        box.addView(clock);

        TextView date = new TextView(this);
        date.setTextColor(0xB3FFFFFF);
        date.setTextSize(26);
        date.setText(dateFmt.format(new Date()));
        box.addView(date);

        String hijri = getIntent().getStringExtra(EXTRA_HIJRI);
        if (hijri != null && !hijri.isEmpty()) {
            TextView hj = new TextView(this);
            hj.setTextColor(0x80FFFFFF);
            hj.setTextSize(20);
            hj.setText(hijri);
            hj.setPadding(0, dp(4), 0, 0);
            box.addView(hj);
        }

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(box, boxLp);
        centerBox = box;

        // ---- Kiri bawah: kad Now Playing ----
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String artist = getIntent().getStringExtra(EXTRA_ARTIST);
        boolean live = getIntent().getBooleanExtra(EXTRA_LIVE, false);

        card = buildCard(title, artist, live);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp(420), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        cardLp.setMargins(dp(48), 0, 0, dp(48));
        root.addView(card, cardLp);
        card.setVisibility(title == null || title.isEmpty() ? View.GONE : View.VISIBLE);

        // ---- Lapisan malap (selepas 10 minit) ----
        dimOverlay = new View(this);
        dimOverlay.setBackgroundColor(Color.BLACK);
        dimOverlay.setAlpha(0f);
        root.addView(dimOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private View buildCard(String title, String artist, boolean live) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(20));
        c.setBackground(bg);

        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setColor(0xFF4B5D2A);
        artBg.setCornerRadius(dp(12));
        if (cover != null) {
            art.setImageDrawable(cover);
        } else {
            art.setBackground(artBg);
        }
        art.setClipToOutline(true);
        art.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
            }
        });
        c.addView(art, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);

        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setText(title == null ? "" : title);
        texts.addView(t);

        TextView a = new TextView(this);
        a.setTextColor(0xB3FFFFFF);
        a.setTextSize(15);
        a.setSingleLine(true);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        a.setText(artist == null ? "" : artist);
        texts.addView(a);

        if (live) {
            TextView badge = new TextView(this);
            badge.setText("LIVE");
            badge.setTextColor(0xFFFF5A52);
            badge.setTextSize(12);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setPadding(0, dp(4), 0, 0);
            texts.addView(badge);
        }

        c.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return c;
    }

    @Override protected void onResume() {
        super.onResume();
        h.post(tick);
        h.post(bgTask);
        h.postDelayed(shiftTask, 120_000);
        h.postDelayed(dimTask, 600_000);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacksAndMessages(null);
    }

    /** Tekan Home: keluar dari screensaver juga. */
    @Override protected void onUserLeaveHint() {
        finish();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        cover = null;
        bgWorker.shutdownNow();
    }

    // ---- Keluar: mana-mana butang / sentuhan ----
    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        // abaikan tekanan Back kedua yang masih berlangsung semasa skrin ini baru dibuka
        if (SystemClock.uptimeMillis() - created < 600) return true;
        finish();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) finish();
        return true;
    }

    // ================================================================ Latar Bing

    /** Muat gambar Bing seterusnya di thread latar (muat turun dulu kalau belum ada / sudah tiba masa). */
    private void nextBackground() {
        bgWorker.execute(() -> {
            long now = SystemClock.elapsedRealtime();
            boolean tryNet = Bing.due(this) && (lastBingTry == 0 || now - lastBingTry > 10 * 60_000L);
            if (tryNet) {
                lastBingTry = now;
                Bing.refresh(this);
            }
            List<File> pics = Bing.pictures(this);
            if (pics.isEmpty()) return;
            File f = pics.get(Math.floorMod(picIdx++, pics.size()));
            Bitmap b = decode(f, 1280);
            if (b != null) h.post(() -> showBackground(b));
        });
    }

    private static Bitmap decode(File f, int targetW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Fade silang: gambar baru masuk di lapisan belakang, lapisan depan pudar. */
    private void showBackground(Bitmap b) {
        if (isFinishing() || isDestroyed()) return;
        ImageView front = frontA ? bgA : bgB, back = frontA ? bgB : bgA;
        back.setImageBitmap(b);
        back.setAlpha(0f);
        back.animate().alpha(1f).setDuration(1500).start();
        if (front.getDrawable() != null) {
            front.animate().alpha(0f).setDuration(1500).withEndAction(() -> front.setImageDrawable(null)).start();
        }
        frontA = !frontA;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
