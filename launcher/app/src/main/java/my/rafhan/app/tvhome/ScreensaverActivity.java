package my.rafhan.app.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Screensaver: latar gambar Bing yang bertukar-tukar, jam besar di kiri, tarikh dan Hijri di bawahnya, kemudian satu baris
 * "♪ Penyanyi · Tajuk lagu" tanpa kotak (kalau ada lagu atau radio yang sedang main).
 * Anti burn-in: semuanya beralih sedikit setiap 2 minit, malap lagi selepas 10 minit.
 * Keluar: tekan mana-mana butang atau sentuh.
 */
public class ScreensaverActivity extends Activity {

    public static final String EXTRA_TITLE = "ss_title";
    public static final String EXTRA_ARTIST = "ss_artist";
    public static final String EXTRA_LIVE = "ss_live";
    public static final String EXTRA_HIJRI = "ss_hijri";

    /** Tidak digunakan lagi (kad dengan gambar album sudah dibuang). Kekal supaya MainActivity masih dikompil. */
    public static Drawable cover;

    private static final int[][] SHIFTS = {{0, 0}, {3, 2}, {-3, -2}, {2, -3}};
    private static final long POLL_MS = 3000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView clock;
    private TextView song;
    private View block;
    private View dimOverlay;
    private int shiftIdx = 0;
    // AtomicBoolean: dilepaskan dalam thread bacaan sendiri, bukan melalui Handler (yang dikosongkan bila onPause).
    private final AtomicBoolean polling = new AtomicBoolean(false);

    // ---- Latar: gambar Bing yang bertukar, dua lapisan untuk fade silang ----
    private ImageView bgA, bgB;
    private boolean frontA = true;
    private int picIdx;
    private long lastBingTry;
    private static final long BG_MS = 45_000;
    private static final float SCRIM_NORMAL = 0.55f;
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
            block.animate().translationX(dp(SHIFTS[shiftIdx][0])).translationY(dp(SHIFTS[shiftIdx][1]))
                    .setDuration(800).start();
            h.postDelayed(this, 120_000);
        }
    };

    /** Lagu boleh bertukar semasa screensaver: baca semula sesi media setiap beberapa saat. */
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            pollSession();
            h.postDelayed(this, POLL_MS);
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

        // Latar: gambar Bing, dua lapisan untuk fade silang, digelapkan supaya jam jelas.
        bgA = new ImageView(this);
        bgB = new ImageView(this);
        for (ImageView v : new ImageView[] {bgA, bgB}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            root.addView(v, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        View scrim = new View(this);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(SCRIM_NORMAL);
        root.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Satu blok di kiri: jam, tarikh, Hijri, kemudian baris lagu.
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.START);

        clock = new TextView(this);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(120);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        box.addView(clock);

        TextView date = new TextView(this);
        date.setTextColor(0xE6FFFFFF);
        date.setTextSize(26);
        date.setText(dateFmt.format(new Date()));
        box.addView(date);

        String hijri = getIntent().getStringExtra(EXTRA_HIJRI);
        if (hijri != null && !hijri.isEmpty()) {
            TextView hj = new TextView(this);
            hj.setTextColor(0x99FFFFFF);
            hj.setTextSize(20);
            hj.setText(hijri);
            hj.setPadding(0, dp(4), 0, 0);
            box.addView(hj);
        }

        // Tanpa kotak, tanpa gambar album: penyanyi dan tajuk sahaja.
        song = new TextView(this);
        song.setTextColor(0xF2FFFFFF);
        song.setTextSize(22);
        song.setSingleLine(true);
        song.setEllipsize(TextUtils.TruncateAt.END);
        song.setPadding(0, dp(22), 0, 0);
        song.setMaxWidth(dp(760));
        box.addView(song);
        showSong(getIntent().getStringExtra(EXTRA_TITLE), getIntent().getStringExtra(EXTRA_ARTIST));

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        boxLp.setMargins(dp(88), 0, 0, 0);
        root.addView(box, boxLp);
        block = box;

        // Lapisan malap (selepas 10 minit)
        dimOverlay = new View(this);
        dimOverlay.setBackgroundColor(Color.BLACK);
        dimOverlay.setAlpha(0f);
        root.addView(dimOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    /** "♪ Penyanyi · Tajuk". Kosong kalau tiada apa-apa yang sedang main. */
    private void showSong(String title, String artist) {
        boolean hasTitle = title != null && !title.isEmpty();
        boolean hasArtist = artist != null && !artist.isEmpty();
        if (!hasTitle && !hasArtist) {
            song.setVisibility(View.GONE);
            return;
        }
        String text = hasTitle && hasArtist ? artist + " \u00b7 " + title : hasTitle ? title : artist;
        song.setText("\u266a  " + text);
        song.setVisibility(View.VISIBLE);
    }

    @Override protected void onResume() {
        super.onResume();
        stopTasks(); // elak tugasan berganda kalau onResume dipanggil berulang
        h.post(tick);
        h.post(bgTask);
        h.postDelayed(pollTask, 1500); // baca lagu awal; kemudian setiap POLL_MS
        h.postDelayed(shiftTask, 120_000);
        h.postDelayed(dimTask, 600_000);
    }

    @Override protected void onPause() {
        super.onPause();
        stopTasks();
    }

    private void stopTasks() {
        h.removeCallbacks(tick);
        h.removeCallbacks(bgTask);
        h.removeCallbacks(pollTask);
        h.removeCallbacks(shiftTask);
        h.removeCallbacks(dimTask);
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

    private void pollSession() {
        if (!polling.compareAndSet(false, true)) return;
        final android.content.Context ctx = getApplicationContext();
        new Thread(() -> {
            NowPlayingPanel.Info i = null;
            try {
                i = NowPlayingPanel.readSession(ctx);
            } catch (Throwable ignored) {
            } finally {
                polling.set(false); // sentiasa dilepaskan, walau apa pun berlaku
            }
            final NowPlayingPanel.Info result = i;
            android.util.Log.d("SS", "poll: " + (result == null ? "null" : result.artist + " | " + result.title));
            h.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == null) showSong(null, null);
                else showSong(result.title, result.artist);
            });
        }, "ss-song-poll").start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
