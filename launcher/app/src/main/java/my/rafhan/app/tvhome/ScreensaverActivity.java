package my.rafhan.app.tvhome;

import android.app.Activity;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Screensaver: jam besar di kiri, tarikh dan Hijri di bawahnya, kemudian satu baris
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
    private static final long POLL_MS = 8000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView clock;
    private TextView song;
    private View block;
    private View dimOverlay;
    private int shiftIdx = 0;
    private boolean polling;
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
        h.post(tick);
        h.postDelayed(pollTask, POLL_MS);
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

    private void pollSession() {
        if (polling) return;
        polling = true;
        final android.content.Context ctx = getApplicationContext();
        new Thread(() -> {
            NowPlayingPanel.Info i = NowPlayingPanel.readSession(ctx);
            h.post(() -> {
                polling = false;
                if (isFinishing()) return;
                if (i == null) showSong(null, null);
                else showSong(i.title, i.artist);
            });
        }, "ss-song-poll").start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
